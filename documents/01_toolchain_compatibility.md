# 1. Android NDK & CMake Toolchain Compatibility (PrusaSlicer 3.0)

## Overview
Cross-compiling the PrusaSlicer 3.0 core engine for Android (`arm64-v8a`) using NDK 23+ and CMake involves addressing significant changes in module boundaries, dependency management, and toolchain configurations compared to the legacy OrcaSlicer/Slic3r architecture.

## Legacy Perl-to-C++ Abstractions Removal
* **Positive Impact:** The total removal of legacy Perl-to-C++ bindings (XS) drastically simplifies the CMake build configuration. In older architectures, stripping out Perl headers and linking dependencies required extensive `#ifdef` patches or custom build targets.
* **NDK Benefit:** The build is now a pure C++17/C++20 environment, making it more compliant with modern NDK toolchains. CMake's `find_package` resolution is less fragile without mixed-language build steps.

## Immediate Compilation and POSIX Alignment Obstacles
1. **Filesystem and OS Abstractions:** PrusaSlicer 3.0 heavily utilizes modern C++ standard library features (`std::filesystem`). While NDK 23+ has robust support, edge cases involving Android's sandboxed file system (Scoped Storage) and POSIX `stat()` / `mmap()` calls on specific memory-mapped files can cause unexpected `EACCES` or `EPERM` runtime errors.
2. **Boost and oneTBB Compatibility:** PrusaSlicer 3.0 relies on updated versions of Boost and oneTBB.
   * **oneTBB:** Linking oneTBB for ARM64 requires specific compiler flags to ensure atomic operations map correctly to ARM's `ldxr/stxr` instructions.
   * **Boost:** Some Boost components (like Boost.Context or Boost.Fiber, if used in new async modules) require explicit ARM64 assembly paths in their b2 builds before linking via CMake in the NDK.
3. **Open CASCADE (OCCT) and GMP/MPFR:** These heavy mathematical dependencies must be built as static libraries (`.a`) or explicit dynamic `.so` objects loaded via `System.loadLibrary()` before `libslic3r.so` to satisfy the linker on Android.

## Compiler Flags & Memory Footprint Constraints
Mobile devices have strict memory limitations (e.g., a 4GB or 6GB RAM ceiling for the entire OS, with per-app limits often much lower).

* **Memory Footprint Flags:**
  * `-Os` or `-Oz`: Optimize for size rather than absolute speed, crucial for reducing the final `.so` footprint.
  * `-flto=thin`: Thin Link-Time Optimization is mandatory to strip unused symbols across the strict module boundaries of PrusaSlicer 3.0, reducing memory overhead during library loading.
  * `-fno-exceptions` and `-fno-rtti`: Where possible (though often difficult with Boost and OCCT), disabling exceptions/RTTI can save megabytes. However, PrusaSlicer's core heavily relies on exceptions for geometry errors; careful selective compilation is needed.
* **Stability Flags:**
  * `-fPIC`: Position Independent Code is required by Android.
  * `-Wl,--gc-sections`: Garbage collect unused sections during linking.
  * `-Wl,--hash-style=both`: For backward compatibility with older Android dynamic linkers, though `gnu` is standard for newer API levels.
  * `-O3` (Selective): For core mathematical modules, `-O3` must be used with `-ffast-math` disabled, as OCCT and GMP require strict IEEE 754 floating-point precision; fast-math can cause subtle, catastrophic slicing bugs.

## Summary
The pure C++ nature of PrusaSlicer 3.0 eases the CMake configuration, but the strict module boundaries mean the linking phase (specifically handling heavy dependencies like OCCT and Boost) will require careful orchestration using Thin LTO and size-optimization flags to stay within Android's memory and APK size limits.
