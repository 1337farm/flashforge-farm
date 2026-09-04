# 4. Mobile Feature Preservation & Porting Risk

## Overview
FlashForge Farm has implemented native mobile features layered over the Orca engine, including complex multi-color mesh painting, multi-plate layout math, prime tower generators, and native geometry transforms. Moving to PrusaSlicer 3.0's sandboxed, plugin-friendly architecture presents both opportunities and significant porting risks for these custom features.

## Multi-Color Mesh Painting (Brush, Bucket-Fill, Height-Range)
* **Current State:** OrcaSlicer handles multi-material painting by embedding color data into the vertices or facets of the `TriangleMesh` or via auxiliary data structures applied right before the slicing pipeline.
* **PrusaSlicer 3.0 Impact:** PrusaSlicer 3.0 has completely overhauled object manipulation and multi-material handling. The API is stricter about how model volumes are defined and modified.
* **Porting Risk:** **High.** The custom Android painting logic (which likely maps screen touches to raycasts against the OCCT or proprietary mesh data) will need to interface with a new internal data structure.
* **Mitigation:** The new architecture is plugin-friendly. Instead of hacking the core mesh classes, the painting tools should be refactored into a separate C++ module that generates "Modifier Volumes" or utilizes the new multi-material segmentation APIs in 3.0. This keeps the custom logic sandboxed from upstream core updates.

## Multi-Plate Layout Math & Auto-Arrange
* **Current State:** FlashForge Farm manages multiple build plates, likely by managing offsets in a global coordinate space before sending specific objects to the slicer.
* **PrusaSlicer 3.0 Impact:** PrusaSlicer uses `libnest2d` for arrangement. The 3.0 architecture provides cleaner abstractions for multiple print jobs.
* **Porting Risk:** **Low to Medium.** The core layout math in the Android app (Java/Kotlin) can likely remain untouched. The challenge is ensuring the JNI layer correctly translates the coordinates of objects on a specific "virtual plate" into the strict `Print` bounding box expected by the 3.0 slicing backend.

## Prime Tower Generators & Flush Volumes
* **Current State:** Handled natively by Orca's multi-material logic, generating wiping and priming toolpaths.
* **PrusaSlicer 3.0 Impact:** PrusaSlicer 3.0 has significantly updated its Wipe Tower logic (especially for the Prusa XL multi-toolhead).
* **Porting Risk:** **Medium.** If FlashForge Farm relies entirely on the engine to generate the tower, the transition relies on correctly mapping the JSON profile settings (flush multipliers, tower size) to the new 3.0 config keys (see Document 3). If custom toolpath generation was injected, it must be rewritten to conform to the new Extruder and Tool mapping boundaries.

## Native Geometry Transforms (3MF/STEP/OBJ/STL)
* **Current State:** The app imports and transforms these formats natively, likely leveraging OCCT for STEP and ASSIMP/custom parsers for meshes.
* **PrusaSlicer 3.0 Impact:** 3.0 handles STEP imports (via OCCT) and meshes robustly.
* **Porting Risk:** **Low.** The geometry loading and transformation pipelines (scale, rotate, cut) are standard linear algebra operations applied to the `ModelObject`. PrusaSlicer 3.0 provides clean APIs for this. The main risk is ensuring memory is managed correctly during heavy STEP-to-Mesh tessellation on mobile devices (requires strict memory limits and OCCT optimization flags).

## The Sandboxed Plugin Architecture
* **The Opportunity:** PrusaSlicer 3.0's move towards a plugin/module architecture is the biggest asset for this migration. It allows FlashForge Farm to maintain its custom logic (like the JSON translation layer or custom painting modifiers) in isolated C++ files that link against the core, rather than maintaining a massive fork of modified core files. This drastically reduces future merge conflicts when updating to 3.1 or 4.0.
