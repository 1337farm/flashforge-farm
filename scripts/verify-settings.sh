#!/usr/bin/env bash
# scripts/verify-settings.sh — text-anchored on-device settings verification.
# No guessed coordinates: every tap target is located by its visible text in
# a fresh uiautomator dump. Asserts expected rows per tab and fails on any
# app FATAL. Screenshots are captured ONLY on failure.
#
# Usage: scripts/verify-settings.sh [DEVICE] [PKG]
# Exit 0 = all screens render with expected rows, no crashes.
set -euo pipefail
DEV="${1:-127.0.0.1:5555}"
PKG="${2:-com.flashforge.farm}"
OUT="${OUT:-/tmp/verify-settings}"
mkdir -p "$OUT"
FAIL=0

adb_() { adb -s "$DEV" "$@"; }

dump() { # $1 = tag; writes $OUT/$1.xml; retries until UI idle
    local tag="$1" tries=0
    while [ $tries -lt 10 ]; do
        if adb_ shell uiautomator dump "/sdcard/vs-$tag.xml" >/dev/null 2>&1 \
            && adb_ pull "/sdcard/vs-$tag.xml" "$OUT/$tag.xml" >/dev/null 2>&1; then
            return 0
        fi
        tries=$((tries + 1)); sleep 2
    done
    echo "FAIL: ui dump '$tag' never became idle" >&2
    return 1
}

# tap_text <dump-tag> <exact-text> : taps center of first node with text
tap_text() {
    local tag="$1" want="$2"
    local bounds
    bounds=$(python3 - "$OUT/$tag.xml" "$want" <<'EOF'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
want = sys.argv[2]
for m in re.finditer(r'<node[^>]*text="' + re.escape(want) + r'"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"', x):
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
EOF
)
    if [ -z "$bounds" ]; then echo "FAIL: text '$want' not on screen ($tag)" >&2; return 1; fi
    # shellcheck disable=SC2086
    adb_ shell input tap $bounds
}

# assert_texts <dump-tag> <expected...>: every expected string must be visible
assert_texts() {
    local tag="$1"; shift
    local missing=0
    for want in "$@"; do
        if ! grep -q -F "$want" "$OUT/$tag.xml"; then
            echo "FAIL: [$tag] missing '$want'" >&2
            missing=1
        fi
    done
    return $missing
}

shot_on_fail() { # $1 = tag
    adb_ exec-out screencap -p > "$OUT/FAIL-$1.png" 2>/dev/null || true
    echo "screenshot: $OUT/FAIL-$1.png" >&2
}

check_crash() {
    if adb_ logcat -d -s AndroidRuntime:E 2>/dev/null | grep -q "Process: $PKG"; then
        echo "FAIL: FATAL from $PKG" >&2
        adb_ logcat -d -s AndroidRuntime:E 2>/dev/null | grep -A4 "Process: $PKG" | head -8 >&2
        return 1
    fi
    return 0
}

echo "== focus app =="
adb_ shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 5
adb_ logcat -c
dump launch || exit 1

echo "== Print / Quality =="
tap_text launch "Print" || { shot_on_fail launch; exit 1; }
sleep 3; dump quality || exit 1
assert_texts quality "Layer height" "Line width" || { FAIL=1; shot_on_fail quality; }
check_crash || { FAIL=1; shot_on_fail quality-crash; }

echo "== Print / Strength =="
tap_text quality "Strength" || { shot_on_fail quality; exit 1; }
sleep 3; dump strength || exit 1
assert_texts strength "Walls" "Top/bottom shells" || { FAIL=1; shot_on_fail strength; }
check_crash || { FAIL=1; shot_on_fail strength-crash; }

if [ "$FAIL" = 0 ]; then echo "VERIFY-OK"; else echo "VERIFY-FAILED"; fi
exit "$FAIL"
