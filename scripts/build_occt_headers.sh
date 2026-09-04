#!/usr/bin/env bash
# scripts/build_occt_headers.sh — stage a complete, self-contained set of OCCT
# public headers into engine/src/main/occt/include/<ABI>.
#
# OCCT's generated inc/opencascade tree (copied as-is by build_all_deps_android.sh
# / build_occt()) contains Forwarding headers (e.g. Standard_HashUtils.lxx,
# STEPCAFControl_Reader.hxx, <Class>.pxx/.gxx) whose entire content is a single
# line:
#   #include "/tmp/build_android_deps/OCCT/src/<Pkg>/<File>.<ext>"
# i.e. an ABSOLUTE path baked in at OCCT configure/build time. That path never
# exists on a different runner (or the app's compile step — the OCCT work dir is
# per-machine and cleaned up), so any such stub fails there with:
#   fatal error: '<File>' file not found
# (header found at occt/include/<ABI>, but its forwarding include target is
# missing).
#
# The real declarations live in the OCCT source tree under src/<Pkg>/. OCCT
# source headers include each other by flat filename resolved via -I, so
# flatten-copying every <Pkg> header into the same include dir is exactly how
# OCCT's own inc/ directory is consumed.
#
# This script shadows every forwarding stub (across ALL OCCT header extensions:
# .hxx/.h/.lxx/.gxx/.pxx) with its real content. It is idempotent and MUST run
# AFTER the occt artifact is staged into engine/src/main/occt — whether from a
# fresh build or a cache restore — and BEFORE the artifact is uploaded.
#
# Env inputs (all optional; defaults shown):
#   WORK_DIR     build scratch root (default /tmp/build_android_deps)
#   OCCT_SRC     path to an OCCT V7_9_0 source tree with a src/ subdir
#                (default $WORK_DIR/OCCT)
#   OCCT_INC     destination include dir (default <repo>/engine/src/main/occt/include/<ABI>)
#   ABI          target ABI (default arm64-v8a)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"

ABI="${ABI:-arm64-v8a}"
OCCT_SRC="${OCCT_SRC:-${WORK_DIR:-/tmp/build_android_deps}/OCCT}"
OCCT_INC="${OCCT_INC:-$ROOT/engine/src/main/occt/include/$ABI}"
SRC_HDR_ROOT="$OCCT_SRC/src"

[ -d "$SRC_HDR_ROOT" ] || {
    echo "[occt-headers] ERROR: OCCT source header tree not found at $SRC_HDR_ROOT (set OCCT_SRC or WORK_DIR)" >&2
    exit 1
}

mkdir -p "$OCCT_INC"

# find is called twice (once to count, once to copy). Keep the match expression
# in one place and forward any extra args (e.g. -exec) to find to avoid drift.
find_occt_headers() {
    find "$SRC_HDR_ROOT" -maxdepth 2 -type f \
        \( -name '*.hxx' -o -name '*.h' -o -name '*.lxx' -o -name '*.gxx' -o -name '*.pxx' \) "$@"
}

N="$(find_occt_headers | wc -l)"
echo "--- [occt-headers] flattening $N headers from $SRC_HDR_ROOT over $OCCT_INC ---"

# find -exec (not `cp <burst>`) to stay under ARG_MAX: OCCT source has thousands
# of headers. -L dereferences any intra-src symlinks.
find_occt_headers -exec cp -f -L {} "$OCCT_INC/" \;

# Report and fail if any forwarding stub (absolute /tmp include) survived.
# `|| true` guards the grep exit-code 1 (no matches) under -e/pipefail.
REMAIN="$(grep -rl '/tmp/' "$OCCT_INC" 2>/dev/null | wc -l || true)"
echo "--- [occt-headers] remaining /tmp forwarding stubs: $REMAIN ---"
if [ "$REMAIN" -ne 0 ]; then
    echo "[occt-headers] ERROR: $REMAIN forwarding stubs not resolved; list:" >&2
    grep -rl '/tmp/' "$OCCT_INC" 2>/dev/null | head -10 >&2
    exit 1
fi
echo "--- [occt-headers] done: all OCCT headers staged with real content ---"
