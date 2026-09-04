# flashforge-farm Mobile — AI Handoff (2026-06-10)

> Continues the work. Also read the older `HANDOFF.md` (multi-color details from the prior AI). This file is the **current** state.

Android slicer (Java + native JNI libslic3r) based on the flashforge-farm engine. Package `com.flashforge.farm`.

## ⚠️ Critical ground rules

- **WORKING FOLDER = `/home/cody/workspace/flashforge-farmMobileTestHandOffToGemini/`** — NOT `/home/cody/workspace/flashforge-farmMobileTest/`. The two clones diverged; HandOffToGemini is canonical. Bash cwd resets between calls to the other folder — always `cd` or use absolute paths.
- **Device testing is the user's job.** Give numbered on-device steps; do NOT drive adb taps. **NEVER run `adb shell pm clear`** (wiped their setup once). Installing for them via gradle when asked is fine.
- Device: Samsung `R5CR60DKJGB`. Native build = Release, arm64-v8a.

## Build / install

```bash
cd /home/cody/workspace/flashforge-farmMobileTestHandOffToGemini
./gradlew :app:compileDebugJavaWithJavac -q 2>&1 | grep -iE "error:" | head   # quick Java check
./gradlew :app:installDebug -q 2>&1 | tail -3                                  # build + install
```
Native C++ (libslic3r) builds through gradle/CMake; if only Java changed the `.so` is reused.

Reading slice errors:
```bash
adb logcat -c          # clear first; user reproduces on device
adb logcat -d 2>&1 | grep -iE "BeamPaint|slic3r|calib|RuntimeError|out_of_range|what\(\)" | tail -60
```
Native slice errors come back as a Java `Slic3rRuntimeError` shown in a UI dialog ("Slice failed!") and do NOT reliably hit logcat — screenshot the dialog to read the message:
`adb exec-out screencap -p > /tmp/x.png` then Read it.

---

## CURRENT TASK: flashforge-farm calibration suite (replacing K3D)

User: *"since we are adding orcas calibration, lets ditch the k3d that was from PrusaSlicer."* Old K3D (PrusaSlicer-era) Linear-Advance + Retraction web calibrators are **removed**; wiring flashforge-farm's own generators (`calib.cpp/.hpp`).

### ✅ DONE this session — Pressure Advance (PA Line) end-to-end
- **K3D removed** from `FileMenu.java` `CalibrationsMenu`.
- **Native** (`app/src/main/jni/farm/farm_native.cpp`): `model_slice` JNI extended with `jint calibMode, jdouble calibStart, jdouble calibEnd, jdouble calibStep`. Added `#include "libslic3r/calib.hpp"`. After `print.apply(model->model, config);`:
  ```cpp
  if (calibMode != 0) {
      Slic3r::Calib_Params cp;
      cp.mode = static_cast<Slic3r::CalibMode>(calibMode);
      cp.start = calibStart; cp.end = calibEnd; cp.step = calibStep;
      cp.print_numbers = true;
      print.set_calib_params(cp);
  }
  ```
- **Java:**
  - `slic3r/Native.java`: `model_slice(... int calibMode, double calibStart, double calibEnd, double calibStep)`.
  - `slic3r/Model.java`: `slice(cfg,gcode,listener)` → delegates to `slice(...,0,0,0,0)`; new overload forwards calib params to `Native.model_slice`. (Also runs multi-color detection — see paint section.)
  - `FarmApp.java`: statics `PENDING_CALIB_MODE/START/END/STEP` (0 = normal print).
  - `fragment/BedFragment.java` (~line 586): slice passes `FarmApp.PENDING_CALIB_*`, then sets `PENDING_CALIB_MODE = 0` to consume after one slice.
  - `components/bed_menu/FileMenu.java` `CalibrationsMenu`: "Pressure Advance" entry arms mode=1 (Calib_PA_Line), start=0/end=0.1/step=0.002. If bed empty, calls new helper `ensurePlaceholderModel("box")` (loads `assets/models/box.stl`) so the engine's object validation passes. Added `import android.util.Log;`.

### Bug fixed this session
First on-device test → "Slice failed! No visible objects" (guard `BedFragment.java:551` = `getModel()==null`) because the bed was empty. PA-Line generates its own gcode pattern but the pipeline still needs ≥1 object. Fixed by auto-loading the placeholder box. Confirmed at `GCode.cpp:3236` that Calib_PA_Line writes only the PA pattern and the `else` branch (normal object printing) is **skipped**, so placeholder geometry is never printed. **Built + installed; awaiting user's re-test of the PA pattern in preview.**

