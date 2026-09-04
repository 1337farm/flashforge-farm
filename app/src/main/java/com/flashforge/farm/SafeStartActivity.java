package com.flashforge.farm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.FarmButton;

public class SafeStartActivity extends Activity {
    private static final int REQ_SAF_CREATE = 1002;
    private String crashLog = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.WHITE);
            View v = getWindow().getDecorView();
            v.setSystemUiVisibility(v.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        crashLog = Prefs.getPrefs().getString("crash", "");

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setText(R.string.AppCrashed);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12), 0);
        ll.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView desc = new TextView(this);
        desc.setTextColor(0x99000000);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        String log = getString(R.string.AppCrashedDesc, Build.VERSION.RELEASE, Build.BRAND + " " + Build.MODEL, crashLog);
        desc.setText(log);
        desc.setPadding(0, 0, 0, ViewUtils.dp(12));
        scroll.setPadding(ViewUtils.dp(12), ViewUtils.dp(12), ViewUtils.dp(12), 0);
        scroll.addView(desc);
        ll.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FarmButton save = new FarmButton(this);
        save.setText(R.string.AppCrashedSave);
        save.setOnClickListener(v -> saveToDownloads());
        ll.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)) {{
            leftMargin = rightMargin = ViewUtils.dp(12);
            topMargin = ViewUtils.dp(8);
        }});

        FarmButton share = new FarmButton(this);
        share.setText(R.string.AppCrashedShare);
        share.setOnClickListener(v -> {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, log);
            sendIntent.setType("text/plain");

            Intent shareIntent = Intent.createChooser(sendIntent, null);
            startActivity(shareIntent);
        });
        ll.addView(share, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)) {{
            leftMargin = rightMargin = ViewUtils.dp(12);
            topMargin = ViewUtils.dp(8);
        }});

        TextView restart = new TextView(this);
        restart.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        restart.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        restart.setGravity(Gravity.CENTER);
        restart.setTextColor(ThemesRepo.getColor(android.R.attr.colorAccent));
        restart.setText(R.string.AppCrashedRestart);
        restart.setBackground(ViewUtils.createRipple(ColorUtils.setAlphaComponent(ThemesRepo.getColor(android.R.attr.colorAccent), 0x21), 16));
        restart.setOnClickListener(v -> {
            Prefs.getPrefs().edit().remove("crash").apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        ll.addView(restart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewUtils.dp(52)) {{
            leftMargin = rightMargin = ViewUtils.dp(12);
            topMargin = ViewUtils.dp(8);
            bottomMargin = ViewUtils.dp(12);
        }});

        setContentView(ll);
    }

    private void saveToDownloads() {
        // On API 29+ an app owns files it creates via MediaStore.Downloads, so no
        // WRITE_EXTERNAL_STORAGE grant is required. Attempt that first; if it
        // fails, fall back to the SAF save picker (works on every API, no perms).
        if (doSaveMediaStore()) {
            Toast.makeText(this, R.string.AppCrashedSaved, Toast.LENGTH_LONG).show();
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("text/plain");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        create.putExtra(Intent.EXTRA_TITLE, "farm_crash_" + stamp + ".log");
        try {
            startActivityForResult(create, REQ_SAF_CREATE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.AppCrashedSaveFailed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean doSaveMediaStore() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        return FarmApp.saveToDownloads(this, "farm_crash_" + stamp + ".log", crashLog);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAF_CREATE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            boolean ok = FarmApp.writeToUri(this, data.getData(), crashLog);
            Toast.makeText(this,
                    ok ? R.string.AppCrashedSaved : R.string.AppCrashedSaveFailed,
                    Toast.LENGTH_LONG).show();
        }
    }
}
