# How to work (agent directive)

Distilled from the PrusaSlicer 3.0 bring-up sessions (Sept 2026): every rule
below paid for itself in a merged PR or a diagnosed red run. Follow it
verbatim; do not improvise process.

## 1. The loop

All engine bring-up is **PR-driven compile-fix**: small focused change →
PR → red CI enumerates the next exact error → fix → next PR. A red run is
output, not failure. Never batch unrelated concerns into one PR (one dep or
one fix per PR keeps each CI cycle attributable).

Do not end a turn with red CI unaddressed. Babysit every PR you open
through merge (see §3). If blocked, hand off exact state, never silence.

## 2. Branch discipline

- One change = one fresh branch off `origin/main`: `git checkout -qb
  fix/<topic> origin/main`. Never reuse a merged branch, never push to main.
- **Rebase before opening a PR.** If a rebase starts replaying ancient
  history (dozens of commits, `AA`/`UU` on files you never touched), ABORT
  it — the branch has archaeology. Cherry-pick only your commits onto a
  fresh branch instead.
- Squash-merges duplicate content: after main absorbs a squash, your old
  branch will show phantom diffs. Do not "fix" them by hand — merge main
  once to collapse, or start fresh.
- `CONFLICTING` means **CI never triggers** (GitHub can't build the preview
  merge ref). Fix first, always: fetch main, merge, resolve, push.
- Merged branches are auto-deleted; pushing recreates them ("new branch").
  Always open a new PR afterward — never assume one exists.
- Close superseded PRs with a pointer comment (`Superseded by #N`).
- Delete merged branches locally and remotely; drop superseded stashes.

## 3. Babysitting cadence

- Fast failures first: configure-stage failures land in 1–5 min on warm
  caches. Check at 45–120s intervals until the run leaves the queue; use
  longer waits only for real builds (Boost FULL, OCCT).
- Read failures with `gh run view <id> --log-failed`, filtering the
  git-cleanup noise. For full context use `--log` to a file.
- For `find_package` mysteries, turn on the module's debug flag for ONE
  cycle (e.g. `Boost_DEBUG=ON`) and `ls` the actual staged filenames into
  the validate step — candidate-vs-actual ends all guessing. Remove the
  debug flag in the fix PR.
- Do not push to a branch while its CI is running (cache save/restore
  races). Batch deliberately or wait for green/red first.
- Workflow runs held for approval land as `action_required` (0 jobs,
  "awaiting approval from a maintainer") — an UNRUN, not a result. This is
  GitHub's public-repo supply-chain gate (flags recently-changed workflow
  files triggered by bot pushes). Unblock with
  `scripts/babysit.sh approve <run-id>` (POSTs the approvals endpoint; the
  run re-attempts). Pushes must authenticate as a trusted actor (`gh` auth /
  `SYNC_PAT`), never bare `GITHUB_TOKEN`, or PR runs gate behind approval.
- Pushing a branch while automerge is enabled can make the bot's own sync
  push re-trigger these holds; approve held runs before re-checking.
- **Main CI is dispatched, not pushed.** Automerge merges with GITHUB_TOKEN,
  which suppresses push triggers, so a squash merge to main creates NO push
  runs by design. The native `automerge` job closes the gap: after queueing
  the merge it polls up to 20 min for the squash to land, then dispatches
  both workflows on main. Green branch protection alone is NOT evidence of
  CI on main — confirm the dispatched main runs exist (see §3.1); if the
  dispatch itself failed, re-run it manually from the merge commit.
- The sync-head trust gate verifies `SYNC_PAT` authenticates as the repo
  owner (`gh api user`) before pushing and fails loudly otherwise. A dead
  PAT (expired/revoked) previously fell back to `GITHUB_TOKEN` and silently
  spawned gated zombies for days — never restore that fallback. Rotation:
  owner creates a classic PAT (`contents:write`, no expiry) and updates the
  `SYNC_PAT` repo secret.

### §3.1 Babysitting tooling (`scripts/babysit.sh`)

Run the babysitting flow with the dedicated tool — never ad-hoc gh one-liners
for the two failure modes above. It is non-interactive, deterministic, and
documents its own exit codes (0 green / 1 red / 2 usage / 3 blocked);
`--dry-run` previews every side effect without POSTing or dispatching.

- Held runs: `scripts/babysit.sh held` lists them;
  `scripts/babysit.sh approve <run-id>` (or `--sha <sha>`) approves them;
  `scripts/babysit.sh pr <N> --approve --wait-min M` palms the PR, approving
  held runs as they appear, until merged or checks settle.
- Post-merge main validation (push triggers are dead, §3):
  `scripts/babysit.sh main --workflow native-engine-build.yml --dispatch --approve --wait-min 60`
  and, when the merge touched 3.0 inputs/workflows, also
  `--workflow prusa30-headless.yml`. Without `--dispatch`, missing evidence
  is BLOCKED (exit 3) — never silent.
- `scripts/babysit.sh status <pr>` prints a snapshot (state, held runs,
  required-check coverage) and `main` reports observed runs per workflow on
  a commit; trust its exit code, not the wall of text.
- A green `main` run automatically downloads the `FlashForgeFarm-Debug-APK`
  artifact to `$APK_STAGE_DIR/<sha>/` inside the repo (default `.babysit-apks`;
  copied to `~/storage/downloads/` when Termux storage is set up);
  `--no-download` disables this.
