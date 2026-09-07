package com.flashforge.farm.modelrepo.moderation;

import com.flashforge.farm.modelrepo.TrustStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LabelAggregator {
    private final TrustStore personal;
    private final Set<String> subscribed = new HashSet<>();
    private final Map<String, ModeratorFeed.Feed> feeds = new HashMap<>();
    private final Map<String, Long> feedSeq = new HashMap<>();

    public LabelAggregator(TrustStore personal) {
        this.personal = personal;
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
        if (personal != null && personal.level(pubkeyHex) == TrustStore.Level.BLOCKED) {
            return true;
        }
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
        for (ModeratorFeed.Feed f : feeds.values()) {
            for (ModeratorFeed.Label l : f.labels) {
                if (l.pubkey.equals(k) && l.action.equals("flag")) {
                    return true;
                }
            }
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