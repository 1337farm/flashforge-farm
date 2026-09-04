package com.flashforge.farm.components;

import com.flashforge.farm.Bus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

import com.flashforge.farm.BuildConfig;
import com.flashforge.farm.R;
import com.flashforge.farm.FarmApp;
import com.flashforge.farm.events.NeedDismissCalibrationsMenu;
import com.flashforge.farm.fragment.BedFragment;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.DividerView;

public class WebViewMenu extends UnfoldMenu {
    private final Uri uri;
    private String javascript;
    private BedFragment fragment;

    private FileOutputStream fileStream;
    private File cacheFile;

    public WebViewMenu(Uri uri) {
        this.uri = uri;
    }

    public WebViewMenu(Uri uri, String javascript) {
        this(uri);
        this.javascript = javascript;
    }

    public WebViewMenu setFragment(BedFragment fragment) {
        this.fragment = fragment;
        return this;
    }

    @SuppressLint("SetJavaScriptEnabled")
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

        WebView webView = new WebView(ctx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (fileStream != null) {
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        webView.addJavascriptInterface(new Bridge(), "FarmApp");

        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(String.format(Locale.ROOT, "FarmApp/%s-%d", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(false);

        webView.loadUrl(uri.toString());
        webView.setAlpha(0f);
        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri requestUri = Uri.parse(url);
                return handleUrlLoading(view, requestUri);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                Uri requestUri = request.getUrl();
                return handleUrlLoading(view, requestUri);
            }

            private boolean handleUrlLoading(WebView view, Uri requestUri) {
                if (requestUri != null && requestUri.getHost() != null && uri.getHost() != null) {
                    if (requestUri.getHost().equals(uri.getHost()) &&
                            (requestUri.getScheme() != null && requestUri.getScheme().equals(uri.getScheme()))) {
                        return false;
                    }
                }

                Intent intent = new Intent(Intent.ACTION_VIEW, requestUri);
                try {
                    view.getContext().startActivity(intent);
                } catch (Exception e) {
                    Log.e("WebViewMenu", "Failed to start activity for URL: " + requestUri, e);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (javascript != null) {
                    webView.evaluateJavascript(javascript, value -> ViewUtils.postOnMainThread(() -> webView.animate().alpha(1f).start()));
                } else {
                    webView.animate().alpha(1f).start();
                }
            }
        });

        ll.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return ll;
    }

    @Override
    public int getRequestedSize(FrameLayout into, boolean portrait) {
        return portrait ? into.getHeight() - into.getPaddingTop() - into.getPaddingBottom() : into.getWidth();
    }

    private final class Bridge {

        @JavascriptInterface
        public void beginDownload(String filename) {
            // Prevent path traversal vulnerability by sanitizing the filename
            String safeFilename = new File(filename).getName();
            cacheFile = new File(FarmApp.getModelCacheDir(), safeFilename);
            try {
                fileStream = new FileOutputStream(cacheFile);
            } catch (Exception e) {
                Log.e("WebViewMenu", "Failed to begin download", e);
            }
        }

        @JavascriptInterface
        public void writeData(String data) {
            try {
                fileStream.write(Base64.decode(data, 0));
            } catch (Exception e) {
                Log.e("WebViewMenu", "Failed to write to stream", e);
            }
        }

        @JavascriptInterface
        public void finishDownload() {
            try {
                fileStream.close();

                ViewUtils.postOnMainThread(() -> {
                    dismiss(true);
                    Bus.DISMISS_CALIBRATIONS_MENU.postValue(new NeedDismissCalibrationsMenu());
                    ViewUtils.postOnMainThread(() -> fragment.loadGCode(cacheFile), 200);
                });
            } catch (Exception e) {
                Log.e("WebViewMenu", "Failed to finish file", e);
            }
        }
    }
}
