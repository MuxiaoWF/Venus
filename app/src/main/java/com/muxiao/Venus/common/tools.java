package com.muxiao.Venus.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.ui.LensGlassDrawable;
import com.muxiao.Venus.VenusApplication;
import com.muxiao.Venus.common.data.UserRepository;

import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.content.ContentValues;

import androidx.fragment.app.Fragment;

import com.google.android.material.transition.MaterialSharedAxis;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLException;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.GzipSource;
import okio.Okio;

/**
 * 通用工具类：HTTP 请求（OkHttp，自动gzip解压）、SharedPreferences 读写、
 * Snackbar/错误对话框、剪贴板、日志文件读写、键盘收起。
 */
public class tools {
    // 共享 OkHttpClient 实例已集中到 ApiClient，此处 getSharedClient() 转发到 ApiClient.get()

    /**
     * 返回应用共享的 OkHttpClient（转发到 {@link ApiClient#get()}，复用连接池）。
     */
    private static OkHttpClient getSharedClient() {
        return ApiClient.get();
    }

    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    /**
     * 登录状态变化监听器，回调最新登录状态文案。
     */
    public interface StatusInterface {
        void onLoginStatusChanged(String status);
    }

    /**
     * 登录状态广播器：用 CopyOnWriteArrayList 维护监听器，支持跨线程安全增删与通知。
     */
    public static class StatusNotifier {
        private final CopyOnWriteArrayList<StatusInterface> listeners = new CopyOnWriteArrayList<>();

        /** 注册一个登录状态监听器。 */
        public void addListener(StatusInterface listener) {
            listeners.add(listener);
        }

        /** 清空所有已注册监听器（如 Activity 销毁时调用，避免泄漏）。 */
        public void removeAllListeners() {
            listeners.clear();
        }

        /** 向所有监听器广播最新的登录状态变化。 */
        public void notifyListeners(String status) {
            for (StatusInterface listener : listeners)
                listener.onLoginStatusChanged(status);
        }
    }

    private static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

    /**
     * 统一网络异常出口（所有请求方法共用）：按异常类型映射为用户可理解的提示，
     * 全部走 strings.xml 本地化（经全局 Application Context，跟随应用语言设置）。
     * <ul>
     *   <li>已是业务异常（如「请求失败，状态码：403」）→ 原样抛出，避免嵌套文案；</li>
     *   <li>无法解析主机（DNS 失败/断网）→ 提示无法解析服务器地址；</li>
     *   <li>连接超时 / 连接被拒 / 安全连接失败 / 连接中断 → 分别给出对应提示；</li>
     *   <li>其余（解析、非 HTTP 的 IO 等）→ 统一包装为「请求失败：…」。</li>
     * </ul>
     * 各类异常均保留 cause 供日志与「复制错误信息」使用。
     */
    private static RuntimeException normalizeRequestError(Exception e) {
        if (e instanceof RuntimeException)
            return (RuntimeException) e;
        if (e instanceof UnknownHostException)
            return new RuntimeException(str("无法解析服务器地址：%s，请检查网络连接", R.string.err_network_host_unresolved, describeError(e)), e);
        if (e instanceof SocketTimeoutException)
            return new RuntimeException(str("网络请求超时，请检查网络后重试", R.string.err_network_timeout), e);
        if (e instanceof ConnectException)
            return new RuntimeException(str("无法连接到服务器，请检查网络连接", R.string.err_network_connect_failed), e);
        if (e instanceof SSLException)
            return new RuntimeException(str("安全连接失败，请检查系统时间或网络环境", R.string.err_network_ssl_failed), e);
        if (e instanceof IOException)
            return new RuntimeException(str("网络连接中断，请检查网络后重试", R.string.err_network_interrupted), e);
        return new RuntimeException(str("请求失败：%s", R.string.err_request_failed_generic, describeError(e)), e);
    }

