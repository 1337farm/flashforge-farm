package com.flashforge.farm.modelrepo.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class JsonValidator {
    private JsonValidator() {
    }

    public static JsonObject parseObject(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null json");
        }
        if (raw.length == 0 || raw.length > SafetyPolicy.MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("json size out of bounds: " + (raw == null ? 0 : raw.length));
        }
        JsonElement el;
        try {
            el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed json");
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("top level must be object");
        }
        checkDepth(el, 0);
        return el.getAsJsonObject();
    }

    public static JsonObject parseObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null json");
        }
        return parseObject(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void checkDepth(JsonElement el, int depth) {
        if (depth > SafetyPolicy.MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("json too deep");
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getKey() != null && e.getKey().length() > SafetyPolicy.MAX_JSON_STRING) {
                    throw new IllegalArgumentException("json key too long");
                }
                checkDepth(e.getValue(), depth + 1);
            }
        } else if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() > SafetyPolicy.MAX_FEED_LABELS) {
                throw new IllegalArgumentException("json array too large");
            }
            for (JsonElement child : arr) {
                checkDepth(child, depth + 1);
            }
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            if (el.getAsString().length() > SafetyPolicy.MAX_JSON_STRING) {
                throw new IllegalArgumentException("json string too long");
            }
        }
    }

    public static String reqString(JsonObject o, String key, int maxLen) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()
                || !o.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string: " + key);
        }
        String s = o.get(key).getAsString();
        if (s.length() > maxLen) {
            throw new IllegalArgumentException(key + " too long");
        }
        return s;
    }

    public static String optString(JsonObject o, String key, int maxLen, String def) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        if (!o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("not a string: " + key);
        }
        String s = o.get(key).getAsString();
        if (s.length() > maxLen) {
            throw new IllegalArgumentException(key + " too long");
        }
        return s;
    }
}