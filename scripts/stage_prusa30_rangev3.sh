#!/bin/bash
# scripts/stage_prusa30_rangev3.sh — stage range-v3 headers for the 3.0 engine.
#
# Upstream ProjectPresetView.hpp spells #include <range/v3/view/concat.hpp>;
# upstream consumes range-v3 0.12.0 via its deps/ build. Pin/URL/SHA mirror
# deps/+rangev3/rangev3.cmake so both stagings stay in lockstep. Header-only.
#
#   STAGE_ROOT  install prefix (default $(pwd)/engine/prusa30/jniImports/rangev3)
#   WORK_DIR    scratch dir (default /tmp/stage_prusa30_rangev3)
set -e
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports/rangev3}"
WORK_DIR="${WORK_DIR:-/tmp/stage_prusa30_rangev3}"
RANGE_VER="0.12.0"
RANGE_SHA="015adb2300a98edfceaf0725beec3337f542af4915cec4d0b89fa0886f4ba9cb"
RANGE_URL="https://github.com/ericniebler/range-v3/archive/refs/tags/${RANGE_VER}.tar.gz"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"
DL="$WORK_DIR/range-v3-$RANGE_VER.tar.gz"
for i in 1 2 3; do
    if curl -fsSL --connect-timeout 20 --max-time 300 \
            --retry 2 --retry-all-errors -o "$DL" "$RANGE_URL" \
       && [ -s "$DL" ]; then
        break
    fi
    echo "--- [stage] range-v3 download attempt $i/3 failed ---" >&2
    [ "$i" = "3" ] && exit 1
    sleep "$((i * 5))"
done
echo "$RANGE_SHA  $DL" | sha256sum -c --status - \
    || { echo "--- [stage] range-v3 sha256 mismatch ---" >&2; exit 1; }
rm -rf "$WORK_DIR/range-v3"; mkdir -p "$WORK_DIR/range-v3"
tar xzf "$DL" -C "$WORK_DIR/range-v3"
SRC_INC="$(find "$WORK_DIR/range-v3" -maxdepth 3 -type d -name include | head -1)"
[ -n "$SRC_INC" ] && [ -d "$SRC_INC/range" ] \
    || { echo "--- [stage] ERROR: range headers not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/include"
cp -r "$SRC_INC/range" "$STAGE_ROOT/include/"
[ -f "$STAGE_ROOT/include/range/v3/view/concat.hpp" ] \
    || { echo "--- [stage] ERROR: staged range headers incomplete ---" >&2; exit 1; }
echo "range-v3 $RANGE_VER staged at $STAGE_ROOT/include"
