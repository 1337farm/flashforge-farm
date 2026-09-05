package com.flashforge.farm.modelrepo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ModelSafety {
    public static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    public static final int MAX_FILE_COUNT = 100;

    private static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
            "stl", "3mf", "obj", "step", "stp", "png", "jpg", "jpeg", "webp", "json"));

    public static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    public static boolean isAllowedName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
            return false;
        }
        if (!ALLOWED_EXT.contains(extensionOf(new File(name).getName()))) {
            return false;
        }
        return true;
    }

    public static File canonicalChild(File dir, String name) throws Exception {
        File f = new File(dir, name);
        String root = dir.getCanonicalPath();
        String target = f.getCanonicalPath();
        if (!target.equals(root) && !target.startsWith(root + File.separator)) {
            throw new SecurityException("path escapes target dir: " + name);
        }
        return f;
    }

    public static void checkBudget(long fileBytes, long runningTotalBytes, int fileCount) {
        if (fileBytes < 0 || fileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file too large: " + fileBytes);
        }
        if (runningTotalBytes + fileBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("torrent too large");
        }
        if (fileCount >= MAX_FILE_COUNT) {
            throw new IllegalArgumentException("too many files");
        }
    }

    public static String sniffKind(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[64];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) != -1) {
                n += r;
            }
            if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
                return "zip";
            }
            if (n >= 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "png";
            }
            if (n >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
                return "jpeg";
            }
            String text = new String(head, 0, Math.min(n, 32), "UTF-8").trim();
            if (text.startsWith("solid")) {
                return "stl-ascii";
            }
            if (text.startsWith("#") || text.startsWith("v ") || text.startsWith("o ") || text.startsWith("mtllib")) {
                return "obj";
            }
            if (n >= 80) {
                return "stl-binary?";
            }
            return "unknown";
        } catch (Exception e) {
            return "unreadable";
        }
    }

    public static boolean extensionMatchesSniff(String ext, String sniffed) {
        switch (ext) {
            case "stl":
                return sniffed.equals("stl-ascii") || sniffed.equals("stl-binary?");
            case "3mf":
                return sniffed.equals("zip");
            case "obj":
                return sniffed.equals("obj");
            case "png":
                return sniffed.equals("png");
            case "jpg":
            case "jpeg":
                return sniffed.equals("jpeg");
            case "webp":
            case "step":
            case "stp":
            case "json":
                return !sniffed.equals("unreadable");
            default:
                return false;
        }
    }
}
