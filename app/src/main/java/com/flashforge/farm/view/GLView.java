package com.flashforge.farm.view;

import com.flashforge.farm.Bus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Region;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import java.nio.IntBuffer;
import java.util.ArrayList;

import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.events.LongClickTranslationEvent;
import com.flashforge.farm.render.GLRenderer;
import com.flashforge.farm.slic3r.GLModel;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.Vec3d;
import com.flashforge.farm.utils.ViewUtils;

public class GLView extends GLSurfaceView implements IThemeView {
    private static final long FILL_BED_STEP_DELAY_MS = 16L;

    private GLRenderer renderer;

    private float lastX, lastY;
    private float lastLength;
    private int touchSlop;
    private float pixelsPerInch = 160f;

    // Bed-plane grab point for 1:1 panning: the world point under the gesture
    // midpoint when the drag started. Null when the ray misses the plane
    // (camera looking horizontal) or no pan is active.
    private Vec3d panGrabPoint = null;
    // Last pinch finger positions in view pixels (UI thread only).
    private float lastPinchX0, lastPinchY0, lastPinchX1, lastPinchY1;
    private boolean hasLastPinch = false;
    // World point pinned under the pinch midpoint. Written and read on the GL
    // thread only (inside queued runnables); volatile for UI visibility.
    private volatile Vec3d pinchAnchor = null;
    // Last midpoint in render pixels, for the physical-gain pan fallback.
    private float lastPanX, lastPanY;
    private boolean hasLastPan = false;

    private boolean fromTwoPointers;
    private boolean onePointerGesture;
    private boolean twoPointerGesture;
    private boolean longClickGesture;
    private boolean longClickMoved;
    private boolean isScaling;
    private boolean fillBedInProgress;
    private boolean primeTowerGesture;
    private int longClickTargetObject = -1;

    private OnModelLongPressListener onModelLongPressListener;
    private OnBedLongPressListener onBedLongPressListener;

    // When true and the renderer is in paint mode, a single finger paints instead of orbiting.
    private boolean paintBrushActive;

    public void setPaintBrushActive(boolean active) {
        this.paintBrushActive = active;
    }

    public boolean isPaintBrushActive() {
        return paintBrushActive;
    }

    private void paintAtScreen(float x, float y) {
        float renderScale = Prefs.getRenderScale();
        queueEvent(() -> {
            renderer.paintAt(x * renderScale, y * renderScale);
            requestRender();
        });
    }

    public interface OnModelLongPressListener {
        void onModelLongPress(GLView view, int objectIndex, float x, float y);
    }

    public interface OnBedLongPressListener {
        void onBedLongPress(GLView view, float x, float y);
    }

    public interface FillBedCallback {
        void onFillBedComplete(int addedCopies);
    }

    private Vec3d tempVec = new Vec3d();
    private Vec3d longClickOffset = new Vec3d();
    private Vec3d longClickTranslation = new Vec3d();
    private ArrayList<GLModel.HitResult> longClickHitResults = new ArrayList<>();
    private long lastActionTime = System.currentTimeMillis();
    private Runnable longClick = () -> {
        if (longClickTargetObject == -1 || getRenderer().getModel() == null || getRenderer().getBed() == null) return;

        float renderScale = Prefs.getRenderScale();
        getRenderer().getBed().getRaycaster().raycast(getRenderer(), longClickHitResults, lastX * renderScale, lastY * renderScale);
        if (!longClickHitResults.isEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            longClickGesture = true;
            longClickMoved = false;
            longClickTranslation.set(0, 0, 0);

            if (getRenderer().focusForDrag(longClickTargetObject)) {
                requestRender();
            }

            GLModel.HitResult result = longClickHitResults.get(0);
            getRenderer().getModel().getTranslation(longClickTargetObject, tempVec);
            longClickOffset.x = result.position.x - tempVec.x;
            longClickOffset.y = result.position.y - tempVec.y;
        }
    };

    private Runnable bedLongClick = () -> {
        if (getRenderer().getModel() == null || getRenderer().getBed() == null) return;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (onBedLongPressListener != null) {
            onBedLongPressListener.onBedLongPress(this, lastX, lastY);
        }
    };

    private Path path = new Path();
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint xferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private TextPaint invalidBedText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout invalidBedDescriptionLayout;

    private float lastScale;

