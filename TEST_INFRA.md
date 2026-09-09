# Test Infrastructure: Headless E2E Test Suite for FlashForge Farm

This document outlines the testing philosophy, feature coverage inventory, test runner architecture, and execution details for the headless end-to-end (E2E) test suite of FlashForge Farm.

## 1. Test Philosophy
The test suite follows an **opaque-box, requirement-driven** model:
* **Requirement-Driven**: Every test case directly traces to a specific feature requirement or bug fix.
* **Opaque-Box**: Tests verify correct behavior by invoking public UI controllers, simulating input touch coordinates, and asserting changes in application state or configuration output, treating the underlying graphics rendering and native C++ processing as a black box.

## 2. Feature Inventory
The E2E test suite covers the following 8 core features:
1. **Color Painting**: Applying filament colors to mesh facets.
2. **Support Painting**: Marking facets for support enforcement or blocking.
3. **3D Measuring**: Calculating coordinates and distance between two selected points.
4. **Long-Press Context Menu**: Opening context options via sustained hold.
5. **Fill Bed pre-calculation**: Optimizing the grid count of models fitting on the bed.
6. **Mandatory Setup Screen**: Forcing printer configuration wizard on first-start.
7. **Auto Brim config loading**: Sanitizing auto-brim configuration values to prevent crashes.
8. **Nomenclature fixes**: Verifying that UI strings use current "FlashForge Farm" and "flashforge-farm" branding consistently.

## 3. Test Architecture
The test infrastructure runs headlessly on a standard JVM without an emulator or device:
* **Shadow Native Class**: A test-only `com.flashforge.farm.slic3r.Native` class is placed first on the classpath, replacing JNI library loading with pure Java mock responses.
* **Android Stubs**: Lightweight mock implementations of Android SDK classes are compiled as part of the test setup.
* **Simulation Layer**: Motion events are dispatched programmatically to `GLView` components to simulate user input.

```
scripts/tests/
├── E2ETestRunner.java       # Runs all test tiers and generates a summary report
├── E2ETestSuite.java        # Contains test case definitions and assertions
└── mocks/                   # Contains Android stubs and shadow Native.java
```

## 4. Coverage Thresholds
The test suite guarantees coverage across 4 distinct testing tiers:
* **Tier 1 (Feature Coverage)**: 5 tests per feature = **40 tests**
* **Tier 2 (Boundary & Corner)**: 5 tests per feature = **40 tests**
* **Tier 3 (Cross-Feature)**: Integration of multiple features = **8 tests**
* **Tier 4 (Real-World)**: End-to-end user workflows = **5 tests**
* **Minimum Total Coverage**: **93 tests**

## 5. Verification Method
To compile and execute the E2E test suite:
```bash
./scripts/tests/run_e2e_tests.sh
```
This script automates:
1. Compilation of Android stubs and JVM mock classes.
2. Compilation of application code combined with E2ETestSuite and E2ETestRunner.
3. Classpath layering (mock classes ahead of actual classes) and JVM execution.

## 6. Artifact Binding Rule (engine .so must match engine/ sources)
A sha-only check cannot see source changes, so every consumer binds the
engine binary to the source tree before trusting it:
* `scripts/fetch-native-deps.sh` accepts a release `libslic3r.so` only when
  `engine-manifest.json:engine_src == git rev-parse HEAD:engine`, and records
  the accepted tree in `engine/output/<ABI>/.engine_src` (also written on
  source builds).
* `scripts/tests/engine_repro/build_harness.sh` fails closed when the staged
  `.so`'s `.engine_src` differs from `HEAD:engine`; `run_regressions.sh`
  prints both so the audit trail shows what was actually tested.
* `scripts/ensure-apk.sh` installs only an APK built for the current commit
  (bundle filename + `apk-manifest.json:commit` must match); otherwise it
  builds locally.
Rule: never test or ship a stale engine — refresh with
`scripts/fetch-native-deps.sh --engine`, confirm
`cat engine/output/arm64-v8a/.engine_src` equals `git rev-parse HEAD:engine`,
then re-run.

## 7. Slice-flow regression coverage (crash: "ConfigOptionEnumGeneric: incompatible type")
Three layers guard the open-file → slice flow that produced the enum-type crash:

1. **Engine repro harness** (`scripts/tests/engine_repro/`): `config_apply_crash`
   + `run_regressions.sh` reproduce the exact pre-apply pipeline of
   `farm_native.cpp::model_slice` (load → `normalize_fdm` → `curr_bed_type` /
   flush-fixups → `Print::apply`) against the real `libslic3r.so`. The
   `user_benchy_ad5m.ini` fixture was reconstructed from the failing crash
   inventory, so a green run proves the current engine applies/validates the
   reported config cleanly. It must be run against the *current* engine (`||
   exit 2` on a stale `.so`). Note: `run_regressions.sh` prints the `engine_src`
   under test for the audit trail.

2. **JVM unit tests** (`app/src/test/.../ConfigSerializeFlowTest.java`, run by
   CI via `./gradlew :app:testDebugUnitTest`): parse the bundled/imported INI
   → key migration (`ConfigObject.KEY_MIGRATION`) → serialization, and assert
   the bool→enum migration (`gap_fill_enabled` → `gap_fill_target`) emits an
   enum name, never a legacy bool token.

3. **Headless E2E** (`scripts/tests/E2ETestSuite.java`): `testSliceFlowReachesNativeModelSlice`
   drives `FarmApp.genCurrentConfig()` + `Model.slice(...)` and asserts the mock
   `Native.model_slice` (full 11-arg signature) actually recorded the generated
   `slic3r_current.ini` path. This closes the gap where the shadow `Native` mock
   was out of sync with the native declarations and `Model.slice` silently
   failed to link (NoSuchMethodError) instead of running.

### Engine instrumentation note
`ConfigOptionEnumGeneric::set` and `ConfigOptionEnumsGenericTempl::set`
(engine/src/main/jni/libslic3r/Config.hpp) all append `config_type_pair_msg`
(dst/src classes) and `apply_only` prepends the key, so a recurrence is
diagnosable offline. Changing Config.hpp requires an **engine rebuild and
re-publish** before the on-device error changes shape — the binding rule in §6
is what prevents shipping the old-instrumentation `.so`.
Note: `ConfigOptionEnumsGenericTempl::set` copies via `static_cast` on the
shared `ConfigOptionInts` base, **not** `dynamic_cast` — the app loads
`libfarm.so` and `libslic3r.so` as separate DSOs, each compiling its own
typeinfo/vtable for the header template, so a cross-boundary `dynamic_cast`
returns null even when `typeid().name()` matches (device crash 2026-09-09).

### Bool→enum value-semantics hazard
Any `ConfigObject.KEY_MIGRATION` entry that maps a legacy *bool* key to an
engine *enum* key needs a value transform (see `normalizeSerializedValue`) or
the engine rejects the value on load. `gap_fill_enabled`→`gap_fill_target`
(0/1 → nowhere/everywhere) is handled; audit any future migration the same way.
