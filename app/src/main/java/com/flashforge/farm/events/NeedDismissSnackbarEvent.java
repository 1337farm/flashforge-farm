package com.flashforge.farm.events;


public class NeedDismissSnackbarEvent {
    public final String tag;

    public NeedDismissSnackbarEvent(String tag) {
        this.tag = tag;
    }
}
