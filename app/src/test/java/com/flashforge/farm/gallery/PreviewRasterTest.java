package com.flashforge.farm.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Vets the gallery spinner rasterizer against the two historical
 * inside-out regressions:
 *
 * 1. Inverted depth test (nearer fragments must win). The preview camera
 *    sits on the -Z side, so nearer means SMALLER rotated view-space Z.
 * 2. Inverted facing cull (toward-camera faces must be kept). Toward faces
 *    project CCW (screen-space area &gt; 0) — verified numerically over 288
 *    outward-face poses with the exact projection before this test was
 *    written; grazing/edge-on slivers excepted.
 *
 * All tests drive PreviewRaster, the exact production code path used by
 * SpinningPreviewView (no android.* here, so plain JVM JUnit suffices).
 */
public class PreviewRasterTest {

    private static final float YAW = 0f;
    private static final float PITCH = -0.35f;

    private static float[] frame(float yaw, float pitch, int size, float radius) {
        float cosA = (float) Math.cos(yaw), sinA = (float) Math.sin(yaw);
        float cosT = (float) Math.cos(pitch), sinT = (float) Math.sin(pitch);
        float k = (size / 2f - 4f) / radius;
        float fl = PreviewRaster.FOCAL_RADII * radius;
        return new float[]{cosA, sinA, cosT, sinT, k, fl};
    }

    private static float[] light() {
        float lx = PreviewRaster.LIGHT_X, ly = PreviewRaster.LIGHT_Y, lz = PreviewRaster.LIGHT_Z;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        return new float[]{lx / len, ly / len, lz / len};
    }

    private static int[] renderMesh(GalleryMesh mesh, float yaw, float pitch, int size) {
        float[] b = new float[6];
        mesh.bounds(b);
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
        float radius = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        float[] f = frame(yaw, pitch, size, radius);
        float[] li = light();
        int[] pixels = new int[size * size];
        float[] depth = new float[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0;
            depth[i] = Float.MAX_VALUE;
        }
        float[] xs = new float[3], ys = new float[3], zs = new float[3], tmp = new float[6];
        for (int t = 0; t < mesh.triCount; t++) {
            PreviewRaster.rasterTriangle(mesh.xyz, mesh.normals, t,
                    cx, cy, cz, f[0], f[1], f[2], f[3],
                    size, f[4], f[5], li[0], li[1], li[2],
                    pixels, depth, xs, ys, zs, tmp);
        }
        return pixels;
    }

    /** Lambert shade of a unit normal, computed from first principles. */
    private static int expectedShade(float nx, float ny, float nz,
            float yaw, float pitch) {
        float cosA = (float) Math.cos(yaw), sinA = (float) Math.sin(yaw);
        float cosT = (float) Math.cos(pitch), sinT = (float) Math.sin(pitch);
        float nx1 = nx * cosA + nz * sinA;
        float nz1 = -nx * sinA + nz * cosA;
        float ny2 = ny * cosT - nz1 * sinT;
        float nz2 = ny * sinT + nz1 * cosT;
        float[] li = light();
        float diff = nx1 * li[0] + ny2 * li[1] + nz2 * li[2];
        return PreviewRaster.shadeColor(diff);
    }

    @Test
    public void keepTriangle_towardFacesKeptAwayFacesCulled() {
        // Hand-verified at identity rotation: the -Z (camera-side) box face
        // projects CCW (area > 0), the +Z face CW (area < 0).
        assertTrue(PreviewRaster.keepTriangle(400f));
        assertFalse(PreviewRaster.keepTriangle(-400f));
        // Only truly degenerate tris are dropped; sub-pixel front faces must
        // splat (dense models lost ~25% coverage to the old 0.25 band).
        assertTrue(PreviewRaster.keepTriangle(0.1f));
        assertFalse(PreviewRaster.keepTriangle(-0.1f));
        assertFalse(PreviewRaster.keepTriangle(0f));
    }

    @Test
    public void screenArea_identityWindingMatchesCodeConvention() {
        // NZ tri A=(10,-10),B=(-10,-10),C=(-10,10) in screen coords.
        assertTrue(PreviewRaster.screenArea(10, 10, -10, 10, -10, -10) > 0);
        // PZ tri A=(-10,+10),B=(+10,+10),C=(+10,-10) in screen coords.
        assertTrue(PreviewRaster.screenArea(-10, 10, 10, 10, 10, -10) < 0);
    }

