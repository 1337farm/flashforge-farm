#!/usr/bin/env bash
# babysit.sh — deterministic babysitting tool for PR checks and post-merge CI.
#
# Encodes two hard-won invariants from the 3.0 bring-up (doc/HOW_TO_WORK.md §3):
#
#   1. Public-repo runs that hit GitHub's supply-chain gate land in
#      `action_required` (zero jobs, "requires approval from a maintainer")
#      instead of running. NOT a result — an unrun. Unblock via
#      `POST /repos/{owner}/{repo}/actions/runs/{id}/approve`; the run then
#      re-attempts. This tool detects those runs and approves them.
#
#   2. Push-to-main workflow triggers have been dead since 2026-09-11; a
#      squash merge creates NO runs on main (verified empty run list on merge
#      commits). Post-merge validation is therefore EXPLICIT: dispatch the
#      workflow(s) on main and babysit them to terminal.
#
# Non-interactive and deterministic: same input + same repo state => same
# behavior and same exit code. It never guesses and never prompts.
#
# Exit codes:
#   0 = green / nothing to do / already terminal-success
#   1 = red (a check or run concluded in failure/cancelled/timed_out)
#   2 = usage error (bad args)
#   3 = blocked (a human must act: conflicts, dispatching not granted,
#       missing evidence on a commit, or timeout)
#
# Usage:
#   scripts/babysit.sh <subcommand> [args]
#
#   status <pr>                Deterministic snapshot: PR state, mergeability,
#                              per-workflow runs on the head SHA, held
#                              (action_required) runs, and required-check
#                              coverage vs branch protection.
#   approve <runID...>         Approve held runs (idempotent: non-held runs
#                              are reported and skipped).
#   approve --sha <sha>        Approve every held run on a commit.
#   held [--sha <sha>]         List runs awaiting approval (optionally scoped
#                              to a commit). Exit 0 when none.
#   pr <N> [--approve]         Babysit PR #N: snapshot, optionally approve
#         [--wait-min M]       held runs, optionally poll until merged or all
#                              checks settle (M minutes; default 0 = snapshot
#                              once, no polling).
#   main [--sha <sha>]         Ensure CI evidence exists for a main commit
#        [--ref main]          (default: latest main). Per workflow, uses the
#        [--workflow W]...     runs actually observed on the SHA.
#        [--approve]           --dispatch triggers missing workflows on the
#        [--dispatch]          ref (workflow_dispatch is the ONLY reliable
#        [--wait-min M]        trigger today — push is dead). Without
#        [--no-download]       --dispatch, missing evidence is BLOCKED (exit3).
#                              On a terminal-green native run, the
#                              FlashForgeFarm-Debug-APK artifact is downloaded
#                              to $APK_STAGE_DIR/<sha>/ (default .babysit-apks,
#                              configurable in .babysitrc; copied to
#                              ~/storage/downloads/ when present). --no-download
#                              disables this.
#
#   Global: --dry-run      print what would be done; never POST/approve/dispatch.
#   Global: --no-download  validate only; never fetch the APK artifact.
#
# Workflows are referenced by path (native-engine-build.yml,
# prusa30-headless.yml) — stable regardless of display-name churn.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SUB="${1:-}"
[ -n "$SUB" ] || { echo "usage: babysit.sh <status|approve|held|pr|main> [args]" >&2; exit 2; }
shift

DRY_RUN=0
APPROVE=0
DISPATCH=0
NO_DOWNLOAD=0
WAIT_MIN=0
REF="main"
SHA=""
APK_RUN_ID=""
APK_ARTIFACT=""
APK_STAGE_DIR=""
declare -a WORKFLOWS=()
declare -a POSARGS=()

i=0
ARGV=("$@")
while [ "$i" -lt "${#ARGV[@]}" ]; do
  a="${ARGV[$i]}"
  case "$a" in
    --dry-run)   DRY_RUN=1 ;;
    --approve)   APPROVE=1 ;;
    --dispatch)  DISPATCH=1 ;;
    --no-download) NO_DOWNLOAD=1 ;;
    --ref)       i=$((i+1)); REF="${ARGV[$i]:-}" ;;
    --ref=*)     REF="${a#--ref=}" ;;
    --sha)       i=$((i+1)); SHA="${ARGV[$i]:-}" ;;
    --sha=*)     SHA="${a#--sha=}" ;;
    --wait-min)  i=$((i+1)); WAIT_MIN="${ARGV[$i]:-}" ;;
    --wait-min=*) WAIT_MIN="${a#--wait-min=}" ;;
    --workflow)  i=$((i+1)); WORKFLOWS+=("${ARGV[$i]:-}") ;;
    --workflow=*) WORKFLOWS+=("${a#--workflow=}") ;;
    --*)         echo "unknown arg: $a" >&2; exit 2 ;;
    *)           POSARGS+=("$a") ;;
  esac
  i=$((i+1))
