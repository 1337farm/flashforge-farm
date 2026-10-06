#!/usr/bin/env bash
# scripts/ui-probe.sh — fast on-device UI check for settings screens.
# Usage: ui-probe.sh [DEVICE] [TAPS...]
#   Each TAP is "x,y". After each tap the script waits for UI idle by
#   retrying uiautomator dump (no fixed sleeps), then prints visible texts
#   and any app FATALs. One invocation replaces a dozen manual adb roundtrips.
set -euo pipefail
DEV="${1:-127.0.0.1:5555}"
shift || true
PKG="${PKG:-com.flashforge.farm}"
OUT="${OUT:-/tmp/ui-probe}"
mkdir -p "$OUT"

# Abort instead of tapping blindly when our app is not in front.
require_focus() {
    local tries=0
    while [ $tries -lt 5 ]; do
        if adb -s "$DEV" shell "dumpsys activity activities" 2>/dev/null \
            | grep -q "mFocusedApp=.*$PKG"; then
            return 0
        fi
        tries=$((tries + 1))
        sleep 2
    done
    echo "PROBE-ABORT: $PKG not in foreground, refusing to tap" >&2
    return 1
}

dump() {
    local tag="$1" tries=0
    while [ $tries -lt 10 ]; do
        if adb -s "$DEV" shell uiautomator dump "/sdcard/$tag.xml" >/dev/null 2>&1; then
            adb -s "$DEV" pull "/sdcard/$tag.xml" "$OUT/$tag.xml" >/dev/null 2>&1 \
                && grep -q "$PKG" "$OUT/$tag.xml" && return 0
        fi
        tries=$((tries + 1))
        sleep 2
    done
    echo "PROBE-FAIL: dump $tag failed" >&2
    return 1
}

texts() {
    python3 - "$OUT/$1.xml" <<'EOF'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
seen = set()
for m in re.finditer(r'<node[^>]*text="([^"]+)"[^>]*bounds="([^"]+)"', x):
    t, b = m.groups()
    if t.strip() and (t, b) not in seen:
        seen.add((t, b))
        print(t + ' | ' + b)
EOF
}

crashes() {
    adb -s "$DEV" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -E "FATAL|flashforge" | head -5 || true
}

i=0
require_focus || exit 2
for tap in "$@"; do
    i=$((i + 1))
    require_focus || exit 2
    adb -s "$DEV" shell input tap "${tap%,*}" "${tap#*,}"
    dump "step$i"
    echo "--- after tap $tap ---"
    texts "step$i" | head -30
done
echo "--- crash check ---"
crashes
echo PROBE-DONE
