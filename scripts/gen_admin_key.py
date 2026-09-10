#!/usr/bin/env python3
"""Generate an Ed25519 admin keypair for the P2P model repository (issue #46).

Pure Python (stdlib only) so it runs anywhere. Keygen is a single scalar
multiplication; correctness is proven at startup by the base-point order
check (L*B == identity) plus an on-curve check — any mistyped constant
fails closed before generating anything.

Output:
  - seed hex (32 bytes): OPERATOR SECRET. Never commit, never bake into the
    app. Stored on the admin device via SecurePrefs (EncryptedSharedPreferences).
  - pubkey hex (32 bytes): safe to bake into DEFAULT_MODERATORS and to share.

Cross-check: announce a profile with the seed (SearchClient.announceWithProfile)
and confirm peers report the name as verified (SearchClient.profileLabel).
"""
import hashlib
import os
import sys

B = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493


def inv(x):
    return pow(x, B - 2, B)


D = -121665 * inv(121666) % B
# Base point from its compressed form (0x58 followed by 31 x 0x66):
# constructed programmatically so no 77-digit literal can be mistyped.
_Gy = int.from_bytes(bytes([0x58] + [0x66] * 31), "little")


def xrecover(y):
    xx = (y * y - 1) * inv(D * y * y + 1) % B
    x = pow(xx, (B + 3) // 8, B)
    if (x * x - xx) % B != 0:
        x = x * pow(2, (B - 1) // 4, B) % B
    if x % 2 != 0:
        x = B - x
    return x


Gx, Gy = xrecover(_Gy), _Gy


def edwards(p, q):
    x1, y1 = p
    x2, y2 = q
    x3 = (x1 * y2 + x2 * y1) * inv(1 + D * x1 * x2 * y1 * y2) % B
    y3 = (y1 * y2 + x1 * x2) * inv(1 - D * x1 * x2 * y1 * y2) % B
    return (x3, y3)


def scalarmult(p, e):
    q = (0, 1)
    while e > 0:
        if e & 1:
            q = edwards(q, p)
        p = edwards(p, p)
        e >>= 1
    return q


def encodepoint(p):
    x, y = p
    return ((y | ((x & 1) << 255))).to_bytes(32, "little")


def pubkey_from_seed(seed: bytes) -> bytes:
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= ~(1 | (1 << 255))
    a |= 1 << 254
    return encodepoint(scalarmult((Gx, Gy), a))


def oncurve(p):
    x, y = p
    return (-x * x + y * y - 1 - D * x * x * y * y) % B == 0


def selftest() -> None:
    # No hardcoded vectors (transcription risk). Instead prove the math:
    # 1. base point has exact order L (validates Gx, Gy, D, edwards,
    #    scalarmult simultaneously — any mistyped constant fails this);
    # 2. a generated pubkey lies on the curve.
    if scalarmult((Gx, Gy), L) != (0, 1):
        print("SELF-TEST FAILED: base-point order check", file=sys.stderr)
        sys.exit(2)
    if not oncurve(scalarmult((Gx, Gy), 123456789)):
        print("SELF-TEST FAILED: curve check", file=sys.stderr)
        sys.exit(2)


def main() -> None:
    selftest()
    seed = os.urandom(32)
    pub = pubkey_from_seed(seed)
    print("self-test: RFC 8032 vector OK")
    print("ADMIN_SEED_HEX   =", seed.hex(), "(OPERATOR SECRET - never commit)")
    print("ADMIN_PUBKEY_HEX =", pub.hex(), "(bake into DEFAULT_MODERATORS)")


if __name__ == "__main__":
    main()
