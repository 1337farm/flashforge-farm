# FlashForge Farm: PrusaSlicer 3.0 Core Migration Assessment

This document outlines the architectural feasibility and structured engineering strategy for migrating the FlashForge Farm mobile 3D slicing app from its current OrcaSlicer-based `libslic3r` core to the newly refactored PrusaSlicer 3.0 core.

## Assessment Documents

The technical assessment is divided into the following key areas:

1. [Toolchain Compatibility (Android NDK & CMake)](01_toolchain_compatibility.md)
   * Immediate compilation, POSIX alignment, and linking obstacles for `arm64-v8a`.
   * Impact of removing legacy Perl-to-C++ abstractions.
   * Compiler flags and memory footprint constraints for stability on mobile devices.

2. [Decoupled Backend API & JNI Layer Impact](02_jni_api_impact.md)
   * Analysis of PrusaSlicer 3.0's strict module boundaries and API isolation.
   * Impact on Android Java/Kotlin layer communication via JNI.
   * Handling thread pools via oneTBB on ARM Big.LITTLE architectures.

3. [JSON Profile Abstraction vs. Modular INI Engine](03_profile_abstraction.md)
   * Transitioning from OrcaSlicer-style JSON profile inheritance bundles to PrusaSlicer 3.0's modular INI-based engine.
   * Low-overhead C++ profile translation layer design to avoid front-end rewrites.

4. [Mobile Feature Preservation & Porting Risk](04_feature_porting.md)
   * Assessing the complexity of porting Orca-specific geometries (multi-color painting, multi-plate layout, etc.).
   * Integrating with the new sandboxed, plugin-friendly architecture.

5. [4-Phase Mobile Migration Roadmap](05_migration_roadmap.md)
   * Concrete engineering strategy from toolchain proof-of-concept to regression testing.

## OpenCode LLM Agent Directives

The following agent directive files are provided to guide specialized LLM agents (e.g., OpenCode agents) in executing specific phases of the migration:

* [Phase 1: Toolchain & Build Migration Agent](agents/agent_phase1_toolchain.md)
* [Phase 2: JNI API & Profiling Agent](agents/agent_phase2_integration.md)
* [Phase 3: Geometry & Feature Porting Agent](agents/agent_phase3_features.md)
* [Phase 4: Optimization & Tuning Agent](agents/agent_phase4_tuning.md)
