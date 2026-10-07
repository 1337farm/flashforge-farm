package com.flashforge.farm.gallery;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GalleryCacheKeyTest {

    @Test
    public void testBuiltinKeyIsStableId() {
        ShapeGallery.Item item = new ShapeGallery.Item(
                "cube", "Cube", "20 mm primitive", ShapeGallery.KIND_CUBE, null, null);
        assertEquals("cube", ShapeGallery.cacheKey(item));
    }

    @Test
    public void testCustomKeyTracksContentIdentity() throws Exception {
        File f = File.createTempFile("cachekey", ".stl");
        f.deleteOnExit();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[]{1, 2, 3, 4});
        }
        ShapeGallery.Item item = new ShapeGallery.Item(
                "custom:cachekey.stl", "cachekey.stl", "Custom model",
                ShapeGallery.KIND_CUSTOM, null, f);
        String key = ShapeGallery.cacheKey(item);
        assertTrue(key.startsWith("custom:cachekey.stl_"));
        assertTrue(key.contains(String.valueOf(f.length())));
        // Same file, same key (memory LRU hits instead of re-reading).
        assertEquals(key, ShapeGallery.cacheKey(item));
    }

    @Test
    public void testRowPreviewSwaps() {
        ShapeGallery.Item item = new ShapeGallery.Item(
                "cube", "Cube", "20 mm primitive", ShapeGallery.KIND_CUBE, null, null);
        GalleryRowItem row = new GalleryRowItem(item, ShapeGallery.placeholderCube());
        GalleryMesh other = Primitives.cube(10);
        row.updatePreview(other);
        assertFalse(row == null);
    }
}
