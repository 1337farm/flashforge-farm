package com.flashforge.farm.gallery;

public final class GalleryMesh {
    public final float[] xyz;
    public final int triCount;

    public GalleryMesh(float[] xyz) {
        this.xyz = xyz;
        this.triCount = xyz.length / 9;
    }

    public void bounds(float[] out) {
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

    public static GalleryMesh concat(GalleryMesh... parts) {
        int total = 0;
        for (GalleryMesh p : parts) total += p.xyz.length;
        float[] out = new float[total];
        int o = 0;
        for (GalleryMesh p : parts) {
            System.arraycopy(p.xyz, 0, out, o, p.xyz.length);
            o += p.xyz.length;
        }
        return new GalleryMesh(out);
    }

    public GalleryMesh translated(float dx, float dy, float dz) {
        float[] out = xyz.clone();
        for (int i = 0; i < out.length; i += 3) {
            out[i] += dx;
            out[i + 1] += dy;
            out[i + 2] += dz;
        }
        return new GalleryMesh(out);
    }

    public GalleryMesh rotatedX(double angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] out = xyz.clone();
        for (int i = 0; i < out.length; i += 3) {
            float y = out[i + 1], z = out[i + 2];
            out[i + 1] = y * cos - z * sin;
            out[i + 2] = y * sin + z * cos;
        }
        return new GalleryMesh(out);
    }

    public GalleryMesh rotatedY(double angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] out = xyz.clone();
        for (int i = 0; i < out.length; i += 3) {
            float x = out[i], z = out[i + 2];
            out[i] = x * cos + z * sin;
            out[i + 2] = -x * sin + z * cos;
        }
        return new GalleryMesh(out);
    }
}
