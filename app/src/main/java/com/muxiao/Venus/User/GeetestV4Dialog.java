package com.muxiao.Venus.User;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muxiao.Venus.common.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极验第四代（v4）验证宿主：用 WebView 加载极验官方 {@code gt4.js} 完成验证。
 * <p>
 * 为什么不用原生 SDK：v4 的校验结果必须由官方 v4 SDK 向极验服务端申请，而 v4 的 Android
 * AAR 无公开 Maven 坐标、需随包分发且与极验侧版本强绑定。WebView 加载的是与 TeyvatGuide
 * 完全同一份 {@code https://static.geetest.com/v4/gt4.js}，指纹形态与 PC 端一致，
 * 升级只需换 HTML，不动构建。
 * <p>
 * 与 v3（GT3，见 {@code UserLoginActivity#solveAigis}）的三处关键差异：
 * <ol>
 *   <li>参数：v4 只给 {@code captchaId}（即 aigis.data.gt）与 {@code riskType}，无 challenge；
 *       且必须把 {@code session_id} 放进 {@code userInfo}，否则验证结果无法与本次米游社
 *       风控会话关联，重提必然失败；</li>
 *   <li>取值时机：结果在 {@code onSuccess} 里通过 {@code getValidate()} 取，v3 是在 {@code onClose}；</li>
 *   <li>结果形态：返回极验原生 validate JSON（lot_number / captcha_output / pass_token /
 *       gen_time / captcha_id / sign_token），整体 base64 后与 session_id 拼成 {@code x-rpc-aigis}。</li>
 * </ol>
 * 必须在主线程调用。
 */
public final class GeetestV4Dialog {

    /** 验证结果回调，只回调一次；{@code null} 表示用户关闭、加载失败或超时 */
    interface Callback {
        void onFinished(@Nullable String validateJson);
    }

    /**
     * 页面基址。用 {@code loadDataWithBaseURL} 把页面挂在极验域名下，
     * 使文档 origin 为 https（而非 file:// 的 null origin）：
     * gt4.js 及其后续请求都被视作同源，既避开 CORS 差异，也保证 localStorage 可用。
     */
    private static final String BASE_URL = "https://static.geetest.com/";

    /** 本地验证页，须与 assets 中文件名一致 */
    private static final String PAGE_ASSET = "geetest_v4.html";

    /** 允许加载的远端域名后缀，其余一律拦截（页面只依赖极验静态资源） */
    private static final String[] ALLOWED_HOST_SUFFIXES = {".geetest.com", "geetest.com"};

    /**
     * 常规 Chrome Android UA。刻意不带 {@code "; wv"} —— 部分风控会直接拒绝 WebView
     * 默认 UA，而极验只看行为特征，用普通浏览器 UA 更稳。
     */
    private static final String WEB_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Gson GSON = new Gson();

    private final Activity activity;
    private final PasswordLogin.AigisInfo info;
    private final Callback callback;
    /** 单次验证是否已有结论：保证 onSuccess / onClose / 超时只走一条 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private Dialog dialog;
    private WebView webView;

    private GeetestV4Dialog(Activity activity, PasswordLogin.AigisInfo info, Callback callback) {
        this.activity = activity;
        this.info = info;
        this.callback = callback;
    }

    /**
     * 弹出验证页。调用方拿到返回的 Dialog 后应在自身超时分支里 dismiss，
     * 避免用户长时间不操作时把 WebView 留在后台。
     *
     * @return 已显示的 Dialog；宿主 Activity 已不可用时返回 null
     */
    @Nullable
    static Dialog show(Activity activity, PasswordLogin.AigisInfo info, Callback callback) {
        // isFinishing / isDestroyed 任一为真时 show() 会抛 BadTokenException
        if (activity == null || info == null
                || activity.isFinishing() || activity.isDestroyed())
            return null;
        return new GeetestV4Dialog(activity, info, callback).present();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private Dialog present() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 点击遮罩不关闭：验证中途意外退出会让本次风控会话作废
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(true);

        webView = new WebView(activity);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(WEB_UA);
        // 页面只加载 https 静态资源，禁止 http 子资源混入
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 页面由 loadDataWithBaseURL 提供，不需要任何本地文件访问能力
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        FrameLayout root = new FrameLayout(activity);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);
        dialog.setContentView(root);

        webView.addJavascriptInterface(new Bridge(), "VenusBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl() == null)
                    return true;
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String suffix : ALLOWED_HOST_SUFFIXES) {
                        if (host.endsWith(suffix))
                            return false;
                    }
                }
                // 其余跳转（含误触广告）在当前页拦下，不放 WebView 出去
                Logger.debug("VenusGeetestV4", "blocked navigation to " + request.getUrl());
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                // 主文档加载失败立即收尾，由上层给出「改用其它登录方式」的提示
                if (request.isForMainFrame())
                    conclude(null, "page-load-error");
            }
        });

        // 用户按返回键 / 点外部关闭：兜底「没拿到结果就消失」，避免后台线程空等
        dialog.setOnDismissListener(d -> conclude(null, "dismiss-without-result"));

        String page = readPage();
        if (page == null) {
            // assets 缺失属于打包问题，直接收尾并留日志，不让用户对着空白页等 5 分钟
            conclude(null, "page-asset-missing");
            return dialog;
        }

        try {
            dialog.show();
        } catch (RuntimeException e) {
            // 宿主在判断与 show 之间被回收（极窄竞态）：直接收尾，别让后台线程干等
            conclude(null, "show-failed:" + e.getClass().getSimpleName());
            return dialog;
        }
        // window 只在 show() 之后才存在，全屏与遮罩必须在这里设置
        Window window = dialog.getWindow();
        if (window != null) {
            // 半透明黑遮罩，极验弹层浮在中间，观感与 GT3 弹窗的遮罩一致
            window.setBackgroundDrawable(new ColorDrawable(0x99000000));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
        }
        webView.loadDataWithBaseURL(BASE_URL, page, "text/html", "UTF-8", null);
        return dialog;
    }

    /** 读取 assets 中的验证页；失败返回 null（由调用方走失败分支） */
    @Nullable
    private String readPage() {
        try (java.io.InputStream in = activity.getAssets().open(PAGE_ASSET)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            Logger.debug("VenusGeetestV4", "read asset failed: " + e);
            return null;
        }
    }

    /**
     * 结束一次验证：写入结论并关闭弹窗。多线程可达（JS 桥在 WebView 线程池、主线程回调），
     * 用 CAS 保证只执行一次。
     */
    private void conclude(@Nullable String validateJson, String reason) {
        if (!finished.compareAndSet(false, true))
            return;
        if (validateJson == null)
            Logger.debug("VenusGeetestV4", "verification aborted: " + reason);

        // 先摘掉监听，避免下面 dismiss 时再次进入本方法
        dialog.setOnDismissListener(null);
        try {
            if (dialog.isShowing())
                dialog.dismiss();
        } catch (IllegalArgumentException ignored) {
            // Activity 已销毁，忽略
        }
        destroyWebView();
        callback.onFinished(validateJson);
    }

    private void destroyWebView() {
        if (webView == null)
            return;
        try {
            webView.removeJavascriptInterface("VenusBridge");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (RuntimeException ignored) {
            // WebView 已随 Activity 销毁，忽略
        } finally {
            webView = null;
        }
    }

    /**
     * JS 桥：验证页通过 {@code window.VenusBridge} 回报加载与验证进度。
     * <p>
     * 必须是 {@code public}（连同外层类）：WebView 通过反射调用带
     * {@link JavascriptInterface} 的方法，且内部不做 setAccessible，
     * 因此类本身不可访问时会直接抛 IllegalAccessException —— 桥静默失效。
     */
    public final class Bridge {

        /** gt4.js 加载完成：把米游社下发的参数注入页面并启动验证 */
        @JavascriptInterface
        public void onReady() {
            JsonObject config = new JsonObject();
            config.addProperty("captchaId", info.gt);
            config.addProperty("riskType", info.riskType);
            config.addProperty("sessionId", info.sessionId);
            // GSON.toJson(String) 产出带引号且已转义的 JS 字符串字面量，避免手拼引号出错
            final String argument = GSON.toJson(config.toString());
            activity.runOnUiThread(() -> {
                if (finished.get() || webView == null)
                    return;
                webView.evaluateJavascript("window.venusStart(" + argument + ");", null);
            });
        }

        /** 验证通过：validateJson 是极验原生结果 JSON 字符串 */
        @JavascriptInterface
        public void onSuccess(String validateJson) {
            activity.runOnUiThread(() -> conclude(validateJson, "success"));
        }

        /** 用户关闭 / SDK 异常 / 加载失败 */
        @JavascriptInterface
        public void onClose(String reason) {
            activity.runOnUiThread(() -> conclude(null, reason));
        }
    }
}
