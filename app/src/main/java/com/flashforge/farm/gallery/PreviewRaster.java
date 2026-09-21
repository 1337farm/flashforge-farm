package com.flashforge.farm.gallery;

/**
 * Pure-JVM core of the SpinningPreviewView software rasterizer.
 *
 * No android.* imports, so unit tests exercise the exact production path.
 * SpinningPreviewView owns the bitmap/pixel/depth buffers and the turntable
 * angles; every per-triangle pixel decision lives here.
 *
 * Camera model: pinhole at rotated view-space Z {@code -fl} looking toward
 * +Z, so nearer fragments have SMALLER rotated Z and win the depth test
 * ({@code z < depth}, buffer init {@code +MAX_VALUE}). Toward-camera faces
 * project CCW in screen space ({@code area > 0}; verified numerically over
 * 288 outward-face poses against rotated normals with the exact projection,
 * including per-vertex perspective — grazing/edge-on slivers excepted), so
 * {@link #keepTriangle(float)} culls the rest. This mirrors the build-plate
 * path, where GL culls non-CCW-front faces of the same outward soup.
 */
public final class PreviewRaster {
    private PreviewRaster() {
    }

    /** Focal length in bounding-sphere radii; 2.5 gives visible depth. */
    public static final float FOCAL_RADII = 2.5f;
    /** Half-width of the degenerate-triangle screen-area band (px^2). */
    public static final float MIN_AREA = 0.25f;
    /** Flat-shade ramp: base + diffuse * clamped Lambert term. */
    public static final float BASE_SHADE = 0.38f;
    public static final float DIFF_SHADE = 0.62f;
    /** Preview key-light direction (normalized by the caller). */
    public static final float LIGHT_X = -0.42f;
    public static final float LIGHT_Y = 0.78f;
    public static final float LIGHT_Z = 0.46f;
    /** Shade channel scales (slightly cool gray). */
    public static final int SHADE_R = 186;
    public static final int SHADE_G = 191;
    public static final int SHADE_B = 198;

    /**
     * Rotate one mesh vertex by yaw/pitch around the mesh center and project
     * it. Writes screen {@code (xs, ys)} and rotated view-space
     * {@code z2} into {@code out[3]}. Pure port of the render-loop math.
     */
    public static void projectVertex(float x, float y, float z,
            float cx, float cy, float cz,
            float cosA, float sinA, float cosT, float sinT,
            int size, float k, float fl, float[] out) {
        float px = x - cx, py = y - cy, pz = z - cz;
        float x1 = px * cosA + pz * sinA;
        float z1 = -px * sinA + pz * cosA;
        float y2 = py * cosT - z1 * sinT;
        float z2 = py * sinT + z1 * cosT;
        float p = fl / (fl + z2);
        out[0] = size / 2f + x1 * k * p;
        out[1] = size / 2f - y2 * k * p;
        out[2] = z2;
    }

    /** Rotate a unit normal by yaw/pitch; writes into {@code out[3]}. */
    public static void rotateNormal(float nx, float ny, float nz,
            float cosA, float sinA, float cosT, float sinT, float[] out) {
        float nx1 = nx * cosA + nz * sinA;
        float nz1 = -nx * sinA + nz * cosA;
        out[0] = nx1;
        out[1] = ny * cosT - nz1 * sinT;
        out[2] = ny * sinT + nz1 * cosT;
    }

    /** Signed screen-space area of (ax,ay),(bx,by),(cx,cy). */
    public static float screenArea(float ax, float ay, float bx, float by,
            float cx, float cy) {
        return (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
    }

    /**
     * Keep iff the triangle faces the camera: {@code area >= MIN_AREA}.
     * This is exactly the old two-line rule (degenerate band
     * {@code |area| < MIN_AREA} plus away-face cull {@code area <= 0})
     * fused into one predicate so the facing convention has a single home.
     */
    public static boolean keepTriangle(float area) {
        return area >= MIN_AREA;
    }

    /** Flat-shaded ARGB color for a clamped Lambert term. */
    public static int shadeColor(float diff) {
        if (diff < 0) diff = 0;
        float shade = BASE_SHADE + DIFF_SHADE * diff;
        int r = (int) (SHADE_R * shade);
        int g = (int) (SHADE_G * shade);
        int b = (int) (SHADE_B * shade);
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Rasterize soup triangle {@code tri} into {@code pixels}/{@code depth}.
     * Verbatim port of the SpinningPreviewView inner loop; {@code xs/ys/zs}
     * are caller-owned length-3 scratch (allocated once per frame), as is
     * {@code tmp} (length 6: projected vertex + rotated normal), so the hot
     * loop allocates nothing.
     */
    public static void rasterTriangle(float[] v, float[] n, int tri,
            float cx, float cy, float cz,
            float cosA, float sinA, float cosT, float sinT,
            int size, float k, float fl,
            float lx, float ly, float lz,
            int[] pixels, float[] depth,
            float[] xs, float[] ys, float[] zs, float[] tmp) {
        for (int j = 0; j < 3; j++) {
            int o = tri * 9 + j * 3;
            projectVertex(v[o], v[o + 1], v[o + 2], cx, cy, cz,
                    cosA, sinA, cosT, sinT, size, k, fl, tmp);
            xs[j] = tmp[0];
            ys[j] = tmp[1];
            zs[j] = tmp[2];
        }
        // Skip triangles truly behind the camera (pinhole at z2 = -fl, so
        // behind ⟺ fl + z2 <= 0 for every vertex). NOTE: the far half of the
        // model (z2 > 0) is NOT behind the camera — an earlier version tested
        // zs >= 0 here and silently dropped far-half fragments, which read
        // as clipping holes on-device.
        if (zs[0] + fl <= 0 && zs[1] + fl <= 0 && zs[2] + fl <= 0) return;
        float area = screenArea(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2]);
        if (!keepTriangle(area)) return;
        rotateNormal(n[tri * 3], n[tri * 3 + 1], n[tri * 3 + 2],
                cosA, sinA, cosT, sinT, tmp);
        float diff = tmp[0] * lx + tmp[1] * ly + tmp[2] * lz;
        int color = shadeColor(diff);
        int x0 = (int) Math.max(0, Math.floor(min3(xs[0], xs[1], xs[2])));
        int x1 = (int) Math.min(size - 1, Math.ceil(max3(xs[0], xs[1], xs[2])));
        int y0 = (int) Math.max(0, Math.floor(min3(ys[0], ys[1], ys[2])));
        int y1 = (int) Math.min(size - 1, Math.ceil(max3(ys[0], ys[1], ys[2])));
        float d0x = xs[1] - xs[0], d0y = ys[1] - ys[0];
        float d1x = xs[2] - xs[0], d1y = ys[2] - ys[0];
        float denom = d0x * d1y - d1x * d0y;
        if (denom > -1e-9f && denom < 1e-9f) return;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float ex = x + 0.5f - xs[0], ey = y + 0.5f - ys[0];
                float w1 = (ex * d1y - ey * d1x) / denom;
                float w2 = (d0x * ey - d0y * ex) / denom;
                float w0 = 1f - w1 - w2;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2];
                int idx = y * size + x;
                if (z < depth[idx]) {
                    depth[idx] = z;
                    pixels[idx] = color;
                }
            }
        }
    }

    static float min3(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    static float max3(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }
}
