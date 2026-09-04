package com.flashforge.farm.components;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import com.mrudultora.colorpicker.ColorPickerPopUp;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class FarmColorPickerPopUp extends ColorPickerPopUp {
    public FarmColorPickerPopUp(Context context) {
        super(context);
    }

    @Override
    public void show() {
        super.show();

        Dialog dialog = getDialog();
        dialog.getWindow().setBackgroundDrawable(new GradientDrawable() {{
            setCornerRadius(ViewUtils.dp(32));
            setColor(ThemesRepo.getColor(R.attr.dialogBackground));
        }});
        View v = dialog.getWindow().getDecorView();
        TextView v2;
        int id = getResources().getIdentifier("alertTitle", "id", "android");
        if (id != -1) {
            v2 = v.findViewById(id);
            if (v2 != null) {
                v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            }
        }
        v2 = v.findViewById(android.R.id.button1);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        }
        v2 = v.findViewById(android.R.id.button2);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        }
        v2 = v.findViewById(android.R.id.button3);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        }
    }
}
