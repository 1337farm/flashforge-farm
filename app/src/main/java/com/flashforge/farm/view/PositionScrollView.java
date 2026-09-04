package com.flashforge.farm.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Scroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.VibrationUtils;
import com.flashforge.farm.utils.ViewUtils;

public class PositionScrollView extends View implements IThemeView {
    private final static int STEP = 1;
    private final static int MARK_STEP = 1;

    private final static int TOP_MARGIN_DP = ViewUtils.dp(6);
    private final static int BOTTOM_MARGIN_DP = ViewUtils.dp(14);
    private final static int STEP_DP = ViewUtils.dp(10);
    private final static int GRADIENT_DP = ViewUtils.dp(32);

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private int currentPosition = 0;
    private float progress;
    private Consumer<Integer> listener;
    private Consumer<Integer> progressListener;

    private GradientDrawable leftDrawable = new GradientDrawable(), rightDrawable = new GradientDrawable();
    private GestureDetector gestureDetector;
    private int lastX;
    private Scroller gestureScroller;
    private SpringAnimation stepAnimation;
    private float wasProgress;

    private int activeColor = android.R.attr.colorAccent;
    private int inactiveColor = android.R.attr.textColorSecondary;

    private boolean notClick;
    private int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;

    private List<PositionScrollView> synchronizedScrolls = new ArrayList<>();
    private Map<PositionScrollView, Integer> syncDeltas = new HashMap<>();

    public PositionScrollView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(ViewUtils.dp(2.5f));

        textPaint.setTextSize(ViewUtils.dp(14));

