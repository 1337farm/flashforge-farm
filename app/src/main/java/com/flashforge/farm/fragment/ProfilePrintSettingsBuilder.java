package com.flashforge.farm.fragment;

import java.util.ArrayList;
import java.util.List;

import com.flashforge.farm.R;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.slic3r.ConfigOptionDef;
import com.flashforge.farm.slic3r.PrintConfigDef;
import com.flashforge.farm.utils.ViewUtils;

public final class ProfilePrintSettingsBuilder {
    private final PrintConfigFragment fragment;
    private final PrintConfigDef def;

    private ProfilePrintSettingsBuilder(PrintConfigFragment fragment) {
        this.fragment = fragment;
        this.def = PrintConfigDef.getInstance();
    }

    public static List<ProfileListFragment.OptionElement> build(PrintConfigFragment fragment) {
        return new ProfilePrintSettingsBuilder(fragment).build();
    }

    private List<ProfileListFragment.OptionElement> build() {
        List<ProfileListFragment.OptionElement> items = new ArrayList<>();
        items.addAll(buildQuality());
        items.addAll(buildStrength());
        items.addAll(buildSpeed());
        items.addAll(buildSupport());
        items.addAll(buildMultimaterial());
        items.addAll(buildOthers());
        return items;
    }

    private List<ProfileListFragment.OptionElement> buildCategory(int icon, String title, SectionAppender appender) {
        List<ProfileListFragment.OptionElement> items = new ArrayList<>();
        items.add(fragment.new OptionElement(icon, title));
        appender.append(items);
        return items;
    }

    private interface SectionAppender {
        void append(List<ProfileListFragment.OptionElement> items);
    }

    private void addSection(List<ProfileListFragment.OptionElement> items, String title, String... keys) {
        List<ProfileListFragment.OptionElement> section = new ArrayList<>();
        for (String key : keys) {
            addOption(section, key);
        }
        if (section.isEmpty()) {
            return;
        }
        items.add(fragment.new OptionElement(new ProfileListFragment.SubHeader(title)));
        items.addAll(section);
        items.add(fragment.new OptionElement(new SpaceItem(0, ViewUtils.dp(4))));
    }

    private void addOption(List<ProfileListFragment.OptionElement> items, String key) {
        ConfigOptionDef opt = def.options.get(key);
        if (opt != null) {
            items.add(fragment.new OptionElement(opt));
        } else {
            // Engine-driven visibility: stale keys (renamed/removed upstream)
            // must show up in logcat instead of silently emptying a section.
            android.util.Log.w("PrintSettingsBuilder", "option missing from engine defs: " + key);
        }
    }

    private List<ProfileListFragment.OptionElement> buildQuality() {
        return buildCategory(
                R.drawable.print_layers_28,
                "Quality",
                items -> {
                    addSection(items, "Layer height", "layer_height", "first_layer_height", "variable_layer_height");
                    addSection(items, "Line width", "extrusion_width", "first_layer_extrusion_width", "perimeter_extrusion_width", "external_perimeter_extrusion_width", "infill_extrusion_width", "solid_infill_extrusion_width", "top_infill_extrusion_width", "support_material_extrusion_width");
                    addSection(items, "Seam", "seam_position", "staggered_inner_seams");
                    addSection(items, "Scarf joint seam", "scarf_seam_placement", "scarf_seam_entire_loop", "scarf_seam_length", "scarf_seam_max_segment_length", "scarf_seam_on_inner_perimeters", "scarf_seam_only_on_smooth", "scarf_seam_start_height");
                    addSection(items, "Precision", "slice_closing_radius", "resolution", "elefant_foot_compensation");
                    addSection(items, "Ironing", "ironing_type", "ironing_flowrate", "ironing_spacing");
                    addSection(items, "Wall generator", "perimeter_generator", "wall_transition_angle", "wall_transition_filter_deviation", "wall_transition_length", "wall_distribution_count", "min_bead_width", "min_feature_size");
                    addSection(items, "Walls and surfaces", "extra_perimeters_on_overhangs", "ensure_vertical_shell_thickness", "avoid_crossing_perimeters", "avoid_crossing_perimeters_max_detour", "thin_walls", "top_one_perimeter_type", "only_one_perimeter_first_layer", "gap_fill_enabled", "infill_first");
                    addSection(items, "Overhangs", "overhangs");
                    addSection(items, "Bridging", "thick_bridges", "bridge_angle");
                }
        );
    }

