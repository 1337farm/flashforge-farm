# engine/prusa30 — PrusaSlicer 3.0 engine (fetched, not vendored)

The 3.0 engine is **not copied into this repo's git**. At build time we fetch
the pinned upstream commit and apply `patches/`, then build the headless slice
core. See `doc/PRUSASLICER30_MIGRATION.md` for the grounded migration plan.

## Layout

```
engine/prusa30/
├── fetch_prusaslicer.sh           # clone+pin+patch the upstream engine tree (build dir only)
├── CMakeLists.txt                 # headless android build entry (adds modules + driver)
├── farm_driver.cpp                # JNI-facing slice driver (Domain::ConfigPack -> Print -> gcode)
└── patches/
    └── 0001-src-allow-headless-build.patch   # remove upstream `FATAL_ERROR` under SLIC3R_GUI=OFF
```

## How the build consumes it

- `fetch_prusaslicer.sh` writes the engine source to `engine/build/prusaslicer-src`
  (a build dir in `.gitignore`, never committed). `PRUSA_REF` selects the commit;
  default = pinned `version_3.0.0-alpha11`.
- The JNI layer and app/CMakeLists reference `engine/prusa30` targets, *not* the
  upstream tree directly, so the only thing that changes with upstream bumps is
  `PRUSA_REF`.
- The upstream source requires the staged native deps (Boost 1.86.0, OCCT V7_6_1,
  oneTBB 2021.12.0, CGAL v5.6.2, OpenVDB v11.0.0, …). Those handle-overs are
  tracked separately and must be in place before the headless build goes green.

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