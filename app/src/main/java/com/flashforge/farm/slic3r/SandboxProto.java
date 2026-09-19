package com.flashforge.farm.slic3r;

/**
 * Pure-Java protocol constants for the isolated slice sandbox (issue #47).
 * Kept free of android.* imports so it is unit-testable on the JVM.
 */
public final class SandboxProto {
    private SandboxProto() {
    }

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR_CONTENT = 1;
    public static final int STATUS_ERROR_INFRA = 2;

    public static final String KEY_STATUS = "status";
    public static final String KEY_DETAIL = "detail";
    public static final String KEY_NUM_FILAMENTS = "numFilaments";
    public static final String KEY_COLORS = "colors";
    public static final String KEY_CALIB_MODE = "calibMode";
    public static final String KEY_CALIB_START = "calibStart";
    public static final String KEY_CALIB_END = "calibEnd";
    public static final String KEY_CALIB_STEP = "calibStep";
    public static final String KEY_BASENAME = "baseName";
    public static final String KEY_PLATE_ID = "plateId";

    /**
     * Path through which the sandbox opens an FD it received over Binder.
     * Isolated processes cannot traverse app-private dirs, but opening an
     * already-open FD via /proc/self/fd bypasses path permission checks
     * (the SELinux check happened at the original open in the app process).
     * Requires seekable FDs (regular files); the caller must keep the
     * ParcelFileDescriptor alive until the native call returns.
     */
    public static String fdPath(int fd) {
        if (fd < 0) {
            throw new IllegalArgumentException("negative fd: " + fd);
        }
        return "/proc/self/fd/" + fd;
    }
}
