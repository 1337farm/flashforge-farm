# 2. Decoupled Backend API & JNI Layer Impact

## Overview
PrusaSlicer 3.0 introduces a strict separation of concerns, isolating the backend slicing engine into distinct modules and providing a dedicated offline mode. This API isolation significantly alters how the FlashForge Farm Android application will interface with the C++ core via JNI.

## JNI Layer Impact & API Isolation
* **Strict Module Boundaries:** The new architecture forces a clean break between UI/Application logic and the slicing engine. This is highly beneficial for an Android app, as it prevents UI thread blocking and memory leaks across the JNI boundary.
* **JNI Interface Redesign:** Our existing JNI layer, which likely interacted with tightly coupled `Print`, `PrintObject`, and `Model` classes in the OrcaSlicer engine, will need to be rewritten to interface with the new API facades. The new API is designed to accept an encapsulated state and emit a sliced output, minimizing the need for granular JNI calls for every geometry manipulation.
* **Progress Callbacks:** The isolated backend API provides robust hooking points for progress and cancellation. Instead of polling, the JNI layer can register native C++ callbacks that bridge to Java via `CallVoidMethod`.
  * *Critical Constraint:* These callbacks occur on background worker threads. The JNI bridge must ensure it attaches the current thread to the JVM (`AttachCurrentThread`) before invoking the Java callback and detaches afterward, or carefully route messages through a C++ thread-safe queue consumed by a dedicated JNI dispatcher thread to avoid JVM crashes.

## oneTBB and ARM Big.LITTLE Architecture
Android devices utilize ARM Big.LITTLE (or newer dynamically clustered) CPU architectures, mixing high-performance (power-hungry) cores with high-efficiency (low-power) cores.

* **oneTBB Thread Pool Management:** PrusaSlicer uses Intel's oneTBB for task-based parallelism. On standard x86 desktops, TBB scales linearly. On ARM Big.LITTLE, blind scaling can be disastrous.
* **The OS Scheduler Problem:** If oneTBB spawns threads equal to the total core count, the Linux kernel scheduler on Android may bounce heavy geometry calculations between "Big" and "LITTLE" cores. This causes thermal throttling, cache invalidation, and severe battery drain, often resulting in slower slicing than a single-threaded execution.
* **Mitigation Strategy via JNI:**
  1. **Thread Affinity:** We must implement a JNI initialization function that configures the oneTBB `task_arena` and `global_control`.
  2. **Core Masking:** The Android app (via Java) should query `Runtime.getRuntime().availableProcessors()` and determine the core topology (often by reading `/sys/devices/system/cpu/`). It should pass a configuration to the C++ backend to restrict oneTBB to *only* use the "Big" cores for intensive tasks (like Arachne wall generation).
  3. **Concurrency Limits:** Set `tbb::global_control::max_allowed_parallelism` to a sensible limit (e.g., `Num_Big_Cores - 1`) to ensure the Android OS remains responsive during slicing.

## Dedicated Offline Mode
PrusaSlicer 3.0's offline mode aligns perfectly with FlashForge Farm's "fully offline" requirement. This completely bypasses legacy application network libraries (like curl dependencies often baked into desktop slicers), reducing the NDK compilation footprint and removing the need to stub out network calls in the JNI layer. The JNI interface can focus purely on data ingestion (profiles, geometry) and data output (G-code, thumbnails).
