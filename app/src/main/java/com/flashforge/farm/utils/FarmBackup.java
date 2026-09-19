package com.flashforge.farm.utils;

import com.flashforge.farm.BuildConfig;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.config.ConfigObject;
import com.flashforge.farm.slic3r.Slic3rConfigWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FarmBackup {
    private FarmBackup() {
    }

    public static final String BACKUP_FILE_NAME = "flashforge-farm-backup.json";
    private static final String FORMAT = "flashforge-farm-backup";
    private static final int VERSION = 1;
    private static final Gson gson = new Gson();

    public static class BackupException extends Exception {
        BackupException(String msg) {
            super(msg);
        }

        BackupException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class RestoreReport {
        public int printers;
        public int queuedJobs;
        public int printProfiles;
        public int filamentProfiles;
        public int printerProfiles;
    }

    public static String exportJson() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("format", FORMAT);
        doc.put("version", VERSION);
        doc.put("appVersion", BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE);
        doc.put("commit", BuildConfig.COMMIT);
        doc.put("exportedAt", System.currentTimeMillis());
        List<PrinterFleetManager.Printer> fleet = PrinterFleetManager.getPrinters();
        doc.put("fleet", fleet == null ? new ArrayList<>() : fleet);
        List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
        doc.put("queue", queue == null ? new ArrayList<>() : queue);
        doc.put("slicerIni", FarmApp.CONFIG != null ? FarmApp.CONFIG.serialize() : "");
        String palette = Prefs.getPrefs() != null ? Prefs.getPrefs().getString("filament_palette", null) : null;
        doc.put("filamentPalette", palette);
        Map<String, String> presets = (FarmApp.CONFIG != null && FarmApp.CONFIG.presets != null)
                ? FarmApp.CONFIG.presets.values : new HashMap<String, String>();
        doc.put("presets", presets);
        return gson.toJson(doc);
    }

    public static RestoreReport importJson(String json) throws BackupException {
        final Map<String, Object> doc;
        try {
            doc = gson.fromJson(json, new TypeToken<Map<String, Object>>() {
            }.getType());
        } catch (Exception e) {
            throw new BackupException("not a valid backup file", e);
        }
        if (doc == null || !FORMAT.equals(doc.get("format"))) {
            throw new BackupException("not a flashforge-farm backup file");
        }
        Object v = doc.get("version");
        if (!(v instanceof Number) || ((Number) v).intValue() != VERSION) {
            throw new BackupException("unsupported backup version");
        }
        RestoreReport report = new RestoreReport();

        List<PrinterFleetManager.Printer> fleet = decodeItems(doc.get("fleet"), PrinterFleetManager.Printer.class);
        for (int i = fleet.size() - 1; i >= 0; i--) {
            if (fleet.get(i).id == null) fleet.remove(i);
        }
        PrinterFleetManager.savePrinters(fleet);
        report.printers = fleet.size();

        List<PrintQueueManager.QueueItem> queue = decodeItems(doc.get("queue"), PrintQueueManager.QueueItem.class);
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (queue.get(i).id == null) queue.remove(i);
        }
        PrintQueueManager.saveQueue(queue);
        report.queuedJobs = queue.size();

        Object palette = doc.get("filamentPalette");
        if (palette instanceof String && Prefs.getPrefs() != null) {
            Prefs.getPrefs().edit().putString("filament_palette", (String) palette).apply();
        }

        Object ini = doc.get("slicerIni");
        if (ini instanceof String && !((String) ini).isEmpty()) {
            if (FarmApp.CONFIG == null) FarmApp.CONFIG = new Slic3rConfigWrapper();
            final Slic3rConfigWrapper w;
            try {
                w = new Slic3rConfigWrapper(new ByteArrayInputStream(
                        ((String) ini).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new BackupException("backup slicer config is corrupt", e);
            }
            for (ConfigObject cfg : w.printConfigs) FarmApp.CONFIG.importPrint(cfg);
            for (ConfigObject cfg : w.filamentConfigs) FarmApp.CONFIG.importFilament(cfg);
            for (ConfigObject cfg : w.printerConfigs) FarmApp.CONFIG.importPrinter(cfg);
            report.printProfiles = w.printConfigs.size();
            report.filamentProfiles = w.filamentConfigs.size();
            report.printerProfiles = w.printerConfigs.size();
        }

        Object presets = doc.get("presets");
        if (presets instanceof Map && FarmApp.CONFIG != null) {
            if (FarmApp.CONFIG.presets == null) FarmApp.CONFIG.presets = new ConfigObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) presets).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof String) {
                    FarmApp.CONFIG.presets.values.put((String) e.getKey(), (String) e.getValue());
                }
            }
        }

        if (FarmApp.CONFIG != null) {
            FarmApp.saveConfig();
            FarmApp.clearLiveDiffs();
        }
        return report;
    }

    private static <T> List<T> decodeItems(Object raw, Class<T> cls) {
        List<T> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            try {
                T t = gson.fromJson(gson.toJson(o), cls);
                if (t != null) out.add(t);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
