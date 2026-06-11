package com.muxiao.Venus.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.GzipSource;
import okio.Okio;

public class tools {
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

    /**
     * 监听器接口
     */
    public interface StatusInterface {
        void onLoginStatusChanged(String status);
    }

    /**
     * 监听器类
     */
    public static class StatusNotifier {
        private final List<StatusInterface> listeners = new ArrayList<>();

        /**
         * 添加监听器
         *
         * @param listener 监听器
         */
        public void addListener(StatusInterface listener) {
            listeners.add(listener);
        }

        /**
         * 移除所有监听器
         */
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
        // 向url中添加参数
        if (params != null) {
            urlBuilder.append("?");
            for (Map.Entry<String, String> entry : params.entrySet())
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            urlBuilder.deleteCharAt(urlBuilder.length() - 1);
        }
        Request.Builder requestBuilder = new Request.Builder()
                .url(urlBuilder.toString())
                .get();
        // 添加请求头
        if (headers != null)
            for (Map.Entry<String, String> entry : headers.entrySet())
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
        // 发送请求
        Request request = requestBuilder.build();
        try (Response response = getSharedClient().newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new RuntimeException("请求失败，状态码：" + response.code());
            ResponseBody responseBody = response.body();
            if ("gzip".equals(response.header("Content-Encoding"))) {
                GzipSource gzipSource = new GzipSource(responseBody.source());
                return Okio.buffer(gzipSource).readUtf8();
            } else {
                return responseBody.string();
            }
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查是否联网" + e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sendPostRequest(String urlStr, Map<String, String> headers, Map<String, Object> body) {
        RequestBody requestBody;
        if (body != null) {
            Gson gson = new Gson();
            String jsonBody = gson.toJson(body);
            requestBody = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        } else {
            requestBody = RequestBody.create(new byte[0], null);
        }
        Request.Builder requestBuilder = new Request.Builder()
                .url(urlStr)
                .post(requestBody);
        // 添加请求头
        if (headers != null)
            for (Map.Entry<String, String> entry : headers.entrySet())
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
        // 发送请求
        try (Response response = getSharedClient().newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful())
                throw new RuntimeException("请求失败，状态码：" + response.code());
            ResponseBody responseBody = response.body();
            if ("gzip".equals(response.header("Content-Encoding"))) {
                // 数据被gzip压缩，进行解码
                GzipSource gzipSource = new GzipSource(responseBody.source());
                return Okio.buffer(gzipSource).readUtf8();
            } else {
                // 如果没有压缩，直接返回字符串
                return responseBody.string();
            }
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException("请检查网络连接：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("请求失败：" + e.getMessage(), e);
        }
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

        // 圆角背景
        snackbarView.setBackgroundResource(R.drawable.snackbar_background);

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
        snackbarView.setElevation(6f);
        int h = (int) (16 * context.getResources().getDisplayMetrics().density);
        int v = (int) (10 * context.getResources().getDisplayMetrics().density);
        snackbarView.setPadding(h, v, h, v);

        // 居中显示，留出底部间距
        android.view.ViewGroup.LayoutParams lp = snackbarView.getLayoutParams();
        lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
            flp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
            flp.bottomMargin = (int) (80 * context.getResources().getDisplayMetrics().density);
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
                .setTitle("错误")
                .setMessage(error_message)
                .setPositiveButton("复制错误信息", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("错误信息", error_message);
                    clipboard.setPrimaryClip(clip);
                    if (context instanceof Activity) {
                        View rootView = ((Activity) context).findViewById(android.R.id.content);
                        showCustomSnackbar(rootView, context, "错误信息已复制到剪切板");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 复制文本到剪贴板
     */
    public static void copyToClipboard(View view, Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copy Venus", text);
        clipboard.setPrimaryClip(clip);
        showCustomSnackbar(view, context, "链接已复制到剪贴板");
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
                throw new Exception("无法打开源文件");
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
     * 清理过期的日志文件，只保留今天的日志。
     * 在任务开始时调用。
     */
    public static void cleanOldLogs(Context context) {
        File logDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logDir.exists()) return;
        String todayPrefix = "daily_task_log_" + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        File[] files = logDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("daily_task_log_") && !file.getName().startsWith(todayPrefix)) {
                file.delete();
            }
        }
    }

    /**
     * 静默写入日志文件，不触发通知。
     */
    public static void writeLog(Context context, String message) {
        try {
            File logDir = new File(context.getExternalFilesDir(null), "logs");
            if (!logDir.exists()) logDir.mkdirs();
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            File logFile = new File(logDir, "daily_task_log_" + date + ".txt");
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            FileWriter writer = new FileWriter(logFile, true);
            writer.append("[").append(timestamp).append("] ").append(message).append("\n");
            writer.close();
        } catch (IOException ignored) {}
    }

    /**
     * 向当天日志文件写入一条分隔符，用于区分不同次任务执行。
     */
    public static void writeLogSeparator(Context context) {
        try {
            File logDir = new File(context.getExternalFilesDir(null), "logs");
            if (!logDir.exists()) logDir.mkdirs();
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            File logFile = new File(logDir, "daily_task_log_" + date + ".txt");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            FileWriter writer = new FileWriter(logFile, true);
            writer.append("\n==================== ").append(timestamp).append(" ====================\n\n");
            writer.close();
        } catch (IOException ignored) {}
    }
}
