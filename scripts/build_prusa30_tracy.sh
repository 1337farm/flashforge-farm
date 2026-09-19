#!/usr/bin/env bash
# scripts/build_prusa30_tracy.sh — Tracy v0.13.1 SOURCES for the Android NDK.
#
# Upstream pins Tracy v0.13.1 via deps/+Tracy (add_cmake_project,
# -DTRACY_ENABLE=ON) and consumes it with find_package(Tracy) +
# slic3r_add_tracy(), which needs target Tracy::TracyClient with an
# INTERFACE_INCLUDE_DIRECTORIES property — even when profiling is OFF
# (SLIC3R_ENABLE_PROFILING defaults OFF; only the include dir is used then).
# TracyClient.cpp is compiled into the lib only with TRACY_ENABLE=ON, which
# the headless Android build does not want, so do NOT build a Tracy lib:
# stage the pristine sources (public/ tree with Tracy.hpp + TracyClient.cpp)
# and let a 0004 patch provide the imported-target fallback when the CONFIG
# package does not cross-resolve. Source staging needs no NDK at all, but
# keep the env surface identical to the sibling scripts.
#
# Pin (exact, from upstream deps/+Tracy/Tracy.cmake):
#   https://github.com/wolfpld/tracy/archive/refs/tags/v0.13.1.tar.gz
#   SHA256=d4efc50ebcb0bfcfdbba148995aeb75044c0d80f5d91223aebfaa8fa9e563d2b
#
# Env inputs (all optional; defaults shown):
#   WORK_DIR      build scratch dir (default /tmp/build_prusa30_deps)
#   STAGE_ROOT    stage root (default $(pwd)/engine/prusa30/jniImports/pngfmt;
#                 sources land in $STAGE_ROOT/tracy/)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TRACY_VER="0.13.1"
TRACY_URL="https://github.com/wolfpld/tracy/archive/refs/tags/v${TRACY_VER}.tar.gz"
TRACY_SHA="d4efc50ebcb0bfcfdbba148995aeb75044c0d80f5d91223aebfaa8fa9e563d2b"

WORK_DIR="${WORK_DIR:-/tmp/build_prusa30_deps}"
STAGE_ROOT="${STAGE_ROOT:-$ROOT/engine/prusa30/jniImports/pngfmt}"

echo "======================================================================="
echo " [tracy] Staging Tracy v$TRACY_VER sources (no compile)"
echo " [tracy] stage: $STAGE_ROOT/tracy"
echo "======================================================================="

mkdir -p "$WORK_DIR" "$STAGE_ROOT/tracy"
cd "$WORK_DIR"
[ -f "tracy-$TRACY_VER.tar.gz" ] || curl -fsSL --connect-timeout 20 --max-time 300 \
    --retry 2 --retry-all-errors -o "tracy-$TRACY_VER.tar.gz" "$TRACY_URL"
echo "$TRACY_SHA  tracy-$TRACY_VER.tar.gz" | sha256sum -c --status - \
    || { echo "[tracy] ERROR: sha256 mismatch" >&2; exit 1; }

rm -rf "tracy-$TRACY_VER"
mkdir -p "tracy-$TRACY_VER" && tar xzf "tracy-$TRACY_VER.tar.gz" -C "tracy-$TRACY_VER"
# v0.13.1 layout: public/TracyClient.cpp + public/tracy/Tracy.hpp (+common/).
# Anchor on the uniquely-placed TracyClient.cpp: a Tracy.hpp search lands in
# the nested tracy/ subdir and the staged tree misses the .cpp. Dump the
# layout on miss instead of dying inside `xargs dirname` with a cryptic
# operand error.
TCPP="$(find "tracy-$TRACY_VER" -maxdepth 4 -name TracyClient.cpp | head -1)"
if [ -z "$TCPP" ]; then
    echo "[tracy] ERROR: no TracyClient.cpp in tarball; top-level layout:" >&2
    find "tracy-$TRACY_VER" -maxdepth 3 | head -20 >&2
    exit 1
fi
TSRC="$(dirname "$TCPP")"
# Stage the public/ tree verbatim.
cp -r "$TSRC"/. "$STAGE_ROOT/tracy/"

test -f "$STAGE_ROOT/tracy/tracy/Tracy.hpp" || { echo "[tracy] ERROR: tracy/Tracy.hpp missing" >&2; exit 1; }
test -f "$STAGE_ROOT/tracy/TracyClient.cpp" || { echo "[tracy] ERROR: TracyClient.cpp missing" >&2; exit 1; }
# Profiling stays OFF (SLIC3R_ENABLE_PROFILING default): nothing is compiled,
# but find_package(Tracy) + slic3r_add_tracy() need target Tracy::TracyClient
# with an INTERFACE_INCLUDE_DIRECTORIES property. Provide a minimal CONFIG
# package pointing at the staged sources (relative path: portable across
# runners, unlike absolute stage paths baked at build time).
mkdir -p "$STAGE_ROOT/lib/cmake/Tracy"
cat > "$STAGE_ROOT/lib/cmake/Tracy/TracyConfig.cmake" <<'EOF'
# Staged Tracy sources (profiling-OFF build: headers only, no lib).
if(NOT TARGET Tracy::TracyClient)
  add_library(Tracy::TracyClient INTERFACE IMPORTED)
  set_target_properties(Tracy::TracyClient PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../tracy")
endif()
EOF
test -f "$STAGE_ROOT/lib/cmake/Tracy/TracyConfig.cmake" || { echo "[tracy] ERROR: config write failed" >&2; exit 1; }
echo "[tracy] done -> $STAGE_ROOT/tracy"
