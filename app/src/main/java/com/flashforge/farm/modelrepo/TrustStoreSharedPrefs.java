package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * SharedPreferences-based storage for TrustStore.
 * Provides persistent storage of trust decisions across app sessions.
 */
public class TrustStoreSharedPrefs implements TrustStore.Storage {
    private static final String TAG = "TrustStoreSharedPrefs";
    private static final String PREF_NAME = "farm_trust_store";
    
    private static final String PREFIX_BLOCK = "block_";
    private static final String PREFIX_TRUST = "trust_";
    private static final String PREFIX_SEEN = "seen_";
    
    private final SharedPreferences prefs;
    
    public TrustStoreSharedPrefs(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "TrustStoreSharedPrefs initialized");
    }
    
    @Override
    public boolean getBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }
    
    @Override
    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }
    
    @Override
    public long getLong(String key, long def) {
        return prefs.getLong(key, def);
    }
    
    @Override
    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }
    
    /**
     * Export all trust data to a portable format.
     * Useful for backup/restore or sync across devices.
     */
    public String exportTrustData() {
        Set<String> keys = prefs.getAll().keySet();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(key).append("\":");
            Object value = prefs.getAll().get(key);
            if (value instanceof Boolean) {
                sb.append((Boolean) value);
            } else if (value instanceof Long) {
                sb.append((Long) value);
            } else if (value instanceof Integer) {
                sb.append((Integer) value);
            } else if (value instanceof Float) {
                sb.append((Float) value);
            } else if (value instanceof String) {
                sb.append("\"").append(escapeJson((String) value)).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Import trust data from exported format.
     */
    public void importTrustData(String json) {
        // Simple implementation - in practice, use Gson or similar
        // This is a placeholder for the actual JSON parsing logic
        try {
            SharedPreferences.Editor editor = prefs.edit();
            // Parse JSON and apply to editor
            // For now, just log
            Log.d(TAG, "Importing trust data: " + json.length() + " bytes");
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to import trust data", e);
        }
    }
    
    /**
     * Clear all trust data.
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "All trust data cleared");
    }
    
    /**
     * Get all blocked pubkeys.
     */
    public Set<String> getAllBlocked() {
        Set<String> blocked = new HashSet<>();
        Set<String> keys = prefs.getAll().keySet();
        for (String key : keys) {
            if (key.startsWith(PREFIX_BLOCK) && prefs.getBoolean(key, false)) {
                String pubkey = key.substring(PREFIX_BLOCK.length());
                blocked.add(pubkey);
            }
        }
        return blocked;
    }
    
    /**
     * Get all trusted pubkeys.
     */
    public Set<String> getAllTrusted() {
        Set<String> trusted = new HashSet<>();
        Set<String> keys = prefs.getAll().keySet();
        for (String key : keys) {
            if (key.startsWith(PREFIX_TRUST) && prefs.getBoolean(key, false)) {
                String pubkey = key.substring(PREFIX_TRUST.length());
                trusted.add(pubkey);
            }
        }
        return trusted;
    }
    
    /**
     * Get all seen pubkeys.
     */
    public Set<String> getAllSeen() {
        Set<String> seen = new HashSet<>();
        Set<String> keys = prefs.getAll().keySet();
        for (String key : keys) {
            if (key.startsWith(PREFIX_SEEN) && prefs.getLong(key, 0) > 0) {
                String pubkey = key.substring(PREFIX_SEEN.length());
                seen.add(pubkey);
            }
        }
        return seen;
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
