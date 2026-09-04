#!/usr/bin/env bash
# scripts/build_tbb_bridge.sh — generate oneapi/tbb/<> forward headers so code
# written against oneTBB's modern include layout (#include <oneapi/tbb/x.h>)
# compiles against the CLASSIC-TBB include tree (#include <tbb/x.h>) shipped by
# the openvdb-android oneTBB dep artifact as jniImports/oneTBB/include. Idempotent
# and safe to run on a cached, restored, or freshly built tree.
#
# usage: scripts/build_tbb_bridge.sh <include-root>
#   <include-root> is the directory that *contains* tbb/ (e.g.
#   engine/src/main/jniImports/oneTBB/include). Writes forwarders into
#   <include-root>/oneapi/tbb/, one per top-level tbb/*.h.
set -euo pipefail

INC="${1:?usage: build_tbb_bridge.sh <include-root>}"
SRC="$INC/tbb"
DST="$INC/oneapi/tbb"
test -d "$SRC" || { echo "ERROR: $SRC not found (pass the dir that contains tbb/)" >&2; exit 1; }

mkdir -p "$DST"
count=0
skipped=0
for h in "$SRC"/*.h; do
  [ -e "$h" ] || continue
  name="$(basename "$h")"
  # Idempotent: a real oneapi/tbb header already present wins (don't clobber).
  if [ -f "$DST/$name" ]; then
    skipped=$((skipped+1))
    continue
  fi
  guard="__BRIDGE_ONEAPI_TBB_$(printf '%s' "$name" | tr '[:lower:].' '[:upper:]__')_H"
  {
    echo "#ifndef $guard"
    echo "#define $guard"
    echo "#include <tbb/$name>"
    echo "#endif"
  } > "$DST/$name"
  count=$((count+1))
done
echo "[tbb-bridge] generated $count forwarders in $DST (classic tbb/ -> oneapi/tbb/; $skipped already present)"