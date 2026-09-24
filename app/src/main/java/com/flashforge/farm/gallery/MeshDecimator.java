package com.flashforge.farm.gallery;

import java.util.ArrayList;
import java.util.HashMap;

public final class MeshDecimator {
    private MeshDecimator() {
    }

    public static GalleryMesh decimate(GalleryMesh mesh, int maxTris) {
        if (mesh.triCount <= maxTris || maxTris <= 0) return mesh;
        // Progressive from fine: cluster the ORIGINAL at a fine grid first
        // and coarsen only while still over budget, so the first result that
        // fits keeps maximum detail (thin walls, text, rails). Coarse-first
        // merged whole features into mush with holes.
        int grid = 128;
        GalleryMesh out = mesh;
        for (int pass = 0; pass < 6 && out.triCount > maxTris; pass++) {
            GalleryMesh clustered = cluster(mesh, grid);
            if (clustered == mesh) break; // total collapse guard: keep input
            out = clustered;
            grid = Math.max(4, grid / 2);
        }
        return out;
    }

    private static GalleryMesh cluster(GalleryMesh mesh, int grid) {
        float[] b = new float[6];
        mesh.bounds(b);
        float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
        float span = Math.max(dx, Math.max(dy, dz));
        if (span <= 0) return mesh;
        float cell = span / grid;
        float[] v = mesh.xyz;
        int verts = mesh.triCount * 3;
        HashMap<Long, Integer> cellToRep = new HashMap<Long, Integer>(verts / 2 + 16);
        ArrayList<Float> sums = new ArrayList<Float>(verts);
        int[] remap = new int[verts];
        for (int i = 0; i < verts; i++) {
            int ix = (int) ((v[i * 3] - b[0]) / cell);
            int iy = (int) ((v[i * 3 + 1] - b[1]) / cell);
            int iz = (int) ((v[i * 3 + 2] - b[2]) / cell);
            if (ix >= grid) ix = grid - 1;
            if (iy >= grid) iy = grid - 1;
            if (iz >= grid) iz = grid - 1;
            if (ix < 0) ix = 0;
            if (iy < 0) iy = 0;
            if (iz < 0) iz = 0;
            long key = (((long) ix) << 40) | (((long) iy) << 20) | ((long) iz);
            Integer rep = cellToRep.get(key);
            if (rep == null) {
                rep = sums.size() / 4;
                cellToRep.put(key, rep);
                sums.add(v[i * 3]);
                sums.add(v[i * 3 + 1]);
                sums.add(v[i * 3 + 2]);
                sums.add(1f);
            } else {
                int o = rep * 4;
                sums.set(o, sums.get(o) + v[i * 3]);
                sums.set(o + 1, sums.get(o + 1) + v[i * 3 + 1]);
                sums.set(o + 2, sums.get(o + 2) + v[i * 3 + 2]);
                sums.set(o + 3, sums.get(o + 3) + 1f);
            }
            remap[i] = rep;
        }
        int cells = sums.size() / 4;
        float[] avg = new float[cells * 3];
        for (int c = 0; c < cells; c++) {
            int o = c * 4;
            float n = sums.get(o + 3);
            avg[c * 3] = sums.get(o) / n;
            avg[c * 3 + 1] = sums.get(o + 1) / n;
            avg[c * 3 + 2] = sums.get(o + 2) / n;
        }
        float[] out = new float[mesh.xyz.length];
        int o = 0;
        for (int t = 0; t < mesh.triCount; t++) {
            int a = remap[t * 3], c = remap[t * 3 + 1], d = remap[t * 3 + 2];
            if (a == c || c == d || a == d) continue;
            out[o++] = avg[a * 3];
            out[o++] = avg[a * 3 + 1];
            out[o++] = avg[a * 3 + 2];
            out[o++] = avg[c * 3];
            out[o++] = avg[c * 3 + 1];
            out[o++] = avg[c * 3 + 2];
            out[o++] = avg[d * 3];
            out[o++] = avg[d * 3 + 1];
            out[o++] = avg[d * 3 + 2];
        }
        if (o == 0) return mesh;
        float[] trim = new float[o];
        System.arraycopy(out, 0, trim, 0, o);
        return new GalleryMesh(trim);
    }
}
