// FlashForge Farm JNI bridge — PrusaSlicer 3.0 port (issues #211 model/IO, #212 config).
//
// Every Java_... entry signature is byte-identical to the 2.x bridge (pinned by
// scripts/tests/test_jni_contract.py); only bodies and private helpers changed.
//
// Ported against the fetched 3.0 tree (engine/prusa30/fetch_prusaslicer.sh):
//   model IO      -> Slic3r::Biz::FileLoadingLogic::read_model_from_file
//   model edits   -> Slic3r::Domain::Model (+ Biz::Algorithms::Model/ModelObject)
//   cut           -> Slic3r::Biz::Cut (+ Domain::CutConnectorType/CutInfo)
//   paint         -> Slic3r::Biz::Algorithms::TriangleSelector (+ AABBMesh)
//   slice prep    -> init_print/IPrint + Biz::Config::load_preset_and_config
//                   (see engine/prusa30/farm_driver.cpp for the driver shape)
// Entries with no 3.0 counterpart yet are minimal stubs: log + safe default,
// marked TODO(#212). Slice/export paths throw instead of fake success.

#include <android/log.h>
#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstring>
#include <fstream>
#include <memory>
#include <numbers>
#include <sstream>
#include <string>
#include <variant>
#include <vector>

#include <jni.h>

#include <GLES3/gl3.h>

#include "Slic3r/Domain/Model.hpp"
#include "Slic3r/Domain/ModelObject.hpp"
#include "Slic3r/Domain/ModelVolume.hpp"
#include "Slic3r/Domain/ModelInstance.hpp"
#include "Slic3r/Domain/TriangleMesh.hpp"
#include "Slic3r/Domain/CutConnector.hpp"
#include "Slic3r/Domain/LayerHeightProfile.hpp"
#include "farm_driver.hpp"
#include "Slic3r/Biz/FileLoadingLogic.hpp"
#include "Slic3r/Biz/Algorithms/Model.hpp"
#include "Slic3r/Biz/Algorithms/ModelObject.hpp"
#include "Slic3r/Biz/Algorithms/TriangleMesh.hpp"
#include "Slic3r/Biz/Algorithms/TriangleSelector.hpp"
#include "Slic3r/Biz/Algorithms/AABBMesh.hpp"
#include "Slic3r/Biz/Utils/CutUtils.hpp"

namespace Domain  = Slic3r::Domain;
namespace BizAlgo = Slic3r::Biz::Algorithms;

using Domain::Vec3d;
using Domain::Vec3f;
using Domain::Transform3d;
using Domain::TriangleSelector::TriangleStateType;

// The render stack is real 3.0-ported code compiled into libfarm itself
// (render/GLModel.cpp + render/GLShader.cpp are farm target sources): GLModel
// for mesh draw + raycast, GLShaderProgram for program lifecycle, and
// bed_utils.hpp for the plate/gridline/contour builders.
#include "Slic3r/Biz/Algorithms/Scaling.hpp"
#include "Slic3r/Biz/Algorithms/Color.hpp"
#include "Slic3r/Biz/libpgcode/Processor.hpp"
#include "Slic3r/Biz/libpgcode/ProcessorConfig.hpp"
#include "Slic3r/Biz/libpgcode/ProcessorResult.hpp"
#include "Slic3r/Biz/libpgcode/Types.hpp"
#include "Slic3r/Domain/Color.hpp"
#include "Slic3r/Domain/GCodeExtrusionRole.hpp"
#include "render/GLShader.hpp"
#include "render/Program.hpp"   // declares Slic3r::get_current_shader() (defined below)
#include "render/GLModel.hpp"
#include "render/bed_utils.hpp"
#include "Viewer.hpp"
#include "GCodeInputData.hpp"
#include "PathVertex.hpp"
#include "Types.hpp"

#define LOG_TAG "NativeCut"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TAG "FF_Native"

struct ModelRef {
    Domain::Model model;
    std::string base_name;
};
// Multi-color painting session, bound to one ModelObject. Holds a 3.0
// Biz::Algorithms::TriangleSelector over the object's merged mesh plus an
// AABBMesh for ray-casting touch points to a facet.
struct PaintSessionRef {
    ModelRef* model = nullptr;
    int objIdx = -1;
    // 0 = color (mm segmentation), 1 = support, 2 = seam, 3 = fuzzy skin.
    int mode = 0;
    Domain::TriangleMesh mesh;
    Slic3r::AABBMesh* emesh = nullptr;
    std::unique_ptr<BizAlgo::TriangleSelector> selector;
};
// Live GL shader program handle (render/GLShader.hpp, compiled into libfarm).
struct ShaderRef {
    Slic3r::GLShaderProgram* program = nullptr;
};
// Renderable mesh: the GLModel plus the CPU-side mesh backing raycasts
// (emesh over the same world-coords its, per-face normals for hit shading).
struct GLModelRef {
    Slic3r::GUI::GLModel model;
    Domain::TriangleMesh mesh;
    std::unique_ptr<Slic3r::AABBMesh> emesh;
    std::vector<Domain::Vec3f> normals;
};
// Bed visuals: scaled contour + print height parsed from the app's legacy INI
// (bed_shape/max_print_height), and the three GL models Bed3D renders
// (plate triangles, gridlines, contour lines). The models are handed to
// Java as GLModelRef pointers via bed_create and driven by the glmodel_*
// natives; bed_arrange consumes the contour once it is ported (#212).
struct FarmBedRef {
    Domain::Vec2ds contour_mm;
    float max_print_height = 0.0f;
    bool configured = false;
    std::unique_ptr<GLModelRef> triangles;
    std::unique_ptr<GLModelRef> gridlines;
    std::unique_ptr<GLModelRef> contourlines;
};
// Real native gcode preview: the vendored libvgcode viewer renders parsed
// libpgcode moves. GCodeResultRef owns one parsed result; GCodeViewerRef owns
// the viewer plus the last input data handed to Viewer::load().
namespace farm_vgcode {

using PgMoveType = Slic3r::Biz::libpgcode::MoveType;
using PgMoveVertex = Slic3r::Biz::libpgcode::MoveVertex;
using PgProcessor = Slic3r::Biz::libpgcode::Processor;
using PgProcessorConfig = Slic3r::Biz::libpgcode::ProcessorConfig;
using PgProcessorResult = Slic3r::Biz::libpgcode::ProcessorResult;

libvgcode::Vec3 vgcode_convert_position(const Domain::Vec3f& v) {
    return {v.x(), v.y(), v.z()};
}

libvgcode::EGCodeExtrusionRole vgcode_convert_role(Domain::GCodeExtrusionRole role) {
    switch (role) {
        case Domain::GCodeExtrusionRole::None: return libvgcode::EGCodeExtrusionRole::None;
        case Domain::GCodeExtrusionRole::Perimeter: return libvgcode::EGCodeExtrusionRole::Perimeter;
        case Domain::GCodeExtrusionRole::ExternalPerimeter: return libvgcode::EGCodeExtrusionRole::ExternalPerimeter;
        case Domain::GCodeExtrusionRole::OverhangPerimeter: return libvgcode::EGCodeExtrusionRole::OverhangPerimeter;
        case Domain::GCodeExtrusionRole::InternalInfill: return libvgcode::EGCodeExtrusionRole::InternalInfill;
        case Domain::GCodeExtrusionRole::SolidInfill: return libvgcode::EGCodeExtrusionRole::SolidInfill;
        case Domain::GCodeExtrusionRole::TopSolidInfill: return libvgcode::EGCodeExtrusionRole::TopSolidInfill;
        case Domain::GCodeExtrusionRole::Ironing: return libvgcode::EGCodeExtrusionRole::Ironing;
        case Domain::GCodeExtrusionRole::BridgeInfill: return libvgcode::EGCodeExtrusionRole::BridgeInfill;
        case Domain::GCodeExtrusionRole::GapFill: return libvgcode::EGCodeExtrusionRole::GapFill;
        case Domain::GCodeExtrusionRole::Skirt: return libvgcode::EGCodeExtrusionRole::Skirt;
        case Domain::GCodeExtrusionRole::SupportMaterial: return libvgcode::EGCodeExtrusionRole::SupportMaterial;
        case Domain::GCodeExtrusionRole::SupportMaterialInterface: return libvgcode::EGCodeExtrusionRole::SupportMaterialInterface;
        case Domain::GCodeExtrusionRole::WipeTower: return libvgcode::EGCodeExtrusionRole::WipeTower;
        case Domain::GCodeExtrusionRole::Custom: return libvgcode::EGCodeExtrusionRole::Custom;
        default: return libvgcode::EGCodeExtrusionRole::None;
    }
}

libvgcode::EMoveType vgcode_convert_move_type(PgMoveType type) {
    switch (type) {
        case PgMoveType::Noop: return libvgcode::EMoveType::Noop;
        case PgMoveType::Retract: return libvgcode::EMoveType::Retract;
        case PgMoveType::Unretract: return libvgcode::EMoveType::Unretract;
        case PgMoveType::Seam: return libvgcode::EMoveType::Seam;
        case PgMoveType::ToolChange: return libvgcode::EMoveType::ToolChange;
        case PgMoveType::ColorChange: return libvgcode::EMoveType::ColorChange;
        case PgMoveType::PausePrint: return libvgcode::EMoveType::PausePrint;
        case PgMoveType::CustomGCode: return libvgcode::EMoveType::CustomGCode;
        case PgMoveType::Travel: return libvgcode::EMoveType::Travel;
        case PgMoveType::Wipe: return libvgcode::EMoveType::Wipe;
        case PgMoveType::Extrude: return libvgcode::EMoveType::Extrude;
        case PgMoveType::Flush: return libvgcode::EMoveType::CustomGCode;
        default: return libvgcode::EMoveType::Noop;
    }
}

libvgcode::Color vgcode_convert_color(const std::string& color_str) {
    Domain::ColorRGBA color;
    if (Slic3r::Biz::Algorithms::Color::decode_color(color_str, color)) {
        return {color.r_uchar(), color.g_uchar(), color.b_uchar()};
    }
    return libvgcode::DUMMY_COLOR;
}

// Matches the old 2.x conversion: phantom vertices let libvgcode detect path
// boundaries for travels/wipes/options whose role/type changes.
libvgcode::GCodeInputData vgcode_convert_input_data(const PgProcessorResult& result) {
    libvgcode::GCodeInputData out;
    out.spiral_vase_mode = result.spiral_vase_enabled;

    out.tools_colors.reserve(result.extruder_str_colors.size());
    for (const std::string& color : result.extruder_str_colors) {
        out.tools_colors.emplace_back(vgcode_convert_color(color));
    }
    const std::vector<std::string> print_colors = result.color_strings_for_color_print();
    const std::vector<std::string>& color_source = print_colors.empty() ? result.extruder_str_colors : print_colors;
    out.color_print_colors.reserve(color_source.size());
    for (const std::string& color : color_source) {
        out.color_print_colors.emplace_back(vgcode_convert_color(color));
    }

    const std::shared_ptr<const Slic3r::Biz::libpgcode::MoveVertices> moves = result.const_moves();
    if (moves == nullptr || moves->size() < 2) return out;
    out.vertices.reserve(2 * moves->size());
    for (size_t i = 1; i < moves->size(); ++i) {
        const PgMoveVertex& curr = (*moves)[i];
        const PgMoveVertex& prev = (*moves)[i - 1];
        const libvgcode::EMoveType curr_type = vgcode_convert_move_type(curr.type);
        const libvgcode::EOptionType option_type = libvgcode::move_type_to_option(curr_type);
        if (option_type == libvgcode::EOptionType::COUNT || option_type == libvgcode::EOptionType::Travels ||
            option_type == libvgcode::EOptionType::Wipes) {
            if (out.vertices.empty() || prev.type != curr.type || prev.extrusion_role != curr.extrusion_role) {
                libvgcode::PathVertex vertex = {
                    vgcode_convert_position(prev.position), curr.height, curr.width, curr.feedrate, prev.actual_feedrate,
                    curr.mm3_per_mm, curr.fan_speed, curr.temperature, vgcode_convert_role(curr.extrusion_role), curr_type,
                    curr.gcode_id, curr.layer_id, curr.extruder_id, curr.cp_color_id, -1, {0.0f, 0.0f}};
                out.vertices.emplace_back(vertex);
            }
        }
        libvgcode::PathVertex vertex = {
            vgcode_convert_position(curr.position), curr.height, curr.width, curr.feedrate, curr.actual_feedrate,
            curr.mm3_per_mm, curr.fan_speed, curr.temperature, vgcode_convert_role(curr.extrusion_role), curr_type,
            curr.gcode_id, curr.layer_id, curr.extruder_id, curr.cp_color_id, -1, {curr.time[0], curr.time[1]}};
        out.vertices.emplace_back(vertex);
    }
    out.vertices.shrink_to_fit();
    return out;
}

std::string vgcode_read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open gcode file: " + path);
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

// Parse a gcode file with the 3.0 libpgcode processor (the successor of the
// 2.x GCodeProcessor::process_file/extract_result pair). Throws std::exception
// on IO/parse failure; never returns a half-parsed result.
std::unique_ptr<PgProcessorResult> vgcode_parse_gcode_file(const std::string& path) {
    std::string gcode = vgcode_read_file(path);
    const Slic3r::Biz::libpgcode::GCodeProducer producer =
        Slic3r::Biz::libpgcode::detect_producer(gcode);
    PgProcessorConfig config;
    config.reset();
    config.producer = producer;
    if (producer == Slic3r::Biz::libpgcode::GCodeProducer::PrusaSlicer) {
        config = Slic3r::Biz::libpgcode::extract_processor_config_from_prusaslicer_gcode(gcode);
    }
    PgProcessor processor(std::move(config));
    processor.process_buffer(std::move(gcode));
    return std::make_unique<PgProcessorResult>(processor.finalize());
}

} // namespace farm_vgcode

