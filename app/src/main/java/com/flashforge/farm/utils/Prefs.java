package com.flashforge.farm.utils;

import android.app.Application;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.flashforge.farm.BuildConfig;
import com.flashforge.farm.R;
import com.flashforge.farm.SetupActivity;

public class Prefs {
    public final static int CAMERA_CONTROL_MODE_ROTATE_MOVE = 0,
                            CAMERA_CONTROL_MODE_MOVE_ROTATE = 1,
                            CAMERA_CONTROL_MODE_MOVE_ONLY = 2;

    private static SharedPreferences mPrefs;

    public static void init(Application ctx) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static SharedPreferences getPrefs() {
        return mPrefs;
    }

    public static String getLastCommit() {
        return mPrefs.getString("last_commit", null);
    }

    public static void setLastCommit() {
        mPrefs.edit().putString("last_commit", BuildConfig.COMMIT).apply();
    }

    /**
     * Up to 16 filaments used for multi-color printing/painting. Each is a material type + color,
     * stored as comma-separated "TYPE|RRGGBB".
     */
    public final static int MAX_FILAMENT_COLORS = 16;

    public static java.util.List<FilamentSlot> getFilamentSlots() {
        java.util.List<FilamentSlot> slots = new java.util.ArrayList<>();
        String raw = mPrefs.getString("filament_palette", null);
        if (raw == null || raw.isEmpty()) {
            slots.add(new FilamentSlot(0xFF1AC5A2, "PLA")); // flashforge-farm teal PLA as the single default
            return slots;
        }
        for (String part : raw.split(",")) {
            if (part.isEmpty()) continue;
            String type = "PLA";
            String hex = part;
            int bar = part.indexOf('|');
            if (bar >= 0) {
                type = part.substring(0, bar);
                hex = part.substring(bar + 1);
            }
            int color;
            try {
                color = (int) Long.parseLong(hex.trim(), 16) | 0xFF000000;
            } catch (NumberFormatException e) {
                color = 0xFF1AC5A2;
            }
            slots.add(new FilamentSlot(color, type));
            if (slots.size() >= MAX_FILAMENT_COLORS) break;
        }
        if (slots.isEmpty()) slots.add(new FilamentSlot(0xFF1AC5A2, "PLA"));
        return slots;
    }

    public static void setFilamentSlots(java.util.List<FilamentSlot> slots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(slots.size(), MAX_FILAMENT_COLORS); i++) {
            if (sb.length() > 0) sb.append(',');
            FilamentSlot s = slots.get(i);
            sb.append(s.type).append('|').append(String.format("%06X", s.color & 0xFFFFFF));
        }
        mPrefs.edit().putString("filament_palette", sb.toString()).apply();
    }

    /** Colors only, derived from the filament slots (e.g. for rendering). */
    public static int[] getFilamentPalette() {
        java.util.List<FilamentSlot> slots = getFilamentSlots();
        int[] colors = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) colors[i] = slots.get(i).color;
        return colors;
    }

    public static boolean isScaleInputInMM() {
        return mPrefs.getBoolean("scale_input_mm", false);
    }

    public static void setScaleInputInMM(boolean v) {
        mPrefs.edit().putBoolean("scale_input_mm", v).apply();
    }

    public static boolean isScaleLinked() {
        return mPrefs.getBoolean("scale_linked", true);
    }

    public static void setScaleLinked(boolean v) {
        mPrefs.edit().putBoolean("scale_linked", v).apply();
    }

    public static long getLastCheckedInfo() {
        return mPrefs.getLong("last_checked_info", 0);
    }

    public static void setLastCheckedInfo() {
        mPrefs.edit().putLong("last_checked_info", System.currentTimeMillis()).apply();
    }

    public static int getCameraControlMode() {
        return mPrefs.getInt("camera_control_mode", mPrefs.getBoolean("rotation_enabled", true) ? CAMERA_CONTROL_MODE_ROTATE_MOVE : CAMERA_CONTROL_MODE_MOVE_ONLY);
    }

    public static void setCameraControlMode(int mode) {
        mPrefs.edit().putInt("camera_control_mode", mode).apply();
    }

    public static boolean isOrthoProjectionEnabled() {
        return mPrefs.getBoolean("ortho_projection", true);
    }

    public static void setOrthoProjectionEnabled(boolean e) {
        mPrefs.edit().putBoolean("ortho_projection", e).apply();
    }

    public static float getCameraSensitivity() {
        return 5f;
    }

    public static int getAccentColor() {
        return mPrefs.getInt("accent", SetupActivity.AccentColors.DEFAULT.color);
    }

    public static void setAccentColor(int color) {
        mPrefs.edit().putInt("accent", color).apply();
    }

    public static boolean isVibrationEnabled() {
        return mPrefs.getBoolean("vibration", true);
    }

    public static float getRenderScale() {
        return mPrefs.getFloat("render_scale", 1f);
    }

    public static void setRenderScale(float s) {
        mPrefs.edit().putFloat("render_scale", s).apply();
    }

    private static ThemeMode cachedThemeMode;
    public static ThemeMode getThemeMode() {
        if (cachedThemeMode == null) {
            cachedThemeMode = ThemeMode.values()[mPrefs.getInt("theme_mode", 0)];
        }
        return cachedThemeMode;
    }

    public static void setThemeMode(int i) {
        mPrefs.edit().putInt("theme_mode", i).apply();
        cachedThemeMode = null;
    }

    public static boolean isPerformanceModeEnabled() {
        return mPrefs.getBoolean("performance_mode", false);
    }

    public static void setPerformanceModeEnabled(boolean en) {
        mPrefs.edit().putBoolean("performance_mode", en).apply();
    }

    public enum ThemeMode {
        SYSTEM(R.string.SettingsInterfaceThemeSystem),
        LIGHT(R.string.SettingsInterfaceThemeLight),
        DARK(R.string.SettingsInterfaceThemeDark);

        public final int title;

        ThemeMode(int title) {
            this.title = title;
        }
    }
}
