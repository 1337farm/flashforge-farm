package com.flashforge.farm.modelrepo.safety;

public final class SafetyPolicy {
    private SafetyPolicy() {
    }

    public static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    public static final long MAX_PARSE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    public static final int MAX_FILE_COUNT = 100;

    public static final long MAX_METADATA_BYTES = 256L * 1024;
    public static final int MAX_JSON_DEPTH = 16;
    public static final int MAX_JSON_STRING = 64 * 1024;

    public static final long MAX_FACETS = 10_000_000L;

    public static final long MAX_IMAGE_PIXELS = 4096L * 4096L;
    public static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

    public static final long MAX_ZIP_OUTPUT_BYTES = 500L * 1024 * 1024;
    public static final double MAX_ZIP_RATIO = 100.0;
    public static final int MAX_ZIP_ENTRIES = 2000;

    public static final int MAX_FILENAME_LEN = 128;
    public static final int MAX_TITLE_LEN = 200;
    public static final int MAX_DESC_LEN = 4000;

    public static final long MAX_PROFILE_BYTES = 16L * 1024;
    public static final int MAX_PROFILE_NAME = 64;
    public static final int MAX_PROFILE_BIO = 500;

    public static final long MAX_FEED_BYTES = 256L * 1024;
    public static final int MAX_FEED_LABELS = 10000;
}