#!/bin/bash
# Resolve the engine libs dir to test and ensure config_apply_crash is
# executable. Used by run_regressions.sh.
#
# Usage: build_harness.sh [engine_libs_dir]
# Candidate dirs (first match wins):
#   1. first positional arg
#   2. $ENGINE_LIBS_DIR
#   3. dirname($ENGINE_SO)
#   4. $REPO/engine/output/arm64-v8a
#   5. $REPO/engine/output
#
# Prints the resolved libs dir (the dir containing libslic3r.so) on stdout
# and records a sha256 pin file so audit trails show which engine was tested.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
HARNESS="$REPO/scripts/tests/engine_repro/config_apply_crash"
PIN="$REPO/scripts/tests/engine_repro/regress/.engine_pin"

candidates=()
[ -n "${1:-}" ] && candidates+=("$1")
[ -n "${ENGINE_LIBS_DIR:-}" ] && candidates+=("$ENGINE_LIBS_DIR")
[ -n "${ENGINE_SO:-}" ] && candidates+=("$(dirname "$ENGINE_SO")")
candidates+=("$REPO/engine/output/arm64-v8a" "$REPO/engine/output")

SO=""
for c in "${candidates[@]}"; do
    if [ -f "$c/libslic3r.so" ]; then
        SO="$c/libslic3r.so"
        break
    fi
done

if [ -z "$SO" ]; then
    echo "build_harness.sh: no libslic3r.so under candidate dirs" >&2
    exit 2
fi

if [ ! -x "$HARNESS" ]; then
    chmod +x "$HARNESS" || exit 2
fi

sha256sum "$SO" | awk '{print $1}' >"$PIN"
echo "$(dirname "$SO")"