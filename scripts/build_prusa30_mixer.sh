#!/usr/bin/env bash
# scripts/build_prusa30_mixer.sh — prusa-fdm-mixer 09d372ae (static) for the NDK.
#
# slic3r-biz-algorithms consumes find_package(prusa_fdm_mixer CONFIG). The
# vanilla repo does not install a config package (its CMakeLists builds a
# filament_mix lib + tests); upstream's deps/+prusa_fdm_mixer overlays its
# own CMakeLists.txt + Config.cmake.in onto cpp/ which build+install
# prusa_fdm_mixer + prusa_fdm_mixer::prusa_fdm_mixer. We do the same: copy
# the two overlay files from the pinned upstream tree, then build+install
# the cpp/ dir into engine/prusa30/jniImports/mixer. Static only.
#
# Pin (exact, from upstream deps/+prusa_fdm_mixer/prusa_fdm_mixer.cmake):
#   https://github.com/prusa3d/prusa-fdm-mixer/archive/09d372aeccb4f7b9a0efbe59d99d70dba196814a.zip
#   SHA256=795B3DEC5FE64E59054F648D43E527C476A52BC38FE19CF3AE3BE62F817DE660
#
# Inputs: PRUSA_UP (pinned upstream checkout for the overlay files;
# default ../prusaslicer-upstream), plus the standard NDK env surface
# (NDK/ABI/API_LEVEL/N_CORES/WORK_DIR/STAGE_ROOT default .../jniImports/mixer).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MIXER_REF="09d372aeccb4f7b9a0efbe59d99d70dba196814a"
MIXER_URL="https://github.com/prusa3d/prusa-fdm-mixer/archive/${MIXER_REF}.zip"
MIXER_SHA="795B3DEC5FE64E59054F648D43E527C476A52BC38FE19CF3AE3BE62F817DE660"
PRUSA_UP="${PRUSA_UP:-/data/data/com.termux/files/home/code/prusaslicer-upstream}"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[mixer] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/mixer}"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [mixer] Building prusa-fdm-mixer $MIXER_REF ($ABI, api $API_LEVEL, static)"
echo " [mixer] stage: $STAGE_ROOT"
echo "======================================================================="

OVERLAY="$PRUSA_UP/deps/+prusa_fdm_mixer"
test -f "$OVERLAY/CMakeLists.txt" || { echo "[mixer] ERROR: overlay CMakeLists missing at $OVERLAY (set PRUSA_UP)" >&2; exit 1; }
test -f "$OVERLAY/Config.cmake.in" || { echo "[mixer] ERROR: overlay Config.cmake.in missing at $OVERLAY" >&2; exit 1; }

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
[ -f "mixer-$MIXER_REF.zip" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "mixer-$MIXER_REF.zip" "$MIXER_URL"
echo "$MIXER_SHA  mixer-$MIXER_REF.zip" | sha256sum -c --status - \
    || { echo "[mixer] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "mixer-$MIXER_REF" "mixer-build"
mkdir -p "mixer-$MIXER_REF" && unzip -q -o "mixer-$MIXER_REF.zip" -d "mixer-$MIXER_REF"
MSRC="$(find "mixer-$MIXER_REF" -maxdepth 2 -type d -name cpp | head -1)"
[ -n "$MSRC" ] || { echo "[mixer] ERROR: no cpp/ dir in mixer tarball" >&2; exit 1; }
cp "$OVERLAY/CMakeLists.txt" "$OVERLAY/Config.cmake.in" "$MSRC/"
cmake -S "$MSRC" -B "mixer-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF
cmake --build "mixer-build" -j"$N_CORES"
cmake --install "mixer-build"

find "$STAGE_ROOT" -name 'libprusa_fdm_mixer.a' | grep -q . \
    || { echo "[mixer] ERROR: no libprusa_fdm_mixer.a staged" >&2; exit 1; }
find "$STAGE_ROOT" -name 'prusa_fdm_mixerConfig.cmake' -o -name 'prusa_fdm_mixer-config.cmake' | grep -q . \
    || { echo "[mixer] ERROR: no mixer config staged" >&2; exit 1; }
echo "[mixer] done -> $STAGE_ROOT"
