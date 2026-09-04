package com.flashforge.farm.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class ProfileDropdownView extends View implements IThemeView {
    private Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Drawable dropdown;
    private String title;
    private CharSequence titleEllipsized;
    private StaticLayout layout;
    private float progress;

    public ProfileDropdownView(Context context) {
        super(context);

        textPaint.setTextSize(ViewUtils.dp(16));
        textPaint.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        dropdown = ContextCompat.getDrawable(context, R.drawable.dropdown_24);
        setWillNotDraw(false);
        onApplyTheme();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        titleEllipsized = null;
        layout = null;
        requestLayout();
        invalidate();
    }

    public void setProgress(float progress) {
        this.progress = progress;
        invalidateColor();
        invalidate();
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (title != null) {
            titleEllipsized = TextUtils.ellipsize(title, textPaint, MeasureSpec.getSize(widthMeasureSpec) - ViewUtils.dp(21) * 2 - ViewUtils.dp(24), TextUtils.TruncateAt.END);
            layout = new StaticLayout(titleEllipsized, textPaint, Math.round(textPaint.measureText(titleEllipsized, 0, titleEllipsized.length())), Layout.Alignment.ALIGN_NORMAL, 0f, 0f, false);
        } else {
            titleEllipsized = null;
            layout = null;
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ViewUtils.dp(16), ViewUtils.dp(16), bgPaint);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ViewUtils.dp(16), ViewUtils.dp(16), paint);
        if (titleEllipsized != null) {
            canvas.save();
            canvas.translate(ViewUtils.dp(21), (getHeight() - layout.getHeight()) / 2f);
            layout.draw(canvas);
            canvas.restore();

            dropdown.setBounds(0, 0, ViewUtils.dp(24), ViewUtils.dp(24));
            canvas.save();
            canvas.translate(getWidth() - dropdown.getBounds().width() - ViewUtils.dp(12), (getHeight() - dropdown.getBounds().height()) / 2f);
            dropdown.setAlpha((int) ((1f - progress) * 0xFF));
            dropdown.draw(canvas);
            canvas.restore();
        }
        super.draw(canvas);
    }

    private void invalidateColor() {
        textPaint.setColor(ColorUtils.blendARGB(ThemesRepo.getColor(android.R.attr.textColorPrimary), 0, progress));
        dropdown.setTintList(ColorStateList.valueOf(ThemesRepo.getColor(android.R.attr.textColorPrimary)));
    }

    @Override
    public void onApplyTheme() {
        bgPaint.setColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        paint.setColor(ColorUtils.setAlphaComponent(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0x10));
        invalidateColor();
        setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 16));
        invalidate();
    }
}
