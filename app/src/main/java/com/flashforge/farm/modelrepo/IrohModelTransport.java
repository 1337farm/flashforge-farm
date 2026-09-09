package com.flashforge.farm.modelrepo;

import android.util.Log;

import com.example.irohapp.IrohBridge;
import com.example.irohapp.IrohTransferListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P2P transport backed by the shared iroh-android-native engine
 * (com.example.irohapp:irohbridge AAR; native libnative_iroh_engine.so via
 * GitHub Packages). Replaces the in-repo farm-iroh UniFFI crate.
 */
public class IrohModelTransport implements ModelTransport {
    private static final String TAG = "IrohModelTransport";

    private boolean initialized;

    public IrohModelTransport() {
    }

    public synchronized void initialize(byte[] secretKey, String dataDir) throws UnavailableException {
        if (initialized) {
            return;
        }
        try {
            byte[] sk = (secretKey != null && secretKey.length == 32) ? secretKey : null;
            if (!IrohBridge.INSTANCE.initialize(dataDir, sk)) {
                throw new UnavailableException("Iroh init returned false");
            }
            initialized = true;
            Log.i(TAG, "Iroh endpoint initialized: " + bytesToHex(IrohBridge.INSTANCE.endpointId()));
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native libnative_iroh_engine.so missing", e);
            throw new UnavailableException("P2P native library missing in this build");
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to initialize Iroh endpoint", e);
            throw new UnavailableException("Iroh init failed: " + e.getMessage());
        }
    }

    public synchronized void shutdown() {
        if (initialized) {
            try {
                IrohBridge.INSTANCE.shutdown();
            } catch (Exception ignored) {
            }
            initialized = false;
        }
    }

    public synchronized boolean isReady() {
        return initialized;
    }

