package com.flashforge.farm.gallery;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
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
}