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
        GalleryMesh m = quad(100);
        GalleryMesh d = m.decimated(30);
        assertTrue(d.triCount <= 30);
        assertTrue(d.triCount > 0);
        // Order preserved: first kept tri starts at x=0.
        assertEquals(0f, d.xyz[0], 1e-6f);
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
        // Face normal of first tri (+Z) must survive at slot 0.
        assertEquals(0f, d.normals[0], 1e-6f);
        assertEquals(0f, d.normals[1], 1e-6f);
        assertEquals(1f, d.normals[2], 1e-6f);
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
