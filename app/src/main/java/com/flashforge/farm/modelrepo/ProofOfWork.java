package com.flashforge.farm.modelrepo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

public class ProofOfWork {
    private ProofOfWork() {
    }

    public static class Solution {
        public final long nonce;
        public final byte[] digest;

        Solution(long nonce, byte[] digest) {
            this.nonce = nonce;
            this.digest = digest;
        }
    }

    public static Solution solve(byte[] pubkey, long seq, byte[] payloadHash, int bits, long maxTries)
            throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] nonceBytes = new byte[8];
        for (long nonce = 0; nonce < maxTries; nonce++) {
            sha.reset();
            sha.update(pubkey);
            sha.update(longBytes(seq));
            sha.update(payloadHash);
            ByteBuffer.wrap(nonceBytes).order(ByteOrder.BIG_ENDIAN).putLong(nonce);
            sha.update(nonceBytes);
            byte[] d = sha.digest();
            if (leadingZeroBits(d) >= bits) {
                return new Solution(nonce, d);
            }
        }
        throw new IllegalStateException("no solution within " + maxTries + " tries");
    }

    public static boolean verify(byte[] pubkey, long seq, byte[] payloadHash, long nonce, int bits)
            throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(pubkey);
        sha.update(longBytes(seq));
        sha.update(payloadHash);
        byte[] nonceBytes = new byte[8];
        ByteBuffer.wrap(nonceBytes).order(ByteOrder.BIG_ENDIAN).putLong(nonce);
        sha.update(nonceBytes);
        return leadingZeroBits(sha.digest()) >= bits;
    }

    static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).putLong(v);
        return b;
    }

    static int leadingZeroBits(byte[] d) {
        int n = 0;
        for (byte v : d) {
            int u = v & 0xFF;
            if (u == 0) {
                n += 8;
                continue;
            }
            while ((u & 0x80) == 0) {
                n++;
                u <<= 1;
            }
            break;
        }
        return n;
    }
}
