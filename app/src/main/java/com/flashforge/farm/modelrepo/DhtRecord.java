package com.flashforge.farm.modelrepo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class DhtRecord {
    // NOTE — transport gap (P3): this codec builds and verifies BEP44
    // mutable items, but no DHT carrier exists yet. Options under evaluation:
    // (a) minimal Mainline UDP client, (b) Iroh-internal DHT via Rust bridge.
    // Salted per-keyword index records are additionally deferred pending
    // interop verification of the salt-target construction against a
    // reference client. Until then: unsalted publisher pointers only.
    private DhtRecord() {
    }

    public static class MutableItem {
        public final byte[] pubkey;
        public final long seq;
        public final byte[] value;
        public final byte[] signature;

        public MutableItem(byte[] pubkey, long seq, byte[] value, byte[] signature) {
            this.pubkey = pubkey;
            this.seq = seq;
            this.value = value;
            this.signature = signature;
        }
    }

    public static byte[] signBuffer(byte[] bencodedValue, long seq) {
        byte[] v = bencodedValue;
        String head = "3:seqi" + seq + "e1:v" + v.length + ":";
        byte[] h = head.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[h.length + v.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(v, 0, out, h.length, v.length);
        return out;
    }

    public static MutableItem sign(byte[] seed, long seq, Map<String, Object> value) throws Exception {
        byte[] v = Bencode.encode(stringifyMap(value));
        byte[] sig = Identity.sign(seed, signBuffer(v, seq));
        return new MutableItem(Identity.publicKey(seed), seq, v, sig);
    }

    public static boolean verify(MutableItem item) {
        if (item.pubkey == null || item.pubkey.length != 32
                || item.signature == null || item.signature.length != 64
                || item.value == null || item.value.length == 0
                || item.value.length > 1000 || item.seq < 0) {
            return false;
        }
        return Identity.verify(item.pubkey, signBuffer(item.value, item.seq), item.signature);
    }

    public static byte[] target(byte[] pubkey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return sha1.digest(pubkey);
    }

    public static Map<String, Object> catalogPointer(String ticket, long updated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", ticket);
        m.put("updated", updated);
        return m;
    }

    private static Map<String, Object> stringifyMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public static boolean equalBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
