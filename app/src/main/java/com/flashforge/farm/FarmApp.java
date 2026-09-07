package com.flashforge.farm;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Intent;
import android.util.Log;

import com.instacart.truetime.time.TrueTimeImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import com.flashforge.farm.boot.AppBoot;

import com.flashforge.farm.boot.CheckUpdateJsonTask;
import com.flashforge.farm.boot.ClearModelCacheTask;
import com.flashforge.farm.boot.LoadSlic3rConfigTask;
import com.flashforge.farm.boot.PrefsTask;
import com.flashforge.farm.boot.PrintConfigWarmupTask;
import com.flashforge.farm.boot.TrueTimeTask;
import com.flashforge.farm.boot.VibrationUtilsTask;

import com.flashforge.farm.config.ConfigObject;
import com.flashforge.farm.slic3r.ConfigOptionDef;
import com.flashforge.farm.slic3r.PrintConfigDef;
import com.flashforge.farm.slic3r.Slic3rConfigWrapper;
import com.flashforge.farm.slic3r.Slic3rUtils;
import com.flashforge.farm.utils.Prefs;

public class FarmApp extends Application {
    public static FarmApp INSTANCE;
    public static TrueTimeImpl TRUE_TIME;
    public static Slic3rConfigWrapper CONFIG;
    public static int CONFIG_UID = 0;

    // Unsaved, live edits to the active printer/print/filament presets. Applied on top of the saved
    // presets when slicing/previewing, so a changed setting takes effect immediately without having to
    // save (overwrite) the profile. Cleared when the preset is switched or the edits are saved/reset.
    public static final ConfigObject LIVE_DIFF_PRINTER = new ConfigObject();
    public static final ConfigObject LIVE_DIFF_PRINT = new ConfigObject();
    public static final ConfigObject LIVE_DIFF_FILAMENT = new ConfigObject();

    // Tracks whether the user selected auto_brim before genCurrentConfig() converts it to outer_only
    // for Bed3D compatibility. Restored by Model.slice() so the native engine runs its btAutoBrim logic.
    public static boolean AUTO_BRIM_SELECTED = false;

    // CalibMode enum values (from native libslic3r CalibMode enum):
    // 0 = Calib_None
    // 1 = Calib_PA_Line
    // 2 = Calib_PA_Pattern  
    // 3 = Calib_PA_Tower
    // 4 = Calib_Auto_PA_Line
    // 5+ = other calibration modes
    public static int PENDING_CALIB_MODE = 0;
    public static double PENDING_CALIB_START = 0, PENDING_CALIB_END = 0, PENDING_CALIB_STEP = 0;

    public static void clearLiveDiffs() {
        LIVE_DIFF_PRINTER.values.clear();
        LIVE_DIFF_PRINT.values.clear();
        LIVE_DIFF_FILAMENT.values.clear();
    }

    public static ConfigObject liveDiffFor(int profileListType) {
        switch (profileListType) {
            case ConfigObject.PROFILE_LIST_PRINTER: return LIVE_DIFF_PRINTER;
            case ConfigObject.PROFILE_LIST_FILAMENT: return LIVE_DIFF_FILAMENT;
            case ConfigObject.PROFILE_LIST_PRINT: return LIVE_DIFF_PRINT;
            default: return new ConfigObject(); // e.g. SettingsFragment — isolated, never sliced
        }
    }

    public static boolean hasUpdateInfo;

