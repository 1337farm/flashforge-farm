package com.flashforge.farm.gallery;

import java.util.ArrayList;

public final class CalibModels {
    private CalibModels() {
    }

    private static final float LINE_W = 0.6f;
    private static final float LAYER_H = 0.2f;
    private static final float SHORT_LEN = 20f;
    private static final float LONG_LEN = 40f;
    private static final float ROW_GAP = 3.5f;
    private static final int LINE_ROWS = 12;

    public static GalleryMesh paLine() {
        ArrayList<Float> out = new ArrayList<Float>();
        float totalW = SHORT_LEN * 2 + LONG_LEN;
        float y0 = -(LINE_ROWS * ROW_GAP) / 2;
        for (int i = 0; i < LINE_ROWS; i++) {
            float y = y0 + i * ROW_GAP;
            box(out, -totalW / 2, y - LINE_W / 2, 0, totalW, LINE_W, LAYER_H);
        }
        float labelX = totalW / 2 + 2;
        box(out, labelX, y0 - ROW_GAP, 0, 8, (LINE_ROWS + 1) * ROW_GAP, LAYER_H);
        return toMesh(out);
    }

    public static GalleryMesh paPattern() {
        ArrayList<Float> out = new ArrayList<Float>();
        float cell = 12f;
        float gap = 2f;
        int cols = 5;
        int rows = 5;
        float totalW = cols * cell + (cols - 1) * gap;
        float totalD = rows * cell + (rows - 1) * gap;
        float x0 = -totalW / 2;
        float y0 = -totalD / 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float x = x0 + c * (cell + gap);
                float y = y0 + r * (cell + gap);
                box(out, x, y, 0, cell, LINE_W, LAYER_H);
                box(out, x, y + cell - LINE_W, 0, cell, LINE_W, LAYER_H);
                box(out, x, y, 0, LINE_W, cell, LAYER_H);
                box(out, x + cell - LINE_W, y, 0, LINE_W, cell, LAYER_H);
                box(out, x + cell / 2 - LINE_W / 2, y, 0, LINE_W, cell, LAYER_H);
            }
        }
        return toMesh(out);
    }

    public static GalleryMesh paTower() {
        ArrayList<Float> out = new ArrayList<Float>();
        float base = 40f;
        float levels = 10;
        float levelH = 3f;
        for (int i = 0; i < levels; i++) {
            float w = base - i * 2f;
            float x0 = -w / 2;
            float y0 = -w / 2;
            float z0 = i * levelH;
            box(out, x0, y0, z0, w, w, levelH);
        }
        return toMesh(out);
    }

    private static void box(ArrayList<Float> out, float x, float y, float z, float w, float d, float h) {
        float x0 = x, x1 = x + w;
        float y0 = y, y1 = y + d;
        float z0 = z, z1 = z + h;
        quad(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(out, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(out, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(out, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(ArrayList<Float> out,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz) {
        tri(out, ax, ay, az, bx, by, bz, cx, cy, cz);
        tri(out, ax, ay, az, cx, cy, cz, dx, dy, dz);
    }

    private static void tri(ArrayList<Float> out,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz) {
        out.add(ax);
        out.add(ay);
        out.add(az);
        out.add(bx);
        out.add(by);
        out.add(bz);
        out.add(cx);
        out.add(cy);
        out.add(cz);
    }

    private static GalleryMesh toMesh(ArrayList<Float> out) {
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return new GalleryMesh(arr);
    }
}
