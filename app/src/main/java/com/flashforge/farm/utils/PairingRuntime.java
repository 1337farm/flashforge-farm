package com.flashforge.farm.utils;

import android.content.Context;
import android.util.Log;

import com.example.irohapp.IPairListener;
import com.example.irohapp.IrohBridge;
import com.flashforge.farm.Bus;
import com.flashforge.farm.events.NeedSnackbarEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Phone side of USB reverse-pairing (docs/usb-pairing.md).
 *
 * <p>Starts the shared iroh engine with this phone's stable node identity and
 * runs the pairing accept-loop (ALPN farm/pair/0). When a provisioned printer
 * dials in presenting a pending token, the beacon is validated and the peer
 * NodeId is bound to the fleet printer by {@link PairingService}.
 *
 * <p>All engine calls are guarded: without the irohbridge AAR (or when init
 * fails) pairing stays inactive and everything else keeps working.
 */
public final class PairingRuntime {
    private PairingRuntime() {
    }

    private static final String TAG = "PairingRuntime";
    private static boolean started;

    /** Idempotent: init engine once, then (re)start the accept-loop. Safe off the main thread. */
    public static synchronized void ensureStarted(Context ctx) {
        if (started) return;
        try {
            byte[] seed = NodeIdentity.getOrCreateSeed(ctx);
            File dir = new File(ctx.getFilesDir(), "iroh-engine");
            if (!IrohBridge.INSTANCE.initialize(dir.getAbsolutePath(), seed)) {
                Log.w(TAG, "engine initialize failed; pairing accept inactive");
                return;
            }
            started = true;
            refreshTokens();
        } catch (Throwable t) {
            Log.w(TAG, "pairing runtime unavailable", t);
        }
    }

    /** Push the current pending-token set to the engine accept-loop. Cheap and idempotent. */
    public static synchronized void refreshTokens() {
        if (!started) return;
        try {
            List<PrinterFleetManager.Printer> fleet = PrinterFleetManager.getPrinters();
            long now = System.currentTimeMillis();
            List<String> tokens = new ArrayList<>();
            if (fleet != null) {
                for (PrinterFleetManager.Printer p : fleet) {
                    if (p.pairToken != null && p.pairExpiresAt > now && tokens.size() < 64) {
                        tokens.add(p.pairToken);
                    }
                }
            }
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(tokens.get(i)).append('"');
            }
            json.append(']');
            IrohBridge.INSTANCE.pairAccept(PairingService.PAIR_ALPN, json.toString(), LISTENER);
        } catch (Throwable t) {
            Log.w(TAG, "pair accept refresh failed", t);
        }
    }

    private static final IPairListener LISTENER = new IPairListener() {
        @Override
        public void onPairResult(String peerNodeIdHex, String tokenHex, boolean ok) {
            if (!ok) return;
            PrinterFleetManager.Printer p = PairingService.onPairBeacon(peerNodeIdHex, tokenHex);
            if (p != null) {
                String name = p.name != null && !p.name.isEmpty() ? p.name : p.id;
                Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent("Printer paired: " + name));
                refreshTokens();
            }
        }
    };
}
