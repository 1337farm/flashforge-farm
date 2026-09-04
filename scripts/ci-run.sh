#!/usr/bin/env bash
# ci-run.sh — drive the "Build Native Engine (from source)" workflow for any PR.
#
# Usage:
#   scripts/ci-run.sh <pr-number> [--watch] [--status] [--logs] [--show-fail]
#                      [--stage] [--apk]
#
# Actions (default: --status):
#   --status     Show the CI check status for the PR.
#   --logs       Print the tail of every (failed) job's log for the PR.
#   --show-fail  Fetch + print failed job logs and download dep-build-logs.
#   --watch      Poll until the workflow finishes, then act on the result.
#   --stage      Download libslic3r + native-deps artifacts and stage them into
#                the local source tree so `./gradlew assembleDebug
#                -PskipNativeRebuild=true` works offline.
#   --apk        Download the FlashForgeFarm-Debug-APK artifact (user testing).
#
# Examples:
#   scripts/ci-run.sh 76 --status
#   scripts/ci-run.sh 76 --watch --show-fail --stage --apk
set -euo pipefail

cd "$(dirname "$0")/.."

PR="${1:?usage: ci-run.sh <pr-number> [--watch|--status|--logs|--show-fail|--stage|--apk]}"
shift

WATCH=0 STATUS=0 LOGS=0 SHOW_FAIL=0 STAGE=0 APK=0
for a in "$@"; do
  case "$a" in
    --watch)     WATCH=1 ;;
    --status)    STATUS=1 ;;
    --logs)      LOGS=1 ;;
    --show-fail) SHOW_FAIL=1 ;;
    --stage)     STAGE=1 ;;
    --apk)       APK=1 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done
if [ "$STATUS" -eq 0 ] && [ "$LOGS" -eq 0 ] && [ "$SHOW_FAIL" -eq 0 ] && [ "$WATCH" -eq 0 ] && [ "$STAGE" -eq 0 ] && [ "$APK" -eq 0 ]; then
  STATUS=1
fi

require_gh() {
  command -v gh >/dev/null 2>&1 || { echo "gh CLI required" >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || { echo "gh not authenticated" >&2; exit 1; }
}
require_gh

ART_DIR="${CI_RUN_ARTIFACTS:-$PWD/.ci-artifacts}"
mkdir -p "$ART_DIR"

run_id_for_pr() {
  gh pr view "$PR" --json headRefName,baseRefName -q '{h:.headRefName,b:.baseRefName}' \
    | tr -d '{}" ' | tr ':', ',' >/dev/null
  # Latest workflow run that matches this PR's head SHA.
  local sha
  sha="$(gh pr view "$PR" --json headRefOid -q .headRefOid)"
  gh run list --workflow native-engine-build.yml --commit "$sha" --limit 1 --json databaseId \
    -q '.[0].databaseId'
}

run_id="$(run_id_for_pr || true)"
if [ -n "$run_id" ]; then
  echo "workflow run: $run_id"
else
  echo "no native-engine-build run found for PR #$PR (not triggered?)" >&2
fi

if [ "$STATUS" -eq 1 ]; then
  echo "=== PR #$PR checks ==="
  gh pr checks "$PR" 2>&1 || true
fi

[ "$WATCH" -eq 1 ] && [ -n "$run_id" ] && {
  echo "=== watching run $run_id (every 30s; stops as soon as the run is done) ==="
  # Poll the run status ourselves so we can stop EARLY the moment the run is
  # terminal (completed = success OR failure, regardless of any fail/pass/skip
  # mix), and print a clear conclusion instead of relying on gh's exit codes.
  while :; do
    L="$(gh run view "$run_id" --json status,conclusion 2>/dev/null)"
    ST="$(printf '%s' "$L" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
    CO="$(printf '%s' "$L" | sed -n 's/.*"conclusion":"\([^"]*\)".*/\1/p')"
    case "$ST" in
      completed)
        case "$CO" in
          success)
            echo "=== run $run_id COMPLETED: SUCCESS ===";;
          *.|failure|cancelled|timed_out|action_required)
            echo "=== run $run_id COMPLETED: $CO ===" ;;
          *)
            echo "=== run $run_id COMPLETED: $CO (some jobs skipped/failed) ===" ;;
        esac
        break;;
      *)
        echo "[$(date +%H:%M:%S)] status=$ST conclusion=${CO:-pending} (still running)"; sleep 30;;
    esac
  done
}

if [ "$LOGS" -eq 1 ] && [ -n "$run_id" ]; then
  echo "=== failed job logs (tail) for run $run_id ==="
  gh run view "$run_id" --json jobs \
    -q '.jobs[] | select(.conclusion=="failure") | .databaseId' | while read -r jid; do
    [ -z "$jid" ] && continue
    gh run view "$run_id" --job "$jid" --log-failed 2>/dev/null \
      | tail -80
  done
fi

if [ "$SHOW_FAIL" -eq 1 ] && [ -n "$run_id" ]; then
  echo "=== downloading dep-build-logs (if produced) ==="
  gh run download "$run_id" -n dep-build-logs -D "$ART_DIR/dep-build-logs" 2>/dev/null \
    && echo "dep logs -> $ART_DIR/dep-build-logs" || echo "(no dep-build-logs artifact)"
fi

if [ "$STAGE" -eq 1 ] && [ -n "$run_id" ]; then
  echo "=== downloading artifacts for local hybrid build ==="
  # Delegates to scripts/fetch-native-deps.sh: downloads native-deps + libslic3r
  # and stages Boost/OCCT/oneTBB/GMP into the source tree (cached per run id).
  RUN_ID="$run_id" scripts/fetch-native-deps.sh || \
    echo "WARNING: fetch-native-deps.sh failed" >&2
fi

if [ "$APK" -eq 1 ] && [ -n "$run_id" ]; then
  echo "=== downloading FlashForgeFarm-Debug-APK ==="
  gh run download "$run_id" -n FlashForgeFarm-Debug-APK -D "$ART_DIR/apk" 2>/dev/null \
    && echo "APK -> $ART_DIR/apk" || echo "(no APK artifact; package-apk failed/skipped?)"
fi

echo "done."
