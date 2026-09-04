#!/usr/bin/env bash
# scripts/fetch-native-deps.sh — stage the native dependencies AND the OrcaSlicer
# engine for a LOCAL app build.
#
# Fast path (default): download the CI-published rolling per-dep releases
# (dep-tbb-latest, dep-boost-latest, dep-occt-latest, dep-gmp-latest) plus
# engine-latest, with sha256 manifest verification, and stage them into
# engine/. Slow path: compile everything from source via the in-repo scripts
# (matches the CI workflow, native-engine-build.yml).
# There is no separate engine/deps release repo: releases live on this repo.
# Publishing is per-step in CI, so a dep release is usable even when a later
# pipeline step failed.
#
# Usage:
#   scripts/fetch-native-deps.sh                    # releases first, else build
#   scripts/fetch-native-deps.sh --deps-only        # deps only (either path)
#   scripts/fetch-native-deps.sh --engine           # engine only (either path)
#   scripts/fetch-native-deps.sh --build-from-source # force compile, no download
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

MODE="${1:-all}"
FORCE_SOURCE=0
if [ "$MODE" = "--build-from-source" ]; then
  FORCE_SOURCE=1
  MODE="all"
fi

verify_manifest() {
  local dir="$1" manifest="$2" file="$3" want got
  want="$(python3 -c "import json;print(json.load(open('$dir/$manifest'))['files']['$file'])" 2>/dev/null || true)"
  [ -n "$want" ] || { echo "[fetch] ERROR: no sha for $file in $manifest" >&2; return 1; }
  got="$(sha256sum "$dir/$file" | cut -d' ' -f1)"
  [ "$want" = "$got" ] || { echo "[fetch] ERROR: sha mismatch for $file" >&2; return 1; }
}

# --- fast path: CI releases -------------------------------------------------
DEPS_OK=0
ENGINE_OK=0
if [ "$FORCE_SOURCE" -eq 0 ] && command -v gh >/dev/null 2>&1; then
  DL="$(mktemp -d)"
  trap 'rm -rf "$DL"' EXIT
  if [ "$MODE" != "--engine" ]; then
    DEPS_OK=1
    for DEP in tbb boost occt gmp; do
      TARBALL="$DEP-deps.tar.gz"
      MANIFEST="$DEP-manifest.json"
      if gh release download "dep-$DEP-latest" \
           --pattern "$TARBALL" --pattern "$MANIFEST" \
           --dir "$DL" --clobber >/dev/null 2>&1 \
         && verify_manifest "$DL" "$MANIFEST" "$TARBALL"; then
        echo "[fetch] dep-$DEP release verified; extracting"
        tar -xzf "$DL/$TARBALL"
      else
        echo "[fetch] dep-$DEP release unavailable/invalid"
        DEPS_OK=0
      fi
    done
    if [ "$DEPS_OK" = "0" ]; then
      echo "[fetch] one or more dep releases missing; will build deps from source"
    fi
  fi
  if [ "$MODE" != "--deps-only" ] \
     && gh release download engine-latest \
          --pattern 'libslic3r.so' --pattern 'engine-manifest.json' \
          --dir "$DL" --clobber >/dev/null 2>&1 \
     && verify_manifest "$DL" engine-manifest.json libslic3r.so; then
    echo "[fetch] engine release verified; staging libslic3r.so"
    mkdir -p "engine/output/$ABI"
    cp "$DL/libslic3r.so" "engine/output/$ABI/libslic3r.so"
    ENGINE_OK=1
  else
    echo "[fetch] engine release unavailable/invalid; will build from source"
    ENGINE_OK=0
  fi
  rm -rf "$DL"
  trap - EXIT
  if { [ "$MODE" = "--deps-only" ] && [ "$DEPS_OK" = "1" ]; } \
     || { [ "$MODE" = "--engine" ] && [ "$ENGINE_OK" = "1" ]; } \
     || { [ "$MODE" = "all" ] && [ "$DEPS_OK" = "1" ] && [ "$ENGINE_OK" = "1" ]; }; then
    echo "[fetch] done (from releases)."
    exit 0
  fi
  echo "[fetch] falling back to source build for the missing parts"
fi

command -v cmake >/dev/null 2>&1 || { echo "[fetch] ERROR: cmake required (install build essentials first)" >&2; exit 1; }

if [ "$DEPS_OK" = "1" ]; then
  echo "[fetch] deps already staged from release; skipping source build"
else
  echo "[fetch] Building native deps from source (ABI=$ABI, NDK=$ANDROID_NDK_ROOT) ..."
  chmod +x scripts/build_all_deps_android.sh
  ./scripts/build_all_deps_android.sh
fi

if [ "$MODE" = "--deps-only" ]; then
  echo "[fetch] deps staged. Skipping engine (--deps-only)."
  exit 0
fi

if [ "$ENGINE_OK" = "1" ]; then
  echo "[fetch] done. libslic3r.so at engine/output/$ABI/libslic3r.so (from release)."
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