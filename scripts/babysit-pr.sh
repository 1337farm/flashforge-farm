#!/usr/bin/env bash
# scripts/babysit-pr.sh — poll a PR until its checks resolve, then report.
#
# Babysitting means polling, not hoping: this script watches a PR's checks
# until they complete, reports failures/blockers with run URLs, and exits
# nonzero unless the PR is green (or merged). Run it, then fix what it
# reports, then run it again — that is the loop.
#
# Usage:
#   scripts/babysit-pr.sh <PR> [interval_sec=60] [max_polls=60] [--apk [out-dir]]
#
# With --apk, after the merge the script keeps going: it waits for main's
# native pipeline run for the merge commit (push event or automerge
# dispatch), waits for it to complete, then downloads the built debug APK
# (artifact FlashForgeFarm-Debug-APK, falling back to the farm-apk-latest
# release) into out-dir (default ./apk-out).
#
# Exit codes:
#   0  PR merged (+ APK downloaded with --apk), or all checks pass and mergeable
#   1  a check failed, the main run failed, or the APK download failed
#   2  PR cannot run CI at all (CONFLICTING: GitHub can't build the preview
#      merge ref, so no runs will ever trigger — merge main first)
#   3  PR closed unmerged / usage error / timed out waiting
#
# Notes:
#   - `action_required` 0s runs are gated duplicates (bot-actor push), not
#     results; they are reported and ignored.
#   - "no checks reported" after several polls + MERGEABLE usually means the
#     trigger hasn't fired yet; with CONFLICTING it never will.
set -uo pipefail

[ -d /data/data/com.termux/files/usr/bin ] && export PATH="$PATH:/data/data/com.termux/files/usr/bin"

PR=""
INTERVAL=60
MAX_POLLS=60
WANT_APK=0
APK_DIR="./apk-out"
for arg in "$@"; do
    case "$arg" in
        --apk) WANT_APK=1 ;;
        --apk=*) WANT_APK=1; APK_DIR="${arg#--apk=}" ;;
        *) if [ -z "$PR" ]; then PR="$arg";
           elif [ "$INTERVAL" = "60" ]; then INTERVAL="$arg";
           elif [ "$MAX_POLLS" = "60" ]; then MAX_POLLS="$arg";
           else echo "usage: $0 <PR> [interval_sec] [max_polls] [--apk[=dir]]" >&2; exit 3; fi ;;
    esac
done
[ -n "$PR" ] || { echo "usage: $0 <PR> [interval_sec] [max_polls] [--apk[=dir]]" >&2; exit 3; }
command -v gh >/dev/null || { echo "babysit: gh CLI not found" >&2; exit 3; }

APK_ARTIFACT="FlashForgeFarm-Debug-APK"

# Wait for a workflow run to complete. Prints final status, exits nonzero
# unless the run concluded successfully.
wait_for_run() {
    local run_id="$1" phase="$2" n=0 status="" concl=""
    while [ "$n" -lt "$MAX_POLLS" ]; do
        n=$((n + 1))
        status="$(gh run view "$run_id" --json status --jq .status 2>/dev/null || echo UNKNOWN)"
        concl="$(gh run view "$run_id" --json conclusion --jq .conclusion 2>/dev/null || echo "")"
        if [ "$status" = "completed" ]; then
            echo "babysit: $phase run $run_id completed: ${concl:-unknown}"
            [ "$concl" = "success" ] && return 0 || return 1
        fi
        echo "babysit: $phase run $run_id $status... [$n/$MAX_POLLS]"
        sleep "$INTERVAL"
    done
    echo "babysit: timed out waiting for $phase run $run_id." >&2
    return 3
}

