#!/bin/bash
# Local parity syntax sweep (NOT for building): runs host clang++ -fsyntax-only
# over every TU the CI engine build compiles, with CI's -I sets + defines.
# Catches missing headers / type errors in seconds instead of CI rounds.
# Known divergences from CI: no -fopenmp (host lacks libomp; pragmas ignored),
# host clang 21 (newer than NDK r26 clang 19), stubs for NDK-only headers.
# Usage: bash scripts/local_syntax_sweep.sh [jobs]
set -u
JOBS="${1:-8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/engine/build/prusaslicer-src"
SCRATCH="${SYNTAX_SCRATCH:-/data/data/com.termux/files/home/.cache/opencode/tmp/stage}"
STUBS="$SCRATCH/stubs"
OUT="$SCRATCH/sweep.txt"
: > "$OUT"

COMMON="-fsyntax-only -std=c++20 -w -DNDEBUG -DSLIC3R_VERSION=\"0.4.6\" -DSLIC3R_BUILD_ID=\"4\""
CORE_INC="-I$STUBS -I$SCRATCH/prusagen -I$ROOT/engine/prusa30 \
 -I$P/src/libslic3r/include -I$P/src/libslic3r/src \
 -I$P/src/libseqarrange/include \
 -I$P/bundled_deps -I$P/bundled_deps/admesh -I$P/bundled_deps/clipper/include \
 -I$P/bundled_deps/semver -I$P/bundled_deps/localesutils -I$P/bundled_deps/ankerl \
 -I$P/bundled_deps/stb_image -I$P/bundled_deps/stb_dxt -I$P/bundled_deps/int128 \
 -I$P/src/slic3r-domain/include -I$P/src/slic3r-base/include \
 -I$P/src/slic3r-biz-algorithms/include -I$P/src/slic3r-biz-arrange/include \
 -I$P/src/slic3r-shared/include -I$P/src/slic3r-biz-cgal-algorithms/include \
 -I$P/src/slic3r-biz-parser/include -I$P/src/slic3r-gcode-reader/include \
 -I$P/src/slic3r-jthread/include -I$P/src/libpgcode/include \
 -I$P/src/slic3r-domain/src -I$P/src/slic3r-shared/src -I$P/src/slic3r-base/src \
 -I$P/src/slic3r-biz-algorithms/src -I$P/src/slic3r-biz-arrange/src \
 -I$P/src/slic3r-biz-cgal-algorithms/src -I$P/src/slic3r-biz-parser/src \
 -I$P/src/slic3r-gcode-reader/src -I$P/src/libpgcode/src \
 -I$P/bundled_deps/slic3r-domain-types/include \
 -I$ROOT/engine/prusa30/jniImports/tbb/include \
 -I$ROOT/engine/src/main/jniImports/oneTBB/include \
 -I$ROOT/engine/src/main/jniImports/boost/include \
 -I$ROOT/engine/build/headers/eigen/include \
 -I$ROOT/engine/build/headers/spdlog/include \
 -I$ROOT/engine/build/headers/fmt/include \
 -I$ROOT/engine/build/headers/cereal/include \
 -I$ROOT/engine/build/headers/nlohmann_json/include \
 -I$ROOT/engine/build/headers/sol2/include \
 -I$ROOT/engine/build/headers/expected/include \
 -I$ROOT/engine/build/headers/magic_enum/include \
 -I$ROOT/engine/prusa30/jniImports/assert/include \
 -I$ROOT/engine/prusa30/jniImports/nanosvg/include \
 -I$ROOT/engine/src/main/jniImports/gmp/include/arm64-v8a \
 -I$ROOT/engine/src/main/jni/libnest2d/include \
 -I$ROOT/engine/src/main/jni/LibBGCode \
 -I$ROOT/engine/src/main/jni/qhull \
 -I$ROOT/engine/src/main/jni/libigl \
 -I$ROOT/engine/prusa30/jniImports/mixer/include \
 -I$ROOT/engine/src/main/jni/miniz \
 -I$ROOT/engine/src/main/jni/libpng \
 -I$ROOT/engine/src/main/jni/nlopt/api \
 -I$ROOT/engine/src/main/jni/expat \
 -I$ROOT/engine/src/main/jni/fast_float \
 -I$ROOT/engine/src/main/jni/glu-libtess/include \
 -I$ROOT/engine/src/main/jni/semver \
 -I$ROOT/engine/src/main/jni"

