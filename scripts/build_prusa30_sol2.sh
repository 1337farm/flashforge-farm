#!/usr/bin/env bash
# scripts/build_prusa30_sol2.sh — sol2 for the Android NDK (header-only).
#
# Required by find_package(sol2 REQUIRED) in src/slic3r-biz-lua/CMakeLists.txt
# (config mode; the staged sol2-config.cmake + sol2-targets.cmake provide the
# header-only sol2::sol2 INTERFACE target). Built with sol2's own CMake as the
# TOP-LEVEL project (upstream deps/+Sol2/Sol2.cmake style: SOL2_BUILD_LUA=OFF
# and the policy pin). Its standalone configure runs
# "find_package(Lua 5.4 EXACT REQUIRED)", so the staged Lua prefix is pre-seeded
# via LUA_INCLUDE_DIR/LUA_LIBRARY (find_path/find_library honor presets even
# under the NDK's CMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY re-rooting, verified).
# LUA_MATH_LIBRARY is pre-seeded from the NDK sysroot when present (Android has
# no separate libm; FindLua would otherwise emit LUA_MATH_LIBRARY-NOTFOUND).
#
# Pins (exact, from upstream deps/+Sol2/Sol2.cmake):
#   sol2: https://github.com/ThePhD/sol2/archive/refs/tags/v3.5.0.zip
#         SHA256=b43e539415956960055f62a9d328fec3fd1ad4f272d6206631b9f022b0b12678
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/sol2)
#   LUA_STAGE     staged Lua prefix (default jniImports/lua; needs lua.h + liblua.a)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SOL2_VER="3.5.0"
SOL2_URL="https://github.com/ThePhD/sol2/archive/refs/tags/v${SOL2_VER}.zip"
SOL2_SHA="b43e539415956960055f62a9d328fec3fd1ad4f272d6206631b9f022b0b12678"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[sol2] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/sol2}"
LUA_STAGE="${LUA_STAGE:-$ROOT/engine/prusa30/jniImports/lua}"

LUA_INC_DIR="$(dirname "$(find "$LUA_STAGE" -name lua.h | head -1)")"
[ -n "$LUA_INC_DIR" ] || { echo "[sol2] ERROR: lua.h not staged in $LUA_STAGE" >&2; exit 1; }
LUA_LIB="$(find "$LUA_STAGE" -name 'liblua.a' | head -1)"
[ -n "$LUA_LIB" ] || { echo "[sol2] ERROR: liblua.a not staged in $LUA_STAGE" >&2; exit 1; }
LUA_MATH_LIB="$(find "$NDK" \( -name 'libm.a' -o -name 'libm.so' \) | head -1 || true)"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [sol2] Building sol2 v$SOL2_VER ($ABI, api $API_LEVEL, header-only)"
echo " [sol2] stage: $STAGE_ROOT"
echo " [sol2] lua include: $LUA_INC_DIR"
echo " [sol2] lua lib: $LUA_LIB"
echo "======================================================================="

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
if [ ! -f "sol2-v$SOL2_VER.zip" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "sol2-v$SOL2_VER.zip" "$SOL2_URL"
fi
echo "$SOL2_SHA  sol2-v$SOL2_VER.zip" | sha256sum -c --status - \
    || { echo "[sol2] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "sol2-v$SOL2_VER" "sol2-3.5.0" "sol2-build"
unzip -q -o "sol2-v$SOL2_VER.zip"
SSRC="$(find . -maxdepth 2 -name CMakeLists.txt -path '*sol2*/CMakeLists.txt' \
    -printf '%h\n' | head -1)"
[ -n "$SSRC" ] || { echo "[sol2] ERROR: no sol2 CMakeLists" >&2; exit 1; }

LUA_ARGS=(-DLUA_INCLUDE_DIR="$LUA_INC_DIR" -DLUA_LIBRARY="$LUA_LIB")
if [ -n "$LUA_MATH_LIB" ]; then
    LUA_ARGS+=(-DLUA_MATH_LIBRARY="$LUA_MATH_LIB")
fi

cmake -S "$SSRC" -B "sol2-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DSOL2_BUILD_LUA=OFF -DSOL2_LUA_VERSION=5.4 \
    -DSOL2_TESTS=OFF -DSOL2_EXAMPLES=OFF -DSOL2_CI=OFF \
    -DSOL2_DOCS=OFF -DSOL2_DYNAMIC_LOADING_EXAMPLES=OFF \
    -DCMAKE_CXX_STANDARD=17 -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    "${LUA_ARGS[@]}"
cmake --build "sol2-build" -j"$N_CORES"
cmake --install "sol2-build"

test -d "$STAGE_ROOT/include/sol" \
    || { echo "[sol2] ERROR: sol/ headers missing" >&2; exit 1; }
SOL2_CMAKE_DIR="$(find "$STAGE_ROOT" -name sol2-config.cmake -printf '%h\n' | head -1)"
[ -n "$SOL2_CMAKE_DIR" ] || { echo "[sol2] ERROR: sol2-config.cmake missing" >&2; exit 1; }
test -f "$SOL2_CMAKE_DIR/sol2-targets.cmake" \
    || { echo "[sol2] ERROR: sol2-targets.cmake missing" >&2; exit 1; }
grep -q "sol2::sol2" "$SOL2_CMAKE_DIR/sol2-targets.cmake" \
    || { echo "[sol2] ERROR: sol2::sol2 target missing" >&2; exit 1; }
echo "[sol2] done -> $STAGE_ROOT (config: $SOL2_CMAKE_DIR)"