package com.flashforge.farm.gallery;

import java.util.ArrayList;

public final class Primitives {
    private Primitives() {
    }

    public static GalleryMesh box(float w, float h, float d) {
        float x = w / 2, y = h / 2, z = d / 2;
        float[] v = {
            x, -y, z, x, -y, -z, x, y, -z,
            x, -y, z, x, y, -z, x, y, z,
            -x, -y, -z, -x, -y, z, -x, y, z,
            -x, -y, -z, -x, y, z, -x, y, -z,
            -x, y, z, x, y, z, x, y, -z,
            -x, y, z, x, y, -z, -x, y, -z,
            -x, -y, -z, x, -y, -z, x, -y, z,
            -x, -y, -z, x, -y, z, -x, -y, z,
            -x, -y, z, x, -y, z, x, y, z,
            -x, -y, z, x, y, z, -x, y, z,
            x, -y, -z, -x, -y, -z, -x, y, -z,
            x, -y, -z, -x, y, -z, x, y, -z,
        };
        return new GalleryMesh(v);
    }

    public static GalleryMesh cube(float size) {
        return box(size, size, size);
    }

    public static GalleryMesh cylinder(float diameter, float height, int segments) {
        float r = diameter / 2, hy = height / 2;
        ArrayList<Float> out = new ArrayList<Float>();
        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments;
            double a1 = 2 * Math.PI * (i + 1) / segments;
            float x0 = (float) (r * Math.cos(a0)), z0 = (float) (r * Math.sin(a0));
            float x1 = (float) (r * Math.cos(a1)), z1 = (float) (r * Math.sin(a1));
            push(out, x0, -hy, z0, x0, hy, z0, x1, hy, z1);
            push(out, x0, -hy, z0, x1, hy, z1, x1, -hy, z1);
            push(out, 0, hy, 0, x1, hy, z1, x0, hy, z0);
            push(out, 0, -hy, 0, x0, -hy, z0, x1, -hy, z1);
        }
        return toMesh(out);
    }

    public static GalleryMesh cone(float diameter, float height, int segments) {
        float r = diameter / 2, hy = height / 2;
        ArrayList<Float> out = new ArrayList<Float>();
        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments;
            double a1 = 2 * Math.PI * (i + 1) / segments;
            float x0 = (float) (r * Math.cos(a0)), z0 = (float) (r * Math.sin(a0));
            float x1 = (float) (r * Math.cos(a1)), z1 = (float) (r * Math.sin(a1));
            push(out, x0, -hy, z0, 0, hy, 0, x1, -hy, z1);
            push(out, 0, -hy, 0, x0, -hy, z0, x1, -hy, z1);
        }
        return toMesh(out);
    }

    public static GalleryMesh sphere(float diameter, int lonSegs, int latSegs) {
        float r = diameter / 2;
        ArrayList<Float> out = new ArrayList<Float>();
        for (int la = 0; la < latSegs; la++) {
            double t0 = Math.PI * la / latSegs;
            double t1 = Math.PI * (la + 1) / latSegs;
            for (int lo = 0; lo < lonSegs; lo++) {
                double p0 = 2 * Math.PI * lo / lonSegs;
                double p1 = 2 * Math.PI * (lo + 1) / lonSegs;
                float[] a = sph(r, t0, p0), b = sph(r, t1, p0);
                float[] c = sph(r, t1, p1), d = sph(r, t0, p1);
                if (la > 0) push(out, a[0], a[1], a[2], c[0], c[1], c[2], b[0], b[1], b[2]);
                if (la + 1 < latSegs) push(out, a[0], a[1], a[2], d[0], d[1], d[2], c[0], c[1], c[2]);
                if (la == 0) push(out, a[0], a[1], a[2], c[0], c[1], c[2], b[0], b[1], b[2]);
                if (la + 1 == latSegs) push(out, a[0], a[1], a[2], d[0], d[1], d[2], c[0], c[1], c[2]);
            }
        }
        return toMesh(out);
    }

    private static float[] sph(float r, double theta, double phi) {
        return new float[]{
            (float) (r * Math.sin(theta) * Math.cos(phi)),
            (float) (r * Math.cos(theta)),
            (float) (r * Math.sin(theta) * Math.sin(phi)),
        };
    }

    private static void push(ArrayList<Float> out, float... vals) {
        for (float v : vals) out.add(v);
    }

    private static GalleryMesh toMesh(ArrayList<Float> out) {
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return new GalleryMesh(arr);
    }
}
