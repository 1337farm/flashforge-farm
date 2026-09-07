package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class P2pManager {
    private static final String TAG = "P2pManager";
    private static final String PREF_SECRET = "iroh_secret";
    private static final String PREF_MIGRATED = "iroh_migrated_v1";

    private static IrohModelTransport transport;
    private static boolean migrating;

    private P2pManager() {
    }

    public static synchronized IrohModelTransport transport(Context ctx) throws ModelTransport.UnavailableException {
        if (transport != null && transport.isReady()) {
            return transport;
        }
        Context app = ctx.getApplicationContext();
        IrohModelTransport t = new IrohModelTransport();
        byte[] secret = loadSecret();
        File dataDir = new File(app.getFilesDir(), "iroh");
        dataDir.mkdirs();
        t.initialize(secret, dataDir.getAbsolutePath());
        if (secret == null || secret.length != 32) {
            saveSecret(t.getSecretKey());
        }
        transport = t;
        return transport;
    }

    public static synchronized void shutdown() {
        if (transport != null) {
            transport.shutdown();
            transport = null;
        }
        migrating = false;
    }

    public static synchronized boolean migrateIfSeeded(Context ctx, ModelMigrator.Callback callback) {
        if (migrating) {
            return false;
        }
        if (isMigrated()) {
            return false;
        }
        File modelsDir = new File(ctx.getApplicationContext().getFilesDir(), "models");
        File[] stls = modelsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".stl"));
        if (stls == null || stls.length == 0) {
            Log.i(TAG, "no seed models present, skipping p2p migration");
            return false;
        }
        final IrohModelTransport t;
        try {
            t = transport(ctx);
        } catch (ModelTransport.UnavailableException e) {
            Log.e(TAG, "p2p unavailable for migration", e);
            return false;
        }
        migrating = true;
        final ModelMigrator.Callback outer = callback;
        new ModelMigrator(ctx.getApplicationContext(), t).migrateAll(new ModelMigrator.Callback() {
            @Override
            public void onProgress(String model, String stage, int percent) {
                if (outer != null) {
                    outer.onProgress(model, stage, percent);
                }
            }

            @Override
            public void onCompleted(String model, String modelHash) {
                if (outer != null) {
                    outer.onCompleted(model, modelHash);
                }
            }

            @Override
            public void onError(String model, String error) {
                if (outer != null) {
                    outer.onError(model, error);
                }
            }

            @Override
            public void onAllDone() {
                synchronized (P2pManager.class) {
                    migrating = false;
                }
                markMigrated();
                if (outer != null) {
                    outer.onAllDone();
                }
            }
        });
        return true;
    }

    private static byte[] loadSecret() {
        try {
            String hex = com.flashforge.farm.utils.Prefs.getPrefs().getString(PREF_SECRET, null);
            if (hex == null || hex.isEmpty()) {
                return null;
            }
            return IrohModelTransport.hexToBytes(hex);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveSecret(byte[] secret) {
        try {
            if (secret == null || secret.length != 32) {
                return;
            }
            com.flashforge.farm.utils.Prefs.getPrefs().edit()
                    .putString(PREF_SECRET, IrohModelTransport.bytesToHex(secret)).apply();
        } catch (Exception ignored) {
        }
    }

    private static boolean isMigrated() {
        try {
            return com.flashforge.farm.utils.Prefs.getPrefs().getBoolean(PREF_MIGRATED, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void markMigrated() {
        try {
            com.flashforge.farm.utils.Prefs.getPrefs().edit().putBoolean(PREF_MIGRATED, true).apply();
        } catch (Exception ignored) {
        }
    }
}
