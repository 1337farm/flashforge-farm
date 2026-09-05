#!/usr/bin/env bash
# ensure-apk.sh — get a COMPLETE Debug APK (with all required native .so libs)
# for the current commit, wired together as a single pipeline:
#
#   1. Check the latest build hash on GitHub ("the proper hash").
#   2. If CI has a successful run for EXACTLY this commit -> download the APK
#      bundle ("FlashForgeFarm-Debug-APK" artifact) instead of building.
#   2b. Else fall back to the rolling farm-apk-latest release (sha-verified;
#      artifacts expire after 7 days, releases persist). Commit mismatch only
#      warns — the .so assertion still gates the copy.
#   3. Otherwise build locally: stage the engine bundle + native deps (via
#      scripts/fetch-native-deps.sh) so Gradle compiles the thin libfarm.so JNI
#      bridge and assembles the APK.
#   4. ALWAYS assert the resulting APK ships every required native .so.
#
# The APK is copied to ~/storage/downloads/FlashForgeFarm-Debug-APK/ only after
# the .so assertion passes (never install a half-built/black-screen APK).
#
# Usage:
#   scripts/ensure-apk.sh [--no-assert] [--download-only] [--build-only]
#
#   --no-assert   skip the .so assertion (use only when debugging the scripts)
#   --download-only  download CI bundle if hash matches, never build locally
#   --build-only     always build locally, never download
#   --run-id=<id>    force a specific CI workflow run for hash matching
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
ROOT="$(cd "$SCRIPT_DIR/.."; pwd)"

ASSERT=1
DOWNLOAD_ONLY=0
BUILD_ONLY=0
RUN_ID_OPT=""
for a in "$@"; do
  case "$a" in
    --no-assert)    ASSERT=0 ;;
    --download-only) DOWNLOAD_ONLY=1 ;;
    --build-only)   BUILD_ONLY=1 ;;
    --run-id=*)     RUN_ID_OPT="${a#--run-id=}" ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

cd "$ROOT"

WORKFLOW="native-engine-build.yml"
APK_ARTIFACT="FlashForgeFarm-Debug-APK"
LOCAL_SHA="$(git rev-parse --short=10 HEAD)"
APK_DIR="$ROOT/app/build/outputs/apk/debug"
DL_CACHE="$ROOT/.apk-cache/$LOCAL_SHA"

# Native libs the app requires at runtime. Keep in sync with
# Native.java / OCCTLoader.java. libslic3r.so is the engine and MUST be present
# (black screen / boot crash otherwise); libfarm.so is this app's JNI bridge.
REQUIRED_SO="libslic3r.so libfarm.so libc++_shared.so libgmp.so libgmpxx.so libmpfr.so"
OCCT_SO="TKDESTEP TKXCAF TKCAF TKLCAF TKCDF TKV3d TKMesh TKXMesh TKXSBase TKService TKBO TKPrim TKHLR TKShHealing TKTopAlgo TKGeomAlgo TKGeomBase TKBRep TKG3d TKG2d TKMath TKernel"
REQUIRED_SO="$REQUIRED_SO $(echo "$OCCT_SO" | xargs -n1 printf 'lib%s.so ')"

ABI="arm64-v8a"

log()  { echo "[ensure-apk] $*"; }
err()  { echo "[ensure-apk] ERROR: $*" >&2; }

# --- artifact discovery ------------------------------------------------------
command -v gh >/dev/null 2>&1 || { err "gh CLI required"; exit 1; }
gh auth status >/dev/null 2>&1 || { err "gh not authenticated (run 'gh auth login')"; exit 1; }

