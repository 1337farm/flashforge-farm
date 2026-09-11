package com.flashforge.farm.gallery;

import java.util.ArrayList;

public final class Tags {
    private Tags() {
    }

    public static final String[] MATERIALS = {
        "PLA", "PETG", "PET", "ABS", "ASA", "TPU", "PP",
        "PS", "PA", "PC", "PVA", "PVB", "POM",
    };

    public static GalleryMesh buildTag(String label) {
        float plateW = 46, plateT = 1.2f, plateD = 32;
        ArrayList<GalleryMesh> parts = new ArrayList<GalleryMesh>();
        parts.add(Primitives.box(plateW, plateT, plateD).translated(0, plateT / 2, 0));
        parts.add(arrows().translated(0, plateT, -6));
        GalleryMesh text = BlockFont.textPlate(plateW, plateD, 0.01f, label, 2.4f, 0.8f);
        parts.add(text.translated(0, plateT - 0.05f, 8.5f));
        GalleryMesh[] arr = new GalleryMesh[parts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = parts.get(i);
        GalleryMesh tag = GalleryMesh.concat(arr);
        float[] b = new float[6];
        tag.bounds(b);
        return tag.translated(0, -b[1], 0);
    }

    private static GalleryMesh arrows() {
        ArrayList<GalleryMesh> parts = new ArrayList<GalleryMesh>();
        double[] angles = {Math.PI / 2, Math.PI / 2 + 2 * Math.PI / 3, Math.PI / 2 + 4 * Math.PI / 3};
        for (double a : angles) {
            parts.add(arrow(-a));
        }
        GalleryMesh[] arr = new GalleryMesh[parts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = parts.get(i);
        return GalleryMesh.concat(arr);
    }

    private static GalleryMesh arrow(double phi) {
        GalleryMesh shaft = Primitives.box(2.2f, 0.8f, 7f).translated(0, 0.4f, 1f);
        GalleryMesh head = Primitives.cone(3.6f, 3.4f, 4).rotatedX(Math.PI / 2).translated(0, 0.4f, 6.2f);
        GalleryMesh group = GalleryMesh.concat(shaft, head).translated(0, 0, -2.7f);
        double ux = Math.cos(-phi), uz = Math.sin(-phi);
        return group.rotatedY(phi).translated((float) (ux * 6.5), 0, (float) (uz * 6.5));
    }
}
