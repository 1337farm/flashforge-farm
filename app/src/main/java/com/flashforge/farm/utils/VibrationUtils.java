package com.flashforge.farm.utils;

import android.content.Context;
import android.os.Vibrator;

public class VibrationUtils {
    private static Vibrator vibrator;

    public static void init(Context ctx) {
        vibrator = ctx.getSystemService(Vibrator.class);
    }

    public static boolean hasAmplitudeControl() {
        return vibrator != null && vibrator.hasAmplitudeControl();
    }
}
