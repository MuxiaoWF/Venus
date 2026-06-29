package com.muxiao.Venus.User;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
public class OAuthLoginActivity extends AppCompatActivity {

    private static final String HOYOLAB_URL = "https://act.hoyolab.com/bbs/event/signin/hkrpg/index.html?act_id=e202303301540311";

    private tools.StatusNotifier status_notifier;
    private UserManager user_manager;
    private boolean relogin_mode;
    private String relogin_username;

    private WebView oauth_webview;
    private FrameLayout oauth_webview_container;
    private ProgressBar oauth_webview_progress;
    private CountDownTimer cookie_polling_timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int selectedTheme = SettingsFragment.getSelectedTheme(this);
        setTheme(selectedTheme);
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

        oauth_webview_container = findViewById(R.id.oauth_webview_container);
        oauth_webview = findViewById(R.id.oauth_webview);
        oauth_webview_progress = findViewById(R.id.oauth_webview_progress);

        findViewById(R.id.btn_cookie_login).setOnClickListener(v -> startCookieCapture());
        oauth_webview_container.setVisibility(View.GONE);
    }

    // ==================== Cookie 登录 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private void startCookieCapture() {
        changeButtonStatus(false);
        oauth_webview_container.setVisibility(View.VISIBLE);

        WebSettings settings = oauth_webview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
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
                    oauth_webview_container.setVisibility(View.GONE);
                    changeButtonStatus(true);
                    tools.show_error_dialog(OAuthLoginActivity.this, getString(R.string.cookie_login_failed));
                });
            }
        }.start();
    }

    private void onCookieObtained(String cookieString) {
        if (cookieString == null || cookieString.isEmpty()) {
            runOnUiThread(() -> {
                oauth_webview_container.setVisibility(View.GONE);
                changeButtonStatus(true);
            });
            return;
        }

        runOnUiThread(() -> oauth_webview_container.setVisibility(View.GONE));

        if (relogin_mode && relogin_username != null) {
            getSharedPreferences("user_" + relogin_username, MODE_PRIVATE).edit().clear().apply();
            saveCookieUser(relogin_username, cookieString);
            runOnUiThread(() -> {
                user_manager.setCurrentUser(relogin_username);
                changeButtonStatus(true);
            });
        } else {
            runOnUiThread(() -> showUsernameDialog(cookieString));
        }
    }

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

    private String getCookieValue(String cookieString, String name) {
        if (cookieString == null) return null;
        for (String cookie : cookieString.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(name))
                return parts[1].trim();
        }
        return null;
    }

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
