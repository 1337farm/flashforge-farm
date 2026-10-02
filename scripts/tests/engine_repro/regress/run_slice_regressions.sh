#!/bin/bash
# Slice-stage regression gate for the slice_repro harness.
#
# Usage: run_slice_regressions.sh <slice_repro_binary>
#
# Drives the REAL farm::slice_to_gcode with fixture models + legacy INIs and
# classifies the outcome. Exit codes from slice_repro:
#   0  sliced OK (gcode written, non-empty)
#   2  explicit failure (names the cause: model load, config, apply, IO)
#   3  bare SlicingStatus::Exception (the device crash signature):
#      prints SLICING_EXCEPTION code=<int> keys=[...] object=<id>
#
# A bare code=3 with a NEW ErrorCode is a FINDING, not a pass — it names the
# next fix. Cases below pin behavior known-good or known-diagnosed.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"
REG="$REPO/scripts/tests/engine_repro/regress"
HARNESS="${1:-}"

if [ -z "$HARNESS" ] || [ ! -x "$HARNESS" ]; then
    echo "run_slice_regressions.sh: need executable slice_repro binary as \$1" >&2
    exit 64
fi

fails=0
TMPGCODE="$(mktemp /tmp/slice_repro_XXXXXX.gcode)"
trap 'rm -f "$TMPGCODE"' EXIT

# Library path for the harness binary. SLICE_LIBS_DIR overrides (a 3.0
# engine staging dir, e.g. the CI libslic3r-<src> artifact); otherwise fall
# back to build_harness.sh resolution (fail-closed on stale engines).
if [ -z "${SLICE_LIBS_DIR:-}" ]; then
    SLICE_LIBS_DIR="$(bash "$REPO/scripts/tests/engine_repro/build_harness.sh" 2>/dev/null || true)"
fi
if [ -n "$SLICE_LIBS_DIR" ]; then
    DEPS_LIBS_DIR="$REPO/engine/src/main/jniLibs/$(basename "$SLICE_LIBS_DIR")"
    [ -d "$DEPS_LIBS_DIR" ] || DEPS_LIBS_DIR="$REPO/engine/src/main/jniLibs/arm64-v8a"
    export LD_LIBRARY_PATH="$SLICE_LIBS_DIR:$DEPS_LIBS_DIR:${LD_LIBRARY_PATH:-}"
fi

run() {
    local name="$1" expect="$2"; shift 2
    local log="$REG/.slice_run.log"
    : > "$TMPGCODE"
    "$HARNESS" "$@" "$TMPGCODE" >"$log" 2>&1
    local rc=$?
    if [ "$rc" -eq "$expect" ]; then
        echo "PASS  $name (exit $rc)"
        return 0
    fi
    echo "FAIL  $name (expected exit $expect, got $rc)"
    tail -n 12 "$log"
    return 1
}

# 1) Baseline: 20mm cube on a minimal valid config must slice end to end.
#    Guards the bed-instances regression: with no instances assigned to the
#    bed, update() emptied the model and slice died with bare EmptyPrint.
run "cube_20mm + minimal.ini slices" 0 \
    "$REG/minimal.ini" "$REG/cube_20mm.stl" || fails=$((fails+1))

# 2) Empty model must fail LOUDLY — explicit error (2) or coded slicing
#    exception (3). Either names the cause; silent success (0) or an
#    unclassified crash would be the bug.
rc_log="$REG/.slice_run.log"
: > "$TMPGCODE"
if "$HARNESS" "$REG/minimal.ini" "$REG/empty.stl" "$TMPGCODE" >"$rc_log" 2>&1; then
    echo "FAIL  empty.stl sliced silently (expected loud failure)"
    tail -n 12 "$rc_log"
    fails=$((fails+1))
else
    rc=$?
    if [ "$rc" -eq 2 ] || [ "$rc" -eq 3 ]; then
        echo "PASS  empty.stl fails loudly (exit $rc)"
    else
        echo "FAIL  empty.stl unclassified exit $rc (expected 2 or 3)"
        tail -n 12 "$rc_log"
        fails=$((fails+1))
    fi
fi

# 3) The reported Benchy AD5M config must never die BARE: exit 2 with a
#    named cause, or exit 3 carrying a SLICING_EXCEPTION code. A bare
#    "Slicing exception" with no code is the regression.
: > "$TMPGCODE"
if "$HARNESS" "$REG/slice_benchy_ad5m.ini" "$REG/cube_20mm.stl" "$TMPGCODE" >"$rc_log" 2>&1; then
    echo "PASS  benchy config slices (exit 0)"
else
    rc=$?
    if { [ "$rc" -eq 2 ] && grep -q "REPRO_ERROR" "$rc_log"; } \
        || { [ "$rc" -eq 3 ] && grep -q "SLICING_EXCEPTION code=" "$rc_log"; }; then
        echo "PASS  benchy config fails loudly (exit $rc, cause named)"
    else
        echo "FAIL  benchy config failed without a named cause (exit $rc)"
        tail -n 12 "$rc_log"
        fails=$((fails+1))
    fi
fi

echo
if [ "$fails" -gt 0 ]; then
    echo "SLICE_REGRESSION: $fails case(s) FAILED"
    exit 1
fi
echo "SLICE_REGRESSION: all cases pass"
