#pragma once
#include <string>
#include <functional>
#include "Slic3r/Biz/Slicing/FDMResult.hpp"

namespace farm {
Slic3r::Biz::Slicing::FDMResult slice(const std::string& model_path, const std::string& config_json_path,
    std::function<void(const Slic3r::Biz::Slicing::Progress&)> progress);
}