done

case "$WAIT_MIN" in
  ''|*[!0-9]*) echo "error: --wait-min must be a non-negative integer" >&2; exit 2 ;;
esac

log() { echo "[babysit] $*"; }
err() { echo "[babysit] ERROR: $*" >&2; }

command -v gh >/dev/null 2>&1 || { err "gh CLI required"; exit 1; }
gh auth status >/dev/null 2>&1 || { err "gh not authenticated (run 'gh auth login')"; exit 1; }

REPO="$(git remote get-url origin 2>/dev/null | sed -E 's#\.git$##; s#^.*(github\.com[:/])([^/#]+/[^/#]+)$#\2#' || true)"
[ -n "$REPO" ] || REPO="$(gh repo view 2>/dev/null --json nameWithOwner -q .nameWithOwner || true)"
[ -n "$REPO" ] || { err "cannot resolve repo from git remote"; exit 1; }

# Repo-local babysitting config (shared with 1337farm/pr-babysitter's
# babysit-pr.sh); CLI flags in .babysitrc are overridden by our flags above.
if [ -f "$ROOT/.babysitrc" ]; then
  . "$ROOT/.babysitrc"
fi
APK_ARTIFACT="${APK_ARTIFACT:-FlashForgeFarm-Debug-APK}"
APK_STAGE_DIR="${APK_STAGE_DIR:-.babysit-apks}"

# GitHub's head_sha= filter requires the full 40-hex SHA; normalize --sha once.
if [ -n "$SHA" ]; then
  local_sha="$SHA"
  SHA="$(gh api "repos/$REPO/commits/$SHA" --jq .sha 2>/dev/null || true)"
  [ -n "$SHA" ] || { err "cannot resolve --sha '$local_sha'"; exit 1; }
fi

# --- helpers -----------------------------------------------------------------

# TSV: id \t name \t event \t status \t conclusion \t attempt
runs_for_sha() {
  gh api --paginate "repos/$REPO/actions/runs?head_sha=$1&per_page=100" \
    --jq '.workflow_runs[] | [.id, (.name // ""), .event, .status, (.conclusion // ""), .run_attempt] | @tsv' 2>/dev/null || true
}

# TSV: status \t conclusion \t attempt
run_meta() {
  gh api "repos/$REPO/actions/runs/$1" --jq '[.status, (.conclusion // ""), .run_attempt] | @tsv' 2>/dev/null || true
}

is_held() { # $1 status  $2 conclusion -> 0 if awaiting approval
  [ "$1" = "action_required" ] || [ "$2" = "action_required" ]
}

state_verdict() { # run conclusion token -> RED|PENDING|PASS
  local s
  s="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$s" in
    FAIL|FAILURE|ERROR|CANCELLED|TIMED_OUT) echo RED ;;
    PENDING|NEUTRAL|QUEUED|IN_PROGRESS|ACTION_REQUIRED|EXPECTED) echo PENDING ;;
    *) echo PASS ;;
  esac
}

approve_run() { # $1 run id
  local id="$1" st co meta
  meta="$(run_meta "$id")"
  st="$(printf '%s' "$meta" | cut -f1)"
  co="$(printf '%s' "$meta" | cut -f2)"
  if is_held "$st" "$co"; then
    if [ "$DRY_RUN" -eq 1 ]; then
      log "would approve held run $id (status=$st conclusion=$co)"
      return 0
    fi
    log "approving held run $id (status=$st conclusion=$co)"
    if gh api "repos/$REPO/actions/runs/$id/approve" --method POST >/dev/null; then
      log "approved $id (a fresh attempt will be created)"
    else
      err "approve POST failed for run $id"
      return 1
    fi
  else
    log "run $id not held (status=$st conclusion=$co) — nothing to do"
  fi
}

required_checks() { # -> names required by branch protection on $REF
  gh api "repos/$REPO/branches/$REF/protection" --jq '.required_status_checks.contexts[]' 2>/dev/null || true
}

