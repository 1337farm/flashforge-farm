package com.flashforge.farm.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public class SpinningPreviewView extends View {
    private static final int MAX_BUF = 144;
    /** Slow turntable: full revolution in ~12s (was STEP/frame ~2s/rev). */
    private static final float AUTO_SPEED = 0.52f;
    private static final float DEFAULT_TILT = -0.35f;
    /** Preview frame cap: tiny previews don't need 60fps of CPU rasterizing. */
    private static final long FRAME_MS = 66;
    /** Idle delay before auto-rotate resumes after a drag. */
    private static final long RESUME_MS = 2500;

    private GalleryMesh mesh;
    private float[] normals;
    private float cx, cy, cz, radius;
    private float yaw;
    private float pitch = DEFAULT_TILT;

    private Bitmap bitmap;
    private int[] pixels;
    private float[] depth;
    private int bufSize;
    private boolean running;
    private long lastFrameMs;
    private long resumeAtMs;
    private final Paint paint = new Paint();
    private final RectF dstRect = new RectF();

    private boolean dragging;
    private boolean moved;
    private float lastX, lastY, downX, downY;
    private int touchSlop = -1;
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (dragging && !moved && getParent() instanceof View) {
                ((View) getParent()).performLongClick();
            }
        }
    };

    public SpinningPreviewView(Context context) {
        super(context);
        init();
    }

    public SpinningPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setFilterBitmap(true);
    }

    public void setMesh(GalleryMesh mesh) {
        this.mesh = mesh;
        this.normals = null;
        if (mesh != null && mesh.triCount > 0) {
            float[] b = new float[6];
            mesh.bounds(b);
            cx = (b[0] + b[3]) / 2;
            cy = (b[1] + b[4]) / 2;
            cz = (b[2] + b[5]) / 2;
            float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
            radius = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
            if (radius <= 0) radius = 1;
            normals = new float[mesh.triCount * 3];
            float[] v = mesh.xyz;
            for (int t = 0; t < mesh.triCount; t++) {
                int o = t * 9;
                float ux = v[o + 3] - v[o], uy = v[o + 4] - v[o + 1], uz = v[o + 5] - v[o + 2];
                float wx = v[o + 6] - v[o], wy = v[o + 7] - v[o + 1], wz = v[o + 8] - v[o + 2];
                float nx = uy * wz - uz * wy;
                float ny = uz * wx - ux * wz;
                float nz = ux * wy - uy * wx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-12f) {
                    nx = 0;
                    ny = 0;
                    nz = 0;
                } else {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
                normals[t * 3] = nx;
                normals[t * 3 + 1] = ny;
                normals[t * 3 + 2] = nz;
            }
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameMs = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (touchSlop < 0) touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                moved = false;
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                resumeAtMs = Long.MAX_VALUE;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) break;
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (!moved && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(longPress);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (moved) {
                    yaw += dx * 0.012f;
                    pitch = Math.max(-1.2f, Math.min(0.3f, pitch + dy * 0.012f));
                    resumeAtMs = android.os.SystemClock.uptimeMillis() + RESUME_MS;
                    lastX = event.getX();
                    lastY = event.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging && !moved && getParent() instanceof View) {
                    ((View) getParent()).performClick();
                }
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                resumeAtMs = android.os.SystemClock.uptimeMillis() + RESUME_MS;
                postInvalidateOnAnimation();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                resumeAtMs = android.os.SystemClock.uptimeMillis() + RESUME_MS;
                postInvalidateOnAnimation();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int size = Math.min(Math.min(w, h), MAX_BUF);
        if (size < 8) size = 8;
        if (bitmap == null || bufSize != size) {
            bufSize = size;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            pixels = new int[size * size];
            depth = new float[size * size];
        }
        if (mesh != null && mesh.triCount > 0) {
            render(size);
            float s = Math.min(w, h);
            float left = (w - s) / 2f;
            float top = (h - s) / 2f;
            dstRect.set(left, top, left + s, top + s);
            canvas.drawBitmap(bitmap, null, dstRect, paint);
        }
        if (running && isShown()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (lastFrameMs == 0) lastFrameMs = now;
            if (!dragging && now >= resumeAtMs) {
                yaw += AUTO_SPEED * (now - lastFrameMs) / 1000f;
            }
            lastFrameMs = now;
            postInvalidateDelayed(FRAME_MS);
        }
    }

    private void render(int size) {
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0;
            depth[i] = Float.MAX_VALUE;
        }
        float cosA = (float) Math.cos(yaw), sinA = (float) Math.sin(yaw);
        float cosT = (float) Math.cos(pitch), sinT = (float) Math.sin(pitch);
        float lx = -0.42f, ly = 0.78f, lz = 0.46f;
        float llen = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= llen;
        ly /= llen;
        lz /= llen;
        float k = (size / 2f - 4f) / radius;
        // True perspective divide (not orthographic): closer verts project
        // larger. fl ~2.5 radii gives visible depth; 6+ radii looks flat.
        float fl = 2.5f * radius;
        float[] v = mesh.xyz;
        float[] xs = new float[3], ys = new float[3], zs = new float[3];
        for (int t = 0; t < mesh.triCount; t++) {
            for (int j = 0; j < 3; j++) {
                int o = t * 9 + j * 3;
                float x = v[o] - cx, y = v[o + 1] - cy, z = v[o + 2] - cz;
                float x1 = x * cosA + z * sinA;
                float z1 = -x * sinA + z * cosA;
                float y2 = y * cosT - z1 * sinT;
                float z2 = y * sinT + z1 * cosT;
                float p = fl / (fl + z2);
                xs[j] = size / 2f + x1 * k * p;
                ys[j] = size / 2f - y2 * k * p;
                zs[j] = -z2;
            }
            float area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]);
            if (area > -0.25f && area < 0.25f) continue;
            float nx = normals[t * 3], ny = normals[t * 3 + 1], nz = normals[t * 3 + 2];
            float nx1 = nx * cosA + nz * sinA;
            float nz1 = -nx * sinA + nz * cosA;
            float ny2 = ny * cosT - nz1 * sinT;
            float nz2 = ny * sinT + nz1 * cosT;
            float diff = nx1 * lx + ny2 * ly + nz2 * lz;
            if (diff < 0) diff = 0;
            float shade = 0.38f + 0.62f * diff;
            int r = (int) (186 * shade);
            int g = (int) (191 * shade);
            int b = (int) (198 * shade);
            if (r > 255) r = 255;
            if (g > 255) g = 255;
            if (b > 255) b = 255;
            int color = 0xFF000000 | (r << 16) | (g << 8) | b;
            int x0 = (int) Math.max(0, Math.floor(min3(xs[0], xs[1], xs[2])));
            int x1 = (int) Math.min(size - 1, Math.ceil(max3(xs[0], xs[1], xs[2])));
            int y0 = (int) Math.max(0, Math.floor(min3(ys[0], ys[1], ys[2])));
            int y1 = (int) Math.min(size - 1, Math.ceil(max3(ys[0], ys[1], ys[2])));
            float d0x = xs[1] - xs[0], d0y = ys[1] - ys[0];
            float d1x = xs[2] - xs[0], d1y = ys[2] - ys[0];
            float denom = d0x * d1y - d1x * d0y;
            if (denom > -1e-9f && denom < 1e-9f) continue;
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
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
    }

    private static float min3(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    private static float max3(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }
}
