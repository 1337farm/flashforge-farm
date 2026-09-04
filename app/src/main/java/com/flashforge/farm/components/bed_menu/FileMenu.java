package com.flashforge.farm.components.bed_menu;

import com.flashforge.farm.Bus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import com.flashforge.farm.SetupActivity;
import com.flashforge.farm.FarmApp;

import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.components.UnfoldMenu;
import com.flashforge.farm.config.ConfigObject;

import com.flashforge.farm.events.NeedDismissCalibrationsMenu;
import com.flashforge.farm.events.NeedDismissSnackbarEvent;
import com.flashforge.farm.events.NeedSnackbarEvent;
import com.flashforge.farm.events.ObjectsListChangedEvent;
import com.flashforge.farm.events.SelectedObjectChangedEvent;
import com.flashforge.farm.fragment.BedFragment;
import com.flashforge.farm.recycler.PreferenceItem;
import com.flashforge.farm.recycler.SimpleRecyclerAdapter;
import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.slic3r.Bed3D;
import com.flashforge.farm.slic3r.Slic3rRuntimeError;
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
                        act.startActivityForResult(i, MainActivity.REQUEST_CODE_OPEN_FILE);
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
                        act.startActivityForResult(i, MainActivity.REQUEST_CODE_IMPORT_PROFILES);
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
                                                    act.startActivityForResult(i, MainActivity.REQUEST_CODE_EXPORT_PROFILES);
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
                        act.startActivityForResult(i, MainActivity.REQUEST_CODE_EXPORT_3MF);
                    }
                })
        ));
        return list;
    }

    public final class CalibrationsMenu extends UnfoldMenu {
        private final Bus.Listener<NeedDismissCalibrationsMenu> onDismiss = e -> dismiss();
        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return (int) (portrait ? into.getHeight() * 0.35f : into.getWidth() * 0.6f);
        }

        /** Synchronously load a bundled placeholder model from assets/models/<key>.stl onto the bed. */
        private void ensurePlaceholderModel(String key) {
            try {
                File f = new File(FarmApp.getModelCacheDir(), "calibration_" + key + ".stl");
                InputStream in = FarmApp.INSTANCE.getAssets().open("models/" + key + ".stl");
                FileOutputStream fos = new FileOutputStream(f);
                byte[] buffer = new byte[10240]; int c;
                while ((c = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, c);
                }
                fos.close();
                in.close();
                FileMenu.this.fragment.loadModel(f);
                Bus.OBJECTS_LIST_CHANGED.postValue(new ObjectsListChangedEvent());
            } catch (Exception e) {
                Log.e("FileMenu", "Failed to load PA placeholder model", e);
            }
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
                        FarmApp.PENDING_CALIB_MODE = 1; // CalibMode::Calib_PA_Line
                        FarmApp.PENDING_CALIB_START = 0;
                        FarmApp.PENDING_CALIB_END = 0.1;
                        FarmApp.PENDING_CALIB_STEP = 0.002;
                        // The PA line generates its own gcode pattern (GCode.cpp), but the engine still
                        // needs at least one object on the bed to pass validation. If the bed is empty,
                        // drop in a tiny placeholder; its geometry is never printed (engine skips normal
                        // object printing in Calib_PA_Line mode).
                        boolean hasModel = FileMenu.this.fragment.getGlView().getRenderer().getModel() != null;
                        if (!hasModel) {
                            ensurePlaceholderModel("pa_test");
                        }
                        Toast.makeText(ctx, "Pressure Advance armed — go to the Slice tab", Toast.LENGTH_LONG).show();
                        Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu());
                        dismiss(true);
                    }),
                    new PreferenceItem().setIcon(R.drawable.deployed_code_24).setTitle(ctx.getString(R.string.MenuFileCalibrationsModels)).setSubtitle(ctx.getString(R.string.MenuFileCalibrationsModelsDescription)).setOnClickListener(v -> {
                        if (ctx instanceof MainActivity) {
                            ((MainActivity) ctx).showUnfoldMenu(new CalibrationModelsMenu().setFragment(fragment), v);
                        }
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

    public final static class CalibrationModelsMenu extends UnfoldMenu {
        private void loadModel(String key) {
            BedFragment fragment = this.fragment;
            ViewUtils.postOnMainThread(() -> {
                File f = new File(FarmApp.getModelCacheDir(), "handy_model_" + key);
                new Thread(()->{
                    try {
                        InputStream in = FarmApp.INSTANCE.getAssets().open("models/" + key);
                        FileOutputStream fos = new FileOutputStream(f);
                        byte[] buffer = new byte[10240]; int c;
                        while ((c = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, c);
                        }
                        fos.close();
                        in.close();

                        ViewUtils.postOnMainThread(() -> {
                            try {
                                if (f.getName().endsWith(".gcode")) {
                                    fragment.loadGCode(f);
                                } else {
                                    fragment.loadModel(f);
                                    Bus.OBJECTS_LIST_CHANGED.postValue(new ObjectsListChangedEvent());
                                }
                                Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(R.string.MenuFileOpenFileLoaded));
                            } catch (Slic3rRuntimeError e) {
                                f.delete();
                                android.util.Log.e("FarmApp", "Slic3r error: ", e);

                                ViewUtils.postOnMainThread(() -> new FarmAlertDialogBuilder(fragment.getContext())
                                        .setTitle(R.string.MenuFileOpenFileFailed)
                                        .setMessage(e.toString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show());
                            }
                        });
                    } catch (Exception e) {
                        f.delete();
                        ViewUtils.postOnMainThread(() -> new FarmAlertDialogBuilder(fragment.getContext())
                                .setTitle(R.string.MenuFileOpenFileFailed)
                                .setMessage(e.toString())
                                .setPositiveButton(android.R.string.ok, null)
                                .show());
                    }
                }).start();
            }, 200);
            Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu());
            dismiss(true);
        }

        @Override
        protected View onCreateView(Context ctx, boolean portrait) {
            LinearLayout ll = new LinearLayout(ctx);
            ll.setOrientation(LinearLayout.VERTICAL);

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

            ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

            RecyclerView rv = new FadeRecyclerView(ctx);
            SimpleRecyclerAdapter adapter = new SimpleRecyclerAdapter();
            adapter.setItems(Arrays.asList(
                    new PreferenceItem().setIcon(R.drawable.model_thumb_orcacube_v2).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Orca Cube").setOnClickListener(v -> loadModel("OrcaCube_v2.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_orcatolerancetest).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Orca Tolerance Test").setOnClickListener(v -> loadModel("OrcaToleranceTest.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_3dbenchy).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle(ctx.getString(R.string.MenuFileCalibrationsModels3DBenchy)).setOnClickListener(v -> loadModel("3dbenchy.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_calicat).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Cali Cat").setOnClickListener(v -> loadModel("calicat.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_ksr_fdmtest_v4).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Autodesk FDM Test").setOnClickListener(v -> loadModel("ksr_fdmtest_v4.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_voron_design_cube_v7).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Voron Cube").setOnClickListener(v -> loadModel("Voron_Design_Cube_v7.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_stanford_bunny).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Stanford Bunny").setOnClickListener(v -> loadModel("Stanford_Bunny.stl")),
                    new PreferenceItem().setIcon(R.drawable.model_thumb_orca_stringhell).setNoTint(true).setRoundRadius(ViewUtils.dp(8)).setTitle("Orca String Hell").setOnClickListener(v -> loadModel("Orca_stringhell.stl"))
            ));
            rv.setAdapter(adapter);
            ll.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return ll;
        }

        @Override
        public int getRequestedSize(FrameLayout into, boolean portrait) {
            return portrait ? into.getHeight() - into.getPaddingTop() - into.getPaddingBottom() : into.getWidth();
        }

        public CalibrationModelsMenu setFragment(BedFragment fragment) {
            this.fragment = fragment;
            return this;
        }
    }
}
