package com.muxiao.Venus.User;

import dagger.hilt.android.AndroidEntryPoint;

import com.muxiao.Venus.BaseActivity;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.tools;

import java.util.Objects;

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
    private View oauth_webview_card;
    private ProgressBar oauth_webview_progress;
    private CountDownTimer cookie_polling_timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsFragment.applyAppTheme(this);
        setContentView(R.layout.activity_oauth_login);
        EdgeToEdge.enable(this);

        user_manager = new UserManager(this);

        relogin_mode = getIntent().getBooleanExtra("RELOGIN_MODE", false);
        relogin_username = getIntent().getStringExtra("USERNAME");

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.oauth_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        status_notifier = new tools.StatusNotifier();

        oauth_webview_card = findViewById(R.id.oauth_webview_card);
        oauth_webview = findViewById(R.id.oauth_webview);
        oauth_webview_progress = findViewById(R.id.oauth_webview_progress);

        findViewById(R.id.btn_cookie_login).setOnClickListener(v -> startCookieCapture());
        oauth_webview_card.setVisibility(View.GONE);
    }

    // ==================== Cookie 登录 ====================

    @SuppressLint("SetJavaScriptEnabled")
    // 启动 Cookie 抓取：配置 WebView（强制 https、注入 UA）并清空旧 Cookie，随后加载登录页并开启轮询
    private void startCookieCapture() {
        changeButtonStatus(false);
        oauth_webview_card.setVisibility(View.VISIBLE);

        WebSettings settings = oauth_webview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // P1-6 安全：WebView 仅允许 https，禁止加载混合内容（http 子资源），避免明文流量被注入。
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
                oauth_webview_progress.setVisibility(View.GONE);
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
                    oauth_webview_card.setVisibility(View.GONE);
                    changeButtonStatus(true);
                    tools.show_error_dialog(OAuthLoginActivity.this, getString(R.string.cookie_login_failed));
                });
            }
        }.start();
    }

    // Cookie 就绪后的处理：重登录模式直接刷新凭证，否则弹出用户名输入对话框
    private void onCookieObtained(String cookieString) {
        if (cookieString == null || cookieString.isEmpty()) {
            runOnUiThread(() -> {
                oauth_webview_card.setVisibility(View.GONE);
                changeButtonStatus(true);
            });
            return;
        }

        runOnUiThread(() -> oauth_webview_card.setVisibility(View.GONE));

        if (relogin_mode && relogin_username != null) {
            // 经仓储清除缓存 + 旧 SP + DataStore 三处，避免只清旧 SP 时读取仍命中旧凭证
            new com.muxiao.Venus.common.data.UserRepository(this).clear(relogin_username);
            saveCookieUser(relogin_username, cookieString);
            runOnUiThread(() -> {
                user_manager.setCurrentUser(relogin_username);
                changeButtonStatus(true);
            });
        } else {
            runOnUiThread(() -> showUsernameDialog(cookieString));
        }
    }

    // 弹出对话框让用户输入用户名，校验唯一性后保存 Cookie 用户
    private void showUsernameDialog(String cookieString) {
        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint(getString(R.string.username_hint));
        TextInputEditText input = new TextInputEditText(this);
        inputLayout.addView(input);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        inputLayout.setPadding(padding, padding, padding, 0);

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.oauth_login_title))
                .setMessage(getString(R.string.oauth_set_username))
                .setView(inputLayout)
                .setPositiveButton(getString(R.string.login), null)
                .setNegativeButton(getString(R.string.cancel_task), null)
                .setCancelable(false)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String username = Objects.requireNonNull(input.getText()).toString().trim();
            if (username.isEmpty()) {
                input.setError(getString(R.string.username_hint));
                return;
            }
            if (user_manager.getUsers().containsKey(username)) {
                input.setError(getString(R.string.snack_username_exists));
                return;
            }
            dialog.dismiss();
            saveCookieUser(username, cookieString);
            runOnUiThread(() -> {
                user_manager.addUser(username);
                user_manager.setCurrentUser(username);
                changeButtonStatus(true);
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

    // 启用/禁用 Cookie 登录按钮，防止重复触发
    private void changeButtonStatus(boolean enabled) {
        findViewById(R.id.btn_cookie_login).setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cookie_polling_timer != null)
            cookie_polling_timer.cancel();
        if (oauth_webview != null)
            oauth_webview.destroy();
        if (status_notifier != null)
            status_notifier.removeAllListeners();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        tools.hideKeyboardOnTouchOutside(this, event);
        return super.dispatchTouchEvent(event);
    }
}
