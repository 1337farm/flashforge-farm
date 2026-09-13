#!/usr/bin/env bash
# scripts/build_prusa30_lua.sh — Lua for the Android NDK (static).
#
# Required by find_package(Lua REQUIRED) + find_package(sol2 REQUIRED) in
# src/slic3r-biz-lua/CMakeLists.txt (the headless configure fails with
# "Could NOT find Lua (missing: LUA_LIBRARIES LUA_INCLUDE_DIR)" otherwise).
# Built with Lua's own make (upstream deps/+Lua/Lua.cmake pins the identical
# release; no CMake, so no install-libdir resolution quirk). Only the static
# library + canonical headers are staged: the lua/luac executables are not
# built, so no readline/math-link concerns for the Android cross toolchain.
#
# Pins (exact, from upstream deps/+Lua/Lua.cmake):
#   Lua: https://www.lua.org/ftp/lua-5.4.8.tar.gz
#        SHA256=4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/lua)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

LUA_VER="5.4.8"
LUA_URL="https://www.lua.org/ftp/lua-${LUA_VER}.tar.gz"
LUA_SHA="4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[lua] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/lua}"

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
PREBUILT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$PREBUILT" ]; then
    echo "[lua] ERROR: no prebuilt toolchain at $PREBUILT" >&2
    exit 1
fi
CLANG="$PREBUILT/bin/aarch64-linux-android${API_LEVEL}-clang"
RANLIB="$PREBUILT/bin/llvm-ranlib"

echo "======================================================================="
echo " [lua] Building Lua $LUA_VER ($ABI, api $API_LEVEL, static)"
echo " [lua] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "lua-$LUA_VER.tar.gz" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "lua-$LUA_VER.tar.gz" "$LUA_URL"
fi
echo "$LUA_SHA  lua-$LUA_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[lua] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "lua-$LUA_VER"
tar -xzf "lua-$LUA_VER.tar.gz"
LSRC="lua-$LUA_VER"

make -C "$LSRC/src" -j"$N_CORES" liblua.a \
    CC="$CLANG" AR="$PREBUILT/bin/llvm-ar rcu" RANLIB="$RANLIB" MYCFLAGS="-fPIC"

mkdir -p "$STAGE_ROOT/include" "$STAGE_ROOT/lib"
cp "$LSRC/src/liblua.a" "$STAGE_ROOT/lib/"
for h in lua.h luaconf.h lualib.h lauxlib.h; do
    cp "$LSRC/src/$h" "$STAGE_ROOT/include/"
done

test -f "$STAGE_ROOT/include/lua.h" \
    || { echo "[lua] ERROR: lua.h missing" >&2; exit 1; }
test -f "$STAGE_ROOT/lib/liblua.a" \
    || { echo "[lua] ERROR: liblua.a missing" >&2; exit 1; }
echo "[lua] done -> $STAGE_ROOT"