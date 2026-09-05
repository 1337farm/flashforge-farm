#!/usr/bin/env bash
# scripts/package-occt-runtime.sh — stage ONLY the OCCT toolkits the engine
# actually loads, stripped, into engine/output/occt-libs/<ABI>/ for APK
# packaging. The full OCCT build produces ~50 shared libs (~64MB); the
# engine's DT_NEEDED closure is exactly OCCT_LIBS in engine/CMakeLists.txt
# (22 toolkits, verified against a built libslic3r.so). The rest never loads
# and only bloats the APK. Idempotent.
#
# Usage: scripts/package-occt-runtime.sh
# Env:   ABI (default arm64-v8a), ANDROID_NDK_ROOT (for strip)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"
cd "$ROOT"

ABI="${ABI:-arm64-v8a}"
SRC="engine/src/main/occt/jniLibs/$ABI"
DST="engine/output/occt-libs/$ABI"

[ -d "$SRC" ] || { echo "[occt-pkg] ERROR: $SRC missing (stage OCCT first)" >&2; exit 1; }
LIBS="$(grep -oP 'set\(OCCT_LIBS \K[^)]*' engine/CMakeLists.txt | tr ' ' '\n' | grep -v '^$')"
[ -n "$LIBS" ] || { echo "[occt-pkg] ERROR: could not parse OCCT_LIBS" >&2; exit 1; }

rm -rf "$DST"
mkdir -p "$DST"
missing=0
for name in $LIBS; do
    if [ -f "$SRC/lib${name}.so" ]; then
        cp "$SRC/lib${name}.so" "$DST/"
    else
        echo "[occt-pkg] ERROR: lib${name}.so not in $SRC" >&2
        missing=1
    fi
done
[ "$missing" -eq 0 ] || exit 1

chmod +x "$SCRIPT_DIR/strip-so.sh"
"$SCRIPT_DIR/strip-so.sh" "$DST"/libTK*.so >/dev/null
count="$(ls "$DST"/libTK*.so | wc -l)"
size="$(du -sh "$DST" | cut -f1)"
echo "[occt-pkg] staged $count OCCT runtime libs ($size) into $DST"
