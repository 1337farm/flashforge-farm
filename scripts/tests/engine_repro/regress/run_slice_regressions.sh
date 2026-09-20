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

# 1) Baseline: 20mm cube on the reported Benchy AD5M config must slice.
#    If this throws, the engine slice path itself is broken for everything.
run "cube_20mm + user_benchy_ad5m.ini slices" 0 \
    "$REG/user_benchy_ad5m.ini" "$REG/cube_20mm.stl" || fails=$((fails+1))

# 2) Empty model must fail LOUDLY — explicit error (2) or coded slicing
#    exception (3). Either names the cause; silent success (0) or an
#    unclassified crash would be the bug.
rc_log="$REG/.slice_run.log"
: > "$TMPGCODE"
if "$HARNESS" "$REG/user_benchy_ad5m.ini" "$REG/empty.stl" "$TMPGCODE" >"$rc_log" 2>&1; then
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

echo
if [ "$fails" -gt 0 ]; then
    echo "SLICE_REGRESSION: $fails case(s) FAILED"
    exit 1
fi
echo "SLICE_REGRESSION: all cases pass"
