package com.flashforge.farm.utils;

import android.animation.TimeInterpolator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

import com.flashforge.farm.FarmApp;

public class ViewUtils {
    public final static TimeInterpolator CUBIC_INTERPOLATOR = new PathInterpolator(0.25f, 0, 0.25f, 1f);
    public final static String ROBOTO_MEDIUM = "roboto_medium";

    private static Handler uiHandler = new Handler(Looper.getMainLooper());
    private static Map<String, Typeface> typefaceCache = new HashMap<>();


    public static Handler getUiHandler() {
        return uiHandler;
    }

    public static void postOnMainThread(Runnable runnable) {
        uiHandler.post(runnable);
    }

    public static void postOnMainThread(Runnable runnable, long delay) {
        uiHandler.postDelayed(runnable, delay);
    }

    public static void removeCallbacks(Runnable runnable) {
        uiHandler.removeCallbacks(runnable);
    }

    public static Typeface getTypeface(String key) {
        Typeface typeface = typefaceCache.get(key);
        if (typeface == null) {
            typefaceCache.put(key, typeface = Typeface.createFromAsset(FarmApp.INSTANCE.getAssets(), "font/" + key + ".ttf"));
        }
        return typeface;
    }

    public static float lerp(float a, float b, float progress) {
        return a + (b - a) * progress;
    }

    public static double lerpd(double a, double b, double c, float progress) {
        return lerpd(lerpd(a, b, Math.min(progress, 0.5f) / 0.5f), c, (Math.max(progress, 0.5f) - 0.5f) / 0.5f);
    }

    public static double lerpd(double a, double b, float progress) {
        return a + (b - a) * progress;
    }

    public static RippleDrawable createRipple(int color, float radiusDp) {
        return createRipple(color, 0, radiusDp);
    }

    public static RippleDrawable createRipple(int color, int fillColor, float radiusDp) {
        if (radiusDp == -1) {
            return new RippleDrawable(ColorStateList.valueOf(color), null, null);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.BLACK);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(color), fillColor != 0 ? new GradientDrawable() {{
            setColor(fillColor);
            setCornerRadius(dp(radiusDp));
        }} : null, mask);
    }

    public static int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, FarmApp.INSTANCE.getResources().getDisplayMetrics());
    }

    public static android.widget.TextView makeText(Context ctx, String text, float textSizeDp, int color) {
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeDp);
        tv.setTextColor(color);
        return tv;
    }

    public static android.widget.EditText makeEditText(Context ctx, String text) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(text);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return et;
    }

    public static void addLabeledEditText(android.widget.LinearLayout parent, String labelText, String hintText, android.widget.EditText editText) {
        android.widget.LinearLayout itemLayout = new android.widget.LinearLayout(parent.getContext());
        itemLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        itemLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.TextView label = new android.widget.TextView(parent.getContext());
        label.setText(labelText);
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        itemLayout.addView(label);
        editText.setHint(hintText);
        editText.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        itemLayout.addView(editText);
        parent.addView(itemLayout);
    }
}
