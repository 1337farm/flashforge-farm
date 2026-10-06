#!/usr/bin/env python3
"""Settings key audit: every option key referenced by the Android settings UI
must exist in the PrusaSlicer 3.0 engine FDM definitions (the exact set the
native ConfigDef bridge emits).

Fast (<5s): pure source-text check, no build. Guards against stale hardcoded
keys silently emptying settings sections (e.g. the empty "Line width" header:
2.x keys like `line_width` that do not exist as 3.0 `extrusion_width` defs).

Engine universe = ConfigCommon.cpp + ConfigDefsFDM.cpp, matching
Domain::get_defs_fdm(). UI universe = options.get("...") keys in the
fragment builders.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOMAIN = REPO / "engine/build/prusaslicer-src/src/slic3r-domain/src/Slic3r/Domain"
UI_FILES = [
    REPO / "app/src/main/java/com/flashforge/farm/fragment/ProfilePrintSettingsBuilder.java",
    REPO / "app/src/main/java/com/flashforge/farm/fragment/FilamentConfigFragment.java",
    REPO / "app/src/main/java/com/flashforge/farm/fragment/PrinterConfigFragment.java",
]


def engine_keys():
    keys = set()
    for name in ("ConfigCommon.cpp", "ConfigDefsFDM.cpp"):
        text = (DOMAIN / name).read_text()
        keys.update(re.findall(r'defs\.add\("([a-z0-9_]+)"', text))
    assert keys, "no engine defs found"
    return keys


def ui_keys(path):
    text = path.read_text()
    keys = set(re.findall(r'options\.get\("([a-z0-9_]+)"', text))
    if path.name == "ProfilePrintSettingsBuilder.java":
        # Builder also passes keys positionally to addSection(title, keys...);
        # those are lowercase-led, while section titles start uppercase.
        keys.update(re.findall(r'"([a-z][a-z0-9_]{2,})"', text))
    return keys


def main():
    eng = engine_keys()
    failures = []
    for path in UI_FILES:
        if not path.exists():
            print(f"SKIP missing file: {path}")
            continue
        keys = ui_keys(path)
        missing = sorted(keys - eng)
        print(f"{path.name}: {len(keys)} keys, {len(missing)} stale")
        for k in missing:
            print(f"  STALE: {k}")
            failures.append(f"{path.name}:{k}")
    if failures:
        print(f"\nFAIL: {len(failures)} stale settings keys")
        return 1
    print("\nOK: all settings keys exist in engine defs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
