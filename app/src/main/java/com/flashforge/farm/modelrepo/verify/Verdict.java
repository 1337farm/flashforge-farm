package com.flashforge.farm.modelrepo.verify;

public final class Verdict {
    public enum Reason {
        OK,
        BAD_METADATA,
        UNSAFE_NAME,
        UNSAFE_TYPE,
        OVER_BUDGET,
        HASH_MISMATCH,
        BLOCKED_PUBLISHER,
        UNTRUSTED_PUBLISHER,
        REVOKED,
        FLAGGED
    }

    public final boolean allow;
    public final boolean needsConfirm;
    public final Reason reason;
    public final String detail;

    private Verdict(boolean allow, boolean needsConfirm, Reason reason, String detail) {
        this.allow = allow;
        this.needsConfirm = needsConfirm;
        this.reason = reason;
        this.detail = detail == null ? "" : detail;
    }

    public static Verdict ok() {
        return new Verdict(true, false, Reason.OK, "");
    }

    public static Verdict confirm(Reason reason, String detail) {
        return new Verdict(true, true, reason, detail);
    }

    public static Verdict deny(Reason reason, String detail) {
        return new Verdict(false, false, reason, detail);
    }
}