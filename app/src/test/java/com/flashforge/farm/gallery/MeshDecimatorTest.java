package com.flashforge.farm.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MeshDecimatorTest {
    /** Dense grid of small quads (2 tris each) plus one thin spike. */
    private static GalleryMesh denseMesh() {
        int n = 40;
        float[] xyz = new float[n * n * 2 * 9];
        int o = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float x0 = i, y0 = j, x1 = i + 1, y1 = j + 1;
                float[] q = {x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y0, 0, x1, y1, 0, x0, y1, 0};
                for (float v : q) xyz[o++] = v;
            }
        }
        // Thin spike far above one cell: coarse clustering eats it entirely.
        float[] with = new float[xyz.length + 9];
        System.arraycopy(xyz, 0, with, 0, xyz.length);
        float[] spike = {5f, 5f, 0f, 5.2f, 5f, 0f, 5.1f, 5f, 30f};
        System.arraycopy(spike, 0, with, xyz.length, 9);
        return new GalleryMesh(with);
    }

    @Test
    public void denseMeshStaysDetailedAndBounded() {
        GalleryMesh mesh = denseMesh();
        assertTrue(mesh.triCount > 1200);
        GalleryMesh out = MeshDecimator.decimate(mesh, 1200);
        assertTrue(out.triCount > 0);
        assertTrue("budget respected, got " + out.triCount, out.triCount <= 1200);
        float[] before = new float[6];
        float[] after = new float[6];
        mesh.bounds(before);
        out.bounds(after);
        // Footprint preserved (no wholesale collapse into a blob).
        assertEquals(before[0], after[0], 2.0f);
        assertEquals(before[3], after[3], 2.0f);
        assertEquals(before[1], after[1], 2.0f);
        assertEquals(before[4], after[4], 2.0f);
    }

    @Test
    public void underBudgetReturnsSameMesh() {
        GalleryMesh mesh = denseMesh();
        GalleryMesh small = MeshDecimator.decimate(new GalleryMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}), 1200);
        assertEquals(1, small.triCount);
    }
}