### Engine reference (`app/src/main/jni/libslic3r/`)
- `calib.hpp`: `enum CalibMode` — `None=0, Calib_PA_Line=1, Calib_PA_Pattern=2, Calib_PA_Tower=3, Calib_Auto_PA_Line=4, Calib_Flow_Rate=5, Calib_Temp_Tower=6, Calib_Vol_speed_Tower=7, Calib_VFA_Tower=8, Calib_Retraction_tower=9` (RE-VERIFY against the actual header before using). `Calib_Params{mode,start,end,step,print_numbers,extruder_id,...}`. `Print::set_calib_params(cp)`.
- `GCode.cpp` calib branches: ~2911, 3067, **3236 (PA_Line full pattern via `CalibPressureAdvanceLine`)**, 4603, 6729, 8089.

### ▶️ NEXT STEPS
1. Confirm PA Line preview works (user testing).
2. **PA parameter dialog**: start/end/step + DD vs bowden presets (DD ≈ 0–0.1 step 0.002; bowden ≈ 0–0.5 step 0.05) → write into `PENDING_CALIB_*`.
3. **Other calibrations** (Temp Tower=6, Flow=5, Retraction=9, VFA=8, Vol Speed=7): these need a **specific calibration MODEL on the bed** (engine modulates temp/flow per object or Z band — they do NOT self-generate gcode like PA Line). Each needs its own param UI.
4. **Calibration models are NOT plain STL.** flashforge-farm `resources/calib/` has them as **Draco `.drc`** (temp tower, retraction, vfa, vol speed) and **`.3mf`** (flow rate, 27 blocks, no embedded flow → per-object flow assignment). To bundle: decode `.drc`→mesh (add Draco decode or pre-convert to STL at build) and handle `.3mf` multi-object setup. PA Line needs none (box placeholder suffices).

---

## PAUSED: Multi-color painting (max 16) — walls under-color

Mostly works; **outer walls only take painted color ~30–44%** even when fully painted. Root cause = flashforge-farm MMU segmentation under-applies paint to **perimeters** (`MultiMaterialSegmentation.cpp` Voronoi `extract_colored_segments`/`segmentation_by_painting`; `apply_mm_segmentation` in `PrintObjectSlice.cpp`). Region-inflate workaround (option 2) had **zero effect** (apply_mm_segmentation overrides). PAUSED per user. To resume: deeper Voronoi fix (option 1).

**Works:** palette UI (between process-template dropdown and tabs) w/ per-chip filament TYPE+color; brush mode + floating move/brush toggle; height painting; persists after exiting; model base = T0 color; multi-color slicing emits tool changes (~182 verified); top/bottom color; gcode Filament view. **Broken:** walls (above), bucket-fill.

**Hard-won multi-color facts:**
- Trigger: `filament_diameter.size()>1 && any volume has mmu facets`. Java detection: `Prefs.getFilamentPalette().length>1 && (hasPaint(i)||getExtruder(i)>1)`.
- `set_num_filaments(N)` resizes `filament_option_keys`. `init_filament_option_keys()` MUST be in `PrintConfigDef` ctor (already is) or export crashes `std::out_of_range`.
- **Never clone identity fields** (`filament_self_index`, `filament_settings_id`…) to identical values → engine merges filaments → 0 tool changes. Fix: skip-identity clone + `filament_self_index=1..N` (current working logic in beam_native.cpp).
- `EnforcerBlockerType`: Extruder1=ENFORCER=1, Extruder2=BLOCKER=2, … Extruder16. Annotation field `mmu_segmentation_facets`.
- "Bridges to prime tower" already fixed by prior AI (`enable_prime_tower=true`, `print.is_BBL_printer()=false`).
- libvgcode: `EViewType` FeatureType=0, Tool=11; `set_view_type`, `set_tool_colors`.

---

## OTHER PENDING
- **Cut tool** (task #6): planes, dowels, connectors. Not started.
- **Handy calibration models** (task #4): `CalibrationModelsMenu` loads `assets/models/<key>.stl` (3dbenchy, xyz_cube, bunny, fox, box, cone, cylinder, pyramid, sphere). May want more bundled.

## DONE earlier (don't redo)
- Performance button (8sp); perf keeps bottom layers + never culls overhangs/bridges (`ViewerImpl.cpp`: removed BridgeInfill from culled `is_internal`); perf OFF by default.
- Audited + surfaced missing flashforge-farm settings in config UI.
- Horizontal desktop-style settings tabs (Quality|Strength|Speed|Support|Multimaterial|Others), each its own page.
- Removed welcome color picker (locked teal); splash shows "flashforge-farm v2.4 Beta".

## Memory files
`/home/cody/.claude/projects/-home-cody-workspace-flashforge-farmMobileTest/memory/` — `MEMORY.md` (index), `correct-working-folder.md`, `device-testing-preference.md`, `flashforge-farm-mobile-paint-feature.md`, `orca-calibration-feature.md`. Keep updated.