        gestureScroller = new Scroller(context);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            private boolean gotOffset;

            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                if (stepAnimation != null) {
                    stepAnimation.cancel();
                }
                gotOffset = false;
                if (!gestureScroller.isFinished()) {
                    notClick = true;
                }
                gestureScroller.forceFinished(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (stepAnimation != null) {
                    stepAnimation.cancel();
                }
                if (!gotOffset) {
                    gotOffset = true;
                } else {
                    scrollDx(distanceX);
                }

                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                gestureScroller.fling(lastX = 0, 0, (int) (-velocityX * 2f), 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
                ViewCompat.postInvalidateOnAnimation(PositionScrollView.this);

                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                return !notClick && callOnClick();
            }
        });

        onApplyTheme();
    }

    public void addSynced(PositionScrollView scroll) {
        syncDeltas.put(scroll, scroll.getCurrentPosition() - currentPosition);
        synchronizedScrolls.add(scroll);
    }

    public void removeSynced(PositionScrollView scroll) {
        syncDeltas.remove(scroll);
        synchronizedScrolls.remove(scroll);
    }

    public void updateSyncDeltas() {
        for (PositionScrollView scroll : synchronizedScrolls) {
            syncDeltas.put(scroll, scroll.getCurrentPosition() - currentPosition);
        }
    }

    public void setActiveColor(int activeColor) {
        this.activeColor = activeColor;
        invalidate();
    }

    public void setInactiveColor(int inactiveColor) {
        this.inactiveColor = inactiveColor;
        invalidate();
    }

    public void setCurrentPosition(int currentPosition) {
        setCurrentPosition(currentPosition, false);
    }

    private void onSetProgress(float progress) {
        this.progress = progress;
        for (PositionScrollView s : synchronizedScrolls) {
            if (currentPosition + syncDeltas.get(s) <= s.min && syncDeltas.get(s) < 0) {
                s.progress = 0;
            } else {
                s.progress = progress;
            }
            s.invalidate();
        }
    }

    private void onSetCurrentPosition(int currentPosition) {
        this.currentPosition = currentPosition;
        for (PositionScrollView s : synchronizedScrolls) {
            s.currentPosition = Math.max(currentPosition + syncDeltas.get(s), s.min);
            s.invalidate();
        }
    }

    public void setCurrentPosition(int currentPosition, boolean notify) {
        onSetCurrentPosition(currentPosition);
        onSetProgress(0);
        if (notify && listener != null) {
            listener.accept(currentPosition);
        }
        invalidate();
    }

    public void stopScroll() {
        gestureScroller.forceFinished(true);
        computeScroll();
    }

    private boolean scrollDx(float dx) {
        if (currentPosition == min && dx < 0 || currentPosition == max && dx > 0) {
            invalidate();
            return false;
        }

        float pr = -dx / ViewUtils.dp(STEP_DP);
        onSetProgress(progress + pr);
        if (progress > 0f && currentPosition == min || progress < 0f && currentPosition == max) {
            onSetProgress(0f);
        }

        while (progress >= 1f) {
            onSetProgress(progress - 1);
            if (currentPosition > min) {
                onSetCurrentPosition(currentPosition - STEP);
                notifyNewCurrent();
            }
        }
        while (progress <= -1f) {
            onSetProgress(progress + 1);
            if (currentPosition < max) {
                onSetCurrentPosition(currentPosition + STEP);
                notifyNewCurrent();
            }
        }
        invalidate();

        return true;
    }

    @Override
    public void computeScroll() {
        super.computeScroll();

        if (gestureScroller.computeScrollOffset()) {
            int dx = gestureScroller.getCurrX() - lastX;
            lastX = gestureScroller.getCurrX();
            if (!scrollDx(dx)) {
                gestureScroller.forceFinished(true);

                lastX = 0;
                if (stepAnimation != null) {
                    stepAnimation.cancel();
                }
                wasProgress = 0;
                stepAnimation = new SpringAnimation(new FloatValueHolder(progress))
                        .setMinimumVisibleChange(1 / 500f)
                        .setSpring(new SpringForce(0f)
                                .setStiffness(400f)
                                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY))
                        .setStartVelocity(MathUtils.clamp(-(gestureScroller.getCurrVelocity() / 1500) / ViewUtils.dp(STEP_DP), -6f, 6f))
                        .addUpdateListener((animation, value, velocity) -> {
                            onSetProgress(value);
                            invalidate();
                        })
                        .addEndListener((animation, canceled, value, velocity) -> {
                            if (progress >= 1f) {
                                onSetProgress(progress - 1);
                                onSetCurrentPosition(currentPosition - STEP);
                                notifyNewCurrent();
                            } else if (progress <= -1f) {
                                onSetProgress(progress + 1);
                                onSetCurrentPosition(currentPosition + STEP);
                                notifyNewCurrent();
                            }
                        })
                        .addEndListener((animation, canceled, value, velocity) -> {
                            stepAnimation = null;
                            if (listener != null) {
                                listener.accept(currentPosition);
                            }
                        });
                stepAnimation.start();
            }
        } else if (lastX != 0) {
            onScrollFinished();
        }
    }

    private void onScrollFinished() {
        lastX = 0;
        notClick = false;
        if (stepAnimation != null) {
            stepAnimation.cancel();
        }
        stepAnimation = new SpringAnimation(new FloatValueHolder(wasProgress = progress))
                .setMinimumVisibleChange(1 / 500f)
                .setSpring(new SpringForce(progress > 0.75f ? 1f : progress < -0.75f ? -1f : 0f)
                        .setStiffness(400f)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY))
                .addUpdateListener((animation, value, velocity) -> {
                    onSetProgress(value);
                    invalidate();
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    if (progress >= 1f) {
                        onSetProgress(progress - 1);
                        onSetCurrentPosition(currentPosition - STEP);
                        notifyNewCurrent();
                    } else if (progress <= -1f) {
                        onSetProgress(progress + 1);
                        onSetCurrentPosition(currentPosition + STEP);
                        notifyNewCurrent();
                    }
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    stepAnimation = null;
                    if (listener != null) {
                        listener.accept(currentPosition);
                    }
                });
        stepAnimation.start();
    }

    public int getCurrentPosition() {
        return currentPosition;
    }

    public void notifyNewCurrent() {
        if (progressListener != null) {
            progressListener.accept(currentPosition);
        }
        for (PositionScrollView s : synchronizedScrolls) {
            if (s.progressListener != null) {
                s.progressListener.accept(Math.max(currentPosition + syncDeltas.get(s), s.min));
            }
        }
        vibrateHaptic();
        invalidate();
    }

    private void vibrateHaptic() {
        if (Prefs.isVibrationEnabled() && VibrationUtils.hasAmplitudeControl() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
        }
    }

    public void setProgressListener(Consumer<Integer> progressListener) {
        this.progressListener = progressListener;
    }

    public void setListener(Consumer<Integer> listener) {
        this.listener = listener;
    }

    public void setMinMax(int min, int max) {
        this.min = min;
        this.max = max;
        invalidate();
    }

    private int resolveInactiveColor() {
        int clr = ThemesRepo.getColor(inactiveColor);
        return ColorUtils.setAlphaComponent(clr, (int) (Color.alpha(clr) * 0.6f));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float progressClamped = stepAnimation != null && wasProgress == 0f ? 0f : MathUtils.clamp(progress, -1f, 1f);

        float transformProgress = Math.abs(progressClamped);
        paint.setColor(ColorUtils.blendARGB(ThemesRepo.getColor(activeColor), resolveInactiveColor(), transformProgress));

        float cX = getWidth() / 2f + progress * ViewUtils.dp(STEP_DP);
        canvas.drawLine(cX, ViewUtils.lerp(paint.getStrokeWidth(), ViewUtils.dp(TOP_MARGIN_DP), transformProgress), cX, getHeight() - ViewUtils.dp(BOTTOM_MARGIN_DP), paint);

        drawTextIfNeeded(canvas, cX, currentPosition, transformProgress);

        float x = cX;
        int i = currentPosition;
        while (x > getPaddingLeft() && i > min) {
            x -= ViewUtils.dp(STEP_DP);
            i -= STEP;

            float pr = i == currentPosition - STEP ? Math.max(0, progressClamped) : 0;
            float top = ViewUtils.lerp(ViewUtils.dp(TOP_MARGIN_DP), 0, pr);
            paint.setColor(ColorUtils.blendARGB(resolveInactiveColor(), ThemesRepo.getColor(activeColor), pr));
            canvas.drawLine(x, top, x, getHeight() - ViewUtils.dp(BOTTOM_MARGIN_DP), paint);

            drawTextIfNeeded(canvas, x, i, 1f - pr);
        }

        x = cX;
        i = currentPosition;
        while (x < getWidth() - getPaddingRight() && i < max) {
            x += ViewUtils.dp(STEP_DP);
            i += STEP;

            float pr = i == currentPosition + STEP ? -Math.min(0, progressClamped) : 0;
            float top = ViewUtils.lerp(ViewUtils.dp(TOP_MARGIN_DP), 0, pr);
            paint.setColor(ColorUtils.blendARGB(resolveInactiveColor(), ThemesRepo.getColor(activeColor), pr));
            canvas.drawLine(x, top, x, getHeight() - ViewUtils.dp(BOTTOM_MARGIN_DP), paint);

            drawTextIfNeeded(canvas, x, i, 1f - pr);
        }

        paint.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        canvas.drawRect(0, 0, getPaddingLeft(), getHeight(), paint);
        canvas.drawRect(getWidth() - getPaddingRight(), 0, getWidth(), getHeight(), paint);

        leftDrawable.setBounds(getPaddingLeft(), 0, ViewUtils.dp(GRADIENT_DP), getHeight());
        leftDrawable.draw(canvas);

        rightDrawable.setBounds(getWidth() - ViewUtils.dp(GRADIENT_DP), 0, getWidth() - getPaddingRight(), getHeight());
        rightDrawable.draw(canvas);
    }

    private void drawTextIfNeeded(Canvas canvas, float x, int i, float alpha) {
        if (i % MARK_STEP == 0 || i == min) {
            String str = String.valueOf(i);
            float width = textPaint.measureText(str);
            int wasAlpha = textPaint.getAlpha();
            textPaint.setAlpha((int) (alpha * wasAlpha));
            canvas.save();
            float sc = 0.85f + alpha * 0.15f;
            canvas.scale(sc, sc, x - width / 2f, getHeight() - ViewUtils.dp(BOTTOM_MARGIN_DP));
            canvas.drawText(str, x - width / 2f, getHeight() - ViewUtils.dp(BOTTOM_MARGIN_DP) / 2f, textPaint);
            canvas.restore();
            textPaint.setAlpha(wasAlpha);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean detector = gestureDetector.onTouchEvent(event);
        if ((event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) && gestureScroller.isFinished()) {
            onScrollFinished();
        }
        return detector;
    }

    @Override
    public void onApplyTheme() {
        textPaint.setColor(resolveInactiveColor());

        leftDrawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        leftDrawable.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        leftDrawable.setColors(new int[] {ThemesRepo.getColor(android.R.attr.windowBackground), Color.TRANSPARENT});

        rightDrawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        rightDrawable.setOrientation(GradientDrawable.Orientation.RIGHT_LEFT);
        rightDrawable.setColors(new int[] {ThemesRepo.getColor(android.R.attr.windowBackground), Color.TRANSPARENT});
    }
}
