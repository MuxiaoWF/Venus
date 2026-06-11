package com.muxiao.Venus.Home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 后台人机验证控制器。
 * 遇到验证时直接拉起 MainActivity 完成前台验证，
 * 后台线程阻塞等待验证结果，完成后继续执行任务。
 */
public class BackgroundGeetestController implements GeetestController {

    private final Context context;
    private final tools.StatusNotifier notifier;

    private GT3GeetestUtils gt3Utils;
    private BroadcastReceiver resultReceiver;
    private CountDownLatch resultLatch;

    // 静态存储前台验证结果，供后台任务在 latch 释放后读取
    private static volatile Map<String, String> lastGeetestResult;
    // 保存后台任务的 configBean（含其 challenge），供前台直接使用
    private static volatile GT3ConfigBean pendingConfigBean;
    // 保存后台任务 API1 获取的 gt 和 challenge
    private static volatile String pendingGt;
    private static volatile String pendingChallenge;
    // 保存后台任务的请求 headers（含 Cookie），供前台 API2 二次验证使用
    private static volatile Map<String, String> pendingHeaders;

    public BackgroundGeetestController(Context context, tools.StatusNotifier notifier) {
        this.context = context;
        this.notifier = notifier;
    }

    @Override
    public void createUtils() {
        gt3Utils = new GT3GeetestUtils(context);
    }

    /**
     * 遇到人机验证时，拉起前台 Activity 完成验证，后台线程阻塞等待结果。
     * Geetest.geetest() 会同步调用此方法，因此阻塞不会影响其他线程。
     */
    @Override
    public void createButton(GT3ConfigBean gt3ConfigBean) {
        android.util.Log.e("VenusCaptcha", "BackgroundGeetestController.createButton called");
        notifier.notifyListeners("需要人机验证，正在打开应用...");
        pendingConfigBean = gt3ConfigBean;
        resultLatch = new CountDownLatch(1);

        // 注册结果接收器（在启动 Activity 之前，避免竞态）
        registerResultReceiver();

        // 拉起前台 Activity 进行验证
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction("ACTION_HANDLE_CAPTCHA");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);

        // 阻塞后台线程，等待前台验证完成（最多 5 分钟）
        try {
            android.util.Log.e("VenusCaptcha", "Background: waiting on latch...");
            resultLatch.await(5, TimeUnit.MINUTES);
            android.util.Log.e("VenusCaptcha", "Background: latch released, continuing task");
        } catch (InterruptedException e) {
            android.util.Log.e("VenusCaptcha", "Background: latch interrupted");
            Thread.currentThread().interrupt();
        } finally {
            unregisterResultReceiver();
        }
    }

    @Override
    public GT3GeetestUtils getGeetestUtils() {
        return gt3Utils;
    }

    @Override
    public void destroyButton() {
        unregisterResultReceiver();
        if (resultLatch != null) resultLatch.countDown();
    }

    @Override
    public void destroyUtils() {
        if (gt3Utils != null) {
            gt3Utils.destory();
            gt3Utils = null;
        }
    }

    @Override
    public void updateTaskStatusWaring(String taskName) {
        notifier.notifyListeners(taskName + "：等待人机验证");
    }

    @Override
    public void updateTaskStatusInProgress(String taskName) {
        notifier.notifyListeners(taskName + "：验证完成，继续执行");
    }

    /**
     * 由前台 Activity 调用，通知验证成功。
     */
    public static void notifyVerificationSuccess(Context context, String resultJson, Map<String, String> geetCode) {
        android.util.Log.e("VenusCaptcha", "notifyVerificationSuccess: sending broadcast, package=" + context.getPackageName());
        lastGeetestResult = geetCode;
        Intent intent = new Intent(Constants.ACTION_CAPTCHA_RESULT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(Constants.EXTRA_CAPTCHA_SUCCESS, true);
        intent.putExtra(Constants.EXTRA_CAPTCHA_CODE_JSON, resultJson);
        context.sendBroadcast(intent);
        android.util.Log.e("VenusCaptcha", "notifyVerificationSuccess: broadcast sent");
    }

    /**
     * 获取前台验证完成后存储的 geetest 结果。
     * 后台任务在 latch 释放后调用此方法获取验证代码。
     */
    public static Map<String, String> getGeetestResult() {
        Map<String, String> result = lastGeetestResult;
        lastGeetestResult = null;
        return result;
    }

    /**
     * 获取后台任务保存的 configBean（含其 challenge）。
     * 前台直接使用此 bean 初始化 Geetest SDK，避免重复 API1 调用。
     */
    public static GT3ConfigBean consumePendingConfigBean() {
        GT3ConfigBean bean = pendingConfigBean;
        pendingConfigBean = null;
        return bean;
    }

    /**
     * 保存后台任务 API1 获取的 gt 和 challenge，供前台使用同一 challenge 进行验证。
     */
    public static void savePendingChallenge(String gt, String challenge) {
        pendingGt = gt;
        pendingChallenge = challenge;
    }

    /**
     * 消费并清除后台任务保存的 gt 和 challenge。
     * @return [0]=gt, [1]=challenge，如果没有则返回 null。
     */
    public static String[] consumePendingChallenge() {
        String gt = pendingGt;
        String challenge = pendingChallenge;
        pendingGt = null;
        pendingChallenge = null;
        if (gt != null && challenge != null) return new String[]{gt, challenge};
        return null;
    }

    /**
     * 保存后台任务的请求 headers（含 Cookie），供前台 API2 二次验证使用。
     */
    public static void savePendingHeaders(Map<String, String> headers) {
        pendingHeaders = headers != null ? new HashMap<>(headers) : null;
    }

    /**
     * 消费并清除后台任务保存的 headers。
     * @return headers 副本，如果没有则返回 null。
     */
    public static Map<String, String> consumePendingHeaders() {
        Map<String, String> h = pendingHeaders;
        pendingHeaders = null;
        return h;
    }

    /**
     * 由前台 Activity 调用，通知验证失败。
     */
    public static void notifyVerificationFailure(Context context, String error) {
        Intent intent = new Intent(Constants.ACTION_CAPTCHA_RESULT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(Constants.EXTRA_CAPTCHA_SUCCESS, false);
        intent.putExtra(Constants.EXTRA_CAPTCHA_ERROR, error);
        context.sendBroadcast(intent);
    }

    private void registerResultReceiver() {
        unregisterResultReceiver();
        resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                android.util.Log.e("VenusCaptcha", "BroadcastReceiver.onReceive action=" + intent.getAction());
                if (!Constants.ACTION_CAPTCHA_RESULT.equals(intent.getAction())) return;
                boolean success = intent.getBooleanExtra(Constants.EXTRA_CAPTCHA_SUCCESS, false);
                android.util.Log.e("VenusCaptcha", "BroadcastReceiver success=" + success + ", counting down latch");
                if (resultLatch != null) resultLatch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(Constants.ACTION_CAPTCHA_RESULT);
        ContextCompat.registerReceiver(context, resultReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterResultReceiver() {
        if (resultReceiver != null) {
            try {
                context.unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {}
            resultReceiver = null;
        }
    }
}
