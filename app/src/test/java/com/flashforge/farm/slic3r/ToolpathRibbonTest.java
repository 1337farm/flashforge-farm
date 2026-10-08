package com.flashforge.farm.slic3r;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ToolpathRibbonTest {

    @Test
    public void testSingleSegmentEmitsQuad() {
        float[] verts = {0, 0, 0.2f, 10, 0, 0.2f};
        ToolpathRibbon.Mesh m = ToolpathRibbon.build(verts, new int[]{0, 1}, 0.4f);
        assertEquals(4 * 3, m.xyz.length);
        assertEquals(4 * 2, m.uv.length);
        assertEquals(6, m.idx.length);
        // Width 0.4 along Y for an X-run: y in [-0.2, 0.2].
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            minY = Math.min(minY, m.xyz[i * 3 + 1]);
            maxY = Math.max(maxY, m.xyz[i * 3 + 1]);
        }
        assertEquals(-0.2f, minY, 1e-6f);
        assertEquals(0.2f, maxY, 1e-6f);
        // u spans 0..1 across the ribbon.
        float minU = 2, maxU = -1;
        for (int i = 0; i < 4; i++) {
            minU = Math.min(minU, m.uv[i * 2]);
            maxU = Math.max(maxU, m.uv[i * 2]);
        }
        assertEquals(0f, minU, 1e-6f);
        assertEquals(1f, maxU, 1e-6f);
    }

    @Test
    public void testDegenerateSegmentsSkipped() {
        float[] verts = {0, 0, 0.2f, 0, 0, 0.2f, 5, 5, 0.2f, 9, 5, 0.2f};
        ToolpathRibbon.Mesh m = ToolpathRibbon.build(verts, new int[]{0, 1, 2, 3}, 0.4f);
        // First pair is zero-length: only the second emits.
        assertEquals(4 * 3, m.xyz.length);
        assertEquals(6, m.idx.length);
    }

    @Test
    public void testOutOfRangeIndicesSkipped() {
        float[] verts = {0, 0, 0.2f, 10, 0, 0.2f};
        ToolpathRibbon.Mesh m = ToolpathRibbon.build(verts, new int[]{0, 5}, 0.4f);
        assertEquals(0, m.xyz.length);
        assertEquals(0, m.idx.length);
    }

    @Test
    public void testIndicesAreDenseIdentity() {
        float[] verts = {0, 0, 0, 3, 4, 0, 6, 8, 0, 9, 12, 0};
        ToolpathRibbon.Mesh m = ToolpathRibbon.build(verts, new int[]{0, 1, 2, 3}, 0.5f);
        // Two quads = 8 verts, 12 indices referencing exactly 0..7.
        assertEquals(8 * 3, m.xyz.length);
        assertEquals(12, m.idx.length);
        boolean[] seen = new boolean[8];
        int min = 99, max = -1;
        for (int id : m.idx) {
            assertTrue(id >= 0 && id < 8);
            seen[id] = true;
            min = Math.min(min, id);
            max = Math.max(max, id);
        }
        assertEquals(0, min);
        assertEquals(7, max);
        for (boolean s : seen) assertTrue(s);
    }
}
