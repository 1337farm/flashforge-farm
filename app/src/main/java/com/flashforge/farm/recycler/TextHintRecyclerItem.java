package com.flashforge.farm.recycler;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class TextHintRecyclerItem extends SimpleRecyclerItem<TextView> {
    public String title;

    public TextHintRecyclerItem() {}

    public TextHintRecyclerItem(String t) {
        title = t;
    }

    @Override
    public TextView onCreateView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        tv.setPadding(ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12));
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    @Override
    public void onBindView(TextView view) {
        view.setText(title);
    }
}
