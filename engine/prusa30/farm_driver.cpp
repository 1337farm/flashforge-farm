// FlashForge Farm — PrusaSlicer 3.0 headless slice driver (legacy-INI flavor).
//
// Drives the 3.0 slicing surface directly, mirroring the upstream CLI's
// slice flow (slic3r-app-cli ProcessActions.cpp) without the interactor
// stack:
//
//   Biz::load_config_from_legacy_file(ini)   -> Domain::ConfigPack
//   bed_shape/max_print_height from the pack -> Domain::Bed::create(...)
//   init_print(FFF, callbacks, id)           -> unique_ptr<IPrint>
//   IPrint::update(model, pack, bed, meta)   -> ApplyStatus::Status
//   IPrint::slice(id, thumbnails, nullopt)   -> on_fdm_result(FDMResult)
//   ProcessorResult::const_gcode()->str()    -> plain gcode file
//
// The app keeps writing its legacy PrusaSlicer INI (slic3r_current.ini);
// Slic3r::Biz::load_config_from_legacy_file (ConfigLegacy.hpp) is the
// public INI reader wrapper, so no JSON cutover is needed here.
//
#include <android/log.h>
#include <fstream>
#include <functional>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <type_traits>
#include <utility>
#include <variant>
#include <vector>

#include <magic_enum/magic_enum.hpp>

#include "libslic3r/InitPrint.hpp"
#include "libslic3r/IPrint.hpp"
#include "libslic3r/IThumbnailImageGenerator.hpp"
#include "libslic3r/SLAResult.hpp"
#include "libslic3r/GeneratedSupportPoints.hpp"
#include "libslic3r/WipeTowerGeometry.hpp"
#include "libslic3r/SerializedConfig.hpp"
#include "libslic3r/SlicingStatus.hpp"
#include "libslic3r/ThumbnailImageRequest.hpp"
#include "libslic3r/ThumbnailImageResult.hpp"

#include "Slic3r/Biz/Config/ConfigLegacy.hpp"
#include "Slic3r/Biz/FileLoadingLogic.hpp"
#include "Slic3r/Domain/Bed.hpp"
#include "Slic3r/Domain/BedInstance.hpp"
#include "Slic3r/Domain/ConfigPack.hpp"
#include "Slic3r/Domain/Model.hpp"
#include "Slic3r/Domain/Preset/SelectedPreset.hpp"
#include "Slic3r/Domain/SlicingId.hpp"

#include "farm_driver.hpp"

#define TAG "FarmPrusa"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace farm {

namespace Domain = Slic3r::Domain;
namespace ApplyStatus = Slic3r::Biz::Slicing::ApplyStatus;
using Slic3r::Biz::Slicing::FDMResult;
using Slic3r::Biz::Slicing::GeneratedSupportPointsSnapshot;
using Slic3r::Biz::Slicing::IPrint;
using Slic3r::Biz::Slicing::IProcessCallbacks;
using Slic3r::Biz::Slicing::IThumbnailImageGenerator;
using Slic3r::Biz::Slicing::OptWipeTowerGeometry;
using Slic3r::Biz::Slicing::SLAResult;
using Slic3r::Biz::Slicing::SerializedConfig;
using Slic3r::Biz::Slicing::StatusUpdate;
using Slic3r::Biz::Slicing::StatusCode;
using Slic3r::Biz::Slicing::ThumbnailImageRequests;
using Slic3r::Biz::Slicing::ThumbnailImageResults;
using Slic3r::Domain::Bed;
using Slic3r::Domain::BedCreationData;
using Slic3r::Domain::BedInstance;
using Slic3r::Domain::BedType;
using Slic3r::Domain::ConfigPack;
using Slic3r::Domain::ConfigPackFDM;
using Slic3r::Domain::Model;
using Slic3r::Domain::PrinterTechnology;
using Slic3r::Domain::SlicingId;
using Slic3r::Domain::Vec2d;
using Slic3r::Domain::Vec2ds;

namespace {

class FarmCallbacks final : public IProcessCallbacks {
public:
    void on_fdm_result(FDMResult&& result, SlicingId id) override {
        (void) id;
        fdm_result_ = std::move(result);
    }
    void on_sla_result(const SlicingId& id, SLAResult&& result) override {
        (void) id; (void) result;
    }
    void on_sla_object(const SlicingId& id, Slic3r::Biz::Slicing::Sla::Object&& object) override {
        (void) id; (void) object;
    }
    void on_status(const StatusUpdate status, SlicingId id) override {
        (void) id;
        status_ = status;
    }
    void on_exception(std::exception_ptr exception, SlicingId id) override {
        (void) id;
        exception_ = exception;
    }
    void on_wipe_tower_geometry(OptWipeTowerGeometry&& geometry, SlicingId id) override {
        (void) id; (void) geometry;
    }
    void on_extruder_candidates(std::vector<unsigned>&& candidates, SlicingId id) override {
        (void) id; (void) candidates;
    }
    void on_generated_support_points(GeneratedSupportPointsSnapshot&& points, SlicingId id) override {
        (void) id; (void) points;
    }
    StatusCode get_status(const SlicingId id) const override {
        (void) id;
        return StatusCode::Empty;
    }

