# FlashForge Farm

[![Build Native Engine (from source)](https://github.com/1337farm/flashforge-farm/actions/workflows/native-engine-build.yml/badge.svg)](https://github.com/1337farm/flashforge-farm/actions/workflows/native-engine-build.yml)

A 3D printing slicer for Android, powered by the **flashforge-farm** slicing engine.

FlashForge Farm is a full-featured Android slicer built on the flashforge-farm `libslic3r` engine — the same engine you use on the desktop.

## Features

- **Full flashforge-farm slicing engine** running natively on-device: Arachne wall generator, tree supports, seam control, adaptive layers, and the rest of the Orca feature set
- **flashforge-farm profile import** — load `.orca_printer` / `.orca_filament` config bundles, including profile inheritance resolution and automatic download of vendor base profiles
- **Multi-color painting** — paint models with brush, bucket-fill, and height-range tools from a palette of up to 16 filaments; sliced output gets proper tool changes, a prime tower, and per-filament flush volumes
- **G-code preview** with feature-type and filament/tool color views
- **Profile editor** with flashforge-farm setting names and categories
- **3MF / STL / STEP / OBJ model import**, transform tools, auto-arrange, cut tool, auto-orient
- **Fully offline** — no account, no telemetry

## Install

Download the latest APK from the [Releases](../../releases) page and sideload it (you may need to allow "install from unknown sources").

Requirements: Android 5.0+, 64-bit ARM device (arm64-v8a).

## Build from source

```bash
./gradlew assembleDebug
```

- Android SDK 35, NDK `23.1.7779620`
- The native engine (libslic3r + dependencies) builds via CMake on the first build — expect it to take a while
- Prebuilt native dependencies not stored in git (Boost, oneTBB, OCCT, GMP/MPFR) are expected under `app/src/main/jniImports/` and `app/src/main/occt/`
- The output APK lands in `app/build/outputs/apk/debug/`

Note: the Java package is `com.flashforge.farm` and the `applicationId` is `com.flashforge.farm`.

## Credits

FlashForge Farm is built on:

- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) by SoftFever — slicing engine
- [PrusaSlicer](https://github.com/prusa3d/PrusaSlicer) / Slic3r and [Bambu Studio](https://github.com/bambulab/BambuStudio) — which flashforge-farm is built upon

## Status

Experimental / alpha. Sliced output should always be sanity-checked in the G-code preview before printing. Issues and feedback are welcome.

## License

[AGPL-3.0](LICENSE), same as the projects it derives from. Source availability and attribution must be maintained for distributed APKs.