# Find a successful workflow run building EXACTLY $LOCAL_SHA ("proper hash").
find_successful_run_for_commit() {
  local sha="$1" run
  run="$(gh run list --workflow "$WORKFLOW" --status success --limit 50 \
    --json headSha,databaseId \
    --jq "[.[] | select(.headSha[0:10]==\"$sha\") | .databaseId][0]" 2>/dev/null || true)"
  if [ -n "$run" ] && [ "$run" != "null" ]; then
    printf '%s' "$run"
  fi
  return 0
}

# --- .so assertion on a produced APK ------------------------------------------
assert_apk_so() {
  local apk="$1" missing=0 name
  if [ "$ASSERT" -eq 0 ]; then
    log "assert skipped (--no-assert)"
    return 0
  fi
  [ -f "$apk" ] || { err "no APK at $apk"; exit 1; }
  local listing
  listing="$(unzip -l "$apk" 2>/dev/null)"
  for name in $REQUIRED_SO; do
    if ! echo "$listing" | grep -q "lib/$ABI/$name"; then
      err "missing required native lib lib/$ABI/$name in $apk"
      missing=1
    fi
  done
  if [ "$missing" -eq 1 ]; then
    err "APK is INCOMPLETE (probably build with -PskipNativeRebuild and no staged libslic3r.so)."
    err "Use scripts/ensure-apk.sh so CI deps/APK are used, or stage deps first."
    exit 1
  fi
  log "assert: lib/$ABI/*.so complete ($(echo "$REQUIRED_SO" | wc -w) libs)"
}

# --- publish APK to Downloads ------------------------------------------------
publish_apk() {
  local apk="$1"
  assert_apk_so "$apk"
  local dest="$HOME/storage/downloads/FlashForgeFarm-Debug-APK"
  if [ -d "$HOME/storage/downloads" ]; then
    mkdir -p "$dest"
    cp -f "$apk" "$dest/"
    log "copied -> $dest/$(basename "$apk")"
  else
    log "no ~/storage/downloads (termux-setup-storage); APK left at $apk"
  fi
}

# --- path 1: download CI bundle when the hash matches -------------------------
if [ "$BUILD_ONLY" -eq 0 ] && [ -z "$(find "$DL_CACHE" -name 'FlashForgeFarm_*.apk' 2>/dev/null | head -1)" ]; then
  RUN_ID="${RUN_ID_OPT:-$(find_successful_run_for_commit "$LOCAL_SHA")}"
  if [ -n "$RUN_ID" ]; then
    log "CI built commit $LOCAL_SHA (run #$RUN_ID) -> downloading APK bundle"
    mkdir -p "$DL_CACHE"
    if gh run download "$RUN_ID" -n "$APK_ARTIFACT" -D "$DL_CACHE" 2>"$DL_CACHE/.dl.err"; then
      APK="$(find "$DL_CACHE" -name 'FlashForgeFarm_*.apk' | head -1)"
      if [ -n "$APK" ]; then
        publish_apk "$APK"
        exit 0
      fi
    else
      cat "$DL_CACHE/.dl.err" >&2
    fi
    rm -f "$DL_CACHE/.dl.err"
    log "bundle download failed/empty; falling back to local build"
  else
    log "no successful CI run for commit $LOCAL_SHA yet; building locally"
  fi
fi

# --- path 1b: rolling APK release (any commit; artifacts expire, releases persist)
if [ "$BUILD_ONLY" -eq 0 ] && [ -z "$(find "$DL_CACHE" -name 'FlashForgeFarm_*.apk' 2>/dev/null | head -1)" ]; then
  mkdir -p "$DL_CACHE"
  if gh release download farm-apk-latest \
       --pattern 'FlashForgeFarm_*.apk' --pattern 'apk-manifest.json' \
       --dir "$DL_CACHE" --clobber 2>"$DL_CACHE/.rel.err"; then
    APK="$(find "$DL_CACHE" -name 'FlashForgeFarm_*.apk' | head -1)"
    MAN="$DL_CACHE/apk-manifest.json"
    if [ -n "$APK" ] && [ -f "$MAN" ]; then
      WANT="$(python3 -c "import json,glob,os; m=json.load(open('$MAN')); n=[k for k in m['files'] if k.endswith('.apk')][0]; print(m['files'][n])" 2>/dev/null || true)"
      GOT="$(sha256sum "$APK" | cut -d' ' -f1)"
      BUILT="$(python3 -c "import json; print(json.load(open('$MAN')).get('commit','?'))" 2>/dev/null || echo ?)"
      if [ -n "$WANT" ] && [ "$WANT" = "$GOT" ]; then
        if [ "$BUILT" = "$LOCAL_SHA" ]; then
          log "release APK verified, built for this commit ($BUILT)"
        else
          log "WARNING: release APK is for commit $BUILT, not $LOCAL_SHA; using it (assert still enforced)"
        fi
        publish_apk "$APK"
        exit 0
      else
        err "release APK sha mismatch; ignoring"
      fi
    fi
  else
    cat "$DL_CACHE/.rel.err" >&2 || true
  fi
  rm -f "$DL_CACHE/.rel.err"
  log "no usable release APK; continuing"
fi

if [ "$DOWNLOAD_ONLY" -eq 1 ]; then
  err "--download-only requested but no bundle available for $LOCAL_SHA"
  exit 1
fi

# --- path 2: build locally from staged CI deps --------------------------------
log "local build path (commit $LOCAL_SHA)"
RUN_ID="${RUN_ID_OPT:-$(find_successful_run_for_commit "$LOCAL_SHA")}"
if [ -n "$RUN_ID" ]; then
  log "staging deps from matching run #$RUN_ID"
  RUN_ID="$RUN_ID" scripts/fetch-native-deps.sh
else
  log "no matching successful run; fetch-native-deps will auto-pick latest"
  log "WARNING: staged engine may be for a different commit than $LOCAL_SHA"
  scripts/fetch-native-deps.sh || true
fi

if [ ! -f "engine/output/arm64-v8a/libslic3r.so" ]; then
  err "libslic3r.so not staged (engine build output missing?) — cannot assemble a complete APK"
  err "Run scripts/fetch-native-deps.sh, or run CI so it builds commit $LOCAL_SHA, then retry."
  exit 1
fi

log "assembling APK (libfarm.so compiled by CMake against staged engine + deps)"
./gradlew :app:assembleDebug --console=plain

APK="$(find "$APK_DIR" -name 'FlashForgeFarm_*.apk' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
[ -n "$APK" ] || { err "no APK produced under $APK_DIR"; exit 1; }

publish_apk "$APK"
log "done. APK: $APK"