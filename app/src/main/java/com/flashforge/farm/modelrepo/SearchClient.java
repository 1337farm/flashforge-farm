package com.flashforge.farm.modelrepo;

import android.util.Log;

import com.flashforge.farm.modelrepo.profile.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchClient {
    private static final String TAG = "SearchClient";

    private final IrohModelTransport transport;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, List<SearchResult>> queryCache = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> profileCache = new ConcurrentHashMap<>();
    private final List<SearchListener> listeners = new CopyOnWriteArrayList<>();

    public interface SearchListener {
        void onResults(String query, List<SearchResult> results);
        void onError(String query, String error);
    }

    public static class SearchResult {
        public final String modelHash;
        public final String title;
        public final String description;
        public final String designerName;
        public final String designerPubkey;
        public final String category;
        public final List<String> tags;
        public final long fileCount;
        public final long totalSize;

        public SearchResult(String modelHash, String title, String description,
                           String designerName, String designerPubkey,
                           String category, List<String> tags,
                           long fileCount, long totalSize) {
            this.modelHash = modelHash;
            this.title = title;
            this.description = description;
            this.designerName = designerName;
            this.designerPubkey = designerPubkey;
            this.category = category;
            this.tags = tags;
            this.fileCount = fileCount;
            this.totalSize = totalSize;
        }
    }

    public SearchClient(IrohModelTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport required");
        }
        this.transport = transport;
    }

    public void addListener(SearchListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SearchListener listener) {
        listeners.remove(listener);
    }

    public void searchAsync(String keyword, SearchCallback callback) {
        executor.execute(() -> {
            try {
                List<SearchResult> results = search(keyword);
                queryCache.put(keyword.toLowerCase(), results);
                if (callback != null) {
                    callback.onResults(results);
                }
                for (SearchListener l : listeners) {
                    l.onResults(keyword, results);
                }
            } catch (Exception e) {
                Log.e(TAG, "Search failed for: " + keyword, e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
                for (SearchListener l : listeners) {
                    l.onError(keyword, e.getMessage());
                }
            }
        });
    }

    public List<SearchResult> search(String keyword) throws Exception {
        String cacheKey = keyword.toLowerCase();
        if (queryCache.containsKey(cacheKey)) {
            return queryCache.get(cacheKey);
        }
        List<String> hashes = transport.searchAll(keyword);
        List<SearchResult> results = new ArrayList<>();
        for (String hash : hashes) {
            try {
                SearchResult result = getModel(hash);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get metadata for " + hash, e);
            }
        }
        queryCache.put(cacheKey, results);
        return results;
    }

    public SearchResult getModel(String modelHashHex) throws Exception {
        String metadataJson = transport.getMetadata(modelHashHex);
        return parseSearchResult(modelHashHex, metadataJson);
    }

    public String publishModel(String modelJson, List<byte[]> fileDatas) throws Exception {
        String ticket = transport.publishModel(modelJson, fileDatas);
        queryCache.clear();
        return ticket;
    }

    public String syncAnnounce() throws Exception {
        return transport.syncAnnounce();
    }

    public SyncResult syncMerge(String ticket) throws Exception {
        String res = transport.syncMerge(ticket);
        queryCache.clear();
        SyncResult parsed = SyncResult.parse(res);
        for (String pt : parsed.profileTickets) {
            resolveProfileTicket(pt);
        }
        return parsed;
    }

    public UserProfile fetchProfile(String pubkeyHex, String profileTicket) {
        if (pubkeyHex == null || profileTicket == null) {
            return null;
        }
        String key = pubkeyHex.trim().toLowerCase();
        UserProfile cached = profileCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] raw = transport.blobFetch(profileTicket);
            UserProfile p = UserProfile.parse(raw, pubkeyHex);
            profileCache.put(key, p);
            return p;
        } catch (Exception e) {
            Log.w(TAG, "No profile for " + key, e);
            return null;
        }
    }

    private void resolveProfileTicket(String profileTicket) {
        try {
            byte[] raw = transport.blobFetch(profileTicket);
            UserProfile probe = UserProfile.parse(raw, null);
            if (probe != null && probe.pubkey != null && !probe.pubkey.isEmpty()) {
                profileCache.put(probe.pubkey.toLowerCase(), probe);
            }
        } catch (Exception ignored) {
        }
    }

    public List<String> knownPeers() throws Exception {
        return transport.knownPeers();
    }

    public String profileLabel(String designerName, String pubkeyHex) {
        String k = pubkeyHex == null ? "" : pubkeyHex.trim().toLowerCase();
        UserProfile verified = profileCache.get(k);
        String name;
        String mark;
        if (verified != null && verified.name != null && !verified.name.isEmpty()) {
            name = verified.name;
            mark = "verified";
        } else {
            name = (designerName == null || designerName.isEmpty()) ? "anon" : designerName;
            mark = "unverified";
        }
        String shortKey = k.length() <= 12 ? k : k.substring(0, 8) + ".." + k.substring(k.length() - 4);
        return name + " [" + mark + "] - " + shortKey;
    }

    public UserProfile parseProfile(byte[] raw, String pubkeyHex) {
        try {
            return UserProfile.parse(raw, pubkeyHex);
        } catch (Exception e) {
            return null;
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private SearchResult parseSearchResult(String modelHash, String metadataJson) {
        try {
            com.google.gson.JsonObject meta = com.google.gson.JsonParser.parseString(metadataJson).getAsJsonObject();
            String title = meta.has("title") ? meta.get("title").getAsString() : "Unknown";
            String description = meta.has("description") ? meta.get("description").getAsString() : "";
            String category = meta.has("category") ? meta.get("category").getAsString() : "";
            String designerName = "";
            String designerPubkey = "";
            if (meta.has("designer")) {
                com.google.gson.JsonObject designer = meta.getAsJsonObject("designer");
                designerName = designer.has("name") ? designer.get("name").getAsString() : "";
                designerPubkey = designer.has("pubkey") ? designer.get("pubkey").getAsString() : "";
            }
            List<String> tags = new ArrayList<>();
            if (meta.has("tags")) {
                for (com.google.gson.JsonElement e : meta.getAsJsonArray("tags")) {
                    tags.add(e.getAsString());
                }
            }
            long fileCount = 0;
            long totalSize = 0;
            if (meta.has("files")) {
                fileCount = meta.getAsJsonArray("files").size();
            }
            if (meta.has("sizes")) {
                for (com.google.gson.JsonElement e : meta.getAsJsonArray("sizes")) {
                    try {
                        totalSize += e.getAsLong();
                    } catch (Exception ignored) {
                    }
                }
            }
            return new SearchResult(modelHash, title, description, designerName, designerPubkey,
                    category, tags, fileCount, totalSize);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse metadata for " + modelHash, e);
            return null;
        }
    }

    public interface SearchCallback {
        void onResults(List<SearchResult> results);
        void onError(String error);
    }
}
