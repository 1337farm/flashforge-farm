#!/usr/bin/env bash
# scripts/build_prusa30_libbgcode.sh — Prusa libbgcode for the Android NDK
# (static).
#
# Required by find_package(LibBGCode REQUIRED COMPONENTS Convert) in
# src/libslic3r/CMakeLists.txt, which then remaps the LibBGCode::bgcode_core /
# bgcode_binarize / bgcode_convert imported targets into slic3r's internal
# targets.
#
# The library's own CMake supplies a full config package (LibBGCodeConfig.cmake
# exporting LibBGCode::bgcode_{core,binarize,convert}) whose Config.cmake.in
# re-derives downstream deps per component and find_dependency()s them at
# CONSUMER configure time:
#   Binarize: find_dependency(heatshrink 0.4) + find_dependency(ZLIB 1.0)
#   Convert : find_dependency(Boost 1.78)  (header-only Boost::boost)
# so the headless configure (and this build) needs heatshrink_DIR, ZLIB and
# Boost presets all resolvable — exactly what this script presets for the
# build and what the headless step pre-seeds at configure.
#
# Built with upstream's exact deps/+LibBGCode/LibBGCode.cmake CMAKE_ARGS
# (tests/cmd tool off, -DCMAKE_FIND_ROOT_PATH=/ so the NDK toolchain's
# sysroot re-rooting can't shadow staged deps), static via BUILD_SHARED_LIBS=OFF
# so the DOWNSTREAM_DEPS bake into the installed config.
#
# Pins (exact, from upstream deps/+LibBGCode/LibBGCode.cmake):
#   libbgcode: https://github.com/prusa3d/libbgcode/archive/d4da9073616d70a43c151e8c1d7fbff879d2e08a.zip
#              SHA256=73076348e80315d1c2584f883c6b07637cd847c74bca7e9279e42b56a308f948
#              (version 0.3.0 per cmake/ProjectVersion.cmake)
#
# Env inputs (all optional; defaults shown):
#   NDK              NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                    $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI              target ABI (default arm64-v8a)
#   API_LEVEL        Android API level (default 23)
#   N_CORES          parallelism (default nproc)
#   WORK_DIR         build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT       install prefix (default $(pwd)/engine/prusa30/jniImports/bgcode)
#   BOOST_STAGE      Boost prefix (default boost stage; header-only for Convert,
#                    but Boost_INCLUDE_DIR must resolve FindBoost >= 1.78)
#   ZLIB_STAGE       zlib prefix (default pngfmt stage; needs zlib.h + libz.a)
#   HEATSHRINK_STAGE heatshrink prefix (default heatshrink stage; needs its
#                    heatshrinkConfig.cmake, see build_prusa30_heatshrink.sh)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BGCODE_VER="0.3.0"
BGCODE_URL="https://github.com/prusa3d/libbgcode/archive/d4da9073616d70a43c151e8c1d7fbff879d2e08a.zip"
BGCODE_SHA="73076348e80315d1c2584f883c6b07637cd847c74bca7e9279e42b56a308f948"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[libbgcode] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/bgcode}"
BOOST_STAGE="${BOOST_STAGE:-$ROOT/engine/prusa30/jniImports/boost}"
ZLIB_STAGE="${ZLIB_STAGE:-$ROOT/engine/prusa30/jniImports/pngfmt}"
HEATSHRINK_STAGE="${HEATSHRINK_STAGE:-$ROOT/engine/prusa30/jniImports/heatshrink}"

BOOST_INC_DIR="$BOOST_STAGE/include"
test -f "$BOOST_INC_DIR/boost/version.hpp" \
    || { echo "[libbgcode] ERROR: boost/version.hpp not staged in $BOOST_STAGE" >&2; exit 1; }
grep -q "1_86" "$BOOST_INC_DIR/boost/version.hpp" \
    || { echo "[libbgcode] ERROR: staged Boost headers are not 1.86" >&2; exit 1; }
