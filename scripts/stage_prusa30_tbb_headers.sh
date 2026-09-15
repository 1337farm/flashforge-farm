#!/bin/bash
# scripts/stage_prusa30_tbb_headers.sh — stage oneTBB v2021.12.0 HEADERS only.
#
# The package-apk job must not use actions/cache (CI guard); a full oneTBB
# source build is ~10 min, but the app bridge only needs TBB *headers*
# (it links the engine's libslic3r.so, never libtbb.a). So this stages just
# include/{oneapi,tbb} from the pinned tag in seconds. Pin/URL/SHA mirror
# scripts/build_prusa30_tbb.sh so both stagings stay in lockstep.
#
#   STAGE_ROOT  install prefix (default $(pwd)/engine/prusa30/jniImports/tbb)
#   WORK_DIR    scratch dir (default /tmp/stage_prusa30_tbb_headers)
set -e
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports/tbb}"
WORK_DIR="${WORK_DIR:-/tmp/stage_prusa30_tbb_headers}"
TBB_VER="2021.12.0"
TBB_URL="https://github.com/oneapi-src/oneTBB/archive/refs/tags/v${TBB_VER}.zip"
TBB_SHA="fe6ca052b5bdd2c6e0616b360c9b0dcbcc46e01bbd0aa8fd0517c17fc58931db"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"
DL="$WORK_DIR/oneTBB-$TBB_VER.zip"
for i in 1 2 3; do
    if curl -fsSL --connect-timeout 20 --max-time 300 \
            --retry 2 --retry-all-errors -o "$DL" "$TBB_URL" \
       && [ -s "$DL" ]; then
        break
    fi
    echo "--- [stage] oneTBB headers download attempt $i/3 failed ---" >&2
    [ "$i" = "3" ] && exit 1
    sleep "$((i * 5))"
done
echo "$TBB_SHA  $DL" | sha256sum -c --status - \
    || { echo "--- [stage] oneTBB sha256 mismatch ---" >&2; exit 1; }
rm -rf "$WORK_DIR/oneTBB-$TBB_VER"; mkdir -p "$WORK_DIR/oneTBB-$TBB_VER"
unzip -q -o "$DL" -d "$WORK_DIR/oneTBB-$TBB_VER"
SRC="$(find "$WORK_DIR/oneTBB-$TBB_VER" -maxdepth 2 -name CMakeLists.txt -path '*oneTBB*' | head -1 | xargs dirname)"
[ -n "$SRC" ] || SRC="$(find "$WORK_DIR/oneTBB-$TBB_VER" -maxdepth 1 -mindepth 1 -type d | head -1)"
[ -d "$SRC/include/oneapi" ] && [ -d "$SRC/include/tbb" ] \
    || { echo "--- [stage] ERROR: oneTBB include tree not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/include"
cp -r "$SRC/include/oneapi" "$SRC/include/tbb" "$STAGE_ROOT/include/"
[ -f "$STAGE_ROOT/include/oneapi/tbb/version.h" ] && [ -f "$STAGE_ROOT/include/tbb/pipeline.h" ] \
    || { echo "--- [stage] ERROR: staged oneTBB headers incomplete ---" >&2; exit 1; }
echo "oneTBB $TBB_VER headers staged at $STAGE_ROOT/include"
