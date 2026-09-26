// Auto-orient wrapper around the genuine upstream PrusaSlicer orienter.
//
// Isolated in its own translation unit (like farm_progress.cpp) for one reason:
// Orient.hpp transitively includes libslic3r/Point.hpp -> libslic3r.h, which
// defines a global SCALED_EPSILON that collides with Slic3r::Domain's constant
// in any TU that also uses render/bed_utils.hpp. Keeping the include out of
// farm_native.cpp avoids that clash entirely.

#include "farm_orient.hpp"

#include <android/log.h>

#include "Slic3r/Biz/Algorithms/ModelObject.hpp"
#include "Slic3r/Biz/Algorithms/TriangleMesh.hpp"

#include "Orient.hpp"

#include <exception>
#include <vector>

#define LOG_TAG "FarmOrient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Consume an upstream-orienter result exactly the way upstream intends:
// orientation() fills in rotation_matrix / euler_angles on the OrientMesh (both
// the serial and parallel branches compute rotation_from_two_vectors(dir, +Z)),
// and the transformation matrix is applied in the same per-volume composition
// that upstream's orient(ModelObject*) used: keep each volume's offset in world
// space, rotate its linear part, then re-seat the object on the bed.
static void apply_orientation(Slic3r::Domain::ModelObject* obj,
                              const Slic3r::orientation::OrientMesh& oriented)
{
    if (obj == nullptr || !oriented.rotation_matrix.allFinite()) return;
    const Slic3r::Domain::Transform3d rotation_matrix =
        Slic3r::Domain::Transform3d(oriented.rotation_matrix);
    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol == nullptr) continue;
        const Slic3r::Domain::Transformation& old_transform = vol->get_transformation();
        vol->set_transformation(old_transform.get_offset_matrix() * rotation_matrix *
                                old_transform.get_matrix_no_offset());
    }
    obj->invalidate_bounding_box();
    Slic3r::Biz::Algorithms::ModelObject::ensure_on_bed(*obj, false);
}

extern "C" void farm_auto_orient(void* model_object_ptr, double overhang_angle)
{
    if (model_object_ptr == nullptr) return;
    auto* obj = static_cast<Slic3r::Domain::ModelObject*>(model_object_ptr);
    try {
        const Slic3r::Domain::TriangleMesh mesh =
            Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
        if (mesh.its.vertices.empty() || mesh.its.indices.empty()) {
            LOGE("farm_auto_orient: no mesh (instances=%zu volumes=%zu)",
                 obj->instances.size(), obj->volumes.size());
            return;
        }

        // Upstream AutoOrienter + OrientParams: real support-area minimization,
        // overhang angle, low-angle-face and appearance-face preferences.
        // The results are consumed through upstream's own apply()/setter API.
        Slic3r::orientation::OrientMesh om;
        om.mesh = mesh;
        om.overhang_angle = overhang_angle;
        om.setter = [obj](const Slic3r::orientation::OrientMesh& o) { apply_orientation(obj, o); };
        Slic3r::orientation::OrientMeshs items{om};

        // The public orient() entry calls progressfn(i, name) unconditionally
        // per item; with the default empty std::function that throws
        // std::bad_function_call and the whole orient silently no-ops (the
        // removed 2.x wrappers never hit this because they built AutoOrienter
        // directly). Hand in a callable progress hook.
        Slic3r::orientation::OrientParams params;
        params.progressind = [](unsigned, const std::string&) {};
        Slic3r::orientation::orient(items, {}, params);

        const Slic3r::orientation::OrientMesh& oriented = items.front();
        LOGD("farm_auto_orient: facets=%zu dir=(%+.4f, %+.4f, %+.4f) rotFinite=%d",
             mesh.its.indices.size(), oriented.orientation.x(), oriented.orientation.y(),
             oriented.orientation.z(), (int) oriented.rotation_matrix.allFinite());
        oriented.apply();
        LOGD("farm_auto_orient: applied rotation onto +Z and re-seated on bed");
    } catch (const std::exception& e) {
        LOGE("farm_auto_orient failed: %s", e.what());
    }
}

extern "C" void farm_auto_orient_batch(void* const* raw_objects, size_t count,
                                       double overhang_angle,
                                       FarmOrientProgressFn progress)
{
    if (raw_objects == nullptr || count == 0) return;
    try {
        std::vector<Slic3r::Domain::ModelObject*> objects;
        objects.reserve(count);
        for (size_t k = 0; k < count; ++k) {
            if (raw_objects[k] == nullptr) continue;
            objects.push_back(static_cast<Slic3r::Domain::ModelObject*>(raw_objects[k]));
        }
        if (objects.empty()) return;

        Slic3r::orientation::OrientMeshs items;
        items.reserve(objects.size());
        for (Slic3r::Domain::ModelObject* obj : objects) {
            const Slic3r::Domain::TriangleMesh mesh =
                Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
            if (mesh.its.vertices.empty() || mesh.its.indices.empty()) continue;
            Slic3r::orientation::OrientMesh om;
            om.mesh = mesh;
            om.overhang_angle = overhang_angle;
            om.name = obj->name;
            om.setter = [obj](const Slic3r::orientation::OrientMesh& o) { apply_orientation(obj, o); };
            items.push_back(std::move(om));
        }
        if (items.empty()) return;

        Slic3r::orientation::OrientParams params;
        params.progressind = [progress](unsigned tag, const std::string& name) {
            if (progress) progress(tag, name);
        };
        Slic3r::orientation::orient(items, {}, params);

        for (size_t j = 0; j < items.size() && j < objects.size(); ++j) {
            items[j].apply();
            LOGD("farm_auto_orient_batch: object %zu of %zu oriented: dir=(%+.4f, %+.4f, %+.4f)",
                 j + 1, items.size(), items[j].orientation.x(),
                 items[j].orientation.y(), items[j].orientation.z());
        }
    } catch (const std::exception& e) {
        LOGE("farm_auto_orient_batch failed: %s", e.what());
    }
}
