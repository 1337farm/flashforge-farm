package com.flashforge.farm.navigation;

import static com.flashforge.farm.utils.DebugUtils.assertTrue;

import android.content.Context;
import android.util.SparseArray;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.CallSuper;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Stack;

import com.flashforge.farm.theme.ThemesRepo;

public abstract class NavigationDelegate {
    protected Context context;

    protected SparseArray<Stack<Fragment>> fragmentStack = new SparseArray<>();
    protected int currentSlot = 0;
    protected FrameLayout container;

    private SpringAnimation switchAnimation;

    public final void setContext(Context ctx) {
        context = ctx;
    }

    @CallSuper
    public void onCreate() {
        for (int i = 0; i < fragmentStack.size(); i++) {
            Stack<Fragment> fragments = fragmentStack.get(fragmentStack.keyAt(i));
            for (Fragment fr : fragments) {
                fr.setContext(context);
                fr.onCreate();
            }
        }

        if (fragmentStack.get(currentSlot) == null) {
            Stack<Fragment> stack = new Stack<>();
            fragmentStack.put(currentSlot, stack);
            Fragment fr = newFragment(currentSlot);
            fr.setContext(context);
            fr.onCreate();
            stack.push(fr);
        }
    }

    public void onApplyTheme() {
        for (int i = 0; i < fragmentStack.size(); i++) {
            Stack<Fragment> st = fragmentStack.valueAt(i);
            assertTrue(st != null);
            for (Fragment fr : st) {
                fr.onApplyTheme();
                if (fr.getView() != null) {
                    ThemesRepo.invalidateView(fr.getView());
                }
            }
        }
    }

    @CallSuper
    public void onResume() {
        fragmentStack.get(currentSlot).peek().onResume();
    }

    @CallSuper
    public void onPause() {
        fragmentStack.get(currentSlot).peek().onPause();
    }

    @CallSuper
    public void onDestroy() {
        for (int i = 0; i < fragmentStack.size(); i++) {
            Stack<Fragment> fragments = fragmentStack.get(fragmentStack.keyAt(i));
            for (Fragment fr : fragments) {
                fr.onDestroy();
            }
        }
    }

    public FrameLayout getContainerView() {
        return container;
    }

    public abstract View onCreateView(Context ctx);
    public abstract Fragment newFragment(int slot);
    public abstract FrameLayout getOverlayView();

    public boolean isSwitchingWithX() {
        return true;
    }

    public void switchSlot(int slot, Runnable onInterfaceChange) {
        Stack<Fragment> stack = fragmentStack.get(slot);
        if (stack == null) {
            fragmentStack.put(slot, stack = new Stack<>());
            Fragment fr = newFragment(slot);
            fr.setContext(context);
            fr.onCreate();
            stack.push(fr);
        }
        int wasSlot = currentSlot;
        currentSlot = slot;

        if (container.getChildCount() > 0) {
            if (switchAnimation != null) {
                switchAnimation.cancel();
            }

            Fragment cur = fragmentStack.get(wasSlot).peek();
            cur.onPause();

            Runnable next = () -> {
                onInterfaceChange.run();

                Fragment fr = fragmentStack.get(slot).peek();
                View wasView = container.getChildAt(0);
                View newView;
                if (fr.getView() == null) {
                    newView = fr.onCreateView(context);
                    fr.onViewCreated(newView);
                    fr.onApplyTheme();
                } else {
                    newView = fr.getView();
                }
                container.addView(newView);

                boolean forward = slot > wasSlot;
                switchAnimation = new SpringAnimation(new FloatValueHolder(0))
                        .setMinimumVisibleChange(1 / 256f)
                        .setSpring(new SpringForce(1f)
                                .setStiffness(1000f)
                                .setDampingRatio(1f))
                        .addUpdateListener((animation, value, velocity) -> {
                            float fValue = value;
                            if (!forward) {
                                fValue = 1f - fValue;
                            }

                            if (isSwitchingWithX()) {
                                wasView.setTranslationX(fValue * -wasView.getWidth() * 0.75f);
                                newView.setTranslationX((1f - value) * (forward ? 1 : -1) * newView.getWidth() * 0.75f);
                            } else {
                                wasView.setTranslationY(fValue * -wasView.getHeight() * 0.75f);
                                newView.setTranslationY((1f - value) * (forward ? 1 : -1) * newView.getHeight() * 0.75f);
                            }
                            wasView.setAlpha(1f - value);
                            newView.setAlpha(value);
                        })
                        .addEndListener((animation, canceled, value, velocity) -> {
                            switchAnimation = null;
                            container.removeView(wasView);
                            fr.onResume();
                        });
                switchAnimation.start();
            };

            Fragment.SlotChangeCallback callback = cur.onCheckDelayForSlotChange();
            if (callback == null || !cur.onCheckDelayForSlotChange().needDelay(next)) {
                next.run();
            }
        } else {
            Fragment fr = fragmentStack.get(slot).peek();
            fr.setContext(context);
            fr.onCreate();
            View v = fr.onCreateView(context);
            fr.onViewCreated(v);
            fr.onApplyTheme();
            container.addView(v);
            fr.onResume();
            currentSlot = slot;

            onInterfaceChange.run();
        }
    }

