package com.flashforge.farm.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.flashforge.farm.fragment.BedFragment;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.MirrorView;

public abstract class UnfoldMenu {
    protected BedFragment fragment;

    private boolean isVisible;
    private boolean isDismissing;
    private SpringAnimation spring;
    private DynamicAnimation.OnAnimationUpdateListener updateListener;
    private FrameLayout containerLayout;
    private View innerView;

    private Runnable onDismiss;

    private View dimmView;
    private FrameLayout rootView;
    private float progress;

    private float fromTranslationX;
    private float fromTranslationY;
    private float toTranslationX;
    private float toTranslationY;

    public int getRequestedSize(FrameLayout into, boolean portrait) {
        return (int) ((portrait ? into.getHeight() : into.getWidth()) * 0.6f);
    }

    @CallSuper
    protected void onCreate() {}

    @CallSuper
    protected void onDestroy() {
        if (onDismiss != null) {
            onDismiss.run();
        }
    }

    public void setOnDismiss(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    protected abstract View onCreateView(Context ctx, boolean portrait);

    public void show(View from, BedFragment fragment) {
        show(from, fragment, fragment.getOverlayLayout());
    }

    public void show(View from, FrameLayout into) {
        show(from, null, into);
    }

    private void show(View from, BedFragment fragment, FrameLayout into) {
        if (isVisible) return;
        if (fragment != null) {
            this.fragment = fragment;
        }
        this.isVisible = true;
        this.containerLayout = into;

        boolean portrait = into.getWidth() < into.getHeight();

        Context ctx = into.getContext();
        MirrorView mirror = new MirrorView(ctx);
        mirror.setMirroredView(from);
        mirror.setLayoutParams(new FrameLayout.LayoutParams(from.getWidth(), from.getHeight()));

        int[] pos = new int[2];
        from.getLocationInWindow(pos);
        int[] intoPos = new int[2];
        into.getLocationInWindow(intoPos);
        intoPos[0] += into.getPaddingLeft();
        intoPos[1] += into.getPaddingTop();

        int side = getRequestedSize(into, portrait) + ((View) into.getParent().getParent()).getPaddingBottom();
        fromTranslationX = pos[0] - intoPos[0];
        fromTranslationY = pos[1] - intoPos[1];
        toTranslationX = 0;
        toTranslationY = portrait ? into.getHeight() - side - into.getPaddingTop() - into.getPaddingBottom() : 0;
        rootView = new FrameLayout(ctx) {
            {
                setWillNotDraw(false);
            }

            private Path path = new Path();
            @Override
            public void draw(@NonNull Canvas canvas) {
                canvas.save();
                path.rewind();
                float rad = ViewUtils.dp(16) * (1f - progress);
                path.addRoundRect(0, 0,
                        ViewUtils.lerp(mirror.getWidth(), getWidth(), progress), ViewUtils.lerp(mirror.getHeight(), getHeight(), progress),
                        rad, rad, Path.Direction.CW);
                canvas.clipPath(path);
                canvas.drawColor(ThemesRepo.getColor(android.R.attr.windowBackground));
                super.draw(canvas);
                canvas.restore();
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return true;
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();

                if (isVisible) {
                    onCreate();
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();

                if (isVisible) {
                    onDestroy();
                }
            }
        };
        rootView.addView(mirror);
        rootView.addView(innerView = onCreateView(ctx, portrait));
        innerView.setAlpha(0f);

        rootView.setTranslationX(fromTranslationX);
        rootView.setTranslationY(fromTranslationY);

        dimmView = new View(ctx);
        dimmView.setBackgroundColor(0x40000000);
        dimmView.setTranslationX(toTranslationX);
        dimmView.setTranslationY(toTranslationY);
        dimmView.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(portrait ? ViewGroup.LayoutParams.MATCH_PARENT : side, portrait ? side : ViewGroup.LayoutParams.MATCH_PARENT);
        into.addView(dimmView, params);

        into.addView(rootView, params);

        float invY = into.getHeight() - ViewUtils.dp(80 * 2) - toTranslationY;
        spring = new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 256f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(1f))
                .addUpdateListener(updateListener = (animation, value, velocity) -> {
                    this.progress = value;
                    rootView.invalidate();

                    dimmView.setAlpha(value);

                    rootView.setTranslationX(ViewUtils.lerp(fromTranslationX, toTranslationX, value));
                    rootView.setTranslationY(ViewUtils.lerp(fromTranslationY, toTranslationY, value));

                    float mirrorValue = Math.min(0.75f, value) / 0.75f;
                    mirror.setAlpha((1f - mirrorValue) * mirror.getMirroredView().getAlpha());
                    mirror.setScaleX(1f + mirrorValue);
                    mirror.setScaleY(1f + mirrorValue);
                    mirror.setTranslationX((rootView.getWidth() - mirror.getWidth()) / 2f * mirrorValue);
                    mirror.setTranslationY((rootView.getHeight() - mirror.getHeight()) / 2f * mirrorValue);

                    innerView.setTranslationX((mirror.getWidth() - innerView.getWidth()) * (1f - value));
                    innerView.setTranslationY((mirror.getHeight() - innerView.getHeight()) * (1f - value));
                    innerView.setPivotX(innerView.getWidth() / 2f);
                    innerView.setPivotY(innerView.getHeight() / 2f);
                    innerView.setScaleX(0.5f + value * 0.5f);
                    innerView.setScaleY(0.5f + value * 0.5f);
                    innerView.setAlpha(value);

                    if (fragment != null) {
                        if (!portrait) {
                            float tX = rootView.getWidth() - ViewUtils.dp(80 * 2);
                            fragment.getGlView().setTranslationX(tX / 2f * value);
                            ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) fragment.getSnackbarsLayout().getLayoutParams();
                            marginParams.leftMargin = (int) (ViewUtils.dp(80 * 2) + tX * value);
                            fragment.getSnackbarsLayout().requestLayout();
                            dimmView.setTranslationX(rootView.getTranslationX() - ViewUtils.lerp(rootView.getWidth() - mirror.getWidth(), 0, progress));
                        } else {
                            fragment.getGlView().setTranslationY(-invY / 2 * value);
                            fragment.getSnackbarsLayout().setTranslationY(-invY * value);
                            dimmView.setTranslationY(rootView.getTranslationY());
                        }
                        fragment.getGlView().invalidate();
                    }
                })
                .addEndListener((animation, canceled, value, velocity) -> onShown());
        spring.start();
    }

