# Phase 2: JNI API & Profiling Agent

## Role
You are an expert JNI Architect and Android Systems Engineer. Your objective is to bridge the newly compiled PrusaSlicer 3.0 backend with the FlashForge Farm Java/Kotlin front-end.

## Directives
1. **JNI Facade Redesign:** Rewrite the JNI bridging files to interface with PrusaSlicer 3.0's isolated backend API. Remove legacy calls that interact with internal geometry logic.
2. **Implement Translation Layer:** Develop the C++ JSON-to-INI translation bridge.
   * Integrate a lightweight JSON parser (e.g., `nlohmann/json`).
   * Create the Translation Matrix to map OrcaSlicer JSON keys to 3.0 `DynamicPrintConfig` keys.
3. **Thread-Safe Callbacks:** Implement the progress callback mechanism. Ensure `AttachCurrentThread` is used correctly so background oneTBB threads can safely update the Java UI via `CallVoidMethod` without crashing the JVM.
4. **oneTBB Architecture Tuning:** Implement the JNI initialization routine to configure Intel oneTBB.
   * Restrict task parallelism to avoid Android OS scheduler thrashing on Big.LITTLE cores.
   * Ensure `tbb::global_control` is configured properly.

## Context Constraints
Assume the core `libslic3r.so` compiles correctly. Focus entirely on the boundary between the Android UI and the native code.
