package com.flashforge.farm.gallery;

/**
 * Single source of truth for PA calibration arming: gallery-kind to native
 * CalibMode ordinal plus the default sweep modifiers. Both the calibrations
 * list (FileMenu) and the shape-gallery taps (ShapeGalleryMenu) go through
 * here so a kind can never arm the wrong engine mode or a stale sweep.
 *
 * Mode ordinals must match the native {@code Slic3r::CalibMode} enum:
 * 0 = none, 1 = PA line, 2 = PA pattern, 3 = PA tower.
 */
public final class CalibArm {
    private CalibArm() {
    }

    public static final int MODE_NONE = 0;
    public static final int MODE_PA_LINE = 1;
    public static final int MODE_PA_PATTERN = 2;
    public static final int MODE_PA_TOWER = 3;

    public static final double DEFAULT_START = 0;
    public static final double DEFAULT_END = 0.1;
    public static final double DEFAULT_STEP = 0.002;

    public static boolean isCalibKind(int kind) {
        return kind == ShapeGallery.KIND_CALIB_PA_LINE
                || kind == ShapeGallery.KIND_CALIB_PA_PATTERN
                || kind == ShapeGallery.KIND_CALIB_PA_TOWER;
    }

    /** Engine mode for a calib kind; throws for non-calib kinds. */
    public static int modeForKind(int kind) {
        if (kind == ShapeGallery.KIND_CALIB_PA_PATTERN) return MODE_PA_PATTERN;
        if (kind == ShapeGallery.KIND_CALIB_PA_TOWER) return MODE_PA_TOWER;
        if (kind == ShapeGallery.KIND_CALIB_PA_LINE) return MODE_PA_LINE;
        throw new IllegalArgumentException("not a calib kind: " + kind);
    }

    /** Number of sweep steps the defaults produce; must be positive. */
    public static int defaultStepCount() {
        return (int) Math.round(Math.ceil((DEFAULT_END - DEFAULT_START) / DEFAULT_STEP)) + 1;
    }
}