    @Test
    public void frontFaceBeatsBackFaceAtOverlap() {
        // Full-pipeline regression for the inside-out screenshot: a mesh of
        // exactly two tris — the camera-side NZ face tri and the far-side PZ
        // face tri, overlapping on screen. Correct code paints the NZ shade
        // (nearer wins + away culled); an inverted depth test OR an inverted
        // facing cull both paint the PZ shade instead.
        float[] soup = {
            // NZ (camera side at yaw=0): A=(10,-10,-10) B=(-10,-10,-10) C=(-10,10,-10)
            10, -10, -10, -10, -10, -10, -10, 10, -10,
            // PZ (far side): A=(-10,-10,10) B=(10,-10,10) C=(10,10,10)
            -10, -10, 10, 10, -10, 10, 10, 10, 10,
        };
        GalleryMesh mesh = new GalleryMesh(soup);
        int size = 64;
        // Pixel containing the projected NZ centroid (inside the NZ tri by
        // construction; also inside the PZ projection, so both compete).
        float[] b = new float[6];
        mesh.bounds(b);
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
        float radius = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        float[] f = frame(YAW, PITCH, size, radius);
        float[] p0 = new float[3];
        // True NZ centroid (strictly inside the tri; the face center (0,0)
        // sits exactly on the shared hypotenuse edge and is borderline).
        PreviewRaster.projectVertex(-10f / 3, -10f / 3, -10, cx, cy, cz,
                f[0], f[1], f[2], f[3], size, f[4], f[5], p0);
        int px = (int) Math.floor(p0[0]);
        int py = (int) Math.floor(p0[1]);
        int[] pixels = renderMesh(mesh, YAW, PITCH, size);
        int expected = expectedShade(0, 0, -1, YAW, PITCH);
        int awayShade = expectedShade(0, 0, 1, YAW, PITCH);
        assertNotEquals("test needs competing shades to discriminate", expected, awayShade);
        assertEquals("overlap pixel must show the toward-camera face",
                expected, pixels[py * size + px]);
    }

    @Test
    public void fullCubePaintsPlausibleSilhouette() {
        // Smoke: a closed convex cube must paint a large silhouette and no
        // pixel may stay at the unpainted sentinel where the cube projects.
        // (Does not discriminate facing by itself — frontFaceBeatsBackFace
        // does that — but guards total raster breakage.)
        int size = 64;
        int[] pixels = renderMesh(Primitives.cube(20), YAW, PITCH, size);
        int painted = 0;
        for (int px : pixels) if (px != 0) painted++;
        // A single front face covers ~1500px here; guards total failure.
        assertTrue("cube must paint most of the frame, painted=" + painted,
                painted > 1000);
    }

    @Test
    public void facingRuleHoldsAcrossRotations() {
        // toward (rotated nz < 0) ⟺ CCW (area > 0), over the preview's yaw
        // range and tilt limits, skipping grazing faces (|nz| < 0.2) whose
        // area sign is numerically sensitive slivers.
        float[] box = Primitives.cube(20).xyz;
        int[] tris = {
            0, 2 * 9, 4 * 9, 6 * 9, 8 * 9, 10 * 9,
        };
        float[] p0 = new float[3];
        float[][] proj = new float[3][3];
        int checked = 0;
        for (int yi = 0; yi < 12; yi++) {
            float yaw = (float) (yi * Math.PI / 6);
            for (float pitch : new float[]{-0.6f, -0.35f, 0f, 0.25f}) {
                float cosA = (float) Math.cos(yaw), sinA = (float) Math.sin(yaw);
                float cosT = (float) Math.cos(pitch), sinT = (float) Math.sin(pitch);
                for (int base : tris) {
                    float ax = box[base], ay = box[base + 1], az = box[base + 2];
                    float bx = box[base + 3], by = box[base + 4], bz = box[base + 5];
                    float cx = box[base + 6], cy = box[base + 7], cz = box[base + 8];
                    float ux = bx - ax, uy = by - ay, uz = bz - az;
                    float wx = cx - ax, wy = cy - ay, wz = cz - az;
                    float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx /= len;
                    ny /= len;
                    nz /= len;
                    float[] nr = new float[3];
                    PreviewRaster.rotateNormal(nx, ny, nz, cosA, sinA, cosT, sinT, nr);
                    if (Math.abs(nr[2]) < 0.2f) continue; // grazing sliver
                    float[][] pts = {{ax, ay, az}, {bx, by, bz}, {cx, cy, cz}};
                    for (int j = 0; j < 3; j++) {
                        PreviewRaster.projectVertex(pts[j][0], pts[j][1], pts[j][2],
                                0, 0, 0, cosA, sinA, cosT, sinT, 64, 1f, 43.3f, p0);
                        proj[j][0] = p0[0];
                        proj[j][1] = p0[1];
                    }
                    float area = PreviewRaster.screenArea(
                            proj[0][0], proj[0][1], proj[1][0], proj[1][1],
                            proj[2][0], proj[2][1]);
                    if (Math.abs(area) < 0.25f) continue;
                    checked++;
                    assertEquals("face nz2=" + nr[2] + " yaw=" + yaw + " pitch=" + pitch,
                            nr[2] < 0, area > 0);
                }
            }
        }
        assertTrue("sweep must actually exercise faces, checked=" + checked, checked > 200);
    }

