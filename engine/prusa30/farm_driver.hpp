#pragma once
#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "Slic3r/Domain/Model.hpp"

namespace farm {

// Keyed by the Java-side GCodeViewer.ExtrusionRole int constants (verified
// 1:1 with Domain::GCodeExtrusionRole ordering) -> (length [mm], mass [g]).
using RoleFilamentStats = std::map<int, std::pair<float, float>>;

struct SliceStats {
    RoleFilamentStats per_role;
    std::vector<float> per_extruder_mm;
    std::vector<float> per_extruder_g;
};

// Slices `model` (already loaded via FileLoadingLogic) with the app-written
// legacy PrusaSlicer INI config and writes the resulting plain gcode to
// `gcode_out_path`. Progress is reported as a 0-100 percentage plus a
// human-readable stage name, on the calling thread.
// Throws std::runtime_error on config/apply/slice/IO failure — never
// returns silent success.
SliceStats slice_to_gcode(Slic3r::Domain::Model& model,
                          const std::string& legacy_ini_path,
                          const std::string& gcode_out_path,
                          std::function<void(int percent, const std::string& stage)> progress);

} // namespace farm
