package scripts.tests;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.config.ConfigObject;
import com.flashforge.farm.slic3r.ConfigOptionDef;
import com.flashforge.farm.slic3r.PrintConfigDef;
import com.flashforge.farm.slic3r.Slic3rConfigWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Headless reproduction of the app's config-generation path (FarmApp.buildCurrentConfigObject +
 * genCurrentConfig, FarmApp.java:478-549) against a real vendor profile INI, emitting the exact
 * slic3r_current.ini that is handed to the native engine at slice time.
 *
 * The merge logic here deliberately mirrors FarmApp.java:478-549 so the emitted INI is byte-for-byte
 * what a farm device would produce for the supplied presets. LIVE_DIFF_* edits are empty on a fresh
 * process. AUTO_BRIM_SELECTED re-maps brim_type auto_brim -> outer_only exactly like genCurrentConfig.
 *
 * Usage: java -Dflashforge.ini=<vendor.ini> scripts.tests.RealConfigDump <out.ini>
 *   vendor.ini     the app's Flashforge.ini asset
 *   out.ini        where the generated slic3r_current.ini is written (then fed to the engine harness)
 */
public class RealConfigDump {
    public static void main(String[] args) throws Exception {
        String iniPath = System.getProperty("flashforge.ini");
        if (iniPath == null) {
            System.err.println("Set -Dflashforge.ini=<vendor.ini>");
            System.exit(2);
        }
        if (args.length != 1) {
            System.err.println("usage: RealConfigDump <out.ini>");
            System.exit(2);
        }

        FarmApp.INSTANCE = new FarmApp();
        FarmApp.CONFIG = new Slic3rConfigWrapper(new File(iniPath));

        // Optional per-preset overrides (used by the sweep harness for finding the failing combo).
        String pPrinter = System.getProperty("preset.printer");
        String pPrint = System.getProperty("preset.print");
        String pFilament = System.getProperty("preset.filament");

        // First-run onboarding defaults: first printer, print, and filament profiles.
        if (FarmApp.CONFIG.presets.get("printer") == null) {
            FarmApp.CONFIG.presets.put("printer", pPrinter != null ? pPrinter
                    : (FarmApp.CONFIG.printerConfigs.isEmpty() ? "MyPrinter" : FarmApp.CONFIG.printerConfigs.get(0).getTitle()));
        }
        if (FarmApp.CONFIG.presets.get("print") == null) {
            FarmApp.CONFIG.presets.put("print", pPrint != null ? pPrint
                    : (FarmApp.CONFIG.printConfigs.isEmpty() ? "Standard" : FarmApp.CONFIG.printConfigs.get(0).getTitle()));
        }
        if (FarmApp.CONFIG.presets.get("filament") == null) {
            FarmApp.CONFIG.presets.put("filament", pFilament != null ? pFilament
                    : (FarmApp.CONFIG.filamentConfigs.isEmpty() ? "Generic" : FarmApp.CONFIG.filamentConfigs.get(0).getTitle()));
        }

        ConfigObject singleObject = buildCurrentConfigObject();
        String iniText = serializeForEngine(singleObject);

        FileOutputStream fos = new FileOutputStream(new File(args[0]));
        fos.write(iniText.getBytes(StandardCharsets.UTF_8));
        fos.close();

        System.out.println("CONFIG_FILE=" + new File(args[0]).getAbsolutePath());
        System.out.println("AUTO_BRIM=" + "auto_brim".equals(singleObject.get("brim_type")));
        System.out.println("PRESETS printer=" + FarmApp.CONFIG.presets.get("printer")
                + " print=" + FarmApp.CONFIG.presets.get("print")
                + " filament=" + FarmApp.CONFIG.presets.get("filament"));
    }

    // Verbatim replica of FarmApp.buildCurrentConfigObject() (FarmApp.java:478-531).
    static ConfigObject buildCurrentConfigObject() throws Exception {
        return buildCurrentConfigObject(FarmApp.CONFIG,
                FarmApp.CONFIG.presets.get("printer"),
                FarmApp.CONFIG.presets.get("print"),
                System.getProperty("defaults.ini"));
    }