# 0 when no check is pending AND every protection-required check is present
run_checks_settled() { # $1 pr
  local pending missing=0 rc
  pending="$(gh pr checks "$1" -R "$REPO" --json name,state --jq '[.[] | select(.state=="PENDING" or .state=="NEUTRAL")] | length' 2>/dev/null || true)"
  [ -n "$pending" ] || { return 1; }
  [ "$pending" = "0" ] || { return 1; }
  while IFS= read -r rc; do
    [ -n "$rc" ] || continue
    gh pr checks "$1" -R "$REPO" --json name,state --jq "[.[] | select(.name==\"$rc\") | .name] | length" 2>/dev/null | grep -q '^1$' || missing=1
  done < <(required_checks)
  [ "$missing" -eq 0 ]
}

# Download the APK artifact from a terminal-green native run on $SHA.
# Deterministic: silently no-ops unless (a) a run id is known, (b) it is
# terminal, and (c) it concluded success. Dry-run prints intent only.
maybe_download_apk() { # $1 run id
  local id="$1" st co meta dir apk
  if [ "$NO_DOWNLOAD" -eq 1 ]; then
    log "APK download disabled (--no-download)"
    return 0
  fi
  if [ -z "$id" ]; then
    log "no native run id on $SHA — nothing to download"
    return 0
  fi
  meta="$(run_meta "$id")"
  st="$(printf '%s' "$meta" | cut -f1)"
  co="$(printf '%s' "$meta" | cut -f2)"
  if [ "$st" != "completed" ]; then
    log "run $id not terminal (status=$st) — skipping APK download"
    return 0
  fi
  if [ "$co" != "success" ]; then
    log "run $id not success (conclusion=$co) — skipping APK download"
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    log "would download $APK_ARTIFACT from run $id (dry-run)"
    return 0
  fi
  dir="$ROOT/$APK_STAGE_DIR/$SHA"
  mkdir -p "$dir"
  # --clobber only exists in newer gh releases (Termux ships 2.100.0,
  # which rejects the flag): detect once and pre-clean the artifact dir
  # instead, so re-downloads behave the same everywhere.
  dl_flags=""
  if gh run download --help 2>/dev/null | grep -q -- '--clobber'; then
    dl_flags="--clobber"
  else
    rm -rf "${dir:?}/${APK_ARTIFACT:?}"
  fi
  # Sandboxed runners (Termux) often have no writable system temp dir and gh
  # stages artifact zips in $TMPDIR -> "permission denied". Stage in-repo.
  tmpdir="$ROOT/.babysit-tmp"
  mkdir -p "$tmpdir"
  if TMPDIR="$tmpdir" gh run download "$id" -R "$REPO" -n "$APK_ARTIFACT" -D "$dir" $dl_flags 2>"$dir/.dl.err"; then
    apk="$(find "$dir" -maxdepth 1 -name '*.apk' 2>/dev/null | head -1)"
    if [ -n "$apk" ]; then
      log "APK downloaded: $apk ($(du -h "$apk" | cut -f1))"
      # $HOME may be unset under set -u (non-interactive runners).
      if [ -n "${HOME:-}" ] && [ -d "$HOME/storage/downloads" ]; then
        mkdir -p "$HOME/storage/downloads/FlashForgeFarm-Debug-APK"
        cp -f "$apk" "$HOME/storage/downloads/FlashForgeFarm-Debug-APK/"
        log "copied -> $HOME/storage/downloads/FlashForgeFarm-Debug-APK/$(basename "$apk")"
      else
        log "no ~/storage/downloads (termux-setup-storage); APK left in cache"
      fi
      rm -f "$dir/.dl.err"
      return 0
    fi
    err "artifact downloaded but no .apk under $dir"
    rm -f "$dir/.dl.err"
    return 1
  fi
  cat "$dir/.dl.err" >&2 || true
  rm -f "$dir/.dl.err"
  err "artifact download failed for run $id"
  return 1
}

# --- status ------------------------------------------------------------------

