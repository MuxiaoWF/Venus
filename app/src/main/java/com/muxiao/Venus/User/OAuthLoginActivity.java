package com.muxiao.Venus.User;

import com.muxiao.Venus.common.BaseActivity;
import dagger.hilt.android.AndroidEntryPoint;


import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.EdgeToEdge;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.tools;


/**
 * 国际服用户登录：通过WebView访问HoYoLAB，自动轮询Cookie获取登录凭证。
 * 支持Cookie登录和重新登录两种模式。
 */
@AndroidEntryPoint
public class OAuthLoginActivity extends BaseActivity {

    private static final String HOYOLAB_URL = "https://act.hoyolab.com/bbs/event/signin/hkrpg/index.html?act_id=e202303301540311";

    private tools.StatusNotifier status_notifier;
    private UserManager user_manager;
    private boolean relogin_mode;
    private String relogin_username;

    private WebView oauth_webview;
    /** Compose 版页面宿主：工具栏 / Cookie 卡 / WebView 容器与进度都由它绘制。 */
    private com.muxiao.Venus.common.ui.compose.OauthLoginScreenView pane;
    private CountDownTimer cookie_polling_timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsFragment.applyAppTheme(this);
        pane = new com.muxiao.Venus.common.ui.compose.OauthLoginScreenView(this);
        setContentView(pane);
        EdgeToEdge.enable(this);

        user_manager = new UserManager(this);

        relogin_mode = getIntent().getBooleanExtra("RELOGIN_MODE", false);
        relogin_username = getIntent().getStringExtra("USERNAME");

        // 状态栏内边距由 Compose 的 statusBarsPadding 处理（EdgeToEdge 已让出窗口 inset）
        pane.setOnBackClickListener(this::finish);
        status_notifier = new tools.StatusNotifier();

        // WebView 由 Compose 容器创建后才存在：就绪即交给 startCookieCapture 配置并加载
        pane.setOnWebViewCreatedListener(this::startCookieCapture);
    }

    // ==================== Cookie 登录 ====================

    // 启动 Cookie 抓取：配置 WebView（强制 https、注入 UA）并清空旧 Cookie，随后加载登录页并开启轮询。
    // 按钮禁用 / 卡片展开 / 进度条由 Compose 宿主在点击时完成，这里只做 WebView 自身的配置。
    @SuppressLint("SetJavaScriptEnabled")
    private void startCookieCapture(WebView webView) {
        oauth_webview = webView;
        WebSettings settings = oauth_webview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 安全：WebView 仅允许 https，禁止加载混合内容（http 子资源），避免明文流量被注入。
        // 如真机验证发现 HoYoLAB 登录页依赖混合内容导致加载异常，可降级为 MIXED_CONTENT_COMPATIBILITY。
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(oauth_webview, true);

        oauth_webview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pane.setLoading(false);
            }
        });

        // 清除所有cookie后再加载页面，确保显示登录界面
        cookieManager.removeAllCookies(success -> runOnUiThread(() -> {
            oauth_webview.loadUrl(HOYOLAB_URL);
            startCookiePolling();
        }));
    }

    // 开启 2 分钟倒计时轮询，每 2 秒检测 Cookie 是否出现登录凭证，命中即停止轮询
    private void startCookiePolling() {
        cookie_polling_timer = new CountDownTimer(120000, 2000) {
            @Override
            public void onTick(long millisUntilFinished) {
                String allCookies = CookieManager.getInstance().getCookie(HOYOLAB_URL);
                if (allCookies != null && (allCookies.contains("ltoken=") || allCookies.contains("ltoken_v2=") || allCookies.contains("cookie_token=") || allCookies.contains("account_id="))) {
                    cancel();
                    onCookieObtained(allCookies);
                }
            }

            @Override
            public void onFinish() {
                runOnUiThread(() -> {
                    pane.resetAfterCapture();
                    tools.show_error_dialog(OAuthLoginActivity.this, getString(R.string.cookie_login_failed));
                });
            }
        }.start();
    }

    // Cookie 就绪后的处理：重登录模式直接刷新凭证，否则弹出用户名输入对话框
    private void onCookieObtained(String cookieString) {
        if (cookieString == null || cookieString.isEmpty()) {
            runOnUiThread(() -> pane.resetAfterCapture());
            return;
        }

        runOnUiThread(() -> pane.setWebViewVisible(false));

        if (relogin_mode && relogin_username != null) {
            // 经仓储清除缓存 + 旧 SP + DataStore 三处，避免只清旧 SP 时读取仍命中旧凭证
            new com.muxiao.Venus.common.data.UserRepository(this).clear(relogin_username);
            saveCookieUser(relogin_username, cookieString);
            runOnUiThread(() -> {
                user_manager.setCurrentUser(relogin_username);
                pane.setCookieButtonEnabled(true);
            });
        } else {
            runOnUiThread(() -> showUsernameDialog(cookieString));
        }
    }

    // 弹出对话框让用户输入用户名，校验唯一性后保存 Cookie 用户
    // （Compose 版输入框：校验不通过就地显示错误并保持对话框打开，与原 input.setError 等价）
    private void showUsernameDialog(String cookieString) {
        com.muxiao.Venus.common.ui.compose.VenusDialogs.input(
                this,
                getString(R.string.oauth_login_title),
                getString(R.string.oauth_set_username),
                null,
                getString(R.string.username_hint),
                getString(R.string.login),
                getString(R.string.cancel_task),
                false,
                username -> {
                    if (username.isEmpty()) {
                        return getString(R.string.username_hint);
                    }
                    if (user_manager.getUsers().containsKey(username)) {
                        return getString(R.string.snack_username_exists);
                    }
                    return null;
                },
                username -> {
                    saveCookieUser(username, cookieString);
                    runOnUiThread(() -> {
                        user_manager.addUser(username);
                        user_manager.setCurrentUser(username);
                        changeButtonStatus();
                    });
                });
    }

    // 持久化完整 Cookie 字符串，并提取 ltoken/mid/stuid/cookie_token 单独存储
    private void saveCookieUser(String username, String cookieString) {
        tools.write(this, username, "cookie", cookieString);
        tools.write(this, username, "server_type", "1");

        // 从cookie中提取单独的token供每日任务使用
        String ltoken = getCookieValue(cookieString, "ltoken");
        if (ltoken == null) ltoken = getCookieValue(cookieString, "ltoken_v2");
        if (ltoken != null) tools.write(this, username, "ltoken", ltoken);

        String mid = getCookieValue(cookieString, "mid");
        if (mid != null) tools.write(this, username, "mid", mid);

        String stuid = getCookieValue(cookieString, "account_id");
        if (stuid != null) tools.write(this, username, "stuid", stuid);

        String cookieToken = getCookieValue(cookieString, "cookie_token");
        if (cookieToken != null) tools.write(this, username, "cookie_token", cookieToken);
    }

    // 从 "; " 分隔的 Cookie 串中按名称提取值，未找到返回 null
    private String getCookieValue(String cookieString, String name) {
        if (cookieString == null) return null;
        for (String cookie : cookieString.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(name))
                return parts[1].trim();
        }
        return null;
    }

    // 登录落定后重新启用 Cookie 登录按钮（按钮在 Compose 侧，走宿主状态）
    private void changeButtonStatus() {
        pane.setCookieButtonEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cookie_polling_timer != null)
            cookie_polling_timer.cancel();
        // WebView 的销毁交给 Compose 的 AndroidView.onRelease（离开组合树时触发），
        // 这里只清引用，避免重复 destroy
        oauth_webview = null;
        if (status_notifier != null)
            status_notifier.removeAllListeners();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
