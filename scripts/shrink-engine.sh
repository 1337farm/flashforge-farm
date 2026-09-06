#!/usr/bin/env bash
# scripts/shrink-engine.sh — export-shrink the engine .so to everything the app
# bridge actually uses, so --gc-sections finally drops the dead weight.
#
# WHY: libslic3r.so is the ONLY native engine in the APK and carries the entire
# engine source plus the statically-merged OCCT/Boost/TBB archives. By default
# it exports every defined symbol (~10k). --gc-sections treats every exported
# symbol as a root, so it can only shed code the engine's declarations make
# clearly unreferenced — in practice the OCCT/Boost/TBB bloat the app never
# calls stays in the .so. libslic3r.so has NO JNI of its own: the app's JNI
# bridge libfarm.so (compiled from app/src/main/jni + engine render glue) is
# the ONLY consumer of its exports.
#
# So we shrink in two phases around that fact:
#   Phase A  - stage the default-export libslic3r.so into engine/output, then
#              compile libfarm.so exactly like the APK build would.
#   Capture  - nm the UNDEFINED dynamic symbols of libfarm.so. That is the
#              exact set of engine + boost/tbb symbols the bridge needs at
#              runtime (API + RTTI + vtables), nothing more.
#   Phase B  - re-link the SAME engine objects/archives with a version script
#              that exports only that set and forces everything else `local:*`.
#              Every unexported, unreferenced section becomes a --gc-sections
#              candidate, so OCCT/Boost/TBB/engine code the app never touches
#              is dropped at link time. Compile flags are untouched, so only
#              the link runs again (all object files are reused).
#
# Empirically verified (lld 21 + aarch64-linux-android): an anonymous version
# script `{ global: <set>; local: *; };` emits NO .gnu.version* sections
# (Bionic-safe) and gc-sections drops the unexported functions entirely.
#
# Usage: scripts/shrink-engine.sh [engine-build-dir] [app-capture-dir] [abi]
#   engine-build-dir  CMake build dir of the engine (must contain a Phase A
#                     libslic3r.so with default exports). Default engine/build.
#   app-capture-dir   scratch CMake dir that compiles libfarm against Phase A.
#                     Default app/build/export-capture.
#   abi               Target ABI. Default arm64-v8a (only ABI shipped).
# Env: ANDROID_NDK_ROOT (default $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#      SLIC3R_VERSION (default 0.4.6, must match app/build.gradle)
#      SLIC3R_BUILD_ID (default 4,    must match app/build.gradle)
#      NM (default nm; llvm-nm works too)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${1:-$ROOT/engine/build}"
CAPTURE_DIR="${2:-$ROOT/app/build/export-capture}"
ABI="${3:-arm64-v8a}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
SLIC3R_VERSION="${SLIC3R_VERSION:-0.4.6}"
SLIC3R_BUILD_ID="${SLIC3R_BUILD_ID:-4}"
NM="${NM:-nm}"

err() { echo "[shrink-engine] ERROR: $*" >&2; exit 1; }

[ -x "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ] \
    || err "NDK toolchain file not found under $ANDROID_NDK_ROOT"

SO_A="$(find "$BUILD_DIR" -name libslic3r.so -type f | head -1)"
[ -n "$SO_A" ] || err "no libslic3r.so in $BUILD_DIR — build the engine first (default exports)"
[ -f "$ROOT/app/CMakeLists.txt" ] || err "app/CMakeLists.txt not found at $ROOT"

echo "[shrink-engine] Phase A .so: $SO_A ($(stat -c%s "$SO_A") bytes)"

# --- Phase A: stage for the bridge; the capture build links libfarm against it
mkdir -p "$ROOT/engine/output/$ABI"
cp "$SO_A" "$ROOT/engine/output/$ABI/libslic3r.so"

# --- Capture: compile libfarm.so with the same flags the APK build uses
cmake -S "$ROOT/app" -B "$CAPTURE_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_STL=c++_shared \
    -DANDROID_PLATFORM=android-23 \
    -DCMAKE_BUILD_TYPE=Release \
    "-DSLIC3R_VERSION=\"$SLIC3R_VERSION\"" \
    "-DSLIC3R_BUILD_ID=\"$SLIC3R_BUILD_ID\""
cmake --build "$CAPTURE_DIR" --target farm -j"$(nproc)"

FARM_SO="$(find "$CAPTURE_DIR" -name libfarm.so -type f | head -1)"
[ -n "$FARM_SO" ] || err "capture build produced no libfarm.so"
echo "[shrink-engine] Capture bridge: $FARM_SO"

EXPORT_A="$($NM -D --defined-only "$SO_A" | wc -l | tr -d ' ')"
echo "[shrink-engine] Phase A exported symbols: $EXPORT_A"

# libfarm.so's undefined dynamic symbols = everything it resolves from
# libslic3r.so (and the world it loads). Export exactly this set.
mapfile -t UNDEF < <("$NM" -D --undefined-only "$FARM_SO" \
    | awk '{print $NF}' | sed 's/@.*//' | sed '/^$/d' | sort -u)
[ "${#UNDEF[@]}" -ge 50 ] || err "suspiciously small bridge symbol set (${#UNDEF[@]})"
echo "[shrink-engine] Bridge needs ${#UNDEF[@]} undefined symbols; writing export map"

{
    printf '{\n  global:\n'
    printf '    "%s";\n' "${UNDEF[@]}"
    printf '  local:\n    *;\n};\n'
} > "$ROOT/engine/export.map"

# --- Phase B: re-link the same objects with the version script (link-only)
cmake -S "$ROOT/engine" -B "$BUILD_DIR" "-DSLIC3R_EXPORT_MAP=$ROOT/engine/export.map"
cmake --build "$BUILD_DIR" --target slic3r -j"$(nproc)"

SO_B="$(find "$BUILD_DIR" -name libslic3r.so -type f | head -1)"
[ -n "$SO_B" ] || err "Phase B re-link produced no libslic3r.so"

EXPORT_B="$($NM -D --defined-only "$SO_B" | wc -l | tr -d ' ')"
SZ_B="$(stat -c%s "$SO_B")"
echo "[shrink-engine] Phase B exported symbols: $EXPORT_B ($SZ_B bytes)"

# --- Verify the shrink actually happened and the .so is still a real engine ---
[ "$EXPORT_B" -lt 3000 ] || err "shrink ineffective: still $EXPORT_B exported symbols"
[ "$EXPORT_B" -lt "$((EXPORT_A / 3))" ] || err "shrink ineffective: $EXPORT_A -> $EXPORT_B"
readelf -d "$SO_B" | grep -q "libgmp"  || err "phase B .so lost libgmp NEEDED"
readelf -d "$SO_B" | grep -q "libmpfr" || err "phase B .so lost libmpfr NEEDED"
strings "$SO_B" | grep -q -F 'Orca Slicer' || err "phase B .so lost engine marker"

# The exported set must cover the bridge's whole need set (we built it that
# way, but confirm the link honored it) — including RTTI/vtable/typeinfo
# symbols that farm references across the .so boundary.
for s in "${UNDEF[@]}"; do
    "$NM" -D --defined-only "$SO_B" | grep -q " $s$" \
        || { echo "[shrink-engine] ERROR: '$s' (needed by libfarm) missing from shrunk exports" >&2; exit 1; }
done

# Keep engine/output consistent with the shrunk artifact (the APK path).
cp "$SO_B" "$ROOT/engine/output/$ABI/libslic3r.so"
echo "[shrink-engine] OK: exports $EXPORT_A -> $EXPORT_B; libfarm symbol set fully covered; staged into engine/output/$ABI"