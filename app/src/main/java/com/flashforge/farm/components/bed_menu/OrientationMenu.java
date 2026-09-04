package com.flashforge.farm.components.bed_menu;

import com.flashforge.farm.Bus;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.components.UnfoldMenu;
import com.flashforge.farm.events.FlattenModeResetEvent;
import com.flashforge.farm.events.LongClickTranslationEvent;
import com.flashforge.farm.events.NeedSnackbarEvent;
import com.flashforge.farm.events.ObjectsListChangedEvent;
import com.flashforge.farm.events.SelectedObjectChangedEvent;
import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.slic3r.Model;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Vec3d;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.FarmButton;
import com.flashforge.farm.view.DividerView;
import com.flashforge.farm.view.PositionScrollView;
import com.flashforge.farm.view.TextColorImageSpan;

public class OrientationMenu extends ListBedMenu {
    private final Bus.Listener<FlattenModeResetEvent> onFlattenModeReset = e -> {
        ((BedMenuItem) adapter.getItems().get(3)).isChecked = false;
        adapter.notifyItemChanged(3);
    };

    private final Bus.Listener<ObjectsListChangedEvent> onObjectsChanged = e -> {
        ((BedMenuItem) adapter.getItems().get(0)).setEnabled(fragment.getGlView().getRenderer().getModel() != null);
        adapter.notifyItemChanged(0);

        for (int i = 2; i <= 5; i++) {
            BedMenuItem item = (BedMenuItem) adapter.getItems().get(i);
            item.setEnabled(hasSelection());
            if (item.isCheckable) {
                item.isChecked = false;
            }
            adapter.notifyItemChanged(i);
        }
    };

    private final Bus.Listener<SelectedObjectChangedEvent> onSelectionChanged = e -> {
        for (int i = 2; i <= 5; i++) {
            BedMenuItem item = (BedMenuItem) adapter.getItems().get(i);
            item.setEnabled(hasSelection());
            if (item.isCheckable) {
                item.isChecked = false;
            }
            adapter.notifyItemChanged(i);
        }
    };

    @Override
    protected void onRegisterBus() {
        Bus.FLATTEN_MODE_RESET.observeForever(onFlattenModeReset);
        Bus.OBJECTS_LIST_CHANGED.observeForever(onObjectsChanged);
        Bus.SELECTED_OBJECT_CHANGED.observeForever(onSelectionChanged);
    }

    @Override
    protected void onUnregisterBus() {
        Bus.FLATTEN_MODE_RESET.removeObserver(onFlattenModeReset);
        Bus.OBJECTS_LIST_CHANGED.removeObserver(onObjectsChanged);
        Bus.SELECTED_OBJECT_CHANGED.removeObserver(onSelectionChanged);
    }
    private boolean hasSelection() {
        return fragment.getGlView().getRenderer().getModel() != null && fragment.getGlView().getRenderer().getSelectedObject() != -1;
    }