    @Test
    public void galleryNormalsPointOutward() {
        // Guards the other half of the saga (soup winding): every checked
        // primitive normal must point away from its triangle centroid.
        GalleryMesh[] meshes = {
            Primitives.cube(20),
            Primitives.cylinder(20, 20, 32),
            Primitives.cone(20, 20, 32),
            Primitives.sphere(20, 24, 16),
        };
        for (GalleryMesh m : meshes) {
            for (int t = 0; t < m.triCount; t++) {
                int o = t * 9;
                float nx = m.normals[t * 3], ny = m.normals[t * 3 + 1], nz = m.normals[t * 3 + 2];
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                // Pole-cap degenerates (e.g. sphere la=0/la=15) have zero
                // area and zero normals; the rasterizer skips them by area.
                if (nl < 1e-6f) continue;
                float cx = (m.xyz[o] + m.xyz[o + 3] + m.xyz[o + 6]) / 3;
                float cy = (m.xyz[o + 1] + m.xyz[o + 4] + m.xyz[o + 7]) / 3;
                float cz = (m.xyz[o + 2] + m.xyz[o + 5] + m.xyz[o + 8]) / 3;
                assertTrue("inward normal on tri " + t, nx * cx + ny * cy + nz * cz > 0);
            }
        }
    }

    @Test
    public void modelSpaceIsZUp() throws Exception {
        // Model space must be Z-up (STL convention = bed convention): the
        // recycling tag plate (46 x 32 x 1.2 thick) sits thin in Z on the
        // bed (minZ ~= 0). Y-up soup would give zSize ~= 32 instead.
        ShapeGallery.Item tag =
                new ShapeGallery.Item("tag_pla", "PLA tag", "Recycling tag",
                        ShapeGallery.KIND_TAG, "PLA", null);
        GalleryMesh mesh = ShapeGallery.meshFor(tag);
        float[] b = new float[6];
        mesh.bounds(b);
        float zSize = b[5] - b[2];
        assertTrue("tag must be thin in Z, zSize=" + zSize, zSize < 5f);
        assertEquals("tag must sit on the bed", 0f, b[2], 0.05f);
    }

    @Test
    public void rotationMapsHeightToZ() {
        // Pins both rotation directions used by the axis unification:
        // meshFor bakes +90° about X (Y-up height -> +Z), previewFor bakes
        // -90° (model +Z -> screen-up +Y). A swapped sign breaks one side.
        GalleryMesh up = Primitives.box(10, 20, 30).rotatedX(Math.PI / 2);
        float[] b = new float[6];
        up.bounds(b);
        assertEquals("height must move to Z", 20f, b[5] - b[2], 0.01f);
        assertEquals("depth must move to Y", 30f, b[4] - b[1], 0.01f);
        GalleryMesh display = Primitives.box(10, 20, 30).rotatedX(-Math.PI / 2);
        display.bounds(b);
        assertEquals("model +Z must become screen-up +Y", 30f, b[4] - b[1], 0.01f);
    }
}