    @Override
    public void fetch(String ticket, File dir, Listener listener) throws UnavailableException {
        if (!initialized) {
            throw new UnavailableException("Not initialized");
        }
        final String t = ticket;
        final File d = dir;
        final Listener l = listener;
        new Thread(() -> {
            AtomicBoolean finished = new AtomicBoolean(false);
            IrohTransferListener cb = new IrohTransferListener() {
                @Override
                public void onTransferProgress(int statusCode, int progressPct, long downloadedBytes, long totalBytes, String message) {
                    if (finished.get()) {
                        return;
                    }
                    if (statusCode < 0) {
                        finished.set(true);
                        l.onError(t, (message == null || message.isEmpty())
                                ? "fetch failed (status " + statusCode + ")" : message);
                    } else if (statusCode == 4) {
                        l.onProgress(t, downloadedBytes, totalBytes);
                    } else if (statusCode == 5) {
                        finish();
                    }
                }

                @Override
                public void onModelMetadata(String modelJson, String fileNamesJson) {
                    if (finished.get()) {
                        return;
                    }
                    ModelMetadata meta = ModelMetadata.parse(modelJson);
                    l.onMetadata(t, meta, entriesFrom(fileNamesJson, modelJson));
                }

                @Override
                public void onFetchComplete(String dir) {
                    finish();
                }

                private void finish() {
                    if (finished.compareAndSet(false, true)) {
                        l.onComplete(t, d);
                    }
                }
            };
            try {
                d.mkdirs();
                IrohBridge.INSTANCE.modelFetch(t, d.getAbsolutePath(), cb);
            } catch (Throwable ex) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                Log.e(TAG, "Fetch failed for " + t, ex);
                String msg = ex.getMessage();
                l.onError(t, msg == null ? "fetch failed" : msg);
            }
        }, "iroh-fetch").start();
    }

    @Override
    public void stop(String ticket) {
        try {
            IrohBridge.INSTANCE.cancelFetch();
        } catch (Exception ignored) {
        }
    }

    private static List<Entry> entriesFrom(String namesJson, String modelJson) {
        List<Entry> out = new ArrayList<>();
        if (namesJson == null) {
            return out;
        }
        try {
            JSONArray names = new JSONArray(namesJson);
            JSONArray sizes = null;
            try {
                sizes = new JSONObject(modelJson).optJSONArray("sizes");
            } catch (Exception ignored) {
            }
            for (int i = 0; i < names.length(); i++) {
                long size = (sizes != null) ? sizes.optLong(i, 0) : 0;
                out.add(new Entry(names.optString(i, "file"), size));
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad file names json", e);
        }
        return out;
    }

    public List<String> searchAll(String keyword) throws UnavailableException {
        try {
            String[] hits = IrohBridge.INSTANCE.searchQuery(keyword);
            return hits == null ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(hits));
        } catch (RuntimeException e) {
            throw new UnavailableException("Search failed: " + e.getMessage());
        }
    }

    public String getMetadata(String modelHashHex) throws UnavailableException {
        try {
            String meta = IrohBridge.INSTANCE.searchGetMetadata(modelHashHex);
            if (meta == null) throw new UnavailableException("Get metadata returned no data");
            return meta;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Get metadata failed: " + e.getMessage());
        }
    }

    public String publishModel(String modelJson, List<byte[]> fileDatas) throws UnavailableException {
        try {
            String ticket = IrohBridge.INSTANCE.modelPublish(modelJson, fileDatas.toArray(new byte[0][]));
            if (ticket == null) throw new UnavailableException("Publish returned no ticket (see logcat)");
            return ticket;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Publish failed: " + e.getMessage());
        }
    }

    public byte[] storeBlob(byte[] data) throws UnavailableException {
        try {
            byte[] hash = IrohBridge.INSTANCE.blobAdd(data);
            if (hash == null) throw new UnavailableException("Store blob returned no hash");
            return hash;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Store blob failed: " + e.getMessage());
        }
    }

    public byte[] getBlob(byte[] hash) throws UnavailableException {
        try {
            byte[] data = IrohBridge.INSTANCE.blobGet(hash);
            if (data == null) throw new UnavailableException("Get blob returned no data");
            return data;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Get blob failed: " + e.getMessage());
        }
    }

    public boolean hasBlob(byte[] hash) throws UnavailableException {
        try {
            return IrohBridge.INSTANCE.blobHas(hash);
        } catch (RuntimeException e) {
            throw new UnavailableException("Has blob failed: " + e.getMessage());
        }
    }

    public void removeBlob(byte[] hash) throws UnavailableException {
        throw new UnavailableException("removeBlob is not supported by the irohbridge engine");
    }

    public String shareTicket(byte[] hash) throws UnavailableException {
        try {
            String ticket = IrohBridge.INSTANCE.ticketFor(hash);
            if (ticket == null) throw new UnavailableException("Ticket returned no data");
            return ticket;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Ticket failed: " + e.getMessage());
        }
    }

    public String ticketInfo(String ticket) throws UnavailableException {
        try {
            String info = IrohBridge.INSTANCE.ticketInfo(ticket);
            if (info == null) throw new UnavailableException("Ticket info returned no data");
            return info;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Ticket info failed: " + e.getMessage());
        }
    }

    public String syncAnnounce() throws UnavailableException {
        try {
            String ticket = IrohBridge.INSTANCE.syncAnnounce();
            if (ticket == null) throw new UnavailableException("Announce returned no ticket");
            return ticket;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Announce failed: " + e.getMessage());
        }
    }

    public byte[] blobFetch(String ticket) throws UnavailableException {
        try {
            byte[] raw = IrohBridge.INSTANCE.blobFetch(ticket);
            if (raw == null) {
                throw new UnavailableException("Blob fetch returned no data (see logcat)");
            }
            return raw;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Blob fetch failed: " + e.getMessage());
        }
    }

    public String syncMerge(String ticket) throws UnavailableException {
        try {
            String merged = IrohBridge.INSTANCE.syncMerge(ticket);
            if (merged == null) throw new UnavailableException("Merge returned no data");
            return merged;
        } catch (UnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnavailableException("Merge failed: " + e.getMessage());
        }
    }

    public List<String> knownPeers() throws UnavailableException {
        try {
            String[] peers = IrohBridge.INSTANCE.knownPeers();
            return peers == null ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(peers));
        } catch (RuntimeException e) {
            throw new UnavailableException("Peers failed: " + e.getMessage());
        }
    }

    public String getEndpointId() {
        try {
            return bytesToHex(IrohBridge.INSTANCE.endpointId());
        } catch (RuntimeException e) {
            return "";
        }
    }

    public byte[] getSecretKey() {
        try {
            return IrohBridge.INSTANCE.secretKey();
        } catch (RuntimeException e) {
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