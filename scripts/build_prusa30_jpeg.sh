#!/usr/bin/env bash
# scripts/build_prusa30_jpeg.sh — libjpeg-turbo for the Android NDK (static).
#
# Required by find_package(JPEG) in src/libslic3r/CMakeLists.txt (configure
# fails with "Could NOT find JPEG (missing: JPEG_LIBRARY JPEG_INCLUDE_DIR)"
# otherwise). Built static with upstream's exact deps/+JPEG/JPEG.cmake
# CMAKE_ARGS (shared OFF, static ON, policy 3.5, INSTALL_LIBDIR pinned to an
# ABSOLUTE path under the prefix because turbo forces lib64 AND a relative
# -D CMAKE_INSTALL_LIBDIR is resolved against the process cwd by CMake). Depends on ZLIB (staged in pngfmt).
#
# Pins (exact, from upstream deps/+JPEG/JPEG.cmake):
#   libjpeg-turbo: https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.1.zip
#                  SHA256=d6d99e693366bc03897677650e8b2dfa76b5d6c54e2c9e70c03f0af821b0a52f
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/jpeg)
#   ZLIB_STAGE    zlib prefix (default pngfmt stage; needs zlib.h + libz.a)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JPEG_VER="3.0.1"
JPEG_URL="https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/${JPEG_VER}.zip"
JPEG_SHA="d6d99e693366bc03897677650e8b2dfa76b5d6c54e2c9e70c03f0af821b0a52f"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[jpeg] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/jpeg}"
ZLIB_STAGE="${ZLIB_STAGE:-$ROOT/engine/prusa30/jniImports/pngfmt}"

ZLIB_INC_DIR="$(dirname "$(find "$ZLIB_STAGE" -name zlib.h | head -1)")"
[ -n "$ZLIB_INC_DIR" ] || { echo "[jpeg] ERROR: zlib.h not staged in $ZLIB_STAGE" >&2; exit 1; }
ZLIB_LIB="$(find "$ZLIB_STAGE" -name 'libz.a' | head -1)"
[ -n "$ZLIB_LIB" ] || { echo "[jpeg] ERROR: libz.a not staged in $ZLIB_STAGE" >&2; exit 1; }

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [jpeg] Building libjpeg-turbo $JPEG_VER ($ABI, api $API_LEVEL, static)"
echo " [jpeg] stage: $STAGE_ROOT"
echo " [jpeg] zlib: $ZLIB_LIB"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "libjpeg-turbo-$JPEG_VER.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "libjpeg-turbo-$JPEG_VER.zip" "$JPEG_URL"
fi
echo "$JPEG_SHA  libjpeg-turbo-$JPEG_VER.zip" | sha256sum -c --status - \
    || { echo "[jpeg] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "libjpeg-turbo-$JPEG_VER" "jpeg-build"
mkdir -p "libjpeg-turbo-$JPEG_VER"
unzip -q -o "libjpeg-turbo-$JPEG_VER.zip" -d "libjpeg-turbo-$JPEG_VER"
JSRC="$(find "libjpeg-turbo-$JPEG_VER" -maxdepth 2 -name CMakeLists.txt -path '*libjpeg-turbo*' \
    -printf '%h\n' | head -1)"
[ -n "$JSRC" ] || { echo "[jpeg] ERROR: no libjpeg-turbo CMakeLists" >&2; exit 1; }

cmake -S "$JSRC" -B "jpeg-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_INSTALL_LIBDIR:PATH="$STAGE_ROOT/lib" \
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON \
    -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF -DWITH_SIMD=ON \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DZLIB_INCLUDE_DIR="$ZLIB_INC_DIR" -DZLIB_LIBRARY="$ZLIB_LIB"
cmake --build "jpeg-build" -j"$N_CORES"
cmake --install "jpeg-build"

test -f "$STAGE_ROOT/include/jpeglib.h" \
    || { echo "[jpeg] ERROR: jpeglib.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libjpeg.a' | grep -q . \
    || { echo "[jpeg] ERROR: no libjpeg.a" >&2; exit 1; }
echo "[jpeg] done -> $STAGE_ROOT"