package com.flashforge.farm.modelrepo;

import com.flashforge.farm.modelrepo.ModelMetadata;
import com.flashforge.farm.modelrepo.verify.ModelVerifier;
import com.flashforge.farm.modelrepo.verify.Verdict;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class ModelPublisher {
    private final IrohModelTransport transport;

    public interface PublishCallback {
        void onPublished(String ticket, String modelJson);
        void onProgress(String stage, int percent);
        void onError(String error);
    }

    public ModelPublisher(IrohModelTransport transport) {
        this.transport = transport;
    }

    public void publishModel(File modelFile, ModelMetadata metadata, PublishCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(modelFile);
        publishFiles(files, metadata, callback);
    }

    public void publishModelDirectory(File modelDir, ModelMetadata metadata, PublishCallback callback) {
        new Thread(() -> {
            try {
                callback.onProgress("Scanning model directory...", 5);
                File[] found = modelDir.listFiles((dir, name) -> {
                    try {
                        com.flashforge.farm.modelrepo.safety.PathSanitizer.clean(name);
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                });
                if (found == null || found.length == 0) {
                    callback.onError("No valid model files found");
                    return;
                }
                List<File> files = new ArrayList<>();
                for (File f : found) {
                    if (f.isFile()) {
                        files.add(f);
                    }
                }
                if (files.isEmpty()) {
                    callback.onError("No valid model files found");
                    return;
                }
                publishFiles(files, metadata, callback);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }).start();
    }

    private void publishFiles(List<File> files, ModelMetadata metadata, PublishCallback callback) {
        new Thread(() -> {
            try {
                List<String> names = new ArrayList<>();
                List<byte[]> datas = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    callback.onProgress("Checking " + f.getName() + "...",
                            5 + (int) ((i * 20L) / files.size()));
                    Verdict v = ModelVerifier.verifyForPublish(f);
                    if (!v.allow) {
                        callback.onError("Rejected " + f.getName() + ": "
                                + (v.detail.isEmpty() ? v.reason.name() : v.detail));
                        return;
                    }
                    names.add(f.getName());
                    datas.add(readFile(f));
                }
                metadata.files = names;
                String modelJson = metadata.toJson();
                callback.onProgress("Publishing to network...", 70);
                String ticket = transport.publishModel(modelJson, datas);
                callback.onProgress("Published!", 100);
                callback.onPublished(ticket, modelJson);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }).start();
    }

    public static ModelMetadata createMetadata(String title, String description,
            String designerName, String designerPubkey, String category,
            List<String> tags, String licenseSpdx, String licenseUrl) {
        ModelMetadata meta = new ModelMetadata();
        meta.title = title;
        meta.description = description;
        meta.designer.name = designerName;
        meta.designer.pubkey = designerPubkey;
        meta.category = category;
        meta.tags = tags != null ? tags : new ArrayList<String>();
        meta.license.spdx = licenseSpdx != null ? licenseSpdx : "unspecified";
        meta.license.url = licenseUrl != null ? licenseUrl : "";
        return meta;
    }

    private static byte[] readFile(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            fis.close();
        }
    }
}
