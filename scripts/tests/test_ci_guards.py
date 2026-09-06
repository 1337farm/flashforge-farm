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
