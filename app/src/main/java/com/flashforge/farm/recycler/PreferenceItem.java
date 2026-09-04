package com.flashforge.farm.recycler;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.theme.FarmTheme;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class PreferenceItem extends SimpleRecyclerItem<PreferenceItem.PreferenceHolderView> {
    private Drawable mIcon;
    private CharSequence mTitle;
    private ValueProvider mSubtitle;
    private View.OnClickListener onClickListener;
    private View.OnLongClickListener onLongClickListener;
    private int textColorRes;
    private boolean noTint;
    private ValueProvider valueProvider;
    private float roundRadius;
    private int mPaddings = ViewUtils.dp(12);
    private boolean mForceDark;

    public PreferenceItem setTitle(CharSequence title) {
        mTitle = title;
        return this;
    }

    public PreferenceItem setSubtitle(CharSequence subtitle) {
        mSubtitle = ()->subtitle;
        return this;
    }

    public PreferenceItem setPaddings(int paddings) {
        this.mPaddings = paddings;
        return this;
    }

    public PreferenceItem setForceDark(boolean mForceDark) {
        this.mForceDark = mForceDark;
        return this;
    }

    public PreferenceItem setSubtitleProvider(ValueProvider mSubtitle) {
        this.mSubtitle = mSubtitle;
        return this;
    }

    public PreferenceItem setValueProvider(ValueProvider valueProvider) {
        this.valueProvider = valueProvider;
        return this;
    }

    /** Returns the value provider, or null if none is set. */
    public ValueProvider getValueProvider() {
        return valueProvider;
    }

    public PreferenceItem setValue(String text) {
        this.valueProvider = () -> text;
        return this;
    }

    /** Update only the value provider without rebinding the rest of the view. */
    public PreferenceItem updateValue(String newValue) {
        this.valueProvider = () -> newValue;
        return this;
    }

    public PreferenceItem setIcon(int iconRes) {
        mIcon = ContextCompat.getDrawable(FarmApp.INSTANCE, iconRes);
        return this;
    }

    public PreferenceItem setIcon(Drawable drawable) {
        mIcon = drawable;
        return this;
    }

    public PreferenceItem setNoTint(boolean noTint) {
        this.noTint = noTint;
        return this;
    }

    public PreferenceItem setRoundRadius(float roundRadius) {
        this.roundRadius = roundRadius;
        return this;
    }

    public PreferenceItem setTextColorRes(int textColorRes) {
        this.textColorRes = textColorRes;
        return this;
    }

    public PreferenceItem setOnClickListener(View.OnClickListener onClickListener) {
        this.onClickListener = onClickListener;
        return this;
    }

    public PreferenceItem setOnLongClickListener(View.OnLongClickListener onLongClickListener) {
        this.onLongClickListener = onLongClickListener;
        return this;
    }

    @Override
    public PreferenceHolderView onCreateView(Context ctx) {
        return new PreferenceHolderView(ctx);
    }

    @Override
    public void onBindView(PreferenceHolderView view) {
        view.bind(this);
    }

    public final static class PreferenceHolderView extends LinearLayout implements IThemeView {
        private TextView title, subtitle;
        private ImageView icon;
        private TextView value;
        private float radius;

        private PreferenceItem item;

        public PreferenceHolderView(Context context) {
            super(context);

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            icon = new AppCompatImageView(context) {
                private Path path = new Path();

                @Override
                public void draw(@NonNull Canvas canvas) {
                    if (radius != 0) {
                        canvas.save();
                        path.rewind();
                        path.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
                        canvas.clipPath(path);
                    }
                    super.draw(canvas);
                    if (radius != 0) {
                        canvas.restore();
                    }
                }
            };
            icon.setLayoutParams(new LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)) {{
                setMarginStart(ViewUtils.dp(4));
                setMarginEnd(ViewUtils.dp(8));
            }});
            addView(icon);

            LinearLayout innerLayout = new LinearLayout(context);
            innerLayout.setOrientation(VERTICAL);
            innerLayout.setGravity(Gravity.CENTER_VERTICAL);

            title = new TextView(context);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            innerLayout.addView(title);

            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            innerLayout.addView(subtitle);

            addView(innerLayout, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
                setMarginStart(ViewUtils.dp(8));
                setMarginEnd(ViewUtils.dp(8));
            }});

            value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            value.setPadding(ViewUtils.dp(8), ViewUtils.dp(6), ViewUtils.dp(8), ViewUtils.dp(6));
            value.setVisibility(GONE);
            addView(value, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setMinimumHeight(ViewUtils.dp(56));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            onApplyTheme();
        }

        void bind(PreferenceItem item) {
            this.item = item;
            setPadding(item.mPaddings, item.mPaddings, item.mPaddings, item.mPaddings);
            title.setText(item.mTitle);
            title.setVisibility(TextUtils.isEmpty(item.mTitle) ? GONE : VISIBLE);

            CharSequence sub = item.mSubtitle != null ? item.mSubtitle.provide() : null;
            subtitle.setText(sub);
            subtitle.setVisibility(TextUtils.isEmpty(sub) ? GONE : VISIBLE);

            CharSequence v = item.valueProvider != null ? item.valueProvider.provide() : null;
            value.setText(v);
            value.setVisibility(TextUtils.isEmpty(v) ? GONE : VISIBLE);

            if (item.mIcon != null) {
                icon.setVisibility(VISIBLE);
                icon.setImageDrawable(item.mIcon);
            } else {
                icon.setVisibility(GONE);
            }
            if (item.onClickListener != null) {
                setOnClickListener(item.onClickListener);
            } else {
                setClickable(false);
            }
            setOnLongClickListener(item.onLongClickListener);

            if (item.textColorRes != 0) {
                title.setTextColor(ThemesRepo.getColor(item.textColorRes));
            }

            if (item.textColorRes != 0 || item.mIcon != null) {
                title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            } else {
                title.setTypeface(Typeface.DEFAULT);
            }

            if (item.noTint) {
                icon.setImageTintList(null);
            } else {
                icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(item.textColorRes != 0 ? item.textColorRes : android.R.attr.textColorSecondary)));
            }
            radius = item.roundRadius;
            icon.invalidate();

            ViewGroup.LayoutParams params = icon.getLayoutParams();
            params.width = params.height = radius != 0 ? ViewUtils.dp(42) : ViewUtils.dp(28);
            if (item.mForceDark) {
                onApplyTheme();
            }
        }

        /** Update only the value text in this view, leaving other fields untouched. */
        public void bindValue(CharSequence value) {
            if (this.value != null) {
                this.value.setText(value);
                this.value.setVisibility(TextUtils.isEmpty(value) ? GONE : VISIBLE);
            }
        }

        @Override
        public void onApplyTheme() {
            FarmTheme theme = item != null && item.mForceDark ? FarmTheme.DARK : ThemesRepo.getCurrent();
            title.setTextColor(theme.colors.get(android.R.attr.textColorPrimary));
            subtitle.setTextColor(theme.colors.get(android.R.attr.textColorSecondary));
            value.setTextColor(theme.colors.get(android.R.attr.textColorSecondary));
            icon.setImageTintList(ColorStateList.valueOf(theme.colors.get(android.R.attr.textColorSecondary)));
            setBackground(ViewUtils.createRipple(theme.colors.get(android.R.attr.colorControlHighlight), 16));
        }
    }

    public interface ValueProvider {
        CharSequence provide();
    }
}
