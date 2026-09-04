package com.flashforge.farm.utils;

import androidx.annotation.Nullable;

public class ThreadLocalDoubleArray extends ThreadLocal<double[]> {
    private final int size;

    public ThreadLocalDoubleArray(int size) {
        this.size = size;
    }

    @Nullable
    @Override
    protected double[] initialValue() {
        return new double[size];
    }
}
