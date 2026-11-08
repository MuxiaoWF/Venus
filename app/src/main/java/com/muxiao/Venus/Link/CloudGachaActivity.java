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

import com.muxiao.Venus.Home.CloudGameDaily;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;


/**
 * 云游戏 WebView 页，两种用途共用同一套页面外壳（顶栏 / 提示行 / 加载·失败·重试三态）：
 * <ul>
 *   <li><b>抽卡链接</b>（默认）：访问米哈游云游戏平台，自动拦截含 authkey 的抽卡链接并复制；</li>
 *   <li><b>抓取云游戏 Token</b>（{@link #MODE_CLOUD_TOKEN}）：访问云游戏网页，
 *       从 {@code wallet/wallet.get} 的**请求头**里取 {@code x-rpc-combo_token} 并按用户落库。</li>
 * </ul>
 * 返回键优先回退 WebView 历史，无历史时退出页面。
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

    /** 默认模式：拦截含 authkey 的抽卡链接并复制到剪贴板。 */
    public static final String MODE_GACHA = "gacha";
    /** 抓取云游戏 token（{@code X-Rpc-Combo_token}）。 */
    public static final String MODE_CLOUD_TOKEN = "cloud_token";

    public static final String EXTRA_MODE = "extra_mode";
    /**
     * 可选：指定关心的云游戏（{@code CloudGameDaily.GAME_GENSHIN} / {@code GAME_ZZZ}），
     * 仅影响「完成」判据（回传 RESULT_OK 的时机），不影响入口网址——
     * 抓取始终走云游戏平台页，任意一款命中都会落库。
     */
    public static final String EXTRA_CLOUD_GAME = "extra_cloud_game";

    /** 抽卡模式入口：米哈游云游戏平台（含原神/星铁/绝区零入口）。 */
    private static final String GACHA_URL = "https://mhyy.mihoyo.com/";
    /** 抓 Token 模式入口：与抽卡同为云游戏平台页——它是唯一同时含两款云游戏入口的页面。 */
    private static final String PLATFORM_URL = "https://mhyy.mihoyo.com/";

    private WebView webView;
    /** Compose 版页面宿主：顶栏 / 提示行 / 内容区三态都由它绘制。 */
    private com.muxiao.Venus.common.ui.compose.CloudGachaScreenView pane;
    /** 抽卡链接只需复制一次：页面加载会触发多次含 authkey 的资源请求，原实现每次都弹提示。 */
    private boolean linkCopied = false;
    /** 主帧加载是否失败：onPageFinished 与错误回调都可能出现，以最后一次状态为准。 */
    private boolean loadFailed = false;
    /**
     * 当前模式，见 {@link #MODE_GACHA} / {@link #MODE_CLOUD_TOKEN}。
     * volatile：{@code shouldInterceptRequest} 在 WebView 的 IO 线程读取，主线程在 onCreate 写入。
     */
    private volatile String mode = MODE_GACHA;
    /** 抓 Token 模式下的目标游戏（仅用于判定「可以收工了」，非目标游戏的 token 也会保存）。 */
    private volatile String targetGame;
    /** 当前模式实际加载的入口地址。 */
    private volatile String targetUrl = GACHA_URL;
    /** 已落库的 token（按「游戏|值」去重：wallet 接口会被页面多次调用）。仅主线程读写。 */
    private final java.util.Set<String> capturedTokens = new java.util.HashSet<>();
    /** 「未取到有效 token」的提示只弹一次：未登录时钱包请求会反复发，每次都提示会刷屏。 */
    private boolean warnedInvalidToken = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 与其他二级页面一致：setContentView 之前应用所选主题（基础主题 + 色彩 Overlay）
        com.muxiao.Venus.Setting.SettingsFragment.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        pane = new com.muxiao.Venus.common.ui.compose.CloudGachaScreenView(this);
        setContentView(pane);

        resolveModeFromIntent();

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

    /**
     * 解析 Intent 决定模式、入口地址与提示行文案。
     *
     * <p>抓 Token <b>不绑定目标游戏</b>：云游戏平台页里可先后进入云原神与云绝区零，
     * 两边的 wallet 请求都会被拦截、按请求域名自动识别归属。token 本身互不通用
     * （按云游戏服务签发），但共用同一份通行证登录态，所以一次会话就能把两份都拿到。
     */
    private void resolveModeFromIntent() {
        if (!MODE_CLOUD_TOKEN.equals(getIntent().getStringExtra(EXTRA_MODE))) return;
        mode = MODE_CLOUD_TOKEN;
        targetGame = getIntent().getStringExtra(EXTRA_CLOUD_GAME);
        targetUrl = PLATFORM_URL;
        pane.setPromptText(getString(R.string.cloud_game_fetch_prompt));
    }

    /** WebView 就绪后配置参数与客户端并发起首次加载。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView view) {
        webView = view;
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 云游戏页面的串流需要自动播放能力（无手势触发的媒体）
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
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
                if (MODE_CLOUD_TOKEN.equals(mode)) {
                    captureCloudGameToken(request);
                } else {
                    interceptGachaLink(view, request.getUrl().toString());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.loadUrl(targetUrl);
    }

    /** 抽卡模式：命中含 authkey 的抽卡记录请求即复制到剪贴板（只复制一次）。 */
    private void interceptGachaLink(WebView view, String url) {
        if (!url.contains("authkey=")) return;
        String[] gameHosts = {
                "public-operation-hk4e.",
                "public-operation-hkrpg.",
                "public-operation-nap.",
        };
        for (String host : gameHosts) {
            if (!url.contains(host)) continue;
            // linkCopied 仅在主线程读写（runOnUiThread 内部），无需额外同步
            runOnUiThread(() -> {
                if (linkCopied) return;
                linkCopied = true;
                copyToClipboard(view, CloudGachaActivity.this, url);
                showCustomSnackbar(view, CloudGachaActivity.this, getString(R.string.snack_link_copied));
            });
            return;
        }
    }

    /**
     * 抓 Token 模式：从请求头取 {@code x-rpc-combo_token}。
     *
     * <p>云游戏登录成功后必然请求自家的 wallet 接口，token 就在这次请求的**请求头**里
     * —— 这正是上游文档要求「登录后在 DevTools 里翻 Request Headers」的原因。
     * 本方法在 {@code shouldInterceptRequest}（IO 线程）中执行，只做解析与去重，
     * 落库与提示切回主线程。
     */
    private void captureCloudGameToken(WebResourceRequest request) {
        String host = request.getUrl().getHost();
        if (host == null) return;
        String game;
        boolean oversea = host.contains("sg-cg-api");
        if (host.contains("cg-nap-api")) {
            game = CloudGameDaily.GAME_ZZZ;
        } else if (host.contains("api-cloudgame") || oversea) {
            game = CloudGameDaily.GAME_GENSHIN;
        } else {
            return; // 不是云游戏钱包域名
        }
        String token = headerValue(request, "x-rpc-combo_token");
        if (token == null || token.isEmpty()) return;
        final String capturedGame = game;
        final String capturedToken = token;
        final boolean capturedOversea = oversea;
        runOnUiThread(() -> saveCloudGameToken(capturedGame, capturedToken, capturedOversea));
    }

    /**
     * 大小写不敏感地取请求头值：WebView 给出的键名大小写不稳定，
     * 个别机型在请求头不可用时还会抛异常，故整体兜底。
     */
    private static String headerValue(WebResourceRequest request, String name) {
        try {
            for (java.util.Map.Entry<String, String> entry : request.getRequestHeaders().entrySet()) {
                if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
        } catch (RuntimeException ignored) {
            // 头信息不可用：忽略本次，页面还会再发 wallet 请求
        }
        return null;
    }

    /**
     * 按用户落库并提示。抓到哪款存哪款——用户可能两款都进入过，两份 token 一并留下；
     * 未指定目标游戏（统一入口的常态）时任意一款命中都回传 RESULT_OK 供设置页刷新进度。
     *
     * <p><b>先校验形态再落库</b>：未登录时页面照样请求钱包接口，请求头里是
     * {@code "undefined"} / {@code "null"} 这类由前端拼进去的占位值。若当成 token 保存，
     * 会立刻弹「已获取」（实际什么都没拿到），还会覆盖掉上次登录拿到的真 token。
     */
    private void saveCloudGameToken(String game, String token, boolean oversea) {
        if (!CloudGameDaily.isValidToken(token)) {
            Logger.w("忽略非法云游戏 token：" + game + "=" + CloudGameDaily.tokenPreview(token));
            if (!warnedInvalidToken) {
                warnedInvalidToken = true;
                showCustomSnackbar(pane, this, getString(R.string.cloud_game_login_required));
            }
            return;
        }
        if (!capturedTokens.add(game + "|" + token)) return;
        String userId = new UserManager(this).getCurrentUser();
        if (userId == null || userId.isEmpty()) return;
        tools.write(this, userId,
                CloudGameDaily.tokenKey(game, oversea), token);
        String displayName = CloudGameDaily.displayName(this, game);
        showCustomSnackbar(pane, this, getString(R.string.cloud_game_token_captured, displayName));
        if (targetGame == null || game.equals(targetGame)) setResult(RESULT_OK);
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
        webView.loadUrl(targetUrl);
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