- The repo is resolved from `git remote` and short `--sha` values are expanded
  to the 40-hex needed by `head_sha=` filters. Tool defaults come from the
  tracked `.babysitrc` (shared with the external pr-babysitter): `APK_ARTIFACT`,
  `APK_STAGE_DIR`, `MAIN_WORKFLOW`, `PRUSA30_WORKFLOW`.

## 4. Evidence standards

- Dependency pins come **only** from upstream `deps/*.cmake` (URL + SHA).
  Never hand-write a hash — two from-memory SHAs failed CI on the spot.
- Every patch in `engine/prusa30/patches/` must pass `git apply --check`
  against the pinned tree locally before pushing. Name patches
  `NNNN-what.patch`; each carries a rationale header.
- Every staged tree gets an exact validator (named archives, version
  greps, config-file presence) — never loose file counts. Keep validators
  in sync with what configure actually consumes (the Boost `locale` loop
  kept demanding a lib the COMPONENTS list no longer asked for).
- Local gates before push: `bash -n` scripts, YAML-parse the workflow,
  driver `-fsyntax-only` via `scripts/tests/syntax_check_prusa30.sh`.
- Close issues only with log-quoted evidence (`Found Boost ... found
  components: ...`, `Found NLopt in ...`).

## 5. NDK cross-compile find-module playbook (verified)

- The NDK toolchain sets `CMAKE_FIND_ROOT_PATH_MODE_*=ONLY`: absolute
  `PATHS` get re-rooted under the sysroot and staged-prefix finds miss.
  `find_package(... NO_CMAKE_FIND_ROOT_PATH)` (the FindBoost workaround)
  fixes custom find modules — see patch `0003-findnlopt-no-reroot.patch`.
- `find_package(Boost)`: variable is `Boost_LIBRARY_DIR` (the
  `Boost_LIBRARYDIR` spelling is a hard error); `Boost_ARCHITECTURE="-a64"`
  for b2's arm64 tag; `Boost_COMPILER="-clang"` for b2's versionless
  toolset tag (FindBoost otherwise tries `clang12-...`).
- oneTBB 2021.12's real options are `TBB_TEST`, `BUILD_SHARED_LIBS`,
  `TBBMALLOC_PROXY_BUILD` — similarly-named variants are silently ignored.
  Resolve the installed `TBBConfig.cmake` dir and pass it as `TBB_DIR`.
- Config-only packages (expat, fmt 12.x): pass the **exact config-file
  dir** (`EXPAT_DIR`, `fmt_DIR`) — CONFIG probing never reaches
  `lib/<triplet>/cmake` from the prefix root.
- Upstream `FindNLopt` reads the `NLOPT` **environment** variable
  (`$NLOPT/include/nlopt.hpp` + `$NLOPT/lib/libnlopt_cxx`); set it in the
  step `env:`, not `-D`.
- Build libpng **unprefixed**: upstream's `-DPNG_PREFIX=prusaslicer_` is
  superbuild-only; stock `FindPNG` needs `png.h` + `png` symbols.
- Stock zlib CMake ignores `BUILD_SHARED_LIBS` — delete staged `.so` so
  `FindZLIB` resolves only `libz.a`.
- Boost.Locale has no iconv/ICU backend on NDK API 23: drop the component
  ONLY after grepping all built modules for actual symbol usage (patch
  `0002`; the single `#include` was vestigial, UTF goes via `boost::nowide`).
- Upstream headless find order (`cmake/modules/GlobalDependencies.cmake`):
  Boost → TBB → NLopt → EXPAT → PNG → cereal → fmt → Tracy. Fix in this
  order; do not skip ahead.
- cereal needs nothing staged beyond headers (`Findcereal.cmake` falls back
  to `CHECK_INCLUDE_FILE_CXX`). Tracy with profiling OFF consumes only the
  include dir — stage sources, patch the imported target if CONFIG
  cross-resolve fails.

## 6. Workflow authoring rules

- One cached job per dep: restore → validate → build → verify → save.
  Restores in consumers use `fail-on-cache-miss: true`.
- Cache keys pin script hash + NDK + ABI
  (`prusa30-<dep>-<ver>-${{ hashFiles(...) }}-ndk...-...`).
- Keep `paths:` filters updated for every new script or it never triggers CI.
- Deps must restore from releases/caches on every path; a dep that only
  exists when its own job rebuilt is a cache-miss landmine for consumers.

## 7. Commit / PR / issue conventions

- Commits: `fix(ci):` / `feat(engine):` + body stating failure → fix.
  Bodies cite `Refs #NNN`; PRs state `Supersedes #N` / `Closes #N`.
- One GitHub issue per checklist item; parents referenced (`#117 #120
  #122` for the 3.0 track). Discriminate `enhancement` vs `bug` honestly.
- PR bodies: what changed, the failure analysis that drove it, expected
  next failure. Reviewers (and future agents) reconstruct context from
  the PR alone.

## 8. Hygiene

- `git add` only intended files — always `git status --short` + `git diff
  --stat` before commit. Never commit secrets, staged downloads
  (`engine/prusa30/jniImports/` is gitignored for a reason), or drive-by
  worktree dirt.
- Verify the pushed delta (`git diff origin/main...HEAD --stat`) matches
  the PR's intent before opening it.
