package com.muxiao.Venus.common;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import com.google.gson.Gson;
import com.muxiao.Venus.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
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
        // 构建请求
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        Request.Builder requestBuilder = new Request.Builder()
                .url(urlBuilder.toString())
                .get();
        // 添加请求头
        if (headers != null)
            for (Map.Entry<String, String> entry : headers.entrySet())
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
        // 发送请求
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
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
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查是否联网" + e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sendPostRequest(String urlStr, Map<String, String> headers, Map<String, Object> body) {
        // 构建请求
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
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
        try (Response response = client.newCall(requestBuilder.build()).execute()) {
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
        // 获取Snackbar的视图
        View snackbarView = snackbar.getView();

        // 设置背景颜色（使用主题颜色或自定义颜色）
        snackbarView.setBackgroundColor(context.getResources().getColor(R.color.snackbar_background, context.getTheme()));

        // 设置文本颜色
        MaterialTextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (textView != null) {
            textView.setTextColor(context.getResources().getColor(R.color.snackbar_text, context.getTheme()));
            // 设置文本居中
            textView.setGravity(android.view.Gravity.CENTER);
        }
        
        // 设置动作文本颜色（如果有动作按钮）
        MaterialButton actionView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_action);
        if (actionView != null)
            actionView.setTextColor(context.getResources().getColor(R.color.snackbar_action, context.getTheme()));

        // 设置阴影
        snackbarView.setElevation(8f);

        // 设置内边距
        snackbarView.setPadding(24, 12, 24, 12);
        snackbarView.getLayoutParams().width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

        // 居中显示Snackbar
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) snackbarView.getLayoutParams();
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
        params.bottomMargin = 100; // 设置距离底部的距离
        snackbarView.setLayoutParams(params);
        
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
}
