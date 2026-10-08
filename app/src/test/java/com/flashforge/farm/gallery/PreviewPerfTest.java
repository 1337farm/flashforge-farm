package com.flashforge.farm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PreviewPerfTest {

    private static GalleryMesh quad(int n) {
        float[] xyz = new float[n * 9];
        for (int t = 0; t < n; t++) {
            int o = t * 9;
            xyz[o] = t;
            xyz[o + 1] = 0;
            xyz[o + 2] = 0;
            xyz[o + 3] = t + 1;
            xyz[o + 4] = 0;
            xyz[o + 5] = 0;
            xyz[o + 6] = t;
            xyz[o + 7] = 1;
            xyz[o + 8] = 0;
        }
        return new GalleryMesh(xyz);
    }

    @Test
    public void testDecimatedCapsTriangles() {
        // Dense weldable soup: clustering lands under budget with coverage.
        GalleryMesh m = Primitives.sphere(20, 60, 40);
        assertTrue(m.triCount > 1000);
        GalleryMesh d = m.decimated(1000);
        assertTrue("kept " + d.triCount, d.triCount <= 1000);
        assertTrue(d.triCount > 0);
    }

    @Test
    public void testDecimatedThinStripKeepsFullMesh() {
        // quad() is a 1-unit-tall strip: any weld cell that fits the budget
        // of 30 collapses it entirely. Full mesh (not a holey subset) is the
        // correct fallback — coverage is the invariant, budget the target.
        GalleryMesh m = quad(100);
        assertSame(m, m.decimated(30));
    }

    @Test
    public void testDecimatedSmallMeshReturnsThis() {
        GalleryMesh m = quad(10);
        assertSame(m, m.decimated(30));
    }

    @Test
    public void testDecimatedNormalsFollowFaces() {
        GalleryMesh m = quad(100);
        GalleryMesh d = m.decimated(30);
        // Normals are recomputed from kept faces: every tri is +Z here.
        for (int t = 0; t < d.triCount; t++) {
            assertEquals(0f, d.normals[t * 3], 1e-6f);
            assertEquals(0f, d.normals[t * 3 + 1], 1e-6f);
            assertEquals(1f, d.normals[t * 3 + 2], 1e-6f);
        }
    }

    @Test
    public void testBoundsCachedAndStable() {
        GalleryMesh m = quad(10);
        float[] a = new float[6];
        float[] b = new float[6];
        m.bounds(a);
        m.bounds(b);
        for (int i = 0; i < 6; i++) assertEquals(a[i], b[i], 0f);
        assertEquals(0f, a[0], 0f);
    }

    @Test
    public void testConcatPreservesLaterPartNormals() {
        // Regression: concat copied normals at the xyz stride (9) while
        // they are packed at stride 3, zeroing every part after the first
        // (tag glyphs rendered black).
        GalleryMesh a = Primitives.cube(20);
        GalleryMesh b = Primitives.cube(20).translated(100, 0, 0);
        GalleryMesh c = GalleryMesh.concat(a, b);
        assertEquals(24, c.triCount);
        // Second box tri0 (+X face) must carry (1,0,0), not zeros.
        assertEquals(1f, c.normals[12 * 3], 1e-6f);
        assertEquals(0f, c.normals[12 * 3 + 1], 1e-6f);
        assertEquals(0f, c.normals[12 * 3 + 2], 1e-6f);
        // And its +Y top must be up.
        assertEquals(1f, c.normals[16 * 3 + 1], 1e-6f);
    }

    @Test
    public void testInwardMeshFlippedOutward() {
        // Mirror bit-for-bit like an inward-wound STL: swap v1/v2 per tri.
        GalleryMesh cube = Primitives.cube(20);
        float[] out = cube.xyz.clone();
        for (int t = 0; t < cube.triCount; t++) {
            int o = t * 9;
            for (int k = 0; k < 3; k++) {
                float tmp = out[o + 3 + k];
                out[o + 3 + k] = out[o + 6 + k];
                out[o + 6 + k] = tmp;
            }
        }
        GalleryMesh fixed = new GalleryMesh(out).withOutwardWinding();
        assertEquals(cube.triCount, fixed.triCount);
        for (int t = 0; t < fixed.triCount; t++) {
            int o = t * 9;
            float nx = fixed.normals[t * 3], ny = fixed.normals[t * 3 + 1], nz = fixed.normals[t * 3 + 2];
            float cx = (fixed.xyz[o] + fixed.xyz[o + 3] + fixed.xyz[o + 6]) / 3;
            float cy = (fixed.xyz[o + 1] + fixed.xyz[o + 4] + fixed.xyz[o + 7]) / 3;
            float cz = (fixed.xyz[o + 2] + fixed.xyz[o + 5] + fixed.xyz[o + 8]) / 3;
            assertTrue("still inward on tri " + t, nx * cx + ny * cy + nz * cz > 0);
        }
    }

    @Test
    public void testOutwardMeshUnchanged() {
        GalleryMesh cube = Primitives.cube(20);
        assertSame(cube, cube.withOutwardWinding());
    }

    @Test
    public void testMemoryCacheEvictsOldest() {
        PreviewMemoryCache cache = new PreviewMemoryCache(2);
        GalleryMesh a = quad(1), b = quad(1), c = quad(1);
        cache.put("a", a);
        cache.put("b", b);
        assertSame(a, cache.get("a"));
        cache.put("c", c);
        // 'a' was recently used; 'b' is now eldest.
        assertSame(a, cache.get("a"));
        assertNull(cache.get("b"));
        assertSame(c, cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    public void testMemoryCacheIgnoresNulls() {
        PreviewMemoryCache cache = new PreviewMemoryCache(2);
        cache.put(null, quad(1));
        cache.put("x", null);
        assertEquals(0, cache.size());
        cache.invalidate("missing");
    }
}
