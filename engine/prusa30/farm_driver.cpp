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

// Formats a SlicingStatus::Exception (whose what() is always the bare
// "Slicing exception") with its numeric ErrorCode + item context, so both
// the synchronous-throw and async-callback paths log the same diagnosis.
std::string describe_slice_error(const char* where,
        const Slic3r::Biz::Slicing::Exception& e) {
    const auto& err = e.error();
    std::ostringstream os;
    os << "Slicing failed at " << where
       << " (ErrorCode=" << static_cast<int>(err.code) << ")";
    for (const auto& key : err.item_keys) os << " [" << key << "]";
    if (err.model_object_id)
        os << " object_id=" << err.model_object_id->id;
    return os.str();
}

// Reads the legacy INI's nozzle_diameter (comma list, one entry per tool)
// for the hardware tool metadata. SlicingInput rejects an empty tool list
// (NoHwConfigTools) and tools without a nozzle_diameter feature
// (MissingHwConfigNozzleDiameter). Falls back to a single 0.4 nozzle so a
// missing key degrades to defaults instead of failing the slice.
std::vector<double> read_nozzle_diameters(const std::string& ini_path) {
    std::ifstream in(ini_path);
    std::string line;
    while (std::getline(in, line)) {
        const auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        const auto key_last = key.find_last_not_of(" \t");
        if (key_last == std::string::npos) continue;
        key = key.substr(0, key_last + 1);
        const auto key_first = key.find_first_not_of(" \t");
        if (key_first != std::string::npos) key = key.substr(key_first);
        if (key != "nozzle_diameter") continue;
        std::string value = line.substr(eq + 1);
        const auto first = value.find_first_not_of(" \t\"");
        const auto last = value.find_last_not_of(" \t\"\r");
        if (first == std::string::npos) continue;
        value = value.substr(first, last - first + 1);
        std::vector<double> out;
        size_t pos = 0;
        while (pos < value.size() && out.size() < 16) {
            const size_t comma = value.find(',', pos);
            const std::string tok = comma == std::string::npos
                ? value.substr(pos) : value.substr(pos, comma - pos);
            try {
                const double d = std::stod(tok);
                if (d > 0.0 && d < 5.0) out.push_back(d);
            } catch (const std::exception&) { /* skip malformed entry */ }
            if (comma == std::string::npos) break;
            pos = comma + 1;
        }
        if (!out.empty()) return out;
    }
    return {0.4};
}

// Builds the minimal hardware metadata the slice path requires: the FFF
// technology plus one tool per nozzle diameter (nozzle_diameter feature).
// tool_count matches so material_slot_count() covers the tools; preset
// names stay empty (they only feed gcode placeholder strings).
Slic3r::Domain::Preset::SelectedPresetMetadata build_metadata(
    const std::vector<double>& nozzle_diameters) {
    Slic3r::Domain::Preset::SelectedPresetMetadata metadata;
    metadata.hw_config.technology = PrinterTechnology::FFF;
    metadata.hw_config.tool_count = static_cast<uint8_t>(nozzle_diameters.size());
    for (const double d : nozzle_diameters) {
        Slic3r::Domain::Preset::HwToolConfig tool;
        tool.features["nozzle_diameter"] = d;
        metadata.hw_config.tools.push_back(std::move(tool));
    }
    return metadata;
}

