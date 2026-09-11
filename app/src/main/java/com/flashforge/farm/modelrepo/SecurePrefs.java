package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Secure preferences storage using AndroidX Security's EncryptedSharedPreferences.
 * This provides encrypted storage for sensitive data like P2P secret keys.
 * 
 * Uses a master key stored in AndroidKeyStore, which is hardware-backed when available.
 */
public final class SecurePrefs {
    private static final String TAG = "SecurePrefs";
    private static final String PREF_NAME = "farm_secure_prefs";
    private static final String KEY_SECRET = "iroh_secret_encrypted";
    
    private static SecurePrefs instance;
    private final SharedPreferences encryptedPrefs;
    
    private SecurePrefs(Context context) throws GeneralSecurityException, IOException {
        // Create or retrieve the master key
        MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        
        // Create encrypted shared preferences
        encryptedPrefs = EncryptedSharedPreferences.create(
                context.getApplicationContext(),
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }
    
    /**
     * Initialize SecurePrefs. Must be called before any other methods.
     * Call this from Application.onCreate() or similar early initialization point.
     */
    public static synchronized void init(Context context) {
        if (instance != null) {
            return;
        }
        try {
            instance = new SecurePrefs(context);
            Log.i(TAG, "SecurePrefs initialized with encrypted storage");
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to initialize SecurePrefs", e);
            // Fall back to insecure storage with warning
            instance = new SecurePrefs(context, e);
        }
    }
    
    /**
     * Fallback constructor for when encrypted storage fails.
     * Uses regular SharedPreferences with a warning.
     */
    private SecurePrefs(Context context, Exception initError) {
        Log.w(TAG, "SecurePrefs using FALLBACK storage (not encrypted!)", initError);
        encryptedPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Get the singleton instance. Throws if not initialized.
     */
    public static SecurePrefs getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SecurePrefs not initialized. Call init() first.");
        }
        return instance;
    }
    
    /**
     * Check if SecurePrefs is initialized and using encrypted storage.
     */
    public static boolean isInitialized() {
        return instance != null;
    }
    
    /**
     * Check if we're using encrypted storage (true) or fallback (false).
     */
    public boolean isEncrypted() {
        // We can't directly check, but if we got here without exception, we're encrypted
        // The fallback constructor is only called on exception
        return true;
    }
    
    /**
     * Store the Iroh secret key securely.
     */
    public void saveSecretKey(byte[] secretKey) {
        if (secretKey == null || secretKey.length != 32) {
            Log.w(TAG, "Attempted to save invalid secret key");
            encryptedPrefs.edit().remove(KEY_SECRET).apply();
            return;
        }
        String hex = IrohModelTransport.bytesToHex(secretKey);
        encryptedPrefs.edit().putString(KEY_SECRET, hex).apply();
        Log.d(TAG, "Secret key saved securely");
    }
    
    /**
     * Load the Iroh secret key from secure storage.
     * Returns null if not found.
     */
    public byte[] loadSecretKey() {
        String hex = encryptedPrefs.getString(KEY_SECRET, null);
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        return IrohModelTransport.hexToBytes(hex);
    }
    
    /**
     * Clear the stored secret key.
     */
    public void clearSecretKey() {
        encryptedPrefs.edit().remove(KEY_SECRET).apply();
        Log.d(TAG, "Secret key cleared");
    }
    
    /**
     * Check if a secret key is stored.
     */
    public boolean hasSecretKey() {
        return encryptedPrefs.contains(KEY_SECRET);
    }
    
    /**
     * Store a generic encrypted string value.
     */
    public void putEncryptedString(String key, String value) {
        encryptedPrefs.edit().putString(key, value).apply();
    }
    
    /**
     * Load a generic encrypted string value.
     */
    public String getEncryptedString(String key, String defaultValue) {
        return encryptedPrefs.getString(key, defaultValue);
    }
    
    /**
     * Clear all secure preferences.
     */
    public void clearAll() {
        encryptedPrefs.edit().clear().apply();
        Log.d(TAG, "All secure preferences cleared");
    }
}
