package com.muxiao.Venus.common;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.muxiao.Venus.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
            for (StatusInterface listener : listeners) {
                listener.onLoginStatusChanged(status);
            }
        }
    }

    /**
     * 创建一个设备deviceID
     *
     * @param context 上下文
     * @return deviceID -String
     */
    public static String getDeviceId(Context context) {
        @SuppressLint("HardwareIds") String namespace = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String name = Build.MANUFACTURER + " " + Build.MODEL;
        // Convert namespace to UUID
        UUID namespaceUUID = UUID.nameUUIDFromBytes(namespace.getBytes());
        // Concatenate namespace and name
        long msb = namespaceUUID.getMostSignificantBits();
        long lsb = namespaceUUID.getLeastSignificantBits();
        byte[] namespaceBytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            namespaceBytes[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 8; i < 16; i++) {
            namespaceBytes[i] = (byte) (lsb >>> (8 * (15 - i)));
        }
        byte[] nameBytes = name.getBytes();
        byte[] combinedBytes = new byte[namespaceBytes.length + nameBytes.length];
        System.arraycopy(namespaceBytes, 0, combinedBytes, 0, namespaceBytes.length);
        System.arraycopy(nameBytes, 0, combinedBytes, namespaceBytes.length, nameBytes.length);
        // Hash the combined bytes using MD5
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] hashBytes = md.digest(combinedBytes);
        // Convert the hash to a UUID
        long mostSignificantBits = 0;
        long leastSignificantBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificantBits = (mostSignificantBits << 8) | (hashBytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            leastSignificantBits = (leastSignificantBits << 8) | (hashBytes[i] & 0xff);
        }
        // Set the version to 3 and the variant to DCE 1.1
        mostSignificantBits &= ~0x000000000000F000L;
        mostSignificantBits |= 0x0000000000003000L;
        leastSignificantBits &= ~0xC000000000000000L;
        leastSignificantBits |= 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    public static String getDS2(String body, String salt, String params) {
        String i = String.valueOf(System.currentTimeMillis() / 1000);
        String r = String.valueOf(new Random().nextInt(100000) + 100001);
        String c = "salt=" + salt + "&t=" + i + "&r=" + r + "&b=" + body + "&q=" + params;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(c.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : messageDigest) {
                sb.append(String.format("%02x", b));
            }
            c = sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return i + "," + r + "," + c;
    }

    public static String sendGetRequest(String urlStr, Map<String, String> headers, Map<String, String> params) {
        try {
            StringBuilder urlBuilder = new StringBuilder(urlStr);
            if (params != null) {
                urlBuilder.append("?");
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    urlBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                }
                urlBuilder.deleteCharAt(urlBuilder.length() - 1);
            }
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
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
            }

            Request request = requestBuilder.build();
            try (Response response = client.newCall(request).execute()) {
                if (response.body() != null) {
                    ResponseBody responseBody = response.body();
                    // 使用string()方法自动处理gzip解压缩和字符编码
                    return responseBody.string();
                } else {
                    return "";
                }
            }
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查是否联网");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sendPostRequest(String urlStr, Map<String, String> headers, Map<String, Object> body) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();

            RequestBody requestBody;
            if (body != null) {
                // 构造JSON请求体
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
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
            }

            Request request = requestBuilder.build();
            try (Response response = client.newCall(request).execute()) {
                if (response.body() != null) {
                    return response.body().string();
                } else {
                    return "";
                }
            }
        } catch (UnknownHostException e) {
            throw new RuntimeException("请检查是否联网");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将用户数据写入SharedPreferences
     *
     * @param context 上下文
     * @param userId  用户标识
     * @param key     数据键名
     * @param value   数据值
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
     * @param context 上下文
     * @param userId  用户标识
     * @param key     数据键名
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
        // 获取当前的布局参数
        ViewGroup.LayoutParams layoutParams = snackbarView.getLayoutParams();
        // 检查布局参数类型并相应处理
        if (layoutParams instanceof androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
            // 如果是CoordinatorLayout的LayoutParams，使用原始逻辑
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams params =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) layoutParams;
            // 设置居中对齐
            params.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
            // 设置边距
            params.setMargins(
                    context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_horizontal),
                    0,
                    context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_horizontal),
                    context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_bottom)
            );
            // 设置宽度为wrap_content
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;

            snackbarView.setLayoutParams(params);
        } else {
            // 对于其他类型的布局参数（如FrameLayout.LayoutParams），使用基本设置
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) layoutParams;
                // 设置边距
                marginParams.setMargins(
                        context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_horizontal),
                        0,
                        context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_horizontal),
                        context.getResources().getDimensionPixelSize(R.dimen.snackbar_margin_bottom)
                );
                // 设置居中对齐
                if (layoutParams instanceof FrameLayout.LayoutParams)
                    ((FrameLayout.LayoutParams) layoutParams).gravity =
                            android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
            }
            // 设置宽度为wrap_content
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            snackbarView.setLayoutParams(layoutParams);
        }
        snackbar.show();
    }

    /**
     * 显示错误信息并提供复制
     *
     * @param error_message 错误信息
     */
    public static void show_error_dialog(Context context,String error_message) {
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
     * */
    public static void copyToClipboard(View view, Context context,String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copy Venus", text);
        clipboard.setPrimaryClip(clip);
        showCustomSnackbar(view, context ,"链接已复制到剪贴板");
    }

    /**
     * 复制文件
     *
     * @param context 上下文
     * @param sourceUri 源文件URI
     * @param destFile 目标文件
     * @throws Exception 复制过程中可能发生的异常
     */
    public static void copyFile(Context context, Uri sourceUri, File destFile) throws Exception {
        try (InputStream inputStream = context.getContentResolver().openInputStream(sourceUri);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            if (inputStream == null) {
                throw new Exception("无法打开源文件");
            }
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }
    }
}
