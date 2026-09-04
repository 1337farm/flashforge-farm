package com.flashforge.farm.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.ViewUtils;

public class MiniColorView extends View {
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint outlinePaintSecondary = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float selectionProgress;

    public MiniColorView(Context context) {
        super(context);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(ViewUtils.dp(2f));

        outlinePaintSecondary.setStyle(Paint.Style.STROKE);
        outlinePaintSecondary.setStrokeWidth(ViewUtils.dp(2f));
        outlinePaintSecondary.setColor(ThemesRepo.getColor(R.attr.dividerColor));
    }

    public void setSelectionProgress(float selectionProgress) {
        this.selectionProgress = selectionProgress;
        invalidate();
    }

    public void setColor(int color) {
        paint.setColor(color);
        outlinePaint.setColor(color);
        outlinePaintSecondary.setColor(ColorUtils.calculateLuminance(color) > 0.9f ? ThemesRepo.getColor(R.attr.dividerColor) : Color.TRANSPARENT);
        selectionProgress = Prefs.getAccentColor() == color ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int size = Math.min(getWidth(), getHeight());
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, size / 2f - outlinePaintSecondary.getStrokeWidth() / 2f, outlinePaintSecondary);
        float w = (outlinePaint.getStrokeWidth() + outlinePaintSecondary.getStrokeWidth()) / 2f;
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, size / 2f - w, outlinePaint);
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, size / 2f - ViewUtils.lerp(0, w * 3f, selectionProgress), paint);
    }
}
