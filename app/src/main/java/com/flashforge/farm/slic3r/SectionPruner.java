package com.flashforge.farm.slic3r;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM core of empty-section pruning for settings lists.
 *
 * The settings UI curates option keys that get renamed/removed upstream, so
 * sections (and whole tabs) can resolve to zero rows. Rendering their
 * headers produces empty groups; worse, a leading orphan row makes
 * setConfigItems index category -1 and crash. This core decides which rows
 * survive using only row kinds, so it is covered by plain JUnit tests. The
 * fragment adapts OptionElements to kinds and filters by the result.
 */
public final class SectionPruner {
    private SectionPruner() {}

    public enum Kind {
        /** Tab/category header. Starts a segment. */
        CATEGORY,
        /** Section sub-header. Starts a sub-segment. */
        SECTION,
        /** Real option row (renders a value). */
        OPTION,
        /** Anything else: spacers, invisible placeholders. */
        OTHER
    }

    /**
     * @return keep-flag per input row. A header survives only if at least
     * one OPTION row follows it before the next header; rows before the
     * first surviving category header are dropped.
     */
    public static List<Boolean> prune(List<Kind> kinds) {
        List<Boolean> keep = new ArrayList<>(kinds.size());
        for (int n = 0; n < kinds.size(); n++) keep.add(Boolean.FALSE);
        int i = 0;
        boolean seenCategory = false;
        while (i < kinds.size()) {
            Kind k = kinds.get(i);
            if (k == null) {
                i++;
                continue;
            }
            if (k == Kind.SECTION || k == Kind.CATEGORY) {
                int j = i + 1;
                boolean hasOption = false;
                while (j < kinds.size()) {
                    Kind n = kinds.get(j);
                    // A category owns everything up to the next category,
                    // including sub-sections; a section owns only its own rows.
                    if (n == Kind.CATEGORY || (k == Kind.SECTION && n == Kind.SECTION)) break;
                    if (n == Kind.OPTION) {
                        hasOption = true;
                        break;
                    }
                    j++;
                }
                if (!hasOption) {
                    i++;
                    while (i < kinds.size() && kinds.get(i) == Kind.OTHER) i++;
                    continue;
                }
                if (k == Kind.CATEGORY) {
                    seenCategory = true;
                } else if (!seenCategory) {
                    // Kept section with no surviving category before it: its
                    // rows would still index category -1 downstream. Drop it.
                    i++;
                    while (i < kinds.size() && kinds.get(i) == Kind.OTHER) i++;
                    continue;
                }
            } else if (!seenCategory) {
                i++;
                continue;
            }
            keep.set(i, Boolean.TRUE);
            i++;
        }
        return keep;
    }
}
