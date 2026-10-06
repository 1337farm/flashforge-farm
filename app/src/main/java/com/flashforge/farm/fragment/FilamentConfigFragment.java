package com.flashforge.farm.fragment;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.config.ConfigObject;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.slic3r.PrintConfigDef;
import com.flashforge.farm.slic3r.Slic3rLocalization;
import com.flashforge.farm.slic3r.Slic3rUtils;
import com.flashforge.farm.utils.ViewUtils;

public class FilamentConfigFragment extends ProfileListFragment {
    private List<ProfileListItem> compatItems;
    private String lastPrinter;
    private String lastPrint;
    private int lastUid;
    private ConfigObject currentConfig;

    @Override
    protected int getProfileListType() {
        return ConfigObject.PROFILE_LIST_FILAMENT;
    }

    @Override
    protected boolean useTabs() {
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        onResetConfig();
    }

    @Override
    protected List<ProfileListItem> getItems(boolean filter) {
        List<ConfigObject> list = FarmApp.CONFIG.filamentConfigs;
        if (filter) {
            String printer = FarmApp.CONFIG.presets.get("printer");
            String print = FarmApp.CONFIG.presets.get("print");
            if (Objects.equals(lastPrinter, printer) && Objects.equals(lastPrint, print) && compatItems != null && lastUid == FarmApp.CONFIG_UID) {
                return compatItems;
            }

            List<ProfileListItem> nList = new ArrayList<>(list.size());
            ConfigObject printerObj = FarmApp.CONFIG.findPrinter(printer);
            String model = printerObj != null ? printerObj.get("printer_model") : null;
            String nozzle = printerObj != null ? printerObj.get("printer_variant") : null;
            if (printerObj != null && nozzle == null) nozzle = Slic3rUtils.firstNozzleDiameter(printerObj.get("nozzle_diameter"));
            Slic3rUtils.ConfigChecker checker = new Slic3rUtils.ConfigChecker(printerObj.serialize());
            ConfigObject printObj = FarmApp.CONFIG.findPrint(print);
            Slic3rUtils.ConfigChecker printChecker = printObj != null ? new Slic3rUtils.ConfigChecker(printObj.serialize()) : null;
            java.util.Set<String> seenTitles = new java.util.HashSet<>();
            for (ConfigObject obj : list) {
                boolean okPrinter = Slic3rUtils.isPrinterCompatible(obj.getTitle(), obj.get("compatible_printers"), obj.get("compatible_printers_condition"), printer, model, nozzle, checker);
                boolean okPrint = TextUtils.isEmpty(print)
                        || Slic3rUtils.isCompatible(obj.get("compatible_prints"), obj.get("compatible_prints_condition"), print, printChecker);
                if (okPrinter && okPrint && seenTitles.add(obj.getTitle())) {
                    // Collapse duplicate names the per-printer import created (e.g. multiple "Generic PLA").
                    nList.add(obj);
                }
            }
            if (printChecker != null) printChecker.release();
            checker.release();
            lastPrinter = printer;
            lastPrint = print;
            lastUid = FarmApp.CONFIG_UID;
            return compatItems = nList;
        }
        return new ArrayList<ProfileListItem>(list);
    }

