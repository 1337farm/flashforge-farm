package com.flashforge.farm.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.google.android.material.materialswitch.MaterialSwitch;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;

public class FarmSwitch extends MaterialSwitch implements IThemeView {
    public FarmSwitch(@NonNull Context context) {
        super(context);

        onApplyTheme();
    }

    @Override
    public void onApplyTheme() {
        setTrackTintList(new ColorStateList(new int[][] {
                {android.R.attr.state_enabled, android.R.attr.state_checked},
                {android.R.attr.state_enabled, -android.R.attr.state_checked}
        }, new int[] {
                ThemesRepo.getColor(android.R.attr.colorAccent),
                ThemesRepo.getColor(R.attr.switchThumbUncheckedColor)
        }));
    }

    @Override
    public boolean isLaidOut() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }
}
