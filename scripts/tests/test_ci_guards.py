#!/usr/bin/env python3
"""CI guard tests: the workflow must parse, job display names must stay short,
and every ruleset-required check must map to a real job (renaming a job
without updating the ruleset silently blocks all merges).

Fast (<5s). When `gh` is authenticated, required contexts are read live from
the ruleset; otherwise the expected names are pinned.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/native-engine-build.yml"
PINNED_CHECKS = {"engine", "apk"}
MAX_JOB_NAME = 12


def ruleset_contexts():
    try:
        out = subprocess.run(
            ["gh", "api", "repos/1337farm/flashforge-farm/rulesets",
             "--jq", ".[].rules[] | select(.type==\"required_status_checks\") "
                     "| .parameters.required_status_checks[].context"],
            capture_output=True, text=True, timeout=60, cwd=REPO)
        if out.returncode == 0 and out.stdout.strip():
            return set(out.stdout.split())
    except Exception as e:
        print(f"(gh unavailable: {e}; using pinned checks)")
    return set(PINNED_CHECKS)


def main():
    failures = []
    wf = yaml.safe_load(WORKFLOW.read_text())
    jobs = wf.get("jobs", {})
    names = {jid: (j.get("name") or jid) for jid, j in jobs.items()}

    for jid, name in names.items():
        shown = name
        matrix = jobs[jid].get("strategy", {}).get("matrix", {}).get("include", [])
        for var in set(re.findall(r"\$\{\{\s*matrix\.(\w+)\s*\}\}", name)):
            longest = max((str(row.get(var, "")) for row in matrix), key=len, default="")
            shown = re.sub(r"\$\{\{\s*matrix\." + var + r"\s*\}\}", longest, shown)
        if len(shown) > MAX_JOB_NAME:
            failures.append(f"job display name too long ({len(shown)}): {jid} -> {shown!r}")

    required = ruleset_contexts()
    print(f"job names: {sorted(names.values())}")
    print(f"required checks: {sorted(required)}")
    for ctx in required:
        if ctx not in names.values():
            failures.append(f"required check {ctx!r} matches no job name")

    # OCCT static-link guard: OCCT must be statically linked into libslic3r.so
    # (engine imports .a archives, gc-sections'd) — it must NOT be packaged or
    # loaded as libTK*.so runtime libs anymore. Any place asserting/aspecting
    # libTK*.so in the APK is a regression to the 42MB runtime payload.
    # NOTE: patterns match code constructs only, never comments — documenting
    # the migration with libTK*.so mentions must not fail this guard.
    cmake = (REPO / "engine/CMakeLists.txt").read_text()
    m = re.search(r"set\(OCCT_LIBS\s+([^\)]+)\)", cmake, re.S)
    libs = set(m.group(1).split()) if m else set()
    print(f"occt static link set: {len(libs)} toolkits")
    if not libs:
        failures.append("could not parse OCCT_LIBS from engine/CMakeLists.txt")
    elif "TKVCAF" not in libs:
        # TKVCAF provides the TPrsStd_DriverTable the XCAF/STEP OCAF stack
        # pulls in at link time. Omitting it links a libslic3r.so with
        # undefined TPrsStd_* symbols (static link era regression).
        failures.append("OCCT_LIBS missing TKVCAF (TPrsStd_DriverTable undefined at static link)")
    if re.search(r"occt_\$\{NAME\} SHARED IMPORTED", cmake):
        failures.append("engine/CMakeLists.txt still imports shared libTK*.so (must be static .a)")

    sh = (REPO / "scripts/ensure-apk.sh").read_text()
    if re.search(r'^OCCT_SO="[^"]*"', sh, re.M) or re.search(r"printf 'lib%s\.so '", sh):
        failures.append("ensure-apk.sh still requires OCCT runtime libTK*.so in the APK (must be static-only)")

    gradle = (REPO / "app/build.gradle").read_text()
    if "'../engine/output/occt-libs'" in gradle:
        failures.append("app/build.gradle still stages engine/output/occt-libs (OCCT must be static-only)")

    # Deterministic-signing guard: rolling releases must be signed with ONE
    # committed key. The SDK's auto-generated ~/.android/debug.keystore is
    # regenerated with a NEW random cert on every fresh CI runner, so each
    # published APK has a different signature and installing the next rolling
    # APK over the previous one fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE
    # ("package conflicts with an existing package"). (2026-09-06.)
    if not (REPO / "keystore/farm-debug.keystore").is_file():
        failures.append("committed keystore/farm-debug.keystore missing (rolling APKs must use one fixed cert)")
    if not (REPO / "keystore.properties").is_file():
        failures.append("keystore.properties missing (signingConfig reads it)")
    if re.search(r"storeFile.*debug\.keystore", gradle):
        failures.append(
            "app/build.gradle must not sign with the per-machine auto-generated "
            "debug.keystore (fresh CI runners regenerate it with a random cert "
            "every build -> rolling installs fail with package conflicts); use "
            "the committed keystore/farm-debug.keystore + sign BOTH debug and "
            "release with signingConfigs.farm"
        )
    if gradle.count("signingConfig signingConfigs.farm") < 2:
        failures.append(
            "both debug and release buildTypes must set signingConfig "
            "signingConfigs.farm (committed, deterministic) so CI and local "
            "builds share one cert and rolling APKs update in place"
        )

    loader = (REPO / "app/src/main/java/com/flashforge/farm/slic3r/OCCTLoader.java").read_text()
    if re.search(r"loadLibrary\(\s*\"TK", loader):
        failures.append("OCCTLoader.java still System.loadLibrary's OCCT toolkits (must be static-only)")

    # Dep trees for the APK must come from the dep-*-latest RELEASES, never
    # from actions/cache: build-dep only saves a cache entry when it rebuilds
    # from source, and release-reuse skips saving -> a native PR would
    # otherwise fail on cache-miss. (2026-09-06: Restore OCCT did exactly
    # that with fail-on-cache-miss: true and halted package-apk.)
    apk = jobs.get("package-apk", {})
    if any(
        isinstance(step, dict) and "actions/cache" in str(step.get("uses", ""))
        for step in apk.get("steps", [])
    ):
        failures.append(
            "package-apk uses actions/cache; APK dep trees must be restored from "
            "the dep-*-latest releases (build-dep skips saving the cache on reuse)"
        )

    # Engine release-reuse must not stage a stale .so: mirror the publish
    # quality gate AND the APK static-OCCT invariant (no libTK DT_NEEDED),
    # otherwise a pre-static release .so silently reaches package-apk and
    # hard-fails its static-link gate. (2026-09-06: apk failed exactly there.)
    engine = jobs.get("build-engine", {})
    rel = next(
        (
            s for s in engine.get("steps", [])
            if isinstance(s, dict) and s.get("name") == "Restore engine from release (published build reuse)"
        ),
        {},
    )
    rel_run = str(rel.get("run", ""))
    if "readelf -d" not in rel_run or "libTK" not in rel_run or "'Orca Slicer'" not in rel_run:
        failures.append(
            "engine release-reuse step must run the full quality gate (size, "
            "no libTK runtime DT_NEEDED, Orca Slicer/Arachne/gmp/mpfr markers) "
            "so a stale .so falls through to a fresh build"
        )

    # build-dep must revalidate release/cache trees EXACTLY against the
    # checked-in CMakeLists prebuilt sets. (2026-09-06: a loose "-ge 20
    # libTK*.a" sentinel reused a stale occt release that lacked
    # TKernel/TKMath/TKG2d, then the engine's exact check hard-failed.)
    dep_job = jobs.get("build-dep", {})
    dep_steps = [s for s in dep_job.get("steps", []) if isinstance(s, dict)]
    vstep = next((s for s in dep_steps if s.get("name", "").startswith("Validate restored")), {})
    vrun = str(vstep.get("run", ""))
    if "check-native-prebuilts.py" not in vrun or "--dep" not in vrun:
        failures.append(
            "build-dep revalidation must use check-native-prebuilts.py --dep "
            "(exact CMakeLists OCCT_LIBS/BOOST_LIBS truth), never a loose "
            "file count, so a release built from an older set is rebuilt"
        )
    names_idx = {s.get("name", ""): i for i, s in enumerate(dep_steps)}
    co = names_idx.get("Checkout repository")
    rr = names_idx.get("Restore from release (published build reuse)")
    if (
        co is None or rr is None or co > rr
        or dep_steps[co].get("if")
    ):
        failures.append(
            "build-dep must check out the repo before release-restore and "
            "unconditionally (exact revalidation reads checked-in CMakeLists)"
        )
    pstep = next((s for s in dep_steps if s.get("name", "").startswith("Publish")), {})
    if "refs/heads/main" in str(pstep.get("if", "")):
        failures.append(
            "build-dep publish must not require refs/heads/main: a PR that "
            "rebuilds a verified dep must republish it to self-heal stale "
            "rolling releases for later consumers"
        )

    # Skip-exclusion guard: jobs that must run even when an upstream `needs`
    # job was SKIPPED (native=false path-filter skips dep/engine) need
    # !cancelled() in their job-level if: — a plain conditional like
    # needs.X.result != 'failure' is NOT evaluated when a needs job skipped,
    # so GitHub silently drops the job. (2026-09-06: PR #26 p2p publish kit,
    # native=false -> apk AND merge both skipped, PR never auto-merged.)
    for jid in ("package-apk", "automerge"):
        job = jobs.get(jid, {})
        job_if = str(job.get("if", ""))
        if "cancelled()" not in job_if:
            failures.append(
                f"job {jid!r} ({job.get('name')!r}) must gate its if: with "
                "!cancelled() (skip-exclusion): without it GitHub skips the "
                "job whenever an upstream needs job was skipped, silently "
                "breaking the native=false PR path / automerge"
            )

    # Post-merge release-refresh guard: a squash merge performed with the
    # default GITHUB_TOKEN does NOT trigger the push workflow on main (GitHub
    # suppresses workflow re-triggering from that token), so farm-apk-latest /
    # engine-latest would silently go stale on every auto-merge. The automerge
    # job must therefore dispatch the main pipeline itself after merging —
    # which additionally requires actions: write on that job. (2026-09-06:
    # PR #26 auto-merged but farm-apk-latest stayed at the pre-merge build.)
    auto = jobs.get("automerge", {})
    if auto.get("permissions", {}).get("actions") != "write":
        failures.append(
            "automerge job must grant actions: write (needed to dispatch the "
            "main pipeline to refresh rolling releases after a GITHUB_TOKEN merge)"
        )
    auto_names = [
        (i, str(s.get("name", ""))) for i, s in enumerate(auto.get("steps", []))
        if isinstance(s, dict)
    ]
    if not any("Dispatch main pipeline" in n for _, n in auto_names):
        failures.append(
            "automerge job must include a 'Dispatch main pipeline' step that "
            "runs `gh workflow run native-engine-build.yml --ref main`: "
            "GITHUB_TOKEN merges never trigger the push workflow on main, so "
            "without it the rolling releases go stale on every auto-merge"
        )

    # Stale-head self-heal guard: GitHub runs the workflow found in the PR
    # HEAD, not main. A PR opened before the latest workflow fix (or whose
    # head fell behind main) keeps running a STALE workflow forever, so
    # required checks (apk/engine) never re-evaluate and the PR can never
    # auto-merge (seen on #28/#21 after the #29/#30 fix). A sync-head job must
    # merge current main into the head and push, so the next run uses the
    # working workflow. (2026-09-06.)
    sync = jobs.get("sync-head", {})
    if not sync:
        failures.append(
            "workflow must define a sync-head job that merges current main "
            "into the PR head and pushes it, so stale-workflow PRs are "
            "re-tested with the current workflow instead of being stuck forever"
        )
    else:
        if sync.get("permissions", {}).get("contents") != "write":
            failures.append("sync-head job must grant contents: write (to push the merged head)")
        sync_withs = " ".join(
            str(s.get("with", {})) for s in sync.get("steps", []) if isinstance(s, dict)
        )
        sync_runs = " ".join(
            str(s.get("run", "")) for s in sync.get("steps", []) if isinstance(s, dict)
        )
        if "fetch-depth" not in sync_withs or "git merge" not in sync_runs:
            failures.append(
                "sync-head job must fetch full history and git merge origin/main "
                "into the head (to pick up the current workflow)"
            )
        if "git push" not in sync_runs:
            failures.append(
                "sync-head job must git push the merged head back to the "
                "pull_request head ref (to re-trigger with the current workflow)"
            )

    # Same-step PATH guard: GITHUB_PATH only applies to SUBSEQUENT steps, so a
    # step that echoes the SDK bin dir to GITHUB_PATH AND then runs bare
    # `sdkmanager` in the same run block MUST first export PATH inline —
    # otherwise sdkmanager is not found (exit 127). This bit build-dep's
    # "Set up Android SDK / NDK" on 2026-09-06.
    for jid, job in jobs.items():
        for step in job.get("steps", []):
            run = step.get("run") or ""
            if "GITHUB_PATH" not in run or "sdkmanager" not in run:
                continue
            first = run.find("sdkmanager")
            prefix = run[:first]
            if "export PATH" not in prefix or "cmdline-tools/latest/bin" not in prefix:
                failures.append(
                    f"job {jid!r} step {step.get('name', '?')!r} runs sdkmanager in the same "
                    "step that sets GITHUB_PATH without an inline export PATH "
                    "(cmdline-tools/latest/bin); GITHUB_PATH only affects later steps"
                )

    # Slice-thread stack guard: the native slicer (Print::apply -> config
    # apply -> OpenMP) must run on a big-stack thread. ART's ~1MB default for
    # `new Thread()` overflows inside Slic3r::Print::apply, faulting in the
    # vDSO on the first gettimeofday (2026-09-06 farm_crash_9290.log).
    bed = (REPO / "app/src/main/java/com/flashforge/farm/fragment/BedFragment.java").read_text()
    if not re.search(r"new Thread\(\s*null,\s*\(\) ->", bed) or "32 * 1024 * 1024" not in bed:
        failures.append(
            "BedFragment must run the native slice on a big-stack thread "
            "(Thread(null, runnable, \"farm-slice\", 32 * 1024 * 1024)); the "
            "default ~1MB new Thread() stack overflows in Slic3r::Print::apply"
        )

    # Crash-log consolidation guards: (1) the native handler must write ONE
    # session-stable farm_crash.log with O_APPEND — per-pid/per-timestamp names
    # leave a new Downloads file per crash restart; (2) the header must not
    # format the loaded-library map inside the signal/build/register snprintf,
    # because its would-be length exceeds the buffer on real devices and
    # silently drops the whole header (dumps had no signal/fault/registers);
    # (3) FarmApp must consolidate/update a single Downloads document and prune
    # stale ones instead of inserting a new MediaStore row per crash.
    # (All 2026-09-06: a slice crash-loop piled 10-20 farm_crash_*/logs into
    # Downloads and every report lacked signal info.)
    cd = (REPO / "app/src/main/jni/farm/farm_crashdump.cpp").read_text()
    if "farm_crash_%d" in cd:
        failures.append(
            "farm_crashdump.cpp must write the session-stable farm_crash.log "
            "(no per-pid %d in the name); per-pid names leave one Downloads "
            "file per crash restart"
        )
    if "O_APPEND" not in cd or "O_WRONLY | O_CREAT | O_APPEND" not in cd:
        failures.append(
            "farm_crashdump.cpp must open farm_crash.log O_WRONLY | O_CREAT | "
            "O_APPEND so a crash set from one app start accumulates in one "
            "file; O_TRUNC/per-pid naming leaves one Downloads file per crash"
        )
    if "loaded libraries (name @ load-base):\\n%s" in cd:
        failures.append(
            "farm_crashdump.cpp must not format the loaded-library map inside "
            "the signal/build/register snprintf (would-be length > buffer on "
            "real devices silently drops the whole header, losing signal, fault "
            "address and registers from every dump)"
        )

    farm = (REPO / "app/src/main/java/com/flashforge/farm/FarmApp.java").read_text()
    if re.search(r'"FlashForgeFarm_crash_', farm):
        failures.append(
            "FarmApp's uncaught handler must not save timestamped "
            "FlashForgeFarm_crash_* files (double-quoted string) to Downloads "
            "at crash exit (one more file per crash); the start-time exporter "
            "consolidates into farm_crash.log"
        )
    if '"farm_crash.log"' not in farm:
        failures.append(
            "FarmApp must use the single canonical Downloads document name "
            "farm_crash.log for crash exports, never per-crash insertions"
        )
    if "updateOrCreateDownloads" not in farm:
        failures.append(
            "FarmApp must update the existing farm_crash.log Downloads document "
            "in place (openOutputStream \"rwt\") instead of inserting a new "
            "MediaStore row per crash (that accumulated 10-20 files in Downloads)"
        )
    if "pruneStaleCrashFiles" not in farm:
        failures.append(
            "FarmApp must prune older per-pid/per-timestamp farm_crash_* and "
            "FlashForgeFarm_crash_* Downloads docs owned by the app on start, "
            "to clean up pre-consolidation pile-ups"
        )

    if failures:
        print("CI GUARD FAILURES:")
        for f in failures:
            print(f"- {f}")
        return 1
    print("CI guards OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
