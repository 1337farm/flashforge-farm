package com.flashforge.farm.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Objects;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class SnackbarsLayout extends FrameLayout {
    public SnackbarsLayout(@NonNull Context context) {
        super(context);
    }

    public void show(Snackbar snackbar) {
        if (snackbar.tag != null) {
            dismiss(snackbar.tag);
        }

        SnackbarView v = new SnackbarView(getContext()).bind(snackbar);
        addView(v);
        applyTransforms();
        new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 500f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY))
                .addUpdateListener((animation, value, velocity) -> {
                    v.progress = value;
                    applyTransforms();
                })
                .start();

        if (snackbar.lifetime > 0) {
            ViewUtils.postOnMainThread(v::dismiss, snackbar.lifetime);
        }
    }

    public void dismiss(String tag) {
        for (int i = 0, s = getChildCount(); i < s; i++) {
            SnackbarView snackbar = (SnackbarView) getChildAt(i);
            if (Objects.equals(snackbar.snackbar.tag, tag)) {
                snackbar.dismiss();
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applyTransforms();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        applyTransforms();
    }

    private void applyTransforms() {
        float y = getHeight() - ViewUtils.dp(8);

        for (int i = getChildCount() - 1; i >= 0; i--) {
            SnackbarView snackbar = (SnackbarView) getChildAt(i);
            if (snackbar.getTag() == null) {
                snackbar.setAlpha(snackbar.progress);
            }

            float yOff = snackbar.getAlpha() * snackbar.progress * (snackbar.getHeight() + ViewUtils.dp(8));
            y -= yOff;
            snackbar.setTranslationY(y);
        }
    }

    private class SnackbarView extends LinearLayout {
        private final static int MARGIN_DP = 8;

        private ProgressBar progressBar;
        private ImageView icon;
        private TextView title;
        private Snackbar snackbar;

        private TextView button;

        private float progress;

        private GestureDetector gestureDetector;

        SnackbarView(Context context) {
            super(context);

            setElevation(ViewUtils.dp(4));
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, getWidth(), getHeight(), ViewUtils.dp(16));
                }
            });
            setAlpha(0f);
            setPadding(ViewUtils.dp(10), ViewUtils.dp(10), ViewUtils.dp(10), ViewUtils.dp(10));
            setMinimumHeight(ViewUtils.dp(48));

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            FrameLayout fl = new FrameLayout(context);
            icon = new ImageView(context);
            fl.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            progressBar = new ProgressBar(context);
            progressBar.setVisibility(GONE);
            fl.addView(progressBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(fl, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)) {{
                setMarginStart(ViewUtils.dp(4));
                setMarginEnd(ViewUtils.dp(14));
            }});
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title);

            addView(new Space(context), new LinearLayout.LayoutParams(0, 0, 1f));

            button = new TextView(context);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            button.setMaxLines(1);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setPadding(ViewUtils.dp(8), ViewUtils.dp(8), ViewUtils.dp(8),ViewUtils.dp(8));
            addView(button);

            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
                leftMargin = topMargin = rightMargin = bottomMargin = ViewUtils.dp(MARGIN_DP);
            }});

            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                    getParent().requestDisallowInterceptTouchEvent(true);

                    float off = e2.getX() - e1.getX();
                    setTranslationX(off);
                    return true;
                }

                @Override
                public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                    if (snackbar.lifetime == 0) {
                        return false;
                    }

                    if (Math.abs(velocityX) >= 1500) {
                        if (velocityX > 0) {
                            animateTo(getWidth() + ViewUtils.dp(MARGIN_DP), true);
                        } else {
                            animateTo(-getWidth() - ViewUtils.dp(MARGIN_DP), true);
                        }
                        return true;
                    }

                    return false;
                }
            });
        }

        private void dismiss() {
            setTag(1);

            new SpringAnimation(new FloatValueHolder(0))
                    .setMinimumVisibleChange(1 / 500f)
                    .setSpring(new SpringForce(1f)
                            .setStiffness(1000f)
                            .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY))
                    .addUpdateListener((animation, value, velocity) -> {
                        setAlpha(1f - value);
                        applyTransforms();
                    })
                    .addEndListener((animation, canceled, value, velocity) -> {
                        if (getParent() == null) return;
                        ((ViewGroup) getParent()).removeView(this);
                    })
                    .start();
        }

        private void animateTo(float x, boolean remove) {
            if (remove) {
                setTag(1);
            }
            float start = getTranslationX();
            new SpringAnimation(new FloatValueHolder(0))
                    .setMinimumVisibleChange(1 / 500f)
                    .setSpring(new SpringForce(1f)
                            .setStiffness(1000f)
                            .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY))
                    .addUpdateListener((animation, value, velocity) -> {
                        setTranslationX(ViewUtils.lerp(start, x, value));
                        if (remove) {
                            progress = 1f - value;
                            applyTransforms();
                        }
                    })
                    .addEndListener((animation, canceled, value, velocity) -> {
                        if (remove && getParent() != null) {
                            ((ViewGroup) getParent()).removeView(SnackbarView.this);
                        }
                    })
                    .start();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && getTag() == null) {
                animateTo(0, false);
            }

            MotionEvent ev = MotionEvent.obtain(event);
            ev.offsetLocation(getTranslationX(), 0);
            boolean ret = gestureDetector.onTouchEvent(ev);
            ev.recycle();
            return ret;
        }

        SnackbarView bind(Snackbar snackbar) {
            this.snackbar = snackbar;

            progressBar.setVisibility(snackbar.type == Type.LOADING ? VISIBLE : GONE);
            icon.setVisibility(snackbar.type == Type.LOADING ? GONE : VISIBLE);

            title.setText(snackbar.title);
            button.setText(snackbar.buttonTitle);
            button.setOnClickListener(snackbar.buttonClick);
            button.setVisibility(snackbar.buttonTitle != null ? View.VISIBLE : View.GONE);

            switch (snackbar.type) {
                case DONE:
                    icon.setImageResource(R.drawable.done_outline_28);
                    icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.snackbarDone)));
                    title.setTextColor(ThemesRepo.getColor(R.attr.snackbarDone));
                    button.setTextColor(ThemesRepo.getColor(R.attr.snackbarDone));
                    button.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), ColorUtils.setAlphaComponent(ThemesRepo.getColor(R.attr.snackbarDone), 0x21), 8));
                    setBackgroundColor(ColorUtils.blendARGB(ThemesRepo.getColor(R.attr.snackbarBase), ThemesRepo.getColor(R.attr.snackbarDone), 0.15f));
                    break;
                case WARNING:
                    icon.setImageResource(R.drawable.warning_triangle_outline_28);
                    icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.snackbarWarning)));
                    title.setTextColor(ThemesRepo.getColor(R.attr.snackbarWarning));
                    button.setTextColor(ThemesRepo.getColor(R.attr.snackbarWarning));
                    button.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), ColorUtils.setAlphaComponent(ThemesRepo.getColor(R.attr.snackbarWarning), 0x21), 8));
                    setBackgroundColor(ColorUtils.blendARGB(ThemesRepo.getColor(R.attr.snackbarBase), ThemesRepo.getColor(R.attr.snackbarWarning), 0.15f));
                    break;
                case LOADING:
                    progressBar.setIndeterminateTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.snackbarInfo)));
                    title.setTextColor(ThemesRepo.getColor(R.attr.snackbarInfo));
                    button.setTextColor(ThemesRepo.getColor(R.attr.snackbarInfo));
                    button.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), ColorUtils.setAlphaComponent(ThemesRepo.getColor(R.attr.snackbarInfo), 0x21), 8));
                    setBackgroundColor(ColorUtils.blendARGB(ThemesRepo.getColor(R.attr.snackbarBase), ThemesRepo.getColor(R.attr.snackbarInfo), 0.15f));
                    break;
                case INFO:
                    icon.setImageResource(R.drawable.info_outline_28);
                    icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.snackbarInfo)));
                    title.setTextColor(ThemesRepo.getColor(R.attr.snackbarInfo));
                    button.setTextColor(ThemesRepo.getColor(R.attr.snackbarInfo));
                    button.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), ColorUtils.setAlphaComponent(ThemesRepo.getColor(R.attr.snackbarInfo), 0x21), 8));
                    setBackgroundColor(ColorUtils.blendARGB(ThemesRepo.getColor(R.attr.snackbarBase), ThemesRepo.getColor(R.attr.snackbarInfo), 0.15f));
                    break;
                case ERROR:
                    icon.setImageResource(R.drawable.error_outline_28);
                    icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.snackbarError)));
                    title.setTextColor(ThemesRepo.getColor(R.attr.snackbarError));
                    button.setTextColor(ThemesRepo.getColor(R.attr.snackbarError));
                    button.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), ColorUtils.setAlphaComponent(ThemesRepo.getColor(R.attr.snackbarError), 0x10), 8));
                    setBackgroundColor(ColorUtils.blendARGB(ThemesRepo.getColor(R.attr.snackbarBase), ThemesRepo.getColor(R.attr.snackbarError), 0.15f));
                    break;
            }
            return this;
        }
    }

    public static class Snackbar {
        public CharSequence title;
        public Type type;
        public int lifetime = 2500;
        public String tag;

        public CharSequence buttonTitle;
        public View.OnClickListener buttonClick;

        public Snackbar(Type type, CharSequence title) {
            this.type = type;
            this.title = title;

            if (type == Type.WARNING || type == Type.ERROR) {
                lifetime = 5000;
            }
        }

        public Snackbar tag(String tag) {
            this.lifetime = 0;
            this.tag = tag;
            return this;
        }
    }

    public enum Type {
        DONE, WARNING, INFO, ERROR,
        LOADING // Must use tag
    }
}