SLIC3R_DEFS="-DUSE_TBB -DTBB_USE_CAPTURED_EXCEPTION=0 -DLIBNEST2D_GEOMETRIES_libslic3r -DLIBNEST2D_OPTIMIZER_nlopt -DLIBNEST2D_THREADING_tbb -DLIBNEST2D_STATIC -DENABLE_OPENGL_ES -DSLIC3R_OPENGL_ES -DUSE_CPP11_REGEX"

# TU list mirrors engine/CMakeLists.txt: legacy glob minus dead TUs, module
# globs minus the FILTER, vendored explicit sources, admesh + localesutils.
{
ls $P/src/libslic3r/src/libslic3r/*.cpp | grep -vE "ArrangeHelper|GCodeSender"
for m in slic3r-domain slic3r-shared slic3r-base slic3r-biz-algorithms slic3r-biz-arrange slic3r-biz-cgal-algorithms slic3r-biz-parser slic3r-gcode-reader libpgcode; do
  find $P/src/$m/src -name "*.cpp"
done
} | grep -vE "Win32|win32|Mac\.cpp|Slic3r/App/|sequential_decimator|HttpCurl\\.cpp|SHA256Linux\\.cpp|AppInstanceMessageHandlerLinux\\.cpp|AppInstanceMessageHandlerFactoryLinux\\.cpp|RemovableDriveMonitorLinux\\.cpp|RemovableDriveServiceLinux\\.cpp|TryCatchSignalSEH\\.cpp" > "$SCRATCH/core_tus.txt"
# NOTE: libseqarrange + biz-crypto intentionally excluded (z3/openssl absent).

# Vendored explicit sources: everything compilable except mcut (own build,
# CI-green), Eigen (headers-only), and asm (.S).
find "$ROOT/engine/src/main/jni" \( -name "*.cpp" -o -name "*.c" -o -name "*.cc" \) \
  | grep -v "/mcut/" | grep -v "/eigen/" > "$SCRATCH/vendored_tus.txt"
ls $P/bundled_deps/admesh/admesh/*.cpp $P/bundled_deps/localesutils/*.cpp >> "$SCRATCH/vendored_tus.txt" 2>/dev/null
echo "$ROOT/engine/src/main/jni/engine_compat.cpp" >> "$SCRATCH/vendored_tus.txt"

check_one() {
  f="$1"
  mode="$2"
  extra=""
  case "$f" in
    *CustomParametersHandling.cpp) extra="-fno-openmp" ;;
  esac
  # Mirror CI: core TUs get globals only; slic3r-target TUs get the PUBLIC defs.
  defs=""
  [ "$mode" = "slic3r" ] && defs="$SLIC3R_DEFS"
  if ! clang++ $COMMON $CORE_INC $defs $extra -I$ROOT/engine/src/main/jni "$f" -o /dev/null 2>"$SCRATCH/err_$$.txt"; then
    echo "FAIL: $f"
    head -6 "$SCRATCH/err_$$.txt" | sed 's/^/    /'
  fi
}
export -f check_one
export COMMON CORE_INC SLIC3R_DEFS SCRATCH
export PATH
{ while read -r f; do printf '%s\0' "core:$f"; done < "$SCRATCH/core_tus.txt";
  while read -r f; do printf '%s\0' "slic3r:$f"; done < "$SCRATCH/vendored_tus.txt"; } \
| xargs -0 -P "$JOBS" -I{} bash -c 'pair="$1"; check_one "${pair#*:}" "${pair%%:*}"' _ {} 2>&1 | tee "$OUT"
echo "---"
grep -c "^FAIL" "$OUT" | xargs echo "failed files:"
