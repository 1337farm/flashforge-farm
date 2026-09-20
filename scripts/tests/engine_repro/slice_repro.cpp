// slice_repro — headless unit repro for slice-stage "Slicing exception".
//
// Drives the REAL farm::slice_to_gcode (engine/prusa30/farm_driver.cpp,
// linked in) with a model file + legacy INI, mirroring Native.model_slice:
//   FileLoadingLogic::read_model_from_file
//   BizAlgo::ModelObject::ensure_on_bed per object (mirrors
//     Native.model_ensure_on_bed <- Model.ensureOnBed <- BedFragment load)
//   farm::slice_to_gcode (normalize_legacy_ini, legacy INI load,
//     bed_from_config, metadata, update, slice, gcode export)
//
// Usage: slice_repro <legacy.ini> <model.stl> <out.gcode>
//
// Exit codes:
//   0  sliced OK (gcode written, non-empty)
//   2  explicit failure (model load failed, config/apply/IO error — the
//      message names the cause)
//   3  bare SlicingStatus::Exception from the slice stage (the crash under
//      test): prints SLICING_EXCEPTION code=<int> keys=[...] object=<id>
//   64 usage
//
// Build (CI headless, next to the farm_driver check compile):
//   clang++ -std=c++20 -c engine/prusa30/farm_driver.cpp <INC> -o /tmp/farm_driver.o
//   clang++ -std=c++20 -c scripts/tests/engine_repro/slice_repro.cpp <INC>
//       -Iengine/prusa30 -o /tmp/slice_repro.o
//   clang++ /tmp/slice_repro.o /tmp/farm_driver.o <libslic3r.a + dep libs>
//       -o /tmp/slice_repro

#include <cstdarg>
#include <cstdio>
#include <exception>
#include <string>

#include "Slic3r/Biz/Algorithms/ModelObject.hpp"
#include "Slic3r/Biz/FileLoadingLogic.hpp"
#include "Slic3r/Domain/Model.hpp"
#include "libslic3r/SlicingStatus.hpp"

#include "farm_driver.hpp"

namespace BizAlgo = Slic3r::Biz::Algorithms;

namespace {

void log_marker(const char* fmt, ...) {
    std::fprintf(stdout, "[slice_repro] ");
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stdout, fmt, ap);
    va_end(ap);
    std::fprintf(stdout, "\n");
    std::fflush(stdout);
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s <legacy.ini> <model.stl> <out.gcode>\n", argv[0]);
        return 64;
    }
    const std::string ini_path = argv[1];
    const std::string model_path = argv[2];
    const std::string gcode_path = argv[3];

    try {
        log_marker("load model %s", model_path.c_str());
        auto loaded = Slic3r::Biz::FileLoadingLogic::read_model_from_file(model_path, nullptr);
        if (!loaded) {
            std::fprintf(stderr, "REPRO_ERROR model load failed: %s\n", loaded.error().c_str());
            return 2;
        }
        Slic3r::Domain::Model model = std::move(loaded.value());
        log_marker("model loaded: %zu object(s)", model.objects.size());
        for (size_t i = 0; i < model.objects.size(); ++i) {
            Slic3r::Domain::ModelObject* obj = model.objects[i];
            if (obj == nullptr) continue;
            log_marker("  object %zu '%s' volumes=%zu instances=%zu",
                i, obj->name.c_str(), obj->volumes.size(), obj->instances.size());
            BizAlgo::ModelObject::ensure_on_bed(*obj, false);
        }

        log_marker("slice_to_gcode start");
        auto progress = [](int percent, const std::string& stage) {
            std::fprintf(stdout, "[slice_repro] progress %d%% stage=%s\n",
                percent, stage.c_str());
            std::fflush(stdout);
        };
        farm::SliceStats stats = farm::slice_to_gcode(model, ini_path, gcode_path, progress);
        log_marker("SLICED OK roles=%zu", stats.per_role.size());
        return 0;
    } catch (const Slic3r::Biz::Slicing::Exception& e) {
        const auto& err = e.error();
        std::fprintf(stderr, "SLICING_EXCEPTION code=%d keys=[",
            static_cast<int>(err.code));
        for (size_t i = 0; i < err.item_keys.size(); ++i)
            std::fprintf(stderr, "%s%s", i ? "," : "", err.item_keys[i].c_str());
        if (err.model_object_id)
            std::fprintf(stderr, "] object=%zu\n",
                static_cast<size_t>(err.model_object_id->id));
        else
            std::fprintf(stderr, "] object=-\n");
        std::fflush(stderr);
        return 3;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "REPRO_ERROR %s\n", e.what());
        return 2;
    }
}
