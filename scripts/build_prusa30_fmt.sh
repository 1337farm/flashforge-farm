#!/usr/bin/env bash
# scripts/build_prusa30_fmt.sh — {fmt} 12.1.0 (static) for the Android NDK.
#
# Upstream pins fmt 12.1.0 via deps/+fmt (add_cmake_project) and consumes it
# with find_package(fmt) + slic3r_remap_configs(fmt::fmt). fmt 12.x ships only
# fmt-config.cmake (no Find module), so stage a real `cmake --install` prefix
# and pass -Dfmt_DIR=<exact config dir> at configure. Static only.
# fmt 12.x is header-capable but libslic3r links fmt::fmt, so build the lib.
#
# Pin (exact, from upstream deps/+fmt/fmt.cmake):
#   https://github.com/fmtlib/fmt/releases/download/12.1.0/fmt-12.1.0.zip
#   SHA256=695fd197fa5aff8fc67b5f2bbc110490a875cdf7a41686ac8512fb480fa8ada7
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/pngfmt)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FMT_VER="12.1.0"
FMT_URL="https://github.com/fmtlib/fmt/releases/download/${FMT_VER}/fmt-${FMT_VER}.zip"
FMT_SHA="695fd197fa5aff8fc67b5f2bbc110490a875cdf7a41686ac8512fb480fa8ada7"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[fmt] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/pngfmt}"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [fmt] Building fmt $FMT_VER ($ABI, api $API_LEVEL, static)"
echo " [fmt] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
[ -f "fmt-$FMT_VER.zip" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "fmt-$FMT_VER.zip" "$FMT_URL"
echo "$FMT_SHA  fmt-$FMT_VER.zip" | sha256sum -c --status - \
    || { echo "[fmt] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "fmt-$FMT_VER" "fmt-build"
mkdir -p "fmt-$FMT_VER" && unzip -q -o "fmt-$FMT_VER.zip" -d "fmt-$FMT_VER"
FSRC="$(find "fmt-$FMT_VER" -maxdepth 2 -name CMakeLists.txt -path '*fmt*' | head -1 | xargs dirname)"
[ -n "$FSRC" ] || { echo "[fmt] ERROR: no fmt CMakeLists" >&2; exit 1; }
cmake -S "$FSRC" -B "fmt-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF -DFMT_TEST=OFF -DFMT_DOC=OFF
cmake --build "fmt-build" -j"$N_CORES"
cmake --install "fmt-build"

test -f "$STAGE_ROOT/include/fmt/core.h" || { echo "[fmt] ERROR: fmt/core.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libfmt.a' | grep -q . || { echo "[fmt] ERROR: no libfmt.a" >&2; exit 1; }
find "$STAGE_ROOT" -name 'fmt-config.cmake' | grep -q . || { echo "[fmt] ERROR: no fmt-config.cmake" >&2; exit 1; }
echo "[fmt] done -> $STAGE_ROOT"
