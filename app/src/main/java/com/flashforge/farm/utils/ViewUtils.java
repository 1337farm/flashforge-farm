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
}
