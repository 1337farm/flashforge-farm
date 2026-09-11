#!/usr/bin/env bash
# scripts/build_prusa30_tbb.sh — stock oneTBB v2021.12.0 for the Android NDK.
#
# This is the prusa30 counterpart to the oneTBB leg of
# scripts/build_all_deps_android.sh (which builds the openvdb-android
# tbb-aarch64 FORK for the Orca engine). PrusaSlicer 3.0 pins stock oneTBB
# v2021.12.0 (engine/prusa30/deps-manifest.json) and resolves it via
# find_package(TBB) in cmake/modules/GlobalDependencies.cmake, so we stage a
# real `cmake --install` prefix tree (include/ + lib/ + lib/cmake/TBB/
# with TBBConfig.cmake) — not raw headers+archives. Static only:
# SLIC3R_STATIC defaults ON upstream and GlobalDependencies sets TBB_STATIC.
# Stock 2021.12 ships the modern oneapi/tbb headers natively, so no
# classic->oneapi bridge (scripts/build_tbb_bridge.sh) is needed.
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/tbb)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TBB_VER="2021.12.0"
TBB_URL="https://github.com/oneapi-src/oneTBB/archive/refs/tags/v${TBB_VER}.zip"
TBB_SHA="fe6ca052b5bdd2c6e0616b360c9b0dcbcc46e01bbd0aa8fd0517c17fc58931db"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[tbb] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/tbb}"

echo "======================================================================="
echo " [TBB] Building oneTBB v$TBB_VER ($ABI, api $API_LEVEL, -j$N_CORES, static)"
echo " [TBB] NDK: $NDK"
echo " [TBB] stage: $STAGE_ROOT"
echo "======================================================================="

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

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
fetch "oneTBB-$TBB_VER.zip" "$TBB_URL"
echo "$TBB_SHA  oneTBB-$TBB_VER.zip" | sha256sum -c --status - \
    || { echo "[tbb] ERROR: sha256 mismatch for oneTBB-$TBB_VER.zip" >&2; exit 1; }

rm -rf "oneTBB-$TBB_VER" "oneTBB-build"
mkdir -p "oneTBB-$TBB_VER"
unzip -q -o "oneTBB-$TBB_VER.zip" -d "oneTBB-$TBB_VER"
SRC="$(find "oneTBB-$TBB_VER" -maxdepth 2 -name CMakeLists.txt -path '*oneTBB*' | head -1 | xargs dirname)"
[ -n "$SRC" ] || SRC="$(find "oneTBB-$TBB_VER" -maxdepth 1 -mindepth 1 -type d | head -1)"
echo "[tbb] source: $SRC"

# NOTE: oneTBB v2021.12.0's real option names (see its CMakeLists.txt) are
# TBB_TEST (default ON — the suite does NOT cross-compile to Android),
# BUILD_SHARED_LIBS (default ON — upstream SLIC3R_STATIC wants static), and
# TBBMALLOC_PROXY_BUILD. TBB_BUILD_TESTS / TBB_BUILD_SHARED /
# TBB_BUILD_TBBMALLOC_PROXY are NOT options (they'd be silently ignored).
cmake -S "$SRC" -B "oneTBB-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DTBB_TEST=OFF \
    -DTBBMALLOC_PROXY_BUILD=OFF
cmake --build "oneTBB-build" -j"$N_CORES"
cmake --install "oneTBB-build"

echo "[tbb] verifying install tree..."
test -f "$STAGE_ROOT/include/oneapi/tbb/version.h" \
    || { echo "[tbb] ERROR: oneapi/tbb/version.h missing" >&2; exit 1; }
grep -q "2021" "$STAGE_ROOT/include/oneapi/tbb/version.h" \
    || { echo "[tbb] ERROR: version.h is not oneTBB 2021.x" >&2; exit 1; }
CFG="$(find "$STAGE_ROOT" -name TBBConfig.cmake | head -1)"
[ -n "$CFG" ] || { echo "[tbb] ERROR: TBBConfig.cmake missing (find_package(TBB) needs it)" >&2; exit 1; }
echo "[tbb] TBBConfig: $CFG"
find "$STAGE_ROOT" -name 'libtbb*.a' | head -5
[ -n "$(find "$STAGE_ROOT" -name 'libtbb.a' | head -1)" ] \
    || { echo "[tbb] ERROR: no libtbb.a staged" >&2; exit 1; }
echo "[tbb] done -> $STAGE_ROOT"
