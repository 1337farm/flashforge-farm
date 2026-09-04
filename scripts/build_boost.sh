#!/usr/bin/env bash
# scripts/build_boost.sh — shared Boost-for-Android build core.
#
# Used by the CI "Build Native Engine (from source)" pipeline via
# scripts/build_all_deps_android.sh (WRAP_HOST_TOOLS=none, x86-64 GitHub
# runner where the NDK host tools run natively).
#
# Local on-device builds do NOT compile Boost here anymore: they download the
# CI-built native dependencies (see scripts/fetch-native-deps.sh).
#
# Builds Boost 1.85.0 for the configured ABI via Boost-for-Android's
# build-android.sh, then copies the static archives + headers into the source
# tree at $JNI_IMPORTS_DIR/boost. By default we do a FULL build
# (BOOST_ALL_LIBS=1) so every archive b2 produces for this ABI is staged and
# available to the app; the app links exactly what it needs and unused archives
# are simply not pulled by the linker.
#
# Env inputs (all optional; defaults shown):
#   ANDROID_NDK_ROOT  NDK root (required; falls back to $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ANDROID_SDK_ROOT  SDK root (default ~/android-sdk)
#   ABI               target ABI (default arm64-v8a)
#   API_LEVEL         Android target version (default 21)
#   N_CORES           parallelism (default nproc)
#   BOOST_ALL_LIBS    1 (default) for full build, 0 to restrict to BOOST_LIBS
#   BOOST_LIBS        comma-separated --with-libraries list (only used when BOOST_ALL_LIBS=0)
#   WRAP_HOST_TOOLS   must be 'none' (the only supported mode; emulator modes removed)
#   WORK_DIR          build scratch dir (default /tmp/build_android_deps)
#   JNI_IMPORTS_DIR   destination parent dir (default $(pwd)/engine/src/main/jniImports)
set -euo pipefail

# Resolve repo root from this script's location so JNI_IMPORTS_DIR defaults
# correctly regardless of the caller's cwd.
SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"

# --- inputs --------------------------------------------------------------
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
NDK="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
if [ ! -d "$NDK" ]; then
    echo "[boost] ERROR: NDK not found at $NDK" >&2
    echo "[boost]        set ANDROID_NDK_ROOT or ANDROID_SDK_ROOT" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-21}"
N_CORES="${N_CORES:-$(nproc)}"
WRAP_HOST_TOOLS="${WRAP_HOST_TOOLS:-none}"
WORK_DIR="${WORK_DIR:-/tmp/build_android_deps}"
JNI_IMPORTS_DIR="${JNI_IMPORTS_DIR:-$ROOT/engine/src/main/jniImports}"

# The full Boost library list produced by a FULL build. By default we build
# everything (BOOST_ALL_LIBS=1) — unused archives are simply not pulled by the
# linker and have no effect on APK size or runtime. Set BOOST_ALL_LIBS=0 to
# restrict to BOOST_LIBS for faster dev-cycle builds.
BOOST_LIBS="${BOOST_LIBS:-atomic,charconv,chrono,container,context,contract,coroutine,date_time,exception,fiber,filesystem,graph,iostreams,json,log,math,nowide,program_options,random,regex,serialization,stacktrace,system,test,thread,timer,type_erasure,url,wave}"

echo "======================================================================="
echo " [Boost] Building Boost 1.85.0 ($ABI, api $API_LEVEL, -j$N_CORES, wrap=$WRAP_HOST_TOOLS)"
echo " [Boost] NDK: $NDK"
if [ "${BOOST_ALL_LIBS:-1}" = "1" ]; then
    echo " [Boost] mode: FULL build (all libraries; python/mpi auto-skipped by b2)"
else
    echo " [Boost] libs: $BOOST_LIBS"
fi
echo "======================================================================="

# NOTE: local on-device builds no longer compile Boost via qemu-emulated NDK
# tools. Native deps are downloaded from the CI "Build Native Engine" workflow
# (see scripts/fetch-native-deps.sh). This script now only runs Boost's own
# build for the CI x86-64 path (WRAP_HOST_TOOLS must be 'none').

# --- clone Boost-for-Android --------------------------------------------
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
if [ ! -d "Boost-for-Android" ]; then
    git clone --recursive --depth 1 https://github.com/moritz-wundke/Boost-for-Android.git
fi
cd Boost-for-Android

# --- build ---------------------------------------------------------------
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
# b2's engine build.sh (tools/build/src/engine/build.sh:509) embeds a Windows
# manifest via `windres` -> res.o ONLY when it finds `windres` on PATH. Termux
# ships windres, producing a COFF res.o that the host aarch64 linker (and the
# CI x86_64 linker, which rebuilds the engine per-run) cannot combine. Setting
# B2_DONT_EMBED_MANIFEST skips that res.o/windres step entirely.
export B2_DONT_EMBED_MANIFEST=1
# build-android.sh reads NCPU for its -jN (it is NOT a -- flag). Default 4.
export NCPU="$N_CORES"

# Flags verified against build-android.sh source:
#   --boost=<ver>            selects Boost version
#   --arch=<list>            limits build to the requested ABI (default builds
#                            all 4 ABIs; we only need arm64-v8a)
#   --target-version=<ver>   Android API level (NOT --api-level, which is invalid)
#   <ndk-root>               positional NDK root path
#
# DEFAULT: full build (BOOST_ALL_LIBS=1) — stage every archive b2 produces.
# Set BOOST_ALL_LIBS=0 to restrict to the BOOST_LIBS --with-libraries list
# (matches app/CMakeLists.txt) for faster dev-cycle builds.
b2_libs=()
if [ "${BOOST_ALL_LIBS:-1}" = "1" ]; then
    b2_libs=()
else
    b2_libs=(--with-libraries="$BOOST_LIBS")
fi
./build-android.sh \
    --boost=1.85.0 \
    "${b2_libs[@]}" \
    --arch="$ABI" \
    --target-version="$API_LEVEL" \
    "$NDK"

# --- validate + copy outputs ---------------------------------------------
OUT="build/out/$ABI/lib"
if ! ls "$OUT"/*.a >/dev/null 2>&1; then
    echo "[boost] ERROR: no .a archives produced under $OUT" >&2
    exit 1
fi
DEST="$JNI_IMPORTS_DIR/boost"
mkdir -p "$DEST/lib/$ABI/lib" "$DEST/include"
cp "$OUT"/*.a "$DEST/lib/$ABI/lib/"
# Boost-for-Android installs headers under include/boost-1_85/boost/... but the
# app's CMakeLists expects them flat under include/boost/... (include dirs in
# CMakeLists.txt reference engine/src/main/jniImports/boost/include). Normalize (safe
# to re-run): lift the <ver>/boost dir up one level, then drop the version dir.
cp -r "build/out/$ABI/include/." "$DEST/include/"
if [ -d "$DEST/include/boost-1_85" ]; then
    cp -r "$DEST/include/boost-1_85/." "$DEST/include/"
    rm -rf "$DEST/include/boost-1_85"
fi
echo "[boost] done. $(ls "$DEST/lib/$ABI/lib"/*.a | wc -l) archives -> $DEST/lib/$ABI/lib"
