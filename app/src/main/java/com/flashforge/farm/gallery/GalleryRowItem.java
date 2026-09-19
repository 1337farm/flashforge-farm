package com.flashforge.farm.gallery;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.theme.FarmTheme;
import com.flashforge.farm.theme.IThemeView;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class GalleryRowItem extends SimpleRecyclerItem<GalleryRowItem.RowView> {
    private final ShapeGallery.Item item;
    private final GalleryMesh preview;
    private View.OnClickListener onClickListener;
    private View.OnLongClickListener onLongClickListener;

    public GalleryRowItem(ShapeGallery.Item item, GalleryMesh preview) {
        this.item = item;
        this.preview = preview;
    }

    public GalleryRowItem setOnClickListener(View.OnClickListener l) {
        onClickListener = l;
        return this;
    }

    public GalleryRowItem setOnLongClickListener(View.OnLongClickListener l) {
        onLongClickListener = l;
        return this;
    }

    @Override
    public RowView onCreateView(Context ctx) {
        return new RowView(ctx);
    }

    @Override
    public void onBindView(RowView view) {
        view.bind(this);
    }

    public final static class RowView extends LinearLayout implements IThemeView {
        private final SpinningPreviewView preview;
        private final TextView title;
        private final TextView subtitle;

        public RowView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int p = ViewUtils.dp(12);
            setPadding(p, ViewUtils.dp(8), p, ViewUtils.dp(8));

            preview = new SpinningPreviewView(context);
            addView(preview, new LayoutParams(ViewUtils.dp(64), ViewUtils.dp(64)) {{
                setMarginEnd(ViewUtils.dp(8));
            }});

            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
            inner.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            inner.addView(subtitle);
            addView(inner, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            setMinimumHeight(ViewUtils.dp(76));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            onApplyTheme();
        }

        void bind(GalleryRowItem item) {
            title.setText(item.item.title);
            CharSequence sub = item.item.subtitle;
            subtitle.setText(sub);
            subtitle.setVisibility(TextUtils.isEmpty(sub) ? GONE : VISIBLE);
            preview.setMesh(item.preview);
            if (item.onClickListener != null) {
                setOnClickListener(item.onClickListener);
            } else {
                setClickable(false);
            }
            setOnLongClickListener(item.onLongClickListener);
        }

        @Override
        public void onApplyTheme() {
            FarmTheme theme = ThemesRepo.getCurrent();
            title.setTextColor(theme.colors.get(android.R.attr.textColorPrimary));
            subtitle.setTextColor(theme.colors.get(android.R.attr.textColorSecondary));
            setBackground(ViewUtils.createRipple(theme.colors.get(android.R.attr.colorControlHighlight), 16));
        }
    }
}
