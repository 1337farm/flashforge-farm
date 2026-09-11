#!/usr/bin/env bash
# Fast LOCAL syntax-check of the PrusaSlicer 3.0 headless driver against the
# real upstream headers — catches type/signature/include errors in seconds
# without linking (no libslic3r.a, no NDK). This is the tight loop that drives
# farm_driver.cpp correctness before the slow CI configure/build runs.
#
# Usage:
#   scripts/tests/syntax_check_prusa30.sh [file]   # default engine/prusa30/farm_driver.cpp
#
# Env:
#   PRUSA_UP  path to the prusa3d/PrusaSlicer checkout (default sibling clone)
#   STAGE     staged header-only deps (scripts/build_prusa30_deps.sh output)
#   HD        extra header-only deps (libassert/expected/magic_enum/cpptrace)
set -euo pipefail

UP="${PRUSA_UP:-/data/data/com.termux/files/home/code/prusaslicer-upstream}"
STAGE="${STAGE:-/data/data/com.termux/files/home/.cache/opencode/tmp/prusa30deps-test}"
HD="${HD:-/data/data/com.termux/files/home/.cache/opencode/tmp/hdeps}"
DRV="${1:-/data/data/com.termux/files/home/code/flashforge-farm/engine/prusa30/farm_driver.cpp}"

flags=()
for d in $(find "$UP/src" -maxdepth 2 -type d -name include); do flags+=(-I"$d"); done
# bundled_deps expose headers inconsistently: <dep>/<dep>/x.h, <dep>/include/x.h,
# and <dep>/x.h — add every dir, but EXCLUDE desktop/GUI/windows-only deps
# (avrdude's windows/unistd.h would shadow the system <unistd.h>).
for d in $(find "$UP/bundled_deps" -type d \
    -not -path '*/avrdude/*' \
    -not -path '*/imgui/*' \
    -not -path '*/webview2/*' \
    -not -path '*/stb_dxt/*' \
    -not -path '*/stb_image/*'); do flags+=(-I"$d"); done
for s in nlohmann_json spdlog fmt sol2 eigen cereal; do flags+=(-I"$STAGE/$s/include"); done
for s in libassert-2.2.1 expected-1.1.0 magic_enum-0.9.7 cpptrace-1.0.4; do flags+=(-I"$HD/$s/include"); done
flags+=(-I"$HD/nanosvg_inc")
flags+=(-I/data/data/com.termux/files/usr/include)

exec clang++ -std=c++20 -fsyntax-only "${flags[@]}" "$DRV"