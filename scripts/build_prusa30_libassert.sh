#!/usr/bin/env bash
# scripts/build_prusa30_libassert.sh — cpptrace v1.0.4 + libassert v2.2.1 (static) for the NDK.
#
# slic3r-base links libassert::assert (ASSERT enforces in release too), and
# upstream builds libassert with LIBASSERT_USE_EXTERNAL_CPPTRACE=ON. Both go
# into ONE install prefix (engine/prusa30/jniImports/assert) so a single
# -Dlibassert_DIR satisfies find_package(libassert) and the transitive
# cpptrace::cpptrace resolves from the same tree. Static only.
#
# Backend selection is left to cpptrace's Autoconfig (dladdr/unwind/cxxabi
# all live in the NDK toolchain); external zstd/libdwarf are NOT used.
#
# Pins (exact, from upstream deps/+LibAssert + deps/+cpptrace):
#   cpptrace:  https://github.com/jeremy-rifkin/cpptrace/archive/refs/tags/v1.0.4.zip
#              SHA256=3fca735735cd74d646833f86112223f8aceb965055c8f96686a41d85948b50ba
#   libassert: https://github.com/jeremy-rifkin/libassert/archive/refs/tags/v2.2.1.zip
#              SHA256=a4728a2cc6d2672ba29443bbb871c2529a8a812f2d504c92a0b9b9cff1779117
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/assert)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CPPTRACE_VER="1.0.4"
CPPTRACE_URL="https://github.com/jeremy-rifkin/cpptrace/archive/refs/tags/v${CPPTRACE_VER}.zip"
CPPTRACE_SHA="3fca735735cd74d646833f86112223f8aceb965055c8f96686a41d85948b50ba"
LIBASSERT_VER="2.2.1"
LIBASSERT_URL="https://github.com/jeremy-rifkin/libassert/archive/refs/tags/v${LIBASSERT_VER}.zip"
LIBASSERT_SHA="a4728a2cc6d2672ba29443bbb871c2529a8a812f2d504c92a0b9b9cff1779117"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[assert] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/assert}"
TC="$NDK/build/cmake/android.toolchain.cmake"

echo "======================================================================="
echo " [assert] Building cpptrace $CPPTRACE_VER + libassert $LIBASSERT_VER ($ABI, api $API_LEVEL, static)"
echo " [assert] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"

fetch_verify() { # $1=file $2=url $3=sha
    [ -f "$1" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "$1" "$2"
    echo "$3  $1" | sha256sum -c --status - || { echo "[assert] ERROR: sha256 mismatch for $1" >&2; exit 1; }
}

fetch_verify "cpptrace-$CPPTRACE_VER.zip" "$CPPTRACE_URL" "$CPPTRACE_SHA"
fetch_verify "libassert-$LIBASSERT_VER.zip" "$LIBASSERT_URL" "$LIBASSERT_SHA"

rm -rf "cpptrace-$CPPTRACE_VER" "cpptrace-build"
mkdir -p "cpptrace-$CPPTRACE_VER" && unzip -q -o "cpptrace-$CPPTRACE_VER.zip" -d "cpptrace-$CPPTRACE_VER"
CSRC="$(find "cpptrace-$CPPTRACE_VER" -maxdepth 2 -name CMakeLists.txt -path '*cpptrace*' | head -1 | xargs dirname)"
[ -n "$CSRC" ] || { echo "[assert] ERROR: no cpptrace CMakeLists" >&2; exit 1; }
cmake -S "$CSRC" -B "cpptrace-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DCPPTRACE_BUILD_TESTING=OFF -DCPPTRACE_BUILD_BENCHMARKING=OFF -DCPPTRACE_BUILD_TOOLS=OFF
cmake --build "cpptrace-build" -j"$N_CORES"
cmake --install "cpptrace-build"

rm -rf "libassert-$LIBASSERT_VER" "libassert-build"
mkdir -p "libassert-$LIBASSERT_VER" && unzip -q -o "libassert-$LIBASSERT_VER.zip" -d "libassert-$LIBASSERT_VER"
LSRC="$(find "libassert-$LIBASSERT_VER" -maxdepth 2 -name CMakeLists.txt -path '*libassert*' -o -maxdepth 2 -name CMakeLists.txt -path '*assert*' | head -1 | xargs dirname)"
[ -n "$LSRC" ] || { echo "[assert] ERROR: no libassert CMakeLists" >&2; exit 1; }
cmake -S "$LSRC" -B "libassert-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DCMAKE_PREFIX_PATH="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DLIBASSERT_USE_EXTERNAL_CPPTRACE=ON \
    -Dcpptrace_DIR="$(find "$STAGE_ROOT" -name 'cpptrace-config.cmake' -printf '%h\n' | head -1)"
cmake --build "libassert-build" -j"$N_CORES"
cmake --install "libassert-build"

find "$STAGE_ROOT" -name 'libassert.a' -o -name 'libassert*.a' | grep -q . \
    || { echo "[assert] ERROR: no libassert archive staged" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libassert-config.cmake' -o -name 'libassertConfig.cmake' | grep -q . \
    || { echo "[assert] ERROR: no libassert config staged" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libassert/assert.hpp' | grep -q . \
    || { echo "[assert] ERROR: libassert headers missing" >&2; exit 1; }
echo "[assert] done -> $STAGE_ROOT"
