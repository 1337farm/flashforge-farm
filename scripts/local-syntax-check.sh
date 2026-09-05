#!/usr/bin/env bash
# scripts/local-syntax-check.sh — fast local replication of the CI "Compile
# libslic3r.so" include/header stage, without compiling or linking.
#
# Runs `-fsyntax-only` over the engine's .cpp/.c sources using the device's own
# clang (which CAN cross-target aarch64-linux-android via the NDK sysroot — the
# NDK's prebuilt host binaries are x86_64 and cannot run on a Termux device).
# This reproduces exactly the "fatal error: 'X' file not found" class of CI
# failures and surfaces ALL of them in one pass, so we don't need a ~1h CI
# round-trip per header issue.
#
# Usage:
#   scripts/local-syntax-check.sh [src.c|src.cpp ...]   # default: all slic3r sources
#
# Env (optional):
#   NDK         Android NDK root (default ~/android-sdk/ndk/23.1.7779620)
#   ABI         target ABI (default arm64-v8a)
#   EXTRA_INC   extra -I dirs (colon-separated) e.g. staged artifact dirs
#   ONLY_FC     1 to print only files with fatal errors (default 0)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"
cd "$ROOT/engine"

NDK="${NDK:-$HOME/android-sdk/ndk/23.1.7779620}"
ABI="${ABI:-arm64-v8a}"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

[ -d "$SYSROOT" ] || { echo "ERROR: NDK sysroot not found at $SYSROOT (set NDK)" >&2; exit 1; }
[ -d src/main/occt/include/$ABI ] || { echo "ERROR: OCCT headers not staged (run scripts/build-debug.sh --fetch-only)" >&2; exit 1; }

# Include dirs exactly as the CI build-engine job sets them for the slic3r target.
INC=(
  -Isrc/main/jni
  -Isrc/main/jni/libslic3r
  -Isrc/main/jni/LibBGCode
  -Isrc/main/jni/eigen
  -Isrc/main/jni/libigl
  -Isrc/main/jniImports/boost/include
  -Isrc/main/jniImports/oneTBB/include
  -Isrc/main/occt/include/$ABI
)
if [ -n "${EXTRA_INC:-}" ]; then
  IFS=':' read -r -a E <<< "$EXTRA_INC"
  for i in "${E[@]}"; do INC+=(-I"$i"); done
fi

COMMON=(--target=aarch64-none-linux-android23 "--sysroot=$SYSROOT" -stdlib=libc++ -std=gnu++17 -fPIC -fsyntax-only "${INC[@]}")

if [ "$#" -gt 0 ]; then
  SOURCES=("$@")
else
  # Extract the source list from the slic3r target in CMakeLists.txt.
  mapfile -t SOURCES < <(
    sed -n '/add_library(slic3r/,/^\s*)/p' CMakeLists.txt \
      | grep -oE 'src/main/jni/[^ )]+\.(c|cpp)' | sort -u
  )
fi

FAIL=0; TOTAL=0; MISSING_HEADERS=""
for s in "${SOURCES[@]}"; do
  [ -f "$s" ] || { echo "!! source missing: $s"; continue; }
  TOTAL=$((TOTAL+1))
  case "$s" in
    *.c)   CC=clang ;;
    *)     CC=clang++ ;;
  esac
  OUT="$("$CC" "${COMMON[@]}" "$s" 2>&1)" || {
    FAIL=$((FAIL+1))
    ERR="$(printf '%s\n' "$OUT" | grep -E 'fatal error:' | head -1)"
    echo "[FAIL] $s  $ERR"
    MISSING_HEADERS+="$(printf '%s\n' "$OUT" | sed -n "s/.*file not found.*'\([^']*\)'.*/\1/p" | sort -u; printf '\n')"
    [ "${ONLY_FC:-0}" = "1" ] && continue
    printf '%s\n' "$OUT" | grep -vE '^\s*[0-9]+ \||note:|warning:' | head -5
  }
done

echo ""
echo "=== $TOTAL sources checked; $FAIL failed ==="
if [ -n "$MISSING_HEADERS" ]; then
  echo "--- unique missing headers ---"
  printf '%s\n' "$MISSING_HEADERS" | grep -v '^$' | sort -u
fi
exit $((FAIL > 0))