package com.flashforge.farm.recycler;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.FarmSwitch;

public class PreferenceSwitchItem extends SimpleRecyclerItem<PreferenceSwitchItem.SwitchPreferenceHolderView> {
    private Drawable mIcon;
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private String mKey;
    private boolean mDefaultValue;
    private CompoundButton.OnCheckedChangeListener mChangeListener;
    private View.OnLongClickListener mLongClickListener;
    private ValueProvider valueProvider;

    public PreferenceSwitchItem setValueProvider(ValueProvider valueProvider) {
        this.valueProvider = valueProvider;
        return this;
    }

    public PreferenceSwitchItem setKeyAndDefaultValue(String key, boolean value) {
        mKey = key;
        mDefaultValue = value;
        return this;
    }

    public PreferenceSwitchItem setChangeListener(CompoundButton.OnCheckedChangeListener listener) {
        mChangeListener = listener;
        return this;
    }

    public PreferenceSwitchItem setLongClickListener(View.OnLongClickListener longClickListener) {
        mLongClickListener = longClickListener;
        return this;
    }

    public PreferenceSwitchItem setTitle(CharSequence title) {
        mTitle = title;
        return this;
    }

    public PreferenceSwitchItem setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        return this;
    }

    public PreferenceSwitchItem setIcon(int iconRes) {
        mIcon = ContextCompat.getDrawable(FarmApp.INSTANCE, iconRes);
        return this;
    }

    public PreferenceSwitchItem setIcon(Drawable drawable) {
        mIcon = drawable;
        return this;
    }

    @Override
    public SwitchPreferenceHolderView onCreateView(Context ctx) {
        return new SwitchPreferenceHolderView(ctx);
    }

    @Override
    public void onBindView(SwitchPreferenceHolderView view) {
        view.bind(this);
    }

    public final static class SwitchPreferenceHolderView extends LinearLayout implements IThemeView {
        public TextView title, subtitle;
        public ImageView icon;
        public FarmSwitch matSwitch;

        public SwitchPreferenceHolderView(Context context) {
            super(context);

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            icon = new ImageView(context);
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
                gravity = Gravity.CENTER_VERTICAL;
            }});

            addView(matSwitch = new FarmSwitch(context), new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewUtils.dp(32)));

            int pad = ViewUtils.dp(12);
            setPadding(pad, pad, pad, pad);
            setMinimumHeight(ViewUtils.dp(52));
            onApplyTheme();
        }

        void bind(PreferenceSwitchItem item) {
            title.setText(item.mTitle);
            subtitle.setText(item.mSubtitle);
            if (TextUtils.isEmpty(item.mSubtitle)) {
                subtitle.setVisibility(GONE);
            } else {
                subtitle.setVisibility(VISIBLE);
            }

            if (item.mIcon != null) {
                icon.setVisibility(VISIBLE);
                icon.setImageDrawable(item.mIcon);
            } else {
                icon.setVisibility(GONE);
            }
            if (item.mKey != null) {
                matSwitch.setChecked(Prefs.getPrefs().getBoolean(item.mKey, item.mDefaultValue));
            } else {
                matSwitch.setChecked(item.valueProvider.provide());
            }
            setOnClickListener(v -> {
                boolean check;
                if (item.mKey != null) {
                    check = !Prefs.getPrefs().getBoolean(item.mKey, item.mDefaultValue);
                    Prefs.getPrefs().edit().putBoolean(item.mKey, check).apply();
                    matSwitch.setChecked(check);
                } else {
                    matSwitch.setChecked(check = !matSwitch.isChecked());
                }

                if (item.mChangeListener != null) {
                    item.mChangeListener.onCheckedChanged(matSwitch, check);
                }
            });
            setOnLongClickListener(item.mLongClickListener);
        }

        @Override
        public void onApplyTheme() {
            title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            subtitle.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            icon.setImageTintList(ColorStateList.valueOf(ThemesRepo.getColor(android.R.attr.textColorSecondary)));
            setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 16));
        }
    }

    public interface ValueProvider {
        boolean provide();
    }
}
