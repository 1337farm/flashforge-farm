#!/usr/bin/env python3
"""Generate the multi-dialect profile translation table (import boundary).

Single source of truth for mapping foreign slicer profiles into native
PrusaSlicer 3.0 INI. This is an IMPORT feature (the same idea as PrusaSlicer's
own "Import config from other slicers") and runs once at ingest, never in the
slice path. The slicing core stays native: Java hands the engine an INI string
produced here, and DynamicPrintConfig::load(ini) does the rest.

Dialects (target is always `prusaslicer3`):
  - legacy      Legacy JSON (.prusa_printer / .prusa_filament)
  - compat      Vendor-compat JSON (legacy key space + vendor extras)
  - prusaslicer legacy PrusaSlicer INI
  - slic3r      Slic3r / PrusaSlicer-2.x INI
  - superslicer SuperSlicer INI (legacy Prusa key space + SS extras)

Grounding: the legacy/compat rename is the INVERSION of the hand-written
`ConfigObject.buildKeyMigration()` the app already ships (parsed from source).
Overlays below express the cases a pure rename cannot:
  - LEGACY_REMOVED      Legacy-only keys with no native target -> dropped on import.
  - LEGACY_ENUM_REMAPS  keys whose VALUE (enum labels) must be rewritten.
  - PRUSA3_RENAMES    renames PrusaSlicer 3.0 introduces over the legacy space
                      (populate from the 3.0 print_config_def; empty = pass-through).
  - GCODE_PLACEHOLDER_RENAMES  placeholder tokens inside *_gcode values.

Outputs:
  --out-json  canonical machine-readable table (checked in / shipped in assets)
  --out-java  generated Java consumed by the runtime importer.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# --- Legacy-only keys: no native PrusaSlicer 3.0 equivalent -> dropped ----------
LEGACY_REMOVED: dict[str, str] = {
    "single_extruder_multi_material": "Legacy SEMM toggle; no native equivalent",
    "flush_volumes_matrix": "Legacy purge-matrix layout only",
    "flush_multiplier": "Legacy purge multiplier",
    "flush_volumes_vector": "Legacy purge vector",
    "filament_map": "Legacy virtual->physical filament map",
    "curr_bed_type": "Legacy bed-slot selector enum",
    "cool_plate_temp": "Legacy per-plate bed temp slot",
    "cool_plate_temp_initial_layer": "Legacy per-plate bed temp slot",
    "eng_plate_temp": "Legacy per-plate bed temp slot",
    "eng_plate_temp_initial_layer": "Legacy per-plate bed temp slot",
    "hot_plate_temp": "Legacy per-plate bed temp slot",
    "hot_plate_temp_initial_layer": "Legacy per-plate bed temp slot",
    "textured_plate_temp": "Legacy per-plate bed temp slot",
    "textured_plate_temp_initial_layer": "Legacy per-plate bed temp slot",
}

# --- Legacy keys whose VALUE must be rewritten (not just the key) ---------------
# Each entry: {"to": target key (override inversion), "values": {src: dst}, "note": ...}
# A leading '>' in "to" means: leave the key-name as-is (no rename), only rewrite value.
LEGACY_ENUM_REMAPS: dict[str, dict] = {
    "gap_fill_target": {
        "values": {"everywhere": "1", "topbottom": "1", "nowhere": "0"},
        "note": "Legacy enum -> legacy bool gap_fill_enabled",
    },
    "wall_sequence": {
        "to": "external_perimeters_first",
        "values": {
            "inner wall/outer wall": "0", "inner/outer wall": "0", "inner/outer": "0",
            "outer wall/inner wall": "1", "outer/inner wall": "1", "outer/inner": "1",
        },
        "note": "Legacy wall_sequence enum -> Prusa external_perimeters_first bool",
    },
    "print_sequence": {
        "values": {"by layer": "0", "by object": "1", "by_layer": "0", "by_object": "1"},
        "note": "Legacy print_sequence enum -> Prusa complete_objects bool",
    },
    "ironing_type": {
        "to": "ironing",
        "kind": "type_changed",
        "transform": "ironing_type",
        "note": "Legacy ironing_type enum -> Prusa ironing bool (+ type when valid)",
    },
    # Legacy bools that became 3.0 enums: map both spellings; anything else
    # passes through so the loader reports it verbatim instead of guessing.
    "support_material": {
        "to": "support_material",
        "values": {"1": "everywhere", "0": "none", "true": "everywhere", "false": "none"},
        "note": "Legacy bool -> 3.0 enum {none, enforcers_only, everywhere}",
    },
    "enable_support": {
        "to": "support_material",
        "values": {"1": "everywhere", "0": "none", "true": "everywhere", "false": "none"},
        "note": "Orca bool -> 3.0 support_material enum (same as support_material)",
    },
    "gcode_label_objects": {
        "to": "gcode_label_objects",
        "values": {"1": "octoprint", "0": "disabled", "true": "octoprint", "false": "disabled"},
        "note": "Legacy bool -> 3.0 enum; the 2.x feature emitted OctoPrint comments",
    },
    # Orca-only labels with no 3.0 counterpart: map to the closest setting.
    "brim_type": {
        "to": "brim_type",
        "values": {"auto_brim": "outer_and_inner"},
        "note": "Orca auto brim -> adhesion-safe superset (adds inner where useful)",
    },
    "ensure_vertical_shell_thickness": {
        "to": "ensure_vertical_shell_thickness",
        "values": {"ensure_all": "enabled"},
        "note": "Strongest guarantee maps to enabled",
    },
    "fuzzy_skin": {
        "to": "fuzzy_skin",
        "values": {"disabled_fuzzy": "none"},
        "note": "Disabled state maps to none",
    },
    "top_surface_pattern": {
        "to": "top_fill_pattern",
        "values": {"monotonicline": "monotoniclines"},
        "note": "Orca singular spelling -> 3.0 monotoniclines",
    },
    "enable_arc_fitting": {
        "to": "arc_fitting",
        "values": {"1": "emit_center", "0": "disabled", "true": "emit_center", "false": "disabled"},
        "note": "Legacy bool -> 3.0 enum {disabled, emit_center}",
    },
    "sparse_infill_pattern": {
        "to": "fill_pattern",
        "values": {"crosshatch": "grid"},
        "note": "Orca Cross Hatch has no 3.0 counterpart; grid is the closest crossing-lines pattern",
    },
    "support_style": {
        "to": "support_material_style",
        "values": {"default": "grid"},
        "note": "Orca default (normal) supports -> grid",
    },
    "support_base_pattern": {
        "to": "support_material_pattern",
        "values": {"default": "rectilinear"},
        "note": "Orca default interface pattern -> rectilinear",
    },
    "pressure_advance": {
        "to": "pressure_advance",
        "kind": "type_changed",
        "transform": "pressure_advance",
        "note": "Legacy float K-value -> 3.0 enum (nonzero = enabled)",
    },
    # Tree/normal support is a two-key mapping in Prusa; handled as a converter-side
    # transform. Mark it type_changed so the dialect map flags it.
    "support_type": {
        "kind": "type_changed",
        "transform": "support_type",
        "note": "Legacy normal/tree(auto) enum -> Prusa support_style (snug/organic)",
    },
}

# --- PrusaSlicer 3.0 renames over the legacy Prusa key space ------------------
# Populate from the 3.0 print_config_def / release notes. Empty = identity.
PRUSA3_RENAMES: dict[str, str] = {}

# --- gcode placeholder tokens that are not config keys ------------------------
# Legacy/compat start/end gcode embeds these tokens; Prusa uses brace placeholders.
# Applied to any *_gcode value during import.
GCODE_PLACEHOLDER_RENAMES: dict[str, str] = {
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "bed_temperature_initial_layer_single": "first_layer_bed_temperature",
    "bed_temperature_initial_layer": "first_layer_bed_temperature",
}

RE_PUT = re.compile(r'm\.put\(\s*"([A-Za-z0-9_]+)"\s*,\s*"([A-Za-z0-9_]+)"\s*\)\s*;')


def parse_native_to_foreign(config_object_path: Path) -> dict[str, str]:
    text = config_object_path.read_text(errors="replace")
    # Scope to buildKeyMigration() body only: a whole-file regex also matches
    # `custom.put(...)` in createCustomPrinter/FilamentProfile() (the trailing
    # 'm' of "custom" looks like `m.put`) and inverts value literals into keys.
    marker = "buildKeyMigration()"
    if marker not in text:
        print(f"WARNING: no buildKeyMigration() found in {config_object_path}", file=sys.stderr)
        return {}
    start = text.index("{", text.index(marker))
    end = text.index("return m;", start)
    body = text[start:end]

    native_to_foreign: dict[str, str] = {}
    for legacy, foreign in RE_PUT.findall(body):
        native_to_foreign[legacy] = foreign
    if not native_to_foreign:
        print(f"WARNING: no m.put(...) pairs parsed from {config_object_path}", file=sys.stderr)
    return native_to_foreign


def invert(native_to_foreign: dict[str, str]) -> dict[str, str]:
    foreign_to_native: dict[str, str] = {}
    for legacy, foreign in native_to_foreign.items():
        foreign_to_native.setdefault(foreign, legacy)
    return foreign_to_native


def build_table(
    foreign_to_native: dict[str, str],
    legacy_keys: set[str],
    dialect: str,
) -> dict:
    """Build one dialect's {sourceKey: row} table."""
    out: dict[str, dict] = {}

    if dialect in ("legacy", "compat"):
        for foreign, legacy in foreign_to_native.items():
            target = PRUSA3_RENAMES.get(legacy, legacy)
            spec = LEGACY_ENUM_REMAPS.get(foreign)
            if spec:
                kind = spec.get("kind", "enum_remap")
                row = {"key": spec.get("to", target), "kind": kind,
                       "note": spec.get("note", "")}
                if "values" in spec:
                    row["values"] = spec["values"]
                if "transform" in spec:
                    row["transform"] = spec["transform"]
                out[foreign] = row
            else:
                out[foreign] = {"key": target, "kind": "rename", "note": ""}
        # Legacy-only keys not reachable via KEY_MIGRATION inversion.
        for foreign, note in LEGACY_REMOVED.items():
            out[foreign] = {"key": None, "kind": "removed", "note": note}
        for foreign, spec in LEGACY_ENUM_REMAPS.items():
            if foreign not in out:
                kind = spec.get("kind", "enum_remap")
                row = {"key": spec.get("to"), "kind": kind, "note": spec.get("note", "")}
                if "values" in spec:
                    row["values"] = spec["values"]
                if "transform" in spec:
                    row["transform"] = spec["transform"]
                out[foreign] = row
    else:
        for key in sorted(legacy_keys):
            target = PRUSA3_RENAMES.get(key, key)
            kind = "rename" if key in PRUSA3_RENAMES else "identity"
            row = {"key": target, "kind": kind, "note": ""}
            # Value remaps keyed by this dialect's source spelling apply
            # here too (e.g. a SuperSlicer INI carries support_material as
            # a 2.x bool just like the legacy dialect does). Farm-spelled
            # rows (enable_arc_fitting, ...) never match this key space and
            # are skipped naturally.
            spec = LEGACY_ENUM_REMAPS.get(key)
            if spec:
                if "values" in spec:
                    row["values"] = spec["values"]
                if spec.get("transform"):
                    row["kind"] = "type_changed"
                    row["transform"] = spec["transform"]
                if spec.get("to"):
                    row["key"] = spec["to"]
            out[key] = row

    return out


