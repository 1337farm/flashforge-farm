#!/usr/bin/env bash
# scripts/build_prusa30_ryml.sh — rapidyaml 0.10.0 (+vendored c4core, static)
# for the Android NDK.
#
# Upstream pins ryml v0.10.0 via deps/+ryml (add_cmake_project,
# -DCMAKE_POLICY_VERSION_MINIMUM=3.5) and consumes it with
# find_package(ryml) -> ryml::ryml. SLIC3R_YAML_RYML selects
# YamlAdapterRyml, whose c4::yml::Tree/parse symbols need the COMPILED
# library — the staged headers (stage_prusa30_yaml.sh) cannot provide
# them. Build the ryml target (vendored c4core builds alongside) and
# stage both archives; link order is ryml -> c4core (c4core resolves
# ryml's references). Some builds fold c4core into libryml.a — then the
# libc4core.a find comes up empty and the archive is already complete.
#
# Pin (exact, from upstream deps/+ryml/ryml.cmake):
#   https://github.com/biojppm/rapidyaml/releases/download/v0.10.0/rapidyaml-0.10.0-src.tgz
#   SHA256=54eb1050789809a26c780f80857b7668a5b3123405d6514a65d733e4292c690b
#
# Env inputs (all optional; defaults shown):
#   NDK           NDK root (required; falls back to $ANDROID_NDK_ROOT, then
#                 $ANDROID_SDK_ROOT/ndk/23.1.7779620)
#   ABI           target ABI (default arm64-v8a)
#   API_LEVEL     Android API level (default 23)
#   N_CORES       parallelism (default nproc)
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    stage prefix (default $(pwd)/engine/prusa30/jniImports/ryml;
#                 archives land in $STAGE_ROOT/lib/)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RYML_VER="0.10.0"
RYML_URL="https://github.com/biojppm/rapidyaml/releases/download/v${RYML_VER}/rapidyaml-${RYML_VER}-src.tgz"
RYML_SHA="54eb1050789809a26c780f80857b7668a5b3123405d6514a65d733e4292c690b"

NDK="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}/ndk/23.1.7779620}}"
if [ ! -d "$NDK" ]; then
    echo "[ryml] ERROR: NDK not found at $NDK" >&2
    exit 1
fi
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-23}"
N_CORES="${N_CORES:-$(nproc)}"
WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/ryml}"

TC="$NDK/build/cmake/android.toolchain.cmake"
echo "======================================================================="
echo " [ryml] Building rapidyaml $RYML_VER ($ABI, api $API_LEVEL, static)"
echo " [ryml] stage: $STAGE_ROOT/lib"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT/lib"
cd "$WORK_DIR"
[ -f "rapidyaml-$RYML_VER-src.tgz" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "rapidyaml-$RYML_VER-src.tgz" "$RYML_URL"
echo "$RYML_SHA  rapidyaml-$RYML_VER-src.tgz" | sha256sum -c --status \
    || { echo "[ryml] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "rapidyaml-$RYML_VER" "ryml-build"
mkdir -p "rapidyaml-$RYML_VER" && tar xzf "rapidyaml-$RYML_VER-src.tgz" -C "rapidyaml-$RYML_VER"
RSRC="$(find "rapidyaml-$RYML_VER" -maxdepth 2 -name CMakeLists.txt -path '*rapidyaml*' | head -1 | xargs dirname)"
[ -n "$RSRC" ] || { echo "[ryml] ERROR: no ryml CMakeLists" >&2; exit 1; }
cmake -S "$RSRC" -B "ryml-build" \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBUILD_SHARED_LIBS=OFF -DRYML_BUILD_TESTS=OFF -DRYML_BUILD_TOOLS=OFF \
    -DRYML_BUILD_SAMPLES=OFF -DRYML_BUILD_BENCHMARKS=OFF
cmake --build "ryml-build" --target ryml -j"$N_CORES"

RYML_LIB="$(find ryml-build -name 'libryml.a' | head -1)"
[ -n "$RYML_LIB" ] || { echo "[ryml] ERROR: no libryml.a in build tree" >&2; exit 1; }
cp "$RYML_LIB" "$STAGE_ROOT/lib/"
C4_LIB="$(find ryml-build -name 'libc4core.a' | head -1)"
[ -n "$C4_LIB" ] && cp "$C4_LIB" "$STAGE_ROOT/lib/"
test -f "$STAGE_ROOT/lib/libryml.a" || { echo "[ryml] ERROR: staged libryml.a missing" >&2; exit 1; }
echo "[ryml] done -> $STAGE_ROOT/lib"
