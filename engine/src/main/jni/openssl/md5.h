#ifndef OPENSSL_MD5_COMPAT_H
#define OPENSSL_MD5_COMPAT_H

#include <cstdint>
#include <cstring>
#include <cstdio>

#define MD5_DIGEST_LENGTH 16

typedef struct MD5state_st {
    uint32_t lo, hi;
    uint32_t a, b, c, d;
    unsigned char buffer[64];
    uint32_t block[16];
} MD5_CTX;

namespace md5_detail {

#define MD5_F(x, y, z) ((z) ^ ((x) & ((y) ^ (z))))
#define MD5_G(x, y, z) ((y) ^ ((z) & ((x) ^ (y))))
#define MD5_H(x, y, z) (((x) ^ (y)) ^ (z))
#define MD5_H2(x, y, z) ((x) ^ ((y) ^ (z)))
#define MD5_I(x, y, z) ((y) ^ ((x) | ~(z)))

#define MD5_STEP(f, a, b, c, d, x, t, s) \
    (a) += f((b), (c), (d)) + (x) + (t); \
    (a) = (((a) << (s)) | (((a) & 0xffffffff) >> (32 - (s)))); \
    (a) += (b);

inline const void *body(MD5_CTX *ctx, const void *data, unsigned long size) {
    const unsigned char *ptr = (const unsigned char *) data;
    uint32_t a, b, c, d;
    uint32_t saved_a, saved_b, saved_c, saved_d;

    a = ctx->a; b = ctx->b; c = ctx->c; d = ctx->d;

    do {
        saved_a = a; saved_b = b; saved_c = c; saved_d = d;

        auto set = [&](int n) -> uint32_t {
            return ctx->block[n] = (uint32_t) ptr[n * 4] | ((uint32_t) ptr[n * 4 + 1] << 8) |
                                   ((uint32_t) ptr[n * 4 + 2] << 16) | ((uint32_t) ptr[n * 4 + 3] << 24);
        };
        auto get = [&](int n) -> uint32_t { return ctx->block[n]; };

        MD5_STEP(MD5_F, a, b, c, d, set(0), 0xd76aa478, 7)
        MD5_STEP(MD5_F, d, a, b, c, set(1), 0xe8c7b756, 12)
        MD5_STEP(MD5_F, c, d, a, b, set(2), 0x242070db, 17)
        MD5_STEP(MD5_F, b, c, d, a, set(3), 0xc1bdceee, 22)
        MD5_STEP(MD5_F, a, b, c, d, set(4), 0xf57c0faf, 7)
        MD5_STEP(MD5_F, d, a, b, c, set(5), 0x4787c62a, 12)
        MD5_STEP(MD5_F, c, d, a, b, set(6), 0xa8304613, 17)
        MD5_STEP(MD5_F, b, c, d, a, set(7), 0xfd469501, 22)
        MD5_STEP(MD5_F, a, b, c, d, set(8), 0x698098d8, 7)
        MD5_STEP(MD5_F, d, a, b, c, set(9), 0x8b44f7af, 12)
        MD5_STEP(MD5_F, c, d, a, b, set(10), 0xffff5bb1, 17)
        MD5_STEP(MD5_F, b, c, d, a, set(11), 0x895cd7be, 22)
        MD5_STEP(MD5_F, a, b, c, d, set(12), 0x6b901122, 7)
        MD5_STEP(MD5_F, d, a, b, c, set(13), 0xfd987193, 12)
        MD5_STEP(MD5_F, c, d, a, b, set(14), 0xa679438e, 17)
        MD5_STEP(MD5_F, b, c, d, a, set(15), 0x49b40821, 22)

        MD5_STEP(MD5_G, a, b, c, d, get(1), 0xf61e2562, 5)
        MD5_STEP(MD5_G, d, a, b, c, get(6), 0xc040b340, 9)
        MD5_STEP(MD5_G, c, d, a, b, get(11), 0x265e5a51, 14)
        MD5_STEP(MD5_G, b, c, d, a, get(0), 0xe9b6c7aa, 20)
        MD5_STEP(MD5_G, a, b, c, d, get(5), 0xd62f105d, 5)
        MD5_STEP(MD5_G, d, a, b, c, get(10), 0x02441453, 9)
        MD5_STEP(MD5_G, c, d, a, b, get(15), 0xd8a1e681, 14)
        MD5_STEP(MD5_G, b, c, d, a, get(4), 0xe7d3fbc8, 20)
        MD5_STEP(MD5_G, a, b, c, d, get(9), 0x21e1cde6, 5)
        MD5_STEP(MD5_G, d, a, b, c, get(14), 0xc33707d6, 9)
        MD5_STEP(MD5_G, c, d, a, b, get(3), 0xf4d50d87, 14)
        MD5_STEP(MD5_G, b, c, d, a, get(8), 0x455a14ed, 20)
        MD5_STEP(MD5_G, a, b, c, d, get(13), 0xa9e3e905, 5)
        MD5_STEP(MD5_G, d, a, b, c, get(2), 0xfcefa3f8, 9)
        MD5_STEP(MD5_G, c, d, a, b, get(7), 0x676f02d9, 14)
        MD5_STEP(MD5_G, b, c, d, a, get(12), 0x8d2a4c8a, 20)

        MD5_STEP(MD5_H, a, b, c, d, get(5), 0xfffa3942, 4)
        MD5_STEP(MD5_H2, d, a, b, c, get(8), 0x8771f681, 11)
        MD5_STEP(MD5_H, c, d, a, b, get(11), 0x6d9d6122, 16)
        MD5_STEP(MD5_H2, b, c, d, a, get(14), 0xfde5380c, 23)
        MD5_STEP(MD5_H, a, b, c, d, get(1), 0xa4beea44, 4)
        MD5_STEP(MD5_H2, d, a, b, c, get(4), 0x4bdecfa9, 11)
        MD5_STEP(MD5_H, c, d, a, b, get(7), 0xf6bb4b60, 16)
        MD5_STEP(MD5_H2, b, c, d, a, get(10), 0xbebfbc70, 23)
        MD5_STEP(MD5_H, a, b, c, d, get(13), 0x289b7ec6, 4)
        MD5_STEP(MD5_H2, d, a, b, c, get(0), 0xeaa127fa, 11)
        MD5_STEP(MD5_H, c, d, a, b, get(3), 0xd4ef3085, 16)
        MD5_STEP(MD5_H2, b, c, d, a, get(6), 0x04881d05, 23)
        MD5_STEP(MD5_H, a, b, c, d, get(9), 0xd9d4d039, 4)
        MD5_STEP(MD5_H2, d, a, b, c, get(12), 0xe6db99e5, 11)
        MD5_STEP(MD5_H, c, d, a, b, get(15), 0x1fa27cf8, 16)
        MD5_STEP(MD5_H2, b, c, d, a, get(2), 0xc4ac5665, 23)

        MD5_STEP(MD5_I, a, b, c, d, get(0), 0xf4292244, 6)
        MD5_STEP(MD5_I, d, a, b, c, get(7), 0x432aff97, 10)
        MD5_STEP(MD5_I, c, d, a, b, get(14), 0xab9423a7, 15)
        MD5_STEP(MD5_I, b, c, d, a, get(5), 0xfc93a039, 21)
        MD5_STEP(MD5_I, a, b, c, d, get(12), 0x655b59c3, 6)
        MD5_STEP(MD5_I, d, a, b, c, get(3), 0x8f0ccc92, 10)
        MD5_STEP(MD5_I, c, d, a, b, get(10), 0xffeff47d, 15)
        MD5_STEP(MD5_I, b, c, d, a, get(1), 0x85845dd1, 21)
        MD5_STEP(MD5_I, a, b, c, d, get(8), 0x6fa87e4f, 6)
        MD5_STEP(MD5_I, d, a, b, c, get(15), 0xfe2ce6e0, 10)
        MD5_STEP(MD5_I, c, d, a, b, get(6), 0xa3014314, 15)
        MD5_STEP(MD5_I, b, c, d, a, get(13), 0x4e0811a1, 21)
        MD5_STEP(MD5_I, a, b, c, d, get(4), 0xf7537e82, 6)
        MD5_STEP(MD5_I, d, a, b, c, get(11), 0xbd3af235, 10)
        MD5_STEP(MD5_I, c, d, a, b, get(2), 0x2ad7d2bb, 15)
        MD5_STEP(MD5_I, b, c, d, a, get(9), 0xeb86d391, 21)

        a += saved_a; b += saved_b; c += saved_c; d += saved_d;
        ptr += 64;
    } while (size -= 64);

    ctx->a = a; ctx->b = b; ctx->c = c; ctx->d = d;
    return ptr;
}

#undef MD5_F
#undef MD5_G
#undef MD5_H
#undef MD5_H2
#undef MD5_I
#undef MD5_STEP

} // namespace md5_detail

