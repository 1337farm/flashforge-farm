#!/usr/bin/env bash
# scripts/build_prusa30_blosc.sh — c-blosc (Blosc) for the Android NDK (static).
#
# Required by find_package(OpenVDB): prusa's bundled FindOpenVDB runs
# find_package(Blosc REQUIRED) once the staged openvdb/version.h declares
# OPENVDB_USE_BLOSC (USE_BLOSC=ON), and libslic3r links Blosc::blosc. Blosc's
# own FindBlosc then dohes an unconditional find_package(zstd REQUIRED) +
# links the zstd::libzstd target, so this stage needs BOTH an external zstd
# (PREFER_EXTERNAL_ZSTD=ON propagates the CMake runtime link) and the zstd
# config package staged by build_prusa30_zstd.sh.
#
# Built with upstream's exact deps/+Blosc/Blosc.cmake CMAKE_ARGS (static,
# external zlib/zstd, PIC, no tests/benchmarks). -DDEACTIVATE_SSE2=ON only
# disables the x86 SSE2 code path; the NDK arm64 build never uses it, matching
# how upstream compiles on non-x86.
#
# Pins (exact, from upstream deps/+Blosc/Blosc.cmake):
#   c-blosc: https://github.com/Blosc/c-blosc/archive/refs/tags/v1.21.6.zip
#            SHA256=1919c97d55023c04aa8771ea8235b63e9da3c22e3d2a68340b33710d19c2a2eb
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/blosc)
#   ZLIB_ROOT     zlib prefix (default pngfmt stage; needs zlib.h + libz.a)
#   ZSTD_ROOT     zstd prefix (default zstd stage; needs zstd.h + libzstd.a
#                 + its zstdConfig.cmake, see build_prusa30_zstd.sh)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BLOSC_VER="1.21.6"
BLOSC_URL="https://github.com/Blosc/c-blosc/archive/refs/tags/v${BLOSC_VER}.zip"
BLOSC_SHA="1919c97d55023c04aa8771ea8235b63e9da3c22e3d2a68340b33710d19c2a2eb"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[blosc] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/blosc}"
ZLIB_ROOT="${ZLIB_ROOT:-$ROOT/engine/prusa30/jniImports/pngfmt}"
ZSTD_ROOT="${ZSTD_ROOT:-$ROOT/engine/prusa30/jniImports/zstd}"

# c-blosc reads ZLIB_ROOT/ZSTD_ROOT from the environment; also resolve exact
# lib+include paths so its find calls short-circuit (NDK MODE=ONLY re-rooting
# and sysroot shadowing can't miss a pre-seeded cache var).
ZLIB_INC_DIR="$(dirname "$(find "$ZLIB_ROOT" -name zlib.h | head -1)")"
[ -n "$ZLIB_INC_DIR" ] || { echo "[blosc] ERROR: zlib.h not staged in $ZLIB_ROOT" >&2; exit 1; }
ZLIB_LIB="$(find "$ZLIB_ROOT" -name 'libz.a' | head -1)"
[ -n "$ZLIB_LIB" ] || { echo "[blosc] ERROR: libz.a not staged in $ZLIB_ROOT" >&2; exit 1; }
ZSTD_INC_DIR="$(dirname "$(find "$ZSTD_ROOT" -name zstd.h | head -1)")"
[ -n "$ZSTD_INC_DIR" ] || { echo "[blosc] ERROR: zstd.h not staged in $ZSTD_ROOT" >&2; exit 1; }
ZSTD_LIB="$(find "$ZSTD_ROOT" -name 'libzstd.a' | head -1)"
[ -n "$ZSTD_LIB" ] || { echo "[blosc] ERROR: libzstd.a not staged in $ZSTD_ROOT" >&2; exit 1; }
find "$ZSTD_ROOT" -name zstdConfig.cmake | grep -q . \
    || { echo "[blosc] ERROR: zstdConfig.cmake missing (build_prusa30_zstd.sh)" >&2; exit 1; }
export ZLIB_ROOT ZSTD_ROOT

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [blosc] Building c-blosc $BLOSC_VER ($ABI, api $API_LEVEL, static)"
echo " [blosc] stage: $STAGE_ROOT"
echo " [blosc] zlib: $ZLIB_LIB | zstd: $ZSTD_LIB"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "c-blosc-$BLOSC_VER.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "c-blosc-$BLOSC_VER.zip" "$BLOSC_URL"
fi
echo "$BLOSC_SHA  c-blosc-$BLOSC_VER.zip" | sha256sum -c --status - \
    || { echo "[blosc] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "c-blosc-$BLOSC_VER" "blosc-build"
mkdir -p "c-blosc-$BLOSC_VER"
unzip -q -o "c-blosc-$BLOSC_VER.zip" -d "c-blosc-$BLOSC_VER"
BSRC="$(find "c-blosc-$BLOSC_VER" -maxdepth 2 -name CMakeLists.txt -path '*c-blosc*' \
    -printf '%h\n' | head -1)"
[ -n "$BSRC" ] || { echo "[blosc] ERROR: no c-blosc CMakeLists" >&2; exit 1; }

cmake -S "$BSRC" -B "blosc-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED=OFF -DBUILD_STATIC=ON \
    -DBUILD_TESTS=OFF -DBUILD_BENCHMARKS=OFF \
    -DPREFER_EXTERNAL_ZLIB=ON -DPREFER_EXTERNAL_ZSTD=ON \
    -DDEACTIVATE_SSE2=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DZLIB_INCLUDE_DIR="$ZLIB_INC_DIR" -DZLIB_LIBRARY="$ZLIB_LIB" \
    -DZSTD_INCLUDE_DIR="$ZSTD_INC_DIR" -DZSTD_LIBRARY="$ZSTD_LIB"
cmake --build "blosc-build" -j"$N_CORES"
cmake --install "blosc-build"

test -f "$STAGE_ROOT/include/blosc.h" \
    || { echo "[blosc] ERROR: blosc.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libblosc.a' | grep -q . \
    || { echo "[blosc] ERROR: no libblosc.a" >&2; exit 1; }
echo "[blosc] done -> $STAGE_ROOT"