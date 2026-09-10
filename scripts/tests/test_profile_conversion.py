#!/usr/bin/env python3
"""Profile-conversion tooling tests.

Proves the one-way foreign-slicer -> PrusaSlicer 3.0 converter:
  - key renames (orca -> prusa)
  - removed-key dropping
  - enum value remaps
  - type_changed transforms (support_type)
  - gcode placeholder rewriting
  - JSON inheritance resolution
  - CLI end-to-end (directory batch conversion)
Fast (<5s): pure data conversion against the generated dialect_key_map.json.
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
sys.path.insert(0, str(SCRIPTS))

import convert_to_prusa as cvt  # noqa: E402

MAP = REPO / "app/src/main/assets/dialect_key_map.json"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
PYTHON = shutil.which("python3") or "python3"


def load_map():
    with MAP.open() as f:
        return json.load(f)


def test_map_artifact_present():
    doc = load_map()
    assert doc["schema"] == "dialect_profile_key_map"
    assert doc["target_dialect"] == "prusaslicer3"
    assert set(doc["dialects"]) >= {"orca", "bambu", "prusaslicer", "slic3r", "superslicer"}
    orca = doc["dialects"]["orca"]
    assert orca["wall_loops"]["key"] == "perimeters"
    assert orca["single_extruder_multi_material"]["kind"] == "removed"
    assert "values" in orca["gap_fill_target"]


def test_jvalue_to_str():
    assert cvt.jvalue_to_str(["0.4", "0.4"]) == "0.4,0.4"
    assert cvt.jvalue_to_str(["#FF0000"]) == "#FF0000"
    assert cvt.jvalue_to_str(True) == "1"
    assert cvt.jvalue_to_str(3.0) == "3.0"


def _table(dialect):
    return load_map()["dialects"][dialect]


def test_rename():
    s = cvt.ConvertStats()
    out = cvt.convert_entry("wall_loops", "3", _table("orca"), "orca", {}, s)
    assert out == [("perimeters", "3")]
    assert s.renamed == 1


def test_removed():
    s = cvt.ConvertStats()
    out = cvt.convert_entry("single_extruder_multi_material", "1", _table("orca"), "orca", {}, s)
    assert out == []
    assert s.removed == 1


def test_enum_remap():
    s = cvt.ConvertStats()
    out = cvt.convert_entry("gap_fill_target", "everywhere", _table("orca"), "orca", {}, s)
    assert out == [("gap_fill_enabled", "1")]
    assert s.value_remapped == 1


def test_enum_remap_wall_sequence():
    out = cvt.convert_entry("wall_sequence", "inner wall/outer wall", _table("orca"), "orca", {},
                            cvt.ConvertStats())
    assert out == [("external_perimeters_first", "0")]


def test_transform_support_type():
    s = cvt.ConvertStats()
    out = cvt.convert_entry("support_type", "tree(auto)", _table("orca"), "orca", {}, s)
    assert out == [("support_style", "organic")]
    assert s.transformed == 1


def test_gcode_rewrite():
    table = _table("orca")
    gcode = load_map()["gcode_placeholder_renames"]
    out = cvt.convert_entry(
        "start_gcode", "M104 S[nozzle_temperature_initial_layer]",
        table, "orca", gcode, cvt.ConvertStats())
    assert out == [("start_gcode", "M104 S{first_layer_temperature[0]}")]


def test_inheritance_resolution():
    catalog = {}
    for p in FIXTURES.glob("*.json"):
        obj = cvt.read_json_profile(p)
        catalog[p.stem] = obj
        if isinstance(obj.get("name"), str):
            catalog[obj["name"]] = obj
    child = cvt.read_json_profile(FIXTURES / "orca_sample.json")
    flat = cvt.collect_flat(child, catalog, "orca")
    assert flat["wall_loops"] == "3"              # parent=2 overridden by child=3
    assert flat["sparse_infill_density"] == "15%"  # parent-inherited key survives


def test_full_convert_and_emit():
    flat = {
        "wall_loops": "3", "spiral_mode": "0", "gap_fill_target": "everywhere",
        "single_extruder_multi_material": "1", "support_type": "tree(auto)",
        "nozzle_diameter": "0.4", "ironing_type": "topmost surface",
    }
    out, s = cvt.convert(flat, _table("orca"), "orca", load_map()["gcode_placeholder_renames"])
    assert out["perimeters"] == "3"
    assert out["spiral_vase"] == "0"
    assert out["gap_fill_enabled"] == "1"
    assert out["ironing"] == "1"
    assert out["support_style"] == "organic"
    assert "single_extruder_multi_material" not in out
    assert s.removed == 1
    ini = cvt.emit_ini(out, "test")
    assert "perimeters = 3" in ini


def test_cli_directory_conversion(tmp_path):
    out_dir = tmp_path / "out"
    r = subprocess.run(
        [PYTHON, str(SCRIPTS / "convert_to_prusa.py"),
         "--dialect", "auto", "--dir", str(FIXTURES),
         "--base-dir", str(FIXTURES),
         "--out-dir", str(out_dir),
         "--map", str(MAP)],
        capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    produced = {p.name for p in out_dir.glob("*.ini")}
    assert {"orca_sample.ini", "base_profile.ini", "superslicer_sample.ini"} <= produced

    orca_out = (out_dir / "orca_sample.ini").read_text()
    assert "perimeters = 3" in orca_out             # rename + inheritance override
    assert "gap_fill_enabled = 1" in orca_out       # enum remap
    assert "support_style = organic" in orca_out    # type_changed transform
    assert "single_extruder_multi_material" not in orca_out   # removed
    assert "{first_layer_temperature[0]}" in orca_out         # gcode rewrite

    ss_out = (out_dir / "superslicer_sample.ini").read_text()
    assert "perimeters = 4" in ss_out      # legacy INI key passes through
    assert "support_material = 1" in ss_out


def test_orca_stored_ini_migration(tmp_path):
    # The app's own stored configs are Orca-keyed INI (ConfigObject.serialize()).
    # One-time migration converts them through the `orca` dialect even though
    # the file is INI, not JSON (format and dialect are independent).
    src = tmp_path / "stored.ini"
    src.write_text(
        "wall_loops = 3\n"
        "spiral_mode = 1\n"
        "gap_fill_target = everywhere\n"
        "single_extruder_multi_material = 1\n"
        "nozzle_diameter = 0.4\n")
    out = tmp_path / "out.ini"
    r = subprocess.run(
        [PYTHON, str(SCRIPTS / "convert_to_prusa.py"),
         str(src), "--dialect", "orca", "--out", str(out), "--map", str(MAP)],
        capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    text = out.read_text()
    assert "perimeters = 3" in text
    assert "spiral_vase = 1" in text
    assert "gap_fill_enabled = 1" in text
    assert "single_extruder_multi_material" not in text
    assert "nozzle_diameter = 0.4" in text


if __name__ == "__main__":
    import pytest
    sys.exit(pytest.main([__file__, "-v"]))