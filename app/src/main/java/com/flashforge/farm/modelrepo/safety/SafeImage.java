package com.flashforge.farm.modelrepo.safety;

public final class SafeImage {
    private SafeImage() {
    }

    public static class Dims {
        public final int w;
        public final int h;

        Dims(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    public static Dims dims(byte[] b) {
        if (b == null || b.length < 24 || b.length > SafetyPolicy.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("image size out of bounds");
        }
        Dims d = tryPng(b);
        if (d == null) {
            d = tryJpeg(b);
        }
        if (d == null) {
            d = tryWebp(b);
        }
        if (d == null) {
            throw new IllegalArgumentException("unknown image type");
        }
        checkBudget(d);
        return d;
    }

    public static void checkBudget(Dims d) {
        if (d.w <= 0 || d.h <= 0) {
            throw new IllegalArgumentException("bad image dims");
        }
        long pixels = (long) d.w * (long) d.h;
        if (pixels > SafetyPolicy.MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("image too large: " + d.w + "x" + d.h);
        }
    }

    private static Dims tryPng(byte[] b) {
        if (b.length < 24 || b[0] != (byte) 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        int w = be32(b, 16);
        int h = be32(b, 20);
        if (w <= 0 || h <= 0 || w > 100000 || h > 100000) {
            return null;
        }
        return new Dims(w, h);
    }

    private static Dims tryJpeg(byte[] b) {
        if (b.length < 4 || b[0] != (byte) 0xFF || b[1] != (byte) 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 4 < b.length) {
            if (b[i] != (byte) 0xFF) {
                return null;
            }
            int m = b[i + 1] & 0xFF;
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD9)) {
                i += 2;
                continue;
            }
            if (m == 0xD9) {
                return null;
            }
            int len = be16(b, i + 2);
            if (len < 2 || i + len > b.length) {
                return null;
            }
            if ((m == 0xC0 || m == 0xC1 || m == 0xC2 || m == 0xC3) && len >= 7) {
                int h = be16(b, i + 5);
                int w = be16(b, i + 7);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                return new Dims(w, h);
            }
            i += 2 + len;
        }
        return null;
    }

    private static Dims tryWebp(byte[] b) {
        if (b.length < 16 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return null;
        }
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'X' && b.length >= 30) {
            int w = (le24(b, 24) & 0xFFFFFF) + 1;
            int h = (le24(b, 27) & 0xFFFFFF) + 1;
            return new Dims(w, h);
        }
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == ' ') {
            int p = 20;
            while (p + 10 < b.length) {
                if (b[p] == (byte) 0x9D && b[p + 1] == 0x01 && b[p + 2] == 0x2A) {
                    int w = le16(b, p + 3) & 0x3FFF;
                    int h = le16(b, p + 5) & 0x3FFF;
                    return new Dims(w, h);
                }
                p++;
                if (p > 40) {
                    break;
                }
            }
            return null;
        }
        if (b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'L' && b.length >= 25) {
            if (b[20] != 0x2F) {
                return null;
            }
            long v = le32(b, 21) & 0xFFFFFFFFL;
            int w = (int) (v & 0x3FFF) + 1;
            int h = (int) ((v >> 14) & 0x3FFF) + 1;
            return new Dims(w, h);
        }
        return null;
    }

    private static int be16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16);
    }

    private static long le32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF)) | ((long) (b[i + 1] & 0xFF) << 8)
                | ((long) (b[i + 2] & 0xFF) << 16) | ((long) (b[i + 3] & 0xFF) << 24);
    }
}