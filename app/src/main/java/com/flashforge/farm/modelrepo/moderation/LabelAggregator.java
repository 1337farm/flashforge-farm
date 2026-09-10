package com.flashforge.farm.modelrepo.moderation;

import com.flashforge.farm.modelrepo.TrustStore;
import com.flashforge.farm.modelrepo.SecurityLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LabelAggregator {
    private final TrustStore personal;
    private CommunityTrust communityTrust;
    private final Set<String> subscribed = new HashSet<>();
    private final Map<String, ModeratorFeed.Feed> feeds = new HashMap<>();
    private final Map<String, Long> feedSeq = new HashMap<>();

    public LabelAggregator(TrustStore personal) {
        this(personal, null);
    }
    
    public LabelAggregator(TrustStore personal, CommunityTrust communityTrust) {
        this.personal = personal;
        this.communityTrust = communityTrust;
    }
    
    public void setCommunityTrust(CommunityTrust communityTrust) {
        this.communityTrust = communityTrust;
    }
    
    public CommunityTrust getCommunityTrust() {
        return communityTrust;
    }

    public void subscribe(String ownerPubkeyHex) {
        if (ownerPubkeyHex != null) {
            subscribed.add(key(ownerPubkeyHex));
        }
    }

    public void unsubscribe(String ownerPubkeyHex) {
        String k = key(ownerPubkeyHex);
        subscribed.remove(k);
        feeds.remove(k);
        feedSeq.remove(k);
    }

    public Set<String> subscriptions() {
        return new HashSet<>(subscribed);
    }

    public void updateFeed(byte[] raw) {
        ModeratorFeed.Feed temp = ModeratorFeed.verify(raw, 0);
        if (!subscribed.contains(temp.owner)) {
            throw new IllegalArgumentException("feed not subscribed: " + temp.owner);
        }
        long last = feedSeq.containsKey(temp.owner) ? feedSeq.get(temp.owner) : -1;
        ModeratorFeed.Feed feed = ModeratorFeed.verify(raw, last + 1);
        feeds.put(feed.owner, feed);
        feedSeq.put(feed.owner, feed.seq);
    }

    public boolean isBlocked(String pubkeyHex) {
        String k = key(pubkeyHex);
        if (k.isEmpty()) {
            return false;
        }
        // Check personal trust first
        if (personal != null && personal.level(pubkeyHex) == TrustStore.Level.BLOCKED) {
            return true;
        }
        // Check community trust
        if (communityTrust != null && communityTrust.isBlockedByDefault(pubkeyHex)) {
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.TRUST,
                    "Publisher blocked by community trust",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkeyHex));
            return true;
        }
        // Check moderator feeds
        for (ModeratorFeed.Feed f : feeds.values()) {
            for (ModeratorFeed.Label l : f.labels) {
                if (l.pubkey.equals(k) && l.action.equals("block")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isFlagged(String pubkeyHex) {
        String k = key(pubkeyHex);
        if (k.isEmpty()) {
            return false;
        }
        // Check community trust
        if (communityTrust != null && communityTrust.isWarned(pubkeyHex)) {
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.MODERATION,
                    "Publisher flagged by community trust",
                    "Pubkey: " + SecurityLogger.truncateKey(pubkeyHex));
            return true;
        }
        // Check moderator feeds
        for (ModeratorFeed.Feed f : feeds.values()) {
            for (ModeratorFeed.Label l : f.labels) {
                if (l.pubkey.equals(k) && l.action.equals("flag")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get the combined trust score (personal + community).
     * Personal trust is weighted more heavily (70% personal, 30% community).
     */
    public double getCombinedTrustScore(String pubkeyHex) {
        if (pubkeyHex == null || pubkeyHex.isEmpty()) {
            return 0.0;
        }
        
        // Get personal trust score
        double personalScore = 0.0;
        if (personal != null) {
            TrustStore.Level level = personal.level(pubkeyHex);
            if (level == TrustStore.Level.TRUSTED) {
                personalScore = 1.0;
            } else if (level == TrustStore.Level.SEEN) {
                personalScore = 0.5;
            } else if (level == TrustStore.Level.UNVERIFIED) {
                personalScore = 0.0;
            } else if (level == TrustStore.Level.BLOCKED) {
                personalScore = -1.0;
            }
        }
        
        // Get community trust score
        double communityScore = 0.0;
        if (communityTrust != null) {
            communityScore = communityTrust.getTrustScore(pubkeyHex);
        }
        
        // Combined score (70% personal, 30% community)
        return personalScore * 0.7 + communityScore * 0.3;
    }

    /**
     * Check if a publisher is recommended (trusted by personal or community).
     */
    public boolean isRecommended(String pubkeyHex) {
        if (communityTrust != null && communityTrust.isRecommended(pubkeyHex)) {
            return true;
        }
        if (personal != null && personal.level(pubkeyHex) == TrustStore.Level.TRUSTED) {
            return true;
        }
        return false;
    }

    public String noteFor(String pubkeyHex) {
        String k = key(pubkeyHex);
        List<String> notes = new ArrayList<>();
        for (ModeratorFeed.Feed f : feeds.values()) {
            for (ModeratorFeed.Label l : f.labels) {
                if (l.pubkey.equals(k) && !l.reason.isEmpty()) {
                    notes.add(l.action + " by " + shortKey(f.owner) + ": " + l.reason);
                }
            }
        }
        
        // Add community trust note if available
        if (communityTrust != null) {
            CommunityTrust.TrustMetrics metrics = communityTrust.getMetrics(pubkeyHex);
            if (metrics != null && metrics.getUniqueContributors() > 0) {
                notes.add("Community: " + metrics.getDisplayLevel() + 
                         " (" + metrics.trustCount + " trust, " + 
                         metrics.blockCount + " block, " + 
                         metrics.flagCount + " flag)");
            }
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(notes.get(i));
        }
        return sb.toString();
    }

    private static String key(String pubkeyHex) {
        return pubkeyHex == null ? "" : pubkeyHex.trim().toLowerCase();
    }

    private static String shortKey(String pubkeyHex) {
        String k = key(pubkeyHex);
        return k.length() <= 12 ? k : k.substring(0, 12);
    }
}
