#!/usr/bin/env bash
# scripts/find-trim-candidates.sh — local analysis to find trimmable deps.
#
# Run AFTER a clean CI build + local fetch (scripts/build-debug.sh --fetch-only).
# Analyzes the staged libslic3r.so + archives to identify candidates for removal
# from engine/CMakeLists.txt (OCCT_LIBS, BOOST_LIBS).
#
# Uses only Termux/device tools (nm, readelf) — no re-link, no CI change.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"
cd "$ROOT"

ABI="arm64-v8a"

LIB_SO="engine/output/$ABI/libslic3r.so"
JNI_LIBS="engine/src/main/occt/jniLibs/$ABI"
BOOST_LIB="engine/src/main/jniImports/boost/lib/$ABI/lib"
TBB_LIB="engine/src/main/jniImports/oneTBB/lib/$ABI"

# Scratch space. The device may not have a writable /tmp, so fall back to a
# git-ignored dir under the repo (it is removed at the end).
SCRATCH="${TMPDIR:-/tmp}"
if [ ! -w "$SCRATCH" ]; then
    SCRATCH="$ROOT/.trim-analysis"
    mkdir -p "$SCRATCH"
fi

command -v nm >/dev/null 2>&1 || { echo "ERROR: nm not found" >&2; exit 1; }
command -v readelf >/dev/null 2>&1 || { echo "ERROR: readelf not found" >&2; exit 1; }

[ -f "$LIB_SO" ] || { echo "ERROR: $LIB_SO not found. Run 'scripts/build-debug.sh --fetch-only' first." >&2; exit 1; }

echo "=== Analyzing $LIB_SO ==="

# --- 1. DT_NEEDED of libslic3r.so (runtime shared lib dependencies) ---------
echo ""
echo "--- DT_NEEDED (runtime shared libs) ---"
readelf -d "$LIB_SO" | grep 'NEEDED' | sed 's/.*\[\(.*\)\].*/\1/'

# --- 2. OCCT toolkits (shared .so in jniLibs) --------------------------------
# A toolkit is needed if it is reachable from libslic3r.so via the DT_NEEDED
# closure (direct *or* transitive through other OCCT toolkits).
echo ""
echo "--- OCCT toolkits in $JNI_LIBS ---"
OCCT_SO=("$JNI_LIBS"/libTK*.so)
if [ ${#OCCT_SO[@]} -gt 0 ] && [ -f "${OCCT_SO[0]}" ]; then
  # Seed reachability from libslic3r.so's direct DT_NEEDED.
  declare -A NEEDED=()
  for n in $(readelf -d "$LIB_SO" | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p'); do
    NEEDED["$n"]=1
  done
  # Iteratively expand through OCCT toolkits only (build the full closure).
  changed=1
  while [ "$changed" -eq 1 ]; do
    changed=0
    for so in "${OCCT_SO[@]}"; do
      base="$(basename "$so")"
      [ "${NEEDED[$base]:-0}" -eq 1 ] || continue
      for dep in $(readelf -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p'); do
        if [ "${NEEDED[$dep]:-0}" -ne 1 ]; then
          NEEDED["$dep"]=1
          changed=1
        fi
      done
    done
  done
  for so in "${OCCT_SO[@]}"; do
    base="$(basename "$so" .so)"
    if [ "${NEEDED["$base.so"]:-0}" -eq 1 ]; then
      echo "  NEEDED (reachable): $base"
    else
      echo "  NOT in DT_NEEDED closure: $base  <-- TRIM CANDIDATE"
    fi
  done
else
  echo "  No OCCT .so files found in $JNI_LIBS"
fi

# --- 3. GMP/MPFR/gmpxx (shared .so in jniLibs) -------------------------------
echo ""
echo "--- GMP/MPFR runtime libs ---"
for name in gmp gmpxx mpfr; do
  if [ "${NEEDED["lib$name.so"]:-0}" -eq 1 ]; then
    echo "  NEEDED (reachable): lib$name.so"
  else
    echo "  NOT needed: lib$name.so"
  fi
done

# --- 4. Boost static archives (symbol intersection heuristic) ------------------
echo ""
echo "--- Boost static archives (symbol coverage) ---"

# Get libslic3r.so defined global symbols (from .symtab if present, else .dynsym)
SO_DEFINED="$SCRATCH/so_defined.txt"
# Try static symtab first (unstripped .so), fallback to dynamic
nm --defined-only "$LIB_SO" 2>/dev/null | awk '$2 ~ /^[TDRB]$/ {print $3}' | sort -u > "$SO_DEFINED" || \
  nm -D --defined-only "$LIB_SO" 2>/dev/null | awk '$2 ~ /^[TDRB]$/ {print $3}' | sort -u > "$SO_DEFINED"

SO_DEFINED_COUNT=$(wc -l < "$SO_DEFINED")
echo "  libslic3r.so defined global symbols: $SO_DEFINED_COUNT"

for archive in "$BOOST_LIB"/libboost_*.a; do
  [ -f "$archive" ] || continue
  base="$(basename "$archive" .a)"
  # Get archive's global defined symbols
  nm --defined-only "$archive" 2>/dev/null | awk '$2 ~ /^[TDRB]$/ {print $3}' | sort -u > "$SCRATCH/${base}_defs.txt"
  ARCH_DEFS=$(wc -l < "/tmp/${base}_defs.txt")
  # Intersection
  MATCH=$(comm -12 "/tmp/${base}_defs.txt" "$SO_DEFINED" | wc -l)
  if [ "$MATCH" -eq 0 ]; then
    echo "  NO SYMBOL MATCH: $base ($ARCH_DEFS defined) <-- TRIM CANDIDATE"
  else
    echo "  matched $MATCH/$ARCH_DEFS: $base"
  fi
done

# --- 5. oneTBB static archives -----------------------------------------------
echo ""
echo "--- oneTBB static archives (symbol coverage) ---"
for archive in "$TBB_LIB"/libtbb*.a; do
  [ -f "$archive" ] || continue
  base="$(basename "$archive" .a)"
  nm --defined-only "$archive" 2>/dev/null | awk '$2 ~ /^[TDRB]$/ {print $3}' | sort -u > "$SCRATCH/${base}_defs.txt"
  ARCH_DEFS=$(wc -l < "/tmp/${base}_defs.txt")
  MATCH=$(comm -12 "/tmp/${base}_defs.txt" "$SO_DEFINED" | wc -l)
  if [ "$MATCH" -eq 0 ]; then
    echo "  NO SYMBOL MATCH: $base ($ARCH_DEFS defined) <-- TRIM CANDIDATE"
  else
    echo "  matched $MATCH/$ARCH_DEFS: $base"
  fi
done

# --- Summary -----------------------------------------------------------------
echo ""
echo "=== Summary ==="
echo "Run this after a clean build + fetch. Candidates flagged above may be"
echo "removable from app/CMakeLists.txt (OCCT_LIBS, BOOST_LIBS, tbb entries)."
echo "Verify by editing CMakeLists.txt and re-building (CI or local with rebuild)."

# Cleanup
rm -f "$SO_DEFINED" "$SCRATCH"/*_defs.txt
if [ "$SCRATCH" = "$ROOT/.trim-analysis" ]; then
  rmdir "$SCRATCH" 2>/dev/null || true
fi