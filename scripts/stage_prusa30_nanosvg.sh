#!/bin/bash
# scripts/stage_prusa30_nanosvg.sh — stage fltk NanoSVG headers for the 3.0 engine.
#
# Upstream EmbossShape.hpp spells #include <nanosvg/nanosvg.h>; upstream links
# NanoSVG::nanosvg from the fltk fork (nsvgRasterizeXY). Pin/URL/SHA mirror
# scripts/build_prusa30_deps.sh so both stagings stay in lockstep, and the
# layout (include/nanosvg/*.h + NanoSVGConfig.cmake) matches it for future
# find_package(NanoSVG) use.
#
#   STAGE_ROOT  install prefix (default $(pwd)/engine/prusa30/jniImports/nanosvg)
#   WORK_DIR    scratch dir (default /tmp/stage_prusa30_nanosvg)
set -e
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports/nanosvg}"
WORK_DIR="${WORK_DIR:-/tmp/stage_prusa30_nanosvg}"
NSVG_VER="abcd277ea45e9098bed752cf9c6875b533c0892f"
NSVG_SHA="e859938fbaee4b351bd8a8b3d3c7a75b40c36885ce00b73faa1ce0b98aa0ad34"
NSVG_URL="https://github.com/fltk/nanosvg/archive/${NSVG_VER}.zip"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"
DL="$WORK_DIR/nanosvg.zip"
for i in 1 2 3; do
    if curl -fsSL --connect-timeout 20 --max-time 300 \
            --retry 2 --retry-all-errors -o "$DL" "$NSVG_URL" \
       && [ -s "$DL" ]; then
        break
    fi
    echo "--- [stage] nanosvg download attempt $i/3 failed ---" >&2
    [ "$i" = "3" ] && exit 1
    sleep "$((i * 5))"
done
GOT="$(sha256sum "$DL" | cut -d' ' -f1)"
[ "$GOT" = "$NSVG_SHA" ] || { echo "--- [stage] nanosvg sha256 mismatch (want $NSVG_SHA got $GOT) ---" >&2; exit 1; }
rm -rf "$WORK_DIR/nanosvg"; mkdir -p "$WORK_DIR/nanosvg"
unzip -q -o "$DL" -d "$WORK_DIR/nanosvg"
NSVG_SRC="$(find "$WORK_DIR/nanosvg" -name nanosvg.h | head -1 | xargs dirname)"
[ -n "$NSVG_SRC" ] || { echo "--- [stage] ERROR: nanosvg.h not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/include/nanosvg"
cp "$NSVG_SRC/nanosvg.h" "$NSVG_SRC/nanosvgrast.h" "$STAGE_ROOT/include/nanosvg/"
mkdir -p "$STAGE_ROOT/lib/cmake/NanoSVG"
cat > "$STAGE_ROOT/lib/cmake/NanoSVG/NanoSVGConfig.cmake" <<'EOF'
# Staged NanoSVG headers (header-only, no lib to link).
if(NOT TARGET NanoSVG::nanosvg)
  add_library(NanoSVG::nanosvg INTERFACE IMPORTED)
  set_target_properties(NanoSVG::nanosvg PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
if(NOT TARGET NanoSVG::nanosvgrast)
  add_library(NanoSVG::nanosvgrast INTERFACE IMPORTED)
  set_target_properties(NanoSVG::nanosvgrast PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
EOF
[ -f "$STAGE_ROOT/include/nanosvg/nanosvg.h" ] || { echo "--- [stage] ERROR: staged nanosvg.h missing ---" >&2; exit 1; }
echo "nanosvg $NSVG_VER staged at $STAGE_ROOT"
