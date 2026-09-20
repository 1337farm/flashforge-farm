#!/bin/bash
# Local object build (NOT a full link): compiles every TU the CI engine build
# compiles, with CI's -I sets/defines, using host clang. Catches compile
# errors (incl. codegen) in minutes; link-completeness via nm afterwards.
# Usage: bash scripts/local_build_o.sh [jobs]
# Output: $SCRATCH/objs/*.o, failures listed at end.
set -u
JOBS="${1:-8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/engine/build/prusaslicer-src"
SCRATCH="${SYNTAX_SCRATCH:-/data/data/com.termux/files/home/.cache/opencode/tmp/stage}"
STUBS="$SCRATCH/stubs"
OBJDIR="$SCRATCH/objs"
mkdir -p "$OBJDIR"
FAIL="$SCRATCH/build_fail.txt"
: > "$FAIL"

COMMON='-c -std=c++20 -w -DNDEBUG -DSLIC3R_VERSION="0.4.6" -DSLIC3R_BUILD_ID="4" -DSPDLOG_FMT_EXTERNAL -DJSON_USE_IMPLICIT_CONVERSIONS=0 -DSLIC3R_YAML_RYML'
CORE_INC="$(cat "$SCRATCH/core_includes.txt")"
SLIC3R_DEFS="-DUSE_TBB -DTBB_USE_CAPTURED_EXCEPTION=0 -DLIBNEST2D_GEOMETRIES_libslic3r -DLIBNEST2D_OPTIMIZER_nlopt -DLIBNEST2D_THREADING_tbb -DLIBNEST2D_STATIC -DENABLE_OPENGL_ES -DSLIC3R_OPENGL_ES -DUSE_CPP11_REGEX"

# TU list mirrors engine/CMakeLists.txt exactly.
{
ls $P/src/libslic3r/src/libslic3r/*.cpp | grep -vE "ArrangeHelper|GCodeSender"
for m in slic3r-domain slic3r-shared slic3r-base slic3r-biz-algorithms slic3r-biz-arrange slic3r-biz-cgal-algorithms slic3r-biz-parser slic3r-gcode-reader libpgcode; do
  find $P/src/$m/src -name "*.cpp"
done
# Exclusion FILTER mirrors engine/CMakeLists.txt exactly (single source of
# truth; CMake string escaping collapsed for grep -E).
FILTER_RX="$(grep -o 'EXCLUDE REGEX "[^"]*"' "$ROOT/engine/CMakeLists.txt" | head -1 | cut -d'"' -f2 | sed 's/\\\\/\\/g')"
} | grep -vE "$FILTER_RX" > "$SCRATCH/build_tus_core.txt"
# Vendored explicit sources: parse the add_library(slic3r ...) block.
awk '/^add_library\(slic3r$/{f=1;next} f&&/^\\)$/{exit} f' "$ROOT/engine/CMakeLists.txt" \
  | grep -oE '(src/main/jni/[^ )]+|\$\{PRUSA_SRC\}/[^ )]+|\$\{CMAKE_CURRENT_SOURCE_DIR\}/[^ )]+)' \
  | sed -E 's/\$\{PRUSA_SRC\}/engine\/build\/prusaslicer-src/; s/\$\{CMAKE_CURRENT_SOURCE_DIR\}/engine/' \
  | grep -E '\.(cpp|c|cc)$' | sort -u > "$SCRATCH/build_tus_vendored.txt"

compile_one() {
  f="$1"; mode="$2"
  rel="${f#$ROOT/}"
  obj="$OBJDIR/$(echo "$rel" | tr '/' '_').o"
  [ -f "$obj" ] && return 0
  extra=""; defs=""
  case "$f" in *CustomParametersHandling.cpp) extra="-fno-openmp" ;; esac
  [ "$mode" = "slic3r" ] && defs="$SLIC3R_DEFS"
  if ! clang++ $COMMON -I$STUBS $CORE_INC $defs $extra "$f" -o "$obj" 2>"$SCRATCH/o_$(echo "$f" | md5sum | cut -c1-8).txt"; then
    echo "FAIL: $f" >> "$FAIL"
  fi
}
export -f compile_one
export COMMON CORE_INC SLIC3R_DEFS SCRATCH OBJDIR ROOT PATH
{ while read -r f; do printf '%s\0' "core:$f"; done < "$SCRATCH/build_tus_core.txt";
  while read -r f; do printf '%s\0' "slic3r:$f"; done < "$SCRATCH/build_tus_vendored.txt"; } \
| xargs -0 -P "$JOBS" -I{} bash -c 'pair="$1"; compile_one "${pair#*:}" "${pair%%:*}"' _ {}
echo "=== object build done ==="
wc -l < "$FAIL" | xargs echo "failed TUs:"
sort -u "$FAIL" | head -30
ls "$OBJDIR"/*.o 2>/dev/null | wc -l | xargs echo "objects built:"
