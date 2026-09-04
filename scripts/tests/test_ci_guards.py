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

    if failures:
        print("CI GUARD FAILURES:")
        for f in failures:
            print(f"- {f}")
        return 1
    print("CI guards OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