def build_dialects(native_to_foreign: dict[str, str]) -> dict[str, dict]:
    foreign_to_native = invert(native_to_foreign)
    legacy_keys = set(native_to_foreign.keys())
    dialects = {
        "legacy": build_table(foreign_to_native, legacy_keys, "legacy"),
        "compat": build_table(foreign_to_native, legacy_keys, "compat"),
        "prusaslicer": build_table(foreign_to_native, legacy_keys, "prusaslicer"),
        "slic3r": build_table(foreign_to_native, legacy_keys, "slic3r"),
        "superslicer": build_table(foreign_to_native, legacy_keys, "superslicer"),
    }
    # Report ambiguous collisions (orc -> >1 legacy) for review.
    rev: dict[str, list[str]] = {}
    for legacy, foreign in native_to_foreign.items():
        rev.setdefault(foreign, []).append(legacy)
    for foreign, ls in rev.items():
        if len(ls) > 1:
            print(f"NOTE: '{foreign}' has multiple legacy aliases {ls}; "
                  f"kept '{foreign_to_native[foreign]}'", file=sys.stderr)
    return dialects


def emit_json(dialects: dict, source: str) -> str:
    doc = {
        "version": 2,
        "schema": "dialect_profile_key_map",
        "target_dialect": "prusaslicer3",
        "generated_from": source,
        "gcode_placeholder_renames": GCODE_PLACEHOLDER_RENAMES,
        "dialects": dialects,
    }
    return json.dumps(doc, indent=2, sort_keys=True) + "\n"


def jstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def emit_java(dialects: dict, source: str) -> str:
    lines: list[str] = []
    lines.append("// Auto-generated by scripts/gen_dialect_key_map.py — DO NOT EDIT.")
    lines.append(f"// Source: {source}")
    lines.append("package com.flashforge.farm.config;")
    lines.append("")
    lines.append("import java.util.Collections;")
    lines.append("import java.util.HashMap;")
    lines.append("import java.util.LinkedHashSet;")
    lines.append("import java.util.Map;")
    lines.append("import java.util.Set;")
    lines.append("")
    lines.append("/** Dialect -> (sourceKey -> native prusaslicer3 target). Generated import map. */")
    lines.append("public final class DialectKeyMap {")
    lines.append("    private DialectKeyMap() {}")
    lines.append("")
    for dialect, table in dialects.items():
        # renames (key -> key)
        lines.append(f"    private static Map<String, String> {dialect}Keys() {{")
        lines.append("        Map<String, String> m = new HashMap<>();")
        for src, row in table.items():
            if row["key"] is not None:
                lines.append(f"        m.put({jstr(src)}, {jstr(row['key'])});")
        lines.append("        return m;")
        lines.append("    }")
        # enum value rewrites keyed by source key -> (value -> value)
        lines.append(f"    private static Map<String, Map<String, String>> {dialect}Values() {{")
        lines.append("        Map<String, Map<String, String>> m = new HashMap<>();")
        for src, row in table.items():
            if row.get("values"):
                # Plain strings (not f-strings): a f-string `{{` collapses to a
                # single literal `{`, which turns the double-brace initializer
                # `new HashMap<>() {{ ... }}` into a plain class body and breaks
                # compilation (illegal-start-of-type).
                lines.append('        m.put(' + jstr(src) + ', new HashMap<String, String>() {{')
                for v0, v1 in row["values"].items():
                    lines.append('            put(' + jstr(v0) + ', ' + jstr(v1) + ');')
                lines.append('        }});')
        lines.append("        return m;")
        lines.append("    }")
        # removed set
        lines.append(f"    private static Set<String> {dialect}Removed() {{")
        lines.append("        Set<String> s = new LinkedHashSet<>();")
        for src, row in table.items():
            if row["key"] is None:
                lines.append(f"        s.add({jstr(src)});")
        lines.append("        return s;")
        lines.append("    }")
        lines.append("")

    lines.append("    public static final Map<String, Map<String, String>> RENAMES;")
    lines.append("    public static final Map<String, Map<String, Map<String, String>>> VALUES;")
    lines.append("    public static final Map<String, Set<String>> REMOVED;")
    lines.append("    static {")
    lines.append("        Map<String, Map<String, String>> r = new HashMap<>();")
    lines.append("        Map<String, Map<String, Map<String, String>>> v = new HashMap<>();")
    lines.append("        Map<String, Set<String>> d = new HashMap<>();")
    for dialect in dialects:
        lines.append(f"        r.put({jstr(dialect)}, {dialect}Keys());")
        lines.append(f"        v.put({jstr(dialect)}, {dialect}Values());")
        lines.append(f"        d.put({jstr(dialect)}, {dialect}Removed());")
    lines.append("        RENAMES = Collections.unmodifiableMap(r);")
    lines.append("        VALUES = Collections.unmodifiableMap(v);")
    lines.append("        REMOVED = Collections.unmodifiableMap(d);")
    lines.append("    }")
    lines.append("")
    lines.append("    public static boolean isRemoved(String dialect, String key) {")
    lines.append("        Set<String> s = REMOVED.get(dialect);")
    lines.append("        return s != null && s.contains(key);")
    lines.append("    }")
    lines.append("")
    lines.append("    public static String rename(String dialect, String key) {")
    lines.append("        Map<String, String> m = RENAMES.get(dialect);")
    lines.append("        if (m == null) return key;")
    lines.append("        String t = m.get(key);")
    lines.append("        return t != null ? t : key;")
    lines.append("    }")
    lines.append("")
    lines.append("    public static String value(String dialect, String key, String value) {")
    lines.append("        Map<String, Map<String, String>> m = VALUES.get(dialect);")
    lines.append("        if (m == null) return value;")
    lines.append("        Map<String, String> vv = m.get(key);")
    lines.append("        if (vv == null) return value;")
    lines.append("        String t = vv.get(value.trim());")
    lines.append("        return t != null ? t : value;")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--config-object", required=True, type=Path,
                    help="path to ConfigObject.java (source of the native->foreign map)")
    ap.add_argument("--out-json", required=True, type=Path)
    ap.add_argument("--out-java", required=True, type=Path)
    args = ap.parse_args()

    native_to_foreign = parse_native_to_foreign(args.config_object)
    dialects = build_dialects(native_to_foreign)

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    args.out_java.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.write_text(emit_json(dialects, str(args.config_object)))
    args.out_java.write_text(emit_java(dialects, str(args.config_object)))

    for d, t in dialects.items():
        removed = sum(1 for r in t.values() if r["key"] is None)
        print(f"  {d:<12} -> {len(t)} keys ({removed} removed)", file=sys.stderr)
    print(f"native->foreign parsed: {len(native_to_foreign)}", file=sys.stderr)
    print("json ->", args.out_json, file=sys.stderr)
    print("java ->", args.out_java, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())