#pragma once
#include <string>
#include <functional>
#include "libslic3r/InitPrint.hpp"
#include "libslic3r/SlicingStatus.hpp"

namespace farm {
Slic3r::Biz::Slicing::FDMResult slice(const std::string& model_path, const std::string& config_json_path,
    std::function<void(Slic3r::Biz::Slicing::Progress)> progress);
}
