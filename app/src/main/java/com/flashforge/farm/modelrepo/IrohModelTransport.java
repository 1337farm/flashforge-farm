package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import uniffi.farm_iroh.FarmEndpoint;
import uniffi.farm_iroh.FarmException;
import uniffi.farm_iroh.Farm_irohKt;

public class IrohModelTransport implements ModelTransport {
    private static final String TAG = "IrohModelTransport";
    private static final long POLL_MS = 300;
    private static final int MAX_POLLS = 1200;

    private FarmEndpoint endpoint;

    public IrohModelTransport() {
    }

    public synchronized void initialize(byte[] secretKey, String dataDir) throws UnavailableException {
        if (endpoint != null) {
            return;
        }
        try {
            byte[] sk = (secretKey != null && secretKey.length == 32) ? secretKey : new byte[0];
            endpoint = Farm_irohKt.connect(sk, dataDir);
            Log.i(TAG, "Iroh endpoint initialized: " + bytesToHex(endpoint.endpointId()));
        } catch (FarmException e) {
            Log.e(TAG, "Failed to initialize Iroh endpoint", e);
            throw new UnavailableException("Iroh init failed: " + e.getMessage());
        }
    }

    public synchronized void shutdown() {
        if (endpoint != null) {
            try {
                endpoint.shutdown();
            } catch (Exception ignored) {
            }
            endpoint = null;
        }
    }

    public synchronized boolean isReady() {
        return endpoint != null;
    }

    private FarmEndpoint ep() throws UnavailableException {
        if (endpoint == null) {
            throw new UnavailableException("Not initialized");
        }
        return endpoint;
    }

    @Override
    public void fetch(String ticket, File dir, Listener listener) throws UnavailableException {
        final FarmEndpoint e = ep();
        final String t = ticket;
        final File d = dir;
        final Listener l = listener;
        new Thread(() -> {
            long handle = -1;
            try {
                d.mkdirs();
                handle = e.fetchStart(t, d.getAbsolutePath());
                String pendingMeta = null;
                for (int i = 0; i < MAX_POLLS; i++) {
                    String poll;
                    try {
                        poll = e.fetchPoll(handle);
                    } catch (FarmException fe) {
                        l.onError(t, fe.getMessage());
                        return;
                    }
                    JSONObject o = new JSONObject(poll);
                    String state = o.optString("state", "");
                    if (state.equals("metadata")) {
                        pendingMeta = o.optString("model_json", "{}");
                        List<Entry> entries = entriesFrom(o, pendingMeta);
                        ModelMetadata meta = ModelMetadata.parse(pendingMeta);
                        l.onMetadata(t, meta, entries);
                    } else if (state.equals("progress")) {
                        l.onProgress(t, o.optLong("downloaded", 0), o.optLong("total", -1));
                    } else if (state.equals("done")) {
                        l.onComplete(t, new File(o.optString("dir", d.getAbsolutePath())));
                        return;
                    } else if (state.equals("error")) {
                        l.onError(t, o.optString("msg", "fetch failed"));
                        return;
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException ie) {
                        l.onError(t, "interrupted");
                        return;
                    }
                }
                l.onError(t, "fetch timed out");
            } catch (UnavailableException ue) {
                l.onError(t, ue.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Fetch failed for " + t, ex);
                l.onError(t, ex.getMessage());
            } finally {
                if (handle >= 0) {
                    try {
                        e.fetchStop(handle);
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "iroh-fetch").start();
    }

    @Override
    public void stop(String ticket) {
    }

    public void stopHandle(long handle) throws UnavailableException {
        ep().fetchStop(handle);
    }

    private static List<Entry> entriesFrom(JSONObject o, String modelJson) throws Exception {
        JSONArray names = o.optJSONArray("names");
        List<Entry> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        JSONArray sizes = null;
        try {
            sizes = new JSONObject(modelJson).optJSONArray("sizes");
        } catch (Exception ignored) {
        }
        for (int i = 0; i < names.length(); i++) {
            long size = (sizes != null) ? sizes.optLong(i, 0) : 0;
            out.add(new Entry(names.optString(i, "file"), size));
        }
        return out;
    }

    public List<String> searchAll(String keyword) throws UnavailableException {
        try {
            return new ArrayList<>(ep().searchQuery(keyword));
        } catch (FarmException e) {
            throw new UnavailableException("Search failed: " + e.getMessage());
        }
    }

    public String getMetadata(String modelHashHex) throws UnavailableException {
        try {
            return ep().searchGetMetadata(modelHashHex);
        } catch (FarmException e) {
            throw new UnavailableException("Get metadata failed: " + e.getMessage());
        }
    }

    public String publishModel(String modelJson, List<byte[]> fileDatas) throws UnavailableException {
        try {
            return ep().modelPublish(modelJson, fileDatas);
        } catch (FarmException e) {
            throw new UnavailableException("Publish failed: " + e.getMessage());
        }
    }

    public byte[] storeBlob(byte[] data) throws UnavailableException {
        try {
            return ep().blobAdd(data);
        } catch (FarmException e) {
            throw new UnavailableException("Store blob failed: " + e.getMessage());
        }
    }

    public byte[] getBlob(byte[] hash) throws UnavailableException {
        try {
            return ep().blobGet(hash);
        } catch (FarmException e) {
            throw new UnavailableException("Get blob failed: " + e.getMessage());
        }
    }

    public boolean hasBlob(byte[] hash) throws UnavailableException {
        try {
            return ep().blobHas(hash);
        } catch (FarmException e) {
            throw new UnavailableException("Has blob failed: " + e.getMessage());
        }
    }

    public void removeBlob(byte[] hash) throws UnavailableException {
        try {
            ep().blobRemove(hash);
        } catch (FarmException e) {
            throw new UnavailableException("Remove blob failed: " + e.getMessage());
        }
    }

    public String shareTicket(byte[] hash) throws UnavailableException {
        try {
            return ep().ticketFor(hash);
        } catch (FarmException e) {
            throw new UnavailableException("Ticket failed: " + e.getMessage());
        }
    }

    public String ticketInfo(String ticket) throws UnavailableException {
        try {
            return ep().ticketInfo(ticket);
        } catch (FarmException e) {
            throw new UnavailableException("Ticket info failed: " + e.getMessage());
        }
    }

    public String syncAnnounce() throws UnavailableException {
        try {
            return ep().syncAnnounce();
        } catch (FarmException e) {
            throw new UnavailableException("Announce failed: " + e.getMessage());
        }
    }

    public String syncMerge(String ticket) throws UnavailableException {
        try {
            return ep().syncMerge(ticket);
        } catch (FarmException e) {
            throw new UnavailableException("Merge failed: " + e.getMessage());
        }
    }

    public List<String> knownPeers() throws UnavailableException {
        try {
            return new ArrayList<>(ep().knownPeers());
        } catch (Exception e) {
            throw new UnavailableException("Peers failed: " + e.getMessage());
        }
    }

    public String getEndpointId() {
        try {
            return bytesToHex(ep().endpointId());
        } catch (UnavailableException e) {
            return "";
        }
    }

    public byte[] getSecretKey() {
        try {
            return ep().secretKeyBytes();
        } catch (UnavailableException e) {
            return new byte[0];
        }
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            bytes[i / 2] = (byte) ((hi << 4) + lo);
        }
        return bytes;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
