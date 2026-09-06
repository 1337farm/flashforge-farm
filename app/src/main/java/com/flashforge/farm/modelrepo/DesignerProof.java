package com.flashforge.farm.modelrepo;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

public class DesignerProof {
    private static final Gson GSON = new Gson();
    private static final int TIMEOUT_MS = 15000;

    private DesignerProof() {
    }

    public static class NameFile {
        @SerializedName("names")
        public Map<String, String> names;
    }

    public static boolean verifyMapping(String body, String name, String pubkeyHex) {
        if (body == null || name == null || pubkeyHex == null) {
            return false;
        }
        try {
            NameFile nf = GSON.fromJson(body, NameFile.class);
            if (nf == null || nf.names == null) {
                return false;
            }
            for (Map.Entry<String, String> e : nf.names.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && e.getValue() != null
                        && e.getValue().trim().equalsIgnoreCase(pubkeyHex.trim())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verifyDomain(String domain, String name, String pubkeyHex) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        String[] files = {"farm.json", "nostr.json"};
        for (String file : files) {
            try {
                String url = "https://" + domain + "/.well-known/" + file
                        + "?name=" + URLEncoder.encode(name, "UTF-8");
                String body = fetch(url);
                if (verifyMapping(body, name, pubkeyHex)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) {
                throw new IllegalStateException("http " + c.getResponseCode());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > 65536) {
                        throw new IllegalStateException("proof file too large");
                    }
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
