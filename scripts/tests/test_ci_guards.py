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
    if re.search(r"occt_\$\{NAME\} SHARED IMPORTED", cmake):
        failures.append("engine/CMakeLists.txt still imports shared libTK*.so (must be static .a)")

    sh = (REPO / "scripts/ensure-apk.sh").read_text()
    if re.search(r'^OCCT_SO="[^"]*"', sh, re.M) or re.search(r"printf 'lib%s\.so '", sh):
        failures.append("ensure-apk.sh still requires OCCT runtime libTK*.so in the APK (must be static-only)")

    gradle = (REPO / "app/build.gradle").read_text()
    if "'../engine/output/occt-libs'" in gradle:
        failures.append("app/build.gradle still stages engine/output/occt-libs (OCCT must be static-only)")

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

    if failures:
        print("CI GUARD FAILURES:")
        for f in failures:
            print(f"- {f}")
        return 1
    print("CI guards OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