    @Override
    protected List<SimpleRecyclerItem> onCreateItems(boolean portrait) {
        return Arrays.asList(
                new BedMenuItem(R.string.MenuOrientationArrange, R.drawable.grid_layout_outline_28).onClick(v -> {
                    fragment.getGlView().arrange();
                    fragment.getGlView().queueEvent(() -> {
                        if (fragment.getGlView().getRenderer().invalidateFlattenMode()) {
                            fragment.getGlView().requestRender();
                        }
                    });
                    Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(R.string.MenuOrientationArrangeFinished));
                }).setEnabled(fragment.getGlView().getRenderer().getModel() != null),
                new SpaceItem(portrait ? ViewUtils.dp(8) : 0, portrait ? 0 : ViewUtils.dp(8)),
                new BedMenuItem(R.string.MenuOrientationAutoOrient, R.drawable.menu_orientation_auto_28).setEnabled(hasSelection()).onClick(view -> {
                    if (fragment.getGlView().getRenderer().resetFlattenMode()) {
                        fragment.getGlView().requestRender();
                        ((BedMenuItem) adapter.getItems().get(3)).isChecked = false;
                        adapter.notifyItemChanged(3);
                    }

                    int i = fragment.getGlView().getRenderer().getSelectedObject();
                    fragment.getGlView().getRenderer().getModel().autoOrient(i);
                    fragment.getGlView().getRenderer().invalidateGlModel(i);
                    fragment.getGlView().requestRender();

                    Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(R.string.MenuOrientationAutoOrientDone, Snackbar.LENGTH_SHORT));
                }),
                new BedMenuItem(R.string.MenuOrientationFlatten, R.drawable.menu_orientation_flatten_28).setEnabled(hasSelection()).setCheckable((buttonView, isChecked) -> {
                    fragment.getGlView().getRenderer().setInFlattenMode(isChecked);
                    fragment.getGlView().requestRender();
                }, false),
                new BedMenuItem(R.string.MenuOrientationPosition, R.drawable.menu_orientation_position_28).setEnabled(hasSelection()).onClick(v -> {
                    if (fragment.getGlView().getRenderer().resetFlattenMode()) {
                        fragment.getGlView().requestRender();
                        ((BedMenuItem) adapter.getItems().get(3)).isChecked = false;
                        adapter.notifyItemChanged(3);
                    }
                    fragment.showUnfoldMenu(new PositionMenu(), v);
                }),
                new BedMenuItem(R.string.MenuOrientationRotation, R.drawable.menu_orientation_rotation_28).setEnabled(hasSelection()).onClick(v -> {
                    if (fragment.getGlView().getRenderer().resetFlattenMode()) {
                        fragment.getGlView().requestRender();
                        ((BedMenuItem) adapter.getItems().get(3)).isChecked = false;
                        adapter.notifyItemChanged(3);
                    }
                    fragment.showUnfoldMenu(new RotationMenu(), v);
                })
        );
    }


    public final class PositionMenu extends UnfoldMenu {
        private PositionScrollView xTrack, yTrack, zTrack;
        private TextView xTitle, yTitle, zTitle;
        private Vec3d tempVec = new Vec3d();
        private int startedScrollObject;
        private final Bus.Listener<LongClickTranslationEvent> onLongClickTranslation = e -> {
            if (e.visual) {
                int j = fragment.getGlView().getRenderer().getSelectedObject();
                if (j == -1) return;

                Model model = fragment.getGlView().getRenderer().getModel();
                model.getTranslation(j, tempVec);

                xTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionXValue, tempVec.x + e.x));
                yTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionYValue, tempVec.y + e.y));
                xTrack.setCurrentPosition((int) (tempVec.x + e.x));
                yTrack.setCurrentPosition((int) (tempVec.y + e.y));
            } else {
                setSelectionValues();
            }
        };
        private final Bus.Listener<SelectedObjectChangedEvent> onSelectedObjectChanged = e -> {
            stopScroll();

            if (fragment.getGlView().getRenderer().getSelectedObject() == -1) {
                dismiss();
            } else {
                setSelectionValues();
            }
        };

        public void translateVisual(Double x, Double y, Double z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;
            startedScrollObject = j;

            if (x != null) {
                xTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionXValue, x));
            }
            if (y != null) {
                yTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionYValue, y));
            }
            if (z != null) {
                zTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionZValue, z));
            }

            Model model = fragment.getGlView().getRenderer().getModel();
            model.getTranslation(j, tempVec);

            double dx = 0, dy = 0, dz = 0;
            if (x != null) dx = x - tempVec.x;
            if (y != null) dy = y - tempVec.y;
            if (z != null) dz = z - tempVec.z;

            fragment.getGlView().getRenderer().setSelectionTranslation(dx, dy, dz);
            fragment.getGlView().requestRender();
        }

        private void translate(Double x, Double y, Double z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            startedScrollObject = -1;

            fragment.getGlView().queueEvent(() -> {
                Model model = fragment.getGlView().getRenderer().getModel();
                model.getTranslation(j, tempVec);

                double dx = 0, dy = 0, dz = 0;
                if (x != null) {
                    dx = x - tempVec.x;
                    xTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionXValue, x));
                }
                if (y != null) {
                    dy = y - tempVec.y;
                    yTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionYValue, y));
                }
                if (z != null) {
                    dz = z - tempVec.z;
                    zTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionZValue, z));
                }

                model.translate(j, dx, dy, dz);
                fragment.getGlView().getRenderer().setSelectionTranslation(0, 0, 0);
                fragment.getGlView().getRenderer().invalidateGlModel(j);
                fragment.getGlView().requestRender();
            });
            fragment.getGlView().requestRender();
        }

        private CharSequence formatTrackTitle(int res, double value) {
            SpannableStringBuilder sb = SpannableStringBuilder.valueOf(FarmApp.INSTANCE.getString(res, value));
            sb.append(" d");
            int size = ViewUtils.dp(14);
            Drawable dr = ContextCompat.getDrawable(FarmApp.INSTANCE, R.drawable.edit_outline_28);
            dr.setTint(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            dr.setBounds(0, 0, size, size);
            sb.setSpan(new TextColorImageSpan(dr, 0), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        private void showManualEditor(int title, boolean x, boolean y, boolean z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            Model model = fragment.getGlView().getRenderer().getModel();
            model.getTranslation(j, tempVec);

            double current;
            if (x) {
                current = tempVec.x;
            } else if (y) {
                current = tempVec.y;
            } else {
                current = tempVec.z;
            }

            Context ctx = getView().getContext();
            FrameLayout fl = new FrameLayout(ctx);
            EditText text = new EditText(ctx);
            text.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            text.setText(String.format(Locale.ROOT, "%.2f", current));
            text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            fl.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
                leftMargin = rightMargin = ViewUtils.dp(21);
            }});

            new FarmAlertDialogBuilder(ctx)
                    .setTitle(title)
                    .setView(fl)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        double value;
                        try {
                            value = Double.parseDouble(text.getText().toString());
                        } catch (NumberFormatException e) {
                            value = current;
                        }
                        Double dx = null, dy = null, dz = null;
                        if (x) xTrack.setCurrentPosition((dx = value).intValue());
                        if (y) yTrack.setCurrentPosition((dy = value).intValue());
                        if (z) zTrack.setCurrentPosition((dz = value).intValue());

                        translate(dx, dy, dz);
                    })
                    .show();
            ViewUtils.postOnMainThread(() -> {
                text.requestFocus();
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(text, 0);
                text.setSelection(text.getText().length());
            }, 200);
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setPadding(0, ViewUtils.dp(12), 0, 0);

            xTitle = new TextView(ctx);
            xTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            xTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            xTitle.setTextColor(ThemesRepo.getColor(R.attr.xTrackColor));
            xTitle.setGravity(Gravity.CENTER);
            xTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationPositionX, true, false, false));
            ll.addView(xTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            xTrack = new PositionScrollView(ctx);
            xTrack.setActiveColor(R.attr.xTrackColor);
            xTrack.setProgressListener(integer -> translateVisual(integer.doubleValue(), (double) yTrack.getCurrentPosition(), null));
            xTrack.setListener(integer -> translate(integer.doubleValue(), (double) yTrack.getCurrentPosition(), null));
            ll.addView(xTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            yTitle = new TextView(ctx);
            yTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            yTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            yTitle.setTextColor(ThemesRepo.getColor(R.attr.yTrackColor));
            yTitle.setGravity(Gravity.CENTER);
            yTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationPositionY, false, true, false));
            ll.addView(yTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            yTrack = new PositionScrollView(ctx);
            yTrack.setActiveColor(R.attr.yTrackColor);
            yTrack.setProgressListener(integer -> translateVisual((double) xTrack.getCurrentPosition(), integer.doubleValue(), null));
            yTrack.setListener(integer -> translate((double) xTrack.getCurrentPosition(), integer.doubleValue(), null));
            ll.addView(yTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            // TODO: Sinking parts are not supported yet, so no reason to show it here
//            zTitle = new TextView(ctx);
//            zTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
//            zTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
//            zTitle.setTextColor(ThemesRepo.getColor(R.attr.zTrackColor));
//            zTitle.setGravity(Gravity.CENTER);
//            zTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationPositionZ, false, true, false));
//            ll.addView(zTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
//                bottomMargin = ViewUtils.dp(4);
//            }});
//
//            zTrack = new PositionScrollView(ctx);
//            zTrack.setActiveColor(R.attr.zTrackColor);
//            zTrack.setProgressListener(integer -> translateVisual((double) xTrack.getCurrentPosition(), (double) yTrack.getCurrentPosition(), integer.doubleValue()));
//            zTrack.setListener(integer -> translate((double) xTrack.getCurrentPosition(), (double) yTrack.getCurrentPosition(), integer.doubleValue()));
//            ll.addView(zTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            ll.addView(new Space(ctx), new LinearLayout.LayoutParams(0, 0, 1f));

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));
            LinearLayout toolbar = new LinearLayout(ctx);
            toolbar.setPadding(ViewUtils.dp(12), 0, ViewUtils.dp(12), 0);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));
            toolbar.setOnClickListener(v -> dismiss());

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.arrow_left_outline_28);
            icon.setColorFilter(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            toolbar.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)));

            TextView title = new TextView(ctx);
            title.setText(R.string.MenuOrientationPositionBack);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
                leftMargin = ViewUtils.dp(12);
            }});
            ll.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)));

            return ll;
        }

        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return portrait ? ViewUtils.dp(52) + ViewUtils.dp(80 + 24) * 2 + ViewUtils.dp(12) : (int) (into.getWidth() * 0.5f);
        }

        @Override
        protected void onCreate() {
            super.onCreate();

            Bus.LONG_CLICK_TRANSLATION.observeForever(onLongClickTranslation);
            Bus.SELECTED_OBJECT_CHANGED.observeForever(onSelectedObjectChanged);
            setSelectionValues();
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            Bus.LONG_CLICK_TRANSLATION.removeObserver(onLongClickTranslation);
            Bus.SELECTED_OBJECT_CHANGED.removeObserver(onSelectedObjectChanged);
            stopScroll();
        }

        private void setSelectionValues() {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            fragment.getGlView().getRenderer().setSelectionTranslation(0, 0, 0);

            Model model = fragment.getGlView().getRenderer().getModel();
            model.getTranslation(j, tempVec);

            xTrack.setCurrentPosition((int) tempVec.x);
            yTrack.setCurrentPosition((int) tempVec.y);
//            zTrack.setCurrentPosition((int) tempVec.z);

            xTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionXValue, tempVec.x));
            yTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionYValue, tempVec.y));
