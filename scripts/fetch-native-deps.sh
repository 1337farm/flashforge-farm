#!/usr/bin/env bash
# scripts/fetch-native-deps.sh — stage the native dependencies AND the OrcaSlicer
# engine for a LOCAL app build, building them entirely FROM SOURCE in this repo.
#
# There is no separate engine/deps release repo: the heavy C++ libraries
# (Boost, oneTBB, OCCT, GMP/MPFR) and libslic3r.so are compiled from source via
# the in-repo scripts. This matches the CI workflow (native-engine-build.yml).
#
# Usage:
#   scripts/fetch-native-deps.sh              # build all deps + engine from source
#   scripts/fetch-native-deps.sh --deps-only  # build native deps only
#   scripts/fetch-native-deps.sh --engine     # build libslic3r.so only
#
# Env inputs:
#   ANDROID_SDK_ROOT     Android SDK root (default /usr/local/lib/android/sdk)
#   ANDROID_NDK_ROOT     NDK root (default $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI                  target ABI (default arm64-v8a)
#   N_CORES              parallel build jobs (default $(nproc))
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"
cd "$ROOT"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
export ABI="${ABI:-arm64-v8a}"

command -v cmake >/dev/null 2>&1 || { echo "[fetch] ERROR: cmake required (install build essentials first)" >&2; exit 1; }

MODE="${1:-all}"

echo "[fetch] Building native deps from source (ABI=$ABI, NDK=$ANDROID_NDK_ROOT) ..."
chmod +x scripts/build_all_deps_android.sh
./scripts/build_all_deps_android.sh

if [ "$MODE" = "--deps-only" ]; then
  echo "[fetch] deps built. Skipping engine (--deps-only)."
  exit 0
fi

echo "[fetch] Building engine (libslic3r.so) from source ..."
command -v cmake >/dev/null 2>&1 || { echo "[fetch] ERROR: cmake required" >&2; exit 1; }
[ -n "${ANDROID_NDK_ROOT:-}" ] || { echo "[fetch] ERROR: ANDROID_NDK_ROOT not set" >&2; exit 1; }
cmake -S engine -B engine/build \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_STL=c++_shared \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release \
  "-DSLIC3R_VERSION=\"0.4.6\"" \
  "-DSLIC3R_BUILD_ID=\"4\""
cmake --build engine/build --target slic3r -j"$(nproc)"

SO="$(find engine/build -name 'libslic3r.so' 2>/dev/null | head -1)"
[ -n "$SO" ] || { echo "[fetch] ERROR: libslic3r.so not built" >&2; exit 1; }
mkdir -p "engine/output/$ABI"
cp "$SO" "engine/output/$ABI/libslic3r.so"
echo "[fetch] done. libslic3r.so at engine/output/$ABI/libslic3r.so"