cmd_status() {
  local pr="${POSARGS[0]:-}"
  [ -n "$pr" ] || { err "usage: babysit.sh status <pr>"; exit 2; }
  local prj state head
  prj="$(gh pr view "$pr" -R "$REPO" --json title,state,mergeable,mergeStateStatus,isDraft,headRefOid,mergedAt,mergeCommit 2>/dev/null || true)"
  [ -n "$prj" ] || { err "cannot view PR #$pr"; exit 1; }
  state="$(printf '%s' "$prj" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')"
  head="$(printf '%s' "$prj" | sed -n 's/.*"headRefOid":"\([^"]*\)".*/\1/p')"
  echo "=== PR #$pr ==="
  printf '%s' "$prj" | python3 -c 'import json,sys; d=json.load(sys.stdin); [print(f"{k}: {v}") for k,v in d.items() if k in ("title","state","mergeable","mergeStateStatus","isDraft","headRefOid","mergedAt")]; print("mergeCommit:", (d.get("mergeCommit") or {}).get("oid"))'
  echo "=== runs on head $head ==="
  local rid name ev st co att held=0 red=0 active=0
  while IFS=$'\t' read -r rid name ev st co att; do
    [ -n "$rid" ] || continue
    printf 'run %s\t%s\tevent=%s\tstatus=%s\tconclusion=%s\tattempt=%s\n' "$rid" "$name" "$ev" "$st" "$co" "$att"
    is_held "$st" "$co" && held=1
    case "$(state_verdict "$co")" in RED) red=1 ;; esac
    [ "$st" = "completed" ] || active=1
  done < <(runs_for_sha "$head")
  echo "=== required checks ($REF protection) ==="
  local rc
  while IFS= read -r rc; do
    [ -n "$rc" ] || continue
    local chk; chk="$(gh pr checks "$pr" -R "$REPO" --json name,state --jq ".[] | select(.name==\"$rc\") | .state" 2>/dev/null || true)"
    printf '%s\t%s\n' "$rc" "${chk:-MISSING}"
  done < <(required_checks)
  echo "=== snapshot verdict ==="
  if [ "$state" = "MERGED" ]; then
    local mc; mc="$(gh pr view "$pr" -R "$REPO" --json mergeCommit -q '.mergeCommit.oid')"
    log "PR merged ($mc) — post-merge CI is NOT automatic; run: babysit.sh main --dispatch"
    return 0
  fi
  [ "$held" -eq 1 ]  && log "HELD: some runs await approval (approve: babysit.sh pr $pr --approve)"
  [ "$red" -eq 1 ]   && log "RED: at least one run concluded in failure"
  [ "$active" -eq 1 ] && log "ACTIVE: some runs still in progress"
  if ! run_checks_settled "$pr"; then log "UNSETTLED: checks pending or missing"; fi
  return 0
}

# --- approve -----------------------------------------------------------------

cmd_approve() {
  local ids=() rid name ev st co att
  if [ -n "$SHA" ]; then
    while IFS=$'\t' read -r rid name ev st co att; do
      [ -n "$rid" ] || continue
      is_held "$st" "$co" && ids+=("$rid")
    done < <(runs_for_sha "$SHA")
  else
    ids=("${POSARGS[@]}")
  fi
  [ "${#ids[@]}" -gt 0 ] || { log "no held runs to approve — nothing to do"; return 0; }
  local id rc=0
  for id in "${ids[@]}"; do
    approve_run "$id" || rc=1
  done
  return "$rc"
}

# --- held --------------------------------------------------------------------

cmd_held() {
  local data rid name ev st co att found=0
  if [ -n "$SHA" ]; then
    data="$(runs_for_sha "$SHA")"
  else
    data="$(gh api --paginate "repos/$REPO/actions/runs?per_page=100" \
      --jq '.workflow_runs[] | [.id, (.name // ""), .event, .status, (.conclusion // ""), .run_attempt] | @tsv' 2>/dev/null || true)"
  fi
  while IFS=$'\t' read -r rid name ev st co att; do
    [ -n "$rid" ] || continue
    if is_held "$st" "$co"; then
      printf '%s %s event=%s status=%s conclusion=%s attempt=%s\n' "$rid" "$name" "$ev" "$st" "$co" "$att"
      found=1
    fi
  done <<< "$data"
  [ "$found" -eq 1 ] || log "no held runs"
  return 0
}

# --- pr ----------------------------------------------------------------------

pr_snapshot() { # $1 pr -> MERGED|RED|HELD|PENDING|SETTLED
  local pr="$1" prj state head rid name ev st co att red=0 held=0 active=0
  prj="$(gh pr view "$pr" -R "$REPO" --json state,headRefOid 2>/dev/null || true)"
  state="$(printf '%s' "$prj" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')"
  [ "$state" = "MERGED" ] && { echo MERGED; return 0; }
  head="$(printf '%s' "$prj" | sed -n 's/.*"headRefOid":"\([^"]*\)".*/\1/p')"
  while IFS=$'\t' read -r rid name ev st co att; do
    [ -n "$rid" ] || continue
    is_held "$st" "$co" && held=1
    case "$(state_verdict "$co")" in RED) red=1 ;; esac
    [ "$st" = "completed" ] || active=1
  done < <(runs_for_sha "$head")
  [ "$red" -eq 1 ] && { echo RED; return 0; }
  [ "$held" -eq 1 ] && { echo HELD; return 0; }
  [ "$active" -eq 1 ] && { echo PENDING; return 0; }
  if ! run_checks_settled "$pr"; then echo PENDING; return 0; fi
  echo SETTLED
}

cmd_pr() {
  local pr="${POSARGS[0]:-}"
  [ -n "$pr" ] || { err "usage: babysit.sh pr <N> [--approve] [--wait-min M]"; exit 2; }
  local v mc rid name ev st co att
  v="$(pr_snapshot "$pr")"
  log "PR #$pr initial state: $v"
  case "$v" in
    MERGED)
      mc="$(gh pr view "$pr" -R "$REPO" --json mergeCommit -q '.mergeCommit.oid')"
      log "merged at $mc — post-merge CI is NOT automatic; run: babysit.sh main --dispatch"
      return 0 ;;
    RED)
      err "PR #$pr has a failed run — investigate with 'gh run view <id> --log-failed'"
      return 1 ;;
  esac
  if [ "$APPROVE" -eq 1 ]; then
    log "scanning for held runs on $pr"
    while IFS=$'\t' read -r rid name ev st co att; do
      [ -n "$rid" ] || continue
      is_held "$st" "$co" && approve_run "$rid" || :
    done < <(runs_for_sha "$(gh pr view "$pr" -R "$REPO" --json headRefOid -q .headRefOid)")
    v="$(pr_snapshot "$pr")"
    log "PR #$pr state after approval pass: $v"
  fi
  local deadline now
  deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
  while [ "$WAIT_MIN" -gt 0 ]; do
    sleep 20
    v="$(pr_snapshot "$pr")"
    log "PR #$pr state: $v"
    case "$v" in
      MERGED|RED|SETTLED) break ;;
      *)
        now="$(date +%s)"
        if [ "$now" -ge "$deadline" ]; then err "timeout after ${WAIT_MIN}m (state: $v)"; return 3; fi
        if [ "$APPROVE" -eq 1 ]; then
          while IFS=$'\t' read -r rid name ev st co att; do
            [ -n "$rid" ] || continue
            is_held "$st" "$co" && approve_run "$rid" || :
          done < <(runs_for_sha "$(gh pr view "$pr" -R "$REPO" --json headRefOid -q .headRefOid)")
        fi ;;
    esac
  done
  case "$v" in
    MERGED)
      mc="$(gh pr view "$pr" -R "$REPO" --json mergeCommit -q '.mergeCommit.oid')"
      log "merged at $mc — run 'babysit.sh main --dispatch' for post-merge validation"
      return 0 ;;
    SETTLED)
      log "all checks settled and green"
      return 0 ;;
    HELD)
      err "PR #$pr is HELD for approval — re-run with: babysit.sh pr $pr --approve --wait-min $WAIT_MIN"
      return 3 ;;
    RED)
      err "PR #$pr red"
      return 1 ;;
    PENDING)
      err "PR #$pr still pending (raise --wait-min or poll again)"
      return 3 ;;
  esac
}