# After the merge: find main's native pipeline run for the merge commit,
# wait for it, and download the built APK.
fetch_merge_apk() {
    local pr="$1" n=0 sha="" run_id=""
    sha="$(gh pr view "$pr" --json mergeCommit --jq .mergeCommit.oid 2>/dev/null || echo "")"
    [ -n "$sha" ] || { echo "babysit: cannot resolve merge commit for PR #$pr." >&2; return 3; }
    echo "babysit: PR #$pr merged as $sha; waiting for main's native pipeline..."
    while [ "$n" -lt "$MAX_POLLS" ]; do
        n=$((n + 1))
        run_id="$(gh run list --workflow=native-engine-build.yml --branch main --limit 10 \
            --json databaseId,headSha,status \
            --jq "[.[] | select(.headSha == \"$sha\")] | .[0].databaseId // empty" 2>/dev/null || echo "")"
        [ -n "$run_id" ] && break
        echo "babysit: no main run for $sha yet... [$n/$MAX_POLLS]"
        sleep "$INTERVAL"
    done
    [ -n "$run_id" ] || { echo "babysit: no main pipeline run appeared for $sha." >&2; return 3; }
    echo "babysit: main run: https://github.com/1337farm/flashforge-farm/actions/runs/$run_id"
    wait_for_run "$run_id" "main" || {
        echo "babysit: main run failed — APK not published. See run URL above." >&2
        return 1
    }
    mkdir -p "$APK_DIR"
    if gh run download "$run_id" -n "$APK_ARTIFACT" -D "$APK_DIR" 2>/dev/null; then
        echo "babysit: APK downloaded to $APK_DIR:"
        ls -la "$APK_DIR"/*.apk
        return 0
    fi
    echo "babysit: artifact gone (retention?); falling back to farm-apk-latest release..."
    gh release download farm-apk-latest --pattern '*.apk' --dir "$APK_DIR" --clobber || {
        echo "babysit: APK download failed from artifact and release." >&2
        return 1
    }
    ls -la "$APK_DIR"/*.apk
    return 0
}

poll=0
EMPTY_CHECKS=0
while [ "$poll" -lt "$MAX_POLLS" ]; do
    poll=$((poll + 1))
    STATE="$(gh pr view "$PR" --json state --jq .state 2>/dev/null || echo UNKNOWN)"
    if [ "$STATE" = "MERGED" ]; then
        echo "babysit: PR #$PR is MERGED."
        if [ "$WANT_APK" = "1" ]; then
            fetch_merge_apk "$PR"
            exit $?
        fi
        echo "babysit: Done."
        exit 0
    fi
    if [ "$STATE" = "CLOSED" ]; then
        echo "babysit: PR #$PR is CLOSED unmerged." >&2
        exit 3
    fi
    MERGEABLE="$(gh pr view "$PR" --json mergeable --jq .mergeable 2>/dev/null || echo UNKNOWN)"
    if [ "$MERGEABLE" = "CONFLICTING" ]; then
        echo "babysit: PR #$PR is CONFLICTING — GitHub cannot build the preview merge ref," >&2
        echo "babysit: so NO CI will ever trigger. Merge main into the branch and push first." >&2
        exit 2
    fi
    CHECKS="$(gh pr checks "$PR" 2>&1)"
    if printf '%s\n' "$CHECKS" | grep -q "no checks reported"; then
        EMPTY_CHECKS=$((EMPTY_CHECKS + 1))
        echo "babysit: [$poll/$MAX_POLLS] no checks reported yet (trigger pending?)..."
        if [ "$EMPTY_CHECKS" -ge 5 ]; then
            echo "babysit: still no checks after $EMPTY_CHECKS polls — verify the push landed and paths filters match." >&2
            exit 3
        fi
        sleep "$INTERVAL"
        continue
    fi
    echo "babysit: [$poll/$MAX_POLLS] checks on PR #$PR:"
    printf '%s\n' "$CHECKS" | head -15
    if printf '%s\n' "$CHECKS" | grep -Eq "^[^[:space:]]+[[:space:]]+fail"; then
        echo "babysit: FAILURES detected:" >&2
        printf '%s\n' "$CHECKS" | grep -E "^[^[:space:]]+[[:space:]]+fail" >&2
        echo "babysit: investigate with: gh run view <run-id> --log-failed" >&2
        exit 1
    fi
    if printf '%s\n' "$CHECKS" | grep -Eq "pending|queued|in_progress|waiting|requested"; then
        echo "babysit: still running; sleeping ${INTERVAL}s..."
        sleep "$INTERVAL"
        continue
    fi
    echo "babysit: all reported checks pass on PR #$PR."
    exit 0
done
echo "babysit: timed out after $MAX_POLLS polls." >&2
exit 3
