package com.flashforge.farm.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Pins the phone-side iroh identity semantics that USB reverse-pairing depends on.
 *
 * <p>The fixed-seed vector below must match native_iroh_engine's
 * {@code tests/nodeid_vector.rs} byte-for-byte: it proves the Java
 * (net.i2p eddsa) and Rust (iroh SecretKey) derivations agree, which is what
 * lets the printer's dial find this phone's NodeId.
 */
public class NodeIdentityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void toHexVectors() {
        assertEquals("00ffab", NodeIdentity.toHex(new byte[]{0x00, (byte) 0xFF, (byte) 0xAB}));
        assertEquals("", NodeIdentity.toHex(new byte[0]));
    }

    @Test
    public void fixedSeedMatchesRustEngine() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        assertEquals(
                "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
                NodeIdentity.nodeIdHex(seed));
    }

    @Test
    public void nodeIdIsLowercaseHex64() {
        String id = NodeIdentity.nodeIdHex(NodeIdentity.randomBytes(32));
        assertEquals(64, id.length());
        assertTrue(id.matches("[0-9a-f]{64}"));
    }

    @Test
    public void seedPersistsAcrossCalls() throws Exception {
        File dir = tmp.newFolder("files");
        byte[] first = NodeIdentity.getOrCreateSeed(dir);
        assertEquals(32, first.length);
        byte[] second = NodeIdentity.getOrCreateSeed(dir);
        assertArrayEquals(first, second);
    }

    @Test
    public void shortSeedFileIsRepaired() throws Exception {
        File dir = tmp.newFolder("files");
        try (FileOutputStream out = new FileOutputStream(new File(dir, "node_seed"))) {
            out.write(new byte[]{1, 2, 3});
        }
        byte[] seed = NodeIdentity.getOrCreateSeed(dir);
        assertEquals(32, seed.length);
        assertArrayEquals(seed, NodeIdentity.getOrCreateSeed(dir));
    }

    @Test
    public void randomBytesLength() {
        assertEquals(32, NodeIdentity.randomBytes(32).length);
    }
}
