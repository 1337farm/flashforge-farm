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
- `action_required` (0s, bot actor) is a gated duplicate, not a result —
  the real run appears separately. Pushes must authenticate as a trusted
  actor (`gh` auth / `SYNC_PAT`), never bare `GITHUB_TOKEN`, or PR runs
  gate behind manual approval.

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
