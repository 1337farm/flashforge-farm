package com.flashforge.farm.gallery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded in-memory cache of gallery preview meshes, keyed by item id.
 *
 * Opening the gallery must not re-parse (or re-read) every model each time:
 * disk cache hits are re-read per open, so a second visit re-loads all
 * models into memory again. This LRU keeps the hot set resident (evicting
 * oldest first) with no Android dependencies, so it is unit-tested on JVM.
 * Thread-safe for concurrent row builds.
 */
public final class PreviewMemoryCache {
    private final int maxEntries;
    private final LinkedHashMap<String, GalleryMesh> map;

    public PreviewMemoryCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.map = new LinkedHashMap<String, GalleryMesh>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, GalleryMesh> eldest) {
                return size() > PreviewMemoryCache.this.maxEntries;
            }
        };
    }

    public synchronized GalleryMesh get(String key) {
        return map.get(key);
    }

    public synchronized void put(String key, GalleryMesh mesh) {
        if (key == null || mesh == null) return;
        map.put(key, mesh);
    }

    public synchronized void invalidate(String key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }
}
