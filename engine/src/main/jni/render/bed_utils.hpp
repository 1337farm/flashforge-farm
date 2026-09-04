#include "libslic3r/Geometry.hpp"
#include "libslic3r/ClipperUtils.hpp"
#include "GLModel.hpp"
#include "Tesselate.hpp"

#include <android/log.h>

#ifndef ORCA_SLICER3_RENDER_BED_UTILS_HPP
#define ORCA_SLICER3_RENDER_BED_UTILS_HPP

#define GROUND_Z -0.02f

using namespace Slic3r;
using namespace Slic3r::GUI;

void bed_util_init_gridlines(ExPolygon& contour, GLModel* glGridlines) {
    if (contour.empty())
        return;

    const BoundingBox& bed_bbox = contour.contour.bounding_box();
    const coord_t step = scale_(10.0);

    Polylines axes_lines;
    for (coord_t x = bed_bbox.min.x(); x <= bed_bbox.max.x(); x += step) {
        Polyline line;
        line.append(Point(x, bed_bbox.min.y()));
        line.append(Point(x, bed_bbox.max.y()));
        axes_lines.push_back(line);
    }
    for (coord_t y = bed_bbox.min.y(); y <= bed_bbox.max.y(); y += step) {
        Polyline line;
        line.append(Point(bed_bbox.min.x(), y));
        line.append(Point(bed_bbox.max.x(), y));
        axes_lines.push_back(line);
    }

    // clip with a slightly grown expolygon because our lines lay on the contours and may get erroneously clipped
    Lines gridlines = to_lines(intersection_pl(axes_lines, offset(contour, float(SCALED_EPSILON))));

    // append bed contours
    Lines contour_lines = to_lines(contour);
    std::copy(contour_lines.begin(), contour_lines.end(), std::back_inserter(gridlines));

    GLModel::Geometry init_data;
    init_data.format = { GLModel::Geometry::EPrimitiveType::Lines, GLModel::Geometry::EVertexLayout::P3 };
    init_data.reserve_vertices(2 * gridlines.size());
    init_data.reserve_indices(2 * gridlines.size());

    for (const Slic3r::Line& l : gridlines) {
        init_data.add_vertex(Vec3f(unscale<float>(l.a.x()), unscale<float>(l.a.y()), GROUND_Z));
        init_data.add_vertex(Vec3f(unscale<float>(l.b.x()), unscale<float>(l.b.y()), GROUND_Z));
        const unsigned int vertices_counter = (unsigned int)init_data.vertices_count();
        init_data.add_line(vertices_counter - 2, vertices_counter - 1);
    }

    glGridlines->init_from(std::move(init_data));
}

void bed_util_init_triangles_its(ExPolygon& contour, indexed_triangle_set* its) {
    if (contour.empty())
        return;

    auto triangles = triangulate_expolygon_3d(contour, 0);
    its->vertices.reserve(triangles.size());

    for (size_t i = 0; i < triangles.size(); i += 3) {
        its->vertices.emplace_back(triangles[i].cast<float>());
        its->vertices.emplace_back(triangles[i + 1].cast<float>());
        its->vertices.emplace_back(triangles[i + 2].cast<float>());

        its->indices.emplace_back(i, i + 1, i + 2);
    }
}

void bed_util_init_triangles(ExPolygon& contour, GLModel* glTriangles) {
    if (glTriangles->is_initialized())
        return;

    if (contour.empty())
        return;

    const std::vector<Vec2f> triangles = triangulate_expolygon_2f(contour, NORMALS_UP);
    if (triangles.empty() || triangles.size() % 3 != 0)
        return;

    GLModel::Geometry init_data;
    init_data.format = { GLModel::Geometry::EPrimitiveType::Triangles, GLModel::Geometry::EVertexLayout::P3T2 };
    init_data.reserve_vertices(triangles.size());
    init_data.reserve_indices(triangles.size() / 3);

    Vec2f min = triangles.front();
    Vec2f max = min;
    for (const Vec2f& v : triangles) {
        min = min.cwiseMin(v).eval();
        max = max.cwiseMax(v).eval();
    }

    const Vec2f size = max - min;
    if (size.x() <= 0.0f || size.y() <= 0.0f)
        return;

    Vec2f inv_size = size.cwiseInverse();
    inv_size.y() *= -1.0f;

    // vertices + indices
    unsigned int vertices_counter = 0;
    for (const Vec2f& v : triangles) {
        const Vec3f p = { v.x(), v.y(), GROUND_Z };
        init_data.add_vertex(p, (Vec2f)(v - min).cwiseProduct(inv_size).eval());
        ++vertices_counter;
        if (vertices_counter % 3 == 0)
            init_data.add_triangle(vertices_counter - 3, vertices_counter - 2, vertices_counter - 1);
    }

    glTriangles->init_from(std::move(init_data));
    glTriangles->set_color(Slic3r::ColorRGBA::DARK_GRAY());
}

void bed_util_init_contourlines(ExPolygon& contour, GLModel* glContourlines) {
    if (glContourlines->is_initialized())
        return;

    if (contour.empty())
        return;

    const Lines contour_lines = to_lines(contour);

    GLModel::Geometry init_data;
    init_data.format = { GLModel::Geometry::EPrimitiveType::Lines, GLModel::Geometry::EVertexLayout::P3 };
    init_data.reserve_vertices(2 * contour_lines.size());
    init_data.reserve_indices(2 * contour_lines.size());

    for (const Slic3r::Line& l : contour_lines) {
        init_data.add_vertex(Vec3f(unscale<float>(l.a.x()), unscale<float>(l.a.y()), GROUND_Z));
        init_data.add_vertex(Vec3f(unscale<float>(l.b.x()), unscale<float>(l.b.y()), GROUND_Z));
        const unsigned int vertices_counter = (unsigned int)init_data.vertices_count();
        init_data.add_line(vertices_counter - 2, vertices_counter - 1);
    }

    glContourlines->init_from(std::move(init_data));
    glContourlines->set_color({ 1.0f, 1.0f, 1.0f, 0.5f });
}

#endif //ORCA_SLICER3_RENDER_BED_UTILS_HPP
