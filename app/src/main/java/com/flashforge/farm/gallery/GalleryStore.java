package com.flashforge.farm.gallery;

import com.flashforge.farm.FarmApp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class GalleryStore {
    private static final long MAX_IMPORT_BYTES = 600L * 1024 * 1024; // matches MeshLoader limit
    private GalleryStore() {
    }

    public static File dir() {
        File d = new File(FarmApp.INSTANCE.getFilesDir(), "gallery");
        d.mkdirs();
        return d;
    }

    public static boolean isSupported(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".stl") || n.endsWith(".3mf") || n.endsWith(".obj");
    }

    public static File[] listCustom() {
        File[] files = dir().listFiles();
        if (files == null) return new File[0];
        ArrayList<File> out = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && isSupported(f.getName())) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out.toArray(new File[out.size()]);
    }

    public static File importStream(InputStream in, String displayName) throws IOException {
        if (!isSupported(displayName)) throw new IOException("unsupported model format");
        String safe = sanitize(displayName);
        File dest = unique(new File(dir(), safe));
        OutputStream out = null;
        try {
            out = new FileOutputStream(dest);
            byte[] buf = new byte[10240];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_IMPORT_BYTES) throw new IOException("model larger than " + MAX_IMPORT_BYTES + " bytes");
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            dest.delete();
            throw e;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (dest.length() == 0) {
            dest.delete();
            throw new IOException("empty file");
        }
        return dest;
    }

    public static boolean deleteCustom(File f) {
        try {
            if (f == null) return false;
            if (!f.getParentFile().getCanonicalPath().equals(dir().getCanonicalPath())) return false;
            return f.delete();
        } catch (IOException e) {
            return false;
        }
    }

    private static String sanitize(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 80; i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ' ') sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString().trim();
        if (s.isEmpty() || !isSupported(s)) s = "model.stl";
        return s;
    }

    private static File unique(File f) {
        if (!f.exists()) return f;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 2; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + "_" + i + ext);
            if (!c.exists()) return c;
        }
        return new File(f.getParentFile(), base + "_" + System.currentTimeMillis() + ext);
    }
}
