package com.flashforge.farm.slic3r;

/**
 * Builds camera-independent ribbon triangles from extrusion index pairs.
 *
 * <p>Each consecutive index pair is one genuine extrusion segment (travels
 * add no vertices, so pairs never span travel gaps). Every pair becomes one
 * axis-aligned quad in the layer plane; same-color overlaps at joints are
 * invisible, so no miter joins are needed. Degenerate (zero-length) pairs
 * are skipped.
 */
public final class ToolpathRibbon {
    private ToolpathRibbon() {
    }

    public static final class Mesh {
        public final float[] xyz;
        public final float[] uv;
        public final int[] idx;

        Mesh(float[] xyz, float[] uv, int[] idx) {
            this.xyz = xyz;
            this.uv = uv;
            this.idx = idx;
        }
    }

    /**
     * @param verts packed xyz triplets
     * @param pairs flat index list; consecutive entries form segments
     * @param width ribbon width in mm (extrusion width)
     */
    public static Mesh build(float[] verts, int[] pairs, float width) {
        float hw = Math.max(1e-6f, width / 2);
        java.util.ArrayList<Float> xyz = new java.util.ArrayList<>();
        java.util.ArrayList<Float> uv = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> idx = new java.util.ArrayList<>();
        int n = 0;
        for (int k = 0; k + 1 < pairs.length; k += 2) {
            int a = pairs[k], b = pairs[k + 1];
            if (a < 0 || b < 0) continue;
            int ao = a * 3, bo = b * 3;
            if (ao + 2 >= verts.length || bo + 2 >= verts.length) continue;
            float dx = verts[bo] - verts[ao];
            float dy = verts[bo + 1] - verts[ao + 1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (!(len > 1e-9f)) continue;
            float nx = -dy / len * hw;
            float ny = dx / len * hw;
            float az = verts[ao + 2], bz = verts[bo + 2];
            // a0/a1/b0/b1, u=0 at -n side, u=1 at +n side.
            xyz.add(verts[ao] - nx);
            xyz.add(verts[ao + 1] - ny);
            xyz.add(az);
            uv.add(0f);
            uv.add(0f);
            xyz.add(verts[ao] + nx);
            xyz.add(verts[ao + 1] + ny);
            xyz.add(az);
            uv.add(1f);
            uv.add(0f);
            xyz.add(verts[bo] - nx);
            xyz.add(verts[bo + 1] - ny);
            xyz.add(bz);
            uv.add(0f);
            uv.add(1f);
            xyz.add(verts[bo] + nx);
            xyz.add(verts[bo + 1] + ny);
            xyz.add(bz);
            uv.add(1f);
            uv.add(1f);
            idx.add(n);
            idx.add(n + 1);
            idx.add(n + 3);
            idx.add(n);
            idx.add(n + 3);
            idx.add(n + 2);
            n += 4;
        }
        float[] xa = new float[xyz.size()];
        float[] ua = new float[uv.size()];
        int[] ia = new int[idx.size()];
        for (int i = 0; i < xa.length; i++) xa[i] = xyz.get(i);
        for (int i = 0; i < ua.length; i++) ua[i] = uv.get(i);
        for (int i = 0; i < ia.length; i++) ia[i] = idx.get(i);
        return new Mesh(xa, ua, ia);
    }
}
