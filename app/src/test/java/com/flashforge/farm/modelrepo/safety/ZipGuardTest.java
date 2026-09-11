package com.flashforge.farm.modelrepo.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ZipGuardTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File makeZip(String name, byte[] payload) throws IOException {
        File f = File.createTempFile("zipguard", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new java.io.FileOutputStream(f))) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(payload);
            zos.closeEntry();
        }
        return f;
    }

    @Test
    public void smallEntryRoundTrips() throws Exception {
        byte[] payload = "hello-3mf".getBytes(StandardCharsets.UTF_8);
        File f = makeZip("Metadata/a.config", payload);
        try (ZipFile zip = new ZipFile(f)) {
            assertEquals("hello-3mf",
                    ZipGuard.readEntryText(zip, zip.getEntry("Metadata/a.config"), 1024));
        }
    }

    @Test
    public void oversizedHintFailsFast() throws Exception {
        byte[] payload = new byte[16];
        File f = makeZip("big.bin", payload);
        try (ZipFile zip = new ZipFile(f)) {
            try {
                ZipGuard.readEntryBytes(zip, zip.getEntry("big.bin"), 8);
                fail("expected IOException");
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void bombFailsOnInflatedBytes() throws Exception {
        byte[] zeros = new byte[4096];
        File f = makeZip("bomb.bin", zeros);
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry e = zip.getEntry("bomb.bin");
            try {
                ZipGuard.readEntryBytes(zip, e, 16);
                fail("expected IOException");
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void copyBoundedCountsAndCaps() throws Exception {
        byte[] in = new byte[100];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(100L, ZipGuard.copyBounded(new ByteArrayInputStream(in), out, 100));
        try {
            ZipGuard.copyBounded(new ByteArrayInputStream(in),
                    new ByteArrayOutputStream(), 99);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void entryCountCap() throws Exception {
        ZipGuard.checkEntryCount(SafetyPolicy.MAX_ZIP_ENTRIES);
        try {
            ZipGuard.checkEntryCount(SafetyPolicy.MAX_ZIP_ENTRIES + 1);
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void slipRejectedAndNormalAccepted() throws Exception {
        File root = tmp.getRoot();
        assertTrue(ZipGuard.safeDestination(root, "a/b.config").getCanonicalPath()
                .startsWith(root.getCanonicalPath()));
        try {
            ZipGuard.safeDestination(root, "../evil.sh");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        try {
            ZipGuard.safeDestination(root, "a/../../evil.sh");
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }
}
