package com.flashforge.farm.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the gcode export naming rule: a single object is named after itself,
 * and anything filesystem-hostile is stripped.
 */
public class GCodeExporterTest {

    @Test
    public void keepsPlainName() {
        assertEquals("hogwarts_stamp_v1", GCodeExporter.sanitize("hogwarts_stamp_v1.stl"));
    }

    @Test
    public void stripsExistingGcodeExtension() {
        assertEquals("benchy", GCodeExporter.sanitize("benchy.gcode"));
    }

    @Test
    public void takesBasenameOnly() {
        assertEquals("part_a", GCodeExporter.sanitize("/sdcard/Download/part_a.stl"));
        assertEquals("part_a", GCodeExporter.sanitize("C:\\models\\part_a.stl"));
    }

    @Test
    public void replacesPathSeparatorsAndColons() {
        // A name may not contain the separator that would redirect the write.
        // "a/b:c" reduces to the basename "b", with the colon replaced: "b_c".
        String s = GCodeExporter.sanitize("a/b:c");
        assertEquals("b_c", s);
        assertFalse(s.contains("/"));
        assertFalse(s.contains(":"));
    }

    @Test
    public void collapsesWhitespaceToUnderscore() {
        assertEquals("my_cool_model", GCodeExporter.sanitize("my  cool   model.stl"));
    }

    @Test
    public void stripsLeadingDots() {
        // ".." or ".hidden" would resolve outside the intended name.
        assertEquals("", GCodeExporter.sanitize(".."));
        assertEquals("hidden", GCodeExporter.sanitize(".hidden.stl"));
    }

    @Test
    public void handlesNullAndBlank() {
        assertEquals("", GCodeExporter.sanitize(null));
        assertEquals("", GCodeExporter.sanitize("   "));
    }

    @Test
    public void truncatesVeryLongNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        assertTrue(GCodeExporter.sanitize(sb.toString()).length() <= 64);
    }
}
