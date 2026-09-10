package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class P2pManager {
    private static final String TAG = "P2pManager";
    private static final String PREF_MIGRATED = "iroh_migrated_v1";
    private static final String LEGACY_PREF_SECRET = "iroh_secret";

    private static IrohModelTransport transport;
    private static boolean migrating;

    private P2pManager() {
    }

    /**
     * Initialize SecurePrefs. Call this early in app lifecycle (e.g., Application.onCreate()).
     */
    public static synchronized void initSecureStorage(Context ctx) {
        if (!SecurePrefs.isInitialized()) {
            SecurePrefs.init(ctx);
            
            // Attempt to migrate from legacy plaintext storage
            try {
                String legacyHex = com.flashforge.farm.utils.Prefs.getPrefs()
                        .getString(LEGACY_PREF_SECRET, null);
                if (legacyHex != null && !legacyHex.isEmpty()) {
                    SecurePrefs.migrateFromPlaintext(ctx, legacyHex);
                    // Clear legacy storage after migration
                    com.flashforge.farm.utils.Prefs.getPrefs().edit()
                            .remove(LEGACY_PREF_SECRET).apply();
                    Log.i(TAG, "Migrated legacy secret key to secure storage");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to migrate legacy secret key", e);
            }
        }
    }

    public static synchronized IrohModelTransport transport(Context ctx) throws ModelTransport.UnavailableException {
        if (transport != null && transport.isReady()) {
            return transport;
        }
        
        // Ensure secure storage is initialized
        initSecureStorage(ctx);
        
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
        // Issue #87: verify transport encryption once the endpoint is ready.
        // Observational only (logs via SecurityLogger); never blocks startup.
        TransportSecurity.EncryptionStatus enc = TransportSecurity.verifyEncryption(t);
        if (enc != TransportSecurity.EncryptionStatus.ENABLED) {
            Log.w(TAG, "transport encryption not verified: " + enc);
        }
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
            SecurePrefs securePrefs = SecurePrefs.getInstance();
            return securePrefs.loadSecretKey();
        } catch (Exception e) {
            Log.w(TAG, "Failed to load secret from secure storage", e);
            return null;
        }
    }

    private static void saveSecret(byte[] secret) {
        try {
            SecurePrefs securePrefs = SecurePrefs.getInstance();
            securePrefs.saveSecretKey(secret);
        } catch (Exception e) {
            Log.w(TAG, "Failed to save secret to secure storage", e);
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
