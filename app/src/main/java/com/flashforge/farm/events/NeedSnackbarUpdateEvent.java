package com.flashforge.farm.events;


public class NeedSnackbarUpdateEvent {
    public final String tag;
    public final int progress;
    public final CharSequence step;
    public final CharSequence detail;

    public NeedSnackbarUpdateEvent(String tag, int progress, CharSequence step) {
        this(tag, progress, step, null);
    }

    public NeedSnackbarUpdateEvent(String tag, int progress, CharSequence step, CharSequence detail) {
        this.tag = tag;
        this.progress = progress;
        this.step = step;
        this.detail = detail;
    }
}