package com.flashforge.farm.view;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;

import androidx.appcompat.widget.AppCompatTextView;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class FarmButton extends AppCompatTextView implements IThemeView {
    private int colorRes = android.R.attr.colorAccent;
    private int color;

    public FarmButton(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        setPadding(ViewUtils.dp(21), 0, ViewUtils.dp(21), 0);
        onApplyTheme();
    }

    public void setColor(int color) {
        this.color = color;
        this.colorRes = 0;
        onApplyTheme();
    }

    public void setColorRes(int colorRes) {
        this.colorRes = colorRes;
        onApplyTheme();
    }

    @Override
    public void onApplyTheme() {
        setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), colorRes != 0 ? ThemesRepo.getColor(colorRes) : color, 16));
        setTextColor(ThemesRepo.getColor(R.attr.textColorOnAccent));
    }
}
