package com.flashforge.farm.utils;

import org.junit.Test;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertThrows;

public class DebugUtilsTest {



    @Test
    public void testAssertTrueWithTrue() {
        // Should not throw an exception
        try {
            DebugUtils.assertTrue(true);
        } catch (AssertionError e) {
            fail("assertTrue(true) should not throw an AssertionError");
        }
    }


    @Test
    public void testAssertTrueWithFalse() {
        // Should throw an exception
        try {
            DebugUtils.assertTrue(false);
            fail("assertTrue(false) should throw an AssertionError");
        } catch (AssertionError e) {
            // Expected behavior
        }
    }


    @Test
    public void testAssertFalseWithFalse() {
        // Should not throw an exception
        try {
            DebugUtils.assertFalse(false);
        } catch (AssertionError e) {
            fail("assertFalse(false) should not throw an AssertionError");
        }
    }


    @Test
    public void testAssertFalseWithTrue() {
        // Should throw an exception
        try {
            DebugUtils.assertFalse(true);
            fail("assertFalse(true) should throw an AssertionError");
        } catch (AssertionError e) {
            // Expected behavior
        }
    }


    @Test
    public void assertTrue_withTrue_doesNotThrow() {
        DebugUtils.assertTrue(true);
    }


    @Test
    public void assertTrue_withFalse_throwsAssertionError() {
        assertThrows(AssertionError.class, () -> {
            DebugUtils.assertTrue(false);
        });
    }


    @Test
    public void assertFalse_withFalse_doesNotThrow() {
        DebugUtils.assertFalse(false);
    }


    @Test
    public void assertFalse_withTrue_throwsAssertionError() {
        assertThrows(AssertionError.class, () -> {
            DebugUtils.assertFalse(true);
        });
    }
}