    FDMResult take_result() { return std::move(fdm_result_); }
    std::exception_ptr exception() const { return exception_; }

private:
    FDMResult fdm_result_{};
    StatusUpdate status_{};
    std::exception_ptr exception_{};
};

class NoThumbnails final : public IThumbnailImageGenerator {
public:
    std::future<ThumbnailImageResults> enqueue_thumbnail_requests(
        const ThumbnailImageRequests& /*requests*/) override
    {
        std::promise<ThumbnailImageResults> p;
        p.set_value(ThumbnailImageResults{});
        return p.get_future();
    }
    void handle_enqueued_requests() override {}
};

// Rectangular, axis-aligned bed detection for BedType derivation: the
// legacy bed_shape carries the raw contour, but 3.0 beds also record their
// type (processing helpers differ). Anything that is not an exact
// axis-aligned rectangle is a generic Custom contour.
bool is_axis_aligned_rectangle(const Vec2ds& contour) {
    if (contour.size() != 4)
        return false;
    const Vec2d& a = contour[0];
    const Vec2d& b = contour[1];
    const Vec2d& c = contour[2];
    const Vec2d& d = contour[3];
    return (a.x() == b.x() || a.y() == b.y()) // consecutive edges alternate
        && (b.x() == c.x() || b.y() == c.y())
        && (c.x() == d.x() || c.y() == d.y())
        && (d.x() == a.x() || d.y() == a.y());
}

// Reads bed_shape (vector<Vec2d>) and max_print_height (double) from the
// FDM printer box and builds the 3.0 Bed.
Domain::Bed bed_from_config(const ConfigPackFDM& fdm) {
    const auto& printer = fdm.printer;
    const Slic3r::Domain::ConfigItem* shape_item = printer.items.find("bed_shape");
    if (shape_item == nullptr)
        throw std::runtime_error("config is missing bed_shape (printer settings)");
    const Slic3r::Domain::ConfigItem* height_item = printer.items.find("max_print_height");

    Vec2ds contour = shape_item->get<Vec2ds>();
    if (contour.size() < 3)
        throw std::runtime_error("bed_shape has fewer than 3 points");
    const float max_print_height = height_item != nullptr
        ? static_cast<float>(height_item->get<double>())
        : 200.0f;

    BedCreationData data;
    data.type = is_axis_aligned_rectangle(contour) ? BedType::Rectangle : BedType::Custom;
    data.contour = std::move(contour);
    data.max_print_height = max_print_height;
    return Bed::create(data);
}

std::string stage_name(Slic3r::Biz::Slicing::ProgressInfo stage) {
    std::string name{magic_enum::enum_name(stage)};
    return name.empty() ? "slicing" : name;
}

// Rewrites the app-written legacy INI into a sibling temp file, fixing 2.x
// dialect values the 3.0 config definitions reject. Everything passes through
// untouched except verified quirks:
//   ironing: 2.x folded the enable into ironing_type ("no ironing" = off);
//   3.0 split it into `ironing` (bool) + ironing_type in {top, topmost, solid}.
//   An invalid value otherwise aborts the whole config load with
//   "Invalid value provided for parameter ironing_type: no ironing".
std::string normalize_legacy_ini(const std::string& ini_path) {
    std::ifstream in(ini_path);
    if (!in)
        throw std::runtime_error("cannot open config file " + ini_path);
    std::ostringstream out;
    std::string line;
    const auto value_of = [](std::string l) {
        const auto eq = l.find('=');
        std::string v = eq == std::string::npos ? "" : l.substr(eq + 1);
        // trim spaces + optional quotes (legacy configs quote some values)
        const auto first = v.find_first_not_of(" \t\"");
        const auto last = v.find_last_not_of(" \t\"\r");
        return first == std::string::npos ? "" : v.substr(first, last - first + 1);
    };
    const auto key_of = [](const std::string& l) {
        const auto eq = l.find('=');
        if (eq == std::string::npos) return std::string{};
        auto k = l.substr(0, eq);
        const auto last = k.find_last_not_of(" \t");
        if (last == std::string::npos) return std::string{};
        k = k.substr(0, last + 1);
        const auto first = k.find_first_not_of(" \t");
        return first == std::string::npos ? std::string{} : k.substr(first);
    };
    bool has_ironing_key = false;
    {
        // Pre-scan: does the file already carry an explicit `ironing` bool?
        std::ifstream scan(ini_path);
        std::string l;
        while (std::getline(scan, l)) {
            if (key_of(l) == "ironing") { has_ironing_key = true; break; }
        }
    }
    while (std::getline(in, line)) {
        if (key_of(line) == "ironing_type") {
            std::string v = value_of(line);
            if (v == "no ironing" || v == "no_ironing" || v == "none") {
                // Ironing off: 3.0 has no "none" enum value; the bool carries it.
                if (!has_ironing_key) { out << "ironing = 0\n"; has_ironing_key = true; }
                continue; // drop the type line
            }
            if (v == "all top surfaces" || v == "all_top_surfaces") v = "top";
            else if (v == "topmost surface" || v == "topmost_surface") v = "topmost";
            else if (v == "all solid layers" || v == "all_solid_layers") v = "solid";
            if (!has_ironing_key) { out << "ironing = 1\n"; has_ironing_key = true; }
            out << "ironing_type = " << v << "\n";
            continue;
        }
        out << line << '\n';
    }
    const std::string normalized_path = ini_path + ".prusa30";
    std::ofstream outf(normalized_path, std::ios::binary | std::ios::trunc);
    if (!outf)
        throw std::runtime_error("cannot write normalized config " + normalized_path);
    outf << out.str();
    outf.close();
    if (!outf)
        throw std::runtime_error("writing normalized config " + normalized_path + " failed");
    return normalized_path;
}

} // namespace

