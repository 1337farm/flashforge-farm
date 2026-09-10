package com.flashforge.farm.modelrepo.moderation;

import android.util.Log;

import com.flashforge.farm.modelrepo.SecurityLogger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Community trust aggregation system.
 * Tracks and aggregates trust decisions from multiple users in the community.
 * Provides collective intelligence about publisher trustworthiness.
 */
public class CommunityTrust {
    private static final String TAG = "CommunityTrust";
    private static final String VERSION = "1.0";
    
    private final Map<String, TrustMetrics> trustMetrics = new HashMap<>();
    private final Gson gson = new Gson();
    
    /**
     * Trust metrics for a single publisher.
     */
    public static class TrustMetrics {
        public int trustCount = 0;
        public int blockCount = 0;
        public int flagCount = 0;
        public double trustScore = 0.0;
        public long lastUpdated = 0;
        public String pubkey;
        
        // Track individual contributors for transparency
        public final Map<String, Integer> trustContributors = new HashMap<>();
        public final Map<String, Integer> blockContributors = new HashMap<>();
        public final Map<String, Integer> flagContributors = new HashMap<>();
        
        public TrustMetrics(String pubkey) {
            this.pubkey = pubkey;
        }
        
        /**
         * Add a trust vote from a specific device.
         */
        public void addTrust(String deviceId) {
            if (!trustContributors.containsKey(deviceId)) {
                trustContributors.put(deviceId, 0);
            }
            trustContributors.put(deviceId, trustContributors.get(deviceId) + 1);
            trustCount = trustContributors.values().stream().mapToInt(Integer::intValue).sum();
            
            // Remove from block contributors if present
            blockContributors.remove(deviceId);
            blockCount = blockContributors.values().stream().mapToInt(Integer::intValue).sum();
            
            updateScore();
            lastUpdated = System.currentTimeMillis();
        }
        
        /**
         * Add a block vote from a specific device.
         */
        public void addBlock(String deviceId) {
            if (!blockContributors.containsKey(deviceId)) {
                blockContributors.put(deviceId, 0);
            }
            blockContributors.put(deviceId, blockContributors.get(deviceId) + 1);
            blockCount = blockContributors.values().stream().mapToInt(Integer::intValue).sum();
            
            // Remove from trust contributors if present
            trustContributors.remove(deviceId);
            trustCount = trustContributors.values().stream().mapToInt(Integer::intValue).sum();
            
            updateScore();
            lastUpdated = System.currentTimeMillis();
        }
        
        /**
         * Add a flag from a specific device.
         */
        public void addFlag(String deviceId) {
            if (!flagContributors.containsKey(deviceId)) {
                flagContributors.put(deviceId, 0);
            }
            flagContributors.put(deviceId, flagContributors.get(deviceId) + 1);
            flagCount = flagContributors.values().stream().mapToInt(Integer::intValue).sum();
            
            updateScore();
            lastUpdated = System.currentTimeMillis();
        }
        
        /**
         * Remove a trust vote from a specific device.
         */
        public void removeTrust(String deviceId) {
            if (trustContributors.containsKey(deviceId)) {
                int count = trustContributors.get(deviceId);
                if (count <= 1) {
                    trustContributors.remove(deviceId);
                } else {
                    trustContributors.put(deviceId, count - 1);
                }
                trustCount = trustContributors.values().stream().mapToInt(Integer::intValue).sum();
                updateScore();
                lastUpdated = System.currentTimeMillis();
            }
        }
        
        /**
         * Remove a block vote from a specific device.
         */
        public void removeBlock(String deviceId) {
            if (blockContributors.containsKey(deviceId)) {
                int count = blockContributors.get(deviceId);
                if (count <= 1) {
                    blockContributors.remove(deviceId);
                } else {
                    blockContributors.put(deviceId, count - 1);
                }
                blockCount = blockContributors.values().stream().mapToInt(Integer::intValue).sum();
                updateScore();
                lastUpdated = System.currentTimeMillis();
            }
        }
        
        /**
         * Remove a flag from a specific device.
         */
        public void removeFlag(String deviceId) {
            if (flagContributors.containsKey(deviceId)) {
                int count = flagContributors.get(deviceId);
                if (count <= 1) {
                    flagContributors.remove(deviceId);
                } else {
                    flagContributors.put(deviceId, count - 1);
                }
                flagCount = flagContributors.values().stream().mapToInt(Integer::intValue).sum();
                updateScore();
                lastUpdated = System.currentTimeMillis();
            }
        }
        
