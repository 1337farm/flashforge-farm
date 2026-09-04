package com.flashforge.farm.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class ViewUtilsTest {

    private static final double DELTA = 1e-6;
    private static final float FLOAT_DELTA = 1e-6f;

    @Test
    public void testLerp() {
        // Test progress = 0
        assertEquals(10.0f, ViewUtils.lerp(10.0f, 20.0f, 0f), FLOAT_DELTA);

        // Test progress = 0.5
        assertEquals(15.0f, ViewUtils.lerp(10.0f, 20.0f, 0.5f), FLOAT_DELTA);

        // Test progress = 1.0
        assertEquals(20.0f, ViewUtils.lerp(10.0f, 20.0f, 1.0f), FLOAT_DELTA);
    }

    @Test
    public void testLerpd3Args() {
        // Test progress = 0
        assertEquals(10.0, ViewUtils.lerpd(10.0, 20.0, 0f), DELTA);

        // Test progress = 0.5
        assertEquals(15.0, ViewUtils.lerpd(10.0, 20.0, 0.5f), DELTA);

        // Test progress = 1.0
        assertEquals(20.0, ViewUtils.lerpd(10.0, 20.0, 1.0f), DELTA);
    }

    @Test
    public void testLerpd4Args() {
        // Test progress = 0
        assertEquals(10.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 0f), DELTA);

        // Test progress = 0.5
        assertEquals(20.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 0.5f), DELTA);

        // Test progress = 1.0
        assertEquals(30.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 1.0f), DELTA);

        // Test intermediate progress
        assertEquals(15.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 0.25f), DELTA);
        assertEquals(25.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 0.75f), DELTA);

        // Extrapolation below 0
        assertEquals(5.0, ViewUtils.lerpd(10.0, 20.0, 30.0, -0.25f), DELTA);

        // Extrapolation above 1
        assertEquals(35.0, ViewUtils.lerpd(10.0, 20.0, 30.0, 1.25f), DELTA);
    }


    @Test
    public void testLerpFloat() {
        // Normal cases
        assertEquals(0.0f, ViewUtils.lerp(0.0f, 10.0f, 0.0f), FLOAT_DELTA);
        assertEquals(5.0f, ViewUtils.lerp(0.0f, 10.0f, 0.5f), FLOAT_DELTA);
        assertEquals(10.0f, ViewUtils.lerp(0.0f, 10.0f, 1.0f), FLOAT_DELTA);

        // Negative progress (extrapolation)
        assertEquals(-5.0f, ViewUtils.lerp(0.0f, 10.0f, -0.5f), FLOAT_DELTA);

        // Progress > 1 (extrapolation)
        assertEquals(15.0f, ViewUtils.lerp(0.0f, 10.0f, 1.5f), FLOAT_DELTA);

        // Inverse lerp
        assertEquals(10.0f, ViewUtils.lerp(10.0f, 0.0f, 0.0f), FLOAT_DELTA);
        assertEquals(5.0f, ViewUtils.lerp(10.0f, 0.0f, 0.5f), FLOAT_DELTA);
        assertEquals(0.0f, ViewUtils.lerp(10.0f, 0.0f, 1.0f), FLOAT_DELTA);

        // Negative values
        assertEquals(-5.0f, ViewUtils.lerp(-10.0f, 0.0f, 0.5f), FLOAT_DELTA);
    }


    @Test
    public void testLerpdDoubleDoubleFloat() {
        // Normal cases
        assertEquals(0.0, ViewUtils.lerpd(0.0, 10.0, 0.0f), DELTA);
        assertEquals(5.0, ViewUtils.lerpd(0.0, 10.0, 0.5f), DELTA);
        assertEquals(10.0, ViewUtils.lerpd(0.0, 10.0, 1.0f), DELTA);

        // Negative progress (extrapolation)
        assertEquals(-5.0, ViewUtils.lerpd(0.0, 10.0, -0.5f), DELTA);

        // Progress > 1 (extrapolation)
        assertEquals(15.0, ViewUtils.lerpd(0.0, 10.0, 1.5f), DELTA);

        // Inverse lerp
        assertEquals(10.0, ViewUtils.lerpd(10.0, 0.0, 0.0f), DELTA);
        assertEquals(5.0, ViewUtils.lerpd(10.0, 0.0, 0.5f), DELTA);
        assertEquals(0.0, ViewUtils.lerpd(10.0, 0.0, 1.0f), DELTA);

        // Negative values
        assertEquals(-5.0, ViewUtils.lerpd(-10.0, 0.0, 0.5f), DELTA);
    }


    @Test
    public void testLerpdDoubleDoubleDoubleFloat() {
        // The implementation:
        // lerpd(double a, double b, double c, float progress) {
        //   return lerpd(lerpd(a, b, Math.min(progress, 0.5f) / 0.5f), c, (Math.max(progress, 0.5f) - 0.5f) / 0.5f);
        // }
        // Let's break it down:
        // If progress <= 0.5f, the outer lerpd uses 0.0 for its progress,
        // effectively returning the inner lerpd(a, b, progress / 0.5f).
        // If progress >= 0.5f, the inner lerpd uses 1.0 for its progress (returning b),
        // and the outer lerpd uses (progress - 0.5f) / 0.5f for its progress (from b to c).

        // At progress 0.0: should return a
        assertEquals(0.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.0f), DELTA);

        // At progress 0.25 (midway between a and b): should return 5.0
        assertEquals(5.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.25f), DELTA);

        // At progress 0.5: should return b
        assertEquals(10.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.5f), DELTA);

        // At progress 0.75 (midway between b and c): should return 15.0
        assertEquals(15.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.75f), DELTA);

        // At progress 1.0: should return c
        assertEquals(20.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 1.0f), DELTA);

        // Extrapolation cases:
        // If progress < 0, inner is a + (b-a)*(progress/0.5), outer uses 0 for progress so returns inner
        assertEquals(-10.0, ViewUtils.lerpd(0.0, 10.0, 20.0, -0.5f), DELTA);

        // If progress > 1, inner is b, outer uses (progress-0.5)/0.5 = progress*2 - 1
        // so b + (c-b)*(progress*2 - 1) -> 10 + 10*(1.5*2 - 1) = 10 + 20 = 30
        assertEquals(30.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 1.5f), DELTA);
    }


    @Test
    public void testLerpd3Params() {
        assertEquals(5.0, ViewUtils.lerpd(0.0, 10.0, 0.5f), 0.001);
        assertEquals(0.0, ViewUtils.lerpd(0.0, 10.0, 0.0f), 0.001);
        assertEquals(10.0, ViewUtils.lerpd(0.0, 10.0, 1.0f), 0.001);

        // Extrapolation
        assertEquals(15.0, ViewUtils.lerpd(0.0, 10.0, 1.5f), 0.001);
        assertEquals(-5.0, ViewUtils.lerpd(0.0, 10.0, -0.5f), 0.001);

        // Negative values
        assertEquals(-5.0, ViewUtils.lerpd(-10.0, 0.0, 0.5f), 0.001);
    }


    @Test
    public void testLerp3Params() {
        assertEquals(5.0f, ViewUtils.lerp(0.0f, 10.0f, 0.5f), 0.001);
        assertEquals(0.0f, ViewUtils.lerp(0.0f, 10.0f, 0.0f), 0.001);
        assertEquals(10.0f, ViewUtils.lerp(0.0f, 10.0f, 1.0f), 0.001);
    }


    @Test
    public void testLerpd4Params() {
        assertEquals(0.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.0f), 0.001);
        assertEquals(5.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.25f), 0.001);
        assertEquals(10.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.5f), 0.001);
        assertEquals(15.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 0.75f), 0.001);
        assertEquals(20.0, ViewUtils.lerpd(0.0, 10.0, 20.0, 1.0f), 0.001);
    }
}
