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
            return sniffStream(in, f.length());
        } catch (Exception e) {
            return "unreadable";
        }
    }

    public static String sniffStream(InputStream in, long len) {
        try {
            byte[] head = new byte[84];
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
            boolean solidLead = text.startsWith("solid");
            boolean objLead = text.startsWith("#") || text.startsWith("v ")
                    || text.startsWith("o ") || text.startsWith("mtllib");
            if (objLead) {
                return "obj";
            }
            // STL: binary files may ALSO start with "solid", so disambiguate
            // by the facet-count/size rule (84-byte header + 50 bytes/facet).
            // Files too short for even the header cannot be valid binary STL.
            if (len >= 84) {
                if (!solidLead) {
                    return "stl-binary";
                }
                if (n >= 84) {
                    long facets = ((head[83] & 0xFFL) << 24) | ((head[82] & 0xFFL) << 16)
                            | ((head[81] & 0xFFL) << 8) | (head[80] & 0xFFL);
                    if (len == 84 + facets * 50L) {
                        return "stl-ascii";
                    }
                    return "stl-binary";
                }
                return "stl-binary";
            }
            if (solidLead) {
                return "stl-ascii";
            }
            return "unknown";
        } catch (Exception e) {
            return "unreadable";
        }
    }

    public static String sha256Stream(InputStream in) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte v : md.digest()) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    public static boolean extensionMatchesSniff(String ext, String sniffed) {
        switch (ext) {
            case "stl":
                return sniffed.equals("stl-ascii") || sniffed.equals("stl-binary");
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
