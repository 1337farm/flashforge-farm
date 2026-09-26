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

// Orient one object with the genuine upstream minimizer, iterating to a
// fixpoint: the orienter's candidate directions come from the CURRENT mesh's
// face/hull normals, so on some geometry a single pass settles on a local
// optimum that re-running on the rotated pose improves (hit the button twice,
// the second run lands on the proper face). Re-run the identical upstream
// orienter on the just-rotated mesh until the chosen rotation is ~0 or the
// iteration cap is hit. Every pass is genuine upstream; nothing farm-side.
// Progress reports only the first pass (part index < 20 on part start, then
// the 20/30/60/80 per-stage markers) so the loop stays invisible to the UI.
static void orient_object(Slic3r::Domain::ModelObject* obj, double overhang_angle,
                          const FarmOrientProgressFn& progress, unsigned part_index)
{
    if (obj == nullptr) return;
    constexpr int MAX_ITERATIONS = 5;
    for (int it = 0; it < MAX_ITERATIONS; ++it) {
        try {
            const Slic3r::Domain::TriangleMesh mesh =
                Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
            if (mesh.its.vertices.empty() || mesh.its.indices.empty()) {
                if (it == 0)
                    LOGE("farm_orient: no mesh (instances=%zu volumes=%zu)",
                         obj->instances.size(), obj->volumes.size());
                return;
            }

            Slic3r::orientation::OrientMesh om;
            om.mesh = mesh;
            om.overhang_angle = overhang_angle;
            om.name = obj->name;
            om.setter = [obj](const Slic3r::orientation::OrientMesh& o) { apply_orientation(obj, o); };
            Slic3r::orientation::OrientMeshs items{om};

            // The public orient() entry calls progressfn(i, name) unconditionally
            // per item; with the default empty std::function that throws
            // std::bad_function_call and the whole orient silently no-ops (the
            // removed 2.x wrappers never hit this because they built AutoOrienter
            // directly). Hand in a callable hook, throttled to the first pass.
            Slic3r::orientation::OrientParams params;
            params.progressind = [progress, part_index, it](unsigned tag, const std::string& name) {
                if (progress && it == 0) progress(tag < 20 ? part_index : tag, name);
            };
            Slic3r::orientation::orient(items, {}, params);

            const Slic3r::orientation::OrientMesh& res = items.front();
            if (!res.orientation.allFinite() || res.orientation.norm() < 1e-6 ||
                !res.rotation_matrix.allFinite()) {
                LOGD("farm_orient: degenerate solution on iteration %d, leaving as-is", it + 1);
                return;
            }
            const double rot_deg = std::abs(res.angle) * 180.0 / std::acos(-1.0);
            res.apply();
            LOGD("farm_orient: iter %d dir=(%+.4f, %+.4f, %+.4f) rot=%.2fdeg facets=%zu",
                 it + 1, res.orientation.x(), res.orientation.y(), res.orientation.z(),
                 rot_deg, mesh.its.indices.size());
            if (rot_deg < 0.5) break;  // converged: re-seated on the minimizer's face
        } catch (const std::exception& e) {
            LOGE("farm_orient: iteration %d failed: %s", it + 1, e.what());
            return;
        }
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
