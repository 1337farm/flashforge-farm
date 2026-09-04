package com.flashforge.farm.theme;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.flashforge.farm.MainActivity;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.utils.Prefs;

public class ThemesRepo {
    private static Boolean resolvedSystemMode;

    public static FarmTheme getCurrent() {
        if (Prefs.getThemeMode() == Prefs.ThemeMode.SYSTEM) {
            if (resolvedSystemMode == null) {
                resolvedSystemMode = (FarmApp.INSTANCE.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            }
            return resolvedSystemMode ? FarmTheme.DARK : FarmTheme.LIGHT;
        }
        return Prefs.getThemeMode() == Prefs.ThemeMode.LIGHT ? FarmTheme.LIGHT : FarmTheme.DARK;
    }

    public static void resetSystemResolvedTheme() {
        resolvedSystemMode = null;
    }

    public static int getColor(int res) {
        return getCurrent().colors.get(res);
    }

    public static void invalidate(Activity act) {
        if (act instanceof MainActivity) {
            ((MainActivity) act).onApplyTheme();
        } else {
            invalidateView(act.getWindow().getDecorView());
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public static void invalidateView(View v) {
        if (v instanceof IThemeView) {
            ((IThemeView) v).onApplyTheme();
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                invalidateView(vg.getChildAt(i));
            }
        }
        if (v instanceof RecyclerView) {
            ((RecyclerView) v).getAdapter().notifyDataSetChanged();
        }
    }
}
