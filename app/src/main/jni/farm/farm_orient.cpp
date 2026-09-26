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

#include <cmath>
#include <exception>
#include <vector>

#define LOG_TAG "FarmOrient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Apply an upstream-orienter result as the object's CANONICAL pose. The
// orienter's candidate directions are face/hull normals of the CURRENT mesh,
// and its best orientation is scored against that same posed mesh, so feeding
// it a user-rotated object both changes the candidate set and (when the chosen
// rotation is composed on top of the existing one) produces a different final
// stance — a model lying on a corner instead of its proper face. To make the
// result a pure function of the geometry, orient_object first strips every
// volume's rotation; the mesh supplied to the orienter is then the object's
// own shape, and here we REPLACE the volume rotation with the chosen one while
// keeping the placement (offset) and scale intact.
static void apply_canonical_orientation(Slic3r::Domain::ModelObject* obj,
                                        const Slic3r::orientation::OrientMesh& oriented)
{
    if (obj == nullptr || !oriented.rotation_matrix.allFinite()) return;
    const Slic3r::Domain::Transform3d rotation_matrix =
        Slic3r::Domain::Transform3d(oriented.rotation_matrix);
    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol == nullptr) continue;
        const Slic3r::Domain::Transformation& t = vol->get_transformation();
        vol->set_transformation(t.get_offset_matrix() * t.get_scaling_factor_matrix() *
                                rotation_matrix);
    }
    obj->invalidate_bounding_box();
    Slic3r::Biz::Algorithms::ModelObject::ensure_on_bed(*obj, false);
}

// Orient one object with the genuine upstream minimizer against its OWN
// geometry. The orienter's candidate directions come from the CURRENT mesh's
// face/hull normals, so any pose-dependent run (single or iterated) can settle
// on a different face; strip the volumes' rotation first so the candidates and
// scores describe the shape itself, then apply the chosen rotation as the new
// canonical pose. Progress reports the 20/30/60/80 per-stage markers (part
// index < 20 on part start) so the background run stays visible to the UI.
static void orient_object(Slic3r::Domain::ModelObject* obj, double overhang_angle,
                          const FarmOrientProgressFn& progress, unsigned part_index)
{
    if (obj == nullptr) return;

    for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
        if (vol == nullptr) continue;
        const Slic3r::Domain::Transformation& t = vol->get_transformation();
        vol->set_transformation(t.get_offset_matrix() * t.get_scaling_factor_matrix());
    }

    try {
        const Slic3r::Domain::TriangleMesh mesh =
            Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
        if (mesh.its.vertices.empty() || mesh.its.indices.empty()) {
            LOGE("farm_orient: no mesh (instances=%zu volumes=%zu)",
                 obj->instances.size(), obj->volumes.size());
            return;
        }

        Slic3r::orientation::OrientMesh om;
        om.mesh = mesh;
        om.overhang_angle = overhang_angle;
        om.name = obj->name;
        om.setter = [obj](const Slic3r::orientation::OrientMesh& o) { apply_canonical_orientation(obj, o); };
        Slic3r::orientation::OrientMeshs items{om};

        // The public orient() entry calls progressfn(i, name) unconditionally
        // per item; with the default empty std::function that throws
        // std::bad_function_call and the whole orient silently no-ops. Hand in
        // a callable hook instead.
        Slic3r::orientation::OrientParams params;
        params.progressind = [progress, part_index](unsigned tag, const std::string& name) {
            if (progress) progress(tag < 20 ? part_index : tag, name);
        };
        Slic3r::orientation::orient(items, {}, params);

        const Slic3r::orientation::OrientMesh& res = items.front();
        if (!res.orientation.allFinite() || res.orientation.norm() < 1e-6 ||
            !res.rotation_matrix.allFinite()) {
            LOGD("farm_orient: degenerate solution, leaving orientation unchanged");
            return;
        }
        const double rot_deg = std::abs(res.angle) * 180.0 / std::acos(-1.0);
        res.apply();
        LOGD("farm_orient: dir=(%+.4f, %+.4f, %+.4f) rot=%.2fdeg facets=%zu",
             res.orientation.x(), res.orientation.y(), res.orientation.z(),
             rot_deg, mesh.its.indices.size());
    } catch (const std::exception& e) {
        LOGE("farm_orient: orient failed: %s", e.what());
    }
}

extern "C" void farm_auto_orient(void* model_object_ptr, double overhang_angle)
{
    if (model_object_ptr == nullptr) return;
    orient_object(static_cast<Slic3r::Domain::ModelObject*>(model_object_ptr),
                  overhang_angle, FarmOrientProgressFn(), 0);
}

extern "C" void farm_auto_orient_batch(void* const* raw_objects, size_t count,
                                       double overhang_angle,
                                       FarmOrientProgressFn progress)
{
    if (raw_objects == nullptr || count == 0) return;
    std::vector<Slic3r::Domain::ModelObject*> objects;
    for (size_t k = 0; k < count; ++k) {
        if (raw_objects[k] == nullptr) continue;
        objects.push_back(static_cast<Slic3r::Domain::ModelObject*>(raw_objects[k]));
    }
    for (size_t j = 0; j < objects.size(); ++j) {
        orient_object(objects[j], overhang_angle, progress, (unsigned) j);
        LOGD("farm_auto_orient_batch: object %zu of %zu converged", j + 1, objects.size());
    }
}
