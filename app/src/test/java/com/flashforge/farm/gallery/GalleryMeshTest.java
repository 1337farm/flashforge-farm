package com.flashforge.farm.gallery;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GalleryMeshTest {

    @Test
    public void testWriteReadRoundTrip() throws Exception {
        GalleryMesh mesh = new GalleryMesh(new float[]{
                0, 0, 0,
                10, 0, 0,
                0, 10, 0,
                10, 0, 0,
                10, 10, 0,
                0, 10, 0,
        });
        File tmp = File.createTempFile("gallery-mesh-test", ".bin");
        try {
            mesh.writeToFile(tmp);
            GalleryMesh read = GalleryMesh.readFromFile(tmp);
            assertEquals(mesh.triCount, read.triCount);
            for (int i = 0; i < mesh.xyz.length; i++) {
                assertEquals(mesh.xyz[i], read.xyz[i], 1e-5f);
                assertEquals(mesh.normals[i], read.normals[i], 1e-5f);
            }
        } finally {
            tmp.delete();
        }
    }

    @Test
    public void testReadRejectsInsaneTriangleCount() throws Exception {
        File tmp = File.createTempFile("gallery-mesh-test", ".bad");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            out.write(new byte[]{0x00, 0x00, 0x00, 0x7F});
            out.close();
            boolean threw = false;
            try {
                GalleryMesh.readFromFile(tmp);
            } catch (java.io.IOException e) {
                threw = true;
            }
            assertTrue(threw);
        } finally {
            tmp.delete();
        }
    }

    private static double meshArea(GalleryMesh m) {
        double area = 0;
        for (int t = 0; t < m.triCount; t++) {
            int o = t * 9;
            float ux = m.xyz[o + 3] - m.xyz[o], uy = m.xyz[o + 4] - m.xyz[o + 1], uz = m.xyz[o + 5] - m.xyz[o + 2];
            float wx = m.xyz[o + 6] - m.xyz[o], wy = m.xyz[o + 7] - m.xyz[o + 1], wz = m.xyz[o + 8] - m.xyz[o + 2];
            float cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
            area += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return area;
    }

    @Test
    public void testDecimatedSmallMeshReturnsSameInstance() {
        GalleryMesh cube = Primitives.cube(20);
        assertTrue(cube.decimated(cube.triCount + 100) == cube);
    }

    @Test
    public void testDecimatedPreservesCoverage() {
        // Dense closed sphere: clustering must shrink the triangle count
        // while keeping ~the whole surface (stride-subsampling deleted
        // (N-1)/N of it and rendered holey shells).
        GalleryMesh dense = Primitives.sphere(20, 120, 80);
        assertTrue(dense.triCount > 3000);
        double before = meshArea(dense);
        GalleryMesh small = dense.decimated(3000);
        assertTrue(small.triCount < dense.triCount);
        assertTrue(small.triCount > 0);
        double ratio = meshArea(small) / before;
        assertTrue("coverage lost: " + ratio, ratio > 0.8 && ratio < 1.3);
        // No degenerate output triangles.
        for (int t = 0; t < small.triCount; t++) {
            int o = t * 9;
            float ux = small.xyz[o + 3] - small.xyz[o], uy = small.xyz[o + 4] - small.xyz[o + 1];
            float wx = small.xyz[o + 6] - small.xyz[o], wy = small.xyz[o + 7] - small.xyz[o + 1];
            assertTrue(Math.abs(ux * wy - uy * wx) > 0
                    || Math.abs(small.xyz[o + 5] - small.xyz[o + 2]) > 0
                    || Math.abs(small.xyz[o + 8] - small.xyz[o + 2]) > 0);
        }
    }

    @Test
    public void testRotatedXCarriesNormalsPerTri() {
        // Regression guard: normals are packed one-per-tri at stride 3 while
        // xyz runs at stride 9 — rotation must map tri t -> normal t, not
        // vertex index -> normal slot.
        GalleryMesh sphere = Primitives.sphere(20, 24, 16);
        GalleryMesh rot = sphere.rotatedX(Math.PI / 2);
        float[] expect = GalleryMesh.computeNormals(rot.xyz);
        for (int t = 0; t < rot.triCount; t++) {
            assertEquals(expect[t * 3], rot.normals[t * 3], 1e-5f);
            assertEquals(expect[t * 3 + 1], rot.normals[t * 3 + 1], 1e-5f);
            assertEquals(expect[t * 3 + 2], rot.normals[t * 3 + 2], 1e-5f);
        }
        GalleryMesh rotY = sphere.rotatedY(Math.PI / 2);
        float[] expectY = GalleryMesh.computeNormals(rotY.xyz);
        for (int t = 0; t < rotY.triCount; t++) {
            assertEquals(expectY[t * 3], rotY.normals[t * 3], 1e-5f);
            assertEquals(expectY[t * 3 + 1], rotY.normals[t * 3 + 1], 1e-5f);
            assertEquals(expectY[t * 3 + 2], rotY.normals[t * 3 + 2], 1e-5f);
        }
    }

    @Test
    public void testDecimatedLandsNearBudget() {
        // Targeting: the weld must land near the budget, not far below it.
        // (Accepting the first fitting pass settled ~60% under budget and
        // previews read as "low poly".)
        GalleryMesh dense = Primitives.sphere(20, 120, 80);
        assertTrue(dense.triCount > 15000);
        GalleryMesh d8 = dense.decimated(8000);
        assertTrue("over budget: " + d8.triCount, d8.triCount <= 8000);
        assertTrue("too coarse: " + d8.triCount, d8.triCount >= 8000 * 0.8);
        GalleryMesh d4 = dense.decimated(4000);
        assertTrue("over budget: " + d4.triCount, d4.triCount <= 4000);
        assertTrue("too coarse: " + d4.triCount, d4.triCount >= 4000 * 0.8);
    }

    @Test
    public void testDecimatedPreviewBudgetOnDenseMesh() {
        // Dragon-scale mesh at the real gallery preview budget.
        GalleryMesh dense = Primitives.sphere(20, 360, 240);
        assertTrue(dense.triCount > 150000);
        GalleryMesh d = dense.decimated(30000);
        assertTrue("over budget: " + d.triCount, d.triCount <= 30000);
        assertTrue("preview too coarse: " + d.triCount, d.triCount >= 30000 * 0.7);
    }

    @Test
    public void testDecimatedKeepsSpatialExtent() {
        // Welded vertices are a subset of the input, so the preview cannot
        // wander; it also must not chop the model down.
        GalleryMesh dense = Primitives.sphere(20, 120, 80);
        GalleryMesh d = dense.decimated(8000);
        float[] inB = new float[6];
        dense.bounds(inB);
        float[] outB = new float[6];
        d.bounds(outB);
        for (int a = 0; a < 3; a++) {
            float extent = inB[a + 3] - inB[a];
            assertTrue(extent > 0);
            assertTrue("axis " + a + " chopped",
                    (outB[a + 3] - outB[a]) >= extent * 0.9f);
            assertTrue(outB[a] >= inB[a] - 1e-3f && outB[a + 3] <= inB[a + 3] + 1e-3f);
        }
    }

    @Test
    public void testDecimatedTotalCollapseKeepsFullMesh() {
        // Every triangle inside one weld cell: all collapse, so the full
        // mesh comes back — never an empty mesh.
        float[] xyz = new float[60 * 9];
        for (int t = 0; t < 60; t++) {
            int o = t * 9;
            xyz[o] = 0;
            xyz[o + 1] = 0;
            xyz[o + 2] = 0;
            xyz[o + 3] = 1e-7f;
            xyz[o + 4] = 0;
            xyz[o + 5] = 0;
            xyz[o + 6] = 0;
            xyz[o + 7] = 1e-7f;
            xyz[o + 8] = 0;
        }
        GalleryMesh m = new GalleryMesh(xyz);
        assertSame(m, m.decimated(10));
    }

    @Test
    public void testDecimatedNonPositiveBudgetReturnsSame() {
        GalleryMesh cube = Primitives.cube(20);
        assertSame(cube, cube.decimated(0));
        assertSame(cube, cube.decimated(-3));
    }

    @Test
    public void testDecimatedInventsNoGeometry() {
        // First-seen vertex wins: every output position is bit-identical to
        // an input position (no invented/shrunk surface).
        GalleryMesh dense = Primitives.sphere(20, 48, 32);
        GalleryMesh d = dense.decimated(2000);
        java.util.HashSet<String> src = new java.util.HashSet<>();
        for (int i = 0; i < dense.xyz.length; i += 3) {
            src.add(dense.xyz[i] + "," + dense.xyz[i + 1] + "," + dense.xyz[i + 2]);
        }
        for (int i = 0; i < d.xyz.length; i += 3) {
            assertTrue("invented vertex",
                    src.contains(d.xyz[i] + "," + d.xyz[i + 1] + "," + d.xyz[i + 2]));
        }
    }

    @Test
    public void testDecimatedNormalsAreUnit() {
        // Degenerate triangles are dropped, so every kept face has a real
        // unit normal (no black/flat-shaded patches).
        GalleryMesh dense = Primitives.sphere(20, 120, 80);
        GalleryMesh d = dense.decimated(8000);
        for (int t = 0; t < d.triCount; t++) {
            float nx = d.normals[t * 3], ny = d.normals[t * 3 + 1], nz = d.normals[t * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertTrue("bad normal on tri " + t + ": " + len, Math.abs(len - 1) < 1e-4f);
        }
    }
}