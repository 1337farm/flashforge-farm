package com.flashforge.farm.events;

import android.view.View;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.view.SnackbarsLayout;

public class NeedSnackbarEvent {
    public final CharSequence title;
    public SnackbarsLayout.Type type = SnackbarsLayout.Type.DONE;
    public String tag;

    public CharSequence buttonTitle;
    public View.OnClickListener buttonClick;

    public NeedSnackbarEvent(SnackbarsLayout.Type type, CharSequence title) {
        this.type = type;
        this.title = title;
    }

    public NeedSnackbarEvent(CharSequence title) {
        this.title = title;
    }

    public NeedSnackbarEvent(int title, Object... args) {
        this.title = FarmApp.INSTANCE.getString(title, args);
    }

    public NeedSnackbarEvent(SnackbarsLayout.Type type, int title, Object... args) {
        this.type = type;
        this.title = FarmApp.INSTANCE.getString(title, args);
    }

    public NeedSnackbarEvent tag(String tag) {
        this.tag = tag;
        return this;
    }

    public NeedSnackbarEvent button(int title, View.OnClickListener click) {
        this.buttonTitle = FarmApp.INSTANCE.getString(title);
        this.buttonClick = click;
        return this;
    }
}
