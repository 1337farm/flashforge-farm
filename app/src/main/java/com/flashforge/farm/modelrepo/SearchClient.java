package com.flashforge.farm.modelrepo;

import android.util.Log;

import com.flashforge.farm.modelrepo.ModelTransport.UnavailableException;
import com.flashforge.farm.modelrepo.profile.UserProfile;
import com.flashforge.farm.modelrepo.SecurityLogger;

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
    private final Map<String, CacheEntry> queryCache = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> profileCache = new ConcurrentHashMap<>();
    private volatile String ownProfileTicket;
    
    // Cache settings
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final int MAX_CACHE_SIZE = 100;
    private final List<SearchListener> listeners = new CopyOnWriteArrayList<>();
    
    // Rate limiter for search queries
    private final RateLimiter searchRateLimiter;
    
    // Rate limiter for profile operations
    private final RateLimiter profileRateLimiter;

    public interface SearchListener {
        void onResults(String query, List<SearchResult> results);
        void onError(String query, String error);
    }
    
    /**
     * Cache entry with TTL support.
     */
    public static class CacheEntry {
        public final List<SearchResult> results;
        public final long timestamp;
        
        public CacheEntry(List<SearchResult> results) {
            this.results = results;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
        
        public boolean isExpired(long customTtlMs) {
            return System.currentTimeMillis() - timestamp > customTtlMs;
        }
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
        public final long downloadCount;
        public final double rating;
        public final int ratingCount;
        public final int flagCount;
        public final long lastRated;
        public final long lastDownloaded;
        public final String version;
        public final long createdAt;

        public SearchResult(String modelHash, String title, String description,
                           String designerName, String designerPubkey,
                           String category, List<String> tags,
                           long fileCount, long totalSize,
                           long downloadCount, double rating, int ratingCount, int flagCount,
                           long lastRated, long lastDownloaded, String version, long createdAt) {
            this.modelHash = modelHash;
            this.title = title;
            this.description = description;
            this.designerName = designerName;
            this.designerPubkey = designerPubkey;
            this.category = category;
            this.tags = tags;
            this.fileCount = fileCount;
            this.totalSize = totalSize;
            this.downloadCount = downloadCount;
            this.rating = rating;
            this.ratingCount = ratingCount;
            this.flagCount = flagCount;
            this.lastRated = lastRated;
            this.lastDownloaded = lastDownloaded;
            this.version = version;
            this.createdAt = createdAt;
        }
        
        public SearchResult(String modelHash, String title, String description,
                           String designerName, String designerPubkey,
                           String category, List<String> tags,
                           long fileCount, long totalSize) {
            this(modelHash, title, description, designerName, designerPubkey,
                 category, tags, fileCount, totalSize,
                 0, 0.0, 0, 0, 0, 0, "", 0);
        }
        
        public double getPopularityScore() {
            return downloadCount * 0.7 + ratingCount * 0.3;
        }
        
        public boolean hasRatings() {
            return ratingCount > 0;
        }
        
        public boolean isFlagged() {
            return flagCount > 0;
        }
    }

    public SearchClient(IrohModelTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport required");
        }
        this.transport = transport;
        this.searchRateLimiter = RateLimiter.forSearch();
        this.profileRateLimiter = RateLimiter.perMinute(5);
    }
    
    /**
     * Create a SearchClient with custom rate limiters.
     */
    public SearchClient(IrohModelTransport transport, RateLimiter searchRateLimiter, RateLimiter profileRateLimiter) {
        if (transport == null) {
            throw new IllegalArgumentException("transport required");
        }
        this.transport = transport;
        this.searchRateLimiter = searchRateLimiter != null ? searchRateLimiter : RateLimiter.forSearch();
        this.profileRateLimiter = profileRateLimiter != null ? profileRateLimiter : RateLimiter.perMinute(5);
    }

    public void addListener(SearchListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SearchListener listener) {
        listeners.remove(listener);
    }

    public void searchAsync(String keyword, SearchCallback callback) {
        // Check rate limit before executing search
        if (!searchRateLimiter.tryAcquire()) {
            long waitTimeMs = searchRateLimiter.getWaitTimeMs();
            String errorMsg = "Rate limit exceeded. Please wait " + (waitTimeMs / 1000) + " seconds.";
            Log.w(TAG, "Search rate limit exceeded for: " + keyword);
            SecurityLogger.logRateLimitExceeded("Search: " + keyword, waitTimeMs);
            if (callback != null) {
                callback.onError(errorMsg);
            }
            for (SearchListener l : listeners) {
                l.onError(keyword, errorMsg);
            }
            return;
        }
        
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
            } catch (Throwable e) {
                Log.e(TAG, "Search failed for: " + keyword, e);
                String msg = e.getMessage();
                if (msg == null) {
                    msg = "search failed";
                }
                if (callback != null) {
                    callback.onError(msg);
                }
                for (SearchListener l : listeners) {
                    l.onError(keyword, msg);
                }
            }
        });
    }

    public List<SearchResult> search(String keyword) throws Exception {
        String cacheKey = keyword.toLowerCase();
        
        // Check cache with TTL
        CacheEntry entry = queryCache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.CONTENT,
                    "Cache hit for query",
                    "Query: " + keyword);
            return entry.results;
        }
        
        // Check rate limit (synchronous path)
        if (!searchRateLimiter.tryAcquire()) {
            SecurityLogger.logRateLimitExceeded("Search (sync): " + keyword, searchRateLimiter.getWaitTimeMs());
            throw new UnavailableException("Search rate limit exceeded");
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
        
        // Store in cache with TTL
        queryCache.put(cacheKey, new CacheEntry(results));
        
        // Enforce cache size limit
        if (queryCache.size() > MAX_CACHE_SIZE) {
            evictOldestEntries();
        }
        
        return results;
    }

    public SearchResult getModel(String modelHashHex) throws Exception {
        String metadataJson = transport.getMetadata(modelHashHex);
        return parseSearchResult(modelHashHex, metadataJson);
    }

    public String publishModel(String modelJson, List<byte[]> fileDatas) throws Exception {
        String ticket = transport.publishModel(modelJson, fileDatas);
        
        // Invalidate cache selectively for models in this publish
        try {
            ModelMetadata metadata = ModelMetadata.parse(modelJson);
            if (metadata.files != null && !metadata.files.isEmpty()) {
                // Invalidate cache entries that might contain these files
                for (String file : metadata.files) {
                    invalidateCacheForFile(file);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse metadata for selective cache invalidation", e);
            // Fall back to clearing all cache
            queryCache.clear();
        }
        
        return ticket;
    }

    public String syncAnnounce() throws Exception {
        return transport.syncAnnounce();
    }

    public String publishLocalProfile(String name, String bio) throws Exception {
        return publishLocalProfile(name, bio, null);
    }
    
    /**
     * Publish local profile with signing.
     * If seed is provided, the profile will be signed with it.
     */
    public String publishLocalProfile(String name, String bio, byte[] seed) throws Exception {
        // Check profile rate limit
        if (!profileRateLimiter.tryAcquire()) {
            SecurityLogger.logRateLimitExceeded("Profile publish", profileRateLimiter.getWaitTimeMs());
            throw new UnavailableException("Profile publish rate limit exceeded");
        }
        
        String pubkey = transport.getEndpointId();
        if (pubkey == null || pubkey.isEmpty()) {
            throw new UnavailableException("no local endpoint id");
        }
        
        byte[] raw;
        if (seed != null && seed.length == 32) {
            // Create signed profile
            raw = UserProfile.toJsonWithSignature(pubkey, name, bio, "", null,
                    System.currentTimeMillis() / 1000L, seed);
        } else {
            // Legacy unsigned profile (for backward compatibility)
            raw = UserProfile.toJson(pubkey, name, bio, "", null,
                    System.currentTimeMillis() / 1000L);
        }
        
        UserProfile self = UserProfile.parse(raw, pubkey);
        byte[] hash = transport.storeBlob(raw);
        String ticket = transport.shareTicket(hash);
        profileCache.put(pubkey.toLowerCase(), self);
        ownProfileTicket = ticket;
        return ticket;
    }

    public String ownProfileTicket() {
        return ownProfileTicket;
    }

    public String announceWithProfile(String profileTicket) throws Exception {
        String ann = transport.syncAnnounce();
        if (profileTicket == null || profileTicket.trim().isEmpty()) {
            return ann;
        }
        return SyncResult.announceEnvelope(ann, profileTicket);
    }

    public SyncResult syncMerge(String ticket) throws Exception {
        SyncResult.AnnouncePayload payload = SyncResult.parseAnnounce(ticket);
        String engineTicket = payload.enveloped ? payload.announceTicket : ticket;
        if (engineTicket == null || engineTicket.trim().isEmpty()) {
            throw new UnavailableException("empty announce ticket");
        }
        String res = transport.syncMerge(engineTicket.trim());
        queryCache.clear();
        SyncResult parsed = SyncResult.parse(res);
        if (payload.enveloped && payload.profileTicket != null
                && !payload.profileTicket.trim().isEmpty()) {
            resolveProfileTicket(payload.profileTicket.trim());
            List<String> extra = new ArrayList<>();
            extra.add(payload.profileTicket.trim());
            return parsed.includingProfiles(extra);
        }
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
                // Verify profile signature if present
                if (probe.verifySignature()) {
                    SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.SIGNATURE,
                            "Profile signature verified",
                            "Pubkey: " + SecurityLogger.truncateKey(probe.pubkey));
                    profileCache.put(probe.pubkey.toLowerCase(), probe);
                } else {
                    Log.w(TAG, "Profile signature verification failed for " + profileTicket);
                    SecurityLogger.logSignatureVerificationFailure(probe.pubkey, "Profile signature verification failed");
                    // Still cache it but mark as unverified
                    profileCache.put(probe.pubkey.toLowerCase(), probe);
                }
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
        
        // Reset rate limiters on shutdown
        searchRateLimiter.reset();
        profileRateLimiter.reset();
    }
    
    /**
     * Get the current search rate limiter for monitoring/debugging.
     */
    public RateLimiter getSearchRateLimiter() {
        return searchRateLimiter;
    }
    
    /**
     * Get the current profile rate limiter for monitoring/debugging.
     */
    public RateLimiter getProfileRateLimiter() {
        return profileRateLimiter;
    }
    
    /**
     * Reset the search rate limiter.
     */
    public void resetSearchRateLimit() {
        searchRateLimiter.reset();
    }
    
    /**
     * Reset the profile rate limiter.
     */
    public void resetProfileRateLimit() {
        profileRateLimiter.reset();
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
            
            // Parse reputation data
            long downloadCount = 0;
            double rating = 0.0;
            int ratingCount = 0;
            int flagCount = 0;
            long lastRated = 0;
            long lastDownloaded = 0;
            String version = "";
            long createdAt = 0;
            
            if (meta.has("reputation") && meta.get("reputation").isJsonObject()) {
                com.google.gson.JsonObject reputation = meta.getAsJsonObject("reputation");
                downloadCount = reputation.has("downloadCount") ? reputation.get("downloadCount").getAsLong() : 0;
                rating = reputation.has("rating") ? reputation.get("rating").getAsDouble() : 0.0;
                ratingCount = reputation.has("ratingCount") ? reputation.get("ratingCount").getAsInt() : 0;
                flagCount = reputation.has("flagCount") ? reputation.get("flagCount").getAsInt() : 0;
                lastRated = reputation.has("lastRated") ? reputation.get("lastRated").getAsLong() : 0;
                lastDownloaded = reputation.has("lastDownloaded") ? reputation.get("lastDownloaded").getAsLong() : 0;
            }
            
            if (meta.has("version")) {
                version = meta.get("version").getAsString();
            }
            
            if (meta.has("createdAt")) {
                createdAt = meta.get("createdAt").getAsLong();
            }
            
            return new SearchResult(modelHash, title, description, designerName, designerPubkey,
                    category, tags, fileCount, totalSize,
                    downloadCount, rating, ratingCount, flagCount,
                    lastRated, lastDownloaded, version, createdAt);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse metadata for " + modelHash, e);
            return null;
        }
    }

    public interface SearchCallback {
        void onResults(List<SearchResult> results);
        void onError(String error);
    }
    
    /**
     * Callback for reputation updates.
     */
    public interface ReputationCallback {
        void onSuccess();
        void onError(String error);
    }
    
    /**
     * Record a download for a model (increments download count in reputation).
     */
    public void recordDownload(String modelHashHex, ReputationCallback callback) {
        executor.execute(() -> {
            try {
                String metadataJson = transport.getMetadata(modelHashHex);
                ModelMetadata metadata = ModelMetadata.parse(metadataJson);
                metadata.reputation.incrementDownload();
                metadata.updatedAt = System.currentTimeMillis() / 1000;
                
                // Re-publish with updated reputation
                List<byte[]> fileDatas = new ArrayList<>();
                String ticket = transport.publishModel(metadata.toJson(), fileDatas);
                
                // Invalidate cache for this model
                invalidateCacheForModel(modelHashHex);
                
                SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.CONTENT,
                        "Download recorded for model",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex));
                
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to update reputation for " + modelHashHex, e);
                SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.CONTENT,
                        "Failed to record download",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex) + ", Error: " + e.getMessage());
                if (callback != null) {
                    callback.onError("Failed to update reputation: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Rate a model (adds a rating to the model's reputation).
     */
    public void rateModel(String modelHashHex, double rating, ReputationCallback callback) {
        if (rating < 0 || rating > 5) {
            if (callback != null) {
                callback.onError("Rating must be between 0 and 5");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                String metadataJson = transport.getMetadata(modelHashHex);
                ModelMetadata metadata = ModelMetadata.parse(metadataJson);
                metadata.reputation.addRating(rating);
                metadata.updatedAt = System.currentTimeMillis() / 1000;
                
                // Re-publish with updated reputation
                List<byte[]> fileDatas = new ArrayList<>();
                String ticket = transport.publishModel(metadata.toJson(), fileDatas);
                
                // Invalidate cache for this model
                invalidateCacheForModel(modelHashHex);
                
                SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.CONTENT,
                        "Rating recorded for model",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex) + ", Rating: " + rating);
                
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to rate model " + modelHashHex, e);
                SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.CONTENT,
                        "Failed to record rating",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex) + ", Error: " + e.getMessage());
                if (callback != null) {
                    callback.onError("Failed to rate model: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Flag a model (increments flag count in reputation).
     */
    public void flagModel(String modelHashHex, ReputationCallback callback) {
        executor.execute(() -> {
            try {
                String metadataJson = transport.getMetadata(modelHashHex);
                ModelMetadata metadata = ModelMetadata.parse(metadataJson);
                metadata.reputation.flag();
                metadata.updatedAt = System.currentTimeMillis() / 1000;
                
                // Re-publish with updated reputation
                List<byte[]> fileDatas = new ArrayList<>();
                String ticket = transport.publishModel(metadata.toJson(), fileDatas);
                
                // Invalidate cache for this model
                invalidateCacheForModel(modelHashHex);
                
                SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.MODERATION,
                        "Model flagged",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex));
                
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to flag model " + modelHashHex, e);
                SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.MODERATION,
                        "Failed to flag model",
                        "Hash: " + SecurityLogger.truncateHash(modelHashHex) + ", Error: " + e.getMessage());
                if (callback != null) {
                    callback.onError("Failed to flag model: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Get reputation for a specific model.
     */
    public ModelMetadata.Reputation getReputation(String modelHashHex) {
        try {
            String metadataJson = transport.getMetadata(modelHashHex);
            ModelMetadata metadata = ModelMetadata.parse(metadataJson);
            return metadata.reputation;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get reputation for " + modelHashHex, e);
            return new ModelMetadata.Reputation();
        }
    }
    
    /**
     * Invalidate cache entries that contain a specific model.
     */
    private void invalidateCacheForModel(String modelHashHex) {
        for (String key : queryCache.keySet()) {
            CacheEntry entry = queryCache.get(key);
            if (entry != null && entry.results != null) {
                for (SearchResult result : entry.results) {
                    if (modelHashHex.equals(result.modelHash)) {
                        queryCache.remove(key);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Invalidate cache entries that contain a specific file.
     */
    private void invalidateCacheForFile(String fileName) {
        for (String key : queryCache.keySet()) {
            CacheEntry entry = queryCache.get(key);
            if (entry != null && entry.results != null) {
                for (SearchResult result : entry.results) {
                    // Check if the file is in the result's files
                    // Note: This is a simplified check - in practice, we'd need to fetch
                    // the full metadata to check all files
                    if (result != null && result.modelHash != null && 
                        fileName != null && result.modelHash.contains(fileName)) {
                        queryCache.remove(key);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Evict oldest cache entries to enforce size limit.
     */
    private void evictOldestEntries() {
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(queryCache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().timestamp, b.getValue().timestamp));
        
        int toRemove = entries.size() - MAX_CACHE_SIZE;
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            queryCache.remove(entries.get(i).getKey());
        }
        
        SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.CONTENT,
                "Cache eviction completed",
                "Removed: " + toRemove + ", Remaining: " + queryCache.size());
    }
    
    /**
     * Clean up expired cache entries.
     */
    public void cleanupCache() {
        int removedCount = 0;
        java.util.Iterator<Map.Entry<String, CacheEntry>> it = queryCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                removedCount++;
            }
        }
        
        SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.CONTENT,
                "Cache cleanup completed",
                "Removed expired: " + removedCount + ", Remaining: " + queryCache.size());
    }
    
    /**
     * Clear the entire query cache.
     */
    public void clearCache() {
        queryCache.clear();
        SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.CONTENT,
                "Query cache cleared");
    }
    
    /**
     * Get the current cache size.
     */
    public int getCacheSize() {
        return queryCache.size();
    }
    
    /**
     * Get cache statistics.
     */
    public String getCacheStats() {
        int totalEntries = queryCache.size();
        int expiredEntries = 0;
        long oldestTimestamp = Long.MAX_VALUE;
        long newestTimestamp = 0;
        
        for (CacheEntry entry : queryCache.values()) {
            if (entry.isExpired()) {
                expiredEntries++;
            }
            if (entry.timestamp < oldestTimestamp) {
                oldestTimestamp = entry.timestamp;
            }
            if (entry.timestamp > newestTimestamp) {
                newestTimestamp = entry.timestamp;
            }
        }
        
        long now = System.currentTimeMillis();
        long oldestAgeMs = now - oldestTimestamp;
        long newestAgeMs = now - newestTimestamp;
        
        return String.format("Cache Stats: %d entries, %d expired, oldest: %dms ago, newest: %dms ago",
                totalEntries, expiredEntries, oldestAgeMs, newestAgeMs);
    }
    
    /**
     * Sort search results by popularity (downloads + ratings).
     */
    public void sortByPopularity(List<SearchResult> results) {
        if (results == null) return;
        results.sort((a, b) -> Double.compare(b.getPopularityScore(), a.getPopularityScore()));
    }
    
    /**
     * Sort search results by rating.
     */
    public void sortByRating(List<SearchResult> results) {
        if (results == null) return;
        results.sort((a, b) -> {
            if (a.hasRatings() && !b.hasRatings()) return -1;
            if (!a.hasRatings() && b.hasRatings()) return 1;
            if (!a.hasRatings() && !b.hasRatings()) return 0;
            return Double.compare(b.rating, a.rating);
        });
    }
    
    /**
     * Sort search results by download count.
     */
    public void sortByDownloads(List<SearchResult> results) {
        if (results == null) return;
        results.sort((a, b) -> Long.compare(b.downloadCount, a.downloadCount));
    }
    
    /**
     * Filter search results by minimum rating.
     */
    public List<SearchResult> filterByMinRating(List<SearchResult> results, double minRating) {
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) {
            if (result.rating >= minRating) {
                filtered.add(result);
            }
        }
        return filtered;
    }
    
    /**
     * Filter search results by minimum download count.
     */
    public List<SearchResult> filterByMinDownloads(List<SearchResult> results, long minDownloads) {
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) {
            if (result.downloadCount >= minDownloads) {
                filtered.add(result);
            }
        }
        return filtered;
    }
}
