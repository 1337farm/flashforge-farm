// Engine compatibility shims for the PrusaSlicer engine swap.
//
// This translation unit provides:
//   1. The single nanosvg implementation (the rest of the engine only includes the header).
//   2. Stub definitions for engine features whose third-party backends are not vendored in
//      this Android build, so the libslic3r library links. The corresponding source files
//      (Format/DRC.cpp, MeshBoolean.cpp) are intentionally excluded from the build because
//      their dependencies (draco, mcut) are not available here.

// --- nanosvg single-TU implementation -------------------------------------------------
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"

// NOTE (#210): the Draco load_drc() stubs lived here for the vendored 2.x
// tree. Their only callers were deleted with that tree and upstream 3.0
// owns DRC itself now, so the stubs (and the libslic3r includes) are gone.
