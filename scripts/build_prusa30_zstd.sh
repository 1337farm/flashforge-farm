#!/usr/bin/env bash
# scripts/build_prusa30_zstd.sh — zstd for the Android NDK (static only).
#
# Required by c-blosc (Blosc.cmake sets DEP_Blosc_DEPENDS ZLIB zstd) and by
# libslic3r at configure time: prusa's bundled FindBlosc does an unconditional
# `find_package(zstd REQUIRED)` + links the zstd::libzstd imported target, so
# a zstd config package (zstdConfig.cmake exporting zstd::libzstd) must be
# discoverable or find_package(OpenVDB) fails right after Blosc resolves.
#
# Built with zstd's own cmake project (build/cmake) and the exact options
# upstream deps/+zstd/zstd.cmake passes to its superbuild configure, so the
# produced install tree (config package + static lib) is byte-compatible with
# what the prusa superbuild would have installed.
#
# Pins (exact, from upstream deps/+zstd/zstd.cmake):
#   zstd:    https://github.com/facebook/zstd/releases/download/v1.5.6/zstd-1.5.6.tar.gz
#            SHA256=8c29e06cf42aacc1eafc4077ae2ec6c6fcb96a626157e0593d5e82a34fd403c1
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/zstd)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ZSTD_VER="1.5.6"
ZSTD_URL="https://github.com/facebook/zstd/releases/download/v${ZSTD_VER}/zstd-${ZSTD_VER}.tar.gz"
ZSTD_SHA="8c29e06cf42aacc1eafc4077ae2ec6c6fcb96a626157e0593d5e82a34fd403c1"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[zstd] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/zstd}"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [zstd] Building zstd $ZSTD_VER ($ABI, api $API_LEVEL, static)"
echo " [zstd] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "zstd-$ZSTD_VER.tar.gz" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "zstd-$ZSTD_VER.tar.gz" "$ZSTD_URL"
fi
echo "$ZSTD_SHA  zstd-$ZSTD_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[zstd] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "zstd-$ZSTD_VER" "zstd-build"
mkdir -p "zstd-$ZSTD_VER"
tar xzf "zstd-$ZSTD_VER.tar.gz" -C "zstd-$ZSTD_VER"
ZSRC="$(find "zstd-$ZSTD_VER" -maxdepth 3 -name CMakeLists.txt -path '*build/cmake*' \
    -printf '%h\n' | head -1)"
[ -n "$ZSRC" ] || { echo "[zstd] ERROR: no build/cmake source" >&2; exit 1; }

cmake -S "$ZSRC" -B "zstd-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DZSTD_BUILD_PROGRAMS=OFF -DZSTD_BUILD_CONTRIB=OFF -DZSTD_BUILD_TESTS=OFF \
    -DZSTD_BUILD_STATIC=ON -DZSTD_BUILD_SHARED=OFF -DZSTD_LEGACY_SUPPORT=OFF \
    -DCMAKE_POLICY_DEFAULT_CMP0074=NEW
cmake --build "zstd-build" -j"$N_CORES"
cmake --install "zstd-build"

test -f "$STAGE_ROOT/include/zstd.h" \
    || { echo "[zstd] ERROR: zstd.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libzstd.a' | grep -q . \
    || { echo "[zstd] ERROR: no libzstd.a" >&2; exit 1; }
ZCFG="$(find "$STAGE_ROOT" -name zstdConfig.cmake -printf '%h\n' | head -1)"
test -n "$ZCFG" || { echo "[zstd] ERROR: zstdConfig.cmake not installed" >&2; exit 1; }
grep -rq "zstd::libzstd" "$ZCFG" \
    || { echo "[zstd] ERROR: zstd::libzstd target missing from config" >&2; exit 1; }
echo "[zstd] done -> $STAGE_ROOT (config: $ZCFG)"