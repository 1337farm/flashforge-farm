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
#include <cfloat>
#include <cmath>
#include <cstring>
#include <memory>
#include <numbers>
#include <string>
#include <vector>

#include <jni.h>

#include "Slic3r/Domain/Types.hpp"
#include "Slic3r/Domain/Model.hpp"
#include "Slic3r/Domain/ModelObject.hpp"
#include "Slic3r/Domain/ModelVolume.hpp"
#include "Slic3r/Domain/ModelInstance.hpp"
#include "Slic3r/Domain/TriangleMesh.hpp"
#include "Slic3r/Domain/CutConnector.hpp"
#include "Slic3r/Domain/LayerHeightProfile.hpp"
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

// GLShaderProgram lives in the app render stack (still 2.x); forward-declare so
// get_current_shader() keeps its signature without pulling those headers.
namespace Slic3r { class GLShaderProgram; }

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
// TODO(#212): opaque until the GL render stack ports to 3.0.
struct ShaderRef {
    Slic3r::GLShaderProgram* program = nullptr;
};
// TODO(#212): opaque until the GL render stack ports to 3.0.
struct GLModelRef {
    bool initialized = false;
};
// Bed data that survives without the GL bed visuals. `contour` is filled by
// bed_configure once a public 3.0 INI->bed-shape reader exists; bed_arrange
// then drives Biz::Arrange::arrange_model_in_place.
struct BedRef {
    Domain::Vec2ds contour;
    bool configured = false;
};
// TODO(#212): opaque until the gcode preview stack ports to 3.0.
struct GCodeViewerRef {
    bool initialized = false;
};
struct GCodeResultRef {
    std::string name;
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

// TODO(#212): no shader stack on the 3.0 bridge yet; the engine render utils
// treat null as "no program bound".
Slic3r::GLShaderProgram* Slic3r::get_current_shader() {
    return nullptr;
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
        // Domain::TriangleMesh plus GL planes for picking; both pending.
        (void) env; (void) ptr; (void) i;
        LOGD("model_create_flatten_planes: stub, pending");
        return nullptr;
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
        // Slice prep for #213: the 3.0 shape is read_model_from_file +
        // Biz::Config::load_preset_and_config(JSON) + init_print(FFF, callbacks, id) +
        // IPrint::update(model, pack, bed, metadata, serializer) + slice(id, thumbnails,
        // nullopt), with results via IProcessCallbacks::on_fdm_result (see
        // engine/prusa30/farm_driver.cpp). Two gaps block wiring it here:
        //   1. Java passes a legacy INI file; load_preset_and_config takes JSON, and the
        //      INI reader (Slic3rLegacy::DynamicPrintConfig) is src-private in 3.0.
        //   2. FDMResult (libpgcode::ProcessorResult) -> gcode text/export has no verified
        //      JNI-side path yet (export_gcode/Print::apply/process are gone).
        // Throw: a slice must never report silent success.
        (void) ptr; (void) configPath; (void) path; (void) listener;
        (void) numFilaments; (void) colorsArr; (void) calibMode;
        (void) calibStart; (void) calibEnd; (void) calibStep;
        LOGE("model_slice: 3.0 slice path not wired yet (see #213)");
        env->ThrowNew(env->FindClass("com/flashforge/farm/slic3r/Slic3rRuntimeError"),
            "Slicing is not available in this build: the 3.0 slice path (INI config + gcode export) is still being ported (#213)");
        return 0;
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
        // TODO(#212): GCodeProcessor::process_file/extract_result have no verified 3.0
        // counterpart (preview moved to libpgcode/slic3r-gcode-reader); throw, never
        // return an empty-but-successful handle.
        (void) path; (void) name;
        LOGE("gcoderesult_load_file: 3.0 gcode-parse path not wired");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
            "G-code preview is not available in this build yet (#212)");
        return 0;
    }

    JNIEXPORT jstring JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1recommended_1name(JNIEnv* env, jclass, jlong ptr) {
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        if (ref == nullptr) return nullptr;
        return env->NewStringUTF(ref->name.c_str());
    }

    JNIEXPORT jdouble JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1used_1filament_1mm(JNIEnv* env, jclass, jlong ptr, jint role) {
        // TODO(#212): no print_statistics without a 3.0 gcode result.
        (void) env; (void) ptr; (void) role;
        return 0.0;
    }

    JNIEXPORT jdouble JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1get_1used_1filament_1g(JNIEnv* env, jclass, jlong ptr, jint role) {
        // TODO(#212): see get_used_filament_mm.
        (void) env; (void) ptr; (void) role;
        return 0.0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_gcoderesult_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        GCodeResultRef* ref = (GCodeResultRef*) (intptr_t) ptr;
        delete ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1init_1from_1texts(JNIEnv* env, jclass, jstring name, jstring fsText, jstring vsText) {
        // TODO(#212): GL render stack still 2.x; no shader program to build.
        (void) env; (void) name; (void) fsText; (void) vsText;
        LOGD("shader_init_from_texts: stub, GL stack pending");
        return 0;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1create(JNIEnv* env, jclass) {
        // TODO(#212): GLModel (render/GLModel.hpp) includes deleted libslic3r headers.
        (void) env;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1raycast_1data(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1raycast_1closest_1hit(JNIEnv* env, jclass, jlong ptr, jdoubleArray pointArr, jdoubleArray directionArr) {
        // TODO(#212): see glmodel_create. Empty hit list (never null) keeps callers safe.
        (void) ptr; (void) pointArr; (void) directionArr;
        return env->NewDoubleArray(0);
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1model(JNIEnv* env, jclass, jlong ptr, jlong model) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) model;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1from_1model_1object(JNIEnv* env, jclass, jlong ptr, jlong model, jint i) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) model; (void) i;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1set_1color(JNIEnv* env, jclass, jlong ptr, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) red; (void) green; (void) blue; (void) alpha;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1stilized_1arrow(JNIEnv* env, jclass, jlong ptr, jfloat tip_radius, jfloat tip_length, jfloat stem_radius, jfloat stem_length) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) tip_radius; (void) tip_length; (void) stem_radius; (void) stem_length;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1background_1triangles(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1box(JNIEnv* env, jclass, jlong ptr, jfloat width, jfloat depth, jfloat height) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) width; (void) depth; (void) height;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1init_1bounding_1box(JNIEnv* env, jclass, jlong ptr, jlong modelPtr, jint i) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr; (void) modelPtr; (void) i;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1render(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create. Silent: called per frame, must not log-spam.
        (void) env; (void) ptr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1reset(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1is_1initialized(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr;
        return false;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1is_1empty(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create.
        (void) env; (void) ptr;
        return true;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_glmodel_1release(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see glmodel_create. create() returns 0 so there is nothing to free.
        (void) env; (void) ptr;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1id(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr;
        return 0;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1uniform_1location(JNIEnv* env, jclass, jlong ptr, jstring name) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr; (void) name;
        return 0;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1get_1attrib_1location(JNIEnv* env, jclass, jlong ptr, jstring name) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr; (void) name;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1start_1using(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1stop_1using(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_shader_1release(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see shader_init_from_texts.
        (void) env; (void) ptr;
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
        BedRef* ref = new BedRef();
        // The 3 GL slots (triangles/gridlines/contourlines) have no 3.0 GL backend
        // yet; report nulls so Java never dereferences a dead pointer.
        jlong zero = 0;
        for (int i = 0; i < 3; i++)
            env->SetLongArrayRegion(data, i, 1, &zero);
        return (jlong) (intptr_t) ref;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1configure(JNIEnv* env, jclass, jlong ptr, jstring config_path) {
        // TODO(#212): the bed needs printable_area/printable_height from a legacy INI
        // file, whose reader is src-private in 3.0. Target: Domain::Bed::create(
        // BedCreationData{contour, max_print_height}) + BedInstance; see #213.
        (void) ptr;
        const char* chars = env->GetStringUTFChars(config_path, JNI_FALSE);
        env->ReleaseStringUTFChars(config_path, chars);
        LOGD("bed_configure: stub, INI->BedCreationData pending");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1init_1triangles_1mesh(JNIEnv* env, jclass, jlong ptr, jlong triangles_ptr) {
        // TODO(#212): bed triangulation visuals need the GL stack.
        (void) env; (void) ptr; (void) triangles_ptr;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1arrange(JNIEnv* env, jclass, jlong ptr, jlong model) {
        // TODO(#212): target is Biz::Arrange::arrange_model_in_place(model, contour,
        // Settings) once bed_configure supplies the contour. False = "did not fit",
        // matching the old failure signal; never claim a layout we did not compute.
        (void) env; (void) ptr; (void) model;
        LOGD("bed_arrange: stub, contour pending");
        return false;
    }

    JNIEXPORT jdoubleArray JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1get_1bounding_1volume(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): backed by Domain::BedInstance once bed_configure lands.
        (void) ptr;
        jdoubleArray arr = env->NewDoubleArray(6);
        jdouble zeros[6] = { 0, 0, 0, 0, 0, 0 };
        env->SetDoubleArrayRegion(arr, 0, 6, zeros);
        return arr;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1get_1bounding_1volume_1max_1size(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see bed_get_bounding_volume.
        (void) env; (void) ptr;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_bed_1release(JNIEnv* env, jclass, jlong ptr) {
        (void) env;
        BedRef* ref = (BedRef*) (intptr_t) ptr;
        delete ref;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1create(JNIEnv* env, jclass) {
        // TODO(#212): libvgcode Viewer stack still 2.x (Viewer.hpp needs deleted headers).
        (void) env;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1colors(JNIEnv* env, jclass, jlong ptr, jintArray colorsArr) {
        // TODO(#212): see vgcode_create. Silent: called on preview setup paths.
        (void) env; (void) ptr; (void) colorsArr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1render(JNIEnv* env, jclass, jlong ptr, jfloatArray viewMatrixArr, jfloatArray projectionMatrixArr) {
        // TODO(#212): see vgcode_create. Silent: called per frame, must not log-spam.
        (void) env; (void) ptr; (void) viewMatrixArr; (void) projectionMatrixArr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1init(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
        LOGD("vgcode_init: stub, preview stack pending");
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1initialized(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
        return false;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1load(JNIEnv* env, jclass, jlong ptr, jlong resultPtr) {
        // TODO(#212): needs the 3.0 gcode result (see gcoderesult_load_file).
        (void) env; (void) ptr; (void) resultPtr;
        LOGD("vgcode_load: stub, gcode result pending");
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1reset(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
    }

    JNIEXPORT jlong JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1layers_1count(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1layers_1view_1range(JNIEnv* env, jclass, jlong ptr, jlong min, jlong max) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) min; (void) max;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1infill_1visibility_1depth(JNIEnv* env, jclass, jlong ptr, jint depth) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) depth;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1fast_1mode(JNIEnv* env, jclass, jlong ptr, jboolean fastMode) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) fastMode;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1selected_1object_1id(JNIEnv* env, jclass, jlong ptr, jint id) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) id;
    }

    JNIEXPORT jlongArray JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1layers_1view_1range(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) ptr;
        jlongArray arr = env->NewLongArray(2);
        jlong range[2] = { 0, 0 };
        env->SetLongArrayRegion(arr, 0, 2, range);
        return arr;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1release(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create. create() returns 0: nothing to free.
        (void) env; (void) ptr;
    }

    JNIEXPORT jfloat JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1estimated_1time(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
        return 0;
    }

    JNIEXPORT jfloat JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1estimated_1time_1role(JNIEnv* env, jclass, jlong ptr, jint role) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) role;
        return 0;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1extrusion_1role_1visible(JNIEnv* env, jclass, jlong ptr, jint role) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) role;
        return false;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1toggle_1extrusion_1role_1visibility(JNIEnv* env, jclass, jlong ptr, jint role) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) role;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1view_1type(JNIEnv* env, jclass, jlong ptr, jint type) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) type;
    }

    JNIEXPORT jint JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1get_1view_1type(JNIEnv* env, jclass, jlong ptr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr;
        return 0;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1set_1tool_1colors(JNIEnv* env, jclass, jlong ptr, jintArray colorsArr) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) colorsArr;
    }

    JNIEXPORT jboolean JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1is_1option_1visible(JNIEnv* env, jclass, jlong ptr, jint optionType) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) optionType;
        return false;
    }

    JNIEXPORT void JNICALL Java_com_flashforge_farm_slic3r_Native_vgcode_1toggle_1option_1visibility(JNIEnv* env, jclass, jlong ptr, jint optionType) {
        // TODO(#212): see vgcode_create.
        (void) env; (void) ptr; (void) optionType;
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
        // TODO(#212): facet accumulation is 3.0 (see accumulate_paint_facets) but the
        // GLModel target is still 2.x. Returns 0 triangles until the GL stack ports.
        (void) env; (void) modelPtr; (void) objIdx; (void) glPtr; (void) filamentIdx;
        return 0;
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
        // TODO(#212): facet extraction is 3.0 but the GLModel target is still 2.x.
        (void) env; (void) glPtr; (void) sessionPtr; (void) filamentIdx;
        return 0;
    }
}
