package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.muxiao.Venus.BaseActivity;
import com.muxiao.Venus.R;

import android.widget.ImageView;

/**
 * 云游戏获取页：独立 Activity 承载 WebView，访问米游社云游戏页面，
 * 自动拦截含 authkey 的抽卡链接并复制；返回键/关闭按钮退出。
 */
public class CloudGachaActivity extends BaseActivity {

    private WebView webView;
    /** 抽卡链接只需复制一次：页面加载会触发多次含 authkey 的资源请求，原实现每次都弹提示。 */
    private boolean linkCopied = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 与其他二级页面一致：setContentView 之前应用所选主题（基础主题 + 色彩 Overlay）
        com.muxiao.Venus.Setting.SettingsFragment.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_gacha);

        ImageView closeButton = findViewById(R.id.cloud_close_button);
        closeButton.setOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 修改 navigator.language 和 navigator.languages 属性
                view.evaluateJavascript(
                        "Object.defineProperty(navigator, 'language', {get: function(){return 'zh-CN';}});" +
                                "Object.defineProperty(navigator, 'languages', {get: function(){return ['zh-CN'];}});",
                        null
                );
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("authkey=")) {
                    String[] gameHosts = {
                            "public-operation-hk4e.",
                            "public-operation-hkrpg.",
                            "public-operation-nap.",
                    };
                    for (String host : gameHosts) {
                        if (url.contains(host)) {
                            // linkCopied 仅在主线程读写（runOnUiThread 内部），无需额外同步
                            runOnUiThread(() -> {
                                if (linkCopied) return;
                                linkCopied = true;
                                copyToClipboard(view, CloudGachaActivity.this, url);
                                showCustomSnackbar(view, CloudGachaActivity.this, getString(R.string.snack_link_copied));
                            });
                            break;
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.loadUrl("https://mhyy.mihoyo.com/");
    }

    /** 返回键优先回退 WebView 历史，无历史时退出页面。 */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