    private List<ProfileListFragment.OptionElement> buildStrength() {
        return buildCategory(
                R.drawable.print_infill_28,
                "Strength",
                items -> {
                    addSection(items, "Walls", "perimeters", "extra_perimeters", "thin_walls");
                    addSection(items, "Top/bottom shells", "top_solid_layers", "top_solid_min_thickness", "bottom_solid_layers", "bottom_solid_min_thickness", "top_fill_pattern", "bottom_fill_pattern");
                    addSection(items, "Infill", "fill_density", "fill_pattern", "fill_angle", "infill_anchor", "infill_anchor_max", "automatic_infill_combination", "automatic_infill_combination_max_layer_height", "solid_infill_below_area", "infill_overlap", "gap_fill_enabled", "infill_first");
                    addSection(items, "Advanced", "bridge_flow_ratio", "solid_infill_below_area");
                }
        );
    }

    private List<ProfileListFragment.OptionElement> buildSpeed() {
        return buildCategory(
                R.drawable.menu_orientation_rotation_28,
                "Speed",
                items -> {
                    addSection(items, "Speed", "perimeter_speed", "small_perimeter_speed", "external_perimeter_speed", "infill_speed", "solid_infill_speed", "top_solid_infill_speed", "support_material_speed", "support_material_interface_speed", "bridge_speed", "gap_fill_speed", "ironing_speed", "travel_speed", "travel_speed_z", "first_layer_speed", "first_layer_infill_speed", "max_volumetric_speed");
                    addSection(items, "Overhang speed", "enable_dynamic_overhang_speeds", "overhang_speed_0", "overhang_speed_1", "overhang_speed_2", "overhang_speed_3");
                    addSection(items, "Acceleration", "perimeter_acceleration", "external_perimeter_acceleration", "top_solid_infill_acceleration", "solid_infill_acceleration", "infill_acceleration", "bridge_acceleration", "first_layer_acceleration", "travel_acceleration", "default_acceleration");
                    addSection(items, "Junction deviation", "machine_max_junction_deviation");
                    addSection(items, "Pressure advance", "pressure_advance");
                }
        );
    }

    private List<ProfileListFragment.OptionElement> buildSupport() {
        return buildCategory(
                R.drawable.print_support_28,
                "Support",
                items -> {
                    addSection(items, "Support", "support_material", "support_material_style", "support_material_threshold", "support_material_enforce_layers", "support_material_pattern", "support_material_contact_distance", "support_material_bottom_contact_distance", "support_material_spacing", "support_material_angle", "support_material_buildplate_only", "support_material_xy_spacing");
                    addSection(items, "Raft", "support_material_first_layer_density", "support_material_first_layer_expansion", "raft_layers", "raft_contact_distance", "raft_expansion");
                    addSection(items, "Support filament", "support_material_extruder", "support_material_interface_extruder");
                    addSection(items, "Advanced", "support_material_interface_layers", "support_material_bottom_interface_layers", "support_material_interface_pattern", "support_material_interface_spacing", "support_material_interface_contact_loops", "support_material_interface_speed");
                }
        );
    }

    private List<ProfileListFragment.OptionElement> buildMultimaterial() {
        return buildCategory(
                R.drawable.slot_filament_28,
                "Multimaterial",
                items -> {
                    addSection(items, "Prime tower", "wipe_tower", "wipe_tower_bridging", "wipe_tower_cone_angle", "wipe_tower_extra_spacing", "wipe_tower_extra_flow", "wipe_tower_no_sparse_layers", "single_extruder_multi_material_priming");
                    addSection(items, "Filament for Features", "perimeter_extruder", "infill_extruder", "solid_infill_extruder", "support_material_extruder", "support_material_interface_extruder", "wipe_tower_extruder");
                    addSection(items, "Ooze prevention", "ooze_prevention", "standby_temperature_delta");
                    addSection(items, "Advanced", "interface_shells", "mmu_segmented_region_max_width", "mmu_segmented_region_interlocking_depth");
                }
        );
    }

    private List<ProfileListFragment.OptionElement> buildOthers() {
        return buildCategory(
                R.drawable.settings_outline_28,
                "Others",
                items -> {
                    addSection(items, "Skirt", "skirts", "skirt_distance", "skirt_height", "draft_shield");
                    addSection(items, "Brim", "brim_type", "brim_width");
                    addSection(items, "Special mode", "spiral_vase");
                    addSection(items, "Fuzzy Skin", "fuzzy_skin", "fuzzy_skin_thickness", "fuzzy_skin_point_dist", "fuzzy_skin_noise_type", "fuzzy_skin_scale", "fuzzy_skin_octaves", "fuzzy_skin_persistence", "fuzzy_skin_first_layer");
                    addSection(items, "G-code output", "gcode_comments", "gcode_label_objects");
                    addSection(items, "Notes", "notes");
                    addSection(items, "Profile dependencies", "compatible_printers", "compatible_printers_condition");
                }
        );
    }

}
