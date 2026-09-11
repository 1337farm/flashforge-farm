package com.flashforge.farm.gallery;

import java.util.ArrayList;

public final class BlockFont {
    private BlockFont() {
    }

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[][] GLYPHS = {
        {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7},
        {5, 5, 7, 1, 1}, {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 2, 2, 2},
        {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}, {2, 5, 7, 5, 5}, {6, 5, 6, 5, 6},
        {3, 4, 4, 4, 3}, {6, 5, 5, 5, 6}, {7, 4, 6, 4, 7}, {7, 4, 6, 4, 4},
        {3, 4, 5, 5, 3}, {5, 5, 7, 5, 5}, {7, 2, 2, 2, 7}, {1, 1, 1, 5, 2},
        {5, 5, 6, 5, 5}, {4, 4, 4, 4, 7}, {5, 7, 7, 5, 5}, {5, 7, 7, 7, 5},
        {2, 5, 5, 5, 2}, {6, 5, 6, 4, 4}, {2, 5, 5, 6, 3}, {6, 5, 6, 5, 5},
        {3, 4, 2, 1, 6}, {7, 2, 2, 2, 2}, {5, 5, 5, 5, 7}, {5, 5, 5, 5, 2},
        {5, 5, 5, 7, 5}, {5, 5, 2, 5, 5}, {5, 5, 2, 2, 2}, {7, 1, 2, 4, 7},
    };

    public static GalleryMesh textPlate(float plateW, float plateH, float plateT, String text, float pixel, float emboss) {
        ArrayList<GalleryMesh> parts = new ArrayList<GalleryMesh>();
        parts.add(Primitives.box(plateW, plateT, plateH).translated(0, plateT / 2, 0));
        float cursor = -(text.length() * 4 - 1) * pixel / 2;
        for (int i = 0; i < text.length(); i++) {
            int gi = CHARS.indexOf(Character.toUpperCase(text.charAt(i)));
            if (gi < 0) {
                cursor += 4 * pixel;
                continue;
            }
            int[] rows = GLYPHS[gi];
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 3; c++) {
                    if ((rows[r] & (1 << (2 - c))) != 0) {
                        float px = cursor + c * pixel;
                        float pz = (r - 2) * pixel;
                        parts.add(Primitives.box(pixel * 0.92f, emboss, pixel * 0.92f)
                                .translated(px, plateT + emboss / 2, pz));
                    }
                }
            }
            cursor += 4 * pixel;
        }
        GalleryMesh[] arr = new GalleryMesh[parts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = parts.get(i);
        GalleryMesh plate = GalleryMesh.concat(arr);
        float[] b = new float[6];
        plate.bounds(b);
        return plate.translated(0, -b[1], 0);
    }
}
