package com.flashforge.farm.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.flashforge.farm.slic3r.Model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Saving a sliced gcode out to the device.
 *
 * Naming rule: a plate holding exactly one object is named after that object
 * (an imported "hogwarts_stamp_v1.stl" yields "hogwarts_stamp_v1.gcode").
 * Multi-object plates have no single meaningful name, so they fall back to
 * "flashforge_gcode_<timestamp>". Either way the name is only a default -- the
 * save dialog lets the user edit it before anything is written.
 */
public class GCodeExporter {

    private GCodeExporter() {}

    /** Strip a directory, an extension, and anything a filesystem would reject. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // Replace separator/control characters, collapse whitespace runs.
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("\\s+", "_");
        name = name.replaceAll("^\\.+", "");
        if (name.length() > 64) name = name.substring(0, 64);
        return name;
    }

    /**
     * Default export filename (without directory) for the current plate.
     * Single object -> that object's name; otherwise a timestamped fallback.
     */
    public static String suggestedFileName(Model model) {
        String base = null;
        if (model != null) {
            int count = 0;
            int onlyIndex = -1;
            for (int i = 0; i < model.getObjectsCount(); i++) {
                count++;
                onlyIndex = i;
                if (count > 1) break;
            }
            if (count == 1) base = sanitize(model.getObjectName(onlyIndex));
        }
        if (TextUtilsIsEmpty(base)) {
            base = String.format(Locale.ROOT, "flashforge_gcode_%tY%<tm%<td_%<tH%<tM", System.currentTimeMillis());
        }
        return base.endsWith(".gcode") ? base : base + ".gcode";
    }

    private static boolean TextUtilsIsEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Copy {@code source} into the public Downloads collection under
     * {@code fileName}, returning the resulting content Uri (or null on
     * failure).
     *
     * MediaStore is required rather than a plain File write: since API 29 a
     * direct file write into Downloads is rejected, and the IS_PENDING dance
     * keeps a partially-written file from being visible to other apps.
     */
    public static Uri saveToDownloads(Context ctx, File source, String fileName) throws IOException {
        String name = sanitize(fileName);
        if (name.endsWith(".gcode")) name = name.substring(0, name.length() - ".gcode".length());
        name = name + ".gcode";

        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-gcode");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("MediaStore insert failed for " + name);

        try (InputStream in = new FileInputStream(source); OutputStream out = cr.openOutputStream(item)) {
            if (out == null) throw new IOException("openOutputStream returned null for " + name);
            byte[] buf = new byte[16384];
            int c;
            while ((c = in.read(buf)) != -1) out.write(buf, 0, c);
        } catch (IOException | RuntimeException e) {
            // Never leave a half-written file behind.
            try { cr.delete(item, null, null); } catch (RuntimeException ignored) {}
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(item, values, null, null);
        return item;
    }

    /** Human-readable location of the public Downloads dir, for snackbar text. */
    public static String downloadsLabel() {
        return Environment.DIRECTORY_DOWNLOADS;
    }

    /** API level guard used by callers before touching MediaStore.Downloads. */
    public static boolean supportsMediaStoreDownloads() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }
}
