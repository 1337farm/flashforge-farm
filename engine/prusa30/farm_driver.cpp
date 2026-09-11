// FlashForge Farm — PrusaSlicer 3.0 headless slice driver.
//
// Replaces the Orca `Print::apply()` + `process()` path and the GUI-gated
// `slic3r-app-cli`. Drives the PrusaSlicer 3.0 slicing surface directly:
//
//   FileLoadingLogic::read_model_from_file(path, nullptr)   -> tl::expected<Model>
//   Config::load_preset_and_config(json)                    -> tl::expected<PresetAndConfig>
//   init_print(PrinterTechnology, callbacks, id)            -> unique_ptr<IPrint>
//   IPrint::update(model, config_pack, bed, metadata, ser)  -> ApplyStatus::Status
//   IPrint::slice(id, thumbnail_gen, nullopt)               -> callbacks fire
//
// G-code/preview data arrives asynchronously via
// IProcessCallbacks::on_fdm_result(FDMResult&&) where
// FDMResult = libpgcode::ProcessorResult.
//
// This is a grounded structure compiled against the fetched upstream tree by
// the CI engine build. Remaining glue (Bed construction, SlicingId allocation,
// the metadata serializer, and serializing FDMResult back to gcode text) is
// marked TODO and is resolved through the CI compile loop.
//
#include <android/log.h>
#include <fstream>
#include <functional>
#include <future>
#include <stdexcept>
#include <utility>
#include <nlohmann/json.hpp>

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

#include "Slic3r/Biz/Config/ConfigLoad.hpp"
#include "Slic3r/Biz/FileLoadingLogic.hpp"
#include "Slic3r/Domain/Bed.hpp"
#include "Slic3r/Domain/BedInstance.hpp"
#include "Slic3r/Domain/ConfigPack.hpp"
#include "Slic3r/Domain/Model.hpp"
#include "Slic3r/Domain/Preset/SelectedPreset.hpp"
#include "Slic3r/Domain/SlicingId.hpp"

#define TAG "FarmPrusa"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace farm {

namespace ApplyStatus = Slic3r::Biz::Slicing::ApplyStatus;
using Slic3r::Biz::Config::PresetAndConfig;
using Slic3r::Biz::Config::load_preset_and_config;
using Slic3r::Biz::FileLoadingLogic::read_model_from_file;
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
using Slic3r::Domain::BedInstance;
using Slic3r::Domain::ConfigPack;
using Slic3r::Domain::Model;
using Slic3r::Domain::Preset::SelectedPresetMetadata;
using Slic3r::Domain::PrinterTechnology;
using Slic3r::Domain::SlicingId;

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

namespace {
Model load_model(const std::string& path) {
    auto model = read_model_from_file(path, nullptr);
    if (!model) {
        throw std::runtime_error("read_model_from_file failed: " + model.error());
    }
    return std::move(model.value());
}

PresetAndConfig load_config(const std::string& path) {
    std::ifstream file(path);
    nlohmann::ordered_json json_document;
    file >> json_document;

    auto preset_and_config = load_preset_and_config(json_document);
    if (!preset_and_config) {
        throw std::runtime_error("load_preset_and_config failed: " + preset_and_config.error());
    }
    return std::move(preset_and_config.value());
}
} // namespace

// Slice model+config to a ProcessorResult. The JNI bridge calls this on its
// native thread (never the UI thread).
FDMResult slice(const std::string& model_path, const std::string& config_json_path,
                std::function<void(const Slic3r::Biz::Slicing::Progress&)> progress)
{
    Model model = load_model(model_path);
    PresetAndConfig pac = load_config(config_json_path);

    // TODO(prusa30): build the real bed shape from the config's printable area.
    Bed bed = Bed::create(Slic3r::Domain::BedCreationData{});
    BedInstance bed_instance{bed};

    SelectedPresetMetadata metadata = pac.preset_metadata;
    ConfigPack config = std::move(pac.config_pack);

    // Serialize universal print statistics to a SerializedConfig (project stats).
    auto serializer = [](const IPrint::UniversalPrintStatistics&) {
        return SerializedConfig{};
    };

    const SlicingId id{};  // TODO(prusa30): allocate via the project/interactor layer

    FarmCallbacks callbacks;
    std::unique_ptr<IPrint> print = init_print(PrinterTechnology::FFF, callbacks, id);
    if (!print) {
        throw std::runtime_error("init_print returned null");
    }

    print->progress_callback = std::move(progress);

    const ApplyStatus::Status status = print->update(model, config, bed_instance, metadata, serializer);
    if (std::holds_alternative<ApplyStatus::InvalidData>(status)) {
        const auto& invalid = std::get<ApplyStatus::InvalidData>(status);
        LOGD("print->update returned InvalidData (%zu errors)", invalid.errors.size());
    }

    NoThumbnails thumbnails;
    print->slice(id, thumbnails, std::nullopt);

    if (callbacks.exception()) {
        std::rethrow_exception(callbacks.exception());
    }

    return callbacks.take_result();
}

} // namespace farm