    public Fragment getCurrentFragment() {
        return fragmentStack.get(currentSlot).peek();
    }

    public boolean destroyCurrent() {
        if (fragmentStack.get(currentSlot).size() <= 1) {
            return false;
        }

        Fragment fr = fragmentStack.get(currentSlot).peek();
        fr.onPause();

        Fragment prev = fragmentStack.get(currentSlot).get(fragmentStack.get(currentSlot).size() - 2);

        View wasView = container.getChildAt(0);
        View newView = prev.getView();

        switchAnimation = new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 256f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(1f))
                .addUpdateListener((animation, value, velocity) -> {
                    float fValue = 1f - value;

                    if (isSwitchingWithX()) {
                        wasView.setTranslationX(-fValue * wasView.getWidth() * 0.75f);
                        newView.setTranslationX((1f - fValue) * newView.getWidth() * 0.75f);
                    } else {
                        wasView.setTranslationY(-fValue * wasView.getHeight() * 0.75f);
                        newView.setTranslationY((1f - fValue) * newView.getHeight() * 0.75f);
                    }
                    wasView.setAlpha(1f - value);
                    newView.setAlpha(value);
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    switchAnimation = null;
                    container.removeView(wasView);
                    prev.onResume();
                    fr.onDestroy();
                });
        switchAnimation.start();

        return true;
    }

    public void pushFragment(Fragment fragment) {
        fragment.setContext(context);
        fragment.onCreate();

        fragmentStack.get(currentSlot).peek().onPause();
        View wasView = container.getChildAt(0);
        View newView = fragment.onCreateView(context);
        fragment.onViewCreated(newView);
        fragment.onApplyTheme();
        fragmentStack.get(currentSlot).push(fragment);

        switchAnimation = new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 256f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(1f))
                .addUpdateListener((animation, value, velocity) -> {
                    if (isSwitchingWithX()) {
                        wasView.setTranslationX(-value * wasView.getWidth() * 0.75f);
                        newView.setTranslationX((1f - value) * newView.getWidth() * 0.75f);
                    } else {
                        wasView.setTranslationY(-value * wasView.getHeight() * 0.75f);
                        newView.setTranslationY((1f - value) * newView.getHeight() * 0.75f);
                    }
                    wasView.setAlpha(1f - value);
                    newView.setAlpha(value);
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    switchAnimation = null;
                    container.removeView(wasView);
                    fragment.onResume();
                });
        switchAnimation.start();
    }

    public boolean onBackPressed() {
        Fragment fr = getCurrentFragment();
        if (fr != null && fr.onBackPressed()) return true;
        else if (fragmentStack.get(currentSlot).size() > 1) {
            return destroyCurrent();
        }
        return false;
    }
}
