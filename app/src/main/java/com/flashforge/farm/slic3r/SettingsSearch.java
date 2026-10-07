package com.flashforge.farm.slic3r;

import java.util.Locale;

/**
 * Pure-JVM matcher for the universal settings search.
 *
 * Every whitespace-separated token of the query must appear somewhere in
 * the row's searchable text (option key + labels + tooltip, or section
 * title). Case-insensitive, null-safe, no Android dependencies so it is
 * covered by plain JUnit tests.
 */
public final class SettingsSearch {
    private SettingsSearch() {}

    public static boolean matches(String query, String haystack) {
        if (query == null || haystack == null) return false;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String token : q.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (!h.contains(token)) return false;
        }
        return true;
    }

    /** Builds the searchable text for an option row (engine key names kept verbatim). */
    public static String optionText(String key, String label, String fullLabel, String tooltip) {
        StringBuilder sb = new StringBuilder();
        append(sb, key);
        append(sb, label);
        append(sb, fullLabel);
        append(sb, tooltip);
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
    }
}
