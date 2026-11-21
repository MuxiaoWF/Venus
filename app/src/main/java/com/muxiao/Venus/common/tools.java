package com.muxiao.Venus.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.VenusSnackbar;
import com.google.gson.Gson;
import com.muxiao.Venus.R;
import com.muxiao.Venus.VenusApplication;
import com.muxiao.Venus.common.data.UserRepository;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.content.ContentValues;

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
    private static volatile Toast sToast;
    private static volatile String sToastText = "";
    private static volatile long sToastAtMs = 0;

    // OkHttpClient 统一由 ApiClient 提供，连接池共享

    /** 返回应用共享的 OkHttpClient（{@link ApiClient#get()}，复用连接池）。 */
    private static OkHttpClient getSharedClient() {
        return ApiClient.get();
    }

    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    /** 登录状态变化监听器，回调最新登录状态文案。 */
    public interface StatusInterface {
        void onLoginStatusChanged(String status);
    }

    /** 登录状态广播器：CopyOnWriteArrayList 保证跨线程安全增删与通知。 */
    public static class StatusNotifier {
        private final CopyOnWriteArrayList<StatusInterface> listeners = new CopyOnWriteArrayList<>();

        public void addListener(StatusInterface listener) {
            listeners.add(listener);
        }

        /** 清空所有监听器（Activity 销毁时调用，避免泄漏）。 */
        public void removeAllListeners() {
            listeners.clear();
        }

        public void notifyListeners(String status) {
            for (StatusInterface listener : listeners)
                listener.onLoginStatusChanged(status);
        }
    }

    private static final String JSON_MEDIA_TYPE = "application/json; charset=utf-8";

    /**
     * 统一网络异常出口：按异常类型映射为 strings.xml 本地化提示（跟随应用语言设置）；
     * 已是业务异常则原样抛出避免嵌套文案；均保留 cause 供日志与「复制错误」使用。
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
     * 经全局 Application Context 读 strings.xml（Application 已在 attachBaseContext 按用户语言包装）；
     * Application 未创建时退回中文兜底串，避免因提示文案崩溃。
     * @param fallback 兜底文案，须与 values/strings.xml 默认文案一致
     */
    private static String str(String fallback, int resId, Object... args) {
        Context app = VenusApplication.getAppContext();
        if (app == null)
            return args.length == 0 ? fallback : String.format(Locale.ROOT, fallback, args);
        return app.getString(resId, args);
    }

    /** 异常原因兜底：getMessage() 可能为 null，回退类名，避免出现「请求失败：null」。 */
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
     * 用 HttpUrl 构建器拼 query，保证中文/空格/&/= 正确百分号编码（手工拼接会截断 query 或串参）。
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
     * 同上；fastTimeout=true 改用 {@link ApiClient#getFast()}（connect/read 各 4s），
     * 供「主源 + 兜底源」链路在主源不可达（如 GitHub raw 被墙）时快速失败让位兜底源。
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

    /** 以 JSON body 发 POST，自动 gzip 解压；失败抛 RuntimeException（含联网/错误提示）。 */
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

    /** 响应包装（状态码/body/headers），用于需读响应头的接口，如 loginByPassword 的 x-rpc-aigis。 */
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
     * 以原始 JSON 字符串发 POST，返回状态码/body/headers。
     * 与 {@link #sendPostRequest} 的区别：调用方自行序列化，保证参与 DS 签名的 body 与实际发出的逐字节一致（DS2 必需）；
     * 且非 2xx 不抛异常，由调用方按 retcode 处理。
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

    /** 读响应体：Content-Encoding=gzip 时用 GzipSource 解压，否则直接读字符串。 */
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

    /** 写用户数据（经 UserRepository 单一入口）。 */
    public static void write(Context context, String userId, String key, String value) {
        new UserRepository(context).putString(userId, key, value);
    }

    /** 读用户数据（经 UserRepository 单一入口）。 */
    public static String read(Context context, String userId, String key) {
        return new UserRepository(context).getString(userId, key);
    }

    /**
     * 显示居中短提示条，实际仅转发到 {@link VenusSnackbar}，样式由 Compose 宿主决定。
     * view/context 为历史签名保留（50+ 调用点零改动），新代码直接用 {@link VenusSnackbar#show(String)}。
     */
    @SuppressWarnings("unused") // view/context 为历史签名保留（50+ 调用点零改动），实际仅转发 message
    public static void showCustomSnackbar(View view, Context context, String message) {
        VenusSnackbar.show(message);
    }

    /** 系统 Toast：跨应用可见；同文案 1s 内去重，不同文案替换上一条而非排队。 */
    public static void showToast(String message) {
        if (message == null || message.isEmpty()) return;
        Runnable deliver = () -> {
            long now = System.currentTimeMillis();
            if (message.equals(sToastText) && now - sToastAtMs < 1_000) return;
            if (sToast != null) sToast.cancel();
            Context app = VenusApplication.getAppContext();
            if (app == null) return;
            sToast = Toast.makeText(app, message, Toast.LENGTH_SHORT);
            sToastText = message;
            sToastAtMs = now;
            sToast.show();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run();
        else new Handler(Looper.getMainLooper()).post(deliver);
    }

    /** 保留历史签名，便于按站点替换 Snackbar 调用。 */
    public static void showToast(View view, Context context, String message) {
        showToast(message);
    }

    /** 同上。 */
    public static void showToast(Context context, String message) {
        showToast(message);
    }

    /** 推荐签名：Compose 宿主自己决定落点，无需传锚点 View。 */
    @SuppressWarnings("unused") // context 为历史签名保留，实际仅转发 message
    public static void showCustomSnackbar(Context context, String message) {
        VenusSnackbar.show(message);
    }

    /** 弹错误对话框，带「复制错误」按钮（复制后提示已复制）。 */
    public static void show_error_dialog(Context context, String error_message) {
        show_error_dialog(context, context.getString(R.string.dialog_error), error_message);
    }

    /** 同上，可指定场景化标题（如「检查更新失败」）。 */
    public static void show_error_dialog(Context context, String title, String error_message) {
        // 对话框必须有 Activity 窗口承载；Service/Application 既无 window token 也无 LifecycleOwner，
        // 旧实现在此回退 MaterialAlertDialogBuilder 会在 show() 抛 BadTokenException（必崩的死分支），
        // 故统一降级为记日志：后台错误留在日志可排查，不制造二次崩溃。
        if (!(context instanceof Activity)) {
            Logger.w("show_error_dialog 无 UI 宿主（" + context.getClass().getSimpleName()
                    + "），错误仅记录：" + title + " / " + error_message);
            return;
        }
        Activity activity = (Activity) context;
        VenusDialogs.confirm(activity, title, error_message,
                context.getString(R.string.btn_copy_error), () -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(context.getString(R.string.error_label), error_message);
                    clipboard.setPrimaryClip(clip);
                    showToast(context, context.getString(R.string.snack_error_copied));
                },
                context.getString(R.string.btn_close));
    }

    /** 提示对话框：仅确认按钮、不带「复制错误」，用于非故障的引导信息（如「请先选择用户」）。 */
    public static void show_warning_dialog(Context context, String message) {
        if (!(context instanceof Activity)) {
            Logger.w("show_warning_dialog 无 UI 宿主（" + context.getClass().getSimpleName()
                    + "），提示仅记录：" + message);
            return;
        }
        VenusDialogs.info((Activity) context,
                context.getString(R.string.dialog_hint), message,
                context.getString(R.string.btn_close));
    }

    /** 复制到剪贴板并提示「已复制」。 */
    public static void copyToClipboard(View view, Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copy Venus", text);
        clipboard.setPrimaryClip(clip);
        showToast(view, context, context.getString(R.string.snack_link_copied));
    }

    /** 把 sourceUri 流拷贝到 destFile（8KB 缓冲）；源不可打开时抛异常。 */
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
     * 日志目录：外置私有目录不可用（未挂载/被回收）时回退 filesDir，避免 new File(null, ...) 抛 NPE；
     * 写入与清理共用同一目录。
     */
    private static File logDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), "logs");
    }

    /**
     * 清理过期日志：运行日志只留今天，任务历史留今天+昨天（跨天时 writeDailyLog 写的是昨天文件）。任务开始时调用。
     */
    public static void cleanOldLogs(Context context) {
        File logDir = logDir(context);
        if (logDir.exists()) {
            String todayPrefix = "daily_task_log_" + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().startsWith("daily_task_log_") && !file.getName().startsWith(todayPrefix)) {
                        if (!file.delete()) Logger.w("清理过期日志失败：" + file.getAbsolutePath());
                    }
                }
            }
        }

        File historyDir = new File(context.getFilesDir(), "task_history");
        if (historyDir.exists()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String todayLog = sdf.format(new Date()) + ".log";
            String yesterdayLog = sdf.format(new Date(System.currentTimeMillis() - 86400000L)) + ".log";
            File[] historyFiles = historyDir.listFiles((d, name) -> name.endsWith(".log"));
            if (historyFiles != null) {
                for (File file : historyFiles) {
                    if (!file.getName().equals(todayLog) && !file.getName().equals(yesterdayLog)) {
                        if (!file.delete()) Logger.w("清理历史日志失败：" + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    /** 静默写日志，不触发通知。 */
    public static void writeLog(Context context, String message) {
        try {
            File logFile = getTodayLogFile(context);
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append("[").append(timestamp).append("] ").append(message).append("\n");
            }
        } catch (IOException ignored) {}
    }

    /** 写一条分隔符，区分不同次任务执行。 */
    public static void writeLogSeparator(Context context) {
        try {
            File logFile = getTodayLogFile(context);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append("\n==================== ").append(timestamp).append(" ====================\n\n");
            }
        } catch (IOException ignored) {}
    }

    /** 今天的运行日志文件（logs/daily_task_log_yyyy-MM-dd.txt），目录不存在时创建。 */
    public static File getTodayLogFile(Context context) {
        File logDir = logDir(context);
        if (!logDir.exists() && !logDir.mkdirs()) {
            Logger.w("创建日志目录失败：" + logDir.getAbsolutePath());
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return new File(logDir, "daily_task_log_" + date + ".txt");
    }

    /** 读 stoken/ltoken/mid/stuid 拼认证 Cookie（stoken 优先，无则用 ltoken）。 */
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
     * 随机延时避免触发限频；rangeMs ≤ 0 时退化为固定延时（原实现在 rangeMs=0 时会抛 IllegalArgumentException）。
     */
    public static void randomDelay(int minMs, int rangeMs) throws InterruptedException {
        int base = Math.max(0, minMs);
        if (rangeMs <= 0) {
            if (base > 0) Thread.sleep(base);
            return;
        }
        Thread.sleep(base + RANDOM.nextInt(rangeMs));
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            // 固定 UTF-8，避免依赖平台默认字符集导致同一输入在不同设备上得到不同摘要
            return bytesToHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 保存 Bitmap 到系统相册（API 29+ 走 MediaStore，旧版直接写 Pictures/Venus）。 */
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
     * 系统「减少动态效果」：动画时长/转场缩放任一为 0 即视为关闭，自定义动效据此降级为无动画。
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
     * HTTP 非 2xx 失败，携带状态码供上层按类别定制策略（如 429 限流等待更久）；
     * 避免靠解析本地化文案区分失败类别。文案仍由抛出点用现有字符串资源拼装。
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
