package com.flashforge.farm.utils;

import android.content.Context;
import android.os.Build;
import android.os.Vibrator;

public class VibrationUtils {
    private static Vibrator vibrator;

    public static void init(Context ctx) {
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static boolean hasAmplitudeControl() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl();
    }
}
