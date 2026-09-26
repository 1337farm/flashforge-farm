#ifndef PRUSA_UTILS_HPP
#define PRUSA_UTILS_HPP

#include "libslic3r/Point.hpp"
#include <Eigen/Geometry>
#include <cmath>

// EnumFaceTypes / FaceProperty: 3.0 dropped them from the tree (the orienter's
// face-property bookkeeping needs them), so the vendored 2.x definitions are
// restored here. They are plain PODs over the mesh's own facet list.
enum EnumFaceTypes { eExterior, eInterior, eExteriorAppearance, eInteriorSolid, eUnknown };

namespace Slic3r {

    struct FaceProperty {
        EnumFaceTypes type = eUnknown;
    };

    // 3.0 removed these two 2.x Geometry helpers (no rotation_from_two_vectors
    // anywhere in the 3.0 tree), so the vendored AutoOrienter's calls are
    // satisfied by these inline shims.
    // Rotation taking `v_from` onto `v_to` as axis/angle (+ optional matrix).
    // Parallel vectors give a zero rotation, anti-parallel a 180deg turn about
    // any perpendicular axis.
    inline void rotation_from_two_vectors(const Vec3d &v_from, const Vec3d &v_to, Vec3d &axis,
                                          double &angle, Matrix3d *rotation = nullptr) {
        Vec3d from = v_from.normalized(), to = v_to.normalized();
        double dot = from.dot(to);
        if (dot > 1.0 - 1e-12) {            // already aligned
            axis = Vec3d(0, 0, 1);
            angle = 0;
        } else if (dot < -1.0 + 1e-12) {    // anti-parallel: any perpendicular axis
            axis = Vec3d(0, 0, 1).cross(from);
            if (axis.norm() < 1e-12) axis = Vec3d(0, 1, 0).cross(from);
            axis.normalize();
            angle = M_PI;
        } else {
            axis = to.cross(from);
            axis.normalize();
            angle = std::acos(dot);
        }
        if (rotation)
            *rotation = Eigen::AngleAxisd(angle, axis).toRotationMatrix();
    }

    // Euler angles (ZYX order) of a rotation matrix, Z up.
    inline Vec3d extract_euler_angles(const Matrix3d &rotation) {
        Eigen::Matrix3d r = rotation.transpose();
        double sy = std::sqrt(r(0, 0) * r(0, 0) + r(1, 0) * r(1, 0));
        double x, y, z;
        if (sy > 1e-6) {   // gimbal lock-safe general case
            x = std::atan2(r(2, 1), r(2, 2));
            y = std::atan2(-r(2, 0), sy);
            z = std::atan2(r(1, 0), r(0, 0));
        } else {           // y = +-90deg: fold x into z
            x = std::atan2(-r(1, 2), r(1, 1));
            y = std::atan2(-r(2, 0), sy);
            z = 0.0;
        }
        return Vec3d(x, y, z);
    }

    double area_of_boundingbox(BoundingBoxf3 bb) {
        return double(bb.max(0) - bb.min(0)) * (bb.max(1) - bb.min(1));
    }

    stl_vertex get_its_vertex(indexed_triangle_set& its, int facet_idx, int vertex_idx) {
        return its.vertices[its.indices[facet_idx][vertex_idx]];
    }

    float get_its_facet_area(indexed_triangle_set& its, int facet_idx) {
        return std::abs((get_its_vertex(its, facet_idx, 0) - get_its_vertex(its, facet_idx, 1))
                                .cross(get_its_vertex(its, facet_idx, 0) - get_its_vertex(its, facet_idx, 2)).norm()) / 2;
    }
}

#endif //PRUSA_UTILS_HPP