    /**
     * 底层工具类的本地化文案入口：经全局 Application Context 读取 strings.xml
     * （Application 在 attachBaseContext 已按用户语言包装，故返回语言正确的文案）。
     * 仅在 Application 尚未创建（理论上不会发生）时退回中文兜底串，保证不因提示文案而崩溃。
     *
     * @param fallback 兜底文案（与 values/strings.xml 的默认文案保持一致）
     * @param resId    字符串资源 id
     * @param args     格式化参数
     */
    private static String str(String fallback, int resId, Object... args) {
        Context app = VenusApplication.getAppContext();
        if (app == null)
            return args.length == 0 ? fallback : String.format(Locale.ROOT, fallback, args);
        return app.getString(resId, args);
    }

    /**
     * 异常原因兜底：getMessage() 可能为 null（如部分 IO/NPE 异常），回退为类名，
     * 避免弹窗出现「请求失败：null」「加载失败: null」之类残缺提示。
     * 供各处把异常消息拼进用户可见文案时统一调用。
     */
    public static String describeError(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** 统一的 Header 写入：跳过 null 键值（OkHttp 对 null 名/值会抛 NPE）。 */
    private static void applyHeaders(Request.Builder builder, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            builder.addHeader(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 拼接 GET 查询串：走 OkHttp HttpUrl 构建器，保证中文/空格/&/= 等字符被正确百分号编码。
     * 原实现为字符串手工拼接，参数含特殊字符时会截断 query 或串参数。
     */
    private static String buildGetUrl(String urlStr, Map<String, String> params) {
        if (params == null || params.isEmpty()) return urlStr;
        HttpUrl base = HttpUrl.parse(urlStr);
        if (base == null)
            throw new RuntimeException(str("非法的请求地址：%s", R.string.err_invalid_url, urlStr));
        HttpUrl.Builder builder = base.newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null) continue;
            builder.addQueryParameter(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
        }
        return builder.build().toString();
    }

    /**
     * 发送 GET 请求：params 安全编码后拼接到 query，自动 gzip 解压；非 2xx 或异常统一抛 RuntimeException。
     */
    public static String sendGetRequest(String urlStr, Map<String, String> headers, Map<String, String> params) {
        return sendGetRequest(urlStr, headers, params, false);
    }

    /**
     * 同上，fastTimeout=true 时改用 {@link ApiClient#getFast()} 短超时客户端（connect/read 各 4s）。
     * 用于「主源 + 兜底源」拉取链路：主源不可达（如 GitHub raw 被墙）时快速失败让位兜底源。
     */
    public static String sendGetRequest(String urlStr, Map<String, String> headers, Map<String, String> params,
                                        boolean fastTimeout) {
        Request.Builder requestBuilder = new Request.Builder().url(buildGetUrl(urlStr, params)).get();
        applyHeaders(requestBuilder, headers);
        try (Response response = (fastTimeout ? ApiClient.getFast() : getSharedClient())
                .newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new HttpStatusCodeException(response.code(), str("请求失败，状态码：%d", R.string.err_http_status_code, response.code()));
            return readResponseBody(response);
        } catch (Exception e) {
            throw normalizeRequestError(e);
        }
    }

    /**
     * 以 JSON body 发送 POST 请求，自动 gzip 解压；失败抛 RuntimeException（含联网/错误提示）。
     */
    public static String sendPostRequest(String urlStr, Map<String, String> headers, Map<String, Object> body) {
        RequestBody requestBody;
        if (body != null) {
            String jsonBody = GSON.toJson(body);
            requestBody = RequestBody.create(jsonBody, MediaType.parse(JSON_MEDIA_TYPE));
        } else {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Request.Builder requestBuilder = new Request.Builder().url(urlStr).post(requestBody);
        applyHeaders(requestBuilder, headers);
        try (Response response = getSharedClient().newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new HttpStatusCodeException(response.code(), str("请求失败，状态码：%d", R.string.err_http_status_code, response.code()));
            return readResponseBody(response);
        } catch (Exception e) {
            throw normalizeRequestError(e);
        }
    }

    /**
     * HTTP 响应包装：同时携带状态码、响应体与响应头。
     * 用于需要读取响应头的接口（如 loginByPassword 的 x-rpc-aigis）。
     */
    public static class HttpResponse {
        public final String body;
        private final okhttp3.Headers headers;

        HttpResponse(String body, okhttp3.Headers headers) {
            this.body = body;
            this.headers = headers;
        }

        /** 按名称读取响应头，headers 为 null 或查询不到时返回 null。 */
        public String header(String name) {
            return headers != null ? headers.get(name) : null;
        }
    }

    /**
     * 以原始 JSON 字符串作为请求体发送 POST，并返回状态码/响应体/响应头。
     * <p>
     * 与 {@link #sendPostRequest} 的区别：调用方自行完成 JSON 序列化，
     * 保证「参与 DS 签名的 body」与「实际发出的 body」逐字节一致（DS2 签名必需），
     * 且非 2xx 不抛异常，由调用方根据 retcode 处理。
     *
     * @param jsonBody 已序列化好的 JSON 字符串，为 null 时发送空体
     */
    public static HttpResponse postJson(String urlStr, Map<String, String> headers, String jsonBody) {
        RequestBody requestBody = jsonBody != null
                ? RequestBody.create(jsonBody, MediaType.parse(JSON_MEDIA_TYPE))
                : RequestBody.create(new byte[0], null);
        Request.Builder requestBuilder = new Request.Builder().url(urlStr).post(requestBody);
        applyHeaders(requestBuilder, headers);
        try (Response response = getSharedClient().newCall(requestBuilder.build()).execute()) {
            return new HttpResponse(readResponseBody(response), response.headers());
        } catch (Exception e) {
            throw normalizeRequestError(e);
        }
    }

    /**
     * 读取响应体：Content-Encoding=gzip 时按 GzipSource 解压，否则直接读为字符串。
     */
    private static String readResponseBody(Response response) throws IOException {
        ResponseBody responseBody = response.body();
        if ("gzip".equals(response.header("Content-Encoding"))) {
            try (GzipSource gzipSource = new GzipSource(responseBody.source());
                 okio.BufferedSource bufferedSource = Okio.buffer(gzipSource)) {
                return bufferedSource.readUtf8();
            }
        }
        return responseBody.string();
    }

    /**
     * 将用户数据写入SharedPreferences（经 UserRepository 单一入口）
     *
     * @param userId 用户标识
     * @param key    数据键名
     * @param value  数据值
     */
    public static void write(Context context, String userId, String key, String value) {
        new UserRepository(context).putString(userId, key, value);
    }

    /**
     * 从SharedPreferences中读取用户数据（经 UserRepository 单一入口）
     *
     * @param userId 用户标识
     * @param key    数据键名
     * @return 用户数据
     */
    public static String read(Context context, String userId, String key) {
        return new UserRepository(context).getString(userId, key);
    }

    /**
     * 显示居中、带 M3 主题配色与内边距的自定义 Snackbar（短时长自动消失）。
     *
     * <p>配色（表面色 / 文本 / 动作按钮）与圆角全部由主题的
     * {@code snackbarStyle} / {@code snackbarTextViewStyle} / {@code snackbarButtonStyle}
     * 承载（见 Theme.Venus 与 Widget.Venus.Snackbar*），本方法只保留
     * 居中、留白、短时长这类"布局行为"，不再逐层覆盖 hardcode 颜色。
     */
    public static void showCustomSnackbar(View view, Context context, String message) {
        Snackbar snackbar = Snackbar.make(view, message, Snackbar.LENGTH_SHORT);
        View snackbarView = snackbar.getView();

        // 文本居中 + 最多三行（颜色/字号走 snackbarTextViewStyle）
        MaterialTextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (textView != null) {
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setMaxLines(3);
        }

        // 阴影和内边距
        snackbarView.setElevation(4f);
        float density = context.getResources().getDisplayMetrics().density;
        int h = (int) (12 * density);
        int v = (int) (6 * density);
        snackbarView.setPadding(h, v, h, v);

        // 居中显示，留出底部间距
        android.view.ViewGroup.LayoutParams lp = snackbarView.getLayoutParams();
        lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
            flp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
            flp.bottomMargin = (int) (64 * density);
        }
        snackbarView.setLayoutParams(lp);

        snackbar.show();
    }

    /**
     * 弹出错误对话框，并提供「复制错误」按钮；复制成功后提示已复制。
     *
     * @param error_message 错误信息
     */
    public static void show_error_dialog(Context context, String error_message) {
        show_error_dialog(context, context.getString(R.string.dialog_error), error_message);
    }

    /**
     * 弹出指定标题的错误对话框，并提供「复制错误」按钮。
     *
     * @param title         对话框标题（如通用「错误」或「检查更新失败」等场景化标题）
     * @param error_message 错误信息
     */
    public static void show_error_dialog(Context context, String title, String error_message) {
        // 直接使用调用方的 Activity 上下文：与全应用其它 MaterialAlertDialogBuilder 用法一致，
        // 保留主题（含动态取色）的对话框样式。此前的 ContextThemeWrapper 强制套用
        // Material3_DayNight_NoActionBar，会丢掉动态取色与品牌对话框样式。
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(error_message)
                .setPositiveButton(context.getString(R.string.btn_copy_error), (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(context.getString(R.string.error_label), error_message);
                    clipboard.setPrimaryClip(clip);
                    if (context instanceof Activity) {
                        View rootView = ((Activity) context).findViewById(android.R.id.content);
                        showCustomSnackbar(rootView, context, context.getString(R.string.snack_error_copied));
                    }
                })
                .setNegativeButton(context.getString(R.string.btn_close), null)
                .show();
    }

    /**
     * 弹出提示（警告）对话框：标题「提示」，仅确认按钮，不带「复制错误」。
     * 用于需要用户知晓但并非故障的引导/约束信息（如「请先设置任务」「请先选择用户」），
     * 与 {@link #show_error_dialog}（真正的故障，含复制排障入口）区分开。
     *
     * @param message 提示信息
     */
    public static void show_warning_dialog(Context context, String message) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dialog_hint))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.btn_close), null)
                .show();
    }

    /**
     * 复制文本到系统剪贴板，并弹出「已复制」Snackbar 提示。
     */
    public static void copyToClipboard(View view, Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copy Venus", text);
        clipboard.setPrimaryClip(clip);
        showCustomSnackbar(view, context, context.getString(R.string.snack_link_copied));
    }

    /**
     * 将 sourceUri 指向的文件流拷贝到 destFile（8KB 缓冲，源不可打开时抛异常）。
     *
     * @param sourceUri 源文件 URI
     * @param destFile  目标文件
     */
    public static void copyFile(Context context, Uri sourceUri, File destFile) throws Exception {
        try (InputStream inputStream = context.getContentResolver().openInputStream(sourceUri);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            if (inputStream == null)
                throw new Exception(context.getString(R.string.error_cannot_open_source));
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) > 0)
                outputStream.write(buffer, 0, length);
            outputStream.flush();
        }
    }

    /**
     * 在 Activity 的 dispatchTouchEvent 中调用，点击输入框外区域时自动收起键盘
     */
    public static void hideKeyboardOnTouchOutside(Activity activity, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return;
        View focused = activity.getCurrentFocus();
        if (focused instanceof EditText) {
            int[] location = new int[2];
            focused.getLocationOnScreen(location);
            int x = (int) event.getRawX();
            int y = (int) event.getRawY();
            if (x < location[0] || x > location[0] + focused.getWidth()
                    || y < location[1] || y > location[1] + focused.getHeight()) {
                focused.clearFocus();
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
    }

    /**
     * 运行日志目录：外置私有目录不可用（未挂载/被回收）时回退到内置 filesDir，
     * 避免 new File(null, "logs") 抛 NPE。写入与清理共用同一目录，保证二者一致。
     */
    private static File logDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), "logs");
    }

    /**
     * 清理过期的日志文件。
     * 运行日志只保留今天，任务历史日志保留今天和昨天。
     * 在任务开始时调用。
     */
    public static void cleanOldLogs(Context context) {
        // 清理运行日志（只保留今天）
        File logDir = logDir(context);
        if (logDir.exists()) {
            String todayPrefix = "daily_task_log_" + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().startsWith("daily_task_log_") && !file.getName().startsWith(todayPrefix)) {
                        file.delete();
                    }
                }
            }
        }

        // 清理任务历史日志（保留今天和昨天，因为跨天时 writeDailyLog 写入的是昨天的文件）
        File historyDir = new File(context.getFilesDir(), "task_history");
        if (historyDir.exists()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String todayLog = sdf.format(new Date()) + ".log";
            String yesterdayLog = sdf.format(new Date(System.currentTimeMillis() - 86400000L)) + ".log";
            File[] historyFiles = historyDir.listFiles((d, name) -> name.endsWith(".log"));
            if (historyFiles != null) {
                for (File file : historyFiles) {
                    if (!file.getName().equals(todayLog) && !file.getName().equals(yesterdayLog)) {
                        file.delete();
                    }
                }
            }
        }
    }

    /**
     * 静默写入日志文件，不触发通知。
     */
    public static void writeLog(Context context, String message) {
        try {
            File logFile = getTodayLogFile(context);
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append("[").append(timestamp).append("] ").append(message).append("\n");
            }
        } catch (IOException ignored) {}
    }

    /**
     * 向当天日志文件写入一条分隔符，用于区分不同次任务执行。
     */
    public static void writeLogSeparator(Context context) {
        try {
            File logFile = getTodayLogFile(context);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append("\n==================== ").append(timestamp).append(" ====================\n\n");
            }
        } catch (IOException ignored) {}
    }

    /**
     * 返回今天的运行日志文件（logs/daily_task_log_yyyy-MM-dd.txt），目录不存在时自动创建。
     */
    public static File getTodayLogFile(Context context) {
        File logDir = logDir(context);
        if (!logDir.exists()) logDir.mkdirs();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return new File(logDir, "daily_task_log_" + date + ".txt");
    }

    // ========== 新增公共工具方法 ==========

    /**
     * 读取用户 token 相关字段，构建认证 Cookie
     */
    public static String buildUserCookie(Context context, String userId) {
        String stoken = read(context, userId, "stoken");
        String ltoken = read(context, userId, "ltoken");
        String mid = read(context, userId, "mid");
        String stuid = read(context, userId, "stuid");
        if ((stoken == null && ltoken == null) || stuid == null) return null;
        String sessionToken = stoken != null ? stoken : ltoken;
        String tokenKey = stoken != null ? "stoken" : "ltoken";
        return tokenKey + "=" + sessionToken + (mid != null ? ";mid=" + mid : "") + ";stuid=" + stuid + ";ltuid=" + stuid + ";";
    }

    /**
     * 随机延时（毫秒），用于避免请求频率限制。
     * rangeMs ≤ 0 时退化为固定延时（原实现会在 rangeMs=0 时抛 IllegalArgumentException）。
     */
    public static void randomDelay(int minMs, int rangeMs) throws InterruptedException {
        int base = Math.max(0, minMs);
        if (rangeMs <= 0) {
            if (base > 0) Thread.sleep(base);
            return;
        }
        Thread.sleep(base + RANDOM.nextInt(rangeMs));
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * 计算字符串的 MD5 哈希（十六进制）
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            // 固定 UTF-8，避免依赖平台默认字符集导致同一输入在不同设备上得到不同摘要
            return bytesToHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 保存 Bitmap 到系统相册
     */
    public static void saveBitmapToGallery(Context context, Bitmap bitmap, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Venus");
            android.net.Uri insertedUri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (insertedUri == null) throw new IOException("MediaStore insert returned null");
            try (OutputStream fos = context.getContentResolver().openOutputStream(insertedUri)) {
                if (fos != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                    fos.flush();
                }
            }
        } else {
            File venusDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Venus");
            if (!venusDir.exists() && !venusDir.mkdirs())
                throw new IOException("无法创建保存目录：" + venusDir.getAbsolutePath());
            try (OutputStream fos = new FileOutputStream(new File(venusDir, fileName))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        }
    }

    /**
     * 系统「减少动态效果」检测：动画时长/转场缩放任一为 0 即视为关闭。
     * 所有自定义动效在开启时降级为无动画，尊重无障碍偏好。
     */
    public static boolean isReducedMotionEnabled(Context context) {
        try {
            float animatorScale = Settings.Global.getFloat(
                    context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            float transitionScale = Settings.Global.getFloat(
                    context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            return animatorScale == 0f || transitionScale == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 背景图玻璃感收口：设置了背景图片时，把布局中标记 android:tag="glass" 的视图
     * （液态玻璃 chip/表面行——自带 rim/高光半透明层）背景整体再降一档透明度，
     * 让背景图透出更多、玻璃感更强；无背景图时恢复 255 保持原生质感。
     * 各 Fragment 在 inflate 后调用一次。
     * 注意：① 同一 drawable 资源在各视图间共享 ConstantState，必须 mutate() 后再 setAlpha；
     * 液态玻璃 drawable 为多层 LayerDrawable，部分实现的整体 setAlpha 不向子层传播，
     * 故逐层施加保证 rim/填充/高光同步衰减。
     * ② 背景已被 {@link LensGlassDrawable} 接管的视图直接跳过——透镜的模糊底衬 +
     * 光边即最终观感，叠加透明度会双重淡化。
     */
    public static void applyGlassAlpha(Context context, View root) {
        boolean hasBackground = context.getSharedPreferences(
                        Constants.Prefs.BACKGROUND_PREFS_NAME, Context.MODE_PRIVATE)
                .contains(Constants.Prefs.BACKGROUND_IMAGE_URI);
        int alpha = hasBackground
                ? context.getResources().getInteger(R.integer.surface_glass_alpha)
                : 255;
        applyGlassAlphaRecursive(root, alpha);
    }

    private static void applyGlassAlphaRecursive(View view, int alpha) {
        if ("glass".equals(view.getTag())) {
            android.graphics.drawable.Drawable bg = view.getBackground();
            if (bg != null && !(bg instanceof LensGlassDrawable)) {
                bg = bg.mutate();
                if (bg instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld =
                            (android.graphics.drawable.LayerDrawable) bg;
                    for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                        android.graphics.drawable.Drawable layer = ld.getDrawable(i);
                        if (layer != null) layer.setAlpha(alpha);
                    }
                } else {
                    bg.setAlpha(alpha);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyGlassAlphaRecursive(group.getChildAt(i), alpha);
            }
        }
    }

    /**
     * 为 Fragment 设置 M3 MaterialSharedAxis 页面转场（X 轴水平滑入），
     * 时长取 motion_duration_long，并自动尊重系统「减少动态效果」。
     */
    public static void setupFragmentTransitions(Fragment fragment) {
        if (isReducedMotionEnabled(fragment.requireContext()))
            return;
        int duration = fragment.getResources().getInteger(R.integer.motion_duration_long);
        // M3 方向性缓动：进入（enter/reenter）用 emphasized-decelerate，
        // 退出（exit/return）用 emphasized-accelerate
        MaterialSharedAxis enterTransition = new MaterialSharedAxis(MaterialSharedAxis.X, true);
        enterTransition.setInterpolator(
                android.view.animation.AnimationUtils.loadInterpolator(
                        fragment.requireContext(), R.anim.emphasized_decelerate));
        MaterialSharedAxis exitTransition = new MaterialSharedAxis(MaterialSharedAxis.X, true);
        exitTransition.setInterpolator(
                android.view.animation.AnimationUtils.loadInterpolator(
                        fragment.requireContext(), R.anim.emphasized_accelerate));
        MaterialSharedAxis reenterTransition = new MaterialSharedAxis(MaterialSharedAxis.X, true);
        reenterTransition.setInterpolator(
                android.view.animation.AnimationUtils.loadInterpolator(
                        fragment.requireContext(), R.anim.emphasized_decelerate));
        MaterialSharedAxis returnTransition = new MaterialSharedAxis(MaterialSharedAxis.X, true);
        returnTransition.setInterpolator(
                android.view.animation.AnimationUtils.loadInterpolator(
                        fragment.requireContext(), R.anim.emphasized_accelerate));
        enterTransition.setDuration(duration);
        exitTransition.setDuration(duration);
        reenterTransition.setDuration(duration);
        returnTransition.setDuration(duration);
        fragment.setEnterTransition(enterTransition);
        fragment.setExitTransition(exitTransition);
        fragment.setReenterTransition(reenterTransition);
        fragment.setReturnTransition(returnTransition);
    }

    /**
     * HTTP 非 2xx 响应失败，携带状态码供上层按类别定制策略（如 429 限流等待更久）。
     * 取代原先无类型的 RuntimeException——靠解析本地化文案区分失败类别过于脆弱。
     * 文案仍由抛出点用现有字符串资源拼装，本类只额外携带结构化的状态码。
     */
    public static final class HttpStatusCodeException extends RuntimeException {

        private final int statusCode;

        public HttpStatusCodeException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
