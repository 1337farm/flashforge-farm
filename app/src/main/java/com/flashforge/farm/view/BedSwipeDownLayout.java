package com.flashforge.farm.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class BedSwipeDownLayout extends FrameLayout implements IThemeView {
    private final static int TOP_MARGIN_DP = 28;
    private final static int ARROW_LENGTH_DP = 11;

    private Path path = new Path();
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint dimmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float progress = 0;
    private boolean openEnabled = true;

    private boolean processingSwipe;
    private SpringAnimation springAnimation;
    private float startProgress;
    private GestureDetector gestureDetector;

    public BedSwipeDownLayout(@NonNull Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ViewUtils.dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setPathEffect(new CornerPathEffect(ViewUtils.dp(6)));

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                if (springAnimation != null) return false;

                processingSwipe = true;
                startProgress = progress;
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (springAnimation != null) return false;

                animateTo(1f);
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (springAnimation != null) return false;

                if (processingSwipe) {
                    progress = MathUtils.clamp(startProgress + (e2.getY() - e1.getY()) / getHeight(), 0, 1);
                    invalidateTranslation();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (springAnimation != null) return false;

                if (velocityY >= 1500) {
                    animateTo(1f);
                    return true;
                }
                return false;
            }
        });

        setWillNotDraw(false);
        onApplyTheme();
    }

    public boolean onBackPressed() {
        if (springAnimation != null) {
            return true;
        }
        if (progress == 1f) {
            animateTo(0f);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (openEnabled) {
            if (processingSwipe) {
                boolean d = gestureDetector.onTouchEvent(ev);
                if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    processingSwipe = false;

                    if (springAnimation == null && progress != 0 && progress != 1) {
                        animateTo(progress > 0.5f ? 1f : 0f);
                    }
                }
                return d;
            }

            if (progress != 1f && springAnimation == null) {
                if (ev.getY() < ViewUtils.dp(TOP_MARGIN_DP) + getHeight() * progress) {
                    return gestureDetector.onTouchEvent(ev);
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        int i = indexOfChild(child);
        canvas.save();
        float tY = -getHeight() * (1f - progress);
        if (openEnabled && i != 0) {
            int clr = ThemesRepo.getColor(android.R.attr.windowBackground);
            dimmPaint.setColor(clr);
            canvas.drawRect(0, child.getTop() + tY, child.getWidth(), child.getTop() + tY + child.getHeight(), dimmPaint);
            canvas.clipRect(0, child.getTop() + tY, child.getWidth(), child.getTop() + tY + child.getHeight());
        }

        boolean ch = super.drawChild(canvas, child, drawingTime);
        if (openEnabled) {
            if (i == 0) {
                dimmPaint.setColor(Color.argb((int) (0x66 * progress), 0, 0, 0));
                canvas.drawPaint(dimmPaint);
            }
        }
        canvas.restore();
        return ch;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        super.draw(canvas);

        if (openEnabled) {
            path.rewind();
            float cx = getWidth() * 0.5f, cy = ViewUtils.dp(TOP_MARGIN_DP) * 0.5f + getHeight() * progress;
            float angle = (float) Math.toRadians(25) * (1f - Math.min(progress, 0.5f) / 0.5f);
            int len = ViewUtils.dp(ARROW_LENGTH_DP);
            path.moveTo(cx, cy);
            path.lineTo((float) (cx - Math.cos(angle) * len), (float) (cy - Math.sin(angle) * len));
            path.moveTo(cx, cy);
            path.lineTo((float) (cx + Math.cos(angle) * len), (float) (cy - Math.sin(angle) * len));
            canvas.drawPath(path, paint);
        }
    }

    private void animateTo(float to) {
        springAnimation = new SpringAnimation(new FloatValueHolder(progress))
                .setMinimumVisibleChange(1 / 500f)
                .setSpring(new SpringForce(to)
                        .setStiffness(600f)
                        .setDampingRatio(1f))
                .addUpdateListener((animation, value, velocity) -> {
                    progress = value;
                    invalidateTranslation();
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    springAnimation = null;
                    processingSwipe = false;
                });
        springAnimation.start();
    }

    private void invalidateTranslation() {
        if (getChildCount() > 0) {
            getChildAt(0).setTranslationY(getHeight() * progress * 0.25f);
            for (int i = 1; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.setTranslationY(-getHeight() * (1f - progress) * 0.75f);
                ch.setAlpha(progress);
            }
        }
        invalidate();
    }

    public void setEnableTop(boolean enable) {
        if (enable == openEnabled) {
            return;
        }
        openEnabled = enable;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));

        if (getChildCount() > 0) {
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height - ViewUtils.dp(TOP_MARGIN_DP) * (openEnabled ? 1 : 0), MeasureSpec.EXACTLY));
            for (int i = 1; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (openEnabled && getChildCount() > 0) {
            View first = getChildAt(0);
            first.layout(0, ViewUtils.dp(TOP_MARGIN_DP), first.getMeasuredWidth(), ViewUtils.dp(TOP_MARGIN_DP) + first.getMeasuredHeight());

            for (int i = 1; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.layout(0, 0, ch.getMeasuredWidth(), ch.getMeasuredHeight());
            }
        }
        invalidateTranslation();
    }

    @Override
    public void onApplyTheme() {
        paint.setColor(ColorUtils.setAlphaComponent(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0x44));
    }
}
