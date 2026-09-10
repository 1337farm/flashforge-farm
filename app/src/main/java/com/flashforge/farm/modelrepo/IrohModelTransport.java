package com.flashforge.farm.modelrepo;

import android.util.Log;

import com.example.irohapp.IrohBridge;
import com.example.irohapp.IrohTransferListener;
import com.flashforge.farm.modelrepo.moderation.LabelAggregator;
import com.flashforge.farm.modelrepo.verify.ModelVerifier;
import com.flashforge.farm.modelrepo.verify.Verdict;

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
            String endpointId = bytesToHex(IrohBridge.INSTANCE.endpointId());
            Log.i(TAG, "Iroh endpoint initialized: " + endpointId);
            
            // Log encryption status
            boolean encryptionEnabled = (sk != null && sk.length == 32);
            SecurityLogger.logEncryption(SecurityLogger.Severity.INFO, 
                    "Transport encryption status", 
                    "Endpoint: " + endpointId + ", Encryption: " + encryptionEnabled);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native libnative_iroh_engine.so missing", e);
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.NETWORK,
                    "P2P native library missing", e);
            throw new UnavailableException("P2P native library missing in this build");
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to initialize Iroh endpoint", e);
            SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.NETWORK,
                    "Failed to initialize Iroh endpoint", e);
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
            final ModelMetadata[] metadataHolder = new ModelMetadata[1];
            final String[] expectedHashHolder = new String[1];
            
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
                    try {
                        ModelMetadata parsedMetadata = ModelMetadata.parse(modelJson);
                        metadataHolder[0] = parsedMetadata;
                        l.onMetadata(t, parsedMetadata, entriesFrom(fileNamesJson, modelJson));
                        
                        // Extract expected hash from metadata verification field
                        if (parsedMetadata.verification != null && !parsedMetadata.verification.isEmpty()) {
                            expectedHashHolder[0] = parsedMetadata.verification.toLowerCase();
                            Log.d(TAG, "Expected hash for " + t + ": " + expectedHashHolder[0]);
                            SecurityLogger.log(SecurityLogger.Severity.DEBUG, SecurityLogger.Category.VERIFICATION,
                                    "Expected hash extracted from metadata", 
                                    "Ticket: " + t + ", Hash: " + expectedHashHolder[0]);
                        } else {
                            Log.w(TAG, "No verification hash in metadata for " + t);
                            SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                                    "No verification hash in metadata", 
                                    "Ticket: " + t);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse metadata for " + t, e);
                        SecurityLogger.log(SecurityLogger.Severity.ERROR, SecurityLogger.Category.CONTENT,
                                "Failed to parse metadata", 
                                "Ticket: " + t + ", Error: " + e.getMessage());
                        if (finished.compareAndSet(false, true)) {
                            l.onError(t, "Invalid metadata: " + e.getMessage());
                        }
                    }
                }

                @Override
                public void onFetchComplete(String dir) {
                    if (finished.get()) {
                        return;
                    }
                    
                    // Verify hash before completing
                    if (expectedHashHolder[0] != null && !expectedHashHolder[0].isEmpty()) {
                        verifyAndComplete(dir, expectedHashHolder[0], t, d, l, finished, metadataHolder[0]);
                    } else {
                        // No hash to verify, but still run model verification if we have metadata
                        if (metadataHolder[0] != null) {
                            verifyAndComplete(dir, "", t, d, l, finished, metadataHolder[0]);
                        } else {
                            // No hash and no metadata, complete directly (legacy support)
                            Log.w(TAG, "Completing fetch without verification for " + t);
                            finish();
                        }
                    }
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

    /**
     * Verify downloaded content hash and complete or quarantine
     */
    private void verifyAndComplete(String dirPath, String expectedHash, String ticket, 
            File outputDir, Listener listener, AtomicBoolean finished, ModelMetadata metadata) {
        try {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                if (finished.compareAndSet(false, true)) {
                    listener.onError(ticket, "Download directory missing");
                }
                return;
            }
            
            // Collect all files and compute combined hash
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                if (finished.compareAndSet(false, true)) {
                    listener.onError(ticket, "No files downloaded");
                }
                return;
            }
            
            // For multi-file downloads, we need to verify the content
            // The verification field in metadata should match the SHA-256 of the primary model file
            // or we need to compute a combined hash
            String computedHash = computeDirectoryHash(dir);
            
            if (!expectedHash.equalsIgnoreCase(computedHash)) {
                Log.e(TAG, "Hash mismatch for " + ticket + 
                      ": expected=" + expectedHash + ", got=" + computedHash);
                
                // Log hash verification failure
                SecurityLogger.logHashVerificationFailure(ticket, expectedHash, computedHash);
                SecurityLogger.logQuarantine(SecurityLogger.Severity.WARNING,
                        "Content quarantined due to hash mismatch",
                        "Ticket: " + ticket);
                
                // Quarantine the content
                if (finished.compareAndSet(false, true)) {
                    listener.onError(ticket, "Hash verification failed - content may be tampered. " +
                            "Expected: " + expectedHash.substring(0, Math.min(16, expectedHash.length())) +
                            "..., Got: " + computedHash.substring(0, Math.min(16, computedHash.length())) + "...");
                }
                return;
            }
            
            SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.VERIFICATION,
                    "Hash verification passed", 
                    "Ticket: " + ticket);
            
            Log.i(TAG, "Hash verification passed for " + ticket);
            
            // Now verify the model content using ModelVerifier
            // Only do this if we have metadata
            if (metadata != null) {
                try {
                    // Prepare files for verification
                    java.util.List<ModelVerifier.LocalFile> localFiles = new java.util.ArrayList<>();
                    for (File f : files) {
                        // Try to find the corresponding claimed hash from metadata
                        String claimedHash = "";
                        if (metadata.files != null && metadata.files.contains(f.getName())) {
                            int idx = metadata.files.indexOf(f.getName());
                            if (idx >= 0 && idx < metadata.verification.length()) {
                                // This is a simplification - in practice, each file might have its own hash
                                // For now, we use the primary verification hash for all files
                                claimedHash = metadata.verification;
                            }
                        }
                        localFiles.add(new ModelVerifier.LocalFile(f, claimedHash));
                    }
                    
                    // Create a simple trust store and label aggregator for verification
                    // In production, these should be passed from the caller
                    TrustStore trustStore = new TrustStore(new TrustStore.MemoryStorage());
                    LabelAggregator labelAggregator = new LabelAggregator(trustStore);
                    
                    // Verify the download
                    Verdict verdict = ModelVerifier.verifyDownload(
                            metadata, localFiles, trustStore, labelAggregator);
                    
                    if (!verdict.allow) {
                        Log.e(TAG, "Model verification failed for " + ticket + ": " + verdict.reason + " - " + verdict.detail);
                        
                        // Log model verification failure
                        SecurityLogger.logModelVerificationFailure(ticket, verdict.reason.toString(), verdict.detail);
                        SecurityLogger.logQuarantine(SecurityLogger.Severity.WARNING,
                                "Content quarantined due to model verification failure",
                                "Ticket: " + ticket + ", Reason: " + verdict.reason + ", Detail: " + verdict.detail);
                        
                        // Quarantine the content
                        if (finished.compareAndSet(false, true)) {
                            listener.onError(ticket, "Model verification failed: " + verdict.detail);
                        }
                        return;
                    }
                    
                    Log.i(TAG, "Model verification passed for " + ticket);
                    SecurityLogger.log(SecurityLogger.Severity.INFO, SecurityLogger.Category.VERIFICATION,
                            "Model verification passed", 
                            "Ticket: " + ticket);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Model verification error for " + ticket, e);
                    SecurityLogger.log(SecurityLogger.Severity.WARNING, SecurityLogger.Category.VERIFICATION,
                            "Model verification error (backward compatibility)",
                            "Ticket: " + ticket + ", Error: " + e.getMessage());
                    // Don't fail the download for verification errors - just log
                    // This ensures backward compatibility with unsigned models
                }
            }
            
            if (finished.compareAndSet(false, true)) {
                listener.onComplete(ticket, outputDir);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Hash verification error for " + ticket, e);
            if (finished.compareAndSet(false, true)) {
                listener.onError(ticket, "Verification error: " + e.getMessage());
            }
        }
    }

    /**
     * Compute SHA-256 hash of all files in a directory (sorted by name for consistency)
     */
    private String computeDirectoryHash(File dir) throws Exception {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        
        // Sort files by name for consistent hash
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        
        for (File f : files) {
            if (f.isFile()) {
                // Include filename in hash to detect file substitution
                md.update(f.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        md.update(buf, 0, n);
                    }
                }
            }
        }
        
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest()) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
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