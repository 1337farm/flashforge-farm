#!/usr/bin/env bash
# scripts/strip-so.sh — strip unneeded symbols from Android .so files in place.
# Unstripped NDK builds carry full debug info (libslic3r.so ~52MB); stripping
# typically halves native payload size with no runtime effect (our crash dumps
# are address-based, not symbolized on-device).
#
# Usage: scripts/strip-so.sh <file.so> [more.so ...]
# Env:   ANDROID_NDK_ROOT (default $ANDROID_SDK_ROOT/ndk/23.1.7779620)
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/23.1.7779620}"
STRIP="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || { echo "[strip] ERROR: llvm-strip not found in $ANDROID_NDK_ROOT" >&2; exit 1; }
[ "$#" -gt 0 ] || { echo "[strip] usage: strip-so.sh <file.so>..." >&2; exit 1; }

for f in "$@"; do
    [ -f "$f" ] || { echo "[strip] ERROR: not found: $f" >&2; exit 1; }
    before="$(stat -c%s "$f")"
    "$STRIP" --strip-unneeded "$f"
    after="$(stat -c%s "$f")"
    echo "[strip] $f: $before -> $after bytes"
done
