# 5. 4-Phase Mobile Migration Roadmap

This section outlines a concrete, phase-by-phase engineering strategy to safely transition the Android app backend to the PrusaSlicer 3.0 engine.

## Phase 1: Toolchain & Dependency Proof-of-Concept (POC)
**Goal:** Successfully cross-compile the PrusaSlicer 3.0 core library (`libslic3r.so`) for Android `arm64-v8a` without any UI integration.

* **Step 1.1: Environment Setup.** Configure Android NDK 23+ and CMake. Establish the pure C++17/C++20 environment, verifying the removal of legacy Perl/XS bindings.
* **Step 1.2: Dependency Resolution.** Compile Boost, oneTBB, OCCT, and GMP/MPFR as static libraries or dynamically loadable modules for ARM64. Ensure atomic operations map correctly in oneTBB.
* **Step 1.3: Core Compilation.** Compile the core `libslic3r` code. Apply critical compiler flags: `-Os` (size optimization), `-flto=thin` (Link-Time Optimization), and `-fPIC`.
* **Step 1.4: Dummy CLI Slicing.** Write a minimal C++ wrapper (without JNI) that loads a hardcoded STL and a hardcoded INI config, then executes a slice directly on the Android device via ADB shell.
* **Milestone:** A functional, standalone `libslic3r.so` binary running on an Android test device producing valid G-code.

## Phase 2: JNI API Mapping & Thread Management
**Goal:** Connect the compiled 3.0 engine to the FlashForge Farm Java/Kotlin application architecture.

* **Step 2.1: JNI Interface Redesign.** Create the new JNI facades bridging the decoupled 3.0 backend. Focus on the core lifecycle: Init -> Load Profile -> Load Model -> Slice -> Cancel/Complete.
* **Step 2.2: The Config Translation Layer.** Implement the C++ JSON-to-INI translation bridge (as detailed in Document 3) using a fast JSON parser to convert Orca JSON payloads into `DynamicPrintConfig` objects.
* **Step 2.3: Thread & Callback Safety.** Implement the oneTBB initialization routines to restrict slicing to ARM "Big" cores. Build thread-safe JNI callback mechanisms for progress reporting to the Android UI without blocking or crashing the JVM.
* **Milestone:** The Android app UI can successfully initiate a slice using its existing JSON profiles, update progress bars, and receive completion status.

## Phase 3: Geometry & Feature Porting
**Goal:** Migrate and verify all custom OrcaSlicer-based mobile features.

* **Step 3.1: Model Transformation & Multi-Plate.** Hook the JNI layout math into the 3.0 `Model` and `Print` APIs. Ensure virtual multi-plate coordinates translate correctly into the slicer's expected bounding boxes.
* **Step 3.2: Multi-Color Painting Validation.** Refactor the custom painting logic (brush, bucket-fill, etc.) to utilize the 3.0 multi-material API or "Modifier Volumes". Ensure touched vertices map correctly to the new mesh representations.
* **Step 3.3: Prime Tower & Flush Volumes.** Verify the translation of flush multipliers and tower dimensions from the JSON profiles to the 3.0 engine.
* **Milestone:** All advanced geometries (multi-color objects, multi-plate layouts) slice correctly, producing accurate tool changes and wipe towers.

## Phase 4: Regression Testing, Profiling, & Tuning
**Goal:** Ensure absolute stability, correct G-code output, and acceptable memory/performance metrics on target hardware.

* **Step 4.1: Automated Regression Testing.** Run automated suites comparing the G-code output of the old Orca engine against the new 3.0 engine for a standardized set of test models and profiles. Output *must* be functionally equivalent or demonstrably better.
* **Step 4.2: Memory Profiling.** Use Android Studio Profiler and native memory tracking tools (like `heapprofd`) to monitor memory consumption during complex slices (e.g., Arachne on high-poly models). Ensure the app does not exceed OS limits (typically 1GB-2GB per app).
* **Step 4.3: Thermal & Battery Tuning.** Monitor CPU core utilization. Adjust the oneTBB concurrency limits (Step 2.3) if thermal throttling occurs.
* **Step 4.4: Beta Deployment.** Release to a closed testing group via APK sideloading.
* **Milestone:** A production-ready, stable Android release candidate featuring the PrusaSlicer 3.0 core.
