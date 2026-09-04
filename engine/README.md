# engine — libslic3r C++ source + NDK build

This folder contains the C++ source code and CMake build configuration for the libslic3r engine, compiled into `libslic3r.so` for arm64-v8a using the Android NDK. It is part of the self-contained flashforge-farm repo: there is no separate engine or deps release repo.

Native dependencies (Boost, oneTBB, OCCT, GMP/MPFR) are built from source by the repo's CI (`native-engine-build.yml`) and `scripts/build_all_deps_android.sh`, then staged into `src/main/` before the engine compiles. This folder only compiles the engine shared library itself.

## Repository Structure
```
src/main/
├── jni/                # Engine source code
│   ├── libslic3r/      # Core library sources
│   ├── qhull/          # Convex hull library
│   ├── nlopt/          # Optimization library
│   ├── libvgcode/      # GCode viewer library
│   ├── glu-libtess/    # Tessellation library
│   ├── imgui/          # Dear Immediate Mode UI
│   ├── admesh/         # Mesh processing library
│   ├── expat/          # XML parser
│   ├── qoi/            # Image format library
│   ├── clipper/        # Polygon clipping library
│   ├── semver/         # Version string library
│   ├── nanosvg/        # SVG parsing library
│   ├── miniz/          # zlib-compatible compression library
│   ├── libnest2d/      # 2D rectangular packing library
│   └── bbl/            # BBL library
├── occt/               # OCCT headers for include path reference
├── jniImports/         # Prebuilt native deps (Boost, oneTBB, GMP, MPFR headers + static libs)
│   ├── boost/
│   ├── oneTBB/
│   └── gmp/
├── jniLibs/            # Prebuilt shared libraries (GMP, MPFR, libstdc++-shared)
│   └── arm64-v8a/
│       ├── libgmp.so
│       ├── libgmpxx.so
│       ├── libmpfr.so
│       └── libc++_shared.so
├── occt/jniLibs/       # Prebuilt OCCT JNI libraries (libTK*.so)
│   └── arm64-v8a/
├── CMakeLists.txt      # NDK CMake build configuration
└── ...
```

## Build Workflow
The repo CI (`native-engine-build.yml`, job `build-engine`):
- Builds native deps from source and stages them into `engine/src/main/`
- Configures CMake with the Android toolchain for arm64-v8a (API 21)
- Builds `libslic3r.so` via `cmake --build` from this folder (`cmake -S engine -B engine/build`)
- The app (`app/CMakeLists.txt`) links the built `.so` and compiles its thin JNI bridge against `src/main/jni/` headers in lockstep — no prebuilt binary, no separate release.