struct GCodeViewerRef {
    libvgcode::Viewer viewer;
    libvgcode::GCodeInputData data;
    bool initialized = false;
};
struct GCodeResultRef {
    std::string name;
    farm::RoleFilamentStats per_role; // Java GCodeViewer.ExtrusionRole int -> (mm, g)
    std::unique_ptr<farm_vgcode::PgProcessorResult> parsed;
};
// TODO(#212): opaque until Java passes JSON presets (Biz::Config::load_preset_and_config).
struct ConfigRef {
    bool unused = false;
};

static jclass sliceListenerClass;
static jmethodID sliceListenerOnProgress;

static jclass shadersManagerClass = nullptr;
static jmethodID shadersManagerGetCurrent = nullptr;

static JavaVM* staticVM;

// The Java GLShadersManager owns the programs (built via shader_init_from_texts)
// and tracks GLES30.glGetIntegerv(GL_CURRENT_PROGRAM); ask it for the matching
// native pointer so GLModel::render binds attributes against the right program.
// Render calls happen on the GL thread (a Java-attached thread), so GetEnv works.
Slic3r::GLShaderProgram* Slic3r::get_current_shader() {
    JNIEnv* env = nullptr;
    if (staticVM == nullptr || staticVM->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK)
        return nullptr;
    if (shadersManagerClass == nullptr || shadersManagerGetCurrent == nullptr)
        return nullptr;
    const jlong p = env->CallStaticLongMethod(shadersManagerClass, shadersManagerGetCurrent);
    if (p == 0) return nullptr;
    // getCurrentShaderPointer() returns the JNI handle from
    // shader_init_from_texts — a ShaderRef* — NOT the raw GLShaderProgram*.
    // Unwrapping is mandatory: ShaderRef's first member is a pointer while
    // GLShaderProgram's is a std::string, so a direct cast made the engine
    // dereference string bytes as pointers (device SIGSEGV, fault address
    // 0x31796b6e6974 == ASCII garbage).
    ShaderRef* ref = (ShaderRef*) (intptr_t) p;
    return ref->program;
}

