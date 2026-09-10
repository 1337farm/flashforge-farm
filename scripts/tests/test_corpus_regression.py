#!/usr/bin/env python3
"""Corpus regression tests for the foreign-profile converter.

Runs the one-way converter over the repo's real stored-config corpus
(scripts/tests/engine_repro/regress/*.ini — genuine Orca-engine serializations)
and locks the migration behavior: key renames apply, native Prusa keys pass
through, output is deterministic, and --report emits a complete audit.
Fast (<5s): pure data conversion, no engine build.
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
REGRESS = Path(__file__).resolve().parent / "engine_repro" / "regress"
PYTHON = shutil.which("python3") or "python3"


def _convert_all():
    doc = json.loads(MAP.read_text())
    table = doc["dialects"]["orca"]
    gcode = doc.get("gcode_placeholder_renames", {})
    results = {}
    for ini in sorted(REGRESS.glob("*.ini")):
        entries = cvt.read_ini_profile(ini)
        out, _ = cvt.convert(entries, table, "orca", gcode)
        results[ini.name] = out
    return results


def test_corpus_renames_applied():
    out = _convert_all()["user_benchy_ad5m.ini"]
    assert out["perimeters"] == "2"       # wall_loops -> perimeters
    assert out["spiral_vase"] == "0"      # spiral_mode -> spiral_vase
    assert out["support_material"] == "0"  # enable_support -> support_material
    assert "wall_loops" not in out
    assert "spiral_mode" not in out
    assert "enable_support" not in out


def test_corpus_native_keys_pass_through():
    out = _convert_all()["user_benchy_ad5m.ini"]
    assert out["layer_height"] == "0.2"       # Prusa-native identity
    assert out["nozzle_diameter"] == "0.4"    # Prusa-native identity


def test_corpus_determinism():
    assert _convert_all() == _convert_all()


def test_report_audit(tmp_path):
    report = tmp_path / "report.json"
    r = subprocess.run(
        [PYTHON, str(SCRIPTS / "convert_to_prusa.py"),
         "--dialect", "orca", "--dir", str(REGRESS),
         "--out-dir", str(tmp_path / "out"),
         "--report", str(report), "--map", str(MAP)],
        capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    doc = json.loads(report.read_text())
    assert "files" in doc and len(doc["files"]) == 4
    by_name = {Path(f["file"]).name: f for f in doc["files"]}
    benchy = by_name["user_benchy_ad5m.ini"]
    assert benchy["renamed"]["wall_loops"] == "perimeters"
    assert "layer_height" in benchy["unknown"]
    assert "nozzle_diameter" in benchy["unknown"]
    assert set(benchy["counts"]) == {"renamed", "identity", "removed",
                                     "value_remapped", "transformed", "unmapped"}


if __name__ == "__main__":
    import pytest
    sys.exit(pytest.main([__file__, "-v"]))