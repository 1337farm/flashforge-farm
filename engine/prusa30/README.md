# engine/prusa30 — PrusaSlicer 3.0 engine (fetched, not vendored)

The 3.0 engine is **not copied into this repo's git**. At build time we fetch
the pinned upstream commit and apply `patches/`, then build the headless slice
core. See `doc/PRUSASLICER30_MIGRATION.md` for the grounded migration plan.

## Layout

```
engine/prusa30/
├── fetch_prusaslicer.sh           # clone+pin+patch the upstream engine tree (build dir only)
├── farm_driver.cpp                # JNI-facing slice driver (Domain::ConfigPack -> Print -> gcode)
├── deps-manifest.json             # exact compiled/header dep pins from upstream deps/*.cmake
└── patches/
    └── 0001-src-allow-headless-build.patch   # remove upstream `FATAL_ERROR` under SLIC3R_GUI=OFF
```

## How the build consumes it

- `fetch_prusaslicer.sh` writes the engine source to `engine/build/prusaslicer-src`
  (a build dir in `.gitignore`, never committed). `PRUSA_REF` selects the commit;
  default = pinned `version_3.0.0-alpha11`.
- The `.github/workflows/prusa30-headless.yml` workflow drives the loop: stage the
  header-only deps (`scripts/build_prusa30_deps.sh` → `engine/prusa30/jniImports/`),
  configure the **fetched upstream as the TOP-LEVEL project** (its root CMakeLists
  relies on `CMAKE_SOURCE_DIR`/module-path being the upstream tree — a wrapper
  `add_subdirectory(upstream EXCLUDE_FROM_ALL)` does not work), then build the
  headless core target `libslic3r` (`src/libslic3r/CMakeLists.txt`). Implementing
  deps-Boost 1.86.0, OCCT V7_6_1, oneTBB 2021.12.0, CGAL v5.6.2, OpenVDB v11.0.0,
  …) still to be staged for the NDK — red configure/build runs enumerate the exact
  `find_*`/dep failures, per the loop's design.
- `farm_driver.cpp` is **not yet compiled in CI**. Its config/model-load glue
  (`Slic3r/Biz/Config/ConfigLoad.hpp`, `Slic3r/Biz/FileLoadingLogic.hpp`) lives in
  upstream's `slic3r-shared` module, which is `SLIC3R_GUI`-gated in
  `src/CMakeLists.txt` (3.0-alpha has not split it headless). Until that lands, the
  driver is validated locally with `scripts/tests/syntax_check_prusa30.sh`
  (`-fsyntax-only` against the fetched upstream headers); a future patch adds it to
  the headless build once `slic3r-shared` is buildable.

## Why a headless patch exists at all

Upstream `src/CMakeLists.txt` currently does `message(FATAL_ERROR "Non-GUI build is
not supported yet.")` — Prusa has not made the core build without wxWidgets yet.
The patch removes that line; the build then relies on our own slice driver
(`farm_driver.cpp`) which uses the domain/biz algorithms directly.

## Validation loop

The entire 3.0 bring-up is CI-driven (there is no NDK toolchain on the dev
device). Each iteration: make a change here → open a PR → the `test` +
`apk`/`engine` checks compile against the fetched upstream tree. HEAD of the PR
must stay green before merge. Commits land on `main`, never force-push releases.