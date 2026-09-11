#!/usr/bin/env bash
# Fetch the PrusaSlicer 3.0 engine source (master == version_3.0.0-alpha*) into
# $1 (default: $ENGINE_PRUSA_DIR) and apply our headless-build patch series.
#
# The engine source is intentionally NOT committed to this repo: we fetch the
# PRUSA_REF-pinned upstream tree at build time so the build always tracks the
# pinned commit (default: latest 3.0-alpha) and upgrades land by bumping the pin
# in one place. `./engine/...` holds only scripts/patches, not upstream code.
#
# Env overrides:
#   PRUSA_REF      ref/commit to fetch (default: version_3.0.0-alpha11 pin)
#   PRUSA_DIR      destination dir (default: engine/build/prusaslicer-src)
#   PRUSA_SHALLOW  1 => --depth 1 fetch (default 1)
set -euo pipefail

PRUSA_REF="${PRUSA_REF:-6f510128d7c2e543b62919b74bea7e876f564205}"
DEFAULT_DIR="$(dirname "$0")/../build/prusaslicer-src"
PRUSA_DIR="${PRUSA_DIR:-$DEFAULT_DIR}"
PRUSA_SHALLOW="${PRUSA_SHALLOW:-1}"
PATCHES_DIR="$(dirname "$0")/patches"
REPO_URL="https://github.com/prusa3d/PrusaSlicer.git"

log() { printf '[fetch-prusa] %s\n' "$*" >&2; }

if [ -d "$PRUSA_DIR/.git" ]; then
    log "reusing existing checkout at $PRUSA_DIR (fetch+reset to pin)"
    git -C "$PRUSA_DIR" fetch --quiet --depth "${PRUSA_SHALLOW}" origin "$PRUSA_REF" || \
        git -C "$PRUSA_DIR" fetch --quiet origin "$PRUSA_REF"
    git -C "$PRUSA_DIR" reset --hard -q FETCH_HEAD
    git -C "$PRUSA_DIR" clean -fdq
else
    log "cloning PrusaSlicer master at $PRUSA_REF into $PRUSA_DIR"
    DEPTH_ARGS="--depth ${PRUSA_SHALLOW}"
    # Full clone depth 1 then reset onto the pinned ref keeps history tiny while
    # pinning to the exact commit (so reproducible builds, latest by default).
    git clone --quiet $DEPTH_ARGS --branch master "$REPO_URL" "$PRUSA_DIR"
    if ! git -C "$PRUSA_DIR" rev-list -1 HEAD | grep -q "^$PRUSA_REF"; then
        git -C "$PRUSA_DIR" fetch --quiet --depth "${PRUSA_SHALLOW}" origin "$PRUSA_REF" || \
            git -C "$PRUSA_DIR" fetch --quiet origin "$PRUSA_REF"
        git -C "$PRUSA_DIR" checkout -q -f "$PRUSA_REF"
    fi
fi

log "applying patches from $PATCHES_DIR"
for p in "$PATCHES_DIR"/*.patch; do
    [ -e "$p" ] || { log "no patches present"; break; }
    log "  apply $(basename "$p")"
    git -C "$PRUSA_DIR" apply --check "$p"
    git -C "$PRUSA_DIR" apply "$p"
done

log "engine source ready at $PRUSA_DIR ($(git -C "$PRUSA_DIR" rev-parse --short HEAD))"