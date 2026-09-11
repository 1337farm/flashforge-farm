package com.flashforge.farm.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.flashforge.farm.Bus;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.MainActivity;
import com.flashforge.farm.R;
import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.components.UnfoldMenu;
import com.flashforge.farm.events.NeedDismissCalibrationsMenu;
import com.flashforge.farm.events.NeedSnackbarEvent;
import com.flashforge.farm.events.ObjectsListChangedEvent;
import com.flashforge.farm.recycler.PreferenceItem;
import com.flashforge.farm.recycler.SimpleRecyclerAdapter;
import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.DividerView;
import com.flashforge.farm.view.FadeRecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShapeGalleryMenu extends UnfoldMenu {
    private SimpleRecyclerAdapter adapter;

    @Override
    public int getRequestedSize(FrameLayout into, boolean portrait) {
        return (int) (portrait ? into.getHeight() * 0.6f : into.getWidth() * 0.8f);
    }

    @Override
    protected View onCreateView(Context ctx, boolean portrait) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(ctx);
        header.setText(R.string.MenuFileShapeGallery);
        header.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        header.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        header.setPadding(ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(8));
        ll.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ll.addView(new DividerView(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1f)));

        RecyclerView rv = new FadeRecyclerView(ctx);
        adapter = new SimpleRecyclerAdapter();
        ArrayList<SimpleRecyclerItem> loading = new ArrayList<SimpleRecyclerItem>();
        loading.add(new PreferenceItem().setTitle(ctx.getString(R.string.MenuFileShapeGalleryLoading)));
        adapter.setItems(loading);
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

        reload();
        return ll;
    }

    private void reload() {
        new Thread(() -> {
            final List<SimpleRecyclerItem> rows = buildRows();
            ViewUtils.postOnMainThread(() -> {
                if (adapter != null) adapter.setItems(rows);
            });
        }, "gallery-load").start();
    }

    private List<SimpleRecyclerItem> buildRows() {
        ArrayList<SimpleRecyclerItem> rows = new ArrayList<SimpleRecyclerItem>();
        rows.add(new PreferenceItem()
                .setIcon(R.drawable.folder_simple_plus_outline_28)
                .setTitle(FarmApp.INSTANCE.getString(R.string.MenuFileShapeGalleryAdd))
                .setOnClickListener(v -> {
                    Context ctx = fragment == null ? null : fragment.getContext();
                    if (ctx instanceof Activity) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        ((Activity) ctx).startActivityForResult(i, MainActivity.REQUEST_CODE_IMPORT_GALLERY);
                    }
                }));
        ArrayList<ShapeGallery.Item> items = new ArrayList<ShapeGallery.Item>();
        items.addAll(ShapeGallery.builtins());
        items.addAll(ShapeGallery.customs());
        for (ShapeGallery.Item item : items) {
            GalleryMesh preview = null;
            try {
                preview = ShapeGallery.previewFor(item);
            } catch (Exception ignored) {
            }
            final ShapeGallery.Item tapped = item;
            GalleryRowItem row = new GalleryRowItem(item, preview);
            row.setOnClickListener(v -> loadItem(tapped));
            if (item.kind == ShapeGallery.KIND_CUSTOM) {
                row.setOnLongClickListener(v -> {
                    confirmDelete(tapped);
                    return true;
                });
            }
            rows.add(row);
        }
        return rows;
    }

    private void loadItem(ShapeGallery.Item item) {
        if (!fragment.getGlView().getRenderer().getBed().isValid()) {
            Toast.makeText(fragment.getContext(), R.string.BedConfigurationError, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                final File f = ShapeGallery.fileFor(item);
                ViewUtils.postOnMainThread(() -> {
                    try {
                        fragment.loadModel(f);
                        Bus.OBJECTS_LIST_CHANGED.postValue(new ObjectsListChangedEvent());
                        Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(R.string.MenuFileOpenFileLoaded));
                    } catch (Exception e) {
                        Toast.makeText(FarmApp.INSTANCE, R.string.MenuFileOpenFileFailed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                ViewUtils.postOnMainThread(() ->
                        Toast.makeText(FarmApp.INSTANCE, R.string.MenuFileOpenFileFailed, Toast.LENGTH_SHORT).show());
            }
        }, "gallery-slice-load").start();
        Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu());
        dismiss(true);
    }

    private void confirmDelete(ShapeGallery.Item item) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        new FarmAlertDialogBuilder(ctx)
                .setTitle(R.string.MenuFileShapeGalleryDelete)
                .setMessage(item.title)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    new Thread(() -> {
                        GalleryStore.deleteCustom(item.file);
                        ViewUtils.postOnMainThread(() -> {
                            Toast.makeText(FarmApp.INSTANCE, R.string.MenuFileShapeGalleryDeleted, Toast.LENGTH_SHORT).show();
                            reload();
                        });
                    }, "gallery-delete").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
