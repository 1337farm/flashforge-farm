package com.flashforge.farm.utils;

import com.flashforge.farm.BuildConfig;

public class DebugUtils {
    public static void assertTrue(boolean value) {
        throwIfNot(value);
    }

    public static void assertFalse(boolean value) {
        throwIfNot(!value);
    }

    private static void throwIfNot(boolean value) {
        if (!BuildConfig.DEBUG) return;
        if (!value) {
            throw new AssertionError("Assert failed");
        }
    }
}
