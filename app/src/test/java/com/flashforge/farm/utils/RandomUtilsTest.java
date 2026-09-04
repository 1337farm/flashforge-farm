package com.flashforge.farm.utils;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class RandomUtilsTest {

    private static final int ITERATIONS = 10000;

    @Test
    public void testRandomfPositiveRange() {
        float min = 1.0f;
        float max = 10.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }

    @Test
    public void testRandomfNegativeRange() {
        float min = -20.0f;
        float max = -5.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }

    @Test
    public void testRandomfCrossingZero() {
        float min = -10.0f;
        float max = 10.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }

    @Test
    public void testRandomfEqualBounds() {
        float min = 5.5f;
        float max = 5.5f;
        for (int i = 0; i < 100; i++) {
            float result = RandomUtils.randomf(min, max);
            assertEquals("Result should be exactly " + min, min, result, 0.0f);
        }
    }

    @Test
    public void testRandomfReversedBounds() {
        float min = 10.0f;
        float max = 0.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            // Even if bounds are reversed, the result should be between min and max (inclusive)
            assertTrue("Result " + result + " should be <= " + min, result <= min);
            assertTrue("Result " + result + " should be >= " + max, result >= max);
        }
    }


    @Test
    public void testRandomlPositiveBounds() {
        long min = 10L;
        long max = 100L;
        for (int i = 0; i < ITERATIONS; i++) {
            long result = RandomUtils.randoml(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }


    @Test
    public void testRandomlNegativeBounds() {
        long min = -100L;
        long max = -10L;
        for (int i = 0; i < ITERATIONS; i++) {
            long result = RandomUtils.randoml(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }


    @Test
    public void testRandomlMixedBounds() {
        long min = -50L;
        long max = 50L;
        for (int i = 0; i < ITERATIONS; i++) {
            long result = RandomUtils.randoml(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }


    @Test
    public void testRandomlEqualBounds() {
        long min = 42L;
        long max = 42L;
        for (int i = 0; i < ITERATIONS; i++) {
            long result = RandomUtils.randoml(min, max);
            assertEquals("Result should equal min when min == max", min, result);
        }
    }


    @Test
    public void testRandomfPositiveBounds() {
        float min = 10.0f;
        float max = 100.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }


    @Test
    public void testRandomfNegativeBounds() {
        float min = -100.0f;
        float max = -10.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }


    @Test
    public void testRandomfMixedBounds() {
        float min = -50.0f;
        float max = 50.0f;
        for (int i = 0; i < ITERATIONS; i++) {
            float result = RandomUtils.randomf(min, max);
            assertTrue("Result " + result + " should be >= " + min, result >= min);
            assertTrue("Result " + result + " should be <= " + max, result <= max);
        }
    }
}
