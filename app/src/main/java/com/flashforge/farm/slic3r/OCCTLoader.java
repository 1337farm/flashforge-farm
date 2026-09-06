package com.flashforge.farm.slic3r;

/**
 * OCCT is statically linked into libslic3r.so (the engine links the OCCT
 * archives with --gc-sections; see engine/CMakeLists.txt and
 * scripts/build_all_deps_android.sh). There are no libTK*.so runtime libraries
 * to load anymore — this class exists only as a compatibility point for
 * Native's static-initializer sequence.
 */
class OCCTLoader {
    static void load() {
        // No per-toolkit System.loadLibrary: all OCCT code lives inside
        // libslic3r.so (loaded by Native). A stale libTK*.so load here would
        // fail to crash-free apps and warned silently before, but now simply
        // must not happen.
    }
}
