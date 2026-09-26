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

        // Same application as upstream orient(ModelObject*): turn that
        // direction onto -Z and re-seat the object on the bed.
        const Eigen::Quaterniond rot =
            Eigen::Quaterniond::FromTwoVectors(dir.normalized(), -Slic3r::Domain::Vec3d::UnitZ());
        for (Slic3r::Domain::ModelVolume* vol : obj->volumes) {
            if (vol == nullptr) continue;
            const Slic3r::Domain::Vec3d cur = vol->get_rotation();
            const Eigen::Quaterniond q_cur =
                Eigen::AngleAxisd(cur[2], Eigen::Vector3d::UnitZ()) *
                Eigen::AngleAxisd(cur[1], Eigen::Vector3d::UnitY()) *
                Eigen::AngleAxisd(cur[0], Eigen::Vector3d::UnitX());
            const Eigen::Vector3d e = (rot * q_cur).toRotationMatrix().eulerAngles(2, 1, 0);
            vol->set_rotation(Slic3r::Domain::Vec3d(e[2], e[1], e[0]));
        }
        obj->invalidate_bounding_box();
    } catch (const std::exception&) {
        // Auto-orient is best-effort: a mesh the orienter cannot score is
        // simply left as-is rather than failing the UI action.
    }
}
