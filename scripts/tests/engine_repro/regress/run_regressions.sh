#!/bin/bash
# Regression gate for the config_apply_crash harness.
#
# Usage:
#   run_regressions.sh [engine_libs_dir]
#
# engine_libs_dir defaults to the dir discovered by build_harness.sh
# (ENGINE_SO / ENGINE_LIBS_DIR override it).
#
# Each case runs the harness against the chosen engine libslic3r.so and
# compares the exit code with the expected post-fix behavior. Running against
# the *unpatched* engine should show the SIGSEGV / load-failure cases RED;
# once the engine .so is rebuilt from the fixed sources they go GREEN.
#
# Exit codes used by config_apply_crash:
#   0   apply + validate OK
#   2   ConfigurationError (load/apply/slice config)
#  139  Segmentation fault (signal 11)
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
HARNESS="$REPO/scripts/tests/engine_repro/config_apply_crash"
REG="$REPO/scripts/tests/engine_repro/regress"

ENGINE_LIBS_DIR="${1:-}"
export ENGINE_LIBS_DIR
# build_harness.sh resolves a real libslic3r.so (falling back to the local
# engine/output staging dir when no explicit dir is given) and prints it.
ENGINE_LIBS_DIR="$(bash "$REPO/scripts/tests/engine_repro/build_harness.sh" "$ENGINE_LIBS_DIR")" || exit 2

echo "engine under test: $ENGINE_LIBS_DIR/libslic3r.so"
sha256sum "$ENGINE_LIBS_DIR/libslic3r.so" 2>/dev/null | awk '{print "engine sha256:", $1}'
if [ -f "$ENGINE_LIBS_DIR/.engine_src" ]; then
    echo "engine src: $(cat "$ENGINE_LIBS_DIR/.engine_src") (want $(git -C "$REPO" rev-parse HEAD:engine 2>/dev/null || echo ?))"
fi

# libslic3r.so links libgmp/libgmpxx/libmpfr which are staged next to the
# engine's jniLibs (engine/src/main/jniLibs/<ABI>), not next to the engine
# output. Include both so the harness links without manual LD setup.
DEPS_LIBS_DIR="$REPO/engine/src/main/jniLibs/$(basename "$ENGINE_LIBS_DIR")"
HARNESS_LD_PATH="$ENGINE_LIBS_DIR${DEPS_LIBS_DIR:+:$DEPS_LIBS_DIR}"
[ -d "$DEPS_LIBS_DIR" ] || HARNESS_LD_PATH="$ENGINE_LIBS_DIR"

run() {
    local name="$1" expect="$2"; shift 2
    local log="$REG/.run.log"
    LD_LIBRARY_PATH="$HARNESS_LD_PATH" "$HARNESS" "$@" >"$log" 2>&1
    local rc=$?
    if [ "$rc" -eq "$expect" ]; then
        echo "PASS  $name (exit $rc)"
        return 0
    fi
    echo "FAIL  $name (expected exit $expect, got $rc)"
    tail -n 8 "$log"
    return 1
}

fails=0

# 1) Dual-extruder preset with empty per-extruder variant vectors crashed the
#    engine in update_values_to_printer_extruders (SIGSEGV, exit 139) because
#    get_at computed values.front() on an empty vector. After the guard fix:
#    apply + validate OK (exit 0).
run "dual_extruder_empty_variants.ini" 0 "$REG/dual_extruder_empty_variants.ini" || fails=$((fails+1))

# 2) The multi-extruder merge in FarmApp.buildCurrentConfigObject() joined
#    per-extruder vector values with ';' (e.g. reduce_fan_stop_start_freq = 1;1),
#    which the engine's INI reader rejects for any non-string vector (separator
#    is ','). The fixed editor emits '1,1' which must load and apply.
run "vector_comma_join_fixed.ini (fixed editor output)" 0 "$REG/vector_comma_join_fixed.ini" || fails=$((fails+1))

# 3) The legacy ';'-joined vector must still be rejected (guards the engine's
#    separator contract so an old/mismatched editor cannot silently load).
run "vector_semicolon_join_broken.ini (legacy editor output)" 2 "$REG/vector_semicolon_join_broken.ini" || fails=$((fails+1))

# 4) Reported 3D-Benchy STL slice on the Flashforge AD5M profile
#    (0.20mm Standard, Generic PLA, prime tower, auto_brim, Cool Plate)
#    threw bare ConfigOptionEnumGeneric incompatible-type on a pre-#42
#    engine. Fixture now carries the runtime machine-limit vectors from
#    farm_crash_c92115550c. Must apply+validate clean.
run "user_benchy_ad5m.ini (reported slice config)" 0 "$REG/user_benchy_ad5m.ini" || fails=$((fails+1))

# 5) ConfigOptionEnumsGenericTempl::set() copy semantics, direct. The on-device
#    "Assigning an incompatible type" (2026-09-09) fires because the coEnums
#    copy used dynamic_cast, which fails across the libfarm/libslic3r DSO
#    boundary (duplicated template typeinfo). The fix copies via static_cast on
#    the shared ConfigOptionInts base; this case guards the copy (incl. the
#    Templ<true>/<false> cross-variant path) and the type-mismatch throw.
run "enum-set-check (RTTI-free coEnums copy)" 0 --enum-set-check || fails=$((fails+1))

# 5) Same reported Benchy config against the real STL with paint-relevant
#    filament counts. The app only resizes per-filament vectors when painting
#    or palette state requests N > 1; exercise that branch explicitly because
#    a mismatch there would surface later than config load.
for benchy_filaments in 1 2 8; do
  run "user_benchy_ad5m.ini + 3DBenchy.stl filaments=$benchy_filaments" 0 \
    "$REG/user_benchy_ad5m.ini" "/sdcard/Download/3DBenchy.stl" "$benchy_filaments" \
    || fails=$((fails+1))
done

echo
if [ "$fails" -gt 0 ]; then
    echo "REGRESSION: $fails case(s) FAILED (engine .so still unpatched?)"
    exit 1
fi
echo "REGRESSION: all cases pass"