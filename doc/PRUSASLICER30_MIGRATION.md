# PrusaSlicer 3.0 Engine Migration — grounded design (not speculative)

This is the authoritative, code-grounded migration plan for swapping the
OrcaSlicer-derived engine in `engine/` for the PrusaSlicer 3.0 core
(`prusa3d/PrusaSlicer`, `master`). Every pin/module/version below was extracted
from a fresh shallow clone of upstream, *not* guessed.

## Upstream pin

| Item | Value |
|---|---|
| Repo | https://github.com/prusa3d/PrusaSlicer |
| Branch | `master` |
| Tag | `version_3.0.0-alpha11` |
| Commit | `6f510128d7c2e543b62919b74bea7e876f564205` |

3.0 is not "unreleased": it is `master`, tagged `3.0.0-alpha11`. The big
refactor of the monolithic `libslic3r` is in progress *in the tree* (the legacy
`libslic3r` core still coexists with the new modules — this is a mid-migration
codebase, which is why the build caveats below matter).

## Module graph (from `src/CMakeLists.txt`)

The headless slicing core is now a set of bounded modules, added
unconditionally (i.e. they do NOT need `SLIC3R_GUI`):

```
slic3r-base                  slic3r-gcode-reader
slic3r-domain                libpgcode
slic3r-biz-algorithms        slic3r-jthread
slic3r-biz-cgal-algorithms   libslic3r
slic3r-biz-crypto            slic3r-biz-arrange
slic3r-biz-parser            slic3r-biz-lua
(+ occt_wrapper when SLIC3R_ENABLE_FORMAT_STEP)
```

GUI-only modules (NOT part of the Android core):

```
slic3r-app-cli  slic3r-app-launcher  slic3r-render  slic3r-platform
libvgcode       slic3r-shared         slic3r-platform-wx
slic3r-app-desktop  slic3r-shared-wx
```

### Hard blocker #1 — there is no headless build yet

`src/CMakeLists.txt` currently contains:

```cmake
if (SLIC3R_GUI)
    ...
else()
    message(FATAL_ERROR "Non-GUI build is not supported yet.")
endif()
```

and `slic3r-app-cli` (the slice driver) is only `add_subdirectory`'d under
`SLIC3R_GUI`. **We must add a headless build path** (build only the headless
module set with `SLIC3R_GUI=OFF`) and **write our own slice driver** against the
domain/biz/libslic3r API — there is no upstream headless driver to reuse.

## Config API (the big surface change)

The Orca `DynamicPrintConfig` / `PresetBundle` model is gone. 3.0 uses:

- `Slic3r::Domain::ConfigPack` (`std::variant<ConfigPackFDM, ConfigPackSLA>`)
- `Slic3r::Domain::ConfigContainer` → `.build_print_config()`
- `Slic3r::Domain::FullConfigFDM` / `FullConfigSLA`
- `Slic3r::Domain::Preset/…` — the new INI/tool-mapped preset layout

Key header map (verified in tree):

| Concept | Location |
|---|---|
| Config defs | `src/slic3r-domain/include/Slic3r/Domain/ConfigDef.hpp`, `ConfigDefsFDM.hpp` |
| Full config | `src/slic3r-domain/include/Slic3r/Domain/FullConfigFDM.hpp` |
| Config pack | `src/slic3r-domain/include/Slic3r/Domain/ConfigPack.hpp` |
| Preset | `src/slic3r-domain/include/Slic3r/Domain/Preset/` |
| Model | `src/slic3r-domain/include/Slic3r/Domain/Model.hpp` |
| Print | `src/libslic3r/src/libslic3r/Print.hpp` |
| Slicing | `src/libslic3r/include/libslic3r/Slicing.hpp` |
| TriangleSelector (painting) | `src/slic3r-biz-algorithms/include/Slic3r/Biz/Algorithms/TriangleSelector.hpp` |

This is what the FlashForge Farm JNI bridge and import tooling must target.

## Dependency delta (current repo vs 3.0)

3.0 pins (extracted from `deps/+<Dep>/<Dep>.cmake`):

| Dependency | Current engine/ | PrusaSlicer 3.0 (pinned) | Action |
|---|---|---|---|
| Boost | 1.85 (`1_85`) | **1.86.0** | bump |
| OCCT | 7.9.0 | **V7_6_1** | **downgrade** (Prusa: a chamfer-triangulation bug is unfixed ≤7.9.3) |
| oneTBB | (repo `libtbb.a`) | **v2021.12.0** | pin/bump |
| CGAL | headers only (excluded) | **v5.6.2** | stage full |
| OpenVDB | 8.2.0 | **v11.0.0** | bump |
| Eigen | vendored | **3.4.0** | pin |
| GMP | 6.2.1 | **6.2.1** | keep |
| MPFR | 4.2.0 | **3.1.6** | keep newer (harmless superset) |
| Lua (Sol2) | absent | **5.4.8** | add |
| **New**: nlohmann `json`, `fmt`, `spdlog`, Cereal, `magic_enum`, `cpptrace`, `yaml-cpp` | absent | required | add |

Exact pins are machine-extractable via `scripts/vendor_prusaslicer30.py --upstream … --out-manifest …`.

This is a real dep surface change, not a drop-in swap; `scripts/build_all_deps_android.sh`
must be extended and the prebuilt staging (`jniImports/`, `occt/`) re-bumped.

## Phased rollout

1. **Vendoring + headless build enablement** — add `SLIC3R_GUI=OFF` headless
   path to the module CMake, build only the headless set, and wire a minimal
   headless slice driver (our JNI bridge's replacement for `slic3r-app-cli`).
2. **Dep staging for NDK** — bump Boost→1.86.0, OCCT→7.6.1, TBB→2021.12.0; add
   CGAL/OpenVDB/Lua/json/fmt/spdlog/etc. to `build_all_deps_android.sh`.
3. **JNI bridge rewrite** — target `Domain::ConfigPack` / `ConfigContainer` /
   `Model` / `Print` / `status_callback` for the offline slice path.
4. **Feature port** — painting (`Biz::Algorithms::TriangleSelector`),
   arrange (`slic3r-biz-arrange`), 3MF/STEP (`slic3r-biz-parser` + `occt_wrapper`).
5. **Validation** — golden G-code/3MF byte-compare (the shipped corpus harness).

The import/convert tooling already shipped (#107–#115) maps foreign profiles
into the *old* Orca key space; step 3 re-points its target to the 3.0
`ConfigDef`/`ConfigPack` so imports land natively.