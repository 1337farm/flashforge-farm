package com.flashforge.farm.modelrepo.profile;

import com.flashforge.farm.modelrepo.safety.JsonValidator;
import com.flashforge.farm.modelrepo.safety.SafetyPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class UserProfile {
    public final String pubkey;
    public final String name;
    public final String bio;
    public final String avatarHash;
    public final List<String> links;
    public final long updated;

    private UserProfile(String pubkey, String name, String bio,
            String avatarHash, List<String> links, long updated) {
        this.pubkey = pubkey;
        this.name = name;
        this.bio = bio;
        this.avatarHash = avatarHash;
        this.links = links;
        this.updated = updated;
    }

    public static UserProfile parse(byte[] raw, String expectedPubkeyHex) {
        if (raw == null || raw.length > SafetyPolicy.MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("profile size out of bounds");
        }
        JsonObject o = JsonValidator.parseObject(raw);
        String pubkey = JsonValidator.reqString(o, "pubkey", 64).toLowerCase();
        if (expectedPubkeyHex != null && !expectedPubkeyHex.trim().equalsIgnoreCase(pubkey)) {
            throw new IllegalArgumentException("profile key mismatch");
        }
        String name = JsonValidator.optString(o, "name", SafetyPolicy.MAX_PROFILE_NAME, "");
        String bio = JsonValidator.optString(o, "bio", SafetyPolicy.MAX_PROFILE_BIO, "");
        String avatar = JsonValidator.optString(o, "avatar", 64, "");
        if (!avatar.isEmpty() && !avatar.matches("[0-9a-fA-F]{32,128}")) {
            throw new IllegalArgumentException("bad avatar hash");
        }
        List<String> links = new ArrayList<>();
        if (o.has("links") && o.get("links").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("links");
            if (arr.size() > 10) {
                throw new IllegalArgumentException("too many links");
            }
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonPrimitive() || !arr.get(i).getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("bad link");
                }
                String link = arr.get(i).getAsString();
                if (link.length() > 200 || !(link.startsWith("https://") || link.startsWith("http://"))) {
                    throw new IllegalArgumentException("bad link: " + link);
                }
                links.add(link);
            }
        }
        long updated;
        try {
            updated = o.has("updated") ? o.get("updated").getAsLong() : 0;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad updated ts");
        }
        return new UserProfile(pubkey, name, bio, avatar.toLowerCase(), links, updated);
    }

    public static String displayLabel(UserProfile p, String pubkeyHex) {
        String k = pubkeyHex == null ? "" : pubkeyHex.trim().toLowerCase();
        String shortKey = k.length() <= 12 ? k : k.substring(0, 8) + "…" + k.substring(k.length() - 4);
        if (p == null || p.name == null || p.name.isEmpty()) {
            return "anon · " + shortKey;
        }
        return p.name + " · " + shortKey;
    }

    public static String recordKey(String pubkeyHex) {
        return "profile:" + pubkeyHex.trim().toLowerCase();
    }
}