extern "C" {
    int JNI_OnLoad(JavaVM *vm, void*) {
        JNIEnv *env;
        if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
            return JNI_ERR;
        }

        staticVM = vm;

        sliceListenerClass = env->FindClass("com/flashforge/farm/slic3r/SliceListener");
        sliceListenerOnProgress = env->GetMethodID(sliceListenerClass, "onProgress", "(ILjava/lang/String;)V");

        shadersManagerClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("com/flashforge/farm/slic3r/GLShadersManager")));
        shadersManagerGetCurrent = env->GetStaticMethodID(shadersManagerClass, "getCurrentShaderPointer", "()J");

        return JNI_VERSION_1_6;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_set_1svg_1path_1prefix(JNIEnv *env, jclass, jstring path) {
        // TODO(#212): no svg_path_prefix global in the 3.0 headers; drop once the
        // caller stops providing it, or wire to the 3.0 SVG helper when found.
        LOGD("set_svg_path_prefix: no 3.0 counterpart, ignoring");
        const char* chars = env->GetStringUTFChars(path, JNI_FALSE);
        env->ReleaseStringUTFChars(path, chars);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_get_1print_1config_1def(JNIEnv *env, jclass, jobject def) {
        // TODO(#212): the legacy PrintConfigDef/ConfigOptionDef enum system is gone;
        // exposing Domain::ConfigDef FDM schema to Java needs #212 schema design.
        (void) env; (void) def;
        LOGD("get_print_config_def: stub, 3.0 ConfigDef mapping pending");
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_model_1read_1from_1file(JNIEnv *env, jclass, jstring path, jstring base_name, jint plateId) {
        const char* chars = env->GetStringUTFChars(path, JNI_FALSE);
        const char* baseChars = env->GetStringUTFChars(base_name, JNI_FALSE);
        std::string cppPath(chars);
        std::string cppBase(baseChars);
        env->ReleaseStringUTFChars(path, chars);
        env->ReleaseStringUTFChars(base_name, baseChars);
        (void) plateId; // TODO(#212): plate selection lives in the project/scene layer now.

        try {
            auto loaded = Slic3r::Biz::FileLoadingLogic::read_model_from_file(cppPath, nullptr);
            if (!loaded) {
                env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"), loaded.error().c_str());
                return 0;
            }
            ModelRef* ref = new ModelRef();
            ref->model = std::move(loaded.value());
            ref->base_name = std::move(cppBase);
            return (jlong) (intptr_t) ref;
        } catch (const std::exception& e) {
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
            return 0;
        }
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_model_1create(JNIEnv *env, jclass) {
        (void) env;
        ModelRef* ref = new ModelRef();
        return (jlong) (intptr_t) ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_models_1merge(JNIEnv* env, jclass, jlongArray ptrsArr) {
        ModelRef* ref = new ModelRef();

        jlong* ptrs = env->GetLongArrayElements(ptrsArr, JNI_FALSE);
        int len = env->GetArrayLength(ptrsArr);
        for (int i = 0; i < len; i++) {
            ModelRef* sRef = (ModelRef*) (intptr_t) ptrs[i];
            if (sRef == nullptr) continue;
            for (Domain::ModelObject* obj : sRef->model.objects) {
                if (obj != nullptr)
                    ref->model.add_object(*obj);
            }
        }
        env->ReleaseLongArrayElements(ptrsArr, ptrs, JNI_ABORT);
        return (jlong) (intptr_t) ref;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1objects_1count(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        if (model == nullptr) return 0;
        return (jint) model->model.objects.size();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1add_1object_1from_1another(JNIEnv* env, jclass, jlong ptr, jlong fromPtr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        ModelRef* from = (ModelRef *) (intptr_t) fromPtr;
        if (model == nullptr || from == nullptr || i < 0 || i >= (jint) from->model.objects.size()) return;
        model->model.add_object(*from->model.objects[i]);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1delete_1object(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        if (model == nullptr || i < 0 || i >= (jint) model->model.objects.size()) return;
        model->model.delete_object((size_t) i);
    }

    static Domain::ModelObject* check_object(ModelRef* model, jint i) {
        if (model == nullptr || i < 0 || i >= (jint) model->model.objects.size()) return nullptr;
        return model->model.objects[i];
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1rotation(JNIEnv* env, jclass, jlong ptr, jint object_index) {
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, object_index);
        jdoubleArray arr = env->NewDoubleArray(3);
        Vec3d rot = (obj != nullptr && !obj->volumes.empty()) ? obj->volumes[0]->get_rotation() : Vec3d::Zero();
        env->SetDoubleArrayRegion(arr, 0, 3, rot.data());
        return arr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1mirror(JNIEnv* env, jclass, jlong ptr, jint object_index) {
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, object_index);
        jdoubleArray arr = env->NewDoubleArray(3);
        Vec3d mir = (obj != nullptr && !obj->volumes.empty()) ? obj->volumes[0]->get_mirror() : Vec3d::Zero();
        env->SetDoubleArrayRegion(arr, 0, 3, mir.data());
        return arr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1scale(JNIEnv* env, jclass, jlong ptr, jint object_index) {
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, object_index);
        jdoubleArray arr = env->NewDoubleArray(3);
        Vec3d sc = (obj != nullptr && !obj->volumes.empty()) ? obj->volumes[0]->get_scaling_factor() : Vec3d(1.0, 1.0, 1.0);
        env->SetDoubleArrayRegion(arr, 0, 3, sc.data());
        return arr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1translation(JNIEnv* env, jclass, jlong ptr, jint object_index) {
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, object_index);
        Vec3d center = Vec3d::Zero();
        if (obj != nullptr) {
            Domain::BoundingBox3d bb = BizAlgo::ModelObject::bounding_box_exact(*obj);
            center = (bb.min + bb.max) * 0.5;
        }
        jdoubleArray arr = env->NewDoubleArray(3);
        env->SetDoubleArrayRegion(arr, 0, 3, center.data());
        return arr;
    }

    static bool is_unprocessed_cut_connector(const Domain::ModelVolume* volume) {
        return volume != nullptr && volume->cut_info.is_connector && !volume->cut_info.is_processed;
    }

    static Domain::CutConnectorType connector_type_from_int(jint type) {
        switch (type) {
            case 1: return Domain::CutConnectorType::Dowel;
            case 2: return Domain::CutConnectorType::Snap;
            case 0:
            default: return Domain::CutConnectorType::Plug;
        }
    }

    static void add_cut_connector_volume(Domain::ModelObject* object, const Vec3d& pos, float radius, float height, jint type, double rotX, double rotY) {
        if (object == nullptr) return;
        radius = std::max(radius, 0.1f);
        height = std::max(height, 0.1f);

        Domain::CutConnectorType connector_type = connector_type_from_int(type);
        Domain::TriangleMesh mesh = Slic3r::Biz::Algorithms::TriangleMesh::make_cylinder(1.0, 1.0, std::numbers::pi / 18.0);
        Domain::ModelVolume* volume = BizAlgo::ModelObject::add_volume(object, std::move(mesh), Domain::ModelVolumeType::NEGATIVE_VOLUME);
        if (volume == nullptr) return;

        Transform3d rotation_m = Transform3d::Identity();
        rotation_m.rotate(Eigen::AngleAxisd(rotY, Vec3d::UnitY()));
        rotation_m.rotate(Eigen::AngleAxisd(rotX, Vec3d::UnitX()));
        volume->set_transformation(rotation_m);
        volume->set_offset(pos);
        volume->set_scaling_factor(Vec3d(radius, radius, height));
        volume->cut_info = Domain::ModelVolume::CutInfo(connector_type, 0.05, 0.10, false);
        volume->name = connector_type == Domain::CutConnectorType::Dowel ? "Cut Dowel" : (connector_type == Domain::CutConnectorType::Snap ? "Cut Snap" : "Cut Plug");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1add_1connector(JNIEnv* env, jclass, jlong ptr, jint objIdx, jdouble x, jdouble y, jdouble z, jfloat radius, jfloat height, jint type) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        add_cut_connector_volume(check_object(model, objIdx), Vec3d(x, y, z), radius, height, type, 0.0, 0.0);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1add_1connector_1on_1plane(JNIEnv* env, jclass, jlong ptr, jint objIdx, jdouble x, jdouble y, jdouble z, jfloat radius, jfloat height, jint type, jdouble rotX, jdouble rotY) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        add_cut_connector_volume(check_object(model, objIdx), Vec3d(x, y, z), radius, height, type, rotX, rotY);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1remove_1connector(JNIEnv* env, jclass, jlong ptr, jint objIdx, jint connIdx) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* object = check_object(model, objIdx);
        if (object == nullptr || connIdx < 0) return;
        int connector_counter = 0;
        for (size_t volume_idx = 0; volume_idx < object->volumes.size(); ++volume_idx) {
            if (!is_unprocessed_cut_connector(object->volumes[volume_idx])) continue;
            if (connector_counter == connIdx) {
                object->delete_volume(volume_idx);
                return;
            }
            ++connector_counter;
        }
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1clear_1connectors(JNIEnv* env, jclass, jlong ptr, jint objIdx) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* object = check_object(model, objIdx);
        if (object == nullptr) return;
        for (size_t volume_idx = object->volumes.size(); volume_idx > 0; --volume_idx) {
            if (is_unprocessed_cut_connector(object->volumes[volume_idx - 1])) {
                object->delete_volume(volume_idx - 1);
            }
        }
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_model_1cut(JNIEnv* env, jclass, jlong ptr, jint i, jdouble zHeight, jdouble rotX, jdouble rotY, jboolean keepUpper, jboolean keepLower) {
        (void) env;
        ModelRef* modelRef = (ModelRef *) (intptr_t) ptr;
        Domain::Model* model = (modelRef != nullptr) ? &modelRef->model : nullptr;
        Domain::ModelObject* object = check_object(modelRef, i);
        if (model == nullptr || object == nullptr) {
            LOGD("Cut failed: null model or index %d", i);
            return false;
        }

        Transform3d cut_matrix = Transform3d::Identity();
        cut_matrix.translate(Vec3d(0.0, 0.0, zHeight));
        cut_matrix.rotate(Eigen::AngleAxisd(rotY, Vec3d::UnitY()));
        cut_matrix.rotate(Eigen::AngleAxisd(rotX, Vec3d::UnitX()));

        Slic3r::Biz::ModelObjectCutAttributes attributes;
        attributes.invalidate_cut_info = true;
        attributes.create_dowels = true;
        attributes.keep_upper = keepUpper;
        attributes.keep_lower = keepLower;

        Slic3r::Biz::Cut cut(object, 0, cut_matrix, attributes);
        const Domain::ModelObjectPtrs& cut_objects = cut.perform_with_plane();

        LOGD("Cut returned %zu objects", cut_objects.size());

        if (cut_objects.empty()) {
            return false;
        }

        bool isFirst = true;
        for (Domain::ModelObject* cut_obj : cut_objects) {
            if (cut_obj == nullptr) continue;
            if (isFirst && cut_objects.size() > 1) {
                BizAlgo::ModelObject::translate(*cut_obj, 20.0, 20.0, 10.0);
                isFirst = false;
            }
            model->add_object(*cut_obj);
        }

        model->delete_object((size_t) i);

        return true;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_model_1split(JNIEnv* env, jclass, jlong ptr, jint i) {
        // TODO(#212): ModelObject::split has no 3.0 counterpart (mesh split lives in
        // Biz::Algorithms::MeshSplitImpl, not yet wired to ModelObject topology).
        (void) env; (void) ptr; (void) i;
        LOGD("model_split: stub, no 3.0 counterpart");
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1translate(JNIEnv* env, jclass, jlong ptr, jint i, jdouble x, jdouble y, jdouble z) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return;
        BizAlgo::ModelObject::translate(*obj, x, y, z);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1ensure_1on_1bed(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return;
        BizAlgo::ModelObject::ensure_on_bed(*obj, false);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1scale(JNIEnv* env, jclass, jlong ptr, jint i, jdouble x, jdouble y, jdouble z) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return;
        Vec3d factor(x, y, z);
        for (Domain::ModelVolume* vol : obj->volumes) {
            if (vol != nullptr)
                vol->set_scaling_factor(factor);
        }
        obj->invalidate_bounding_box();
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_model_1is_1left_1handed(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr || obj->volumes.empty() || obj->volumes[0] == nullptr) return false;
        return obj->volumes[0]->is_left_handed();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1rotate(JNIEnv* env, jclass, jlong ptr, jint i, jdouble x, jdouble y, jdouble z) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return;
        Vec3d vec(x, y, z);
        for (Domain::ModelVolume* vol : obj->volumes) {
            if (vol == nullptr) continue;
            Vec3d current_rotation = vol->get_rotation();

            Eigen::Quaterniond q_current =
                    Eigen::AngleAxisd(current_rotation[2], Eigen::Vector3d::UnitZ()) *
                    Eigen::AngleAxisd(current_rotation[1], Eigen::Vector3d::UnitY()) *
                    Eigen::AngleAxisd(current_rotation[0], Eigen::Vector3d::UnitX());

            Eigen::Quaterniond q_delta =
                    Eigen::AngleAxisd(vec[0], Eigen::Vector3d::UnitX()) *
                    Eigen::AngleAxisd(vec[1], Eigen::Vector3d::UnitY()) *
                    Eigen::AngleAxisd(vec[2], Eigen::Vector3d::UnitZ());

            Eigen::Quaterniond q_result = q_delta * q_current;
            Eigen::Vector3d new_rotation = q_result.toRotationMatrix().eulerAngles(2, 1, 0);
            vol->set_rotation(Vec3d(new_rotation[2], new_rotation[1], new_rotation[0]));
        }
        obj->invalidate_bounding_box();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1flatten_1rotate(JNIEnv* env, jclass, jlong ptr, jint i, jlong surface_ptr) {
        // TODO(#212): flatten planes (model_create_flatten_planes) are stubbed with the
        // GL pipeline, so there is no surface to align to yet.
        (void) env; (void) ptr; (void) i; (void) surface_ptr;
        LOGD("model_flatten_rotate: stub, flatten planes pending");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1translate_1global(JNIEnv* env, jclass, jlong ptr, jdouble x, jdouble y, jdouble z) {
        (void) env;
        ModelRef* model = (ModelRef *) (intptr_t) ptr;
        if (model == nullptr) return;
        for (Domain::ModelObject* obj : model->model.objects) {
            if (obj != nullptr)
                BizAlgo::ModelObject::translate(*obj, x, y, z);
        }
    }

    static jdoubleArray bbox_to_array(JNIEnv* env, const Domain::BoundingBox3d& bb) {
        jdoubleArray arr = env->NewDoubleArray(6);
        jdouble elements[6] = { bb.min.x(), bb.min.y(), bb.min.z(), bb.max.x(), bb.max.y(), bb.max.z() };
        env->SetDoubleArrayRegion(arr, 0, 6, elements);
        return arr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1bounding_1box_1approx(JNIEnv* env, jclass, jlong ptr, jint i) {
        ModelRef* ref = (ModelRef*) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(ref, i);
        Domain::BoundingBox3d bb;
        if (obj != nullptr)
            bb = BizAlgo::ModelObject::bounding_box_approx(*obj);
        return bbox_to_array(env, bb);
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1bounding_1box_1exact(JNIEnv* env, jclass, jlong ptr, jint i) {
        ModelRef* ref = (ModelRef*) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(ref, i);
        Domain::BoundingBox3d bb;
        if (obj != nullptr)
            bb = BizAlgo::ModelObject::bounding_box_exact(*obj);
        return bbox_to_array(env, bb);
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1bounding_1box_1approx_1global(JNIEnv* env, jclass, jlong ptr) {
        ModelRef* ref = (ModelRef*) (intptr_t) ptr;
        Domain::BoundingBox3d bb;
        if (ref != nullptr)
            bb = BizAlgo::Model::bounding_box_approx(ref->model);
        return bbox_to_array(env, bb);
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1bounding_1box_1exact_1global(JNIEnv* env, jclass, jlong ptr) {
        ModelRef* ref = (ModelRef*) (intptr_t) ptr;
        Domain::BoundingBox3d bb;
        if (ref != nullptr)
            bb = BizAlgo::Model::bounding_box_exact(ref->model);
        return bbox_to_array(env, bb);
    }

    JNIEXPORT jlongArray JNICALL Java_com_flashforge_farm_slic3r_Native_model_1create_1flatten_1planes(JNIEnv* env, jclass, jlong ptr, jint i) {
        // TODO(#212): plane extraction needs the convex-hull facet walk ported to
        // Domain::TriangleMesh plus GL planes for picking; both pending. Return an
        // EMPTY array (never null): Model.java dereferences ptr.length immediately.
        (void) ptr; (void) i;
        LOGD("model_create_flatten_planes: stub, pending");
        return env->NewLongArray(0);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1auto_1orient(JNIEnv* env, jclass, jlong ptr, jint i) {
        // TODO(#212): compat/Orient.hpp needs the deleted 2.x Model header.
        (void) env; (void) ptr; (void) i;
        LOGD("model_auto_orient: stub, orient pending");
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_model_1is_1big_1object(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef*) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return JNI_FALSE;
        return (obj->volumes.size() == 1 && obj->volumes.front() != nullptr &&
                obj->volumes.front()->mesh().its.indices.size() >= 500000) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_model_1get_1extruder(JNIEnv* env, jclass, jlong ptr, jint i) {
        // TODO(#212): per-object legacy config (obj->config "extruder") is gone; extruder
        // assignment now lives in the ConfigBox/project layer. -1 = unset (as before).
        (void) env; (void) ptr; (void) i;
        return -1;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1set_1extruder(JNIEnv* env, jclass, jlong ptr, jint i, jint extruder) {
        // TODO(#212): see model_get_extruder.
        (void) env; (void) ptr; (void) i; (void) extruder;
        LOGD("model_set_extruder: stub, per-object config pending");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1apply_1adaptive_1layer_1height(JNIEnv* env, jclass, jlong ptr, jint i, jstring configPath, jfloat qualityFactor) {
        // TODO(#212): adaptive params came from PrintObject::slicing_parameters over a
        // legacy DynamicPrintConfig; needs the #212 INI->ConfigPack path first.
        (void) env; (void) ptr; (void) i; (void) configPath; (void) qualityFactor;
        LOGD("model_apply_adaptive_layer_height: stub, config path pending");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1clear_1adaptive_1layer_1height(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef*) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return;
        obj->layer_height_profile.clear();
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_model_1has_1adaptive_1layer_1height(JNIEnv* env, jclass, jlong ptr, jint i) {
        (void) env;
        ModelRef* model = (ModelRef*) (intptr_t) ptr;
        Domain::ModelObject* obj = check_object(model, i);
        if (obj == nullptr) return false;
        return !obj->layer_height_profile.empty();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1emboss_1text(
        JNIEnv* env, jclass, jlong ptr, jint i, jstring fontPath, jstring textStr, jfloat size, jfloat depth, jint type,
        jdouble px, jdouble py, jdouble pz, jdouble nx, jdouble ny, jdouble nz) {
        // TODO(#212): 3.0 Emboss entry points exist (Biz/Emboss/Emboss.hpp: create_font_file,
        // text2shapes, polygons2model) but the volume-attach path needs full signature
        // verification; stubbed rather than risk a wrong mesh.
        (void) env; (void) ptr; (void) i; (void) fontPath; (void) textStr;
        (void) size; (void) depth; (void) type;
        (void) px; (void) py; (void) pz; (void) nx; (void) ny; (void) nz;
        LOGD("model_emboss_text: stub, Emboss attach pending");
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_model_1slice(JNIEnv* env, jclass, jlong ptr, jstring configPath, jstring path, jobject listener, jint numFilaments, jintArray colorsArr, jint calibMode, jdouble calibStart, jdouble calibEnd, jdouble calibStep) {
        // #213: legacy-INI slice path. The app's slic3r_current.ini feeds
        // Biz::load_config_from_legacy_file (the public wrapper over the
        // src-private legacy reader), the bed comes from the config's
        // bed_shape/max_print_height, and the FDMResult's embedded gcode is
        // written verbatim to the destination (see engine/prusa30/farm_driver.cpp).
        // The legacy numFilaments/colors/calibration params stay in the JNI
        // contract; their content is carried by the INI itself.
        ModelRef* ref = (ModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return 0;
        const char* cfgChars = env->GetStringUTFChars(configPath, JNI_FALSE);
        std::string cppConfig(cfgChars);
        env->ReleaseStringUTFChars(configPath, cfgChars);
        const char* pathChars = env->GetStringUTFChars(path, JNI_FALSE);
        std::string cppGcodePath(pathChars);
        env->ReleaseStringUTFChars(path, pathChars);
        (void) numFilaments; (void) colorsArr;
        (void) calibMode; (void) calibStart; (void) calibEnd; (void) calibStep;

        // model_slice runs on the app's farm-slice Java thread, so the
        // captured env is valid for the whole call.
        auto progress = [&](int percent, const std::string& stage) {
            jstring js = env->NewStringUTF(stage.c_str());
            env->CallVoidMethod(listener, sliceListenerOnProgress, (jint) percent, js);
            env->DeleteLocalRef(js);
        };

        try {
            farm::SliceStats stats = farm::slice_to_gcode(ref->model, cppConfig, cppGcodePath, progress);
            GCodeResultRef* result = new GCodeResultRef();
            const size_t slash = cppGcodePath.find_last_of('/');
            result->name = slash == std::string::npos ? cppGcodePath : cppGcodePath.substr(slash + 1);
            result->per_role = std::move(stats.per_role);
            // The native viewer needs parsed moves, not just the file: run the
            // same libpgcode pass the load-file path uses. A parse failure must
            // not fail the slice itself (the gcode file is already written);
            // the viewer then stays empty and the app-side fallback still draws.
            try {
                result->parsed = farm_vgcode::vgcode_parse_gcode_file(cppGcodePath);
            } catch (const std::exception& e) {
                LOGE("model_slice: libpgcode parse failed (viewer empty): %s", e.what());
            }
            return (jlong) (intptr_t) result;
        } catch (const std::exception& e) {
            LOGE("model_slice failed: %s", e.what());
            env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"), e.what());
            return 0;
        }
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1export_13mf(JNIEnv* env, jclass, jlong ptr, jstring configPath, jstring path) {
        // TODO(#212): 3.0 store_3mf takes (filepath, Domain::Project, Store3mfParam) —
        // there is no Model+Config save entry; assembling a Project is #213 scope.
        // Throw: an export must never report silent success.
        (void) ptr; (void) configPath; (void) path;
        LOGE("model_export_3mf: no 3.0 Model+Config save entry");
        env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"),
            "3MF export is not available in this build yet (#212)");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_model_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ModelRef* model = (ModelRef*) (intptr_t) ptr;
        delete model;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1load_1file(JNIEnv* env, jclass, jstring path, jstring name) {
        const char* pathChars = env->GetStringUTFChars(path, JNI_FALSE);
        const char* nameChars = env->GetStringUTFChars(name, JNI_FALSE);
        std::string cppPath(pathChars != nullptr ? pathChars : "");
        std::string cppName(nameChars != nullptr ? nameChars : "");
        if (pathChars != nullptr) env->ReleaseStringUTFChars(path, pathChars);
        if (nameChars != nullptr) env->ReleaseStringUTFChars(name, nameChars);

        try {
            auto parsed = farm_vgcode::vgcode_parse_gcode_file(cppPath);
            GCodeResultRef* ref = new GCodeResultRef();
            ref->name = cppName;
            // print_statistics is a Basic/Full variant; both carry per-role filament stats.
            std::visit([&ref](const auto& stats) {
                for (const auto& entry : stats.used_filaments_per_role) {
                    ref->per_role[static_cast<int>(entry.first)] = {entry.second.first, entry.second.second};
                }
            }, parsed->print_statistics);
            ref->parsed = std::move(parsed);
            return (jlong) (intptr_t) ref;
        } catch (const std::exception& e) {
            LOGE("gcoderesult_load_file failed: %s", e.what());
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
            return 0;
        }
    }

    JNIEXPORT jstring JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1recommended_1name(JNIEnv* env, jclass, jlong ptr) {
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        if (ref == nullptr) return nullptr;
        return env->NewStringUTF(ref->name.c_str());
    }

    JNIEXPORT jdouble JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1used_1filament_1mm(JNIEnv* env, jclass, jlong ptr, jint role) {
        (void) env;
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        if (ref == nullptr) return 0.0;
        const auto it = ref->per_role.find(role);
        return it == ref->per_role.end() ? 0.0 : (jdouble) it->second.first;
    }

    JNIEXPORT jdouble JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1used_1filament_1g(JNIEnv* env, jclass, jlong ptr, jint role) {
        (void) env;
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        if (ref == nullptr) return 0.0;
        const auto it = ref->per_role.find(role);
        return it == ref->per_role.end() ? 0.0 : (jdouble) it->second.second;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        delete ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1init_1from_1texts(JNIEnv* env, jclass, jstring name, jstring fsText, jstring vsText) {
        const char* nameChars = env->GetStringUTFChars(name, JNI_FALSE);
        const char* fsChars = env->GetStringUTFChars(fsText, JNI_FALSE);
        const char* vsChars = env->GetStringUTFChars(vsText, JNI_FALSE);
        ShaderRef* ref = new ShaderRef();
        ref->program = new Slic3r::GLShaderProgram();
        // ShaderSources is indexed by EShaderType {Vertex, Fragment} while the
        // Java signature passes the fragment source first.
        const bool ok = ref->program->init_from_texts(std::string(nameChars),
            {std::string(vsChars), std::string(fsChars)});
        env->ReleaseStringUTFChars(name, nameChars);
        env->ReleaseStringUTFChars(fsText, fsChars);
        env->ReleaseStringUTFChars(vsText, vsChars);
        if (!ok) {
            delete ref->program;
            delete ref;
            LOGE("shader_init_from_texts: compile/link failed");
            return 0;
        }
        return (jlong) (intptr_t) ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1create(JNIEnv* env, jclass) {
        (void) env;
        return (jlong) (intptr_t) new GLModelRef();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1raycast_1data(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->emesh = std::make_unique<Slic3r::AABBMesh>(ref->mesh, true);
        ref->normals = BizAlgo::TriangleMesh::its_face_normals(ref->mesh.its);
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1raycast_1closest_1hit(JNIEnv* env, jclass, jlong ptr, jdoubleArray pointArr, jdoubleArray directionArr) {
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr || ref->emesh == nullptr) return env->NewDoubleArray(0);
        jdouble* point = env->GetDoubleArrayElements(pointArr, JNI_FALSE);
        jdouble* direction = env->GetDoubleArrayElements(directionArr, JNI_FALSE);
        const Domain::Vec3d point3d(point);
        const Domain::Vec3d direction3d(direction);
        env->ReleaseDoubleArrayElements(pointArr, point, JNI_ABORT);
        env->ReleaseDoubleArrayElements(directionArr, direction, JNI_ABORT);

        // The camera ray is a line; query both half-rays so the pick works
        // from either side of the surface (2.x semantics).
        std::vector<Slic3r::AABBMesh::hit_result> hits =
            ref->emesh->query_ray_hits(point3d - direction3d, direction3d);
        std::vector<Slic3r::AABBMesh::hit_result> hits_neg =
            ref->emesh->query_ray_hits(point3d + direction3d, -direction3d);
        hits.insert(hits.end(), hits_neg.begin(), hits_neg.end());
        std::stable_sort(hits.begin(), hits.end(),
            [](const Slic3r::AABBMesh::hit_result& a, const Slic3r::AABBMesh::hit_result& b) {
                return a.distance() < b.distance();
            });

        // Java consumes a flat {px,py,pz, nx,ny,nz} stride per hit, sorted
        // closest-first (it only reads hits.get(0)).
        jdoubleArray arr = env->NewDoubleArray((jsize) (hits.size() * 6));
        std::vector<jdouble> packed;
        packed.reserve(hits.size() * 6);
        for (const auto& hit : hits) {
            const Domain::Vec3d pos = hit.position();
            const Domain::Vec3f n = (hit.face() >= 0 && (size_t) hit.face() < ref->normals.size())
                ? ref->normals[(size_t) hit.face()]
                : Domain::Vec3f::UnitZ();
            packed.push_back(pos.x()); packed.push_back(pos.y()); packed.push_back(pos.z());
            packed.push_back(n.x());   packed.push_back(n.y());   packed.push_back(n.z());
        }
        if (!packed.empty())
            env->SetDoubleArrayRegion(arr, 0, (jsize) packed.size(), packed.data());
        return arr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1model(JNIEnv* env, jclass, jlong ptr, jlong model) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        ModelRef* mRef = (ModelRef*) (intptr_t) model;
        if (ref == nullptr || mRef == nullptr) return;
        // Whole model flattened to a single mesh (2.x Model::mesh() semantics).
        ref->mesh = BizAlgo::Model::flatten_to_mesh(mRef->model);
        ref->model.init_from(ref->mesh.its);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1model_1object(JNIEnv* env, jclass, jlong ptr, jlong model, jint i) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        ModelRef* mRef = (ModelRef*) (intptr_t) model;
        if (ref == nullptr || mRef == nullptr) return;
        Domain::ModelObject* obj = check_object(mRef, i);
        if (obj == nullptr) return;
        // World-coords merged mesh (all instances transformed) — 2.x
        // ModelObject::mesh() semantics via the 3.0 free function.
        ref->mesh = BizAlgo::ModelObject::mesh(*obj);
        ref->model.init_from(ref->mesh.its);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1set_1color(JNIEnv* env, jclass, jlong ptr, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.set_color({red, green, blue, alpha});
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1stilized_1arrow(JNIEnv* env, jclass, jlong ptr, jfloat tip_radius, jfloat tip_length, jfloat stem_radius, jfloat stem_length) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        // Resolution 16 matches the 2.x arrow the Java CoordAxes expects.
        ref->model.init_from(Slic3r::GUI::stilized_arrow(16, tip_radius, tip_length, stem_radius, stem_length));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1path(JNIEnv* env, jclass, jlong ptr, jfloatArray verticesArr, jintArray indicesArr, jboolean lineStrip) {
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr || verticesArr == nullptr || indicesArr == nullptr) return;
        const jsize vertexCount = env->GetArrayLength(verticesArr);
        const jsize indexCount = env->GetArrayLength(indicesArr);
        if (vertexCount % 3 != 0 || vertexCount <= 0 || indexCount <= 0) return;
        jfloat* vertices = env->GetFloatArrayElements(verticesArr, nullptr);
        jint* indices = env->GetIntArrayElements(indicesArr, nullptr);
        if (vertices == nullptr || indices == nullptr) {
            if (vertices != nullptr) env->ReleaseFloatArrayElements(verticesArr, vertices, JNI_ABORT);
            if (indices != nullptr) env->ReleaseIntArrayElements(indicesArr, indices, JNI_ABORT);
            return;
        }
        Slic3r::GUI::GLModel::Geometry g;
        g.format = {lineStrip == JNI_TRUE
                            ? Slic3r::GUI::GLModel::Geometry::EPrimitiveType::LineStrip
                            : Slic3r::GUI::GLModel::Geometry::EPrimitiveType::Lines,
                    Slic3r::GUI::GLModel::Geometry::EVertexLayout::P3};
        g.reserve_vertices((size_t) (vertexCount / 3));
        g.reserve_indices((size_t) indexCount);
        for (jsize i = 0; i < vertexCount; i += 3) {
            g.add_vertex(Domain::Vec3f(vertices[i], vertices[i + 1], vertices[i + 2]));
        }
        for (jsize i = 0; i < indexCount; ++i) {
            const jint id = indices[i];
            if (id < 0 || id * 3 + 2 >= vertexCount) {
                env->ReleaseFloatArrayElements(verticesArr, vertices, JNI_ABORT);
                env->ReleaseIntArrayElements(indicesArr, indices, JNI_ABORT);
                return;
            }
            g.add_index((unsigned int) id);
        }
        env->ReleaseFloatArrayElements(verticesArr, vertices, JNI_ABORT);
        env->ReleaseIntArrayElements(indicesArr, indices, JNI_ABORT);
        ref->model.reset();
        ref->model.init_from(std::move(g));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1background_1triangles(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.reset();
        Slic3r::GUI::GLModel::Geometry init_data;
        init_data.format = {Slic3r::GUI::GLModel::Geometry::EPrimitiveType::Triangles,
                            Slic3r::GUI::GLModel::Geometry::EVertexLayout::P2T2};
        init_data.reserve_vertices(4);
        init_data.reserve_indices(6);
        init_data.add_vertex(Domain::Vec2f(-1.0f, -1.0f), Domain::Vec2f(0.0f, 0.0f));
        init_data.add_vertex(Domain::Vec2f(1.0f, -1.0f), Domain::Vec2f(1.0f, 0.0f));
        init_data.add_vertex(Domain::Vec2f(1.0f, 1.0f), Domain::Vec2f(1.0f, 1.0f));
        init_data.add_vertex(Domain::Vec2f(-1.0f, 1.0f), Domain::Vec2f(0.0f, 1.0f));
        init_data.add_triangle(0, 1, 2);
        init_data.add_triangle(2, 3, 0);
        ref->model.init_from(std::move(init_data));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1box(JNIEnv* env, jclass, jlong ptr, jfloat width, jfloat depth, jfloat height) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.reset();
        // Rectangular prism, origin at the front-left-bottom corner (Java doc).
        Slic3r::GUI::GLModel::Geometry g;
        g.format = {Slic3r::GUI::GLModel::Geometry::EPrimitiveType::Triangles,
                    Slic3r::GUI::GLModel::Geometry::EVertexLayout::P3N3};
        const float w = width, d = depth, h = height;
        const Domain::Vec3f corners[8] = {
            {0, 0, 0}, {w, 0, 0}, {w, d, 0}, {0, d, 0},
            {0, 0, h}, {w, 0, h}, {w, d, h}, {0, d, h},
        };
        const int faces[6][4] = { // quad as {v0, v1, v2, v3}, CCW
            {4, 5, 6, 7}, // top    +Z
            {3, 2, 1, 0}, // bottom -Z
            {0, 1, 5, 4}, // front  -Y
            {2, 3, 7, 6}, // back   +Y
            {1, 2, 6, 5}, // right  +X
            {0, 4, 7, 3}, // left   -X
        };
        g.reserve_vertices(24);
        g.reserve_indices(36);
        unsigned int next = 0;
        for (const auto& f : faces) {
            const Domain::Vec3f& a = corners[f[0]];
            const Domain::Vec3f& b = corners[f[1]];
            const Domain::Vec3f& c = corners[f[2]];
            const Domain::Vec3f& d2 = corners[f[3]];
            const Domain::Vec3f n = (b - a).cross(c - a).normalized();
            g.add_vertex(a, n); g.add_vertex(b, n); g.add_vertex(c, n); g.add_vertex(d2, n);
            g.add_triangle(next, next + 1, next + 2);
            g.add_triangle(next, next + 2, next + 3);
            next += 4;
        }
        ref->model.init_from(std::move(g));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1bounding_1box(JNIEnv* env, jclass, jlong ptr, jlong modelPtr, jint i) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        ModelRef* mRef = (ModelRef*) (intptr_t) modelPtr;
        if (ref == nullptr || mRef == nullptr) return;
        Domain::ModelObject* obj = check_object(mRef, i);
        if (obj == nullptr) return;
        const Domain::TriangleMesh mesh = BizAlgo::ModelObject::mesh(*obj);
        if (mesh.its.vertices.empty()) return;
        Domain::Vec3f b_min(FLT_MAX, FLT_MAX, FLT_MAX), b_max(-FLT_MAX, -FLT_MAX, -FLT_MAX);
        for (const Domain::Vec3f& v : mesh.its.vertices) {
            b_min = b_min.cwiseMin(v);
            b_max = b_max.cwiseMax(v);
        }
        // Selection box: corner brackets (Lines), rebuilt only when the box moved.
        const Domain::Vec3f size = 0.2f * (b_max - b_min);
        ref->model.reset();
        Slic3r::GUI::GLModel::Geometry g;
        g.format = {Slic3r::GUI::GLModel::Geometry::EPrimitiveType::Lines,
                    Slic3r::GUI::GLModel::Geometry::EVertexLayout::P3};
        struct C { Domain::Vec3f p, s; };
        const C brackets[24] = {
            // bottom face brackets (min corner axes)
            {b_min, { size.x(), 0, 0}}, {b_min, {0,  size.y(), 0}}, {b_min, {0, 0,  size.z()}},
            {{b_max.x(), b_min.y(), b_min.z()}, {-size.x(), 0, 0}},
            {{b_max.x(), b_min.y(), b_min.z()}, {0,  size.y(), 0}},
            {{b_max.x(), b_min.y(), b_min.z()}, {0, 0,  size.z()}},
            {{b_max.x(), b_max.y(), b_min.z()}, {-size.x(), 0, 0}},
            {{b_max.x(), b_max.y(), b_min.z()}, {0, -size.y(), 0}},
            {{b_max.x(), b_max.y(), b_min.z()}, {0, 0,  size.z()}},
            {{b_min.x(), b_max.y(), b_min.z()}, { size.x(), 0, 0}},
            {{b_min.x(), b_max.y(), b_min.z()}, {0, -size.y(), 0}},
            {{b_min.x(), b_max.y(), b_min.z()}, {0, 0,  size.z()}},
            // top face brackets
            {{b_min.x(), b_min.y(), b_max.z()}, { size.x(), 0, 0}},
            {{b_min.x(), b_min.y(), b_max.z()}, {0,  size.y(), 0}},
            {{b_min.x(), b_min.y(), b_max.z()}, {0, 0, -size.z()}},
            {{b_max.x(), b_min.y(), b_max.z()}, {-size.x(), 0, 0}},
            {{b_max.x(), b_min.y(), b_max.z()}, {0,  size.y(), 0}},
            {{b_max.x(), b_min.y(), b_max.z()}, {0, 0, -size.z()}},
            {{b_max.x(), b_max.y(), b_max.z()}, {-size.x(), 0, 0}},
            {{b_max.x(), b_max.y(), b_max.z()}, {0, -size.y(), 0}},
            {{b_max.x(), b_max.y(), b_max.z()}, {0, 0, -size.z()}},
            {{b_min.x(), b_max.y(), b_max.z()}, { size.x(), 0, 0}},
            {{b_min.x(), b_max.y(), b_max.z()}, {0, -size.y(), 0}},
            {{b_min.x(), b_max.y(), b_max.z()}, {0, 0, -size.z()}},
        };
        g.reserve_vertices(48);
        g.reserve_indices(48);
        unsigned int vi = 0;
        for (const auto& br : brackets) {
            // Materialize the Eigen sum: an unmaterialized expression converts
            // to every Vec overload and makes add_vertex ambiguous.
            const Domain::Vec3f end = br.p + br.s;
            g.add_vertex(br.p);
            g.add_vertex(end);
            g.add_index(vi++);
            g.add_index(vi++);
        }
        ref->model.init_from(std::move(g));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1render(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.render();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1reset(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.reset();
        ref->mesh.its.vertices.clear();
        ref->mesh.its.indices.clear();
        ref->emesh = nullptr;
        ref->normals.clear();
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1is_1initialized(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        return ref != nullptr && ref->model.is_initialized() ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1is_1empty(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        return ref == nullptr || ref->model.is_empty() ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->model.reset();
        delete ref;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1id(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        return shader != nullptr && shader->program != nullptr ? (jint) shader->program->get_id() : 0;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1uniform_1location(JNIEnv* env, jclass, jlong ptr, jstring name) {
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        if (shader == nullptr || shader->program == nullptr) return -1;
        const char* chars = env->GetStringUTFChars(name, JNI_FALSE);
        const jint location = (jint) shader->program->get_uniform_location(chars);
        env->ReleaseStringUTFChars(name, chars);
        return location;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1attrib_1location(JNIEnv* env, jclass, jlong ptr, jstring name) {
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        if (shader == nullptr || shader->program == nullptr) return -1;
        const char* chars = env->GetStringUTFChars(name, JNI_FALSE);
        const jint location = (jint) shader->program->get_attrib_location(chars);
        env->ReleaseStringUTFChars(name, chars);
        return location;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1start_1using(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        if (shader != nullptr && shader->program != nullptr) shader->program->start_using();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1stop_1using(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        if (shader != nullptr && shader->program != nullptr) shader->program->stop_using();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        ShaderRef* shader = (ShaderRef*) (intptr_t) ptr;
        if (shader == nullptr) return;
        delete shader->program;
        delete shader;
    }

    // 3x3 helpers so utils_calc_view_normal_matrix needs no Eigen at the call site
    // (Domain::Vec types are Eigen subclasses, but plain arrays keep this entry free
    // of engine math headers entirely).
    namespace {
    void mat3_inverse_transpose(const double in[9], double out[9]) {
        double det = in[0] * (in[4] * in[8] - in[5] * in[7])
                   - in[1] * (in[3] * in[8] - in[5] * in[6])
                   + in[2] * (in[3] * in[7] - in[4] * in[6]);
        if (std::fabs(det) < 1e-18) {
            // Singular: fall back to identity rather than NaN-poisoning the view.
            out[0] = 1; out[1] = 0; out[2] = 0;
            out[3] = 0; out[4] = 1; out[5] = 0;
            out[6] = 0; out[7] = 0; out[8] = 1;
            return;
        }
        double inv = 1.0 / det;
        // Adjugate transposed (= inverse transposed), column-major like the input.
        out[0] = (in[4] * in[8] - in[5] * in[7]) * inv;
        out[1] = (in[2] * in[7] - in[1] * in[8]) * inv;
        out[2] = (in[1] * in[5] - in[2] * in[4]) * inv;
        out[3] = (in[5] * in[6] - in[3] * in[8]) * inv;
        out[4] = (in[0] * in[8] - in[2] * in[6]) * inv;
        out[5] = (in[2] * in[3] - in[0] * in[5]) * inv;
        out[6] = (in[3] * in[7] - in[4] * in[6]) * inv;
        out[7] = (in[1] * in[6] - in[0] * in[7]) * inv;
        out[8] = (in[0] * in[4] - in[1] * in[3]) * inv;
    }

    // 4x4 matrix inverse (column-major), Gauss-Jordan. Returns false if singular.
    bool mat4_inverse(const double in[16], double out[16]) {
        double a[16];
        std::memcpy(a, in, sizeof(a));
        for (int i = 0; i < 16; i++) out[i] = (i % 5 == 0) ? 1.0 : 0.0;
        for (int col = 0; col < 4; col++) {
            int piv = col;
            for (int row = col + 1; row < 4; row++)
                if (std::fabs(a[col * 4 + row]) > std::fabs(a[col * 4 + piv])) piv = row;
            if (std::fabs(a[col * 4 + piv]) < 1e-18) return false;
            if (piv != col) {
                for (int k = 0; k < 4; k++) {
                    std::swap(a[k * 4 + col], a[k * 4 + piv]);
                    std::swap(out[k * 4 + col], out[k * 4 + piv]);
                }
            }
            double d = a[col * 4 + col];
            for (int k = 0; k < 4; k++) { a[k * 4 + col] /= d; out[k * 4 + col] /= d; }
            for (int row = 0; row < 4; row++) {
                if (row == col) continue;
                double f = a[col * 4 + row];
                for (int k = 0; k < 4; k++) { a[k * 4 + row] -= f * a[k * 4 + col]; out[k * 4 + row] -= f * out[k * 4 + col]; }
            }
        }
        return true;
    }
    } // namespace

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1calc_1view_1normal_1matrix(JNIEnv* env, jclass, jdoubleArray view_matrix, jdoubleArray world_matrix, jdoubleArray normal_matrix) {
        jdouble view[16], world[16];
        env->GetDoubleArrayRegion(view_matrix, 0, 16, view);
        env->GetDoubleArrayRegion(world_matrix, 0, 16, world);
        // normal = V33 * inverse(transpose(W33)), column-major.
        double v33[9] = { view[0], view[1], view[2], view[4], view[5], view[6], view[8], view[9], view[10] };
        double w33[9] = { world[0], world[1], world[2], world[4], world[5], world[6], world[8], world[9], world[10] };
        double wInvT[9];
        mat3_inverse_transpose(w33, wInvT);
        jdouble n33[9];
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++)
                n33[c * 3 + r] = v33[r] * wInvT[c * 3] + v33[3 + r] * wInvT[c * 3 + 1] + v33[6 + r] * wInvT[c * 3 + 2];
        env->SetDoubleArrayRegion(normal_matrix, 0, 9, n33);
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1config_1create(JNIEnv* env, jclass, jstring config) {
        // TODO(#212): Java passes a legacy INI string; the INI reader
        // (Slic3rLegacy::DynamicPrintConfig) is src-private in 3.0, and
        // load_preset_and_config takes JSON. Pending the #212 config path.
        (void) env; (void) config;
        LOGD("utils_config_create: stub, INI->ConfigPack pending");
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1config_1release(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see utils_config_create. create() returns 0: nothing to free.
        (void) env; (void) ptr;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1config_1check_1compatibility(JNIEnv* env, jclass, jlong ptr, jstring cond) {
        // TODO(#212): PlaceholderParser now evaluates over Biz config boxes, not a
        // legacy DynamicPrintConfig.Fail-open (as the old FIXME did on parse error).
        (void) env; (void) ptr; (void) cond;
        return true;
    }

    JNIEXPORT jstring JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1config_1eval(JNIEnv* env, jclass, jlong ptr, jstring cond) {
        // TODO(#212): see utils_config_check_compatibility. Throw: callers expect a
        // value, and an empty string would be silently wrong.
        (void) ptr; (void) cond;
        LOGE("utils_config_eval: 3.0 placeholder path not wired");
        env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"),
            "Config evaluation is not available in this build yet (#212)");
        return nullptr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_utils_1unproject(JNIEnv* env, jclass, jdoubleArray view_matrix, jdoubleArray projection_matrix, jint screen_width, jint screen_height, jdouble screen_x, jdouble screen_y) {
        jdouble view[16], proj[16];
        env->GetDoubleArrayRegion(view_matrix, 0, 16, view);
        env->GetDoubleArrayRegion(projection_matrix, 0, 16, proj);
        jdoubleArray arr = env->NewDoubleArray(3);
        jdouble out[3] = { 0, 0, 0 };
        // pm = P * V (column-major).
        double pm[16] = { 0 };
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++)
                pm[c * 4 + r] = proj[r] * view[c * 4] + proj[4 + r] * view[c * 4 + 1]
                              + proj[8 + r] * view[c * 4 + 2] + proj[12 + r] * view[c * 4 + 3];
        double inv[16];
        if (mat4_inverse(pm, inv) && screen_width > 0 && screen_height > 0) {
            // Same convention as before (libigl unproject at win_z = 0, y flipped).
            double ndc[4] = { 2.0 * screen_x / (double) screen_width - 1.0,
                              2.0 * ((double) screen_height - screen_y) / (double) screen_height - 1.0,
                              -1.0, 1.0 };
            double w[4] = { 0 };
            for (int r = 0; r < 4; r++)
                w[r] = inv[r] * ndc[0] + inv[4 + r] * ndc[1] + inv[8 + r] * ndc[2] + inv[12 + r] * ndc[3];
            if (std::fabs(w[3]) > 1e-18) {
                out[0] = w[0] / w[3]; out[1] = w[1] / w[3]; out[2] = w[2] / w[3];
            }
        }
        env->SetDoubleArrayRegion(arr, 0, 3, out);
        return arr;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1create(JNIEnv* env, jclass, jlongArray data) {
        FarmBedRef* ref = new FarmBedRef();
        ref->triangles = std::make_unique<GLModelRef>();
        ref->gridlines = std::make_unique<GLModelRef>();
        ref->contourlines = std::make_unique<GLModelRef>();
        const jlong slots[3] = {
            (jlong) (intptr_t) ref->triangles.get(),
            (jlong) (intptr_t) ref->gridlines.get(),
            (jlong) (intptr_t) ref->contourlines.get(),
        };
        env->SetLongArrayRegion(data, 0, 3, slots);
        return (jlong) (intptr_t) ref;
    }

    // Builds the plate/gridline/contour GL models and the plate raycast mesh
    // from the BedRef's configured contour. Shared by bed_configure (rebuild)
    // and bed_init_triangles_mesh (Java calls it after configure).
    static void bed_build_visuals(FarmBedRef* ref) {
        if (ref == nullptr || !ref->configured || ref->contour_mm.size() < 3) return;

        // bed_utils works on scaled-coordinate polygons (Domain::Point = Vec2crd).
        Domain::Points pts;
        pts.reserve(ref->contour_mm.size());
        for (const Domain::Vec2d& v : ref->contour_mm)
            pts.push_back(BizAlgo::Scaling::scaled(v));
        Domain::ExPolygon bed_poly(std::move(pts));

        ref->triangles->model.reset();
        ref->gridlines->model.reset();
        ref->contourlines->model.reset();
        bed_util_init_triangles(bed_poly, &ref->triangles->model);
        bed_util_init_gridlines(bed_poly, &ref->gridlines->model);
        bed_util_init_contourlines(bed_poly, &ref->contourlines->model);

        // Plate raycast data (tap-to-place / drop-on-bed): the triangles
        // model mirrors the plate as a CPU mesh.
        ::indexed_triangle_set its;
        bed_util_init_triangles_its(bed_poly, &its);
        ref->triangles->mesh.its = std::move(its);
        ref->triangles->emesh = std::make_unique<Slic3r::AABBMesh>(ref->triangles->mesh, true);
        ref->triangles->normals = BizAlgo::TriangleMesh::its_face_normals(ref->triangles->mesh.its);
    }

    // Parses a bed-shape value in either dialect the app writes: the Prusa
    // "XxY,XxY,..." point list or a "[[x,y],...]" JSON pair array (project
    // profiles store printable_area this way). Returns the contour in mm;
    // empty when nothing parses, letting the caller keep the previous shape.
    static Domain::Vec2ds parse_bed_shape_value(const std::string& value) {
        Domain::Vec2ds out;
        if (value.empty()) return out;
        std::string body = value;
        if (body.front() == '[') {
            // "[[x,y],...]" project-JSON array (or "[x,y],..."): split on
            // "],[" boundaries and strip stray brackets per token — the
            // shared outer brackets would otherwise eat the first pair.
            size_t pos = 0;
            while (pos < body.size()) {
                const size_t close = body.find("],[", pos);
                std::string tok = (close == std::string::npos)
                    ? body.substr(pos)
                    : body.substr(pos, close - pos);
                const auto l = tok.find_first_not_of(" \t[");
                const auto r = tok.find_last_not_of(" \t]");
                if (l != std::string::npos && r != std::string::npos && l <= r) {
                    tok = tok.substr(l, r - l + 1);
                    const size_t comma = tok.find(',');
                    if (comma != std::string::npos) {
                        try {
                            out.emplace_back(std::stod(tok.substr(0, comma)),
                                             std::stod(tok.substr(comma + 1)));
                        } catch (const std::exception&) { /* skip malformed pair */ }
                    }
                }
                if (close == std::string::npos) break;
                pos = close + 3;
            }
            return out;
        }
        size_t pos = 0;
        while (pos < value.size()) {
            const size_t comma = value.find(',', pos);
            const std::string tok = value.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
            const size_t x = tok.find('x');
            if (x != std::string::npos) {
                try {
                    out.emplace_back(std::stod(tok.substr(0, x)), std::stod(tok.substr(x + 1)));
                } catch (const std::exception&) { /* skip malformed point */ }
            }
            if (comma == std::string::npos) break;
            pos = comma + 1;
        }
        return out;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1configure(JNIEnv* env, jclass, jlong ptr, jstring config_path) {
        FarmBedRef* ref = (FarmBedRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        const char* chars = env->GetStringUTFChars(config_path, JNI_FALSE);
        const std::string iniPath(chars);
        env->ReleaseStringUTFChars(config_path, chars);

        // The app writes farm-dialect keys (ConfigObject::serialize migrates to
        // flashforge-farm names); legacy Prusa profiles use the Prusa names.
        // bed_shape is "XxY,XxY,..." in mm; printable_area the same list or a
        // "[[x,y],...]" JSON array; max_print_height/printable_height a double.
        Domain::Vec2ds shape;
        float max_height = 200.0f;
        {
            std::ifstream in(iniPath);
            if (!in) return;
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
                std::string value = line.substr(eq + 1);
                const auto first = value.find_first_not_of(" \t\"");
                const auto last = value.find_last_not_of(" \t\"\r");
                if (first == std::string::npos) continue;
                value = value.substr(first, last - first + 1);
                if (key == "bed_shape" || key == "printable_area") {
                    const Domain::Vec2ds parsed = parse_bed_shape_value(value);
                    if (parsed.size() >= 3)
                        shape = parsed;
                } else if (key == "max_print_height" || key == "printable_height") {
                    try { max_height = std::stof(value); } catch (const std::exception&) { }
                }
            }
        }
        if (shape.size() < 3) {
            env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"), "Invalid bed shape");
            return;
        }
        ref->contour_mm = std::move(shape);
        ref->max_print_height = max_height;
        ref->configured = true;

        // Rebuild the plate on every reconfigure.
        bed_build_visuals(ref);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1init_1triangles_1mesh(JNIEnv* env, jclass, jlong ptr, jlong triangles_ptr) {
        // The three models live in the FarmBedRef (Java passes the triangles
        // pointer, but gridlines/contourlines are only reachable from here).
        (void) env; (void) triangles_ptr;
        bed_build_visuals((FarmBedRef*) (intptr_t) ptr);
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1arrange(JNIEnv* env, jclass, jlong ptr, jlong model) {
        // TODO(#212): target is Biz::Arrange::arrange_model_in_place(model, contour,
        // Settings) once the arrange surface is verified; false = "did not fit",
        // matching the old failure signal; never claim a layout we did not compute.
        (void) env; (void) ptr; (void) model;
        LOGD("bed_arrange: stub, arrange port pending");
        return false;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1get_1bounding_1volume(JNIEnv* env, jclass, jlong ptr) {
        FarmBedRef* ref = (FarmBedRef*) (intptr_t) ptr;
        jdoubleArray arr = env->NewDoubleArray(6);
        if (ref == nullptr || !ref->configured) {
            const jdouble zeros[6] = { 0, 0, 0, 0, 0, 0 };
            env->SetDoubleArrayRegion(arr, 0, 6, zeros);
            return arr;
        }
        Domain::Vec2d min(1e30, 1e30), max(-1e30, -1e30);
        for (const Domain::Vec2d& v : ref->contour_mm) {
            min = min.cwiseMin(v);
            max = max.cwiseMax(v);
        }
        const jdouble volume[6] = {
            min.x(), min.y(), 0.0,
            max.x(), max.y(), (jdouble) ref->max_print_height,
        };
        env->SetDoubleArrayRegion(arr, 0, 6, volume);
        return arr;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1get_1bounding_1volume_1max_1size(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        FarmBedRef* ref = (FarmBedRef*) (intptr_t) ptr;
        if (ref == nullptr || !ref->configured) return 0;
        Domain::Vec2d min(1e30, 1e30), max(-1e30, -1e30);
        for (const Domain::Vec2d& v : ref->contour_mm) {
            min = min.cwiseMin(v);
            max = max.cwiseMax(v);
        }
        const Domain::Vec2d size = max - min;
        return (jint) std::max({ size.x(), size.y(), (double) ref->max_print_height });
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        FarmBedRef* ref = (FarmBedRef*) (intptr_t) ptr;
        delete ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1create(JNIEnv* env, jclass) {
        (void) env;
        return (jlong) (intptr_t) new GCodeViewerRef();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1colors(JNIEnv* env, jclass, jlong ptr, jintArray colorsArr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr || colorsArr == nullptr) return;
        jint* colors = env->GetIntArrayElements(colorsArr, JNI_FALSE);
        if (colors == nullptr) return;
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::Skirt,                    { (unsigned char) colors[0],  (unsigned char) colors[1],  (unsigned char) colors[2] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::ExternalPerimeter,        { (unsigned char) colors[3],  (unsigned char) colors[4],  (unsigned char) colors[5] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::SupportMaterial,          { (unsigned char) colors[6],  (unsigned char) colors[7],  (unsigned char) colors[8] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::SupportMaterialInterface, { (unsigned char) colors[9],  (unsigned char) colors[10], (unsigned char) colors[11] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::InternalInfill,           { (unsigned char) colors[12], (unsigned char) colors[13], (unsigned char) colors[14] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::SolidInfill,              { (unsigned char) colors[15], (unsigned char) colors[16], (unsigned char) colors[17] });
        ref->viewer.set_extrusion_role_color(libvgcode::EGCodeExtrusionRole::WipeTower,                { (unsigned char) colors[18], (unsigned char) colors[19], (unsigned char) colors[20] });
        env->ReleaseIntArrayElements(colorsArr, colors, JNI_ABORT);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1render(JNIEnv* env, jclass, jlong ptr, jfloatArray viewMatrixArr, jfloatArray projectionMatrixArr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr || viewMatrixArr == nullptr || projectionMatrixArr == nullptr) return;
        jfloat* viewMatrix = env->GetFloatArrayElements(viewMatrixArr, JNI_FALSE);
        jfloat* projectionMatrix = env->GetFloatArrayElements(projectionMatrixArr, JNI_FALSE);
        if (viewMatrix == nullptr || projectionMatrix == nullptr) {
            if (viewMatrix != nullptr) env->ReleaseFloatArrayElements(viewMatrixArr, viewMatrix, JNI_ABORT);
            if (projectionMatrix != nullptr) env->ReleaseFloatArrayElements(projectionMatrixArr, projectionMatrix, JNI_ABORT);
            return;
        }
        libvgcode::Mat4x4 converted_view_matrix;
        std::memcpy(converted_view_matrix.data(), viewMatrix, 16 * sizeof(float));
        libvgcode::Mat4x4 converted_projection_matrix;
        std::memcpy(converted_projection_matrix.data(), projectionMatrix, 16 * sizeof(float));
        // Per-frame path: never let a viewer exception take down the GL thread.
        try {
            ref->viewer.render(converted_view_matrix, converted_projection_matrix);
        } catch (const std::exception& e) {
            LOGE("vgcode_render failed: %s", e.what());
        }
        env->ReleaseFloatArrayElements(viewMatrixArr, viewMatrix, JNI_ABORT);
        env->ReleaseFloatArrayElements(projectionMatrixArr, projectionMatrix, JNI_ABORT);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1init(JNIEnv* env, jclass, jlong ptr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr || ref->initialized) return;
        try {
            ref->viewer.init(reinterpret_cast<const char*>(glGetString(GL_VERSION)));
            ref->initialized = true;
        } catch (const std::exception& e) {
            LOGE("vgcode_init failed: %s", e.what());
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        }
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1initialized(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        return (ref != nullptr && ref->initialized) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1load(JNIEnv* env, jclass, jlong ptr, jlong resultPtr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        GCodeResultRef* resultRef = (GCodeResultRef*) (intptr_t) resultPtr;
        if (ref == nullptr || resultRef == nullptr) return;
        if (resultRef->parsed == nullptr) {
            // Slice succeeded but the libpgcode pass did not (see model_slice):
            // keep the viewer empty rather than breaking the GL thread; the
            // app-side toolpath fallback still draws (#244).
            LOGE("vgcode_load: no parsed gcode result, viewer stays empty");
            return;
        }
        try {
            ref->data = farm_vgcode::vgcode_convert_input_data(*resultRef->parsed);
            ref->viewer.load(std::move(ref->data));
            ref->viewer.set_time_mode(libvgcode::ETimeMode::Normal);
        } catch (const std::exception& e) {
            LOGE("vgcode_load failed: %s", e.what());
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        }
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1reset(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.reset();
        ref->initialized = false;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1layers_1count(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        return ref == nullptr ? 0 : (jlong) ref->viewer.get_layers_count();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1layers_1view_1range(JNIEnv* env, jclass, jlong ptr, jlong min, jlong max) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.set_layers_view_range(static_cast<uint32_t>(std::max<jlong>(0, min)), static_cast<uint32_t>(std::max<jlong>(0, max)));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1infill_1visibility_1depth(JNIEnv* env, jclass, jlong ptr, jint depth) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.set_infill_visibility_depth(depth);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1fast_1mode(JNIEnv* env, jclass, jlong ptr, jboolean fastMode) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.set_fast_mode(fastMode == JNI_TRUE);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1selected_1object_1id(JNIEnv* env, jclass, jlong ptr, jint id) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.set_selected_object_id(id);
    }

    JNIEXPORT jlongArray JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1layers_1view_1range(JNIEnv* env, jclass, jlong ptr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        jlongArray arr = env->NewLongArray(2);
        jlong range[2] = {0, 0};
        if (ref != nullptr) {
            const auto native = ref->viewer.get_layers_view_range();
            range[0] = (jlong) native[0];
            range[1] = (jlong) native[1];
        }
        env->SetLongArrayRegion(arr, 0, 2, range);
        return arr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.shutdown();
        delete ref;
    }

    JNIEXPORT jfloat JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1estimated_1time(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        return ref == nullptr ? 0.0f : ref->viewer.get_estimated_time();
    }

    JNIEXPORT jfloat JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1estimated_1time_1role(JNIEnv* env, jclass, jlong ptr, jint role) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return 0.0f;
        return ref->viewer.get_extrusion_role_estimated_time(farm_vgcode::vgcode_convert_role(static_cast<Domain::GCodeExtrusionRole>(role)));
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1extrusion_1role_1visible(JNIEnv* env, jclass, jlong ptr, jint role) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return JNI_FALSE;
        return ref->viewer.is_extrusion_role_visible(farm_vgcode::vgcode_convert_role(static_cast<Domain::GCodeExtrusionRole>(role))) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1toggle_1extrusion_1role_1visibility(JNIEnv* env, jclass, jlong ptr, jint role) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.toggle_extrusion_role_visibility(farm_vgcode::vgcode_convert_role(static_cast<Domain::GCodeExtrusionRole>(role)));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1view_1type(JNIEnv* env, jclass, jlong ptr, jint type) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.set_view_type(static_cast<libvgcode::EViewType>(type));
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1view_1type(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        return ref == nullptr ? 0 : static_cast<jint>(ref->viewer.get_view_type());
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1tool_1colors(JNIEnv* env, jclass, jlong ptr, jintArray colorsArr) {
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr || colorsArr == nullptr) return;
        const jsize n = env->GetArrayLength(colorsArr);
        jint* colors = env->GetIntArrayElements(colorsArr, JNI_FALSE);
        if (colors == nullptr) return;
        libvgcode::Palette palette;
        palette.reserve((size_t) n);
        for (jsize i = 0; i < n; ++i) {
            const int v = colors[i];
            palette.push_back({(unsigned char) ((v >> 16) & 0xFF), (unsigned char) ((v >> 8) & 0xFF), (unsigned char) (v & 0xFF)});
        }
        env->ReleaseIntArrayElements(colorsArr, colors, JNI_ABORT);
        if (!palette.empty()) ref->viewer.set_tool_colors(palette);
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1option_1visible(JNIEnv* env, jclass, jlong ptr, jint optionType) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return JNI_FALSE;
        return ref->viewer.is_option_visible(static_cast<libvgcode::EOptionType>(optionType)) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1toggle_1option_1visibility(JNIEnv* env, jclass, jlong ptr, jint optionType) {
        (void) env;
        GCodeViewerRef* ref = (GCodeViewerRef*) (intptr_t) ptr;
        if (ref == nullptr) return;
        ref->viewer.toggle_option_visibility(static_cast<libvgcode::EOptionType>(optionType));
    }

    // ---- Multi-color painting (mm segmentation) ----

    // 3.0 paint states: NONE or Extruder1..Extruder16 (Domain::TriangleSelector).
    static TriangleStateType paint_state(jint filamentIdx) {
        if (filamentIdx <= 0) return TriangleStateType::NONE;
        int v = filamentIdx + (int) TriangleStateType::Extruder1 - 1;
        if (v > (int) TriangleStateType::Extruder16) v = (int) TriangleStateType::Extruder16;
        return static_cast<TriangleStateType>(v);
    }

    // The facet channel a paint session reads/writes, by mode: 0 color, 1 support, 2 seam, 3 fuzzy.
    static Domain::FacetsAnnotation& facets_for_mode(Domain::ModelVolume* v, int mode) {
        switch (mode) {
            case 1:  return v->supported_facets;
            case 2:  return v->seam_facets;
            case 3:  return v->fuzzy_skin_facets;
            default: return v->mm_segmentation_facets;
        }
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1begin(JNIEnv* env, jclass, jlong modelPtr, jint objIdx, jint mode) {
        (void) env;
        ModelRef* mRef = (ModelRef*) (intptr_t) modelPtr;
        Domain::ModelObject* obj = check_object(mRef, objIdx);
        if (obj == nullptr || obj->volumes.empty()) return 0;
        PaintSessionRef* s = new PaintSessionRef();
        s->model = mRef;
        s->objIdx = objIdx;
        s->mode = mode;
        // Same merged object mesh the raycaster uses, so hit coords and triangle
        // indices line up exactly. Paint states are stored per triangle index.
        s->mesh = obj->raw_mesh();
        s->selector = std::make_unique<BizAlgo::TriangleSelector>(s->mesh);
        const Domain::TriangleSelector::TriangleSplittingData& data = facets_for_mode(obj->volumes[0], mode).get_data();
        if (!data.triangles_to_split.empty())
            s->selector->deserialize(data, true);
        s->emesh = new Slic3r::AABBMesh(s->mesh, true);
        return (jlong) (intptr_t) s;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1raycast(JNIEnv* env, jclass, jlong sessionPtr, jdoubleArray originArr, jdoubleArray dirArr) {
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->emesh == nullptr) return env->NewDoubleArray(0);
        jdouble o[3], d[3];
        env->GetDoubleArrayRegion(originArr, 0, 3, o);
        env->GetDoubleArrayRegion(dirArr, 0, 3, d);
        Vec3d origin(o[0], o[1], o[2]);
        Vec3d dir(d[0], d[1], d[2]);
        std::vector<Slic3r::AABBMesh::hit_result> hits = s->emesh->query_ray_hits(origin, dir);
        if (hits.empty()) return env->NewDoubleArray(0);
        const Slic3r::AABBMesh::hit_result& hit = hits.front();
        jdoubleArray arr = env->NewDoubleArray(4);
        Vec3d pos = hit.position();
        double out[4] = { (double) hit.face(), pos.x(), pos.y(), pos.z() };
        env->SetDoubleArrayRegion(arr, 0, 4, out);
        return arr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1brush(JNIEnv* env, jclass, jlong sessionPtr, jdoubleArray hitArr, jint facetStart, jdouble radius, jint filamentIdx, jdoubleArray cameraArr, jboolean sphere) {
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->selector == nullptr) return;
        jdouble h[3];
        env->GetDoubleArrayRegion(hitArr, 0, 3, h);
        Vec3f hit((float) h[0], (float) h[1], (float) h[2]);

        jdouble cam[3];
        env->GetDoubleArrayRegion(cameraArr, 0, 3, cam);
        Vec3d camera_pos_world((double) cam[0], (double) cam[1], (double) cam[2]);

        Domain::ModelObject* obj = check_object(s->model, s->objIdx);
        if (obj == nullptr) return;
        Transform3d inst_matrix = Transform3d::Identity();
        if (!obj->instances.empty() && obj->instances.front() != nullptr) {
            inst_matrix = obj->instances.front()->get_matrix();
        }

        // Convert camera position to local mesh space
        Vec3d camera_pos_local = inst_matrix.inverse() * camera_pos_world;

        Transform3d trafo_no_translate = inst_matrix;
        trafo_no_translate.translation() = Vec3d::Zero();

        TriangleStateType state = paint_state(filamentIdx);

        if (facetStart < 0) {
            const ::indexed_triangle_set& its = s->mesh.its;
            float best = FLT_MAX; int bi = 0;
            for (int i = 0; i < (int) its.indices.size(); ++i) {
                const ::stl_triangle_vertex_indices& t = its.indices[i];
                Vec3f c = (its.vertices[t[0]] + its.vertices[t[1]] + its.vertices[t[2]]) / 3.f;
                float dd = (c - hit).squaredNorm();
                if (dd < best) { best = dd; bi = i; }
            }
            facetStart = bi;
        }

        auto cursor = BizAlgo::TriangleSelector::SinglePointCursor::cursor_factory(
            hit, camera_pos_local.cast<float>(), (float)radius,
            sphere ? BizAlgo::TriangleSelector::CursorType::SPHERE : BizAlgo::TriangleSelector::CursorType::CIRCLE,
            inst_matrix, BizAlgo::TriangleSelector::ClippingPlane(), (float)radius / 5.f);

        s->selector->select_patch(facetStart, std::move(cursor), state, trafo_no_translate, true);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1bucket(JNIEnv* env, jclass, jlong sessionPtr, jdoubleArray hitArr, jint facetStart, jint filamentIdx, jboolean propagate, jdouble angle) {
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->selector == nullptr) return;
        jdouble h[3];
        env->GetDoubleArrayRegion(hitArr, 0, 3, h);
        Vec3f hit((float) h[0], (float) h[1], (float) h[2]);
        // Bucket fill needs a valid starting facet; the Java raycaster only gives the hit point,
        // so find the nearest original facet to it.
        if (facetStart < 0) {
            const ::indexed_triangle_set& its = s->mesh.its;
            float best = FLT_MAX; int bi = 0;
            for (int i = 0; i < (int) its.indices.size(); ++i) {
                const ::stl_triangle_vertex_indices& t = its.indices[i];
                Vec3f c = (its.vertices[t[0]] + its.vertices[t[1]] + its.vertices[t[2]]) / 3.f;
                float dd = (c - hit).squaredNorm();
                if (dd < best) { best = dd; bi = i; }
            }
            facetStart = bi;
        }
        using Selector = BizAlgo::TriangleSelector;
        s->selector->bucket_fill_select_triangles(hit, facetStart, Selector::ClippingPlane(),
            (float) angle, 0.02f /* upstream BucketFillGapArea */,
            propagate ? Selector::BucketFillPropagate::YES : Selector::BucketFillPropagate::NO,
            Selector::ForceReselection::YES);
        s->selector->seed_fill_apply_on_triangles(paint_state(filamentIdx));
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1height(JNIEnv* env, jclass, jlong sessionPtr, jdouble zMin, jdouble zMax, jint filamentIdx) {
        (void) env;
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->selector == nullptr) return;
        TriangleStateType state = paint_state(filamentIdx);
        const ::indexed_triangle_set& its = s->mesh.its;
        for (int i = 0; i < (int) its.indices.size(); ++i) {
            const ::stl_triangle_vertex_indices& tri = its.indices[i];
            const Vec3f& a = its.vertices[tri[0]];
            const Vec3f& b = its.vertices[tri[1]];
            const Vec3f& c = its.vertices[tri[2]];
            float cz = (a.z() + b.z() + c.z()) / 3.f;
            // Paint if centroid OR any vertex is within range. This is critical for large
            // hull triangles that cross range boundaries.
            if ((cz >= zMin && cz <= zMax) ||
                (a.z() >= zMin && a.z() <= zMax) ||
                (b.z() >= zMin && b.z() <= zMax) ||
                (c.z() >= zMin && c.z() <= zMax)) {
                s->selector->set_facet(i, state);
            }
        }
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1clear(JNIEnv* env, jclass, jlong sessionPtr) {
        (void) env;
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->selector == nullptr) return;
        s->selector->reset();
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1commit(JNIEnv* env, jclass, jlong sessionPtr) {
        (void) env;
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr || s->selector == nullptr) return;
        Domain::ModelObject* obj = check_object(s->model, s->objIdx);
        if (obj == nullptr) return;

        // The merged session mesh matches obj->raw_mesh(); commit to volumes[0] as the
        // standard for single-part models. Write to the facet channel for this mode.
        // (The old per-volume "extruder=Auto" config poke has no 3.0 counterpart: the
        // slicer now reads mm paint directly from mm_segmentation_facets.)
        for (Domain::ModelVolume* v : obj->volumes) {
            if (v == obj->volumes[0]) {
                facets_for_mode(v, s->mode).set_data(s->selector->serialize());
            }
        }
    }

    // Returns flattened triangle vertices (9 floats per triangle) for the facets painted with the
    // given filament, for rendering a colored overlay.
    JNIEXPORT jfloatArray JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1get_1mesh(JNIEnv* env, jclass, jlong sessionPtr, jint filamentIdx) {
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        ::indexed_triangle_set its;
        if (s != nullptr && s->selector != nullptr)
            its = s->selector->get_facets(paint_state(filamentIdx));
        std::vector<float> buf;
        buf.reserve(its.indices.size() * 9);
        for (const ::stl_triangle_vertex_indices& tri : its.indices) {
            for (int k = 0; k < 3; ++k) {
                const Vec3f& v = its.vertices[tri[k]];
                buf.push_back(v.x()); buf.push_back(v.y()); buf.push_back(v.z());
            }
        }
        jfloatArray arr = env->NewFloatArray((jsize) buf.size());
        if (!buf.empty()) env->SetFloatArrayRegion(arr, 0, (jsize) buf.size(), buf.data());
        return arr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_paint_1end(JNIEnv* env, jclass, jlong sessionPtr) {
        (void) env;
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (s == nullptr) return;
        delete s->emesh;
        delete s;
    }

    // Accumulate, in the object's merged-mesh coordinate space, the facets painted with the given
    // state across ALL model-part volumes.
    static ::indexed_triangle_set accumulate_paint_facets(Domain::ModelObject* obj, TriangleStateType state) {
        ::indexed_triangle_set out;
        if (obj == nullptr) return out;
        for (const Domain::ModelInstance* inst : obj->instances) {
            if (inst == nullptr) continue;
            const Transform3d inst_m = inst->get_matrix();
            for (const Domain::ModelVolume* v : obj->volumes) {
                if (v == nullptr || !v->is_model_part()) continue;
                const auto& data = v->mm_segmentation_facets.get_data();
                if (data.triangles_to_split.empty()) continue;
                Domain::TriangleMesh vmesh = v->mesh();
                BizAlgo::TriangleSelector sel(vmesh);
                sel.deserialize(data, true);
                ::indexed_triangle_set part = sel.get_facets(state);
                if (part.indices.empty()) continue;
                const Transform3d m = inst_m * v->get_matrix();
                const int base = (int) out.vertices.size();
                out.vertices.reserve(out.vertices.size() + part.vertices.size());
                for (const ::stl_vertex& vert : part.vertices)
                    out.vertices.emplace_back((m * vert.cast<double>()).cast<float>());
                out.indices.reserve(out.indices.size() + part.indices.size());
                for (const ::stl_triangle_vertex_indices& tri : part.indices)
                    out.indices.push_back({tri[0] + base, tri[1] + base, tri[2] + base});
            }
        }
        return out;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_model_1build_1paint_1overlay(JNIEnv* env, jclass, jlong modelPtr, jint objIdx, jlong glPtr, jint filamentIdx) {
        (void) env;
        // World-coords painted-facet overlay: accumulate across all instances
        // and volumes (see accumulate_paint_facets) straight into the GLModel.
        GLModelRef* ref = (GLModelRef*) (intptr_t) glPtr;
        ModelRef* m = (ModelRef*) (intptr_t) modelPtr;
        if (ref == nullptr || m == nullptr) return 0;
        Domain::ModelObject* obj = check_object(m, objIdx);
        if (obj == nullptr) return 0;
        ::indexed_triangle_set its = accumulate_paint_facets(obj, paint_state(filamentIdx));
        ref->model.reset();
        ref->model.init_from(its);
        return (jint) its.indices.size();
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_model_1has_1paint(JNIEnv* env, jclass, jlong modelPtr, jint objIdx) {
        (void) env;
        ModelRef* m = (ModelRef*) (intptr_t) modelPtr;
        Domain::ModelObject* obj = check_object(m, objIdx);
        if (obj == nullptr) return false;
        for (const Domain::ModelVolume* v : obj->volumes) {
            if (v != nullptr && v->is_model_part() && !v->mm_segmentation_facets.get_data().triangles_to_split.empty())
                return true;
        }
        return false;
    }

    // Highest filament index (1-based) that has any painted facets across the object's model-part
    // volumes, or 0 if none.
    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_model_1paint_1max_1filament(JNIEnv* env, jclass, jlong modelPtr, jint objIdx) {
        (void) env;
        ModelRef* m = (ModelRef*) (intptr_t) modelPtr;
        Domain::ModelObject* obj = check_object(m, objIdx);
        if (obj == nullptr) return 0;
        int maxF = 0;
        for (const Domain::ModelVolume* v : obj->volumes) {
            if (v == nullptr || !v->is_model_part()) continue;
            const auto& data = v->mm_segmentation_facets.get_data();
            if (data.triangles_to_split.empty()) continue;
            Domain::TriangleMesh vmesh = v->mesh();
            BizAlgo::TriangleSelector sel(vmesh);
            sel.deserialize(data, true);
            for (int f = (int) TriangleStateType::Extruder16; f > maxF; --f) {
                if (!sel.get_facets(static_cast<TriangleStateType>(f)).indices.empty()) {
                    maxF = f;
                    break;
                }
            }
        }
        return (jint) maxF;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1paint(JNIEnv* env, jclass, jlong glPtr, jlong sessionPtr, jint filamentIdx) {
        (void) env;
        GLModelRef* ref = (GLModelRef*) (intptr_t) glPtr;
        PaintSessionRef* s = (PaintSessionRef*) (intptr_t) sessionPtr;
        if (ref == nullptr) return 0;
        // Paint-session mesh-local coordinates (see PaintSessionRef).
        ::indexed_triangle_set its;
        if (s != nullptr && s->selector != nullptr)
            its = s->selector->get_facets(paint_state(filamentIdx));
        ref->model.reset();
        ref->model.init_from(its);
        return (jint) its.indices.size();
    }
}
