package com.flashforge.farm.modelrepo;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class PublishKitTest {

    private static byte[] unhex(String s) {
        return Identity.unhex(s.replaceAll("\\s+", ""));
    }

    @Test
    public void testRfc8032Vector1() throws Exception {
        byte[] secret = unhex("9d61b19deffd5a60ba844af492ec2cc4"
                + "4449c5697b326919703bac031cae7f60");
        byte[] pub = unhex("d75a980182b10ab7d54bfed3c964073a"
                + "0ee172f3daa62325af021a68f707511a");
        byte[] sig = unhex("e5564300c360ac729086e2cc806e828a"
                + "84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46b"
                + "d25bf5f0595bbe24655141438e7a100b");
        assertArrayEquals(pub, Identity.publicKey(secret));
        assertArrayEquals(sig, Identity.sign(secret, new byte[0]));
        assertTrue(Identity.verify(pub, new byte[0], sig));
    }

    @Test
    public void testSignVerifyRoundTrip() throws Exception {
        byte[] seed = Identity.generateSeed();
        byte[] pub = Identity.publicKey(seed);
        byte[] msg = "farm:model:test".getBytes("UTF-8");
        byte[] sig = Identity.sign(seed, msg);
        assertEquals(64, sig.length);
        assertTrue(Identity.verify(pub, msg, sig));
        byte[] bad = sig.clone();
        bad[0] ^= 1;
        assertFalse(Identity.verify(pub, msg, bad));
        assertFalse(Identity.verify(pub, "other".getBytes("UTF-8"), sig));
        assertFalse(Identity.verify(new byte[32], msg, sig));
    }

    @Test
    public void testBencodeOrdering() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zebra", 1L);
        m.put("apple", "x");
        m.put("mango", Arrays.asList(1L, 2L));
        byte[] enc = Bencode.encode(m);
        // keys sorted bytewise: apple, mango, zebra
        String s = new String(enc, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s.startsWith("d5:apple1:x5:mango"));
        assertTrue(s.endsWith("5:zebra i1ee".replace(" ", "")));
    }

    @Test
    public void testDhtRecordRoundTrip() throws Exception {
        byte[] seed = Identity.generateSeed();
        Map<String, Object> v = DhtRecord.catalogPointer("blobTICKET123", 12345L);
        DhtRecord.MutableItem item = DhtRecord.sign(seed, 7L, v);
        assertTrue(DhtRecord.verify(item));
        assertEquals(20, DhtRecord.target(item.pubkey).length);
        byte[] tampered = item.value.clone();
        tampered[0] ^= 1;
        assertFalse(DhtRecord.verify(
                new DhtRecord.MutableItem(item.pubkey, item.seq, tampered, item.signature)));
        assertFalse(DhtRecord.verify(
                new DhtRecord.MutableItem(item.pubkey, item.seq + 1, item.value, item.signature)));
    }

    @Test
    public void testProofOfWork() throws Exception {
        byte[] pub = new byte[32];
        Arrays.fill(pub, (byte) 7);
        byte[] payload = "payload".getBytes("UTF-8");
        ProofOfWork.Solution s = ProofOfWork.solve(pub, 1L, payload, 12, 1 << 20);
        assertTrue(ProofOfWork.verify(pub, 1L, payload, s.nonce, 12));
        assertTrue(ProofOfWork.leadingZeroBits(s.digest) >= 12);
        try {
            ProofOfWork.solve(pub, 1L, payload, 64, 1);
            fail("expected no solution in 1 try at 64 bits");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testDesignerProofMapping() {
        String farm = "{\"names\":{\"alice\":\"ABCDEF0123\"}}";
        assertTrue(DesignerProof.verifyMapping(farm, "alice", "abcdef0123"));
        assertTrue(DesignerProof.verifyMapping(farm, "ALICE", "ABCDEF0123"));
        assertFalse(DesignerProof.verifyMapping(farm, "alice", "0000"));
        assertFalse(DesignerProof.verifyMapping(farm, "bob", "ABCDEF0123"));
        assertFalse(DesignerProof.verifyMapping("not json", "alice", "ABCDEF0123"));
        assertFalse(DesignerProof.verifyMapping(null, "alice", "ABCDEF0123"));
        String nostr = "{\"names\":{\"alice\":\"ABCDEF0123\"},\"relays\":{}}";
        assertTrue(DesignerProof.verifyMapping(nostr, "alice", "abcdef0123"));
    }
}