SliceStats slice_to_gcode(Model& model,
                          const std::string& legacy_ini_path,
                          const std::string& gcode_out_path,
                          std::function<void(int percent, const std::string& stage)> progress) {
    // 1. Legacy INI -> 3.0 ConfigPack (public wrapper over the src-private
    //    Slic3rLegacy::DynamicPrintConfig reader; may throw). The file is
    //    normalized first (ironing et al.): 2.x dialect values 3.0 rejects
    //    would otherwise abort the whole load.
    const std::string normalized_ini = normalize_legacy_ini(legacy_ini_path);
    ConfigPack config = Slic3r::Biz::load_config_from_legacy_file(normalized_ini);
    const ConfigPackFDM* fdm = std::get_if<ConfigPackFDM>(&config);
    if (fdm == nullptr)
        throw std::runtime_error("the config does not describe an FDM printer");

    // 2. Bed from the config's bed_shape/max_print_height.
    Domain::Bed bed = bed_from_config(*fdm);
    BedInstance bed_instance{bed};

    // 3. Minimal preset metadata (gcode header data); the legacy INI has no
    //    preset identity, and slicing only consumes the technology here.
    Slic3r::Domain::Preset::SelectedPresetMetadata metadata;
    metadata.hw_config.technology = PrinterTechnology::FFF;

    // Serialize universal print statistics to a SerializedConfig (project stats).
    auto serializer = [](const IPrint::UniversalPrintStatistics&) {
        return SerializedConfig{};
    };

    const SlicingId id{};
    FarmCallbacks callbacks;
    std::unique_ptr<IPrint> print = init_print(PrinterTechnology::FFF, callbacks, id);
    if (!print)
        throw std::runtime_error("init_print returned null");

    if (progress)
        print->progress_callback = [&progress](Slic3r::Biz::Slicing::Progress p) {
            progress(static_cast<int>(p.progress.value), stage_name(p.progress_info));
        };

    // 4. apply(): model + config + bed -> slicing input.
    const auto status = print->update(model, config, bed_instance, metadata, serializer);
    if (std::holds_alternative<ApplyStatus::InvalidData>(status)) {
        const auto& invalid = std::get<ApplyStatus::InvalidData>(status);
        std::ostringstream os;
        os << "the model or config is invalid for slicing (" << invalid.errors.size() << " errors)";
        for (const auto& error : invalid.errors)
            os << "\n- " << error;
        LOGE("%s", os.str().c_str());
        throw std::runtime_error(os.str());
    }

    // 5. slice(): synchronous; result lands via on_fdm_result.
    NoThumbnails thumbnails;
    print->slice(id, thumbnails, std::nullopt);

    if (callbacks.exception())
        std::rethrow_exception(callbacks.exception());

    FDMResult result = callbacks.take_result();
    if (result.const_gcode() == nullptr || result.const_gcode()->empty())
        throw std::runtime_error("slicing produced no gcode");

    // 6. Export: ProcessorResult embeds the plain gcode buffer.
    {
        std::ofstream out(gcode_out_path, std::ios::binary | std::ios::trunc);
        if (!out)
            throw std::runtime_error("cannot open " + gcode_out_path + " for writing");
        out << result.const_gcode()->str();
        out.flush();
        if (!out)
            throw std::runtime_error("writing " + gcode_out_path + " failed");
    }

    // 7. Filament stats for the app's result panel (Java role ints are 1:1
    //    with Domain::GCodeExtrusionRole ordering — see farm_driver.hpp).
    SliceStats stats;
    std::visit([&stats](const auto& print_statistics) {
        for (const auto& [role, mm_g] : print_statistics.used_filaments_per_role)
            stats.per_role[static_cast<int>(role)] = mm_g;
        if constexpr (std::is_same_v<std::decay_t<decltype(print_statistics)>,
                                     Domain::FullPrintStatistics>) {
            stats.per_extruder_mm = print_statistics.used_filament_per_extruder_mm;
            stats.per_extruder_g = print_statistics.used_filament_per_extruder_g;
        }
    }, result.print_statistics);
    return stats;
}

} // namespace farm
