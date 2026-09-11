package com.flashforge.farm.gallery;

import com.flashforge.farm.FarmApp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ShapeGallery {
    private ShapeGallery() {
    }

    public static final int PREVIEW_MAX_TRIS = 1200;

    public static final int KIND_CUBE = 1;
    public static final int KIND_CYLINDER = 2;
    public static final int KIND_SPHERE = 3;
    public static final int KIND_CONE = 4;
    public static final int KIND_DISK = 5;
    public static final int KIND_TAG = 6;
    public static final int KIND_CUSTOM = 7;

    public static final class Item {
        public final String id;
        public final String title;
        public final String subtitle;
        public final int kind;
        public final String tag;
        public final File file;

        Item(String id, String title, String subtitle, int kind, String tag, File file) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.kind = kind;
            this.tag = tag;
            this.file = file;
        }
    }

    public static List<Item> builtins() {
        ArrayList<Item> out = new ArrayList<Item>();
        out.add(new Item("cube", "Cube", "20 mm primitive", KIND_CUBE, null, null));
        out.add(new Item("cylinder", "Cylinder", "Ø20 × 20 mm primitive", KIND_CYLINDER, null, null));
        out.add(new Item("sphere", "Sphere", "Ø20 mm primitive", KIND_SPHERE, null, null));
        out.add(new Item("cone", "Cone", "Ø20 × 20 mm primitive", KIND_CONE, null, null));
        out.add(new Item("disk", "Helper disk", "Ø12 mm mouse ear", KIND_DISK, null, null));
        for (String m : Tags.MATERIALS) {
            out.add(new Item("tag_" + m.toLowerCase(), m + " tag", "Recycling tag", KIND_TAG, m, null));
        }
        return out;
    }

    public static List<Item> customs() {
        ArrayList<Item> out = new ArrayList<Item>();
        for (File f : GalleryStore.listCustom()) {
            out.add(new Item("custom:" + f.getName(), f.getName(), "Custom model", KIND_CUSTOM, null, f));
        }
        return out;
    }

    public static GalleryMesh meshFor(Item item) throws IOException {
        switch (item.kind) {
            case KIND_CUBE:
                return Primitives.cube(20);
            case KIND_CYLINDER:
                return Primitives.cylinder(20, 20, 32);
            case KIND_SPHERE:
                return Primitives.sphere(20, 24, 16);
            case KIND_CONE:
                return Primitives.cone(20, 20, 32);
            case KIND_DISK:
                return Primitives.cylinder(12, 0.8f, 32);
            case KIND_TAG:
                return Tags.buildTag(item.tag);
            default:
                throw new IOException("not a built-in");
        }
    }

    public static GalleryMesh previewFor(Item item) throws IOException {
        GalleryMesh mesh = item.kind == KIND_CUSTOM ? MeshLoader.load(item.file) : meshFor(item);
        return MeshDecimator.decimate(mesh, PREVIEW_MAX_TRIS);
    }

    public static File fileFor(Item item) throws IOException {
        if (item.kind == KIND_CUSTOM) return item.file;
        File f = new File(FarmApp.getModelCacheDir(), "gallery_" + item.id + ".stl");
        StlWriter.writeBinary(f, meshFor(item));
        return f;
    }

    public static GalleryMesh placeholderCube() {
        return Primitives.cube(20);
    }
}
