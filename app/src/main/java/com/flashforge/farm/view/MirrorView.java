package com.flashforge.farm.view;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.NonNull;

public class MirrorView extends View {
    private View mirrored;

    public MirrorView(Context context) {
        super(context);
    }

    public void setMirroredView(View mirrored) {
        this.mirrored = mirrored;
        invalidate();
    }

    public View getMirroredView() {
        return mirrored;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        mirrored.draw(canvas);
    }
}
