package com.flashforge.farm.gallery;

import com.flashforge.farm.FarmApp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ShapeGallery {
    private ShapeGallery() {
    }

    public static final int PREVIEW_MAX_TRIS = 8000;
    // preview4_: the 8000-tri budget changes preview content vs the 1200-tri
    // era (e.g. Benchy keeps full height and small features); stale files
    // are regenerated, legacy swept below.
    private static final String PREVIEW_CACHE_PREFIX = "preview4_";
    private static final String[] LEGACY_PREVIEW_CACHE_PREFIXES = {"preview_", "preview2_", "preview3_"};

    public static final int KIND_CUBE = 1;
    public static final int KIND_CYLINDER = 2;
    public static final int KIND_SPHERE = 3;
    public static final int KIND_CONE = 4;
    public static final int KIND_DISK = 5;
    public static final int KIND_TAG = 6;
    public static final int KIND_CUSTOM = 7;
    public static final int KIND_CALIB_PA_LINE = 8;
    public static final int KIND_CALIB_PA_PATTERN = 9;
    public static final int KIND_CALIB_PA_TOWER = 10;

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
        out.add(new Item("calib_pa_line", "PA Line test", "Pressure Advance lines", KIND_CALIB_PA_LINE, null, null));
        out.add(new Item("calib_pa_pattern", "PA Pattern test", "Pressure Advance grid", KIND_CALIB_PA_PATTERN, null, null));
        out.add(new Item("calib_pa_tower", "PA Tower test", "Pressure Advance tower", KIND_CALIB_PA_TOWER, null, null));
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
        // Model space is Z-up (STL convention, matches the bed: fileFor
        // writes this soup straight to STL). Primitives are built Y-up, so
        // rotate +90° about X (+Y height -> +Z); proper rotation preserves
        // winding and rotatedX carries the normals. CalibModels are already
        // Z-up (levels stack along Z) and customs load Z-up: no transform.
        switch (item.kind) {
            case KIND_CUBE:
                return Primitives.cube(20).rotatedX(Math.PI / 2);
            case KIND_CYLINDER:
                return Primitives.cylinder(20, 20, 32).rotatedX(Math.PI / 2);
            case KIND_SPHERE:
                return Primitives.sphere(20, 24, 16).rotatedX(Math.PI / 2);
            case KIND_CONE:
                return Primitives.cone(20, 20, 32).rotatedX(Math.PI / 2);
            case KIND_DISK:
                return Primitives.cylinder(12, 0.8f, 32).rotatedX(Math.PI / 2);
            case KIND_TAG:
                return Tags.buildTag(item.tag);
            case KIND_CALIB_PA_LINE:
                return CalibModels.paLine();
            case KIND_CALIB_PA_PATTERN:
                return CalibModels.paPattern();
            case KIND_CALIB_PA_TOWER:
                return CalibModels.paTower();
            default:
                throw new IOException("not a built-in");
        }
    }

    public static GalleryMesh previewFor(Item item) throws IOException {
        File cacheDir = FarmApp.getModelCacheDir();
        File cacheFile = new File(cacheDir, PREVIEW_CACHE_PREFIX + item.id + ".bin");

        // Check if cached preview exists and is valid
        if (cacheFile.exists()) {
            try {
                return GalleryMesh.readFromFile(cacheFile);
            } catch (IOException e) {
                // Cache corrupted, fall through to regenerate
                cacheFile.delete();
            }
        }

        GalleryMesh mesh = item.kind == KIND_CUSTOM ? MeshLoader.load(item.file) : meshFor(item);
        GalleryMesh decimated = MeshDecimator.decimate(mesh, PREVIEW_MAX_TRIS);

        // Display bake: model space is Z-up but the spinner is a Y-up
        // turntable, so rotate once here (-90° about X maps +Z to screen-up
        // and model front to the camera). Cached below, so zero per-frame
        // cost. Sweep best-effort.
        GalleryMesh display = decimated.rotatedX(-Math.PI / 2);
        try {
            File[] stale = cacheDir.listFiles();
            if (stale != null) {
                for (File f : stale) {
                    String name = f.getName();
                    if (!name.startsWith(PREVIEW_CACHE_PREFIX)) {
                        for (String legacy : LEGACY_PREVIEW_CACHE_PREFIXES) {
                            if (name.startsWith(legacy)) {
                                f.delete();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Cache hygiene is best-effort.
        }

        // Cache the decimated display mesh with normals
        try {
            display.writeToFile(cacheFile);
        } catch (IOException ignored) {
            // Non-fatal, continue with in-memory mesh
        }

        return display;
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