inline int MD5_Init(MD5_CTX *ctx) {
    ctx->a = 0x67452301;
    ctx->b = 0xefcdab89;
    ctx->c = 0x98badcfe;
    ctx->d = 0x10325476;
    ctx->lo = 0;
    ctx->hi = 0;
    return 1;
}

inline int MD5_Update(MD5_CTX *ctx, const void *data, unsigned long size) {
    uint32_t saved_lo;
    unsigned long used, free;

    saved_lo = ctx->lo;
    if ((ctx->lo = (saved_lo + size) & 0x1fffffff) < saved_lo) {
        ctx->hi++;
    }
    ctx->hi += (uint32_t)(size >> 29);

    used = saved_lo & 0x3f;
    if (used) {
        free = 64 - used;
        if (size < free) {
            memcpy(&ctx->buffer[used], data, size);
            return 1;
        }
        memcpy(&ctx->buffer[used], data, free);
        data = (const unsigned char *) data + free;
        size -= free;
        md5_detail::body(ctx, ctx->buffer, 64);
    }

    if (size >= 64) {
        data = md5_detail::body(ctx, data, size & ~(unsigned long) 0x3f);
        size &= 0x3f;
    }

    memcpy(ctx->buffer, data, size);
    return 1;
}

inline int MD5_Final(unsigned char *result, MD5_CTX *ctx) {
    unsigned long used, free;
    used = ctx->lo & 0x3f;
    ctx->buffer[used++] = 0x80;
    free = 64 - used;

    if (free < 8) {
        memset(&ctx->buffer[used], 0, free);
        md5_detail::body(ctx, ctx->buffer, 64);
        used = 0;
        free = 64;
    }

    memset(&ctx->buffer[used], 0, free - 8);

    ctx->lo <<= 3;
    ctx->buffer[56] = ctx->lo;
    ctx->buffer[57] = ctx->lo >> 8;
    ctx->buffer[58] = ctx->lo >> 16;
    ctx->buffer[59] = ctx->lo >> 24;
    ctx->buffer[60] = ctx->hi;
    ctx->buffer[61] = ctx->hi >> 8;
    ctx->buffer[62] = ctx->hi >> 16;
    ctx->buffer[63] = ctx->hi >> 24;

    md5_detail::body(ctx, ctx->buffer, 64);

    result[0] = ctx->a;
    result[1] = ctx->a >> 8;
    result[2] = ctx->a >> 16;
    result[3] = ctx->a >> 24;
    result[4] = ctx->b;
    result[5] = ctx->b >> 8;
    result[6] = ctx->b >> 16;
    result[7] = ctx->b >> 24;
    result[8] = ctx->c;
    result[9] = ctx->c >> 8;
    result[10] = ctx->c >> 16;
    result[11] = ctx->c >> 24;
    result[12] = ctx->d;
    result[13] = ctx->d >> 8;
    result[14] = ctx->d >> 16;
    result[15] = ctx->d >> 24;

    return 1;
}

#endif // OPENSSL_MD5_COMPAT_H
