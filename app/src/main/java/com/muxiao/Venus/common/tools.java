package com.muxiao.Venus.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.muxiao.Venus.R;

import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
    // 共享 OkHttpClient 实例，复用连接池，避免每次请求创建新连接
    private static volatile OkHttpClient sharedClient;

    private static OkHttpClient getSharedClient() {
        if (sharedClient == null) {
            synchronized (tools.class) {
                if (sharedClient == null) {
                    sharedClient = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return sharedClient;
    }

    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    /**
     * 监听器接口
     */
    public interface StatusInterface {
        void onLoginStatusChanged(String status);
    }

    /**
     * 监听器类（线程安全）
     */
    public static class StatusNotifier {
        private final CopyOnWriteArrayList<StatusInterface> listeners = new CopyOnWriteArrayList<>();

        public void addListener(StatusInterface listener) {
            listeners.add(listener);
        }

        public void removeAllListeners() {
            listeners.clear();
        }

        public void notifyListeners(String status) {
            for (StatusInterface listener : listeners)
                listener.onLoginStatusChanged(status);
        }
    }

    public static String sendGetRequest(String urlStr, Map<String, String> headers, Map<String, String> params) {
        StringBuilder urlBuilder = new StringBuilder(urlStr);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            for (Map.Entry<String, String> entry : params.entrySet())
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            urlBuilder.deleteCharAt(urlBuilder.length() - 1);
        }
        Request.Builder requestBuilder = new Request.Builder().url(urlBuilder.toString()).get();
        if (headers != null)
            for (Map.Entry<String, String> entry : headers.entrySet())
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
        try (Response response = getSharedClient().newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new RuntimeException("请求失败，状态码：" + response.code());
            return readResponseBody(response);
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查是否联网" + e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sendPostRequest(String urlStr, Map<String, String> headers, Map<String, Object> body) {
        RequestBody requestBody;
        if (body != null) {
            String jsonBody = GSON.toJson(body);
            requestBody = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        } else {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Request.Builder requestBuilder = new Request.Builder().url(urlStr).post(requestBody);
        if (headers != null)
            for (Map.Entry<String, String> entry : headers.entrySet())
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
        try (Response response = getSharedClient().newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new RuntimeException("请求失败，状态码：" + response.code());
            return readResponseBody(response);
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查网络连接：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("请求失败：" + e.getMessage(), e);
        }
    }

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
     * 将用户数据写入SharedPreferences
     *
     * @param userId 用户标识
     * @param key    数据键名
     * @param value  数据值
     */
    public static void write(Context context, String userId, String key, String value) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.apply();
    }

    /**
     * 从SharedPreferences中读取用户数据
     *
     * @param userId 用户标识
     * @param key    数据键名
     * @return 用户数据
     */
    public static String read(Context context, String userId, String key) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE);
        return sharedPreferences.getString(key, null);
    }

    /**
     * 显示自定义Snackbar
     */
    public static void showCustomSnackbar(View view, Context context, String message) {
        Snackbar snackbar = Snackbar.make(view, message, Snackbar.LENGTH_SHORT);
        View snackbarView = snackbar.getView();

        // 圆角背景，使用 tint 方式保留 Material3 基础样式
        snackbarView.setBackgroundResource(R.drawable.snackbar_background);
        snackbarView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(context.getResources().getColor(R.color.snackbar_background, context.getTheme())));

        // 文本样式
        MaterialTextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (textView != null) {
            textView.setTextColor(context.getResources().getColor(R.color.snackbar_text, context.getTheme()));
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setTextSize(13);
            textView.setMaxLines(3);
        }

        // 动作按钮颜色
        MaterialButton actionView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_action);
        if (actionView != null)
            actionView.setTextColor(context.getResources().getColor(R.color.snackbar_action, context.getTheme()));

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
     * 显示错误信息并提供复制
     *
     * @param error_message 错误信息
     */
    public static void show_error_dialog(Context context, String error_message) {
        // 使用ContextThemeWrapper包装context，确保MaterialAlertDialogBuilder能正常工作
        Context themedContext = new android.view.ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        new MaterialAlertDialogBuilder(themedContext)
                .setTitle(context.getString(R.string.dialog_error))
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
     * 复制文本到剪贴板
     */
    public static void copyToClipboard(View view, Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copy Venus", text);
        clipboard.setPrimaryClip(clip);
        showCustomSnackbar(view, context, context.getString(R.string.snack_link_copied));
    }

    /**
     * 复制文件
     *
     * @param sourceUri 源文件URI
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
     * 清理过期的日志文件。
     * 运行日志只保留今天，任务历史日志保留今天和昨天。
     * 在任务开始时调用。
     */
    public static void cleanOldLogs(Context context) {
        // 清理运行日志（只保留今天）
        File logDir = new File(context.getExternalFilesDir(null), "logs");
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

    public static File getTodayLogFile(Context context) {
        File logDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return new File(logDir, "daily_task_log_" + date + ".txt");
    }

    // ========== 新增公共工具方法 ==========

    /**
     * 读取用户 SharedPreferences 中指定 key 的值
     */
    public static String readUserPref(Context context, String userId, String key) {
        return context.getSharedPreferences("user_" + userId, Context.MODE_PRIVATE).getString(key, null);
    }

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
     * 获取当前活跃的 token（stoken 优先，否则 ltoken）
     */
    public static String getActiveToken(Context context, String userId) {
        String stoken = read(context, userId, "stoken");
        return stoken != null ? stoken : read(context, userId, "ltoken");
    }

    /**
     * 获取当前活跃 token 的 key 名
     */
    public static String getActiveTokenKey(Context context, String userId) {
        return read(context, userId, "stoken") != null ? "stoken" : "ltoken";
    }

    /**
     * 随机延时（毫秒），用于避免请求频率限制
     */
    public static void randomDelay(int minMs, int rangeMs) throws InterruptedException {
        Thread.sleep(minMs + RANDOM.nextInt(rangeMs));
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
            return bytesToHex(md.digest(input.getBytes()));
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
            if (!venusDir.exists()) venusDir.mkdirs();
            try (OutputStream fos = new FileOutputStream(new File(venusDir, fileName))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
        }
    }

    /**
     * 为 View 添加状态栏顶部内边距
     */
    public static void applyStatusBarPadding(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }
}
