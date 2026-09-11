package com.flashforge.farm.modelrepo.safety;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zip-bomb and Zip-slip guards for archive reads (pure Java, JVM-testable).
 *
 * Budgets come from SafetyPolicy: per-entry bytes, total inflated output,
 * entry counts. All helpers throw IOException (never return partial data)
 * so callers fail closed.
 */
public final class ZipGuard {
    private ZipGuard() {
    }

    /**
     * Read a single ZipFile entry, capped at maxBytes (checks the size hint
     * first so absurd entries fail without allocating).
     */
    public static byte[] readEntryBytes(ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException {
        if (entry == null) {
            throw new IOException("missing zip entry");
        }
        long hint = entry.getSize();
        if (hint > maxBytes) {
            throw new IOException("zip entry too large: " + entry.getName());
        }
        try (InputStream in = zip.getInputStream(entry);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copyBounded(in, out, maxBytes);
            return out.toByteArray();
        }
    }

    /** UTF-8 convenience over readEntryBytes. */
    public static String readEntryText(ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException {
        return new String(readEntryBytes(zip, entry, maxBytes), StandardCharsets.UTF_8);
    }

    /**
     * Read a ZipInputStream entry (streaming, no size hint) capped at maxBytes.
     * The caller remains positioned for getNextEntry().
     */
    public static byte[] readStreamBytes(InputStream in, long maxBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copyBounded(in, out, maxBytes);
        return out.toByteArray();
    }

    /** Copy capped at maxBytes; throws past the budget. Returns bytes copied. */
    public static long copyBounded(InputStream in, OutputStream out, long maxBytes)
            throws IOException {
        byte[] buf = new byte[32768];
        long total = 0;
        int c;
        while ((c = in.read(buf)) != -1) {
            total += c;
            if (total > maxBytes) {
                throw new IOException("zip output over budget");
            }
            out.write(buf, 0, c);
        }
        return total;
    }

    /** Reject entry enumerations past the policy count. */
    public static void checkEntryCount(int count) throws IOException {
        if (count > SafetyPolicy.MAX_ZIP_ENTRIES) {
            throw new IOException("too many zip entries: " + count);
        }
    }

    /**
     * Resolve a zip entry name under root, rejecting traversal (Zip-slip).
     * Returns the canonical destination File.
     */
    public static File safeDestination(File root, String entryName) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        File out = new File(root, entryName);
        String outPath = out.getCanonicalPath();
        if (!outPath.equals(root.getCanonicalPath()) && !outPath.startsWith(rootPath)) {
            throw new IOException("zip entry escapes directory: " + entryName);
        }
        return out;
    }
}
