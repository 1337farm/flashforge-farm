package com.flashforge.farm.slic3r;

import org.junit.Test;

import static org.junit.Assert.*;

public class EnumLabelResolverTest {

    private static final String[] VALUES = {"none", "external", "all"};
    private static final String[] LABELS = {"None", "Outside walls", "All walls"};

    @Test
    public void testKnownValueResolves() {
        assertEquals(1, EnumLabelResolver.indexOf("external", VALUES, LABELS));
    }

    @Test
    public void testUnknownValueReturnsMinusOne() {
        assertEquals(-1, EnumLabelResolver.indexOf("bogus", VALUES, LABELS));
    }

    @Test
    public void testNumericStringInRangeResolves() {
        assertEquals(2, EnumLabelResolver.indexOf("2", VALUES, LABELS));
    }

    @Test
    public void testNumericStringOutOfRangeReturnsMinusOne() {
        // Regression: ArrayIndexOutOfBoundsException length=6 index=50 on device.
        assertEquals(-1, EnumLabelResolver.indexOf("50", VALUES, LABELS));
    }

    @Test
    public void testHugeDigitStringDoesNotThrow() {
        // Matches ^\d+$ but overflows int: must not throw NumberFormatException.
        assertEquals(-1, EnumLabelResolver.indexOf("99999999999999999999", VALUES, LABELS));
    }

    @Test
    public void testNullInputsReturnMinusOne() {
        assertEquals(-1, EnumLabelResolver.indexOf(null, VALUES, LABELS));
        assertEquals(-1, EnumLabelResolver.indexOf("external", null, LABELS));
        assertEquals(-1, EnumLabelResolver.indexOf("external", VALUES, null));
    }

    @Test
    public void testNullArraysReturnMinusOne() {
        assertEquals(-1, EnumLabelResolver.indexOf("external", (String[]) null, LABELS));
        assertEquals(-1, EnumLabelResolver.indexOf("external", VALUES, (String[]) null));
    }

    @Test
    public void testShortLabelsArrayIsBoundsChecked() {
        String[] shortLabels = {"None"};
        assertEquals(0, EnumLabelResolver.indexOf("none", VALUES, shortLabels));
        assertEquals(-1, EnumLabelResolver.indexOf("external", VALUES, shortLabels));
    }

    @Test
    public void testOrEmpty() {
        assertEquals("", EnumLabelResolver.orEmpty(null));
        assertEquals("x", EnumLabelResolver.orEmpty("x"));
    }
}