        /**
         * Update the trust score based on current metrics.
         */
        private void updateScore() {
            int total = trustCount + blockCount + flagCount;
            if (total == 0) {
                trustScore = 0.0;
            } else {
                // Weighted scoring: trust adds, blocks subtract more, flags subtract some
                trustScore = (trustCount - blockCount * 2.0 - flagCount * 0.5) / (double) total;
            }
        }
        
        /**
         * Get a display-friendly trust level string.
         */
        public String getDisplayLevel() {
            if (trustScore > 0.7) return "Highly Trusted";
            if (trustScore > 0.3) return "Trusted";
            if (trustScore > -0.3) return "Neutral";
            if (trustScore > -0.7) return "Distrusted";
            return "Highly Distrusted";
        }
        
        /**
         * Check if this publisher should be recommended.
         */
        public boolean isRecommended() {
            return trustScore > 0.5 && trustCount >= 3;
        }
        
        /**
         * Check if this publisher should be warned about.
         */
        public boolean isWarned() {
            return trustScore < -0.3 || blockCount >= 2;
        }
        
        /**
         * Check if this publisher should be blocked by default.
         */
        public boolean isBlockedByDefault() {
            return trustScore < -0.7 || blockCount >= 5;
        }
        
        /**
         * Get the number of unique contributors.
         */
        public int getUniqueContributors() {
            java.util.Set<String> all = new java.util.HashSet<>();
            all.addAll(trustContributors.keySet());
            all.addAll(blockContributors.keySet());
            all.addAll(flagContributors.keySet());
            return all.size();
        }
        
        /**
         * Check if a specific device has contributed to this publisher's trust.
         */
        public boolean hasContributionFrom(String deviceId) {
            return trustContributors.containsKey(deviceId) || 
                   blockContributors.containsKey(deviceId) || 
                   flagContributors.containsKey(deviceId);
        }
    }
    
    /**
     * Callback for community trust operations.
     */
    public interface CommunityTrustCallback {
        void onSuccess();
        void onError(String error);
    }
    
    public CommunityTrust() {
    }
    