// Rewrites the app-written legacy INI into a sibling temp file, fixing 2.x
// dialect values the 3.0 config definitions reject. Everything passes through
// untouched except verified quirks:
//   ironing: 2.x folded the enable into ironing_type ("no ironing" = off);
//   3.0 split it into `ironing` (bool) + ironing_type in {top, topmost, solid}.
//   support_material / gcode_label_objects: 2.x bools; 3.0 enums
//   ({none, enforcers_only, everywhere} / {disabled, octoprint, firmware}).
//   The 2.x label feature emitted OctoPrint-style labels.
//   pressure_advance: 2.x float K-value; 3.0 enum. Nonzero means enabled
//   (calibration state is not inferable from the value).
//   brim_type auto_brim (Orca): no 3.0 counterpart; outer_and_inner is the
//   adhesion-safe superset. ensure_all -> enabled; disabled_fuzzy -> none.
//   support_material / enable_support + gcode_label_objects + arc_fitting:
//   2.x/Orca bools; 3.0 enums (everywhere/none, octoprint/disabled,
//   emit_center/disabled).
//   fill_pattern crosshatch (Orca) -> grid; support_material_style default
//   -> grid; support_material_pattern default -> rectilinear;
//   top_fill_pattern monotonicline (Orca singular) -> monotoniclines.
// An invalid value otherwise aborts the whole config load, e.g.
// "Invalid value provided for parameter ironing_type: no ironing".
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
        if (key_of(line) == "support_material") {
            const std::string v = value_of(line);
            if (v == "1" || v == "true") out << "support_material = everywhere\n";
            else if (v == "0" || v == "false") out << "support_material = none\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "gcode_label_objects") {
            const std::string v = value_of(line);
            if (v == "1" || v == "true") out << "gcode_label_objects = octoprint\n";
            else if (v == "0" || v == "false") out << "gcode_label_objects = disabled\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "pressure_advance") {
            const std::string v = value_of(line);
            try {
                out << "pressure_advance = " << (std::stod(v) != 0.0 ? "enabled" : "disabled") << "\n";
            } catch (const std::exception&) { out << line << '\n'; }
            continue;
        }
        if (key_of(line) == "brim_type") {
            const std::string v = value_of(line);
            if (v == "auto_brim") out << "brim_type = outer_and_inner\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "ensure_vertical_shell_thickness") {
            const std::string v = value_of(line);
            if (v == "ensure_all") out << "ensure_vertical_shell_thickness = enabled\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "fuzzy_skin") {
            const std::string v = value_of(line);
            if (v == "disabled_fuzzy") out << "fuzzy_skin = none\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "enable_support" || key_of(line) == "support_material") {
            // Orca bool (and the legacy bool) -> 3.0 support_material enum.
            const std::string v = value_of(line);
            if (v == "1" || v == "true") out << "support_material = everywhere\n";
            else if (v == "0" || v == "false") out << "support_material = none\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "arc_fitting") {
            const std::string v = value_of(line);
            if (v == "1" || v == "true") out << "arc_fitting = emit_center\n";
            else if (v == "0" || v == "false") out << "arc_fitting = disabled\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "fill_pattern") {
            const std::string v = value_of(line);
            if (v == "crosshatch") out << "fill_pattern = grid\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "support_material_style") {
            const std::string v = value_of(line);
            if (v == "default") out << "support_material_style = grid\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "support_material_pattern") {
            const std::string v = value_of(line);
            if (v == "default") out << "support_material_pattern = rectilinear\n";
            else out << line << '\n';
            continue;
        }
        if (key_of(line) == "top_fill_pattern") {
            const std::string v = value_of(line);
            if (v == "monotonicline") out << "top_fill_pattern = monotoniclines\n";
            else out << line << '\n';
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

    // 3. Minimal preset metadata (gcode header data + the hardware tool
    //    list slicing validates): technology, one tool per INI nozzle
    //    diameter (nozzle_diameter feature), matching tool_count.
    Slic3r::Domain::Preset::SelectedPresetMetadata metadata =
        build_metadata(read_nozzle_diameters(legacy_ini_path));

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
    // NOTE: slice() can also throw SlicingStatus::Exception SYNCHRONOUSLY
    // (not just via the on_exception callback). Its what() is the bare
    // "Slicing exception" string — enrich both paths or device crash logs
    // stay undiagnosable (seen on f4ec1be6cd: bare message despite the
    // callback enrichment, because callbacks.exception() was empty).
    NoThumbnails thumbnails;
    try {
        print->slice(id, thumbnails, std::nullopt);
    } catch (const Slic3r::Biz::Slicing::Exception& e) {
        throw std::runtime_error(describe_slice_error("sync slice()", e));
    }

    // SlicingStatus::Exception's what() is just "Slicing exception" — the
    // real cause (NoLayers, EmptyPrint, ObjectExceedsHeight, ...) lives in
    // error(). Rethrow with code + context so device crash logs are useful.
    if (callbacks.exception()) {
        try {
            std::rethrow_exception(callbacks.exception());
        } catch (const Slic3r::Biz::Slicing::Exception& e) {
            const std::string msg = describe_slice_error("async callback", e);
            LOGE("%s", msg.c_str());
            throw std::runtime_error(msg);
        }
    }

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
