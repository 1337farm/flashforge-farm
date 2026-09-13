#!/usr/bin/env bash
# scripts/build_prusa30_heatshrink.sh — heatshrink for the Android NDK (static).
#
# Required by LibBGCode's Binarize component: `find_package(heatshrink 0.4
# REQUIRED)` + links heatshrink::heatshrink_dynalloc. libbgcode's export also
# bakes Binarize_DOWNSTREAM_DEPS "heatshrink_0.4" into its Config.cmake, so
# libslic3r configure re-runs find_dependency(heatshrink 0.4) that needs this
# staged config package too.
#
# Upstream prusa recipe (deps/+heatshrink/heatshrink.cmake) PATCHes nothing:
# it copies its OWN CMakeLists.txt + Config.cmake.in over the upstream zip's,
# then builds via add_cmake_project. That overlay is vendored at
# engine/prusa30/heatshrink/ (kept out of engine/prusa30/patches/ because
# fetch_prusaslicer.sh glob-applies *.patch to the prusa tree). The overlay's
# CMake builds heatshrink + heatshrink_dynalloc, installs
# include/heatshrink/*.h and lib/cmake/heatshrink/heatshrinkConfig.cmake
# exporting heatshrink::heatshrink{,_dynalloc}.
#
# Pins (exact, from upstream deps/+heatshrink/heatshrink.cmake):
#   heatshrink: https://github.com/atomicobject/heatshrink/archive/refs/tags/v0.4.1.zip
#               SHA256=2e2db2366bdf36cb450f0b3229467cbc6ea81a8c690723e4227b0b46f92584fe
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/heatshrink)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HS_VER="0.4.1"
HS_URL="https://github.com/atomicobject/heatshrink/archive/refs/tags/v${HS_VER}.zip"
HS_SHA="2e2db2366bdf36cb450f0b3229467cbc6ea81a8c690723e4227b0b46f92584fe"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[heatshrink] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/heatshrink}"
OVERLAY="$ROOT/engine/prusa30/heatshrink"

test -f "$OVERLAY/CMakeLists.txt" || { echo "[heatshrink] ERROR: vendored overlay CMakeLists.txt missing" >&2; exit 1; }
test -f "$OVERLAY/Config.cmake.in" || { echo "[heatshrink] ERROR: vendored overlay Config.cmake.in missing" >&2; exit 1; }

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [heatshrink] Building heatshrink $HS_VER ($ABI, api $API_LEVEL, static)"
echo " [heatshrink] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "heatshrink-$HS_VER.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "heatshrink-$HS_VER.zip" "$HS_URL"
fi
echo "$HS_SHA  heatshrink-$HS_VER.zip" | sha256sum -c --status - \
    || { echo "[heatshrink] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "heatshrink-$HS_VER" "heatshrink-build"
mkdir -p "heatshrink-$HS_VER"
unzip -q -o "heatshrink-$HS_VER.zip" -d "heatshrink-$HS_VER"
HSRC="$(find "heatshrink-$HS_VER" -maxdepth 2 -name heatshrink_decoder.c -printf '%h\n' | head -1)"
[ -n "$HSRC" ] || { echo "[heatshrink] ERROR: no heatshrink source found" >&2; exit 1; }
# Upstream recipe replaces the zip's CMakeLists with prusa's overlay files.
cp "$OVERLAY/CMakeLists.txt" "$HSRC/CMakeLists.txt"
cp "$OVERLAY/Config.cmake.in" "$HSRC/Config.cmake.in"

cmake -S "$HSRC" -B "heatshrink-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_FIND_ROOT_PATH=/
cmake --build "heatshrink-build" -j"$N_CORES"
cmake --install "heatshrink-build"

test -f "$STAGE_ROOT/include/heatshrink/heatshrink_decoder.h" \
    || { echo "[heatshrink] ERROR: heatshrink headers missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libheatshrink_dynalloc.a' | grep -q . \
    || { echo "[heatshrink] ERROR: no libheatshrink_dynalloc.a" >&2; exit 1; }
HSCFG="$(find "$STAGE_ROOT" -name heatshrinkConfig.cmake -printf '%h\n' | head -1)"
test -n "$HSCFG" || { echo "[heatshrink] ERROR: heatshrinkConfig.cmake not installed" >&2; exit 1; }
grep -rq "heatshrink::heatshrink_dynalloc" "$HSCFG" \
    || { echo "[heatshrink] ERROR: heatshrink::heatshrink_dynalloc target missing from config" >&2; exit 1; }
echo "[heatshrink] done -> $STAGE_ROOT (config: $HSCFG)"