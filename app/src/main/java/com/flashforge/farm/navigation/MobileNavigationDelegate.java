package com.flashforge.farm.navigation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.TextColorImageSpan;
import com.flashforge.farm.view.ThemeBottomNavigationView;
import com.flashforge.farm.view.ThemeRailNavigationView;

public class MobileNavigationDelegate extends DelegateSlotImpl {
    private boolean portrait;
    private FrameLayout root;
    private NavigationBarView navigationView;

    @Override
    public void onApplyTheme() {
        super.onApplyTheme();
        ThemesRepo.invalidateView(navigationView);
        root.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));
    }

    @SuppressLint("RestrictedApi")
    @Override
    public View onCreateView(Context ctx) {
        FrameLayout fl = new FrameLayout(ctx);
        LinearLayout ll = new LinearLayout(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        portrait = dm.widthPixels < dm.heightPixels;
        ll.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        if (portrait) ll.addView(navigationView = new ThemeBottomNavigationView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container = new FrameLayout(ctx);
        Fragment fr = getCurrentFragment();
        if (fr.getView() == null) {
            View v = fr.onCreateView(ctx);
            fr.onViewCreated(v);
            fr.onApplyTheme();

            container.addView(v);
        } else {
            container.addView(fr.getView());
        }
        ll.addView(container, new LinearLayout.LayoutParams(portrait ? ViewGroup.LayoutParams.MATCH_PARENT : 0, portrait ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        if (!portrait) {
            ll.addView(navigationView = new ThemeRailNavigationView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        navigationView.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        Menu menu = navigationView.getMenu();
        for (int i = 0; i < getSlotCount(); i++) {
            MenuItemImpl item = (MenuItemImpl) menu.add(0, i, 0, getSlotTitle(i)).setIcon(getSlotIcon(i));
            if (needDisplaySlotGear(i)) {
                SpannableStringBuilder sb = SpannableStringBuilder.valueOf("d ");
                Drawable dr = ContextCompat.getDrawable(ctx, R.drawable.settings_outline_28);
                int size = ViewUtils.dp(13);
                dr.setBounds(0, 0, size, size);
                sb.setSpan(new ReplacementSpan() {
                    @Override
                    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
                        return ViewUtils.dp(2f);
                    }

                    @Override
                    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {}
                }, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new TextColorImageSpan(dr, ViewUtils.dp(1.5f)), 0, 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(item.getTitle());
                item.setTitle(sb);
            }
            item.setTooltipText(ctx.getString(getSlotTooltip(i)));
        }
        navigationView.setSelectedItemId(currentSlot);
        NavigationBarView finalNavigationView = navigationView;
        navigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == currentSlot) return true;
            switchSlot(item.getItemId(), () -> finalNavigationView.setSelectedItemId(item.getItemId()));
            return false;
        });

        fl.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        fl.addView(ll);
        return root = fl;
    }

    @Override
    public boolean onBackPressed() {
        if (super.onBackPressed()) {
            return true;
        }
        if (currentSlot != 0) {
            switchSlot(0, () -> navigationView.setSelectedItemId(0));
            return true;
        }
        return false;
    }

    @Override
    public FrameLayout getOverlayView() {
        return root;
    }

    @Override
    public boolean isSwitchingWithX() {
        return portrait;
    }
}
