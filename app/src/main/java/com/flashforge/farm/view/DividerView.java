package com.flashforge.farm.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;

public class DividerView extends View implements IThemeView {
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int colorRes;

    public DividerView(Context context) {
        this(context, R.attr.dividerColor);
    }

    public DividerView(Context context, int colorRes) {
        super(context);
        this.colorRes = colorRes;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        onApplyTheme();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawPaint(paint);
    }

    @Override
    public void onApplyTheme() {
        paint.setColor(ThemesRepo.getColor(colorRes));
    }
}
