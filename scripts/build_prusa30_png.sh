#!/usr/bin/env bash
# scripts/build_prusa30_png.sh — zlib + libpng (prusaslicer_-prefixed) for the NDK.
#
# Upstream pins both via deps/+ZLIB and deps/+PNG (libpng v1.6.58 built with
# -DPNG_SHARED=OFF -DPNG_STATIC=ON, depending on its own ZLIB).
# NOTE: upstream's own deps/+PNG passes -DPNG_PREFIX=prusaslicer_ for its
# superbuild, but the headless core links stock FindPNG's PNG::PNG
# (png.h + png symbols). A prefixed libpng installs prusaslicer_png.h and
# prusaslicer_-suffixed symbols, which FindPNG/configure can never match —
# so build UNPREFIXED here. find_package(PNG) is CMake's stock module: it
# honors PNG_ROOT, so staging a single prefix with both installs is enough.
# Static only.
#
# With --libpng-only, only the libpng step runs against the zlib already in
# STAGE_ROOT (used by the pngfmt CI job which builds zlib first).
#
# Pins (exact, from upstream deps/):
#   zlib:      NDK sysroot zlib is linkable but ships no zlib.h-compatible
#              config certainty across API levels; build from source.
#              https://zlib.net/zlib-1.3.1.tar.gz
#              SHA256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede92e1ef76f3c77e02d1e
#   libpng:    https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.58.zip
#              SHA256=ad8fc23d75a76f352989bbec9e905bdfe8f2d2e77b32e4f2070a4bb1849802ee
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

ZLIB_VER="1.3.1"
ZLIB_URL="https://zlib.net/zlib-${ZLIB_VER}.tar.gz"
ZLIB_SHA="9a93b2b7dfdac77ceba5a558a580e74667dd6fede92e1ef76f3c77e02d1e"
PNG_VER="1.6.58"
PNG_URL="https://github.com/pnggroup/libpng/archive/refs/tags/v${PNG_VER}.zip"
PNG_SHA="ad8fc23d75a76f352989bbec9e905bdfe8f2d2e77b32e4f2070a4bb1849802ee"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[png] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/pngfmt}"
LIBPNG_ONLY=0
[ "${1:-}" = "--libpng-only" ] && LIBPNG_ONLY=1

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [png] Building zlib $ZLIB_VER + libpng $PNG_VER ($ABI, api $API_LEVEL, static)"
echo " [png] stage: $STAGE_ROOT (libpng-only=$LIBPNG_ONLY)"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT/include" "$STAGE_ROOT/lib"
cd "$WORK_DIR"

if [ "$LIBPNG_ONLY" = "0" ]; then
    [ -f "zlib-$ZLIB_VER.tar.gz" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "zlib-$ZLIB_VER.tar.gz" "$ZLIB_URL"
    echo "$ZLIB_SHA  zlib-$ZLIB_VER.tar.gz" | sha256sum -c --status - \
        || { echo "[png] ERROR: zlib sha256 mismatch" >&2; exit 1; }
    rm -rf "zlib-$ZLIB_VER" "zlib-build"
    mkdir -p "zlib-$ZLIB_VER" && tar xzf "zlib-$ZLIB_VER.tar.gz" -C "zlib-$ZLIB_VER"
    ZSRC="$(find "zlib-$ZLIB_VER" -maxdepth 2 -name CMakeLists.txt | head -1 | xargs dirname)"
    cmake -S "$ZSRC" -B "zlib-build" \
        -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
        -DBUILD_SHARED_LIBS=OFF -DSKIP_INSTALL_FILES=OFF
    cmake --build "zlib-build" -j"$N_CORES"
    cmake --install "zlib-build"
fi

[ -f "libpng-$PNG_VER.zip" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "libpng-$PNG_VER.zip" "$PNG_URL"
echo "$PNG_SHA  libpng-$PNG_VER.zip" | sha256sum -c --status - \
    || { echo "[png] ERROR: libpng sha256 mismatch" >&2; exit 1; }
rm -rf "libpng-$PNG_VER" "libpng-build"
mkdir -p "libpng-$PNG_VER" && unzip -q -o "libpng-$PNG_VER.zip" -d "libpng-$PNG_VER"
PSRC="$(find "libpng-$PNG_VER" -maxdepth 2 -name CMakeLists.txt -path '*png*' | head -1 | xargs dirname)"
[ -n "$PSRC" ] || { echo "[png] ERROR: no libpng CMakeLists" >&2; exit 1; }
cmake -S "$PSRC" -B "libpng-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_PREFIX_PATH="$STAGE_ROOT" \
    -DPNG_SHARED=OFF -DPNG_STATIC=ON \
    -DPNG_TESTS=OFF -DPNG_EXECUTABLES=OFF
cmake --build "libpng-build" -j"$N_CORES"
cmake --install "libpng-build"

test -f "$STAGE_ROOT/include/zlib.h" || { echo "[png] ERROR: zlib.h missing" >&2; exit 1; }
test -f "$STAGE_ROOT/include/png.h" || { echo "[png] ERROR: png.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libz.a' | grep -q . || { echo "[png] ERROR: no libz.a" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libpng*.a' | grep -q . || { echo "[png] ERROR: no libpng" >&2; exit 1; }
echo "[png] done -> $STAGE_ROOT"
