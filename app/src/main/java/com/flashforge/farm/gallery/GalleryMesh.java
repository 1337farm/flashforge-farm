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
     * Stride-subsampled copy capped at maxTris triangles (every Nth triangle,
     * order preserved). Unlike retriangulation (removed in #272 for causing
     * artifacts), this never invents geometry: thumbnails of huge models stay
     * recognizable and cheap. Returns {@code this} when already small enough.
     */
    public GalleryMesh decimated(int maxTris) {
        if (triCount <= maxTris || maxTris <= 0) return this;
        int stride = (triCount + maxTris - 1) / maxTris;
        int kept = (triCount + stride - 1) / stride;
        float[] outXyz = new float[kept * 9];
        float[] outNormals = new float[kept * 9];
        int o = 0;
        for (int t = 0; t < triCount; t += stride) {
            System.arraycopy(xyz, t * 9, outXyz, o, 9);
            // Face normals are stored one-per-tri at stride 3 (see computeNormals).
            if (t * 3 + 2 < normals.length) {
                outNormals[o / 3] = normals[t * 3];
                outNormals[o / 3 + 1] = normals[t * 3 + 1];
                outNormals[o / 3 + 2] = normals[t * 3 + 2];
            }
            o += 9;
        }
        return new GalleryMesh(outXyz, outNormals);
    }

    public static GalleryMesh concat(GalleryMesh... parts) {        int total = 0;
        for (GalleryMesh p : parts) total += p.xyz.length;
        float[] outXyz = new float[total];
        float[] outNormals = new float[total];
        int o = 0;
        for (GalleryMesh p : parts) {
            System.arraycopy(p.xyz, 0, outXyz, o, p.xyz.length);
            System.arraycopy(p.normals, 0, outNormals, o, p.normals.length);
            o += p.xyz.length;
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
