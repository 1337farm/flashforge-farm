# 3. JSON Profile Abstraction vs. Modular INI Engine

## Overview
FlashForge Farm currently relies on OrcaSlicer's JSON-based profile bundles (`.orca_printer`, `.orca_filament`), resolving inheritance and configuration layering locally on the Android device. PrusaSlicer 3.0 has shifted to a new INI-based engine structured around discrete tool and extruder maps. Transitioning between these data models requires a strategic translation layer to avoid rewriting the entire Android Java/Kotlin front-end profile management system.

## The Architectural Friction
* **Orca JSON Model:** Highly hierarchical. Profiles inherit from base vendor JSONs. The front-end is likely heavily optimized to parse, merge, and present these hierarchical JSON structures to the user in the UI.
* **PrusaSlicer 3.0 INI Model:** Flat, key-value oriented, and heavily reliant on dynamic config dictionaries (`DynamicPrintConfig`) mapped directly to specific tools, extruders, and print regions.

## Lowest-Overhead Migration Strategy: The C++ Translation Layer
To prevent a total rewrite of the Android profile editor and JSON parser, the translation must occur across the JNI boundary, wholly contained within the native C++ codebase.

### Approach: JNI Config Bridge
1. **JSON Payload Delivery:** The Android application continues to resolve inheritance, merge profiles, and construct the final, flattened JSON configuration state representing the print job. This final JSON is passed via JNI as a single `std::string` (or a direct mapped `jbyteArray` for performance).
2. **C++ Native JSON Parsing:** Integrate a lightweight, fast C++ JSON parser (like `nlohmann/json` or `simdjson`) into the JNI translation module.
3. **Key-Value Mapping (The Translation Matrix):**
   * Develop a static mapping dictionary in C++ that translates Orca JSON keys (e.g., `brim_width`, `support_type`) to their exact PrusaSlicer 3.0 INI equivalents inside the `DynamicPrintConfig` object.
   * *Complexity:* Many Orca settings do not map 1:1. For example, Arachne settings or multi-extruder parameters might be represented differently. The translation layer must handle conditional logic (e.g., if JSON `wall_generator == "arachne"`, set PS 3.0 keys X, Y, and Z).
4. **Instantiating the 3.0 Config:** The translation layer iterates over the parsed JSON, uses the Translation Matrix to look up the correct PS 3.0 config key, and populates a new `Slic3r::DynamicPrintConfig` instance.
5. **Tool/Extruder Map Generation:** PrusaSlicer 3.0 requires explicit tool mapping. The C++ layer must parse the JSON filament arrays and construct the necessary `Extruder` objects and assign them to the `PrintConfig` before initializing the slicing engine.

## Memory and Performance Considerations
* **Avoid String Copies:** Passing large JSON strings via JNI can cause memory spikes and trigger the Android Garbage Collector. Use `GetDirectBufferAddress` with a `ByteBuffer` to pass the JSON payload natively without copying.
* **Parsing Overhead:** While parsing JSON adds a slight overhead compared to native INI parsing, doing it once per slice initiation in C++ is negligible (single-digit milliseconds) compared to the actual slicing process.
* **Maintenance:** This approach creates a "maintenance bridge". Whenever PrusaSlicer 3.0 updates its config keys, or Orca JSON schemas change, this C++ Translation Matrix must be updated. However, this is significantly less risky and time-consuming than rewriting the entire Android UI data layer.
