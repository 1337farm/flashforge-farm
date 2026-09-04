package com.flashforge.farm.components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

import com.flashforge.farm.Bus;
import com.flashforge.farm.R;
import com.flashforge.farm.events.SlicingProgressEvent;
import com.flashforge.farm.slic3r.Slic3rLocalization;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class SliceProgressBottomSheet extends BottomSheetDialog {
    private RecyclerView recyclerView;
    private LinearProgressIndicator indicator;
    private List<String> lines = new ArrayList<>();
    private final Bus.Listener<SlicingProgressEvent> onProgressChanged =
            e -> {
                if (!e.message.isEmpty()) {
                    int size = lines.size();
                    lines.add(Slic3rLocalization.getString(e.message) + "...");
                    recyclerView.getAdapter().notifyItemInserted(size);
                    recyclerView.smoothScrollToPosition(size);
                }
                indicator.setProgressCompat(e.progress, true);

                if (e.progress == 100) {
                    dismiss();
                }
            };

    public SliceProgressBottomSheet(@NonNull Context context) {
        super(context);
        setCancelable(false);

        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundResource(R.drawable.bottom_sheet_rounded_background);
        ll.setBackgroundTintList(ColorStateList.valueOf(ThemesRepo.getColor(R.attr.dialogBackground)));
        ll.setPadding(0, ViewUtils.dp(12), 0, ViewUtils.dp(12));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        title.setText(R.string.SliceInProgress);
        title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            leftMargin = rightMargin = ViewUtils.dp(21);
        }});
        ll.addView(title);

        indicator = new LinearProgressIndicator(context);
        indicator.setIndicatorColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        indicator.setTrackColor(ThemesRepo.getColor(R.attr.dividerColor));
        indicator.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(1)) {{
            topMargin = ViewUtils.dp(8);
            bottomMargin = ViewUtils.dp(8);
        }});
        ll.addView(indicator);

        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();

        boolean portrait = dm.widthPixels < dm.heightPixels;
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * (portrait ? 0.4f : 0.6f))));
        recyclerView.setAdapter(new RecyclerView.Adapter() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                TextView v = new TextView(parent.getContext());
                v.setPadding(ViewUtils.dp(12), ViewUtils.dp(4), ViewUtils.dp(12), ViewUtils.dp(4));
                v.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
                v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextView tv = (TextView) holder.itemView;
                tv.setText(lines.get(position));
            }

            @Override
            public int getItemCount() {
                return lines.size();
            }
        });
        ll.addView(recyclerView);

        ll.setFitsSystemWindows(true);
        setContentView(ll);
    }

    @Override
    public void show() {
        super.show();
        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Bus.SLICING_PROGRESS.observeForever(onProgressChanged);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Bus.SLICING_PROGRESS.removeObserver(onProgressChanged);
    }
}
