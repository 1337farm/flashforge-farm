#!/usr/bin/env bash
# scripts/build_prusa30_nlopt.sh — NLopt v2.5.0 for the Android NDK.
#
# Upstream resolves NLopt via its own cmake/modules/FindNLopt.cmake, which
# reads the NLOPT *environment* variable: $NLOPT/include must hold nlopt.hpp
# and $NLOPT/lib must hold the C++ library (libnlopt_cxx). We therefore stage
# a real `cmake --install` prefix tree at engine/prusa30/jniImports/nlopt
# (normalized so both land directly under include/ and lib/) and export
# NLOPT at configure time. Static only (SLIC3R_STATIC direction).
#
# Pin (exact, from upstream deps/+NLopt/NLopt.cmake):
#   https://github.com/stevengj/nlopt/archive/v2.5.0.tar.gz
#   SHA256=c6dd7a5701fff8ad5ebb45a3dc8e757e61d52658de3918e38bab233e7fd3b4ae
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/nlopt)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

NLOPT_VER="2.5.0"
NLOPT_URL="https://github.com/stevengj/nlopt/archive/v${NLOPT_VER}.tar.gz"
NLOPT_SHA="c6dd7a5701fff8ad5ebb45a3dc8e757e61d52658de3918e38bab233e7fd3b4ae"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[nlopt] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/nlopt}"

echo "======================================================================="
echo " [NLopt] Building NLopt v$NLOPT_VER ($ABI, api $API_LEVEL, -j$N_CORES, static)"
echo " [NLopt] NDK: $NDK"
echo " [NLopt] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "nlopt-$NLOPT_VER.tar.gz" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "nlopt-$NLOPT_VER.tar.gz" "$NLOPT_URL"
fi
echo "$NLOPT_SHA  nlopt-$NLOPT_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[nlopt] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "nlopt-$NLOPT_VER" "nlopt-build"
mkdir -p "nlopt-$NLOPT_VER"
tar xzf "nlopt-$NLOPT_VER.tar.gz" -C "nlopt-$NLOPT_VER"
SRC="$(find "nlopt-$NLOPT_VER" -maxdepth 2 -name CMakeLists.txt | head -1 | xargs dirname)"
[ -n "$SRC" ] || { echo "[nlopt] ERROR: no CMakeLists in tarball" >&2; exit 1; }
echo "[nlopt] source: $SRC"

cmake -S "$SRC" -B "nlopt-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DNLOPT_CXX=ON \
    -DNLOPT_TESTS=OFF \
    -DNLOPT_GUI=OFF \
    -DNLOPT_PYTHON=OFF \
    -DNLOPT_OCTAVE=OFF \
    -DNLOPT_MATLAB=OFF \
    -DNLOPT_SWIG=OFF
cmake --build "nlopt-build" -j"$N_CORES"
cmake --install "nlopt-build"

# Normalize for FindNLopt ($NLOPT/include/nlopt.hpp + $NLOPT/lib/libnlopt_cxx.*):
# some GNUInstallDirs layouts nest deeper (lib/<triplet>).
mkdir -p "$STAGE_ROOT/include" "$STAGE_ROOT/lib"
HDR="$(find "$STAGE_ROOT" -name nlopt.hpp | head -1)"
[ -n "$HDR" ] || { echo "[nlopt] ERROR: nlopt.hpp missing" >&2; exit 1; }
cp "$HDR" "$STAGE_ROOT/include/" 2>/dev/null || true
LIB="$(find "$STAGE_ROOT" -name 'libnlopt_cxx.*' | head -1)"
[ -n "$LIB" ] || { echo "[nlopt] ERROR: libnlopt_cxx missing (NLOPT_CXX on?)" >&2; exit 1; }
cp "$LIB" "$STAGE_ROOT/lib/" 2>/dev/null || true
test -f "$STAGE_ROOT/include/nlopt.hpp" || { echo "[nlopt] ERROR: include normalize failed" >&2; exit 1; }
ls "$STAGE_ROOT/lib"/libnlopt_cxx.* >/dev/null || { echo "[nlopt] ERROR: lib normalize failed" >&2; exit 1; }
echo "[nlopt] done -> $STAGE_ROOT ($(ls "$STAGE_ROOT/lib"/libnlopt_cxx.*))"