    /**
     * Record a trust decision from a device.
     */
    public void recordTrust(String pubkeyHex, String deviceId, boolean isTrust) {
        String key = normalizeKey(pubkeyHex);
        TrustMetrics metrics = trustMetrics.computeIfAbsent(key, k -> new TrustMetrics(k));
        
        if (isTrust) {
            metrics.addTrust(deviceId);
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                    "Community trust recorded",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkeyHex) + 
                    ", Device: " + SecurityLogger.truncateHash(deviceId) + 
                    ", Action: TRUST");
        } else {
            metrics.addBlock(deviceId);
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                    "Community distrust recorded",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkeyHex) + 
                    ", Device: " + SecurityLogger.truncateHash(deviceId) + 
                    ", Action: BLOCK");
        }
    }
    
    /**
     * Record a flag from a device.
     */
    public void recordFlag(String pubkeyHex, String deviceId) {
        String key = normalizeKey(pubkeyHex);
        TrustMetrics metrics = trustMetrics.computeIfAbsent(key, k -> new TrustMetrics(k));
        metrics.addFlag(deviceId);
        
        SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.MODERATION,
                "Community flag recorded",
                "Pubkey: " + SecurityLogger.truncateKey(pubkeyHex) + 
                ", Device: " + SecurityLogger.truncateHash(deviceId));
    }
    
    /**
     * Get trust metrics for a publisher.
     */
    public TrustMetrics getMetrics(String pubkeyHex) {
        return trustMetrics.get(normalizeKey(pubkeyHex));
    }
    
    /**
     * Get the trust score for a publisher.
     */
    public double getTrustScore(String pubkeyHex) {
        TrustMetrics metrics = trustMetrics.get(normalizeKey(pubkeyHex));
        return metrics != null ? metrics.trustScore : 0.0;
    }
    
    /**
     * Get the display level for a publisher.
     */
    public String getDisplayLevel(String pubkeyHex) {
        TrustMetrics metrics = trustMetrics.get(normalizeKey(pubkeyHex));
        return metrics != null ? metrics.getDisplayLevel() : "Neutral";
    }
    
    /**
     * Check if a publisher is recommended by the community.
     */
    public boolean isRecommended(String pubkeyHex) {
        TrustMetrics metrics = trustMetrics.get(normalizeKey(pubkeyHex));
        return metrics != null && metrics.isRecommended();
    }
    
    /**
     * Check if a publisher is warned about by the community.
     */
    public boolean isWarned(String pubkeyHex) {
        TrustMetrics metrics = trustMetrics.get(normalizeKey(pubkeyHex));
        return metrics != null && metrics.isWarned();
    }
    
    /**
     * Check if a publisher should be blocked by default based on community trust.
     */
    public boolean isBlockedByDefault(String pubkeyHex) {
        TrustMetrics metrics = trustMetrics.get(normalizeKey(pubkeyHex));
        return metrics != null && metrics.isBlockedByDefault();
    }
    
    /**
     * Get all publishers with community trust data.
     */
    public List<TrustMetrics> getAllMetrics() {
        return new ArrayList<>(trustMetrics.values());
    }
    
    /**
     * Get the number of publishers tracked.
     */
    public int getPublisherCount() {
        return trustMetrics.size();
    }
    
    /**
     * Normalize a public key for use as a map key.
     */
    private static String normalizeKey(String pubkeyHex) {
        if (pubkeyHex == null || pubkeyHex.isEmpty()) {
            return "";
        }
        return pubkeyHex.trim().toLowerCase();
    }
    
    /**
     * Export community trust data to JSON.
     */
    public String exportToJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("version", VERSION);
        obj.addProperty("exportedAt", System.currentTimeMillis());
        obj.addProperty("publisherCount", trustMetrics.size());
        
        JsonArray publishersArray = new JsonArray();
        for (TrustMetrics metrics : trustMetrics.values()) {
            JsonObject metricsObj = new JsonObject();
            metricsObj.addProperty("pubkey", metrics.pubkey);
            metricsObj.addProperty("trustCount", metrics.trustCount);
            metricsObj.addProperty("blockCount", metrics.blockCount);
            metricsObj.addProperty("flagCount", metrics.flagCount);
            metricsObj.addProperty("trustScore", metrics.trustScore);
            metricsObj.addProperty("lastUpdated", metrics.lastUpdated);
            
            // Export contributors
            JsonObject trustObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : metrics.trustContributors.entrySet()) {
                trustObj.addProperty(entry.getKey(), entry.getValue());
            }
            metricsObj.add("trustContributors", trustObj);
            
            JsonObject blockObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : metrics.blockContributors.entrySet()) {
                blockObj.addProperty(entry.getKey(), entry.getValue());
            }
            metricsObj.add("blockContributors", blockObj);
            
            JsonObject flagObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : metrics.flagContributors.entrySet()) {
                flagObj.addProperty(entry.getKey(), entry.getValue());
            }
            metricsObj.add("flagContributors", flagObj);
            
            publishersArray.add(metricsObj);
        }
        obj.add("publishers", publishersArray);
        
        return gson.toJson(obj);
    }
    
    /**
     * Export community trust data to bytes.
     */
    public byte[] exportToBytes() {
        return exportToJson().getBytes(StandardCharsets.UTF_8);
    }
    
    /**
     * Import community trust data from JSON.
     */
    public void importFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            
            if (!VERSION.equals(obj.get("version").getAsString())) {
                Log.w(TAG, "Version mismatch: expected " + VERSION + ", got " + obj.get("version").getAsString());
            }
            
            if (obj.has("publishers") && obj.get("publishers").isJsonArray()) {
                for (JsonElement element : obj.get("publishers").getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        importMetrics(element.getAsJsonObject());
                    }
                }
            }
            
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                    "Community trust data imported",
                    "Publishers: " + trustMetrics.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to import community trust data", e);
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.TRUST,
                    "Failed to import community trust",
                    "Error: " + e.getMessage());
        }
    }
    
    /**
     * Import community trust data from bytes.
     */
    public void importFromBytes(byte[] data) {
        if (data != null && data.length > 0) {
            importFromJson(new String(data, StandardCharsets.UTF_8));
        }
    }
    
    /**
     * Import a single publisher's metrics from JSON.
     */
    private void importMetrics(JsonObject metricsObj) {
        String pubkey = metricsObj.has("pubkey") ? metricsObj.get("pubkey").getAsString() : "";
        if (pubkey.isEmpty()) {
            return;
        }
        
        TrustMetrics metrics = trustMetrics.computeIfAbsent(normalizeKey(pubkey), k -> new TrustMetrics(pubkey));
        
        metrics.trustCount = metricsObj.has("trustCount") ? metricsObj.get("trustCount").getAsInt() : 0;
        metrics.blockCount = metricsObj.has("blockCount") ? metricsObj.get("blockCount").getAsInt() : 0;
        metrics.flagCount = metricsObj.has("flagCount") ? metricsObj.get("flagCount").getAsInt() : 0;
        metrics.trustScore = metricsObj.has("trustScore") ? metricsObj.get("trustScore").getAsDouble() : 0.0;
        metrics.lastUpdated = metricsObj.has("lastUpdated") ? metricsObj.get("lastUpdated").getAsLong() : 0;
        
        // Import contributors
        if (metricsObj.has("trustContributors") && metricsObj.get("trustContributors").isJsonObject()) {
            JsonObject trustObj = metricsObj.get("trustContributors").getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : trustObj.entrySet()) {
                metrics.trustContributors.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        
        if (metricsObj.has("blockContributors") && metricsObj.get("blockContributors").isJsonObject()) {
            JsonObject blockObj = metricsObj.get("blockContributors").getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : blockObj.entrySet()) {
                metrics.blockContributors.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        
        if (metricsObj.has("flagContributors") && metricsObj.get("flagContributors").isJsonObject()) {
            JsonObject flagObj = metricsObj.get("flagContributors").getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : flagObj.entrySet()) {
                metrics.flagContributors.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
    }
    
    /**
     * Merge community trust data from another source.
     */
    public void mergeFrom(CommunityTrust other) {
        if (other == null) {
            return;
        }
        
        for (TrustMetrics otherMetrics : other.trustMetrics.values()) {
            String key = normalizeKey(otherMetrics.pubkey);
            TrustMetrics ourMetrics = trustMetrics.computeIfAbsent(key, k -> new TrustMetrics(k));
            
            // Merge trust contributors
            for (Map.Entry<String, Integer> entry : otherMetrics.trustContributors.entrySet()) {
                ourMetrics.trustContributors.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            
            // Merge block contributors
            for (Map.Entry<String, Integer> entry : otherMetrics.blockContributors.entrySet()) {
                ourMetrics.blockContributors.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            
            // Merge flag contributors
            for (Map.Entry<String, Integer> entry : otherMetrics.flagContributors.entrySet()) {
                ourMetrics.flagContributors.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            
            // Recalculate counts and score
            ourMetrics.trustCount = ourMetrics.trustContributors.values().stream().mapToInt(Integer::intValue).sum();
            ourMetrics.blockCount = ourMetrics.blockContributors.values().stream().mapToInt(Integer::intValue).sum();
            ourMetrics.flagCount = ourMetrics.flagContributors.values().stream().mapToInt(Integer::intValue).sum();
            ourMetrics.updateScore();
            
            if (otherMetrics.lastUpdated > ourMetrics.lastUpdated) {
                ourMetrics.lastUpdated = otherMetrics.lastUpdated;
            }
        }
        
        SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                "Community trust data merged",
                "Publishers: " + other.trustMetrics.size());
    }
    
    /**
     * Clear all community trust data.
     */
    public void clear() {
        trustMetrics.clear();
        SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                "Community trust data cleared");
    }
    
    /**
     * Get a summary of community trust data.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Community Trust Summary\n");
        sb.append("=======================\n\n");
        sb.append("Total Publishers: ").append(trustMetrics.size()).append("\n\n");
        
        int highlyTrusted = 0, trusted = 0, neutral = 0, distrusted = 0, highlyDistrusted = 0;
        for (TrustMetrics metrics : trustMetrics.values()) {
            String level = metrics.getDisplayLevel();
            switch (level) {
                case "Highly Trusted": highlyTrusted++; break;
                case "Trusted": trusted++; break;
                case "Neutral": neutral++; break;
                case "Distrusted": distrusted++; break;
                case "Highly Distrusted": highlyDistrusted++; break;
            }
        }
        
        sb.append("Highly Trusted: ").append(highlyTrusted).append("\n");
        sb.append("Trusted: ").append(trusted).append("\n");
        sb.append("Neutral: ").append(neutral).append("\n");
        sb.append("Distrusted: ").append(distrusted).append("\n");
        sb.append("Highly Distrusted: ").append(highlyDistrusted).append("\n");
        
        return sb.toString();
    }
    
    /**
     * Get the top trusted publishers.
     */
    public List<TrustMetrics> getTopTrusted(int limit) {
        List<TrustMetrics> sorted = new ArrayList<>(trustMetrics.values());
        sorted.sort((a, b) -> Double.compare(b.trustScore, a.trustScore));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }
    
    /**
     * Get the most distrusted publishers.
     */
    public List<TrustMetrics> getMostDistrusted(int limit) {
        List<TrustMetrics> sorted = new ArrayList<>(trustMetrics.values());
        sorted.sort((a, b) -> Double.compare(a.trustScore, b.trustScore));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }
}
