package com.flashforge.farm.api;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class FlashforgeAPIClient {
    private static final String TAG = "FlashforgeAPIClient";
    private String baseUrl;
    private final Gson gson = new Gson();

    public FlashforgeAPIClient(String ipOrUrl) {
        if (!ipOrUrl.startsWith("http://") && !ipOrUrl.startsWith("https://")) {
            this.baseUrl = "http://" + ipOrUrl + ":8898";
        } else {
            this.baseUrl = ipOrUrl;
            if (!this.baseUrl.contains(":8898")) {
                this.baseUrl += ":8898";
            }
        }
    }

    public interface APIResultCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public void getPrinterStatus(APIResultCallback<JsonObject> callback) {
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "/detail");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String payload = "{}";
                try(OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    in.close();
                    JsonObject jsonObject = gson.fromJson(response.toString(), JsonObject.class);
                    callback.onSuccess(jsonObject);
                } else {
                    callback.onError(new Exception("HTTP Error: " + responseCode));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting printer status", e);
                callback.onError(e);
            }
        }).start();
    }
}
