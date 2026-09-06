package com.flashforge.farm.modelrepo;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;

public class ModelSafetyTest {

    @Test
    public void testAllowlist() {
        assertTrue(ModelSafety.isAllowedName("benchy.stl"));
        assertTrue(ModelSafety.isAllowedName("model.3mf"));
        assertTrue(ModelSafety.isAllowedName("part.STL"));
        assertTrue(ModelSafety.isAllowedName("model.json"));
        assertTrue(ModelSafety.isAllowedName("thumb.png"));
        assertFalse(ModelSafety.isAllowedName("evil.exe"));
        assertFalse(ModelSafety.isAllowedName("run.sh"));
        assertFalse(ModelSafety.isAllowedName("lib.apk"));
        assertFalse(ModelSafety.isAllowedName("page.html"));
        assertFalse(ModelSafety.isAllowedName("bundle.zip"));
        assertFalse(ModelSafety.isAllowedName(""));
        assertFalse(ModelSafety.isAllowedName(null));
    }

    @Test
    public void testTraversalRejected() {
        assertFalse(ModelSafety.isAllowedName("../evil.stl"));
        assertFalse(ModelSafety.isAllowedName("/abs.stl"));
        assertFalse(ModelSafety.isAllowedName("a/../../b.stl"));
        assertFalse(ModelSafety.isAllowedName("C:model.stl"));
    }

    @Test
    public void testBudget() {
        ModelSafety.checkBudget(1, 0, 0);
        ModelSafety.checkBudget(ModelSafety.MAX_FILE_BYTES, 0, 0);
        try {
            ModelSafety.checkBudget(ModelSafety.MAX_FILE_BYTES + 1, 0, 0);
            fail("expected oversize file to throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ModelSafety.checkBudget(1, ModelSafety.MAX_TOTAL_BYTES, 0);
            fail("expected oversize total to throw");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ModelSafety.checkBudget(1, 0, ModelSafety.MAX_FILE_COUNT);
            fail("expected too-many-files to throw");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testCanonicalChild() throws Exception {
        java.io.File dir = new java.io.File("/tmp/farmtest");
        java.io.File ok = ModelSafety.canonicalChild(dir, "a/b.stl");
        assertTrue(ok.getPath().contains("a"));
        try {
            ModelSafety.canonicalChild(dir, "../../evil.stl");
            fail("expected traversal to throw");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testExtensionMatchesSniff() {
        assertTrue(ModelSafety.extensionMatchesSniff("stl", "stl-ascii"));
        assertTrue(ModelSafety.extensionMatchesSniff("stl", "stl-binary"));
        assertTrue(ModelSafety.extensionMatchesSniff("3mf", "zip"));
        assertTrue(ModelSafety.extensionMatchesSniff("obj", "obj"));
        assertFalse(ModelSafety.extensionMatchesSniff("stl", "zip"));
        assertFalse(ModelSafety.extensionMatchesSniff("exe", "unknown"));
    }

    @Test
    public void testSniffBinaryStl() throws Exception {
        java.io.File f = java.io.File.createTempFile("model", ".stl");
        try {
            byte[] header = new byte[84];
            java.util.Arrays.fill(header, (byte) 0);
            System.arraycopy("Binary STL - no solid lead".getBytes("UTF-8"), 0, header, 0, 26);
            header[80] = 2;
            byte[] facets = new byte[100];
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(header);
                fos.write(facets);
            }
            assertEquals("stl-binary", ModelSafety.sniffKind(f));
            assertTrue(ModelSafety.extensionMatchesSniff("stl", ModelSafety.sniffKind(f)));
        } finally {
            f.delete();
        }
    }

    @Test
    public void testSniffSolidLeadBinaryStl() throws Exception {
        java.io.File f = java.io.File.createTempFile("model", ".stl");
        try {
            byte[] header = new byte[84];
            java.util.Arrays.fill(header, (byte) 0);
            System.arraycopy("solid sneaky-binary".getBytes("UTF-8"), 0, header, 0, 18);
            header[80] = 5;
            byte[] facets = new byte[50];
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(header);
                fos.write(facets);
            }
            assertEquals("stl-binary", ModelSafety.sniffKind(f));
        } finally {
            f.delete();
        }
    }

    @Test
    public void testSniffAsciiStl() throws Exception {
        java.io.File f = java.io.File.createTempFile("model", ".stl");
        try {
            byte[] body = "solid ascii\nendsolid\n".getBytes("UTF-8");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(body);
            }
            assertEquals("stl-ascii", ModelSafety.sniffKind(f));
        } finally {
            f.delete();
        }
    }

    @Test
    public void testTrustLevels() {
        TrustStore store = new TrustStore(new TrustStore.MemoryStorage());
        assertEquals(TrustStore.Level.UNVERIFIED, store.level("ABC"));
        assertTrue(store.needsConfirm(store.level("ABC")));
        store.markSeen("ABC");
        assertEquals(TrustStore.Level.SEEN, store.level("abc"));
        assertTrue(store.needsConfirm(store.level("ABC")));
        store.setTrusted("ABC", true);
        assertEquals(TrustStore.Level.TRUSTED, store.level("ABC"));
        assertFalse(store.needsConfirm(store.level("ABC")));
        store.setBlocked("ABC", true);
        assertEquals(TrustStore.Level.BLOCKED, store.level("ABC"));
    }

    @Test
    public void testMetadataRoundTrip() {
        ModelMetadata m = new ModelMetadata();
        m.title = "Benchy";
        m.designer.name = "Alice";
        m.designer.pubkey = "abc123";
        m.tags = Arrays.asList("calibration", "test");
        m.files = Arrays.asList("benchy.stl");
        m.remixOf = new ModelMetadata.RemixRef();
        m.remixOf.contentHash = "deadbeef";
        ModelMetadata back = ModelMetadata.parse(m.toJson());
        assertEquals("Benchy", back.title);
        assertEquals("Alice", back.designer.name);
        assertEquals(2, back.tags.size());
        assertTrue(back.isRemix());
        assertFalse(new ModelMetadata().isRemix());
    }
}
