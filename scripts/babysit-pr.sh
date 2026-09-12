#!/usr/bin/env bash
# scripts/babysit-pr.sh — poll a PR until its checks resolve, then report.
#
# Babysitting means polling, not hoping: this script watches a PR's checks
# until they complete, reports failures/blockers with run URLs, and exits
# nonzero unless the PR is green (or merged). Run it, then fix what it
# reports, then run it again — that is the loop.
#
# Usage:
#   scripts/babysit-pr.sh <PR> [interval_sec=60] [max_polls=60]
#
# Exit codes:
#   0  PR merged, or all checks pass and PR is mergeable
#   1  a check failed (details + run URLs printed)
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

PR="${1:-}"
INTERVAL="${2:-60}"
MAX_POLLS="${3:-60}"
[ -n "$PR" ] || { echo "usage: $0 <PR> [interval_sec] [max_polls]" >&2; exit 3; }
command -v gh >/dev/null || { echo "babysit: gh CLI not found" >&2; exit 3; }

poll=0
EMPTY_CHECKS=0
while [ "$poll" -lt "$MAX_POLLS" ]; do
    poll=$((poll + 1))
    STATE="$(gh pr view "$PR" --json state --jq .state 2>/dev/null || echo UNKNOWN)"
    if [ "$STATE" = "MERGED" ]; then
        echo "babysit: PR #$PR is MERGED. Done."
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
