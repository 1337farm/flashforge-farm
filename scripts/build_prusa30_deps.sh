#!/usr/bin/env bash
# Stage the PrusaSlicer 3.0 native dependencies for the Android NDK build.
#
# This is the 3.0 counterpart to scripts/build_all_deps_android.sh (Orca). It
# stages into engine/prusa30/jniImports/ so the Orca engine/deps under
# engine/src/main/jniImports are untouched and the shipping app keeps building.
#
# Headless-only header libs (cereal/json/spdlog/fmt/sol2/eigen) are downloaded,
# sha256-verified, and staged here. The COMPILED deps (Boost 1.86.0, OCCT
# V7_6_1, oneTBB v2021.12.0, CGAL v5.6.2, OpenVDB v11.0.0, Lua 5.4.8,
# GMP 6.2.1/MPFR, NLopt 2.5.0) are pinned in engine/prusa30/deps-manifest.json
# and ported from build_all_deps_android.sh in the CI loop (see the compiled-deps
# note at the bottom). Exact pins are extracted from upstream deps/*.cmake by
# scripts/vendor_prusaslicer30.py — nothing is guessed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"

# Retrying download with mirror fallback (mirrors the Orca deps script).
fetch() {
    local out="$1"; shift
    local url i
    for url in "$@"; do
        for i in 1 2 3; do
            if curl -fsSL --connect-timeout 20 --max-time 300 \
                    --retry 2 --retry-all-errors -o "$out" "$url" \
               && [ -s "$out" ]; then
                return 0
            fi
            echo "--- [fetch] attempt $i/3 failed: $url ---" >&2
            sleep "$((i * 5))"
        done
    done
    echo "--- [fetch] ERROR: all mirrors failed for $out ---" >&2
    rm -f "$out"
    return 1
}

sha256_verify() {
    local file="$1" want="$2"
    local got
    got="$(sha256sum "$file" | cut -d' ' -f1)"
    if [ "$got" != "$want" ]; then
        echo "--- [verify] sha256 mismatch for $file (want $want got $got) ---" >&2
        return 1
    fi
}

# Download + extract a zip/tarball and copy its include tree into the stage.
# usage: stage_include <name> <url> <sha256> <archive> <src-include-glob>...
stage_include() {
    local name="$1" url="$2" sha="$3" archive="$4"; shift 4
    local dl="$WORK_DIR/$archive"
    fetch "$dl" "$url"
    sha256_verify "$dl" "$sha"
    local dir="$WORK_DIR/$name"
    rm -rf "$dir"; mkdir -p "$dir"
    case "$archive" in
        *.zip)  unzip -q -o "$dl" -d "$dir" ;;
        *.tar.gz|*.tgz|*.tar.bz2|*.tar.xz) tar xf "$dl" -C "$dir" ;;
        *) echo "unknown archive type: $archive" >&2; return 1 ;;
    esac
    local dest="$STAGE_ROOT/$name/include"
    mkdir -p "$dest"
    local src
    for src in "$@"; do
        local found
        # Prefer the canonical `<archive>/include/<src>` tree; fall back to any
        # depth of `<src>` for deps (e.g. Eigen) whose headers are not under
        # include/. Avoid matching the staging dir itself.
        found="$(find "$dir" -type d -path "*/include/$src" | sort | head -1)"
        if [ -z "$found" ]; then
            found="$(find "$dir" -mindepth 2 -type d -path "*/$src" | sort | head -1)"
        fi
        if [ -z "$found" ]; then
            echo "--- [stage] WARN: include tree '$src' not found for $name ---" >&2
            continue
        fi
        cp -r "$found" "$dest/"
    done
    echo "--- [stage] $name -> $dest ---"
}

# ---------------------------------------------------------------------------
# Header-only dependencies (exact pins from upstream deps/*.cmake)
# ---------------------------------------------------------------------------
stage_include cereal "https://github.com/USCiLab/cereal/archive/refs/tags/v1.3.2.zip" \
    e72c3fa8fe3d531247773e346e6824a4744cc6472a25cf9b30599cd52146e2ae \
    cereal.zip "cereal"

stage_include nlohmann_json "https://github.com/nlohmann/json/archive/refs/tags/v3.12.0.zip" \
    34660b5e9a407195d55e8da705ed26cc6d175ce5a6b1fb957e701fb4d5b04022 \
    json.zip "nlohmann"

stage_include spdlog "https://github.com/gabime/spdlog/archive/refs/tags/v1.15.3.zip" \
    b74274c32c8be5dba70b7006c1d41b7d3e5ff0dff8390c8b6390c1189424e094 \
    spdlog.zip "spdlog"

stage_include fmt "https://github.com/fmtlib/fmt/releases/download/12.1.0/fmt-12.1.0.zip" \
    695fd197fa5aff8fc67b5f2bbc110490a875cdf7a41686ac8512fb480fa8ada7 \
    fmt.zip "fmt"

stage_include sol2 "https://github.com/ThePhD/sol2/archive/refs/tags/v3.5.0.zip" \
    b43e539415956960055f62a9d328fec3fd1ad4f272d6206631b9f022b0b12678 \
    sol2.zip "sol"

stage_include eigen "https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.zip" \
    eba3f3d414d2f8cba2919c78ec6daab08fc71ba2ba4ae502b7e5d4d99fc02cda \
    eigen.zip "Eigen"

echo "======================================================================="
echo " PrusaSlicer 3.0 header-only deps staged under $STAGE_ROOT"
echo ""
echo " COMPILED deps still pending (pinned in engine/prusa30/deps-manifest.json):"
echo "   Boost 1.86.0 | OCCT V7_6_1 (downgrade) | oneTBB v2021.12.0"
echo "   CGAL v5.6.2 | OpenVDB v11.0.0 | Lua 5.4.8 | GMP 6.2.1 | MPFR 3.1.6"
echo "   NLopt 2.5.0 | OpenSSL/CURL/EXPAT (headless may not need CURL/OpenSSL)"
echo " Ported from scripts/build_all_deps_android.sh in the CI loop."
echo "======================================================================="