//            zTitle.setText(formatTrackTitle(R.string.MenuOrientationPositionZValue, tempVec.z));
        }

        private void stopScroll() {
            xTrack.stopScroll();
            yTrack.stopScroll();
//            zTrack.stopScroll();

            if (startedScrollObject != -1) {
                fragment.getGlView().getRenderer().setSelectionTranslation(0, 0, 0);
            }
            startedScrollObject = -1;
        }
    }
    public final class RotationMenu extends UnfoldMenu {
        private PositionScrollView xTrack, yTrack, zTrack;
        private TextView xTitle, yTitle, zTitle;
        private Vec3d bbMin = new Vec3d(), bbMax = new Vec3d();
        private int startedScrollObject;
        private final Bus.Listener<SelectedObjectChangedEvent> onSelectedObjectChanged = e -> {
            stopScroll();

            if (fragment.getGlView().getRenderer().getSelectedObject() == -1) {
                dismiss();
            } else {
                setSelectionValues();
            }
        };

        private void rotateVisual(Double x, Double y, Double z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;
            startedScrollObject = j;

            if (x != null) {
                xTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationXValue, x));
            }
            if (y != null) {
                yTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationYValue, y));
            }
            if (z != null) {
                zTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationZValue, z));
            }

            double dx = 0, dy = 0, dz = 0;
            if (x != null) dx = x;
            if (y != null) dy = y;
            if (z != null) dz = z;

            dx %= 360;
            dy %= 360;
            dz %= 360;

            fragment.getGlView().getRenderer().setSelectionRotation(dx, dy, dz);
            fragment.getGlView().requestRender();
        }

        private void rotate(Double x, Double y, Double z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            startedScrollObject = -1;

            fragment.getGlView().queueEvent(() -> {
                Model model = fragment.getGlView().getRenderer().getModel();

                double dx = 0, dy = 0, dz = 0;
                if (x != null) {
                    dx = x;
                    xTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationXValue, 0));
                    xTrack.setCurrentPosition(0);
                }
                if (y != null) {
                    dy = y;
                    yTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationYValue, 0));
                    yTrack.setCurrentPosition(0);
                }
                if (z != null) {
                    dz = z;
                    zTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationZValue, 0));
                    zTrack.setCurrentPosition(0);
                }

                dx %= 360;
                dy %= 360;
                dz %= 360;

                model.rotate(j, Math.toRadians(dx), Math.toRadians(dy), Math.toRadians(dz));
                model.ensureOnBed(j);

                fragment.getGlView().getRenderer().setSelectionRotation(0, 0, 0);
                fragment.getGlView().getRenderer().invalidateGlModel(j);
                fragment.getGlView().requestRender();
            });
            fragment.getGlView().requestRender();
        }

        private CharSequence formatTrackTitle(int res, double value) {
            SpannableStringBuilder sb = SpannableStringBuilder.valueOf(FarmApp.INSTANCE.getString(res, value));
            sb.append(" d");
            int size = ViewUtils.dp(14);
            Drawable dr = ContextCompat.getDrawable(FarmApp.INSTANCE, R.drawable.edit_outline_28);
            dr.setTint(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            dr.setBounds(0, 0, size, size);
            sb.setSpan(new TextColorImageSpan(dr, 0), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }

        private void showManualEditor(int title, boolean x, boolean y, boolean z) {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            double current;
            if (x) {
                current = xTrack.getCurrentPosition();
            } else if (y) {
                current = yTrack.getCurrentPosition();
            } else {
                current = zTrack.getCurrentPosition();
            }

            Context ctx = getView().getContext();
            FrameLayout fl = new FrameLayout(ctx);
            EditText text = new EditText(ctx);
            text.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            text.setText(String.format(Locale.ROOT, "%.2f", current));
            text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            fl.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
                leftMargin = rightMargin = ViewUtils.dp(21);
            }});

            new FarmAlertDialogBuilder(ctx)
                    .setTitle(title)
                    .setView(fl)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        double value;
                        try {
                            value = Double.parseDouble(text.getText().toString());
                        } catch (NumberFormatException e) {
                            value = current;
                        }
                        Double dx = null, dy = null, dz = null;
                        if (x) xTrack.setCurrentPosition((dx = value).intValue());
                        if (y) yTrack.setCurrentPosition((dy = value).intValue());
                        if (z) zTrack.setCurrentPosition((dz = value).intValue());

                        rotateVisual(dx, dy, dz);
                    })
                    .show();
            ViewUtils.postOnMainThread(() -> {
                text.requestFocus();
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(text, 0);
                text.setSelection(text.getText().length());
            }, 200);
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setPadding(0, ViewUtils.dp(12), 0, 0);

            xTitle = new TextView(ctx);
            xTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            xTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            xTitle.setTextColor(ThemesRepo.getColor(R.attr.xTrackColor));
            xTitle.setGravity(Gravity.CENTER);
            xTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationRotationX, true, false, false));
            ll.addView(xTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            xTrack = new PositionScrollView(ctx);
            xTrack.setActiveColor(R.attr.xTrackColor);
            xTrack.setProgressListener(integer -> rotateVisual(integer.doubleValue(), (double) yTrack.getCurrentPosition(), (double) zTrack.getCurrentPosition()));
            ll.addView(xTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            yTitle = new TextView(ctx);
            yTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            yTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            yTitle.setTextColor(ThemesRepo.getColor(R.attr.yTrackColor));
            yTitle.setGravity(Gravity.CENTER);
            yTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationRotationY, false, true, false));
            ll.addView(yTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            yTrack = new PositionScrollView(ctx);
            yTrack.setActiveColor(R.attr.yTrackColor);
            yTrack.setProgressListener(integer -> rotateVisual((double) xTrack.getCurrentPosition(), integer.doubleValue(), (double) zTrack.getCurrentPosition()));
            ll.addView(yTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            zTitle = new TextView(ctx);
            zTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            zTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            zTitle.setTextColor(ThemesRepo.getColor(R.attr.zTrackColor));
            zTitle.setGravity(Gravity.CENTER);
            zTitle.setOnClickListener(v -> showManualEditor(R.string.MenuOrientationRotationZ, false, false, true));
            ll.addView(zTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(20)) {{
                bottomMargin = ViewUtils.dp(4);
            }});

            zTrack = new PositionScrollView(ctx);
            zTrack.setActiveColor(R.attr.zTrackColor);
            zTrack.setProgressListener(integer -> rotateVisual((double) xTrack.getCurrentPosition(), (double) yTrack.getCurrentPosition(), integer.doubleValue()));
            ll.addView(zTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(80)));

            ll.addView(new Space(ctx), new LinearLayout.LayoutParams(0, 0, 1f));

            FarmButton btn = new FarmButton(ctx);
            btn.setText(R.string.MenuOrientationRotationApply);
            btn.setOnClickListener(v -> rotate((double) xTrack.getCurrentPosition(), (double) yTrack.getCurrentPosition(), (double) zTrack.getCurrentPosition()));
            if (portrait) {
                ll.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(48)) {{
                    leftMargin = rightMargin = ViewUtils.dp(12);
                    topMargin = bottomMargin = ViewUtils.dp(4);
                }});
            }

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));
            LinearLayout toolbar = new LinearLayout(ctx);
            toolbar.setPadding(ViewUtils.dp(12), 0, ViewUtils.dp(portrait ? 12 : 4), 0);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackground(ViewUtils.createRipple(ThemesRepo.getColor(android.R.attr.colorControlHighlight), 0));
            toolbar.setOnClickListener(v -> dismiss());

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.arrow_left_outline_28);
            icon.setColorFilter(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            toolbar.addView(icon, new LinearLayout.LayoutParams(ViewUtils.dp(28), ViewUtils.dp(28)));


            TextView title = new TextView(ctx);
            title.setText(R.string.MenuOrientationPositionBack);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
                leftMargin = ViewUtils.dp(12);
            }});
            ll.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)));

            if (!portrait) {
                toolbar.addView(btn, new LinearLayout.LayoutParams(0, ViewUtils.dp(42), 1f) {{
                    leftMargin = ViewUtils.dp(12);
                }});
            }

            return ll;
        }

        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return portrait ? ViewUtils.dp(52) + ViewUtils.dp(56) + ViewUtils.dp(80 + 24) * 3 + ViewUtils.dp(12) : (int) (into.getWidth() * 0.5f);
        }

        @Override
        protected void onCreate() {
            super.onCreate();

            Bus.SELECTED_OBJECT_CHANGED.observeForever(onSelectedObjectChanged);
            setSelectionValues();
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            Bus.SELECTED_OBJECT_CHANGED.removeObserver(onSelectedObjectChanged);
            stopScroll();
        }

        private void setSelectionValues() {
            int j = fragment.getGlView().getRenderer().getSelectedObject();
            if (j == -1) return;

            fragment.getGlView().getRenderer().setSelectionTranslation(0, 0, 0);

            xTrack.setCurrentPosition(0);
            yTrack.setCurrentPosition(0);
            zTrack.setCurrentPosition(0);

            xTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationXValue, 0));
            yTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationYValue, 0));
            zTitle.setText(formatTrackTitle(R.string.MenuOrientationRotationZValue, 0));
        }

        private void stopScroll() {
            xTrack.stopScroll();
            yTrack.stopScroll();
            zTrack.stopScroll();

            if (startedScrollObject != -1) {
                fragment.getGlView().getRenderer().setSelectionRotation(0, 0, 0);
            }
            startedScrollObject = -1;
        }

        @Override
        public void dismiss() {
            super.dismiss();

            fragment.getGlView().getRenderer().setSelectionRotation(0, 0, 0);
            fragment.getGlView().requestRender();
        }
    }
}
