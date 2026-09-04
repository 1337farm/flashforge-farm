package com.flashforge.farm.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckedTextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.flashforge.farm.R;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class FarmAlertDialogBuilder extends MaterialAlertDialogBuilder {
    public FarmAlertDialogBuilder(@NonNull Context context) {
        super(context);
    }

    public FarmAlertDialogBuilder(@NonNull Context context, int overrideThemeResId) {
        super(context, overrideThemeResId);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        dialog.getWindow().setBackgroundDrawable(new GradientDrawable() {{
            setCornerRadius(ViewUtils.dp(32));
            setColor(ThemesRepo.getColor(R.attr.dialogBackground));
        }});
        return dialog;
    }

    @Override
    public AlertDialog show() {
        AlertDialog dialog = super.show();
        View v = dialog.getWindow().getDecorView();
        TextView v2;
        int id = getContext().getResources().getIdentifier("alertTitle", "id", getContext().getPackageName());
        if (id > 0) {
            v2 = v.findViewById(id);
            if (v2 != null) {
                v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            }
        }
        v2 = v.findViewById(android.R.id.message);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        }

        v2 = v.findViewById(android.R.id.button1);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        }
        v2 = v.findViewById(android.R.id.button2);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        }
        v2 = v.findViewById(android.R.id.button3);
        if (v2 != null) {
            v2.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        }
        id = getContext().getResources().getIdentifier("select_dialog_listview", "id", getContext().getPackageName());
        if (id > 0) {
            ListView lv = v.findViewById(id);
            if (lv != null) {
                ListAdapter wrapped = lv.getAdapter();
                SparseBooleanArray checked = lv.getCheckedItemPositions() != null ? lv.getCheckedItemPositions().clone() : null;
                lv.setAdapter(new ListAdapter() {
                    @Override
                    public boolean areAllItemsEnabled() {
                        return wrapped.areAllItemsEnabled();
                    }

                    @Override
                    public boolean isEnabled(int position) {
                        return wrapped.isEnabled(position);
                    }

                    @Override
                    public void registerDataSetObserver(DataSetObserver observer) {
                        wrapped.registerDataSetObserver(observer);
                    }

                    @Override
                    public void unregisterDataSetObserver(DataSetObserver observer) {
                        wrapped.unregisterDataSetObserver(observer);
                    }

                    @Override
                    public int getCount() {
                        return wrapped.getCount();
                    }

                    @Override
                    public Object getItem(int position) {
                        return wrapped.getItem(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return wrapped.getItemId(position);
                    }

                    @Override
                    public boolean hasStableIds() {
                        return wrapped.hasStableIds();
                    }

                    @SuppressLint("RestrictedApi")
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = wrapped.getView(position, convertView, parent);
                        TextView text = v.findViewById(android.R.id.text1);
                        if (text != null) {
                            text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
                            if (text instanceof AppCompatCheckedTextView) {
                                ((AppCompatCheckedTextView) text).setSupportCompoundDrawablesTintList(new ColorStateList(new int[][]{
                                        {android.R.attr.state_enabled, android.R.attr.state_checked},
                                        {android.R.attr.state_enabled, -android.R.attr.state_checked}
                                }, new int[]{
                                        ThemesRepo.getColor(android.R.attr.colorAccent),
                                        ThemesRepo.getColor(R.attr.dividerContrastColor)
                                }));
                            }
                        }
                        return v;
                    }

                    @Override
                    public int getItemViewType(int position) {
                        return wrapped.getItemViewType(position);
                    }

                    @Override
                    public int getViewTypeCount() {
                        return wrapped.getViewTypeCount();
                    }

                    @Override
                    public boolean isEmpty() {
                        return wrapped.isEmpty();
                    }

                    @RequiresApi(api = Build.VERSION_CODES.O)
                    @Nullable
                    @Override
                    public CharSequence[] getAutofillOptions() {
                        return wrapped.getAutofillOptions();
                    }
                });
                if (checked != null) {
                    for (int i = 0; i < checked.size(); i++) {
                        lv.setItemChecked(checked.keyAt(i), checked.valueAt(i));
                    }
                }
            }
        }

        return dialog;
    }
}
