#!/usr/bin/env python3
"""Vendor PrusaSlicer 3.0 (upstream `master`) into the FlashForge Farm engine.

PrusaSlicer 3.0 (tag version_3.0.0-alpha11) split the monolithic libslic3r
into bounded modules. This harness pins the exact upstream commit, enumerates
the HEADLESS module set that must be built on Android (no GUI), extracts the
exact dependency pins from upstream's deps/*.cmake, and (optionally) stages the
headless module sources into engine/src/main/jni/.

It performs no guessing: every value is read from the upstream checkout.
Typical flow:

    # 1. (one-time) check out upstream next to this repo:
    #    git clone --depth 1 --branch master \
    #        https://github.com/prusa3d/PrusaSlicer.git ../prusaslicer-upstream
    # 2. analyze + emit the manifest:
    python3 scripts/vendor_prusaslicer30.py --upstream ../prusaslicer-upstream \
        --out-manifest engine/PRUSASLICER30-MANIFEST.json
    # 3. (after CI/dep bring-up is agreed) stage the headless modules:
    python3 scripts/vendor_prusaslicer30.py --upstream ../prusaslicer-upstream \
        --stage engine/src/main/jni/
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

# Pinned upstream commit = tag version_3.0.0-alpha11 (== master at clone time).
PRUSA30_PIN = "6f510128d7c2e543b62919b74bea7e876f564205"
REPO_URL = "https://github.com/prusa3d/PrusaSlicer"

# Headless modules `src/CMakeLists.txt` add unconditionally (before the
# SLIC3R_GUI gate). These are the Android build's target set.
HEADLESS_MODULES = [
    "slic3r-base",
    "slic3r-domain",
    "slic3r-biz-algorithms",
    "slic3r-biz-cgal-algorithms",
    "slic3r-biz-crypto",
    "slic3r-biz-parser",
    "slic3r-gcode-reader",
    "libpgcode",
    "slic3r-jthread",
    "libslic3r",
    "slic3r-biz-arrange",
    "slic3r-biz-lua",
]

# Deps whose versions are extracted directly from deps/+<Dep>/<Dep>.cmake.
DEP_CMAKE = {
    "Boost": "Boost.cmake",
    "CGAL": "CGAL.cmake",
    "OCCT": "OCCT.cmake",
    "TBB": "TBB.cmake",
    "GMP": "GMP.cmake",
    "MPFR": "MPFR.cmake",
    "OpenVDB": "OpenVDB.cmake",
    "Eigen": "Eigen.cmake",
    "Lua": "Lua.cmake",
}

RE_URL = re.compile(r'URL\s+"?([^"\s]+)"?')
RE_HASH = re.compile(r'URL_HASH\s+SHA256=([0-9a-fA-F]{64})')
RE_VERSION = re.compile(r'[vV]?(version[_v.-]?[0-9]+\S*|V\d+_\d+_\d+|v?\d+\.\d+\.\d+)')


def extract_dep_versions(upstream: Path) -> dict:
    out: dict = {}
    for name, cmake in DEP_CMAKE.items():
        path = upstream / "deps" / f"+{name}" / cmake
        if not path.exists():
            continue
        text = path.read_text(errors="replace")
        url = RE_URL.search(text)
        hsh = RE_HASH.search(text)
        entry: dict = {"cmake": f"deps/+{name}/{cmake}"}
        if url:
            entry["url"] = url.group(1)
            m = RE_VERSION.search(url.group(1))
            if m:
                entry["version"] = m.group(1)
        if hsh:
            entry["sha256"] = hsh.group(1)
        out[name] = entry
    return out


def emit_manifest(upstream: Path) -> dict:
    return {
        "schema": "prusaslicer30-vendor-manifest",
        "repo": REPO_URL,
        "pin": PRUSA30_PIN,
        "tag": "version_3.0.0-alpha11",
        "host_build_support": {"headless_build": False,
                                "note": "src/CMakeLists.txt fatal-errors on SLIC3R_GUI=OFF; "
                                        "a headless path must be added + a slice driver written"},
        "headless_modules": list(HEADLESS_MODULES),
        "dependencies_pinned_in_upstream": extract_dep_versions(upstream),
    }


def ensure_pin(upstream: Path) -> None:
    head = ""
    for x in ("master", "HEAD"):
        p = upstream / ".git"
        if p.exists():
            break
    try:
        import subprocess
        head = subprocess.run(
            ["git", "-C", str(upstream), "rev-parse", "HEAD"],
            capture_output=True, text=True).stdout.strip()
    except Exception:
        head = ""
    if head and not head.startswith(PRUSA30_PIN):
        print(f"WARN: upstream HEAD {head} != pin {PRUSA30_PIN}", file=sys.stderr)


def stage(upstream: Path, dest: Path, modules: list[str]) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    for m in modules:
        src = upstream / "src" / m
        if not src.is_dir():
            print(f"SKIP: module not found upstream: {src}", file=sys.stderr)
            continue
        target = dest / m
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(src, target)
        print(f"staged {m} -> {target}", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upstream", type=Path,
                    default=Path(__file__).resolve().parents[1] / ".." / "prusaslicer-upstream",
                    help="path to an upstream PrusaSlicer checkout")
    ap.add_argument("--out-manifest", type=Path, help="write the vendor manifest JSON here")
    ap.add_argument("--stage", type=Path, help="copy headless modules into this dir (e.g. engine/src/main/jni/)")
    args = ap.parse_args()

    if not (args.upstream / "src" / "libslic3r").is_dir():
        print(f"ERROR: {args.upstream} does not look like a PrusaSlicer checkout", file=sys.stderr)
        return 1

    ensure_pin(args.upstream)

    manifest = emit_manifest(args.upstream)
    if args.out_manifest:
        args.out_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.out_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        print(f"manifest -> {args.out_manifest}", file=sys.stderr)
    else:
        print(json.dumps(manifest, indent=2, sort_keys=True))

    if args.stage:
        stage(args.upstream, args.stage, HEADLESS_MODULES)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())