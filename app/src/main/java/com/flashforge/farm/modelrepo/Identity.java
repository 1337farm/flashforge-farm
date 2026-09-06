package com.flashforge.farm.modelrepo;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.SecureRandom;

public class Identity {
    private static final EdDSANamedCurveSpec SPEC =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    private Identity() {
    }

    public static byte[] generateSeed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    public static byte[] publicKey(byte[] seed) {
        EdDSAPrivateKey priv = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, SPEC));
        EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(priv.getAbyte(), SPEC));
        return pub.getAbyte();
    }

    public static byte[] sign(byte[] seed, byte[] message) throws Exception {
        EdDSAPrivateKey priv = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, SPEC));
        EdDSAEngine engine = new EdDSAEngine();
        engine.initSign(priv);
        engine.update(message);
        return engine.sign();
    }

    public static boolean verify(byte[] pubkey, byte[] message, byte[] signature) {
        try {
            if (pubkey == null || pubkey.length != 32 || signature == null || signature.length != 64) {
                return false;
            }
            EdDSAPublicKey pub = new EdDSAPublicKey(new EdDSAPublicKeySpec(pubkey, SPEC));
            EdDSAEngine engine = new EdDSAEngine();
            engine.initVerify(pub);
            engine.update(message);
            return engine.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }
}
