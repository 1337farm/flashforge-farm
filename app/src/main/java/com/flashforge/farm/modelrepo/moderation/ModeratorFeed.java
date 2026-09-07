package com.flashforge.farm.modelrepo.moderation;

import com.flashforge.farm.modelrepo.Identity;
import com.flashforge.farm.modelrepo.safety.JsonValidator;
import com.flashforge.farm.modelrepo.safety.SafetyPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ModeratorFeed {
    private ModeratorFeed() {
    }

    public static class Label {
        public final String pubkey;
        public final String action;
        public final String reason;
        public final String evidence;
        public final long ts;

        public Label(String pubkey, String action, String reason, String evidence, long ts) {
            this.pubkey = pubkey;
            this.action = action;
            this.reason = reason;
            this.evidence = evidence;
            this.ts = ts;
        }
    }

    public static class Feed {
        public final String owner;
        public final long seq;
        public final String prev;
        public final List<Label> labels;

        Feed(String owner, long seq, String prev, List<Label> labels) {
            this.owner = owner;
            this.seq = seq;
            this.prev = prev;
            this.labels = labels;
        }
    }

    public static String canonical(String owner, long seq, String prev, List<Label> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("farm-feed/1\n");
        sb.append(owner.toLowerCase()).append('\n');
        sb.append(seq).append('\n');
        sb.append(prev == null ? "" : prev).append('\n');
        for (Label l : labels) {
            sb.append(l.pubkey.toLowerCase()).append('|')
                    .append(l.action).append('|')
                    .append(l.reason).append('|')
                    .append(l.evidence).append('|')
                    .append(l.ts).append('\n');
        }
        return sb.toString();
    }

    public static String sign(String ownerSeedHex, long seq, String prev, List<Label> labels) throws Exception {
        byte[] seed = Identity.unhex(ownerSeedHex);
        String owner = Identity.hex(Identity.publicKey(seed));
        String body = canonical(owner, seq, prev, labels);
        byte[] sig = Identity.sign(seed, body.getBytes(StandardCharsets.UTF_8));
        JsonObject o = new JsonObject();
        o.addProperty("kind", "farm-feed/1");
        o.addProperty("owner", owner);
        o.addProperty("seq", seq);
        o.addProperty("prev", prev == null ? "" : prev);
        JsonArray arr = new JsonArray();
        for (Label l : labels) {
            JsonObject lo = new JsonObject();
            lo.addProperty("pubkey", l.pubkey.toLowerCase());
            lo.addProperty("action", l.action);
            lo.addProperty("reason", l.reason);
            lo.addProperty("evidence", l.evidence);
            lo.addProperty("ts", l.ts);
            arr.add(lo);
        }
        o.add("labels", arr);
        o.addProperty("sig", Identity.hex(sig));
        return o.toString();
    }

    public static Feed verify(byte[] raw, long minSeq) {
        if (raw == null || raw.length > SafetyPolicy.MAX_FEED_BYTES) {
            throw new IllegalArgumentException("feed size out of bounds");
        }
        JsonObject o = JsonValidator.parseObject(raw);
        if (!o.has("sig") || !o.get("sig").isJsonPrimitive()) {
            throw new IllegalArgumentException("feed missing sig");
        }
        String owner = JsonValidator.reqString(o, "owner", 64);
        long seq;
        try {
            seq = o.has("seq") ? o.get("seq").getAsLong() : -1;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad feed seq");
        }
        if (seq < 0 || seq < minSeq) {
            throw new IllegalArgumentException("stale feed seq");
        }
        String prev = JsonValidator.optString(o, "prev", 128, "");
        if (!o.has("labels") || !o.get("labels").isJsonArray()) {
            throw new IllegalArgumentException("feed missing labels");
        }
        JsonArray arr = o.getAsJsonArray("labels");
        if (arr.size() > SafetyPolicy.MAX_FEED_LABELS) {
            throw new IllegalArgumentException("too many labels");
        }
        List<Label> labels = new ArrayList<>();
        for (JsonElement le : arr) {
            if (!le.isJsonObject()) {
                throw new IllegalArgumentException("bad label");
            }
            JsonObject lo = le.getAsJsonObject();
            String pk = JsonValidator.reqString(lo, "pubkey", 64).toLowerCase();
            String action = JsonValidator.reqString(lo, "action", 16);
            if (!action.equals("block") && !action.equals("flag")) {
                throw new IllegalArgumentException("bad action: " + action);
            }
            String reason = JsonValidator.optString(lo, "reason", 500, "");
            String evidence = JsonValidator.optString(lo, "evidence", 128, "");
            long ts;
            try {
                ts = lo.has("ts") ? lo.get("ts").getAsLong() : 0;
            } catch (Exception e) {
                throw new IllegalArgumentException("bad label ts");
            }
            labels.add(new Label(pk, action, reason, evidence, ts));
        }
        String body = canonical(owner, seq, prev, labels);
        byte[] sig;
        try {
            sig = Identity.unhex(o.get("sig").getAsString());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad sig encoding");
        }
        byte[] ownerBytes;
        try {
            ownerBytes = Identity.unhex(owner);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad owner key");
        }
        if (!Identity.verify(ownerBytes, body.getBytes(StandardCharsets.UTF_8), sig)) {
            throw new IllegalArgumentException("bad feed signature");
        }
        return new Feed(owner.toLowerCase(), seq, prev, labels);
    }
}