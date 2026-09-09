package com.flashforge.farm.modelrepo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class SyncResult {
    public final int newModels;
    public final List<String> modelTickets;
    public final List<String> profileTickets;

    private SyncResult(int newModels, List<String> modelTickets, List<String> profileTickets) {
        this.newModels = newModels;
        this.modelTickets = modelTickets;
        this.profileTickets = profileTickets;
    }

    public static final class AnnouncePayload {
        public final boolean enveloped;
        public final String announceTicket;
        public final String profileTicket;

        private AnnouncePayload(boolean enveloped, String announceTicket, String profileTicket) {
            this.enveloped = enveloped;
            this.announceTicket = announceTicket;
            this.profileTicket = profileTicket;
        }
    }

    public static String announceEnvelope(String announceTicket, String profileTicket) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("announce", announceTicket == null ? "" : announceTicket.trim());
        o.addProperty("profile", profileTicket == null ? "" : profileTicket.trim());
        return o.toString();
    }

    public static AnnouncePayload parseAnnounce(String ticket) {
        if (ticket != null) {
            String t = ticket.trim();
            if (t.startsWith("{")) {
                try {
                    JsonObject o = JsonParser.parseString(t).getAsJsonObject();
                    if (o.has("announce")) {
                        String ann = o.has("announce") ? optText(o, "announce") : "";
                        String prof = o.has("profile") ? optText(o, "profile") : "";
                        return new AnnouncePayload(true, ann, prof);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new AnnouncePayload(false, ticket, "");
    }

    public SyncResult includingProfiles(List<String> extra) {
        List<String> merged = new ArrayList<>(profileTickets);
        if (extra != null) {
            for (String s : extra) {
                if (s != null && !s.trim().isEmpty() && !merged.contains(s.trim())) {
                    merged.add(s.trim());
                }
            }
        }
        return new SyncResult(newModels, modelTickets, merged);
    }

    private static String optText(JsonObject o, String key) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()
                    && o.get(key).getAsJsonPrimitive().isString()) {
                return o.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static SyncResult parse(String json) {
        List<String> models = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        int n = 0;
        if (json == null || json.isEmpty()) {
            return new SyncResult(0, models, profiles);
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            n = o.has("new_models") ? o.get("new_models").getAsInt() : 0;
            models = strings(o, "model_tickets");
            profiles = strings(o, "profile_tickets");
        } catch (Exception ignored) {
        }
        return new SyncResult(n, models, profiles);
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return out;
        }
        JsonArray arr = o.getAsJsonArray(key);
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString();
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