# --- main --------------------------------------------------------------------

run_workflow_exists() { # $1 workflow file or name  $2 sha -> latest run id or empty
  gh api "repos/$REPO/actions/runs?head_sha=$2&per_page=100" \
    --jq "[.workflow_runs[] | select((.name==\"$1\") or ((.path // \"\")|endswith(\"$1\"))) | .id][-1]" 2>/dev/null || true
}

dispatch_workflow() { # $1 workflow file -> echo run id once visible
  local wf="$1" id i
  log "dispatching $wf on $REF"
  gh workflow run "$wf" -R "$REPO" --ref "$REF" || { err "dispatch failed for $wf"; return 1; }
  for i in $(seq 1 30); do
    sleep 5
    id="$(run_workflow_exists "$wf" "$SHA")"
    [ -n "$id" ] && [ "$id" != "null" ] && { echo "$id"; return 0; }
  done
  err "dispatched $wf but no run observed on $SHA within 150s"
  return 1
}

cmd_main() {
  [ "${#WORKFLOWS[@]}" -gt 0 ] || WORKFLOWS=("${MAIN_WORKFLOW:-native-engine-build.yml}" "${PRUSA30_WORKFLOW:-prusa30-headless.yml}")
  if [ -z "$SHA" ]; then
    SHA="$(gh api "repos/$REPO/commits/$REF" --jq .sha 2>/dev/null || true)"
  fi
  [ -n "$SHA" ] || { err "cannot resolve $REF"; exit 1; }
  echo "=== main validation target: $REF @ $SHA ==="
  local wf id missing=0 held=0 red=0 i st co meta
  declare -a RUN_IDS=()
  for wf in "${WORKFLOWS[@]}"; do
    id="$(run_workflow_exists "$wf" "$SHA")"
    if [ -z "$id" ] || [ "$id" = "null" ]; then
      if [ "$DISPATCH" -eq 1 ] && [ "$DRY_RUN" -eq 0 ]; then
        id="$(dispatch_workflow "$wf" || true)"
      else
        log "no run observed on $SHA for $wf"
      fi
      [ -n "$id" ] && [ "$id" != "null" ] || missing=1
    else
      log "run $id observed on $SHA for $wf"
    fi
    [ -n "$id" ] && [ "$id" != "null" ] && RUN_IDS+=("$id")
  done
  if [ "$missing" -eq 1 ]; then
    if [ "$DISPATCH" -eq 1 ] && [ "$DRY_RUN" -eq 1 ]; then
      log "dry-run: would dispatch ${WORKFLOWS[*]} on $REF for $SHA"
      return 0
    fi
    err "missing CI evidence on $SHA (push-to-main triggers are dead; dispatch explicitly)."
    err "  scripts/babysit.sh main --sha $SHA --workflow native-engine-build.yml --dispatch --wait-min 30"
    err "  scripts/babysit.sh main --sha $SHA --workflow prusa30-headless.yml --dispatch --wait-min 30"
    return 3
  fi
  local wf_i
  for wf_i in "${!WORKFLOWS[@]}"; do
    case "${WORKFLOWS[$wf_i]}" in
      native-engine-build.yml) [ "${#RUN_IDS[@]}" -gt "$wf_i" ] && APK_RUN_ID="${RUN_IDS[$wf_i]}" ;;
    esac
  done
  [ -z "$APK_RUN_ID" ] || log "native run for APK artifact: $APK_RUN_ID"
  for i in "${RUN_IDS[@]}"; do
    meta="$(run_meta "$i")"
    st="$(printf '%s' "$meta" | cut -f1)"
    co="$(printf '%s' "$meta" | cut -f2)"
    log "run $i: status=$st conclusion=$co"
    if is_held "$st" "$co"; then
      held=1
      [ "$APPROVE" -eq 1 ] && approve_run "$i"
    fi
    case "$(state_verdict "$co")" in RED) red=1 ;; esac
  done
  if [ "$WAIT_MIN" -gt 0 ]; then
    local deadline now
    deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
    while :; do
      st=""; red=0; held=0; active=0
      for i in "${RUN_IDS[@]}"; do
        meta="$(run_meta "$i")"
        st="$(printf '%s' "$meta" | cut -f1)"
        co="$(printf '%s' "$meta" | cut -f2)"
        case "$(state_verdict "$co")" in RED) red=1 ;; esac
        is_held "$st" "$co" && held=1
        [ "$st" = "completed" ] || active=1
      done
      [ "$red" -eq 1 ]  && { err "red run on $SHA"; return 1; }
      [ "$held" -eq 1 ] && { err "held run on $SHA — re-run with --approve"; return 3; }
      if [ "$active" -ne 1 ]; then
        log "all ${#RUN_IDS[@]} runs on $SHA observed terminal ($SHA)"
        maybe_download_apk "$APK_RUN_ID"
        return 0
      fi
      now="$(date +%s)"
      if [ "$now" -ge "$deadline" ]; then err "timeout after ${WAIT_MIN}m — still active"; return 3; fi
      sleep 30
    done
  fi
  [ "$red" -eq 1 ]  && { err "red run on $SHA"; return 1; }
  [ "$held" -eq 1 ] && [ "$APPROVE" -eq 0 ] && { err "held run on $SHA — re-run with --approve"; return 3; }
  log "snapshot: no red, no held on $SHA; use --wait-min to confirm terminal state"
  maybe_download_apk "$APK_RUN_ID"
  return 0
}

case "$SUB" in
  status)  cmd_status ;;
  approve) cmd_approve ;;
  held)    cmd_held ;;
  pr)      cmd_pr ;;
  main)    cmd_main ;;
  *)       echo "unknown subcommand: $SUB" >&2; exit 2 ;;
esac