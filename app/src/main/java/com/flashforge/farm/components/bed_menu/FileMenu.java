package com.flashforge.farm.components.bed_menu;

import com.flashforge.farm.Bus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import com.flashforge.farm.MainActivity;
import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;

import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.components.UnfoldMenu;
import com.flashforge.farm.config.ConfigObject;

import com.flashforge.farm.events.NeedDismissCalibrationsMenu;
import com.flashforge.farm.events.ObjectsListChangedEvent;
import com.flashforge.farm.events.SelectedObjectChangedEvent;
import com.flashforge.farm.gallery.ShapeGalleryMenu;
import com.flashforge.farm.recycler.PreferenceItem;
import com.flashforge.farm.recycler.SimpleRecyclerAdapter;
import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.slic3r.Bed3D;
import com.flashforge.farm.theme.FarmTheme;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.DividerView;
import com.flashforge.farm.view.FadeRecyclerView;

public class FileMenu extends ListBedMenu {
    private final static List<String> K3D_SUPPORTED_LANGUAGES = Arrays.asList("en");

    private boolean wasPortrait;

    private final Bus.Listener<ObjectsListChangedEvent> onObjectsChanged =
            e -> {
                for (int i = 0; i < adapter.getItems().size(); i++) {
                    SimpleRecyclerItem item = adapter.getItems().get(i);
                    if (item instanceof BedMenuItem) {
                        if (((BedMenuItem) item).titleRes == R.string.MenuFileDelete) {
                            ((BedMenuItem) item).setEnabled(hasSelection());
                            adapter.notifyItemChanged(i);
                        } else if (((BedMenuItem) item).titleRes == R.string.MenuFileExport3mf) {
                            ((BedMenuItem) item).setEnabled(hasModel());
                            adapter.notifyItemChanged(i);
                        }
                    }
                }
            };

