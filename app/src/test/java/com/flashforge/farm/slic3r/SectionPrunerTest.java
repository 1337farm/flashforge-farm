package com.flashforge.farm.slic3r;

import static com.flashforge.farm.slic3r.SectionPruner.Kind.CATEGORY;
import static com.flashforge.farm.slic3r.SectionPruner.Kind.OPTION;
import static com.flashforge.farm.slic3r.SectionPruner.Kind.OTHER;
import static com.flashforge.farm.slic3r.SectionPruner.Kind.SECTION;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SectionPrunerTest {

    private static List<Boolean> prune(SectionPruner.Kind... kinds) {
        return SectionPruner.prune(Arrays.asList(kinds));
    }

    private static void assertKeep(boolean[] expected, List<Boolean> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i, expected[i], actual.get(i));
        }
    }

    @Test
    public void testEmptyList() {
        assertTrue(prune().isEmpty());
    }

    @Test
    public void testHealthySectionSurvives() {
        assertKeep(new boolean[]{true, true, true, true},
                prune(CATEGORY, SECTION, OPTION, OTHER));
    }

    @Test
    public void testEmptySectionHeaderAndSpacerDropped() {
        assertKeep(new boolean[]{true, false, false, true, true},
                prune(CATEGORY, SECTION, OTHER, SECTION, OPTION));
    }

    @Test
    public void testEmptyCategoryDroppedWithOrphans() {
        // Regression: leading orphan spacer indexed category -1 and NPE'd
        // setConfigItems (FATAL on opening settings screens).
        assertKeep(new boolean[]{false, false, false, true, true},
                prune(CATEGORY, OTHER, OTHER, CATEGORY, OPTION));
    }

    @Test
    public void testAllEmptyYieldsEmpty() {
        assertKeep(new boolean[]{false, false, false},
                prune(CATEGORY, SECTION, OTHER));
    }

    @Test
    public void testPlaceholdersDoNotSaveASection() {
        // Missing defs degrade to invisible placeholders (OTHER): they must
        // not keep a header alive, or empty headers render again.
        // A section with no surviving category is dropped too: its rows
        // would index category -1 downstream.
        assertKeep(new boolean[]{false, false, false, false},
                prune(SECTION, OTHER, SECTION, OPTION));
    }

    @Test
    public void testNullKindsTolerated() {
        List<Boolean> out = SectionPruner.prune(
                Arrays.asList(CATEGORY, null, OPTION));
        assertKeep(new boolean[]{true, false, true}, out);
    }
}
