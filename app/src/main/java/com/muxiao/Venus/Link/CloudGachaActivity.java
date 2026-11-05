package com.muxiao.Venus.Link;

import com.muxiao.Venus.common.BaseActivity;
import static com.muxiao.Venus.common.tools.copyToClipboard;
import static com.muxiao.Venus.common.tools.showCustomSnackbar;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import com.muxiao.Venus.R;


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
    /** Compose 版页面宿主：顶栏 / 提示行 / 内容区三态都由它绘制。 */
    private com.muxiao.Venus.common.ui.compose.CloudGachaScreenView pane;
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
        pane = new com.muxiao.Venus.common.ui.compose.CloudGachaScreenView(this);
        setContentView(pane);

        pane.setOnCloseClickListener(this::finish);
        pane.setOnRetryClickListener(this::retryLoad);
        // WebView 延迟到首帧后由 Compose 侧挂载（原 ViewStub + root.post 的等价实现），
        // 创建完成即回到这里配置参数与客户端
        pane.setOnWebViewCreatedListener(this::setupWebView);

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

    /** WebView 就绪后配置参数与客户端并发起首次加载。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView view) {
        webView = view;
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
            // 极端时序（WebView 尚未挂载就点重试）：回到加载态即可，
            // Compose 侧会照常挂载 WebView 并回调 setupWebView 发起首次加载
            showLoading();
            return;
        }
        showLoading();
        loadFailed = false;
        webView.loadUrl(TARGET_URL);
    }

    // 三态只改宿主状态：WebView 不被移除，加载中/失败时由覆盖层遮住（等价于原来的 INVISIBLE）
    private void showLoading() {
        pane.setStage(com.muxiao.Venus.common.ui.compose.CloudGachaScreenKt.CLOUD_STAGE_LOADING);
    }

    private void showContent() {
        pane.setStage(com.muxiao.Venus.common.ui.compose.CloudGachaScreenKt.CLOUD_STAGE_CONTENT);
    }

    private void showError() {
        pane.setStage(com.muxiao.Venus.common.ui.compose.CloudGachaScreenKt.CLOUD_STAGE_ERROR);
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
