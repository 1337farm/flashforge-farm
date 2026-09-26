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

#define LOG_TAG "FarmOrient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
        Slic3r::orientation::OrientMesh om;
        om.mesh = mesh;
        om.overhang_angle = overhang_angle;
        Slic3r::orientation::OrientMeshs items{om};

        // The public orient() entry calls progressfn(i, name) unconditionally
        // per item; with the default empty std::function that throws
        // std::bad_function_call and the whole orient silently no-ops (the
        // removed 2.x wrappers never hit this because they built AutoOrienter
        // directly). Hand in a callable progress hook.
        Slic3r::orientation::OrientParams params;
        params.progressind = [](unsigned, const std::string&) {};
        Slic3r::orientation::orient(items, {}, params);

        const Slic3r::Domain::Vec3d dir = items.front().orientation;
        LOGD("farm_auto_orient: facets=%zu dir=(%+.4f, %+.4f, %+.4f)",
             mesh.its.indices.size(), dir.x(), dir.y(), dir.z());
        if (!(dir.norm() > 1e-9) || !dir.allFinite()) {
            LOGE("farm_auto_orient: degenerate orientation, leaving part unchanged");
            return;
        }

        // Exactly the upstream orient(ModelObject*) application: negate the
        // found up-direction (so setFromTwoVectors(tnormal, -Z) actually maps
        // the part's up-direction onto +Z), rotate every volume in world
        // space while keeping its offset, then re-seat the object on the bed.
        const Slic3r::Domain::Vec3d tnormal = -dir;
        const Slic3r::Domain::Transform3d rotation_matrix =
            Slic3r::Domain::Transform3d(Eigen::Quaterniond().setFromTwoVectors(
                tnormal, -Slic3r::Domain::Vec3d::UnitZ()));
        for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
            if (vol == nullptr) continue;
            const Slic3r::Domain::Transformation& old_transform = vol->get_transformation();
            vol->set_transformation(old_transform.get_offset_matrix() * rotation_matrix *
                                    old_transform.get_matrix_no_offset());
        }
        obj->invalidate_bounding_box();
        Slic3r::Biz::Algorithms::ModelObject::ensure_on_bed(*obj, false);
        LOGD("farm_auto_orient: applied rotation onto +Z and re-seated on bed");
    } catch (const std::exception& e) {
        LOGE("farm_auto_orient failed: %s", e.what());
    }
}
