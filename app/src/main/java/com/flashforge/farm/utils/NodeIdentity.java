package com.flashforge.farm.utils;

import android.content.Context;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;

/**
 * Stable identity for this phone's iroh node.
 *
 * <p>The node secret is 32 random bytes in an app-private file. The NodeId is the
 * standard Ed25519 public key derived from that seed (lowercase hex), so Java and
 * the Rust engine compute the identical NodeId from the same seed file. The engine
 * must be initialized with {@link #getOrCreateSeed} bytes; until then the Java
 * derivation is the source of truth for USB pairing payloads.
 */
public final class NodeIdentity {
    private NodeIdentity() {
    }

    private static final String SEED_FILE = "node_seed";
    private static final EdDSAParameterSpec ED25519 = EdDSANamedCurveTable.ED_25519_CURVE_SPEC;

    /** Load the node seed, generating and persisting it on first use. */
    public static synchronized byte[] getOrCreateSeed(Context ctx) {
        File f = new File(ctx.getFilesDir(), SEED_FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] seed = new byte[32];
                int read = 0;
                while (read < 32) {
                    int n = in.read(seed, read, 32 - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read == 32) return seed;
            } catch (Exception ignored) {
            }
        }
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(seed);
        } catch (Exception ignored) {
        }
        return seed;
    }

    /** This phone's iroh NodeId: Ed25519 public key of the node seed, lowercase hex. */
    public static String nodeIdHex(Context ctx) {
        byte[] seed = getOrCreateSeed(ctx);
        EdDSAPrivateKey priv = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, ED25519));
        return toHex(priv.getAbyte());
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
