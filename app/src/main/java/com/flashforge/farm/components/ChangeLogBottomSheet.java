package com.flashforge.farm.components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.FarmButton;

public class ChangeLogBottomSheet extends BottomSheetDialog {
    private ScrollView scrollView;

    public ChangeLogBottomSheet(@NonNull Context context) {
        super(context);

        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadii(new float[] {
                ViewUtils.dp(28), ViewUtils.dp(28),
                ViewUtils.dp(28), ViewUtils.dp(28),
                0, 0,
                0, 0
        });
        gd.setColor(ThemesRepo.getColor(R.attr.dialogBackground));
        ll.setBackground(gd);
        ll.setPadding(0, ViewUtils.dp(12), 0, ViewUtils.dp(12));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        title.setText(R.string.Changelog);
        title.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            leftMargin = rightMargin = ViewUtils.dp(21);
        }});
        ll.addView(title);

        scrollView = new ScrollView(context);
        TextView text = new TextView(context);
        text.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setPadding(ViewUtils.dp(16), ViewUtils.dp(12), ViewUtils.dp(16), ViewUtils.dp(12));

        try {
            InputStream in = getContext().getAssets().open("update.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[10240]; int c;
            while ((c = in.read(buffer)) != -1) {
                bos.write(buffer, 0, c);
            }
            bos.close();
            in.close();

            JSONObject obj = new JSONObject(bos.toString());
            String code = Locale.getDefault().getLanguage();
            if (obj.has(code)) {
                text.setText(obj.getString(code));
            } else {
                text.setText(obj.getString("en"));
            }
        } catch (Exception e) {
            Log.e("Changelog", "Failed to open update file", e);
        }
        scrollView.addView(text);
        ll.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FarmButton btn = new FarmButton(context);
        btn.setText(R.string.ChangelogOK);
        btn.setOnClickListener(v -> dismiss());
        ll.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(48)) {{
            leftMargin = topMargin = rightMargin = bottomMargin = ViewUtils.dp(12);
        }});

        ll.setFitsSystemWindows(true);
        setContentView(ll);

    }

    @Override
    public void show() {
        super.show();
        getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
