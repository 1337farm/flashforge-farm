#!/usr/bin/env python3
"""Generate the multi-dialect profile translation table (import boundary).

Single source of truth for mapping foreign slicer profiles into native
PrusaSlicer 3.0 INI. This is an IMPORT feature (the same idea as PrusaSlicer's
own "Import config from other slicers") and runs once at ingest, never in the
slice path. The slicing core stays native: Java hands the engine an INI string
produced here, and DynamicPrintConfig::load(ini) does the rest.

Dialects (target is always `prusaslicer3`):
  - orca        OrcaSlicer JSON (.orca_printer / .orca_filament)
  - bambu       Bambu Studio JSON (Orca key space + BBL extras)
  - prusaslicer legacy PrusaSlicer INI
  - slic3r      Slic3r / PrusaSlicer-2.x INI
  - superslicer SuperSlicer INI (legacy Prusa key space + SS extras)

Grounding: the orca/bambu rename is the INVERSION of the hand-written
`ConfigObject.buildKeyMigration()` the app already ships (parsed from source).
Overlays below express the cases a pure rename cannot:
  - ORCA_REMOVED      Orca-only keys with no native target -> dropped on import.
  - ORCA_ENUM_REMAPS  keys whose VALUE (enum labels) must be rewritten.
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

# --- Orca-only keys: no native PrusaSlicer 3.0 equivalent -> dropped ----------
ORCA_REMOVED: dict[str, str] = {
    "single_extruder_multi_material": "Orca SEMM toggle; no native equivalent",
    "flush_volumes_matrix": "Orca purge-matrix layout only",
    "flush_multiplier": "Orca purge multiplier",
    "flush_volumes_vector": "Orca purge vector",
    "filament_map": "Orca virtual->physical filament map",
    "curr_bed_type": "Orca bed-slot selector enum",
    "cool_plate_temp": "Orca per-plate bed temp slot",
    "cool_plate_temp_initial_layer": "Orca per-plate bed temp slot",
    "eng_plate_temp": "Orca per-plate bed temp slot",
    "eng_plate_temp_initial_layer": "Orca per-plate bed temp slot",
    "hot_plate_temp": "Orca per-plate bed temp slot",
    "hot_plate_temp_initial_layer": "Orca per-plate bed temp slot",
    "textured_plate_temp": "Orca per-plate bed temp slot",
    "textured_plate_temp_initial_layer": "Orca per-plate bed temp slot",
}

# --- Orca keys whose VALUE must be rewritten (not just the key) ---------------
# Each entry: {"to": target key (override inversion), "values": {src: dst}, "note": ...}
# A leading '>' in "to" means: leave the key-name as-is (no rename), only rewrite value.
ORCA_ENUM_REMAPS: dict[str, dict] = {
    "gap_fill_target": {
        "values": {"everywhere": "1", "topbottom": "1", "nowhere": "0"},
        "note": "Orca enum -> legacy bool gap_fill_enabled",
    },
    "wall_sequence": {
        "to": "external_perimeters_first",
        "values": {
            "inner wall/outer wall": "0", "inner/outer wall": "0", "inner/outer": "0",
            "outer wall/inner wall": "1", "outer/inner wall": "1", "outer/inner": "1",
        },
        "note": "Orca wall_sequence enum -> Prusa external_perimeters_first bool",
    },
    "print_sequence": {
        "values": {"by layer": "0", "by object": "1", "by_layer": "0", "by_object": "1"},
        "note": "Orca print_sequence enum -> Prusa complete_objects bool",
    },
    "ironing_type": {
        "to": "ironing",
        "values": {
            "no ironing": "0", "no_ironing": "0",
            "all top surfaces": "1", "topmost surface": "1", "all solid layers": "1",
            "all_top_surfaces": "1", "topmost_surface": "1", "all_solid_layers": "1",
        },
        "note": "Orca ironing_type enum -> Prusa ironing bool",
    },
    # Tree/normal support is a two-key mapping in Prusa; handled as a converter-side
    # transform. Mark it type_changed so the dialect map flags it.
    "support_type": {
        "kind": "type_changed",
        "transform": "support_type",
        "note": "Orca normal/tree(auto) enum -> Prusa support_style (snug/organic)",
    },
}

# --- PrusaSlicer 3.0 renames over the legacy Prusa key space ------------------
# Populate from the 3.0 print_config_def / release notes. Empty = identity.
PRUSA3_RENAMES: dict[str, str] = {}

# --- gcode placeholder tokens that are not config keys ------------------------
# Orca/bambu start/end gcode embeds these tokens; Prusa uses brace placeholders.
# Applied to any *_gcode value during import.
GCODE_PLACEHOLDER_RENAMES: dict[str, str] = {
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "bed_temperature_initial_layer_single": "first_layer_bed_temperature",
    "bed_temperature_initial_layer": "first_layer_bed_temperature",
}

RE_PUT = re.compile(r'm\.put\(\s*"([A-Za-z0-9_]+)"\s*,\s*"([A-Za-z0-9_]+)"\s*\)\s*;')


def parse_legacy_to_orca(config_object_path: Path) -> dict[str, str]:
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

    legacy_to_orca: dict[str, str] = {}
    for legacy, orca in RE_PUT.findall(body):
        legacy_to_orca[legacy] = orca
    if not legacy_to_orca:
        print(f"WARNING: no m.put(...) pairs parsed from {config_object_path}", file=sys.stderr)
    return legacy_to_orca


def invert(legacy_to_orca: dict[str, str]) -> dict[str, str]:
    orca_to_legacy: dict[str, str] = {}
    for legacy, orca in legacy_to_orca.items():
        orca_to_legacy.setdefault(orca, legacy)
    return orca_to_legacy


def build_table(
    orca_to_legacy: dict[str, str],
    legacy_keys: set[str],
    dialect: str,
) -> dict:
    """Build one dialect's {sourceKey: row} table."""
    out: dict[str, dict] = {}

    if dialect in ("orca", "bambu"):
        for orca, legacy in orca_to_legacy.items():
            target = PRUSA3_RENAMES.get(legacy, legacy)
            spec = ORCA_ENUM_REMAPS.get(orca)
            if spec:
                kind = spec.get("kind", "enum_remap")
                row = {"key": spec.get("to", target), "kind": kind,
                       "note": spec.get("note", "")}
                if "values" in spec:
                    row["values"] = spec["values"]
                if "transform" in spec:
                    row["transform"] = spec["transform"]
                out[orca] = row
            else:
                out[orca] = {"key": target, "kind": "rename", "note": ""}
        # Orca-only keys not reachable via KEY_MIGRATION inversion.
        for orca, note in ORCA_REMOVED.items():
            out[orca] = {"key": None, "kind": "removed", "note": note}
        for orca, spec in ORCA_ENUM_REMAPS.items():
            if orca not in out:
                kind = spec.get("kind", "enum_remap")
                row = {"key": spec.get("to"), "kind": kind, "note": spec.get("note", "")}
                if "values" in spec:
                    row["values"] = spec["values"]
                if "transform" in spec:
                    row["transform"] = spec["transform"]
                out[orca] = row
    else:
        for key in sorted(legacy_keys):
            target = PRUSA3_RENAMES.get(key, key)
            kind = "rename" if key in PRUSA3_RENAMES else "identity"
            out[key] = {"key": target, "kind": kind, "note": ""}

    return out


