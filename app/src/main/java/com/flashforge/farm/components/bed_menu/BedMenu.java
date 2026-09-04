package com.flashforge.farm.components.bed_menu;

import android.content.Context;
import android.view.View;

import androidx.annotation.CallSuper;

import com.flashforge.farm.fragment.BedFragment;
import com.flashforge.farm.slic3r.Bed3D;

public abstract class BedMenu {
    private View view;

    public abstract View onCreateView(Context ctx, boolean portrait);

    @CallSuper
    public void onViewCreated(View v) {
        view = v;
    }

    @CallSuper
    public void onViewDestroyed() {
        view = null;
    }

    public View getView() {
        return view;
    }

    public void onSetBed(BedFragment fragment) {}
}
