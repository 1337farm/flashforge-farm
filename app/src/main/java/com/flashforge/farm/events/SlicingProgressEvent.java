package com.flashforge.farm.events;

public class SlicingProgressEvent {
    public final int progress;
    public final String message;

    public SlicingProgressEvent(int progress, String message) {
        this.progress = progress;
        this.message = message;
    }
}