    /** Shared merge used by RealConfigDump and SweepGen (mirror of FarmApp.java:478-531). */
    static ConfigObject buildCurrentConfigObject(Slic3rConfigWrapper cfg, String printerTitle, String printTitle, String nativeDefaultsPath)
            throws Exception {
        ConfigObject singleObject = new ConfigObject();
        ConfigObject printer = cfg.findPrinter(printerTitle);
        if (printer == null) {
            printer = !cfg.printerConfigs.isEmpty() ? cfg.printerConfigs.get(0) : ConfigObject.createCustomPrinterProfile();
        }
        singleObject.values.putAll(printer.values);

        ConfigObject print = cfg.findPrint(printTitle);
        if (print != null) {
            singleObject.values.putAll(print.values);
        }
        for (int i = 0; i < (printer != null ? printer.getExtruderCount() : 1); i++) {
            String presetKey = i == 0 ? "filament" : "filament_" + i;
            String filamentTitle = cfg.presets.get(presetKey);
            if (filamentTitle == null) filamentTitle = cfg.presets.get("filament");

            ConfigObject filament = cfg.findFilament(filamentTitle);
            if (filament != null) {
                for (java.util.Map.Entry<String, String> entry : filament.values.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (i == 0) {
                        singleObject.values.put(key, val);
                    } else {
                        String existing = singleObject.values.get(key);
                        if (existing != null) {
                            singleObject.values.put(key, existing + "," + val);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < i; j++) sb.append(",");
                            sb.append(val);
                            singleObject.values.put(key, sb.toString());
                        }
                    }
                }
            }
        }

        // LIVE_DIFF_* edits are empty on a fresh process.

        if (nativeDefaultsPath != null) {
            // The real app populates PrintConfigDef.options from the native engine via
            // Native.get_print_config_def (farm's full_print_config). Headlessly we supply the same
            // defaults from the C++ harness (--dump-defaults). Defaults are only applied to keys the
            // merged presets did not set, exactly like the app's fill loop (FarmApp.java:522-527).
            java.util.Map<String, String> nativeDefaults = readIni(new File(nativeDefaultsPath));
            for (java.util.Map.Entry<String, String> en : nativeDefaults.entrySet()) {
                if (singleObject.get(en.getKey()) == null
                        && !PrintConfigDef.SKIP_DEFAULT_OPTIONS.contains(en.getKey())
                        && en.getValue() != null) {
                    singleObject.put(en.getKey(), en.getValue());
                }
            }
            // applyOrcaLabels injects auto_brim as the brim_type default when the engine enum lacks it
            // (PrintConfigDef.java:173-197); genCurrentConfig then re-maps it to outer_only.
            if (singleObject.get("brim_type") == null) {
                singleObject.put("brim_type", "auto_brim");
            }
        } else {
            PrintConfigDef def = PrintConfigDef.getInstance();
            for (java.util.Map.Entry<String, ConfigOptionDef> en : def.options.entrySet()) {
                if (singleObject.get(en.getKey()) == null && !PrintConfigDef.SKIP_DEFAULT_OPTIONS.contains(en.getKey()) && en.getValue().defaultValue != null) {
                    singleObject.put(en.getKey(), en.getValue().defaultValue);
                }
            }
        }

        return singleObject;
    }

    /** genCurrentConfig's auto_brim re-map (FarmApp.java:538-548), returns the serialized INI. */
    static String serializeForEngine(ConfigObject singleObject) {
        boolean autoBrim = "auto_brim".equals(singleObject.get("brim_type"));
        if (autoBrim) {
            singleObject.put("brim_type", "outer_only");
        }
        return singleObject.serialize();
    }

    private static java.util.Map<String, String> readIni(File f) throws Exception {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf(" = ");
            if (i != -1) {
                String key = line.substring(0, i).trim();
                String value = line.substring(i + 3).trim().replace("\\n", "\n");
                map.put(key, value);
            }
        }
        return map;
    }
}