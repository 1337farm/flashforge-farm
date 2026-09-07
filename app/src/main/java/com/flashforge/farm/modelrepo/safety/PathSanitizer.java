package com.flashforge.farm.modelrepo.safety;

import com.flashforge.farm.modelrepo.ModelSafety;

import java.io.File;

public final class PathSanitizer {
    private PathSanitizer() {
    }

    public static String clean(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        if (name.length() > SafetyPolicy.MAX_FILENAME_LEN) {
            throw new IllegalArgumentException("name too long");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == 0) {
                throw new IllegalArgumentException("null byte in name");
            }
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("control char in name");
            }
            if (c >= 0x202A && c <= 0x202E) {
                throw new IllegalArgumentException("bidi override in name");
            }
            if (c >= 0x2066 && c <= 0x2069) {
                throw new IllegalArgumentException("bidi isolate in name");
            }
        }
        String base = new File(name).getName();
        if (!base.equals(name)) {
            throw new IllegalArgumentException("path separator in name");
        }
        String lower = base.toLowerCase();
        String[] reserved = {"con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
                "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4",
                "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"};
        String stem = lower;
        int dot = lower.lastIndexOf('.');
        if (dot > 0) {
            stem = lower.substring(0, dot);
        }
        for (String r : reserved) {
            if (stem.equals(r)) {
                throw new IllegalArgumentException("reserved name");
            }
        }
        if (base.endsWith(".") || base.endsWith(" ")) {
            throw new IllegalArgumentException("trailing dot/space in name");
        }
        if (!ModelSafety.isAllowedName(base)) {
            throw new IllegalArgumentException("disallowed type: " + base);
        }
        return base;
    }

    public static File child(File dir, String name) throws Exception {
        return ModelSafety.canonicalChild(dir, clean(name));
    }
}