    @Override
    protected List<OptionElement> getConfigItems() {
        PrintConfigDef def = PrintConfigDef.getInstance();
        return Arrays.asList(
                new OptionElement(R.drawable.slot_filament_28, Slic3rLocalization.getString("Filament")),
                new OptionElement(new SubHeader("Filament")),
                new OptionElement(def.options.get("filament_colour")),
                new OptionElement(def.options.get("filament_diameter")),
                new OptionElement(def.options.get("filament_density")),
                new OptionElement(def.options.get("filament_cost")),
                new OptionElement(def.options.get("filament_spool_weight")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Temperature")),
                new OptionElement(def.options.get("idle_temperature")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Nozzle")),
                new OptionElement(def.options.get("first_layer_temperature")),
                new OptionElement(def.options.get("temperature")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Bed")),
                new OptionElement(def.options.get("first_layer_bed_temperature")),
                new OptionElement(def.options.get("bed_temperature")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Chamber")),
                new OptionElement(def.options.get("chamber_temperature")),
                new OptionElement(def.options.get("chamber_minimal_temperature")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(R.drawable.mode_fan_24, Slic3rLocalization.getString("Cooling")),
                new OptionElement(new SubHeader("Enable")),
                new OptionElement(def.options.get("cooling")),
                new OptionElement(def.options.get("fan_always_on")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Fan speed")),
                new OptionElement(def.options.get("min_fan_speed")),
                new OptionElement(def.options.get("max_fan_speed")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(def.options.get("disable_fan_first_layers")),
                new OptionElement(def.options.get("full_fan_speed_layer")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Cooling thresholds")),
                new OptionElement(def.options.get("fan_below_layer_time")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Dynamic fan speeds")),
                new OptionElement(def.options.get("enable_dynamic_fan_speeds")),
                new OptionElement(def.options.get("overhang_fan_speed_0")),
                new OptionElement(def.options.get("overhang_fan_speed_1")),
                new OptionElement(def.options.get("overhang_fan_speed_2")),
                new OptionElement(def.options.get("overhang_fan_speed_3")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(R.drawable.settings_outline_28, Slic3rLocalization.getString("Advanced")),
                new OptionElement(new SubHeader("Filament properties")),
                new OptionElement(def.options.get("filament_type")),
                new OptionElement(def.options.get("filament_soluble")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Pressure advance")),
                new OptionElement(def.options.get("pressure_advance")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Filament change")),
                new OptionElement(def.options.get("high_current_on_filament_swap")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Print speed override")),
                new OptionElement(def.options.get("filament_max_volumetric_speed")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(def.options.get("filament_infill_max_speed")),
                new OptionElement(def.options.get("filament_infill_max_crossing_speed")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Shrinkage compensation")),
                // x_size_compensation and y_size_compensation provide per-axis shrinkage adjustments.
                new OptionElement(def.options.get("filament_shrinkage_compensation_z")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Wipe tower parameters")),
                new OptionElement(def.options.get("filament_minimal_purge_on_wipe_tower")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Toolchange parameters with single extruder MM printers")),
                new OptionElement(def.options.get("filament_loading_speed_start")),
                new OptionElement(def.options.get("filament_loading_speed")),
                new OptionElement(def.options.get("filament_unloading_speed_start")),
                new OptionElement(def.options.get("filament_unloading_speed")),
                new OptionElement(def.options.get("filament_toolchange_delay")),
                new OptionElement(def.options.get("filament_cooling_moves")),
                new OptionElement(def.options.get("filament_cooling_initial_speed")),
                new OptionElement(def.options.get("filament_cooling_final_speed")),
                new OptionElement(def.options.get("filament_stamping_loading_speed")),
                new OptionElement(def.options.get("filament_stamping_distance")),
                new OptionElement(def.options.get("filament_purge_multiplier")),
                new OptionElement(def.options.get("filament_ramming_parameters")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Toolchange parameters with multi extruder MM printers")),
                new OptionElement(def.options.get("filament_multitool_ramming")),
                new OptionElement(def.options.get("filament_multitool_ramming_volume")),
                new OptionElement(def.options.get("filament_multitool_ramming_flow")),

                new OptionElement(R.drawable.settings_outline_28, Slic3rLocalization.getString("Filament Overrides")),
                new OptionElement(new SubHeader("Travel lift")),
                new OptionElement(def.options.get("retract_lift")),
                new OptionElement(def.options.get("retract_lift_above")),
                new OptionElement(def.options.get("retract_lift_below")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Retraction")),
                new OptionElement(def.options.get("retract_length")),
                new OptionElement(def.options.get("retract_speed")),
                new OptionElement(def.options.get("deretract_speed")),
                new OptionElement(def.options.get("retract_restart_extra")),
                new OptionElement(def.options.get("retract_layer_change")),
                new OptionElement(def.options.get("wipe")),
                new OptionElement(def.options.get("retract_before_wipe")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Retraction when tool is disabled")),
                new OptionElement(def.options.get("retract_length_toolchange")),
                new OptionElement(def.options.get("retract_restart_extra_toolchange")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(R.drawable.settings_outline_28, Slic3rLocalization.getString("Custom G-code")),
                new OptionElement(new SubHeader("Start G-code")),
                new OptionElement(def.options.get("start_filament_gcode")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("End G-code")),
                new OptionElement(def.options.get("end_filament_gcode")),

                new OptionElement(R.drawable.note_pen_outline_96, Slic3rLocalization.getString("Notes")),
                new OptionElement(new SubHeader("Notes")),
                new OptionElement(def.options.get("filament_notes")),

                new OptionElement(R.drawable.wrench_outline_28, Slic3rLocalization.getString("Dependencies")),
                new OptionElement(new SubHeader("Profile dependencies")),
                new OptionElement(def.options.get("compatible_printers_condition")),
                new OptionElement(def.options.get("compatible_prints")),
                new OptionElement(def.options.get("compatible_prints_condition"))

        );
    }

    @Override
    protected void cloneCurrentProfile() {
        ConfigObject obj = new ConfigObject(FarmApp.INSTANCE.getString(R.string.SettingsProfileCopy, currentConfig.getTitle()));
        obj.values.putAll(currentConfig.values);
        currentConfig = new ConfigObject(obj);

        FarmApp.CONFIG.filamentConfigs.add(obj);
        FarmApp.CONFIG.presets.put("filament", obj.getTitle());
        FarmApp.saveConfig();
        FarmApp.getCurrentConfigFile().delete();

        currentConfig = new ConfigObject(obj);
        dropdownView.setTitle(getCurrentConfig().getTitle());
        compatItems = null;
    }

    @Override
    protected void deleteCurrentProfile() {
        compatItems = null;
        FarmApp.CONFIG.filamentConfigs.remove(FarmApp.CONFIG.findFilament(currentConfig.getTitle()));
        selectItem(getItems(true).get(0));

        dropdownView.setTitle(getCurrentConfig().getTitle());
    }

    @Override
    protected void onApplyConfig(String title) {
        compatItems = null;
        ConfigObject obj = FarmApp.CONFIG.findFilament(currentConfig.getTitle());
        obj.setTitle(title);
        obj.values.putAll(currentConfig.values);
        currentConfig.setTitle(title);

        FarmApp.CONFIG.presets.put("filament", title);
        FarmApp.saveConfig();
        FarmApp.getCurrentConfigFile().delete();

        dropdownView.setTitle(title);
    }

    @Override
    protected void onResetConfig() {
        ConfigObject filament = FarmApp.CONFIG.findFilament(FarmApp.CONFIG.presets.get("filament"));
        if (filament == null) {
            filament = !FarmApp.CONFIG.filamentConfigs.isEmpty() ? FarmApp.CONFIG.filamentConfigs.get(0) : ConfigObject.createCustomFilamentProfile();
        }
        currentConfig = new ConfigObject(filament);
    }

    @Override
    protected ConfigObject getCurrentConfig() {
        return currentConfig;
    }

    @Override
    protected int getTitle() {
        return R.string.SlotFilamentConfigTooltip;
    }

    @Override
    protected void selectItem(ProfileListItem item) {
        currentConfig = new ConfigObject((ConfigObject) item);
        FarmApp.CONFIG.presets.put("filament", item.getTitle());
        FarmApp.saveConfig();
    }
}