    @SuppressLint("ApplySharedPref")
    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        exportPendingCrashesToDownloads();
        AppBoot.run(Arrays.asList(
                new PrefsTask(),
                new VibrationUtilsTask(),
                new TrueTimeTask(),

                new PrintConfigWarmupTask(),
                new CheckUpdateJsonTask(),
                new ClearModelCacheTask(),
                new LoadSlic3rConfigTask()
        ));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("=== Java uncaught exception ===");
            pw.println("thread: " + t.getName() + " (" + t.getId() + ")");
            pw.println("time  : " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()));
            pw.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                    ", Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            e.printStackTrace(pw);
            pw.flush();

            String trace = sw.toString();
            writeCrashDump("java", trace);

            Runtime.getRuntime().exit(0);
        });
    }

    public static void saveConfig() {
        FarmApp.CONFIG_UID++;
        File f = getConfigFile();
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(CONFIG.serialize().getBytes(StandardCharsets.UTF_8));
            fos.close();

            getCurrentConfigFile().delete();
        } catch (Exception e) {
            Log.e("Config", "Failed to save config", e);
        }

    }

    public static File getModelCacheDir() {
        File f = new File(INSTANCE.getCacheDir(), "model");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File getConfigFile() {
        return new File(INSTANCE.getFilesDir(), "slic3r.ini");
    }

    /** Shared directory for persisted crash/error dumps (Java + native). */
    public static File getCrashDir() {
        File dir = new File(INSTANCE.getExternalFilesDir(null), "crashes");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Append a crash/error dump to the SINGLE session log under getCrashDir()
     * (farm_crash.log, append mode). Used by both the Java uncaught-exception
     * handler and the native crash handler, so every crash record from one app
     * start lives in one file — not one file per pid/timestamp (2026-09-06: a
     * crash loop was producing 10-20 farm_crash_*.log files in Downloads).
     */
    public static void writeCrashDump(String kind, String trace) {
        try {
            File dir = getCrashDir();
            File f = new File(dir, "farm_crash.log");
            String banner = "\n==== " + kind + " crash ====\n"
                    + "build: v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") commit " + BuildConfig.COMMIT + "\n"
                    + "time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date())
                    + " pid: " + android.os.Process.myPid() + "\n";
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(banner.getBytes(StandardCharsets.UTF_8));
                fos.write(trace.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Persisting the dump is best-effort; never let it mask the crash.
        }
    }

    /** Crash/error dump files currently persisted under getCrashDir() (newest first). */
    public static java.util.List<File> listCrashReports() {
        File dir = getCrashDir();
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().startsWith("farm_") && f.getName().endsWith(".log"));
        java.util.List<File> out = new java.util.ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) out.add(f);
        }
        return out;
    }

    /** Newest crash report that should be shown to the user (content), or null if none. */
    public static String consumeLatestCrashReport() {
        java.util.List<File> reports = listCrashReports();
        if (reports.isEmpty()) return null;
        File f = reports.get(0);
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            f.delete();
            for (int i = 1; i < reports.size(); i++) reports.get(i).delete();
            return content;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Copy a crash report into the public Downloads folder via MediaStore
     * (scoped-storage safe on API 29+). Returns true on success, false otherwise
     * (e.g. permission not granted) so the caller can fall back to showing the
     * content on-screen.
     */
    public static boolean saveToDownloads(android.content.Context ctx, String fileName, String content) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = ctx.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) return false;
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Write crash content to a URI chosen via the Storage Access Framework. */
    public static boolean writeToUri(android.content.Context ctx, android.net.Uri uri, String content) {
        try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) return false;
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Silently export any pending crash dumps (native crashes write straight to
     * getCrashDir() at signal time and never get a chance to run the Java
     * uncaught handler) into ONE canonical Downloads document (farm_crash.log),
     * then clear them. Called on app start so a crash is never lost and there
     * is no on-load error screen. Every internal file is concatenated into the
     * same document, and older farm_crash_* docs from earlier crashes are
     * pruned, so Downloads never accumulates 10-20 crash files from a crash
     * loop. The document ACCUMULATES across crash cycles (capped): each export
     * appends after the previously published content instead of replacing it.
     */
    private static final int MAX_DOWNLOADS_CRASH_LOG = 256 * 1024;

    /** Previously published Downloads farm_crash.log content ("" if none). */
    public static String readDownloadsContent(android.content.Context ctx, String displayName) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return "";
        android.net.Uri base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(base,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{displayName, android.os.Environment.DIRECTORY_DOWNLOADS + "/"}, null);
            if (c == null || !c.moveToFirst()) return "";
            android.net.Uri uri = android.content.ContentUris.withAppendedId(base, c.getLong(0));
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return "";
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) != -1 && total < MAX_DOWNLOADS_CRASH_LOG * 2) {
                    bos.write(buf, 0, n);
                    total += n;
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (c != null) c.close();
        }
    }

    public static void exportPendingCrashesToDownloads() {
        try {
            java.util.List<File> reports = listCrashReports();
            // Always sweep stale pre-consolidation docs so an already-polluted
            // Downloads folder converges to the single farm_crash.log even when
            // no new crash has occurred this session.
            pruneStaleCrashFiles();
            StringBuilder all = new StringBuilder();
            for (File f : reports) {
                StringBuilder sb = new StringBuilder();
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    continue; // skip unreadable files
                }
                if (sb.length() > 0) {
                    if (all.length() > 0) all.append('\n');
                    all.append("==== ").append(f.getName()).append(" ====\n");
                    all.append(sb);
                }
            }
            boolean ok = false;
            if (all.length() > 0) {
                // Append after the previously published content (capped, keep
                // the tail) instead of replacing it, so the Downloads log
                // accumulates across crash cycles.
                String previous = readDownloadsContent(INSTANCE, "farm_crash.log");
                StringBuilder combined = new StringBuilder();
                if (!previous.isEmpty()) {
                    combined.append(previous);
                    if (!previous.endsWith("\n")) combined.append('\n');
                }
                combined.append(all);
                String out = combined.toString();
                if (out.length() > MAX_DOWNLOADS_CRASH_LOG) {
                    out = out.substring(out.length() - MAX_DOWNLOADS_CRASH_LOG);
                    int nl = out.indexOf('\n');
                    if (nl >= 0) out = out.substring(nl + 1);
                }
                ok = updateOrCreateDownloads(INSTANCE, "farm_crash.log", out);
            }
            if (ok) {
                for (File f : reports) f.delete();
                Log.i("FarmCrash", "Exported " + reports.size() + " pending crash log(s) to Downloads (single farm_crash.log)");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Write crash content into a SINGLE canonical Downloads document, updating
     * the existing one in place instead of inserting another file. Publishing a
     * new MediaStore row per crash is what filled Downloads with 10-20
     * farm_crash_*.log files during a crash loop.
     */
    public static boolean updateOrCreateDownloads(android.content.Context ctx, String displayName, String content) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
        android.net.Uri base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        android.net.Uri existing = null;
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(base,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{displayName, android.os.Environment.DIRECTORY_DOWNLOADS + "/"}, null);
            if (c != null && c.moveToFirst()) {
                existing = android.content.ContentUris.withAppendedId(base, c.getLong(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (existing != null) {
                // The app owns the MediaStore.Downloads row it created, so it can
                // re-open it "rwt" (truncate) and overwrite in place — no new file.
                try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(existing, "rwt")) {
                    if (os == null) return false;
                    os.write(bytes);
                }
                return true;
            }
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = ctx.getContentResolver().insert(base, values);
            if (uri == null) return false;
            try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) return false;
                os.write(bytes);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Delete older crash documents the app previously published to Downloads
     * (per-pid / per-timestamp farm_crash_* files from before the consolidation,
     * plus this version's own leftovers if an export was interrupted), keeping
     * only the canonical farm_crash.log. Best-effort; rows the app does not own
     * are simply left alone.
     */
    public static void pruneStaleCrashFiles() {
        try {
            android.net.Uri base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            android.database.Cursor c = null;
            java.util.ArrayList<android.net.Uri> stale = new java.util.ArrayList<>();
            try {
                c = INSTANCE.getContentResolver().query(base,
                        new String[]{android.provider.MediaStore.MediaColumns._ID},
                        "(" + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE 'farm_crash_%' OR "
                                + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE 'FlashForgeFarm_crash_%') AND "
                                + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " <> 'farm_crash.log'",
                        null, null);
                if (c != null) {
                    while (c.moveToNext()) stale.add(android.content.ContentUris.withAppendedId(base, c.getLong(0)));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            for (android.net.Uri u : stale) {
                try {
                    INSTANCE.getContentResolver().delete(u, null, null);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Make sure the active process/filament selection is actually compatible with the active printer
     * (and that the process's layer height fits the nozzle). If not, switch to the first compatible
     * preset. Prevents slicing with a leftover incompatible selection — e.g. a 0.56mm process on a
     * 0.4mm nozzle, which the engine rejects with "layer height cannot exceed nozzle diameter".
     */
    public static void ensureCompatibleSelection() {
        if (CONFIG == null) return;
        ConfigObject printer = CONFIG.findPrinter(CONFIG.presets.get("printer"));
        if (printer == null) return;

        String printerName = printer.getTitle();
        String model = printer.get("printer_model");
        String nozzle = printer.get("printer_variant");
        if (nozzle == null) nozzle = Slic3rUtils.firstNozzleDiameter(printer.get("nozzle_diameter"));

        boolean changed = false;
        Slic3rUtils.ConfigChecker checker = new Slic3rUtils.ConfigChecker(printer.serialize());
        try {
            ConfigObject print = CONFIG.findPrint(CONFIG.presets.get("print"));
            boolean printOk = print != null
                    && Slic3rUtils.isPrinterCompatible(print.getTitle(), print.get("compatible_printers"), print.get("compatible_printers_condition"), printerName, model, nozzle, checker)
                    && Slic3rUtils.layerHeightFitsNozzle(print.get("layer_height"), nozzle)
                    && Slic3rUtils.lineWidthFitsNozzle(print.get("line_width"), nozzle);
            if (!printOk) {
                for (ConfigObject obj : CONFIG.printConfigs) {
                    if (Slic3rUtils.isPrinterCompatible(obj.getTitle(), obj.get("compatible_printers"), obj.get("compatible_printers_condition"), printerName, model, nozzle, checker)
                            && Slic3rUtils.layerHeightFitsNozzle(obj.get("layer_height"), nozzle)
                            && Slic3rUtils.lineWidthFitsNozzle(obj.get("line_width"), nozzle)) {
                        CONFIG.presets.put("print", obj.getTitle());
                        changed = true;
                        break;
                    }
                }
            }

            ConfigObject filament = CONFIG.findFilament(CONFIG.presets.get("filament"));
            boolean filamentOk = filament != null
                    && Slic3rUtils.isPrinterCompatible(filament.getTitle(), filament.get("compatible_printers"), filament.get("compatible_printers_condition"), printerName, model, nozzle, checker);
            if (!filamentOk) {
                for (ConfigObject obj : CONFIG.filamentConfigs) {
                    if (Slic3rUtils.isPrinterCompatible(obj.getTitle(), obj.get("compatible_printers"), obj.get("compatible_printers_condition"), printerName, model, nozzle, checker)) {
                        CONFIG.presets.put("filament", obj.getTitle());
                        changed = true;
                        break;
                    }
                }
            }
        } finally {
            checker.release();
        }

        if (changed) saveConfig();
    }

    public static ConfigObject buildCurrentConfigObject() {
        ConfigObject singleObject = new ConfigObject();
        ConfigObject printer = FarmApp.CONFIG.findPrinter(FarmApp.CONFIG.presets.get("printer"));
        if (printer == null) {
            printer = !FarmApp.CONFIG.printerConfigs.isEmpty() ? FarmApp.CONFIG.printerConfigs.get(0) : ConfigObject.createCustomPrinterProfile();
        }
        singleObject.values.putAll(printer.values);

        ConfigObject print = FarmApp.CONFIG.findPrint(FarmApp.CONFIG.presets.get("print"));
        if (print != null) {
            singleObject.values.putAll(print.values);
        }
        for (int i = 0; i < (printer != null ? printer.getExtruderCount() : 1); i++) {
            String presetKey = i == 0 ? "filament" : "filament_" + i;
            String filamentTitle = FarmApp.CONFIG.presets.get(presetKey);
            if (filamentTitle == null) filamentTitle = FarmApp.CONFIG.presets.get("filament");

            ConfigObject filament = FarmApp.CONFIG.findFilament(filamentTitle);
            if (filament != null) {
                for (Map.Entry<String, String> entry : filament.values.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (i == 0) {
                        singleObject.values.put(key, val);
                    } else {
                        String existing = singleObject.values.get(key);
                        if (existing != null) {
                            singleObject.values.put(key, existing + ";" + val);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < i; j++) sb.append(";");
                            sb.append(val);
                            singleObject.values.put(key, sb.toString());
                        }
                    }
                }
            }
        }

        // Apply unsaved live edits on top so a changed setting is used immediately, without saving.
        singleObject.values.putAll(LIVE_DIFF_PRINTER.values);
        singleObject.values.putAll(LIVE_DIFF_PRINT.values);
        singleObject.values.putAll(LIVE_DIFF_FILAMENT.values);

        PrintConfigDef def = PrintConfigDef.getInstance();
        for (Map.Entry<String, ConfigOptionDef> en : def.options.entrySet()) {
            if (singleObject.get(en.getKey()) == null && !PrintConfigDef.SKIP_DEFAULT_OPTIONS.contains(en.getKey()) && en.getValue().defaultValue != null) {
                singleObject.put(en.getKey(), en.getValue().defaultValue);
            }
        }


        return singleObject;
    }

    public static void genCurrentConfig() throws IOException {
        File cfg = getCurrentConfigFile();
        FileOutputStream fos = new FileOutputStream(cfg);
        ConfigObject singleObject = buildCurrentConfigObject();
        
        // Remember if user wants auto_brim (native engine will restore it at slice time).
        AUTO_BRIM_SELECTED = "auto_brim".equals(singleObject.get("brim_type"));
        // Bed3D native code crashes if it sees 'auto_brim' as it does not understand the enum.
        // We write it out as 'outer_only' to prevent rendering crashes.
        // The native slicer's btAutoBrim is restored by Model.slice() via the AUTO_BRIM_SELECTED flag.
        if (AUTO_BRIM_SELECTED) {
            singleObject.put("brim_type", "outer_only");
        }
        
        fos.write(singleObject.serialize().getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    public static File getCurrentConfigFile() {
        return new File(INSTANCE.getFilesDir(), "slic3r_current.ini");
    }
}
