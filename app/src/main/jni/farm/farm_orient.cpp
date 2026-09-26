// Auto-orient wrapper around the genuine upstream PrusaSlicer orienter.
//
// Isolated in its own translation unit (like farm_progress.cpp) for one reason:
// Orient.hpp transitively includes libslic3r/Point.hpp -> libslic3r.h, which
// defines a global SCALED_EPSILON that collides with Slic3r::Domain's constant
// in any TU that also uses render/bed_utils.hpp. Keeping the include out of
// farm_native.cpp avoids that clash entirely.

#include "farm_orient.hpp"

#include "Slic3r/Biz/Algorithms/ModelObject.hpp"
#include "Slic3r/Biz/Algorithms/TriangleMesh.hpp"

#include "Orient.hpp"

#include <exception>

extern "C" void farm_auto_orient(void* model_object_ptr, double overhang_angle)
{
    if (model_object_ptr == nullptr) return;
    auto* obj = static_cast<Slic3r::Domain::ModelObject*>(model_object_ptr);
    try {
        const Slic3r::Domain::TriangleMesh mesh =
            Slic3r::Biz::Algorithms::ModelObject::mesh(*obj);
        if (mesh.its.vertices.empty() || mesh.its.indices.empty()) return;

        // Upstream AutoOrienter + OrientParams: real support-area minimization,
        // overhang angle, low-angle-face and appearance-face preferences.
        Slic3r::orientation::OrientMesh om;
        om.mesh = mesh;
        om.overhang_angle = overhang_angle;
        Slic3r::orientation::OrientMeshs items{om};
        Slic3r::orientation::orient(items, {});

        const Slic3r::Domain::Vec3d dir = items.front().orientation;
        if (!(dir.norm() > 1e-9) || !dir.allFinite()) return;

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
    } catch (const std::exception&) {
        // Auto-orient is best-effort: a mesh the orienter cannot score is
        // simply left as-is rather than failing the UI action.
    }
}