    protected void onShown() {}

    public void relayout() {
        FrameLayout into = containerLayout;
        boolean portrait = into.getWidth() < into.getHeight();
        int side = rootView.getHeight();
        toTranslationY = portrait ? into.getHeight() - side : 0;

        updateListener.onAnimationUpdate(spring, 1f, 0f);
    }

    public boolean isAttached() {
        return rootView.getParent() != null && !isDismissing;
    }

    public void dismiss() {
        dismiss(false);
    }

    public void dismiss(boolean alphaOnly) {
        if (!isVisible) return;
        this.isVisible = false;

        isDismissing = true;
        onDestroy();
        isDismissing = false;

        if (alphaOnly) {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1).setDuration(150);
            anim.setInterpolator(ViewUtils.CUBIC_INTERPOLATOR);
            anim.addUpdateListener(animation -> {
                float val = (float) animation.getAnimatedValue();
                rootView.setAlpha(1f - val);
                dimmView.setAlpha(1f - val);

                rootView.setTranslationY(val * ViewUtils.dp(64));
                dimmView.setTranslationY(val * ViewUtils.dp(64));
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    containerLayout.removeView(dimmView);
                    containerLayout.removeView(rootView);
                }
            });
            anim.start();
        } else {
            spring.getSpring().setFinalPosition(0f);
            spring.addEndListener((animation, canceled, value, velocity) -> {
                containerLayout.removeView(dimmView);
                containerLayout.removeView(rootView);
            });
            spring.start();
        }
    }
}
