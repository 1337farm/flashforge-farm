package com.flashforge.farm.gallery;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GalleryMeshTest {

    @Test
    public void testWriteReadRoundTrip() throws Exception {
        GalleryMesh mesh = new GalleryMesh(new float[]{
                0, 0, 0,
                10, 0, 0,
                0, 10, 0,
                10, 0, 0,
                10, 10, 0,
                0, 10, 0,
        });
        File tmp = File.createTempFile("gallery-mesh-test", ".bin");
        try {
            mesh.writeToFile(tmp);
            GalleryMesh read = GalleryMesh.readFromFile(tmp);
            assertEquals(mesh.triCount, read.triCount);
            for (int i = 0; i < mesh.xyz.length; i++) {
                assertEquals(mesh.xyz[i], read.xyz[i], 1e-5f);
                assertEquals(mesh.normals[i], read.normals[i], 1e-5f);
            }
        } finally {
            tmp.delete();
        }
    }

    @Test
    public void testReadRejectsInsaneTriangleCount() throws Exception {
        File tmp = File.createTempFile("gallery-mesh-test", ".bad");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            out.write(new byte[]{0x00, 0x00, 0x00, 0x7F});
            out.close();
            boolean threw = false;
            try {
                GalleryMesh.readFromFile(tmp);
            } catch (java.io.IOException e) {
                threw = true;
            }
            assertTrue(threw);
        } finally {
            tmp.delete();
        }
    }
}