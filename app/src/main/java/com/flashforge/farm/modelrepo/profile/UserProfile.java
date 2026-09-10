package com.flashforge.farm.modelrepo.profile;

import com.flashforge.farm.modelrepo.Identity;
import com.flashforge.farm.modelrepo.safety.JsonValidator;
import com.flashforge.farm.modelrepo.safety.SafetyPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class UserProfile {
    public final String pubkey;
    public final String name;
    public final String bio;
    public final String avatarHash;
    public final List<String> links;
    public final long updated;
    public final String signature;
    
    private UserProfile(String pubkey, String name, String bio,
            String avatarHash, List<String> links, long updated, String signature) {
        this.pubkey = pubkey;
        this.name = name;
        this.bio = bio;
        this.avatarHash = avatarHash;
        this.links = links;
        this.updated = updated;
        this.signature = signature;
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
        String signature = "";
        if (o.has("signature") && o.get("signature").isJsonPrimitive() 
                && o.get("signature").getAsJsonPrimitive().isString()) {
            signature = o.get("signature").getAsString();
            if (signature.length() > 128) {
                throw new IllegalArgumentException("signature too long");
            }
        }
        long updated;
        try {
            updated = o.has("updated") ? o.get("updated").getAsLong() : 0;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad updated ts");
        }
        return new UserProfile(pubkey, name, bio, avatar.toLowerCase(), links, updated, signature);
    }

    public static byte[] toJson(String pubkeyHex, String name, String bio,
            String avatarHash, List<String> links, long updated) {
        return toJsonWithSignature(pubkeyHex, name, bio, avatarHash, links, updated, null);
    }
    
    /**
     * Create profile JSON with signature.
     * If seed is provided, the profile will be signed.
     */
    public static byte[] toJsonWithSignature(String pubkeyHex, String name, String bio,
            String avatarHash, List<String> links, long updated, byte[] seed) {
        JsonObject o = new JsonObject();
        o.addProperty("pubkey", pubkeyHex == null ? "" : pubkeyHex.trim().toLowerCase());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("bio", bio == null ? "" : bio);
        o.addProperty("avatar", avatarHash == null ? "" : avatarHash.trim().toLowerCase());
        JsonArray arr = new JsonArray();
        if (links != null) {
            for (String l : links) {
                if (l != null) {
                    arr.add(l);
                }
            }
        }
        o.add("links", arr);
        o.addProperty("updated", updated);
        
        // Add signature if seed is provided
        if (seed != null && seed.length == 32) {
            try {
                String jsonForSigning = o.toString();
                byte[] sig = Identity.sign(seed, jsonForSigning.getBytes(StandardCharsets.UTF_8));
                o.addProperty("signature", Identity.hex(sig));
            } catch (Exception e) {
                // If signing fails, just don't include signature
            }
        }
        
        return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
    
    /**
     * Verify the signature on this profile.
     * Returns true if the signature is valid and matches the pubkey.
     */
    public boolean verifySignature() {
        if (signature == null || signature.isEmpty() || pubkey == null || pubkey.isEmpty()) {
            return false;
        }
        try {
            // Reconstruct JSON without signature for verification
            JsonObject o = new JsonObject();
            o.addProperty("pubkey", pubkey);
            o.addProperty("name", name == null ? "" : name);
            o.addProperty("bio", bio == null ? "" : bio);
            o.addProperty("avatar", avatarHash == null ? "" : avatarHash);
            JsonArray arr = new JsonArray();
            if (links != null) {
                for (String l : links) {
                    if (l != null) {
                        arr.add(l);
                    }
                }
            }
            o.add("links", arr);
            o.addProperty("updated", updated);
            
            byte[] jsonBytes = o.toString().getBytes(StandardCharsets.UTF_8);
            byte[] sig = Identity.unhex(signature);
            byte[] pubkeyBytes = Identity.unhex(pubkey);
            return Identity.verify(pubkeyBytes, jsonBytes, sig);
        } catch (Exception e) {
            return false;
        }
    }
}