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

    # Export-shrink guard: libslic3r.so must be re-linked with a version script
    # exporting exactly libfarm's need-set so --gc-sections can drop the
    # statically-merged OCCT/Boost/TBB the app never calls. The workflow must
    # run scripts/shrink-engine.sh after the engine build, the engine CMake
    # must accept the link-only SLIC3R_EXPORT_MAP option, the cache key must
    # track the app bridge sources (a new JNI function invalidates a cached
    # export set), and no gate may depend on an after-shrink string like
    # 'Arachne' surviving (those are mangled symbol names that shrink drops).
    wf = WORKFLOW.read_text()
    if "scripts/shrink-engine.sh" not in wf:
        failures.append("workflow does not call scripts/shrink-engine.sh (exports must be shrunk to the app bridge)")
    if "SLIC3R_EXPORT_MAP" not in cmake:
        failures.append("engine/CMakeLists.txt lacks the SLIC3R_EXPORT_MAP link-only option (export-shrink re-link)")
    if "app/src/main/jni/**" not in wf or "scripts/shrink-engine.sh'" not in wf:
        failures.append("engine cache key must include app bridge sources + shrink script (cached export set would go stale)")
    if re.search(r"grep -q -F 'Arachne'", wf):
        failures.append("workflow gate still depends on the 'Arachne' string surviving the export shrink (symbol-name strings get dropped)")

    if failures:
        print("CI GUARD FAILURES:")
        for f in failures:
            print(f"- {f}")
        return 1
    print("CI guards OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
