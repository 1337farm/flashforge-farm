#!/usr/bin/env bash
# scripts/build_prusa30_qhull.sh — Qhull v8.1-alpha3 (static) for the NDK.
#
# slic3r-biz-algorithms consumes find_package(Qhull 7.2) with
# Qhull::qhullcpp + Qhull::qhullstatic_r (SLIC3R_STATIC path). Qhull's own
# CMake installs those config targets, so stage a real `cmake --install`
# prefix at engine/prusa30/jniImports/qhull and resolve either config-file
# casing at configure time (Qhull year-2000 CMake names vary by version).
# Static-only build of the full project (tests off).
#
# Pin (exact, from upstream deps/+Qhull/Qhull.cmake):
#   https://github.com/qhull/qhull/archive/refs/tags/v8.1-alpha3.zip
#   SHA256=7bd9b5ffae01e69c2ead52f9a9b688af6c65f9a1da05da0a170fa20d81404c06
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/qhull)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

QHULL_VER="8.1-alpha3"
QHULL_URL="https://github.com/qhull/qhull/archive/refs/tags/v${QHULL_VER}.zip"
QHULL_SHA="7bd9b5ffae01e69c2ead52f9a9b688af6c65f9a1da05da0a170fa20d81404c06"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[qhull] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/qhull}"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [qhull] Building Qhull $QHULL_VER ($ABI, api $API_LEVEL, static)"
echo " [qhull] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
[ -f "qhull-$QHULL_VER.zip" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "qhull-$QHULL_VER.zip" "$QHULL_URL"
echo "$QHULL_SHA  qhull-$QHULL_VER.zip" | sha256sum -c --status - \
    || { echo "[qhull] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "qhull-$QHULL_VER" "qhull-build"
mkdir -p "qhull-$QHULL_VER" && unzip -q -o "qhull-$QHULL_VER.zip" -d "qhull-$QHULL_VER"
QSRC="$(find "qhull-$QHULL_VER" -maxdepth 2 -name CMakeLists.txt -path '*qhull*' | head -1 | xargs dirname)"
[ -n "$QSRC" ] || { echo "[qhull] ERROR: no Qhull CMakeLists" >&2; exit 1; }
cmake -S "$QSRC" -B "qhull-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON \
    -DBUILD_TESTING=OFF
cmake --build "qhull-build" -j"$N_CORES"
cmake --install "qhull-build"

find "$STAGE_ROOT" \( -name 'QhullConfig.cmake' -o -name 'qhull-config.cmake' \) | grep -q . \
    || { echo "[qhull] ERROR: no Qhull config staged" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libqhull*.a' | grep -q . \
    || { echo "[qhull] ERROR: no qhull static libs staged" >&2; exit 1; }
echo "[qhull] done -> $STAGE_ROOT"
