package com.flashforge.farm.navigation;

import android.content.Context;
import android.view.View;

import androidx.annotation.CallSuper;

import com.flashforge.farm.theme.ThemesRepo;

public abstract class Fragment {
    private View mView;
    private Context context;

    public Context getContext() {
        return context;
    }

    void setContext(Context context) {
        this.context = context;
    }

    @CallSuper
    public void onCreate() {}

    @CallSuper
    public void onResume() {}

    @CallSuper
    public void onPause() {}

    @CallSuper
    public void onDestroy() {
        mView = null;
    }

    public void onApplyTheme() {
        if (mView != null) {
            mView.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));
        }
    }

    public abstract View onCreateView(Context ctx);

    @CallSuper
    public void onViewCreated(View v) {
        mView = v;
    }

    public View getView() {
        return mView;
    }

    public boolean onBackPressed() {
        return false;
    }

    public SlotChangeCallback onCheckDelayForSlotChange() {
        return null;
    }

    public interface SlotChangeCallback {
        boolean needDelay(Runnable callback);
    }
}