    private long lastDraw;
    private float invalidOffset;

    public GLView(Context context) {
        super(context);

        xferPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        setEGLContextClientVersion(3);
        renderer = new GLRenderer(this);

        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        try {
            float xdpi = context.getResources().getDisplayMetrics().xdpi;
            if (xdpi > 0) pixelsPerInch = xdpi;
        } catch (Exception ignored) {
        }
        setWillNotDraw(false);
    }

    /** Degrees of orbit per inch of finger travel (trackball feel). */
    private float rotateDegreesPerInch() {
        return 90f * (Prefs.getCameraSensitivity() / 5f);
    }

    /** Anchor a pan gesture: remember the bed-plane point under (x, y) in view pixels. */
    private void grabPanAnchor(float x, float y) {
        float rs = Prefs.getRenderScale();
        panGrabPoint = renderer.screenToBedPlane(x * rs, y * rs);
        lastPanX = x * rs;
        lastPanY = y * rs;
        hasLastPan = true;
    }

    /**
     * Drag the world so the grabbed bed-plane point follows the finger at
     * (x, y) in view pixels (exact 1:1). Falls back to a physical-gain screen
     * pan when the ray misses the plane.
     */
    private void dragPanTo(float x, float y) {
        float rs = Prefs.getRenderScale();
        float rx = x * rs, ry = y * rs;
        Vec3d hit = renderer.screenToBedPlane(rx, ry);
        if (panGrabPoint != null && hit != null) {
            renderer.getCamera().moveByWorld(panGrabPoint.x - hit.x, panGrabPoint.y - hit.y, 0);
        } else if (hasLastPan) {
            double wpp = renderer.worldPerPixel();
            if (wpp > 0) {
                renderer.getCamera().moveWorld((float) ((lastPanX - rx) * wpp), (float) ((lastPanY - ry) * wpp));
            }
        }
        lastPanX = rx;
        lastPanY = ry;
        hasLastPan = true;
        requestRender();
    }

    private void clearPanAnchor() {
        panGrabPoint = null;
        hasLastPan = false;
        hasLastPinch = false;
        pinchAnchor = null;
    }

    public void setOnModelLongPressListener(OnModelLongPressListener onModelLongPressListener) {
        this.onModelLongPressListener = onModelLongPressListener;
    }

    public void setOnBedLongPressListener(OnBedLongPressListener onBedLongPressListener) {
        this.onBedLongPressListener = onBedLongPressListener;
    }

    public void arrange() {
        if (renderer.getModel() == null) return;

        queueEvent(() -> {
            renderer.getBed().arrange(renderer.getModel());
            renderer.resetGlModels();
            requestRender();
        });
    }