ZLIB_INC_DIR="$(dirname "$(find "$ZLIB_STAGE" -name zlib.h | head -1)")"
[ -n "$ZLIB_INC_DIR" ] || { echo "[libbgcode] ERROR: zlib.h not staged in $ZLIB_STAGE" >&2; exit 1; }
ZLIB_LIB="$(find "$ZLIB_STAGE" -name 'libz.a' | head -1)"
[ -n "$ZLIB_LIB" ] || { echo "[libbgcode] ERROR: libz.a not staged in $ZLIB_STAGE" >&2; exit 1; }
HS_CMAKE_DIR="$(find "$HEATSHRINK_STAGE" -name heatshrinkConfig.cmake -printf '%h\n' | head -1)"
test -n "$HS_CMAKE_DIR" || { echo "[libbgcode] ERROR: heatshrinkConfig.cmake not staged in $HEATSHRINK_STAGE" >&2; exit 1; }
grep -rq "heatshrink::heatshrink_dynalloc" "$HS_CMAKE_DIR" \
    || { echo "[libbgcode] ERROR: heatshrink::heatshrink_dynalloc target missing from staged heatshrink config" >&2; exit 1; }

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [libbgcode] Building libbgcode $BGCODE_VER ($ABI, api $API_LEVEL, static)"
echo " [libbgcode] stage: $STAGE_ROOT"
echo " [libbgcode] boost: $BOOST_INC_DIR | zlib: $ZLIB_LIB | heatshrink: $HS_CMAKE_DIR"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "libbgcode-pin.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "libbgcode-pin.zip" "$BGCODE_URL"
fi
echo "$BGCODE_SHA  libbgcode-pin.zip" | sha256sum -c --status - \
    || { echo "[libbgcode] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "libbgcode-pin" "libbgcode-build"
mkdir -p "libbgcode-pin"
unzip -q -o "libbgcode-pin.zip" -d "libbgcode-pin"
BSRC="$(find "libbgcode-pin" -maxdepth 2 -name CMakeLists.txt -path '*libbgcode*' \
    -printf '%h\n' | head -1)"
[ -n "$BSRC" ] || { echo "[libbgcode] ERROR: no libbgcode CMakeLists" >&2; exit 1; }

cmake -S "$BSRC" -B "libbgcode-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DLibBGCode_BUILD_TESTS=OFF -DLibBGCode_BUILD_CMD_TOOL=OFF \
    -DLibBGCode_BUILD_SANITIZERS=OFF \
    -DCMAKE_FIND_ROOT_PATH=/ \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -DBOOST_ROOT="$BOOST_STAGE" -DBoost_ROOT="$BOOST_STAGE" \
    -DBoost_INCLUDE_DIR="$BOOST_INC_DIR" \
    -DBoost_COMPILER="-clang" -DBoost_ARCHITECTURE="-a64" \
    -Dheatshrink_DIR="$HS_CMAKE_DIR" \
    -DZLIB_INCLUDE_DIR="$ZLIB_INC_DIR" -DZLIB_LIBRARY="$ZLIB_LIB"
cmake --build "libbgcode-build" -j"$N_CORES"
cmake --install "libbgcode-build"

test -f "$STAGE_ROOT/include/LibBGCode/core/core.hpp" \
    || { echo "[libbgcode] ERROR: LibBGCode core headers missing" >&2; exit 1; }
test -f "$STAGE_ROOT/include/LibBGCode/binarize/binarize.hpp" \
    || { echo "[libbgcode] ERROR: LibBGCode binarize headers missing" >&2; exit 1; }
test -f "$STAGE_ROOT/include/LibBGCode/convert/convert.hpp" \
    || { echo "[libbgcode] ERROR: LibBGCode convert headers missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libbgcode_core.a' | grep -q . \
    || { echo "[libbgcode] ERROR: no libbgcode_core.a" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libbgcode_binarize.a' | grep -q . \
    || { echo "[libbgcode] ERROR: no libbgcode_binarize.a" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libbgcode_convert.a' | grep -q . \
    || { echo "[libbgcode] ERROR: no libbgcode_convert.a" >&2; exit 1; }
BGCODE_CFG="$(find "$STAGE_ROOT" -name LibBGCodeConfig.cmake -printf '%h\n' | head -1)"
test -n "$BGCODE_CFG" || { echo "[libbgcode] ERROR: LibBGCodeConfig.cmake not installed" >&2; exit 1; }
grep -rq "LibBGCode::bgcode_convert" "$BGCODE_CFG" \
    || { echo "[libbgcode] ERROR: LibBGCode::bgcode_convert target missing from config" >&2; exit 1; }
echo "[libbgcode] done -> $STAGE_ROOT (config: $BGCODE_CFG)"