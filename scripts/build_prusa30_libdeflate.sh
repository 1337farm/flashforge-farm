#!/usr/bin/env bash
# scripts/build_prusa30_libdeflate.sh — libdeflate v1.26 (static) for the NDK.
#
# Upstream 3mf/Model3mf.cpp calls libdeflate_* (needs the archive, not just
# headers). Pin/URL/SHA mirror upstream deps/+libdeflate. Installs into ONE
# prefix (engine/prusa30/jniImports/libdeflate) with headers + libdeflate.a.
# No cache reuse by design (like libassert/GMP): ~1 min, immune to poisoning.
#
#   NDK/ABI/API_LEVEL/N_CORES/WORK_DIR/STAGE_ROOT (defaults below)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

LIBDEFLATE_VER="1.26"
LIBDEFLATE_URL="https://github.com/ebiggers/libdeflate/archive/refs/tags/v${LIBDEFLATE_VER}.zip"
LIBDEFLATE_SHA="cc64b9177e8c7eed2d3960a3dd9b0aa061ddc4c43c9067af1209e208e4a8144f"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[libdeflate] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_libdeflate}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/libdeflate}"
TC="$NDK/build/cmake/android.toolchain.cmake"

echo "======================================================================="
echo " [libdeflate] Building libdeflate $LIBDEFLATE_VER ($ABI, api $API_LEVEL, static)"
echo " [libdeflate] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"

fetch_verify() { # $1=file $2=url $3=sha
    [ -f "$1" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "$1" "$2"
    echo "$3  $1" | sha256sum -c --status - || { echo "[libdeflate] ERROR: sha256 mismatch for $1" >&2; exit 1; }
}

fetch_verify "libdeflate-$LIBDEFLATE_VER.zip" "$LIBDEFLATE_URL" "$LIBDEFLATE_SHA"

rm -rf "libdeflate-$LIBDEFLATE_VER" "libdeflate-build"
mkdir -p "libdeflate-$LIBDEFLATE_VER" && unzip -q -o "libdeflate-$LIBDEFLATE_VER.zip" -d "libdeflate-$LIBDEFLATE_VER"
LSRC="$(find "libdeflate-$LIBDEFLATE_VER" -maxdepth 2 -name CMakeLists.txt -path '*libdeflate*' | head -1 | xargs dirname)"
[ -n "$LSRC" ] || { echo "[libdeflate] ERROR: no libdeflate CMakeLists" >&2; exit 1; }
cmake -S "$LSRC" -B "libdeflate-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DLIBDEFLATE_BUILD_SHARED_LIB=OFF \
    -DLIBDEFLATE_BUILD_GZIP=OFF
cmake --build "libdeflate-build" -j"$N_CORES"
cmake --install "libdeflate-build"

find "$STAGE_ROOT" -name 'libdeflate.a' | grep -q . \
    || { echo "[libdeflate] ERROR: no libdeflate.a staged" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libdeflate.h' | grep -q . \
    || { echo "[libdeflate] ERROR: libdeflate headers missing" >&2; exit 1; }
echo "[libdeflate] v$LIBDEFLATE_VER staged and verified"
