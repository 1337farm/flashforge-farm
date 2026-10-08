package com.flashforge.farm.gallery;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class GalleryMesh {
    public final float[] xyz;
    public final int triCount;
    public final float[] normals;

    /** Lazily computed bounds; filled once (usually off the UI thread). */
    private volatile float[] cachedBounds;

    public GalleryMesh(float[] xyz) {
        this(xyz, computeNormals(xyz));
    }

    public GalleryMesh(float[] xyz, float[] normals) {
        this.xyz = xyz;
        this.triCount = xyz.length / 9;
        this.normals = normals;
    }

    public static float[] computeNormals(float[] xyz) {
        float[] normals = new float[xyz.length];
        for (int t = 0; t < xyz.length / 9; t++) {
            int o = t * 9;
            float ux = xyz[o + 3] - xyz[o], uy = xyz[o + 4] - xyz[o + 1], uz = xyz[o + 5] - xyz[o + 2];
            float wx = xyz[o + 6] - xyz[o], wy = xyz[o + 7] - xyz[o + 1], wz = xyz[o + 8] - xyz[o + 2];
            float nx = uy * wz - uz * wy;
            float ny = uz * wx - ux * wz;
            float nz = ux * wy - uy * wx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12f) {
                nx = 0;
                ny = 0;
                nz = 0;
            } else {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            normals[t * 3] = nx;
            normals[t * 3 + 1] = ny;
            normals[t * 3 + 2] = nz;
        }
        return normals;
    }

    public void bounds(float[] out) {
        float[] cached = cachedBounds;
        if (cached != null) {
            System.arraycopy(cached, 0, out, 0, 6);
            return;
        }
        computeBoundsInto(xyz, out);
        cachedBounds = new float[]{out[0], out[1], out[2], out[3], out[4], out[5]};
    }

    private static void computeBoundsInto(float[] xyz, float[] out) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < xyz.length; i += 3) {
            float x = xyz[i], y = xyz[i + 1], z = xyz[i + 2];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        out[0] = minX;
        out[1] = minY;
        out[2] = minZ;
        out[3] = maxX;
        out[4] = maxY;
        out[5] = maxZ;
    }

    /**
     * Coverage-preserving decimation capped at maxTris triangles via vertex
     * clustering: vertices falling into the same grid cell are welded and
     * sub-cell (degenerate) triangles dropped. Unlike stride-subsampling
     * (removed: keeping every Nth triangle deleted (N-1)/N of the surface and
     * rendered million-triangle models as see-through holey shells), this
     * never punches holes — kept triangles still tile the whole surface,
     * just coarser where cells merged. Returns {@code this} when already
     * small enough.
     */
    public GalleryMesh decimated(int maxTris) {
        if (triCount <= maxTris || maxTris <= 0) return this;
        // Total area in one pass: cells covering the surface ~ area/cell^2;
        // aim for ~maxTris/2 cells so output lands near the budget.
        double area = 0;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        for (int t = 0; t < triCount; t++) {
            int o = t * 9;
            float ax = xyz[o], ay = xyz[o + 1], az = xyz[o + 2];
            float ux = xyz[o + 3] - ax, uy = xyz[o + 4] - ay, uz = xyz[o + 5] - az;
            float wx = xyz[o + 6] - ax, wy = xyz[o + 7] - ay, wz = xyz[o + 8] - az;
            float cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
            area += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
            for (int k = 0; k < 3; k++) {
                float x = xyz[o + k * 3], y = xyz[o + k * 3 + 1], z = xyz[o + k * 3 + 2];
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
            }
        }
        if (!(area > 0)) return this;
        double cell = Math.sqrt(2.0 * area / maxTris);
        if (!(cell > 0)) return this;
        // Weld passes with growing cells until the output fits the budget
        // (or stops shrinking: disjoint coarse soup cannot merge further).
        // Coverage is the invariant; the budget is a target.
        java.util.ArrayList<Integer> kept = new java.util.ArrayList<>(maxTris * 3);
        java.util.ArrayList<Float> verts = new java.util.ArrayList<>();
        int lastKept = Integer.MAX_VALUE;
        for (int pass = 0; pass < 8; pass++) {
            kept.clear();
            verts.clear();
            weld(cell, minX, minY, minZ, verts, kept);
            int n = kept.size() / 3;
            if (n <= maxTris || n >= lastKept) break;
            lastKept = n;
            cell *= 1.5;
        }
        if (kept.isEmpty()) return this; // pathological: keep full mesh
        float[] outXyz = new float[kept.size() * 3];
        for (int i = 0; i < kept.size(); i++) {
            int v = kept.get(i);
            outXyz[i * 3] = verts.get(v * 3);
            outXyz[i * 3 + 1] = verts.get(v * 3 + 1);
            outXyz[i * 3 + 2] = verts.get(v * 3 + 2);
        }
        return new GalleryMesh(outXyz);
    }

    /** One weld pass at the given cell size: fills verts + kept index triplets. */
    private void weld(double cell, float minX, float minY, float minZ,
                      java.util.ArrayList<Float> verts, java.util.ArrayList<Integer> kept) {
        // Weld: first-seen vertex per cell wins (no invented positions, no
        // shrinkage). Keys pack 21 bits per axis; clamped defensively.
        java.util.HashMap<Long, Integer> cellToVert =
                new java.util.HashMap<>(Math.min(triCount * 3, 1 << 16));
        int[] remap = new int[triCount * 3];
        for (int i = 0; i < triCount * 3; i++) {
            long ix = clamp21((long) Math.floor((xyz[i * 3] - minX) / cell));
            long iy = clamp21((long) Math.floor((xyz[i * 3 + 1] - minY) / cell));
            long iz = clamp21((long) Math.floor((xyz[i * 3 + 2] - minZ) / cell));
            long key = (ix << 42) | (iy << 21) | iz;
            Integer known = cellToVert.get(key);
            if (known != null) {
                remap[i] = known;
            } else {
                int idx = verts.size() / 3;
                verts.add(xyz[i * 3]);
                verts.add(xyz[i * 3 + 1]);
                verts.add(xyz[i * 3 + 2]);
                cellToVert.put(key, idx);
                remap[i] = idx;
            }
        }
        for (int t = 0; t < triCount; t++) {
            int a = remap[t * 3], b = remap[t * 3 + 1], c = remap[t * 3 + 2];
            if (a == b || b == c || a == c) continue; // collapsed sub-cell tri
            kept.add(a);
            kept.add(b);
            kept.add(c);
        }
    }

    private static long clamp21(long v) {
        return v < 0 ? 0 : Math.min(v, (1L << 21) - 1);
    }
    /**
     * Copy with outward-facing winding, or {@code this} if already outward.
     * Loaded files (STL/OBJ/3MF) do not all follow the CCW-outward
     * convention: an inward-wound mesh renders fully dark (normals point
     * inside, Lambert term ~0 everywhere) and breaks backface culling.
     * Signed soup volume is negative for inward winding; such meshes get
     * their triangles flipped (normals recomputed). Open/non-manifold soup
     * has ~zero volume and is returned unchanged.
     */
    public GalleryMesh withOutwardWinding() {
        double vol = 0;
        for (int t = 0; t < triCount; t++) {
            int o = t * 9;
            double ax = xyz[o], ay = xyz[o + 1], az = xyz[o + 2];
            double bx = xyz[o + 3], by = xyz[o + 4], bz = xyz[o + 5];
            double cx = xyz[o + 6], cy = xyz[o + 7], cz = xyz[o + 8];
            // dot(a, cross(b, c)) / 6 summed over the soup.
            vol += ax * (by * cz - bz * cy)
                 + ay * (bz * cx - bx * cz)
                 + az * (bx * cy - by * cx);
        }
        if (vol >= 0) return this;
        float[] out = xyz.clone();
        for (int t = 0; t < triCount; t++) {
            int o = t * 9;
            for (int k = 0; k < 3; k++) {
                float tmp = out[o + 3 + k];
                out[o + 3 + k] = out[o + 6 + k];
                out[o + 6 + k] = tmp;
            }
        }
        return new GalleryMesh(out);
    }

    public static GalleryMesh concat(GalleryMesh... parts) {
        int totalXyz = 0, totalNrm = 0;
        for (GalleryMesh p : parts) {
            totalXyz += p.xyz.length;
            totalNrm += p.triCount * 3;
        }
        float[] outXyz = new float[totalXyz];
        float[] outNormals = new float[totalXyz];
        int ox = 0, on = 0;
        for (GalleryMesh p : parts) {
            System.arraycopy(p.xyz, 0, outXyz, ox, p.xyz.length);
            // Normals are packed one-per-tri at stride 3 (see
            // computeNormals), NOT at the xyz stride: copying at the xyz
            // offset zeroed every part after the first (dark glyphs).
            int nn = p.triCount * 3;
            System.arraycopy(p.normals, 0, outNormals, on, nn);
            ox += p.xyz.length;
            on += nn;
        }
        return new GalleryMesh(outXyz, outNormals);
    }

    public GalleryMesh translated(float dx, float dy, float dz) {
        float[] outXyz = xyz.clone();
        for (int i = 0; i < outXyz.length; i += 3) {
            outXyz[i] += dx;
            outXyz[i + 1] += dy;
            outXyz[i + 2] += dz;
        }
        return new GalleryMesh(outXyz, normals.clone());
    }

    public GalleryMesh rotatedX(double angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] outXyz = xyz.clone();
        float[] outNormals = normals.clone();
        for (int i = 0; i < outXyz.length; i += 3) {
            float y = outXyz[i + 1], z = outXyz[i + 2];
            outXyz[i + 1] = y * cos - z * sin;
            outXyz[i + 2] = y * sin + z * cos;
            float ny = outNormals[i + 1], nz = outNormals[i + 2];
            outNormals[i + 1] = ny * cos - nz * sin;
            outNormals[i + 2] = ny * sin + nz * cos;
        }
        return new GalleryMesh(outXyz, outNormals);
    }

    public GalleryMesh rotatedY(double angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] outXyz = xyz.clone();
        float[] outNormals = normals.clone();
        for (int i = 0; i < outXyz.length; i += 3) {
            float x = outXyz[i], z = outXyz[i + 2];
            outXyz[i] = x * cos + z * sin;
            outXyz[i + 2] = -x * sin + z * cos;
            float nx = outNormals[i], nz = outNormals[i + 2];
            outNormals[i] = nx * cos + nz * sin;
            outNormals[i + 2] = -nx * sin + nz * cos;
        }
        return new GalleryMesh(outXyz, outNormals);
    }

    // Binary serialization for disk caching
    public void writeToFile(File file) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 64 * 1024))) {
            dos.writeInt(triCount);
            for (int i = 0; i < xyz.length; i++) {
                dos.writeFloat(xyz[i]);
            }
            for (int i = 0; i < normals.length; i++) {
                dos.writeFloat(normals[i]);
            }
        }
    }

    public static GalleryMesh readFromFile(File file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            int triCount = dis.readInt();
            if (triCount <= 0 || triCount > 2000000) throw new IOException("bad cache triangle count");
            int vertexCount = triCount * 9;
            float[] xyz = new float[vertexCount];
            float[] normals = new float[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                xyz[i] = dis.readFloat();
            }
            for (int i = 0; i < vertexCount; i++) {
                normals[i] = dis.readFloat();
            }
            return new GalleryMesh(xyz, normals);
        }
    }
}