    private final Bus.Listener<SelectedObjectChangedEvent> onSelectionChanged =
            e -> {
                for (int i = 0; i < adapter.getItems().size(); i++) {
                    SimpleRecyclerItem item = adapter.getItems().get(i);
                    if (item instanceof BedMenuItem && ((BedMenuItem) item).titleRes == R.string.MenuFileDelete) {
                        ((BedMenuItem) item).setEnabled(hasSelection());
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
            };

    @Override
    protected void onRegisterBus() {
        Bus.OBJECTS_LIST_CHANGED.observeForever(onObjectsChanged);
        Bus.SELECTED_OBJECT_CHANGED.observeForever(onSelectionChanged);
    }

    @Override
    protected void onUnregisterBus() {
        Bus.OBJECTS_LIST_CHANGED.removeObserver(onObjectsChanged);
        Bus.SELECTED_OBJECT_CHANGED.removeObserver(onSelectionChanged);
    }

    private String getK3DLanguage() {
        String lang = Locale.getDefault().getLanguage();
        return K3D_SUPPORTED_LANGUAGES.contains(lang) ? lang : "en";
    }

    static String escapeStringForJs(String s) {
        if (s == null) return s;
        return s.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\r", "\\r")
                .replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    private boolean hasModel() {
        return fragment.getGlView().getRenderer().getModel() != null;
    }

    private boolean hasSelection() {
        return hasModel() && fragment.getGlView().getRenderer().getSelectedObject() != -1;
    }

    @Override
    protected List<SimpleRecyclerItem> onCreateItems(boolean portrait) {
        wasPortrait = portrait;
        List<SimpleRecyclerItem> list = new ArrayList<>(Arrays.asList(
                new BedMenuItem(R.string.MenuFileOpen, R.drawable.folder_simple_plus_outline_28).onClick(v -> {
                    if (!fragment.getGlView().getRenderer().getBed().isValid()) {
                        Toast.makeText(fragment.getContext(), R.string.BedConfigurationError, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (fragment.getContext() instanceof Activity) {
                        Activity act = (Activity) fragment.getContext();

                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        // Don't filter by EXTRA_MIME_TYPES: model formats like .3mf have no standard
                        // MIME type, and providers report inconsistent types (e.g. Downloads stores the
                        // server's Content-Type, often application/zip for .3mf), which greys the file out
                        // in the picker. Allow any file; loadFile() validates by extension afterward.
                        i.setType("*/*");
                        ((com.flashforge.farm.MainActivity) act).pickFile(i, MainActivity.REQUEST_CODE_OPEN_FILE);
                    }
                }),
                new BedMenuItem(R.string.MenuFileDelete, R.drawable.delete_outline_android_28).setEnabled(hasSelection()).onClick(v -> {
                    if (fragment.getGlView().getRenderer().getModel() == null) return;

                    if (fragment.getGlView().getRenderer().deleteObject(fragment.getGlView().getRenderer().getSelectedObject())) {
                        fragment.getGlView().requestRender();
                        fragment.updateModel();
                    }
                }),
                new SpaceItem(portrait ? ViewUtils.dp(3) : 0, portrait ? 0 : ViewUtils.dp(3))));

        list.addAll(Arrays.asList(
                new BedMenuItem(R.string.MenuFileCalibrations, R.drawable.wrench_outline_28).setSingleLine(true).onClick(v -> {
                    if (!fragment.getGlView().getRenderer().getBed().isValid()) {
                        Toast.makeText(fragment.getContext(), R.string.BedConfigurationError, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fragment.showUnfoldMenu(new CalibrationsMenu(), v);
                }),
                new SpaceItem(portrait ? ViewUtils.dp(3) : 0, portrait ? 0 : ViewUtils.dp(3)),
                new BedMenuItem(R.string.MenuFileImportProfiles, R.drawable.folder_simple_arrow_up_outline_28).onClick(v -> {
                    if (fragment.getContext() instanceof Activity) {
                        Activity act = (Activity) fragment.getContext();

                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream", "text/plain"});
                        ((com.flashforge.farm.MainActivity) act).pickFile(i, MainActivity.REQUEST_CODE_IMPORT_PROFILES);
                    }
                }),
                new BedMenuItem(R.string.MenuFileExportProfiles, R.drawable.folder_simple_arrow_right_outline_28).onClick(v -> {
                    CharSequence[] prints = new CharSequence[FarmApp.CONFIG.printConfigs.size()];
                    boolean[] enabledPrints = new boolean[prints.length];
                    for (int i = 0; i < prints.length; i++) {
                        prints[i] = FarmApp.CONFIG.printConfigs.get(i).getTitle();
                        enabledPrints[i] = true;
                    }

                    CharSequence[] filaments = new CharSequence[FarmApp.CONFIG.filamentConfigs.size()];
                    boolean[] enabledFilaments = new boolean[filaments.length];
                    for (int i = 0; i < filaments.length; i++) {
                        filaments[i] = FarmApp.CONFIG.filamentConfigs.get(i).getTitle();
                        enabledFilaments[i] = true;
                    }

                    CharSequence[] printers = new CharSequence[FarmApp.CONFIG.printerConfigs.size()];
                    boolean[] enabledPrinters = new boolean[printers.length];
                    for (int i = 0; i < printers.length; i++) {
                        printers[i] = FarmApp.CONFIG.printerConfigs.get(i).getTitle();
                        enabledPrinters[i] = true;
                    }

                    new FarmAlertDialogBuilder(v.getContext())
                            .setTitle(R.string.MenuFileExportProfilesPrints)
                            .setMultiChoiceItems(prints, enabledPrints, (dialog, which, isChecked) -> enabledPrints[which] = isChecked)
                            .setPositiveButton(android.R.string.ok, (d1, w1) -> new FarmAlertDialogBuilder(v.getContext())
                                    .setTitle(R.string.MenuFileExportProfilesFilaments)
                                    .setMultiChoiceItems(filaments, enabledFilaments, (dialog, which, isChecked) -> enabledFilaments[which] = isChecked)
                                    .setPositiveButton(android.R.string.ok, (d2, w2) -> new FarmAlertDialogBuilder(v.getContext())
                                            .setTitle(R.string.MenuFileExportProfilesPrinters)
                                            .setMultiChoiceItems(printers, enabledPrinters, (dialog, which, isChecked) -> enabledPrinters[which] = isChecked)
                                            .setPositiveButton(android.R.string.ok, (d3, w3) -> {
                                                boolean hasEnabled = false;
                                                MainActivity.EXPORTING_PRINTS = new ArrayList<>();
                                                for (int i = 0; i < enabledPrints.length; i++) {
                                                    if (enabledPrints[i]) {
                                                        hasEnabled = true;
                                                        MainActivity.EXPORTING_PRINTS.add(FarmApp.CONFIG.printConfigs.get(i));
                                                    }
                                                }
                                                MainActivity.EXPORTING_FILAMENTS = new ArrayList<>();
                                                for (int i = 0; i < enabledFilaments.length; i++) {
                                                    if (enabledFilaments[i]) {
                                                        hasEnabled = true;
                                                        MainActivity.EXPORTING_FILAMENTS.add(FarmApp.CONFIG.filamentConfigs.get(i));
                                                    }
                                                }
                                                MainActivity.EXPORTING_PRINTERS = new ArrayList<>();
                                                for (int i = 0; i < enabledPrinters.length; i++) {
                                                    if (enabledPrinters[i]) {
                                                        hasEnabled = true;
                                                        MainActivity.EXPORTING_PRINTERS.add(FarmApp.CONFIG.printerConfigs.get(i));
                                                    }
                                                }
                                                if (!hasEnabled) {
                                                    new FarmAlertDialogBuilder(v.getContext())
                                                            .setTitle(R.string.MenuFileExportProfiles)
                                                            .setMessage(R.string.MenuFileExportProfilesNoProfiles)
                                                            .setPositiveButton(android.R.string.ok, null)
                                                            .show();
                                                    return;
                                                }

                                                if (fragment.getContext() instanceof Activity) {
                                                    Activity act = (Activity) fragment.getContext();
                                                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                                    i.setType("application/ini");
                                                    i.putExtra(Intent.EXTRA_TITLE, "FlashForgeFarm_config_bundle.ini");
                                                    ((com.flashforge.farm.MainActivity) act).pickFile(i, MainActivity.REQUEST_CODE_EXPORT_PROFILES);
                                                }
                                            })
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .show())
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }),
                new BedMenuItem(R.string.MenuFileExport3mf, R.drawable.arrow_down_to_square_outline_28).setEnabled(hasModel()).onClick(v -> {
                    if (fragment.getContext() instanceof Activity) {
                        Activity act = (Activity) fragment.getContext();
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.setType("application/3mf");
                        i.putExtra(Intent.EXTRA_TITLE, "FlashForgeFarm_project.3mf");
                        ((com.flashforge.farm.MainActivity) act).pickFile(i, MainActivity.REQUEST_CODE_EXPORT_3MF);
                    }
                })
        ));
        return list;
    }

    public final class CalibrationsMenu extends UnfoldMenu {
        private final Bus.Listener<NeedDismissCalibrationsMenu> onDismiss = e -> {
            if (e.source == this) return;
            dismiss();
        };
        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return (int) (portrait ? into.getHeight() * 0.35f : into.getWidth() * 0.6f);
        }

        private void ensureCalibModel(int kind) {
            // Same guard as the gallery tap path: a null/unconfigured bed
            // must toast, never NPE-crash the tap.
            com.flashforge.farm.slic3r.Bed3D bed = null;
            if (FileMenu.this.fragment != null && FileMenu.this.fragment.getGlView() != null
                    && FileMenu.this.fragment.getGlView().getRenderer() != null) {
                bed = FileMenu.this.fragment.getGlView().getRenderer().getBed();
            }
            if (bed == null || !bed.isValid()) {
                android.widget.Toast.makeText(FarmApp.INSTANCE, R.string.BedConfigurationError, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                try {
                    com.flashforge.farm.gallery.ShapeGallery.Item item = calibItem(kind);
                    FarmApp.PENDING_CALIB_ITEM = item.id;
                    File f = com.flashforge.farm.gallery.ShapeGallery.fileFor(item);
                    // Blocking: service bind + sandbox parse + native load.
                    // Must stay off the UI thread or taps ANR-freeze (an ANR
                    // is not an uncaught exception, so no crash log).
                    FileMenu.this.fragment.loadModel(f);
                    ViewUtils.postOnMainThread(() -> {
                        Bus.OBJECTS_LIST_CHANGED.postValue(new ObjectsListChangedEvent());
                    });
                } catch (Throwable e) {
                    android.util.Log.e("FileMenu", "PA calib model failed", e);
                    FarmApp.writeCrashDump("pa-calib", android.util.Log.getStackTraceString(e));
                    ViewUtils.postOnMainThread(() -> {
                        android.widget.Toast.makeText(FarmApp.INSTANCE,
                                com.flashforge.farm.R.string.MenuFileOpenFileFailed,
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                }
            }, "pa-calib").start();
        }

        private com.flashforge.farm.gallery.ShapeGallery.Item calibItem(int kind) {
            for (com.flashforge.farm.gallery.ShapeGallery.Item it : com.flashforge.farm.gallery.ShapeGallery.builtins()) {
                if (it.kind == kind) return it;
            }
            throw new IllegalStateException("calib kind missing: " + kind);
        }

        private String loadJSLoader(String key) {
            try {
                InputStream in = FarmApp.INSTANCE.getAssets().open("js_loader/" + key + ".js");
                java.io.InputStreamReader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int c;
                while ((c = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, c);
                }
                reader.close();
                in.close();

                ConfigObject cfg = FarmApp.buildCurrentConfigObject();
                Bed3D bed = FileMenu.this.fragment.getGlView().getRenderer().getBed();
                double bedX = bed.getVolumeMax().x - bed.getVolumeMin().x;
                double bedY = bed.getVolumeMax().y - bed.getVolumeMin().y;

                Pattern placeholderPattern = Pattern.compile("\\$\\['(\\w+?)(\\[\\d+]|)']");
                Matcher m = placeholderPattern.matcher(sb);
                StringBuffer resultBuilder = new StringBuffer();
                while (m.find()) {
                    String pKey = m.group(1);
                    String pIndex = m.group(2);
                    int index = pIndex.isEmpty() ? -1 : Integer.parseInt(pIndex.substring(1, pIndex.length() - 1));

                    String v;
                    boolean quote = false;
                    switch (pKey) {
                        case "bed_x":
                            v = String.format(Locale.ROOT, "%.1f", bedX);
                            quote = true;
                            break;
                        case "bed_y":
                            v = String.format(Locale.ROOT, "%.1f", bedY);
                            quote = true;
                            break;
                        case "color_accent":
                            v = String.format(Locale.ROOT, "#%06X", ThemesRepo.getColor(android.R.attr.colorAccent) & 0xFFFFFF);
                            break;
                        case "window_background_dark":
                            v = String.format(Locale.ROOT, "#%06X", FarmTheme.DARK.colors.get(android.R.attr.windowBackground) & 0xFFFFFF);
                            break;
                        case "window_background_light":
                            v = String.format(Locale.ROOT, "#%06X", FarmTheme.LIGHT.colors.get(android.R.attr.windowBackground) & 0xFFFFFF);
                            break;
                        case "is_dark_theme":
                            v = String.valueOf(ColorUtils.calculateLuminance(ThemesRepo.getColor(android.R.attr.windowBackground)) >= 0.9f);
                            break;
                        default:
                            v = cfg.get(pKey);
                            quote = true;
                            break;
                    }
                    if (v != null && index != -1) {
                        try {
                            v = v.split(",")[index];
                        } catch (ArrayIndexOutOfBoundsException ex) {
                            v = "";
                        }
                    }
                    String newVal = escapeStringForJs(v);
                    if (quote) {
                        newVal = "'" + newVal + "'";
                    }
                    m.appendReplacement(resultBuilder, Matcher.quoteReplacement(newVal));
                }
                m.appendTail(resultBuilder);

                return resultBuilder.toString();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);

            RecyclerView rv = new FadeRecyclerView(ctx);
            SimpleRecyclerAdapter adapter = new SimpleRecyclerAdapter();
            adapter.setItems(Arrays.asList(
                    // K3D (Prusa/FarmApp) web calibrators removed in favor of flashforge-farm's calibrations.
                    new PreferenceItem().setIcon(R.drawable.menu_calibrate_la_28).setTitle("Pressure Advance").setSubtitle("flashforge-farm PA line test — slices a PA pattern").setOnClickListener(v -> {
                        FarmApp.PENDING_CALIB_MODE = com.flashforge.farm.gallery.CalibArm.MODE_PA_LINE;
                        FarmApp.PENDING_CALIB_START = com.flashforge.farm.gallery.CalibArm.DEFAULT_START;
                        FarmApp.PENDING_CALIB_END = com.flashforge.farm.gallery.CalibArm.DEFAULT_END;
                        FarmApp.PENDING_CALIB_STEP = com.flashforge.farm.gallery.CalibArm.DEFAULT_STEP;
                        ensureCalibModel(com.flashforge.farm.gallery.ShapeGallery.KIND_CALIB_PA_LINE);
                        Toast.makeText(ctx, "Pressure Advance armed — go to the Slice tab", Toast.LENGTH_LONG).show();
                        Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu(this));
                        dismiss(true);
                    }),
                    new PreferenceItem().setIcon(R.drawable.menu_calibrate_la_28).setTitle("PA Tower").setSubtitle("flashforge-farm PA tower — each layer gets a different PA value").setOnClickListener(v -> {
                        // CalibMode::Calib_PA_Tower: PA increases linearly per layer from start to end.
                        // Default range: 0 to 0.1 with step 0.002 per layer.
                        LinearLayout towerLl = new LinearLayout(ctx);
                        towerLl.setOrientation(LinearLayout.VERTICAL);
                        towerLl.setPadding(ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(16));
                        towerLl.addView(ViewUtils.makeText(ctx, "PA Tower: start value 0, end value per layer", ViewUtils.dp(14), ThemesRepo.getColor(android.R.attr.textColorSecondary)));
                        Space towerGap = new Space(ctx);
                        towerGap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewUtils.dp(8)));
                        towerLl.addView(towerGap);
                        android.widget.EditText startEt = ViewUtils.makeEditText(ctx, "0");
                        android.widget.EditText endEt = ViewUtils.makeEditText(ctx, "0.1");
                        android.widget.EditText stepEt = ViewUtils.makeEditText(ctx, "0.002");
                        ViewUtils.addLabeledEditText(towerLl, "Start PA", "Start pressure advance value", startEt);
                        ViewUtils.addLabeledEditText(towerLl, "End PA", "End pressure advance value", endEt);
                        ViewUtils.addLabeledEditText(towerLl, "Step", "PA step per layer", stepEt);
                        new android.app.AlertDialog.Builder(ctx)
                            .setTitle("PA Tower Calibration")
                            .setView(towerLl)
                            .setPositiveButton("OK", (d, w) -> {
                                try {
                                    FarmApp.PENDING_CALIB_MODE = com.flashforge.farm.gallery.CalibArm.MODE_PA_TOWER;
                                    FarmApp.PENDING_CALIB_START = Double.parseDouble(startEt.getText().toString());
                                    FarmApp.PENDING_CALIB_END = Double.parseDouble(endEt.getText().toString());
                                    FarmApp.PENDING_CALIB_STEP = Double.parseDouble(stepEt.getText().toString());
                                } catch (NumberFormatException e) {
                                    FarmApp.PENDING_CALIB_START = com.flashforge.farm.gallery.CalibArm.DEFAULT_START;
                                    FarmApp.PENDING_CALIB_END = com.flashforge.farm.gallery.CalibArm.DEFAULT_END;
                                    FarmApp.PENDING_CALIB_STEP = com.flashforge.farm.gallery.CalibArm.DEFAULT_STEP;
                                }
                                ensureCalibModel(com.flashforge.farm.gallery.ShapeGallery.KIND_CALIB_PA_TOWER);
                                Toast.makeText(ctx, "PA Tower armed — go to the Slice tab", Toast.LENGTH_LONG).show();
                                Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu(this));
                                dismiss(true);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    }),
                    new PreferenceItem().setIcon(R.drawable.menu_calibrate_la_28).setTitle("PA Pattern").setSubtitle("flashforge-farm PA pattern — number/flow test grid").setOnClickListener(v -> {
                        FarmApp.PENDING_CALIB_MODE = com.flashforge.farm.gallery.CalibArm.MODE_PA_PATTERN;
                        FarmApp.PENDING_CALIB_START = com.flashforge.farm.gallery.CalibArm.DEFAULT_START;
                        FarmApp.PENDING_CALIB_END = com.flashforge.farm.gallery.CalibArm.DEFAULT_END;
                        FarmApp.PENDING_CALIB_STEP = com.flashforge.farm.gallery.CalibArm.DEFAULT_STEP;
                        ensureCalibModel(com.flashforge.farm.gallery.ShapeGallery.KIND_CALIB_PA_PATTERN);
                        Toast.makeText(ctx, "PA Pattern armed — go to the Slice tab", Toast.LENGTH_LONG).show();
                        Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu(this));
                        dismiss(true);
                    }),
                    new PreferenceItem().setIcon(R.drawable.grid_layout_outline_28).setTitle(ctx.getString(R.string.MenuFileShapeGallery)).setSubtitle("Primitives, tags and your own models").setOnClickListener(v -> {
                        fragment.showUnfoldMenu(new ShapeGalleryMenu(), v);
                    })
            ));
            rv.setAdapter(adapter);
            ll.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
        protected void onCreate() {
            super.onCreate();

            Bus.DISMISS_CALIBRATIONS_MENU.observeForever(onDismiss);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            Bus.DISMISS_CALIBRATIONS_MENU.removeObserver(onDismiss);
        }
    }
}
