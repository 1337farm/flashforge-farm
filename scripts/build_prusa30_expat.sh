#!/usr/bin/env bash
# scripts/build_prusa30_expat.sh — expat R_2_8_2 for the Android NDK.
#
# Upstream resolves expat via its own cmake/modules/FindEXPAT.cmake, which
# first tries find_package(EXPAT CONFIG): a `cmake --install` prefix tree
# provides expat-config.cmake with the expat::expat target, so stage exactly
# that at engine/prusa30/jniImports/expat and export EXPAT_DIR at configure
# time. Static only (SLIC3R_STATIC direction).
#
# Pin (exact, from upstream deps/+EXPAT/EXPAT.cmake):
#   https://github.com/libexpat/libexpat/releases/download/R_2_8_2/expat-2.8.2.tar.gz
#   SHA256=ef7d1994f533c9e7343d6c19f31064fc8ebbcbcaa144be3812b4f43052a05f4c
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    install prefix (default $(pwd)/engine/prusa30/jniImports/expat)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

EXPAT_VER="2.8.2"
EXPAT_URL="https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VER//./_}/expat-${EXPAT_VER}.tar.gz"
EXPAT_SHA="ef7d1994f533c9e7343d6c19f31064fc8ebbcbcaa144be3812b4f43052a05f4c"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[expat] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/expat}"

echo "======================================================================="
echo " [expat] Building expat R_${EXPAT_VER//./_} ($ABI, api $API_LEVEL, -j$N_CORES, static)"
echo " [expat] NDK: $NDK"
echo " [expat] stage: $STAGE_ROOT"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT"
cd "$WORK_DIR"
if [ ! -f "expat-$EXPAT_VER.tar.gz" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 \
        --retry 2 --retry-all-errors -o "expat-$EXPAT_VER.tar.gz" "$EXPAT_URL"
fi
echo "$EXPAT_SHA  expat-$EXPAT_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[expat] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "expat-$EXPAT_VER" "expat-build"
mkdir -p "expat-$EXPAT_VER"
tar xzf "expat-$EXPAT_VER.tar.gz" -C "expat-$EXPAT_VER"
SRC="$(find "expat-$EXPAT_VER" -maxdepth 2 -name CMakeLists.txt -path '*expat*' | head -1 | xargs dirname)"
[ -n "$SRC" ] || SRC="$(find "expat-$EXPAT_VER" -maxdepth 1 -mindepth 1 -type d | head -1)"
[ -n "$SRC" ] || { echo "[expat] ERROR: no CMakeLists in tarball" >&2; exit 1; }
echo "[expat] source: $SRC"

cmake -S "$SRC" -B "expat-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$STAGE_ROOT" \
    -DBUILD_SHARED_LIBS=OFF \
    -DEXPAT_BUILD_TESTS=OFF \
    -DEXPAT_BUILD_EXAMPLES=OFF \
    -DEXPAT_BUILD_TOOLS=OFF \
    -DEXPAT_BUILD_DOCS=OFF
cmake --build "expat-build" -j"$N_CORES"
cmake --install "expat-build"

# Normalize for FindEXPAT CONFIG mode ($EXPAT_DIR with expat-config.cmake):
# some GNUInstallDirs layouts nest the config under lib/<triplet>/cmake.
mkdir -p "$STAGE_ROOT/lib/cmake/expat"
CFG="$(find "$STAGE_ROOT" -name 'expat-config.cmake' | head -1)"
[ -n "$CFG" ] || { echo "[expat] ERROR: expat-config.cmake missing" >&2; exit 1; }
cp "$CFG" "$STAGE_ROOT/lib/cmake/expat/" 2>/dev/null || true
# The -config file references its sibling *-targets files by relative name,
# so copy those alongside.
CFGDIR="$(dirname "$CFG")"
for f in "$CFGDIR"/expat-*-targets*.cmake "$CFGDIR"/expat-config-version.cmake; do
    [ -f "$f" ] && cp "$f" "$STAGE_ROOT/lib/cmake/expat/" 2>/dev/null || true
done
test -f "$STAGE_ROOT/include/expat.h" || { echo "[expat] ERROR: expat.h missing" >&2; exit 1; }
find "$STAGE_ROOT" -name 'libexpat.a' | grep -q . || { echo "[expat] ERROR: no libexpat.a staged" >&2; exit 1; }
echo "[expat] done -> $STAGE_ROOT ($(find "$STAGE_ROOT" -name 'libexpat.a' | head -1))"
