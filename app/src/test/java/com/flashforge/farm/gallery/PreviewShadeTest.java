package com.flashforge.farm.gallery;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PreviewShadeTest {

    static int[] render(GalleryMesh mesh, float yaw, float pitch, int size) {
        float[] b = new float[6];
        mesh.bounds(b);
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
        float radius = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        float cosA = (float) Math.cos(yaw), sinA = (float) Math.sin(yaw);
        float cosT = (float) Math.cos(pitch), sinT = (float) Math.sin(pitch);
        float k = (size / 2f - 4f) / radius;
        float fl = PreviewRaster.FOCAL_RADII * radius;
        float lx = PreviewRaster.LIGHT_X, ly = PreviewRaster.LIGHT_Y, lz = PreviewRaster.LIGHT_Z;
        float llen = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= llen;
        ly /= llen;
        lz /= llen;
        int[] pixels = new int[size * size];
        float[] depth = new float[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0;
            depth[i] = Float.MAX_VALUE;
        }
        float[] xs = new float[3], ys = new float[3], zs = new float[3], tmp = new float[6];
        for (int t = 0; t < mesh.triCount; t++) {
            PreviewRaster.rasterTriangle(mesh.xyz, mesh.normals, t,
                    cx, cy, cz, cosA, sinA, cosT, sinT,
                    size, k, fl, lx, ly, lz,
                    pixels, depth, xs, ys, zs, tmp);
        }
        return pixels;
    }

    static double[] halfBrightness(int[] pixels, int size) {
        long top = 0, bottom = 0;
        int nt = 0, nb = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = pixels[y * size + x];
                if (px == 0) continue;
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, bl = px & 0xFF;
                int lum = (r + g + bl) / 3;
                if (y < size / 2) {
                    top += lum;
                    nt++;
                } else {
                    bottom += lum;
                    nb++;
                }
            }
        }
        return new double[]{nt == 0 ? 0 : (double) top / nt, nb == 0 ? 0 : (double) bottom / nb};
    }

    @Test
    public void testTagTopBrighterThanBottom() throws Exception {
        GalleryMesh mesh = ShapeGallery.meshFor(
                new ShapeGallery.Item("tag_pla", "PLA tag", "Recycling tag",
                        ShapeGallery.KIND_TAG, "PLA", null)).rotatedX(-Math.PI / 2);
        int size = 144;
        double[] hb = halfBrightness(render(mesh, 0f, -0.35f, size), size);
        assertTrue("top=" + hb[0] + " bottom=" + hb[1] + ": key light must come from above",
                hb[0] > hb[1]);
    }

    @Test
    public void testCubeTopBrighterThanBottom() {
        GalleryMesh mesh = Primitives.cube(20).rotatedX(-Math.PI / 2);
        int size = 144;
        double[] hb = halfBrightness(render(mesh, 0f, -0.35f, size), size);
        assertTrue("top=" + hb[0] + " bottom=" + hb[1],
                hb[0] > hb[1]);
    }
}
