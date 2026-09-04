package com.flashforge.farm.iroh;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.flashforge.farm.utils.PrinterFleetManager;

/**
 * Background service for the p2p overlay dial.
 *
 * <p>A provisioned printer carries a unique identity (a public key / access code). This
 * service is the host-side dialer: it accepts a {@code PRINTER_PUB_KEY} extra and, when an
 * on-device Iroh node is available, dials the printer over the overlay to forward the
 * printer's TCP API (port 8898) to loopback and stream JSON telemetry.</p>
 *
 * <p>Today the actual native Iroh node is not bundled in the APK (no Rust dependency), so
 * the dial is a documented placeholder that logs the intent. The USB provisioning manager
 * writes the identity and bootstrapper so the printer side is ready; wiring the on-device
 * node here is the remaining step once a native Iroh library is added.</p>
 */
public class IrohP2PService extends Service {
    private static final String TAG = "IrohP2PService";

    public static final String EXTRA_PRINTER_PUB_KEY = "PRINTER_PUB_KEY";
    public static final String EXTRA_PRINTER_PUB_URL  = "PRINTER_PUB_URL";

    /** Callback for the static {@link #dial(Context, String, DialCallback)} helper. */
    public interface DialCallback {
        void onResult(boolean ok, String msg);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String pubKey = intent != null ? intent.getStringExtra(EXTRA_PRINTER_PUB_KEY) : null;
        if (pubKey == null) return START_NOT_STICKY;

        new Thread(() -> dial(pubKey), "iroh-dial").start();
        return START_STICKY;
    }

    private void dial(String pubKey) {
        Log.i(TAG, "Dialing printer by unique key: " + pubKey);
        PrinterFleetManager.Printer p = PrinterFleetManager.findByAccessCode(pubKey);
        String target = p != null ? p.name + " (" + p.ipOrUrl + ")" : pubKey;
        // TODO: IrohNode.start().dial(pubKey) once a native Iroh library is bundled.
        Log.i(TAG, "P2P dial target resolved to: " + target +
                " (native Iroh node not yet bundled; tunnel + telemetry channels pending)");
    }

    /**
     * Convenience entry-point for fragments: starts the service with the printer's
     * access code and notifies the caller when the (placeholder) dial completes.
     */
    public static void dial(Context ctx, String accessCode, String ipOrUrl, DialCallback cb) {
        if (ctx == null || accessCode == null) {
            if (cb != null) cb.onResult(false, "missing context or access code");
            return;
        }
        Intent intent = new Intent(ctx, IrohP2PService.class);
        intent.putExtra(EXTRA_PRINTER_PUB_KEY, accessCode);
        intent.putExtra(EXTRA_PRINTER_PUB_URL, ipOrUrl);
        ctx.startService(intent);
        // Placeholder: native Iroh node not yet bundled.
        if (cb != null) cb.onResult(false, "native Iroh node not bundled in this build");
    }
}