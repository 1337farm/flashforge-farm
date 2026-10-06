package com.flashforge.farm.slic3r;

import java.util.Arrays;
import java.util.List;

/**
 * Pure-JVM resolution of ENUM option values to display-label indexes.
 *
 * Extracted from ProfileListFragment so the crash-prone paths
 * (ArrayIndexOutOfBounds on numeric values, NumberFormatException on huge
 * digit strings, null values/labels from the native bridge) are covered by
 * plain JUnit tests without Android. Callers localize the resolved label.
 */
public final class EnumLabelResolver {
    private EnumLabelResolver() {}

    /**
     * @return index into {@code labels} for {@code value}, or -1 when the
     * value cannot be mapped (null input, unknown value, numeric index out
     * of range, or digit string overflowing int). Never throws.
     */
    public static int indexOf(String value, List<String> values, List<String> labels) {
        if (value == null || values == null || labels == null) return -1;
        int i = values.indexOf(value);
        if (i != -1 && i < labels.size()) return i;
        if (value.matches("^\\d+$")) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed >= 0 && parsed < labels.size()) return parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** Array overload for call sites holding String[]. */
    public static int indexOf(String value, String[] values, String[] labels) {
        if (value == null || values == null || labels == null) return -1;
        return indexOf(value, Arrays.asList(values), Arrays.asList(labels));
    }

    /** Null-safe coalescing for scalar row values. Never returns null. */
    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
