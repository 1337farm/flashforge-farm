package com.flashforge.farm.modelrepo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TrustStore {
    public enum Level {
        BLOCKED,
        UNVERIFIED,
        SEEN,
        TRUSTED
    }

    public interface Storage {
        boolean getBoolean(String key, boolean def);
        void putBoolean(String key, boolean value);
        long getLong(String key, long def);
        void putLong(String key, long value);
    }

    public static class MemoryStorage implements Storage {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean getBoolean(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            map.put(key, value);
        }

        @Override
        public long getLong(String key, long def) {
            Object v = map.get(key);
            return v instanceof Long ? (Long) v : def;
        }

        @Override
        public void putLong(String key, long value) {
            map.put(key, value);
        }
    }

    private final Storage storage;
    private final Set<String> trustedRoots;

    public TrustStore(Storage storage) {
        this(storage, new HashSet<String>());
    }

    public TrustStore(Storage storage, Set<String> trustedRoots) {
        this.storage = storage;
        this.trustedRoots = trustedRoots;
    }

    private static String key(String pubkey) {
        return pubkey == null ? "" : pubkey.trim().toLowerCase();
    }

    public Level level(String pubkey) {
        String k = key(pubkey);
        if (k.isEmpty()) {
            return Level.UNVERIFIED;
        }
        if (storage.getBoolean("block:" + k, false)) {
            return Level.BLOCKED;
        }
        if (trustedRoots.contains(k) || storage.getBoolean("trust:" + k, false)) {
            return Level.TRUSTED;
        }
        if (storage.getLong("seen:" + k, 0L) > 0) {
            return Level.SEEN;
        }
        return Level.UNVERIFIED;
    }

    public void markSeen(String pubkey) {
        String k = key(pubkey);
        if (!k.isEmpty() && storage.getLong("seen:" + k, 0L) == 0) {
            storage.putLong("seen:" + k, System.currentTimeMillis());
        }
    }

    public void setTrusted(String pubkey, boolean trusted) {
        String k = key(pubkey);
        if (!k.isEmpty()) {
            storage.putBoolean("trust:" + k, trusted);
            markSeen(pubkey);
        }
    }

    public void setBlocked(String pubkey, boolean blocked) {
        String k = key(pubkey);
        if (!k.isEmpty()) {
            storage.putBoolean("block:" + k, blocked);
            markSeen(pubkey);
        }
    }

    public boolean needsConfirm(Level level) {
        return level == Level.UNVERIFIED || level == Level.SEEN;
    }
}
