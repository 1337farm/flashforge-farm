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
    # It must ALSO compare engine sources (manifest engine_src vs
    # HEAD:engine): the sha/size/marker gate cannot see source changes, so an
    # engine PR would otherwise ship the previous release .so.
    # (2026-09-07: a Config.hpp segfault fix was silently skipped this way.)
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
    if "engine_src" not in rel_run or "HEAD:engine" not in rel_run:
        failures.append(
            "engine release-reuse step must compare manifest engine_src "
            "against HEAD:engine and rebuild on mismatch; the .so "
            "sha/size/marker gate alone reuses a stale .so after engine "
            "source changes"
        )
    pub = next(
        (
            s for s in engine.get("steps", [])
            if isinstance(s, dict) and s.get("name") == "Publish engine release"
        ),
        {},
    )
    if "engine_src" not in str(pub.get("run", "")):
        failures.append(
            "Publish engine release must record engine_src (HEAD:engine tree) "
            "in engine-manifest.json, or reuse can never detect source changes"
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

    # Slice-thread stack guard: Slic3r's Print::apply nests several full deep
    # copies of the print config (by-value DynamicPrintConfig + apply_only
    # per-option set()), so it must run on a desktop-parity 8MB named thread.
    # ART's ~1MB default for `new Thread()` overflows inside
    # Slic3r::Print::apply (2026-09-06 farm_crash_9290.log). The JNI side must
    # also keep Print/DynamicPrintConfig on the heap, not as stack locals.
    bed = (REPO / "app/src/main/java/com/flashforge/farm/fragment/BedFragment.java").read_text()
    if not re.search(r"new Thread\(\s*null,\s*\(\) ->", bed) or "8 * 1024 * 1024" not in bed or '"farm-slice"' not in bed:
        failures.append(
            "BedFragment must run the native slice on a desktop-parity thread "
            "(Thread(null, runnable, \"farm-slice\", 8 * 1024 * 1024)); the "
            "default ~1MB new Thread() stack overflows in Slic3r::Print::apply"
        )
    farm = (REPO / "app/src/main/jni/farm/farm_native.cpp").read_text()
    m = re.search(
        r"Java_com_flashforge_farm_slic3r_Native_model_1slice.*?^}",
        farm, re.S | re.M)
    body = m.group(0) if m else ""
    if "make_unique<Print>()" not in body or "make_unique<DynamicPrintConfig>()" not in body:
        failures.append(
            "model_slice must heap-allocate Print/DynamicPrintConfig "
            "(make_unique); stack locals eat the slice thread's stack"
        )

    # AutoDispatch dataSync guard: Android 15+ (targetSdk 35) caps dataSync
    # FGS at 6h/24h and kills the app two ways — running past the cap without
    # stopSelf (DidNotStopInTime) and starting with a spent quota
    # (StartNotAllowed at startForeground, 2026-09-06 farm_crash.log). The
    # service must implement onTimeout -> stopSelf, degrade a refused start
    # to stopped, never run sticky, and stop itself when idle (restart is
    # explicit via kick() on app launch + job enqueue).
    svc = (REPO / "app/src/main/java/com/flashforge/farm/api/AutoDispatchService.java").read_text()
    if "void onTimeout(int startId, int fgsType)" not in svc:
        failures.append(
            "AutoDispatchService must override onTimeout(int, int) -> stopSelf() "
            "(Android 15+ dataSync 6h/24h quota kills without it)"
        )
    if "ForegroundServiceStartNotAllowedException" not in svc:
        failures.append(
            "AutoDispatchService must catch ForegroundServiceStartNotAllowedException "
            "around startForeground (spent quota refuses the start -> stop, never crash)"
        )
    if "START_STICKY" in svc and "START_NOT_STICKY" not in svc:
        failures.append(
            "AutoDispatchService must not return START_STICKY (system would "
            "resurrect a dataSync FGS against the 6h/24h quota)"
        )
    if "MAX_IDLE_POLLS" not in svc:
        failures.append(
            "AutoDispatchService must stop itself after sustained idle polls "
            "(a never-ending dataSync FGS always exhausts the 6h/24h quota)"
        )
    menu = (REPO / "app/src/main/java/com/flashforge/farm/components/bed_menu/SliceMenu.java").read_text()
    if "AutoDispatchService.kick(ctx)" not in menu:
        failures.append(
            "SliceMenu must AutoDispatchService.kick(ctx) after enqueueJob "
            "(the idle service stops itself; enqueue is a restart point)"
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

    # coEnums cross-variant guard: ConfigOptionEnumsGenericTempl<true> and
    # <false> are distinct C++ classes sharing the coEnums tag, so set() must
    # not dynamic_cast across variants (nullptr->values faults at +8;
    # 2026-09-07 farm-slice SIGSEGV, fault 0x8, in Templ<false>::set <-
    # apply_only <- Print::apply). Copy via the shared ConfigOptionInts base.
    cfg = (REPO / "engine/src/main/jni/libslic3r/Config.hpp").read_text()
    if "dynamic_cast<const ConfigOptionInts *>(rhs)" not in cfg:
        failures.append(
            "ConfigOptionEnumsGenericTempl::set must copy via the shared "
            "ConfigOptionInts base (cross-variant Templ<true>/<false> "
            "dynamic_cast yields nullptr and faults at +8)"
        )

    # Crash-log append guard: the Downloads document must accumulate across
    # crash cycles (capped), not be replaced per export — export deletes the
    # internal files after publishing, so a truncate rewrite loses history.
    fapp = (REPO / "app/src/main/java/com/flashforge/farm/FarmApp.java").read_text()
    if "readDownloadsContent" not in fapp or "MAX_DOWNLOADS_CRASH_LOG" not in fapp:
        failures.append(
            "FarmApp export must prepend the previously published Downloads "
            "content (capped by MAX_DOWNLOADS_CRASH_LOG); a truncate rewrite "
            "per export replaces history instead of appending"
        )

    # apply_only diagnostics guard: a bare "incompatible type" from deep in
    # Print::apply names no key and previously never reached any log
    # (2026-09-07 slice failure). apply_only must rethrow with the key.
    conf = (REPO / "engine/src/main/jni/libslic3r/Config.cpp").read_text()
    if "apply_only '" not in conf:
        failures.append(
            "ConfigBase::apply_only must rethrow set() failures with the key "
            "name (\"apply_only '<key>': ...\"); keyless config errors are "
            "undiagnosable"
        )

    # Slice-error logging guard: slice/config failures surface only in a
    # dialog today; they must also append to farm_crash.log via
    # writeCrashDump so the next export carries them to Downloads.
    if 'writeCrashDump("slice"' not in bed:
        failures.append(
            "BedFragment slice/config failure paths must FarmApp.writeCrashDump("
            '"slice", ...) so slice errors reach farm_crash.log, not just a dialog'
        )

    # Region-application diagnostics guard: apply_to_print_region_config does
    # direct my_opt->set() outside apply_only, so it needs the same key
    # annotation — it was the remaining keyless "incompatible type" site
    # (2026-09-07 slice failure).
    pobj = (REPO / "engine/src/main/jni/libslic3r/PrintObject.cpp").read_text()
    if "apply_to_print_region_config '" not in pobj:
        failures.append(
            "apply_to_print_region_config must rethrow set() failures with "
            "the key name; keyless region config errors are undiagnosable"
        )

    # Enum set() diagnostics guard: both enum set() implementations must
    # append the dst/src C++ classes (config_type_pair_msg) to the
    # incompatible-type error — the bare message plus a missing key made the
    # 2026-09-07 slice failure unidentifiable (demangle offline with c++filt).
    cfgh = (REPO / "engine/src/main/jni/libslic3r/Config.hpp").read_text()
    if cfgh.count("config_type_pair_msg") < 3:
        failures.append(
            "Config.hpp must define config_type_pair_msg and use it in both "
            "enum set() type-check throws, so config type mismatches name "
            "both C++ classes"
        )

    # Crash-banner version guard: every farm_crash.log entry must stamp the
    # app version + git commit (BuildConfig), so a report is attributable to
    # the exact binaries that produced it without trusting filenames.
    if 'BuildConfig.COMMIT' not in fapp or '"build: v"' not in fapp:
        failures.append(
            "FarmApp.writeCrashDump banner must stamp build version + "
            "BuildConfig.COMMIT so crash logs are attributable to exact binaries"
        )

    # Validated-artifact reuse guards: the engine .so artifact name must bind
    # the exact source tree (libslic3r-<HEAD:engine>), so a later run reuses
    # it only for identical sources — otherwise an engine change silently
    # ships a stale .so (2026-09-07: full 20-min rebuilds on every engine PR
    # with no reuse path at all). Same-run consumers must resolve the same
    # name via the build-engine job outputs; cross-run restore accepts only
    # non-expired artifacts from successful runs passing the quality gate.
    steps = [s for s in engine.get("steps", []) if isinstance(s, dict)]
    up = next((s for s in steps if s.get("name") == "Upload libslic3r.so artifact"), {})
    if "libslic3r-${{ steps.engine-src.outputs.src }}" not in str((up.get("with", {}) or {}).get("name", "")):
        failures.append(
            "engine artifact upload name must bind the source tree "
            "(libslic3r-${{ steps.engine-src.outputs.src }}); an unbound "
            "name cannot be reused soundly across runs"
        )
    art = next((s for s in steps if s.get("name") == "Restore engine from validated PR artifact"), {})
    art_run = str(art.get("run", ""))
    for needle in ("expired==false", "conclusion", '"success"', "readelf -d", "art_hit"):
        if needle not in art_run:
            failures.append(
                f"validated-artifact restore step must check {needle}; "
                "unvalidated reuse ships untested binaries"
            )
            break
    jdk = next((s for s in steps if s.get("name") == "Set up JDK 17"), {})
    if "art_hit" not in str(jdk.get("if", "")):
        failures.append(
            "build steps must skip on validated-artifact hit (art_hit); "
            "otherwise the reuse path never saves the rebuild"
        )
    apk_job = jobs.get("package-apk", {})
    dl = next(
        (
            s for s in apk_job.get("steps", [])
            if isinstance(s, dict) and s.get("name") == "Download libslic3r.so artifact"
        ),
        {},
    )
    if "needs.build-engine.outputs.engine_src" not in str((dl.get("with", {}) or {}).get("name", "")):
        failures.append(
            "package-apk must download libslic3r-${{ needs.build-engine.outputs.engine_src }} "
            "(same tree-bound name the engine job uploaded); a static name "
            "breaks same-run consumption"
        )

    # Immediate-export guard: a crash/error report must reach Downloads when
    # it happens, not on the next app start — writeCrashDump triggers the
    # export inline (best-effort), so killing + restarting just to read the
    # log is unnecessary.
    if "exportPendingCrashesToDownloads()" not in fapp.split("public static void writeCrashDump", 1)[1].split("public static ", 1)[0]:
        failures.append(
            "writeCrashDump must trigger exportPendingCrashesToDownloads inline "
            "so reports persist to Downloads immediately"
        )

    # model_slice error-surface guard: native slice failures must surface
    # as Slic3rRuntimeError (outer catch) so BedFragment logs them; a silent
    # native death would bypass writeCrashDump entirely.
    farm_native = (REPO / "app/src/main/jni/farm/farm_native.cpp").read_text()
    if 'Slic3rRuntimeError' not in farm_native:
        failures.append(
            "model_slice must surface native failures as Slic3rRuntimeError "
            "so the Java slice path can log them"
        )

    # Sync-push auth guard: sync-head must push with SYNC_PAT (trusted actor),
    # not bare GITHUB_TOKEN — token pushes run as github-actions[bot], whose
    # pull_request runs GitHub gates behind manual approval (2026-09-07: 0-job
    # action_required zombie runs on PR62 after every sync push).
    sync = next(
        (
            s for s in jobs.get("sync-head", {}).get("steps", [])
            if isinstance(s, dict) and "self-heal" in str(s.get("name", ""))
        ),
        {},
    )
    if "SYNC_PAT" not in str(sync.get("run", "")):
        failures.append(
            "sync-head push must authenticate with secrets.SYNC_PAT "
            "(GITHUB_TOKEN pushes run as github-actions[bot] and their "
            "pull_request runs require manual approval)"
        )

# farm-iroh release-reuse must not stage a stale .so: the cdylib output
    # depends on BOTH the p2p/ tree AND scripts/build_farm_iroh_android.sh,
    # so the manifest iroh_src key must cover both; the gate mirrors the
    # publish quality gate (size/arch/uniffi marker). Cheap to compile, so
    # reuse only short-circuits a verified-build repeat. (2026-09-07.)
    iroh = jobs.get("build-iroh", {})
    irel = next(
        (
            s for s in iroh.get("steps", [])
            if isinstance(s, dict) and s.get("name") == "Restore farm-iroh from release (published build reuse)"
        ),
        {},
    )
    irel_run = str(irel.get("run", ""))
    if "iroh_src" not in irel_run or "HEAD:p2p" not in irel_run or "build_farm_iroh_android.sh" not in irel_run:
        failures.append(
            "farm-iroh release-reuse step must compare manifest iroh_src "
            "against HEAD:p2p + scripts/build_farm_iroh_android.sh and rebuild "
            "on mismatch; the sha/size/marker gate alone reuses a stale .so"
        )
    if "AArch64" not in irel_run or "checksum_func_connect" not in irel_run:
        failures.append(
            "farm-iroh release-reuse step must run the full quality gate "
            "(size, AArch64, checksum_func_connect uniffi marker) so a stale "
            ".so falls through to a fresh build"
        )
    ipub = next(
        (
            s for s in iroh.get("steps", [])
            if isinstance(s, dict) and s.get("name") == "Publish farm-iroh release"
        ),
        {},
    )
    if "iroh_src" not in str(ipub.get("run", "")):
        failures.append(
            "Publish farm-iroh release must record iroh_src (HEAD:p2p + build "
            "script) in iroh-manifest.json, or reuse can never detect source "
            "changes"
        )
    if "refs/heads/main" not in str(ipub.get("if", "")):
        failures.append(
            "Publish farm-iroh release must gate on refs/heads/main only "
            "(rolling release; PR builds upload artifacts instead)"
        )

    # Per-build log-file guard: each build must append to its own Downloads
    # document (name carries the build commit) instead of every crash cycle
    # replacing one shared file; prune must keep the current build's file
    # while removing legacy/other-build docs.
    if 'crashDownloadsName()' not in fapp or '"farm_crash_" + c + ".log"' not in fapp:
        failures.append(
            "FarmApp must publish per-build Downloads docs via "
            "crashDownloadsName() (farm_crash_<commit>.log); a single shared "
            "name gets replaced instead of appended across builds"
        )
    if 'keep.equals(name)' not in fapp:
        failures.append(
            "pruneStaleCrashFiles must keep the current build's document "
            "and prune legacy/other-build docs by name comparison"
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
