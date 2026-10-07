package com.flashforge.farm.slic3r;

import org.junit.Test;

import static org.junit.Assert.*;

public class SettingsSearchTest {

    private static final String ROW =
            "extrusion_width Default extrusion width Line width Set manual extrusion width";

    @Test
    public void testEmptyQueryMatches() {
        assertTrue(SettingsSearch.matches("", ROW));
        assertTrue(SettingsSearch.matches("   ", ROW));
    }

    @Test
    public void testNullSafe() {
        assertFalse(SettingsSearch.matches(null, ROW));
        assertFalse(SettingsSearch.matches("width", null));
    }

    @Test
    public void testCaseInsensitiveSubstring() {
        assertTrue(SettingsSearch.matches("WIDTH", ROW));
        assertTrue(SettingsSearch.matches("extrusion", ROW));
    }

    @Test
    public void testMultiTokenRequiresAll() {
        assertTrue(SettingsSearch.matches("line width", ROW));
        assertFalse(SettingsSearch.matches("line height", ROW));
    }

    @Test
    public void testKeySearchableVerbatim() {
        // Engine key names are kept verbatim (no translation) so power
        // users can find options by their config-file names.
        assertTrue(SettingsSearch.matches("extrusion_width", ROW));
    }

    @Test
    public void testOptionTextSkipsNulls() {
        assertEquals("k l", SettingsSearch.optionText("k", "l", null, ""));
        assertEquals("", SettingsSearch.optionText(null, null, null, null));
    }
}