def build_dialects(legacy_to_orca: dict[str, str]) -> dict[str, dict]:
    orca_to_legacy = invert(legacy_to_orca)
    legacy_keys = set(legacy_to_orca.keys())
    dialects = {
        "orca": build_table(orca_to_legacy, legacy_keys, "orca"),
        "bambu": build_table(orca_to_legacy, legacy_keys, "bambu"),
        "prusaslicer": build_table(orca_to_legacy, legacy_keys, "prusaslicer"),
        "slic3r": build_table(orca_to_legacy, legacy_keys, "slic3r"),
        "superslicer": build_table(orca_to_legacy, legacy_keys, "superslicer"),
    }
    # Report ambiguous collisions (orc -> >1 legacy) for review.
    rev: dict[str, list[str]] = {}
    for legacy, orca in legacy_to_orca.items():
        rev.setdefault(orca, []).append(legacy)
    for orca, ls in rev.items():
        if len(ls) > 1:
            print(f"NOTE: '{orca}' has multiple legacy aliases {ls}; "
                  f"kept '{orca_to_legacy[orca]}'", file=sys.stderr)
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
                lines.append(f"        m.put({jstr(src)}, new HashMap<String, String>() {{")
                for v0, v1 in row["values"].items():
                    lines.append(f"            put({jstr(v0)}, {jstr(v1)});")
                lines.append("        }});")
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
                    help="path to ConfigObject.java (source of the legacy->Orca map)")
    ap.add_argument("--out-json", required=True, type=Path)
    ap.add_argument("--out-java", required=True, type=Path)
    args = ap.parse_args()

    legacy_to_orca = parse_legacy_to_orca(args.config_object)
    dialects = build_dialects(legacy_to_orca)

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    args.out_java.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.write_text(emit_json(dialects, str(args.config_object)))
    args.out_java.write_text(emit_java(dialects, str(args.config_object)))

    for d, t in dialects.items():
        removed = sum(1 for r in t.values() if r["key"] is None)
        print(f"  {d:<12} -> {len(t)} keys ({removed} removed)", file=sys.stderr)
    print(f"legacy->orca parsed: {len(legacy_to_orca)}", file=sys.stderr)
    print("json ->", args.out_json, file=sys.stderr)
    print("java ->", args.out_java, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())