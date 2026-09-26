package com.muxiao.Venus.Link;

import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewStub;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.muxiao.Venus.BaseActivity;
import com.muxiao.Venus.R;

import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * 云游戏获取页：独立 Activity 承载 WebView，访问米游社云游戏页面，
 * 自动拦截含 authkey 的抽卡链接并复制；返回键/关闭按钮退出。
 * <p>
 * 体验要点：
 * <ul>
 *   <li>WebView 经 ViewStub 延迟到首帧后再展开——其首次初始化是主线程重活，
 *       同步展开会拖慢首帧、表现为点击入口后卡顿才进入本页；</li>
 *   <li>顶栏注入状态栏内边距（与其他二级页同一套 insets 处理）；</li>
 *   <li>加载中/失败/重试三态：主帧请求失败或 HTTP 错误时显示失败态并隐藏
 *       WebView，不再表现为无感知白屏。</li>
 * </ul>
 */
public class CloudGachaActivity extends BaseActivity {

    private static final String TARGET_URL = "https://mhyy.mihoyo.com/";

    private WebView webView;
    private ProgressBar loadingView;
    private View errorView;
    /** 抽卡链接只需复制一次：页面加载会触发多次含 authkey 的资源请求，原实现每次都弹提示。 */
    private boolean linkCopied = false;
    /** 主帧加载是否失败：onPageFinished 与错误回调都可能出现，以最后一次状态为准。 */
    private boolean loadFailed = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 与其他二级页面一致：setContentView 之前应用所选主题（基础主题 + 色彩 Overlay）
        com.muxiao.Venus.Setting.SettingsFragment.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_gacha);

        ImageView closeButton = findViewById(R.id.cloud_close_button);
        closeButton.setOnClickListener(v -> finish());

        loadingView = findViewById(R.id.cloud_loading);
        errorView = findViewById(R.id.cloud_error);
        findViewById(R.id.cloud_retry).setOnClickListener(v -> retryLoad());

        // 顶栏空出状态栏位置（与其他二级页同一套：insets 监听器在原 padding 基础上叠加）
        LinearLayout header = findViewById(R.id.cloud_header);
        final int headerTopPadding = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(header, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), headerTopPadding + statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // WebView 延迟到首帧后再展开：root.post 排到当前遍历（含首帧绘制）之后执行，
        // 首帧先渲染顶栏 + 加载指示器，点击入口立即有转场反馈
        findViewById(R.id.cloud_root).post(this::setupWebView);

        // 返回键优先回退 WebView 历史，无历史时退出页面。
        // 注：onBackPressed 在预测性返回手势下不再被调用，改用 OnBackPressedDispatcher 注册。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    /** 展开 ViewStub 得到 WebView，配置参数与客户端并发起首次加载。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        ViewStub stub = findViewById(R.id.webview_stub);
        if (stub == null) return; // 已展开（如重入）
        webView = (WebView) stub.inflate();
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
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 新一次主帧导航开始：恢复「加载中」态（含重试后重载与站内跳转）
                loadFailed = false;
                showLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (loadFailed) return; // 失败态保持失败提示，避免白屏盖住
                showContent();
                // 修改 navigator.language 和 navigator.languages 属性
                view.evaluateJavascript(
                        "Object.defineProperty(navigator, 'language', {get: function(){return 'zh-CN';}});" +
                                "Object.defineProperty(navigator, 'languages', {get: function(){return ['zh-CN'];}});",
                        null
                );
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // 仅主帧失败才算页面失败：子资源（图片/脚本）失败不影响整体可用
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showError();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                // HTTP 错误码（404/5xx 等）同样只关注主帧，与网络错误同出口
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showError();
                }
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

        webView.loadUrl(TARGET_URL);
    }

    /** 重试：回「加载中」态并重新发起主帧加载。 */
    private void retryLoad() {
        if (webView == null) {
            // 极端时序（WebView 尚未展开就点重试）：重建整个加载流程
            showLoading();
            findViewById(R.id.cloud_root).post(this::setupWebView);
            return;
        }
        showLoading();
        loadFailed = false;
        webView.loadUrl(TARGET_URL);
    }

    private void showLoading() {
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.VISIBLE);
    }

    private void showError() {
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        if (webView != null) webView.setVisibility(View.INVISIBLE);
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