    public void fillBedWithSelectedModel(FillBedCallback callback) {
        if (renderer.getModel() == null || fillBedInProgress) return;

        fillBedInProgress = true;
        queueEvent(() -> {
            int addedCopies = renderer.fillBedWithSelectedObject();
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                fillBedInProgress = false;
                if (callback != null) {
                    callback.onFillBedComplete(addedCopies);
                }
            });
        });
    }

    public void duplicateSelectedModel(Runnable onComplete) {
        queueEvent(() -> {
            renderer.duplicateSelectedObject();
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void deleteSelectedModel(Runnable onComplete) {
        queueEvent(() -> {
            int sel = renderer.getSelectedObject();
            if (sel != -1) {
                renderer.deleteObject(sel);
            }
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void selectAllModels(Runnable onComplete) {
        queueEvent(() -> {
            renderer.selectAllObjects();
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void centerSelectedOnBed(Runnable onComplete) {
        queueEvent(() -> {
            renderer.centerSelectionOnBed();
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void deleteAllModels(Runnable onComplete) {
        queueEvent(() -> {
            renderer.deleteAllObjects();
            requestRender();
            ViewUtils.postOnMainThread(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void drawOverlay(Canvas canvas, boolean toBitmap) {
        long dt = Math.min(System.currentTimeMillis() - lastDraw, 16);
        lastDraw = System.currentTimeMillis();

        int rad = ViewUtils.dp(16);
        float offsetX = getTranslationX(), offsetY = -getTranslationY();
        if (toBitmap) {
            paint.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
            canvas.drawRect(offsetX, offsetY, getWidth() - offsetX, getHeight() - offsetY, paint);
            canvas.drawRoundRect(offsetX, offsetY, getWidth() - offsetX, getHeight() - offsetY, rad, rad, xferPaint);
        } else {
            path.rewind();
            path.addRoundRect(offsetX, offsetY, getWidth() - offsetX, getHeight() - offsetY, rad, rad, Path.Direction.CW);

            canvas.save();
            canvas.clipOutPath(path);
            canvas.drawColor(ThemesRepo.getColor(android.R.attr.windowBackground));
            canvas.restore();

            if (getRenderer().getBed() != null && !getRenderer().getBed().isValid()) {
                invalidOffset += dt / 10000f;

                paint.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
                int size = ViewUtils.dp(200);
                canvas.drawRect(0, (getHeight() - size) / 2f, getWidth(), (getHeight() + size) / 2f, paint);

                double angle = Math.toRadians(60);
                int stableWidth = ViewUtils.dp(16);
                int lineHeight = ViewUtils.dp(16);
                int lineWidth = ViewUtils.dp(16 + (float) (32 * Math.sin(angle)));
                Path linePath = new Path();
                linePath.moveTo(0, 0);
                linePath.lineTo(stableWidth, 0);
                linePath.lineTo(lineWidth, lineHeight);
                linePath.lineTo(lineWidth - stableWidth, lineHeight);
                linePath.lineTo(0, 0);
                linePath.close();

                paint.setColor(ThemesRepo.getColor(android.R.attr.colorAccent));
                int x = (int) (-lineWidth - invalidOffset * getWidth());
                while (x < getWidth()) {
                    canvas.save();
                    canvas.translate(x, (getHeight() - size) / 2f);
                    canvas.drawPath(linePath, paint);

                    canvas.translate(stableWidth, 0);
                    int alpha = paint.getAlpha();
                    paint.setAlpha((int) (alpha * 0.5f));
                    canvas.drawPath(linePath, paint);
                    canvas.restore();
                    paint.setAlpha(alpha);

                    x += stableWidth * 2;
                }

                x = (int) (getWidth() + lineWidth + invalidOffset * getWidth());
                while (x >= -lineWidth) {
                    canvas.save();
                    canvas.translate(x, (getHeight() + size) / 2f - lineHeight);
                    canvas.drawPath(linePath, paint);

                    canvas.translate(-stableWidth, 0);
                    int alpha = paint.getAlpha();
                    paint.setAlpha((int) (alpha * 0.5f));
                    canvas.drawPath(linePath, paint);
                    canvas.restore();
                    paint.setAlpha(alpha);

                    x -= stableWidth * 2;
                }

                if (invalidBedDescriptionLayout == null) {
                    invalidBedText.setTextSize(ViewUtils.dp(16));
                    invalidBedText.setColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
                    invalidBedText.setTypeface(Typeface.DEFAULT);
                    invalidBedDescriptionLayout = StaticLayout.Builder.obtain(getContext().getString(R.string.BedConfigurationErrorDesc), 0, getContext().getString(R.string.BedConfigurationErrorDesc).length(), invalidBedText, getWidth() - ViewUtils.dp(32)).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1).setIncludePad(false).build();
                }

                int realTextSize = ViewUtils.dp(22);
                int padding = ViewUtils.dp(12);
                int totalHeight = realTextSize + invalidBedDescriptionLayout.getHeight();

                invalidBedText.setTextSize(ViewUtils.dp(18));
                invalidBedText.setColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
                invalidBedText.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
                String errString = getContext().getString(R.string.BedConfigurationError);
                canvas.drawText(errString, 0, errString.length(), (getWidth() - invalidBedText.measureText(errString)) / 2f, getHeight() / 2f - totalHeight / 2f + realTextSize - padding / 2f, invalidBedText);

                invalidBedText.setTextSize(ViewUtils.dp(16));
                invalidBedText.setColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
                invalidBedText.setTypeface(Typeface.DEFAULT);

                canvas.save();
                canvas.translate((getWidth() - invalidBedDescriptionLayout.getWidth()) / 2f, getHeight() / 2f + totalHeight / 2f - invalidBedDescriptionLayout.getHeight() + padding / 2f);
                invalidBedDescriptionLayout.draw(canvas);
                canvas.restore();

                invalidate();
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        drawOverlay(canvas, false);
    }

    public Bitmap snapshotBitmap() {
        int w = getWidth(), h = getHeight();
        int[] bitmapBuffer = new int[w * h];
        int[] bitmapSource = new int[w * h];
        IntBuffer intBuffer = IntBuffer.wrap(bitmapBuffer);
        intBuffer.position(0);
        try {
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, intBuffer);
            int offset1, offset2;
            for (int i = 0; i < h; i++) {
                offset1 = i * w;
                offset2 = (h - i - 1) * w;
                for (int j = 0; j < w; j++) {
                    int texturePixel = bitmapBuffer[offset1 + j];
                    int blue = (texturePixel >> 16) & 0xff;
                    int red = (texturePixel << 16) & 0x00ff0000;
                    int pixel = (texturePixel & 0xff00ff00) | red | blue;
                    bitmapSource[offset2 + j] = pixel;
                }
            }
        } catch (GLException e) {
            throw new RuntimeException(e);
        }
        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    private void calcStartFocus(MotionEvent e) {
        lastX = (e.getX(0) + e.getX(1)) / 2f;
        lastY = (e.getY(0) + e.getY(1)) / 2f;

        float x = e.getX(0) - e.getX(1), y = e.getY(0) - e.getY(1);
        lastLength = (float) Math.sqrt(x * x + y * y);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? renderer.stopHover() : renderer.hover(event.getX() * Prefs.getRenderScale(), event.getY() * Prefs.getRenderScale())) {
            queueEvent(this::requestRender);
        }
        return super.onHoverEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long deltaMs = System.currentTimeMillis() - lastActionTime;
        lastActionTime = System.currentTimeMillis();
        int action = e.getActionMasked();

        if (e.getPointerCount() > 2) {
            removeCallbacks(longClick);
            removeCallbacks(bedLongClick);
            longClickGesture = false;
            return true;
        }

        // Paint mode: a single finger paints; two fingers still orbit/zoom via the normal path.
        if (renderer.isPaintMode() && paintBrushActive && e.getPointerCount() == 1
                && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            paintAtScreen(e.getX(), e.getY());
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (e.getPointerCount() == 2) {
                removeCallbacks(longClick);
                removeCallbacks(bedLongClick);
                longClickGesture = false;
                clearPanAnchor();
                calcStartFocus(e);
                fromTwoPointers = true;
            } else {
                clearPanAnchor();
                lastX = e.getX();
                lastY = e.getY();
                longClickTargetObject = -1;
                longClickMoved = false;

                float renderScale = Prefs.getRenderScale();
                if (renderer.tryStartPrimeTowerDrag(lastX * renderScale, lastY * renderScale)) {
                    removeCallbacks(longClick);
                    removeCallbacks(bedLongClick);
                    primeTowerGesture = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    requestRender();
                    return true;
                }

                int j = renderer.raycastObjectIndex(lastX * renderScale, lastY * renderScale);
                if (renderer.getGcodeResult() == null && j != -1) {
                    longClickTargetObject = j;
                    postDelayed(longClick, 300);
                } else if (renderer.getGcodeResult() == null && renderer.getModel() != null) {
                    postDelayed(bedLongClick, 300);
                }
            }

            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longClick);
            removeCallbacks(bedLongClick);
            clearPanAnchor();
            if (primeTowerGesture) {
                renderer.finishPrimeTowerDrag(action != MotionEvent.ACTION_CANCEL);
                primeTowerGesture = false;
                onePointerGesture = false;
                requestRender();
                return true;
            }
            if (longClickGesture) {
                if (longClickMoved) {
                    queueEvent(()->{
                        // Commit the drag to the whole selection (group move when multiple are selected).
                        getRenderer().translateSelectedObjects(longClickTranslation.x, longClickTranslation.y, 0);
                        requestRender();
                        Bus.LONG_CLICK_TRANSLATION.postValue(new LongClickTranslationEvent(longClickTranslation.x, longClickTranslation.y, false));
                    });
                } else if (action != MotionEvent.ACTION_CANCEL && onModelLongPressListener != null) {
                    onModelLongPressListener.onModelLongPress(this, getRenderer().getSelectedObject(), e.getX(), e.getY());
                }
                longClickGesture = false;
                longClickMoved = false;
                longClickTargetObject = -1;
                onePointerGesture = false;
                return true;
            }
            longClickGesture = false;
            if (fromTwoPointers) {
                if (e.getPointerCount() == 1) {
                    fromTwoPointers = false;
                    isScaling = false;
                    twoPointerGesture = false;
                    lastActionTime = 0;
                }
                return true;
            }

            if (e.getPointerCount() == 1) {
                if (!onePointerGesture && action != MotionEvent.ACTION_CANCEL) {
                    if (renderer.onClick(e.getX() * Prefs.getRenderScale(), e.getY() * Prefs.getRenderScale())) {
                        requestRender();
                    }
                }

                lastX = e.getX(0);
                lastY = e.getY(0);
                onePointerGesture = false;
            }

            // TODO: Rotate with inertia?
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (primeTowerGesture && e.getPointerCount() == 1) {
                float renderScale = Prefs.getRenderScale();
                if (renderer.dragPrimeTower(e.getX() * renderScale, e.getY() * renderScale)) {
                    requestRender();
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;
            }
            if (e.getPointerCount() == 2) {
                float x = (e.getX(0) + e.getX(1)) / 2f;
                float y = (e.getY(0) + e.getY(1)) / 2f;

                float lenX = e.getX(0) - e.getX(1), lenY = e.getY(0) - e.getY(1);
                float len = (float) Math.sqrt(lenX * lenX + lenY * lenY);
                float distanceX = lastX - x, distanceY = lastY - y;

                if (deltaMs > 128) {
                    isScaling = false;
                    twoPointerGesture = false;
                    clearPanAnchor();
                }

                boolean startingGesture = false;
                if (!isScaling && !twoPointerGesture) {
                    if (Math.abs(distanceX) < touchSlop && Math.abs(distanceY) < touchSlop && Math.abs(len - lastLength) > touchSlop * 1.5f) {
                        isScaling = true;
                        startingGesture = true;
                    } else if (Math.sqrt(distanceX * distanceX + distanceY * distanceY) >= touchSlop) {
                        twoPointerGesture = true;
                        startingGesture = true;
                        grabPanAnchor(x, y);
                    }
                }
                if (isScaling) {
                    float x0 = e.getX(0), y0 = e.getY(0), x1 = e.getX(1), y1 = e.getY(1);
                    float last = lastLength;
                    lastLength = len;

                    if (!startingGesture) {
                        // Dual-ray pinch: raycast from BOTH fingers under the
                        // pre-zoom view. The world-space span ratio drives the
                        // zoom (correct under perspective foreshortening, where
                        // a screen-pixel ratio is wrong), and the pinned anchor
                        // absorbs pan so content never slides under the fingers.
                        // The zoom + anchor correction run on the GL thread:
                        // updateProjection rewrites the matrix the draw loop
                        // reads without locking.
                        float rs = Prefs.getRenderScale();
                        Vec3d p0a = hasLastPinch ? renderer.screenToBedPlane(lastPinchX0 * rs, lastPinchY0 * rs) : null;
                        Vec3d p1a = hasLastPinch ? renderer.screenToBedPlane(lastPinchX1 * rs, lastPinchY1 * rs) : null;
                        Vec3d p0b = renderer.screenToBedPlane(x0 * rs, y0 * rs);
                        Vec3d p1b = renderer.screenToBedPlane(x1 * rs, y1 * rs);
                        float f;
                        if (p0a != null && p1a != null && p0b != null && p1b != null) {
                            double spanA = Math.hypot(p1a.x - p0a.x, p1a.y - p0a.y);
                            double spanB = Math.hypot(p1b.x - p0b.x, p1b.y - p0b.y);
                            f = (spanA > 1e-6 && spanB > 0 && last > 0) ? (float) (spanB / spanA) : (last > 0 ? len / last : 1f);
                        } else if (last > 0) {
                            f = len / last;
                        } else {
                            f = 1f;
                        }
                        if (f > 2f) f = 2f;
                        else if (f < 0.5f) f = 0.5f;
                        final float factor = f;
                        final float mx = x, my = y;
                        queueEvent(() -> {
                            float r2 = Prefs.getRenderScale();
                            Vec3d target = pinchAnchor;
                            if (target == null) {
                                target = renderer.screenToBedPlane(mx * r2, my * r2);
                            }
                            renderer.getCamera().zoomBy(factor);
                            renderer.updateProjection();
                            if (target != null) {
                                Vec3d cur = renderer.screenToBedPlane(mx * r2, my * r2);
                                if (cur != null) {
                                    renderer.getCamera().moveByWorld(target.x - cur.x, target.y - cur.y, 0);
                                    pinchAnchor = target;
                                } else {
                                    pinchAnchor = null;
                                }
                            }
                            requestRender();
                        });
                    }
                    lastPinchX0 = x0;
                    lastPinchY0 = y0;
                    lastPinchX1 = x1;
                    lastPinchY1 = y1;
                    hasLastPinch = true;

                    lastX = x;
                    lastY = y;
                } else if (twoPointerGesture) {
                    if (!startingGesture) {
                        int mode = Prefs.getCameraControlMode();
                        if (mode == Prefs.CAMERA_CONTROL_MODE_ROTATE_MOVE || mode == Prefs.CAMERA_CONTROL_MODE_MOVE_ONLY) {
                            // Two-finger pan grabs the bed plane at the midpoint.
                            dragPanTo(x, y);
                        } else {
                            float gain = rotateDegreesPerInch();
                            renderer.getCamera().rotateAround(-distanceX / pixelsPerInch * gain, distanceY / pixelsPerInch * gain);
                            requestRender();
                        }
                    }

                    lastX = x;
                    lastY = y;
                }
            } else if (!fromTwoPointers) {
                float distanceX = lastX - e.getX(), distanceY = lastY - e.getY();
                boolean startingGesture = false;

                if (!onePointerGesture) {
                    if (Math.sqrt(distanceX * distanceX + distanceY * distanceY) >= touchSlop) {
                        onePointerGesture = true;
                        startingGesture = true;
                        removeCallbacks(longClick);
                        removeCallbacks(bedLongClick);
                        int mode = Prefs.getCameraControlMode();
                        if (mode != Prefs.CAMERA_CONTROL_MODE_ROTATE_MOVE && !longClickGesture) {
                            grabPanAnchor(e.getX(), e.getY());
                        }
                    }
                }

                if (onePointerGesture) {
                    if (!startingGesture) {
                        if (longClickGesture) {
                            longClickMoved = true;
                            float renderScale = Prefs.getRenderScale();
                            getRenderer().getModel().getTranslation(getRenderer().getSelectedObject(), tempVec);
                            getRenderer().getBed().getRaycaster().raycast(getRenderer(), longClickHitResults, e.getX() * renderScale, e.getY() * renderScale);
                            if (!longClickHitResults.isEmpty()) {
                                GLModel.HitResult result = longClickHitResults.get(0);
                                longClickTranslation.x = result.position.x - tempVec.x - longClickOffset.x;
                                longClickTranslation.y = result.position.y - tempVec.y - longClickOffset.y;
                                getRenderer().setSelectionTranslation(longClickTranslation.x, longClickTranslation.y, 0);
                                Bus.LONG_CLICK_TRANSLATION.postValue(new LongClickTranslationEvent(longClickTranslation.x, longClickTranslation.y, true));
                            }

                            requestRender();
                        } else {
                            int mode = Prefs.getCameraControlMode();
                            if (mode == Prefs.CAMERA_CONTROL_MODE_ROTATE_MOVE) {
                                // Trackball orbit in physical units: same angular
                                // travel per inch on every display density.
                                float gain = rotateDegreesPerInch();
                                renderer.getCamera().rotateAround(-distanceX / pixelsPerInch * gain, distanceY / pixelsPerInch * gain);
                            } else {
                                dragPanTo(e.getX(), e.getY());
                            }
                            requestRender();
                        }
                    }

                    lastX = e.getX();
                    lastY = e.getY();
                }
            }
        }

        return true;
    }

    private void applyScale() {
        int w = getWidth(), h = getHeight();
        float realScale = Math.round(w * (lastScale = Prefs.getRenderScale())) / (float) w;
        getHolder().setFixedSize((int) (w * realScale), (int) (h * realScale));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != 0 && h != 0) {
            applyScale();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWidth() > 0 && getHeight() > 0 && lastScale != Prefs.getRenderScale()) {
            applyScale();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        requestRender();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        requestRender();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        super.surfaceDestroyed(holder);
        renderer.onDestroy();
    }

    @Override
    public void onApplyTheme() {
        requestRender();
    }
}
