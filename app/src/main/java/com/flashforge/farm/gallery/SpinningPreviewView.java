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
    private static final int MAX_BUF = 320;
    /** Slow turntable: full revolution in ~12s (was STEP/frame ~2s/rev). Negative:
     * screen X is negated (camera looks +Z, so +X is screen-left), and the
     * turntable must still appear to rotate the same way it always has. */
    private static final float AUTO_SPEED = -0.52f;
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
    private int bufSize;
    private boolean running;
    private long lastFrameMs;
    private long resumeAtMs;
    private final Paint paint = new Paint();
    private final RectF dstRect = new RectF();

    /** Shared raster workers: full-mesh CPU raster must never run on the UI
     * thread (high-poly models froze scrolling). Two workers bound total
     * background raster cost; per-view coalescing drops stale frames. */
    private static final java.util.concurrent.ExecutorService RENDER_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "gallery-raster");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private int renderGen = 0;
    private volatile boolean renderInFlight = false;
    /** Angles of the last kicked frame: re-kick whenever the view moved,
     * including mid-drag (otherwise dragging freezes on a stale bitmap). */
    private float renderedYaw = Float.NaN;
    private float renderedPitch = Float.NaN;

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
        renderGen++;
        if (mesh != null && mesh.triCount > 0) {
            float[] b = new float[6];
            mesh.bounds(b);
            cx = (b[0] + b[3]) / 2;
            cy = (b[1] + b[4]) / 2;
            cz = (b[2] + b[5]) / 2;
            float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
            radius = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
            if (radius <= 0) radius = 1;
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
        renderGen++;
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
                    // Grab-and-drag feel: the point under the finger follows
                    // the finger. Screen X is negated (camera looks +Z, so
                    // model +X is screen-left): drag-right must move the front
                    // face right, which needs yaw to increase
                    // (sx = c - x1*k*p, dx1/dyaw < 0 on the camera side).
                    // Drag-down must move it down (pitch decreases).
                    yaw += dx * 0.012f;
                    pitch = Math.max(-1.2f, Math.min(0.3f, pitch - dy * 0.012f));
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
        if (bitmap != null) {
            float s = Math.min(w, h);
            float left = (w - s) / 2f;
            float top = (h - s) / 2f;
            dstRect.set(left, top, left + s, top + s);
            canvas.drawBitmap(bitmap, null, dstRect, paint);
        }
        if (mesh != null && mesh.triCount > 0
                && (bitmap == null || bufSize != size
                    || yaw != renderedYaw || pitch != renderedPitch)) {
            kickRender(size);
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

    /** Rasterize one frame off the UI thread; stale results are dropped. */
    private void kickRender(int size) {
        if (renderInFlight) return;
        final GalleryMesh m = mesh;
        if (m == null || m.triCount <= 0) return;
        renderInFlight = true;
        final float yaw0 = yaw, pitch0 = pitch;
        renderedYaw = yaw0;
        renderedPitch = pitch0;
        final float cx0 = cx, cy0 = cy, cz0 = cz, radius0 = radius;
        final int gen = renderGen;
        RENDER_POOL.execute(() -> {
            int[] px = new int[size * size];
            float[] dp = new float[size * size];
            for (int i = 0; i < px.length; i++) {
                px[i] = 0;
                // Depth buffer stores rotated view-space Z: the camera sits on
                // the -Z side (p = fl/(fl+z2)), so nearer fragments have SMALLER
                // z2 and win with z < depth. (-z2 with either test, and z2 with
                // z > depth, both invert this and show inside-out geometry.)
                dp[i] = Float.MAX_VALUE;
            }
            float cosA = (float) Math.cos(yaw0), sinA = (float) Math.sin(yaw0);
            float cosT = (float) Math.cos(pitch0), sinT = (float) Math.sin(pitch0);
            float lx = -0.42f, ly = 0.78f, lz = 0.46f;
            float llen = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            lx /= llen;
            ly /= llen;
            lz /= llen;
            float k = (size / 2f - 4f) / radius0;
            // True perspective divide (not orthographic): closer verts project
            // larger. fl ~2.5 radii gives visible depth; 6+ radii looks flat.
            float fl = 2.5f * radius0;
            float[] v = m.xyz;
            float[] n = m.normals;
            float[] xs = new float[3], ys = new float[3], zs = new float[3];
            float[] tmp = new float[6];
            for (int t = 0; t < m.triCount; t++) {
                PreviewRaster.rasterTriangle(v, n, t, cx0, cy0, cz0,
                        cosA, sinA, cosT, sinT, size, k, fl,
                        lx, ly, lz, px, dp, xs, ys, zs, tmp);
            }
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bm.setPixels(px, 0, size, 0, 0, size, size);
            post(() -> {
                renderInFlight = false;
                if (gen != renderGen || m != mesh) {
                    bm.recycle();
                    return;
                }
                Bitmap old = bitmap;
                bitmap = bm;
                bufSize = size;
                if (old != null && old != bm) old.recycle();
                invalidate();
            });
        });
    }
}
