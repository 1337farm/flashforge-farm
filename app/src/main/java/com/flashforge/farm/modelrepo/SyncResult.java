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
