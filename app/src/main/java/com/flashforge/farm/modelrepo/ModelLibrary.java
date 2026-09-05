package com.flashforge.farm.modelrepo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModelLibrary {
    private static final String TAG = "ModelLibrary";
    private static final String RELEASE_BASE =
            "https://github.com/1337farm/flashforge-farm/releases/download/models-latest";
    private static final String MANIFEST_SHA =
            "9df6a2972b69bbf143bc3a702efc512a681ffefb6d7f1cce557540d225ced7d7";
    private static final String DIR_NAME = "models";
    private static final Object LOCK = new Object();

    public interface Callback {
        void onReady(File f);
        void onError(String msg);
    }

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR_NAME);
    }

    public static void ensureModel(Context ctx, String filename, Callback cb) {
        File f = new File(dir(ctx.getApplicationContext()), filename);
        if (f.isFile()) {
            post(() -> cb.onReady(f));
            return;
        }
        new Thread(() -> {
            try {
                File ready = getModelFileSync(ctx.getApplicationContext(), filename);
                post(() -> cb.onReady(ready));
            } catch (Exception e) {
                Log.e(TAG, "ensureModel " + filename, e);
                post(() -> cb.onError(e.getMessage()));
            }
        }, "model-download").start();
    }

    public static File getModelFileSync(Context ctx, String filename) throws Exception {
        File target = new File(dir(ctx), filename);
        if (target.isFile()) {
            return target;
        }
        synchronized (LOCK) {
            if (target.isFile()) {
                return target;
            }
            File tmp = File.createTempFile("models", ".zip", ctx.getCacheDir());
            try {
                download(RELEASE_BASE + "/models-manifest.json", tmp);
                String manifest = readAll(tmp);
                String manifestSha = sha256Hex(manifest.getBytes("UTF-8"));
                if (!MANIFEST_SHA.equalsIgnoreCase(manifestSha)) {
                    throw new SecurityException("models manifest sha mismatch");
                }
                JSONObject files = new JSONObject(manifest).getJSONObject("files");
                if (!files.has(filename)) {
                    throw new IllegalArgumentException("unknown model: " + filename);
                }
                String wantArchive = new JSONObject(manifest).getJSONObject("archive").getString("models.zip");
                File zip = File.createTempFile("models", ".zip", ctx.getCacheDir());
                try {
                    download(RELEASE_BASE + "/models.zip", zip);
                    if (!wantArchive.equalsIgnoreCase(sha256File(zip))) {
                        throw new SecurityException("models.zip sha mismatch");
                    }
                    extractEntry(zip, filename, target);
                } finally {
                    zip.delete();
                }
            } finally {
                tmp.delete();
            }
            if (!target.isFile()) {
                throw new IllegalStateException("downloaded archive lacks " + filename);
            }
            return target;
        }
    }

    private static void extractEntry(File zip, String filename, File target) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (!e.isDirectory() && name.equals(filename)) {
                    target.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[32768];
                        int c;
                        while ((c = zin.read(buf)) != -1) {
                            fos.write(buf, 0, c);
                        }
                    }
                    return;
                }
            }
        }
        throw new IllegalStateException("entry not found: " + filename);
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("http " + code + " for " + url);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) != -1) {
                off += n;
            }
            return new String(buf, "UTF-8");
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sha256File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
