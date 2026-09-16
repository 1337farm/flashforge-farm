#!/bin/bash
# scripts/stage_prusa30_pugixml.sh — stage pugixml (headers + sources) for 3.0.
#
# Upstream 3mf/BuildTicket.cpp uses pugi::xml_node (needs compiled pugixml,
# not just headers). Pin/URL/SHA mirror upstream deps/+pugixml. Unlike pure
# header deps, both the include dir (-I) and pugixml.cpp (compiled into
# libslic3r_core, see engine/CMakeLists.txt) are consumed.
#
#   STAGE_ROOT  install prefix (default $(pwd)/engine/prusa30/jniImports/pugixml)
#   WORK_DIR    scratch dir (default /tmp/stage_prusa30_pugixml)
set -e
STAGE_ROOT="${STAGE_ROOT:-$(pwd)/engine/prusa30/jniImports/pugixml}"
WORK_DIR="${WORK_DIR:-/tmp/stage_prusa30_pugixml}"
PUGIXML_VER="1.15"
PUGIXML_SHA="4e1aa9a5b5654b63b2ae5c4cd10b88ac295e91c86b1fb957e701fb4d5b04022"
PUGIXML_URL="https://github.com/zeux/pugixml/releases/download/v${PUGIXML_VER}/pugixml-${PUGIXML_VER}.zip"
mkdir -p "$STAGE_ROOT" "$WORK_DIR"
DL="$WORK_DIR/pugixml.zip"
for i in 1 2 3; do
    if curl -fsSL --connect-timeout 20 --max-time 300 \
            --retry 2 --retry-all-errors -o "$DL" "$PUGIXML_URL" \
       && [ -s "$DL" ] \
       && echo "$PUGIXML_SHA  $DL" | sha256sum -c --status -; then
        break
    fi
    echo "--- [stage] pugixml download/verify attempt $i/3 failed ---" >&2
    [ "$i" = "3" ] && exit 1
    sleep "$((i * 5))"
done
rm -rf "$WORK_DIR/pugixml"; mkdir -p "$WORK_DIR/pugixml"
unzip -q -o "$DL" -d "$WORK_DIR/pugixml"
SRC_H=$(find "$WORK_DIR/pugixml" -name pugixml.hpp | head -1)
SRC_C=$(find "$WORK_DIR/pugixml" -name pugixml.cpp | head -1)
[ -n "$SRC_H" ] && [ -n "$SRC_C" ] \
    || { echo "--- [stage] ERROR: pugixml sources not found ---" >&2; exit 1; }
mkdir -p "$STAGE_ROOT/include" "$STAGE_ROOT/src"
cp "$SRC_H" "$STAGE_ROOT/include/"
cp "$SRC_C" "$STAGE_ROOT/src/"
[ -f "$STAGE_ROOT/include/pugixml.hpp" ] && [ -f "$STAGE_ROOT/src/pugixml.cpp" ] \
    || { echo "--- [stage] ERROR: staged pugixml incomplete ---" >&2; exit 1; }
echo "pugixml $PUGIXML_VER staged at $STAGE_ROOT"
