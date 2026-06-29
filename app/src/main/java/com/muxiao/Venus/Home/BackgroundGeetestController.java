package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.Intent;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
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

    // 静态验证结果传递：前台验证完成后直接写入，后台线程通过 latch 读取
    private static volatile CountDownLatch verificationLatch;
    private static volatile Map<String, String> verificationResult;

    // 将 pendingGt/pendingChallenge/pendingHeaders 合并为不可变快照，保证复合操作原子性
    private static class PendingRequest {
        final String gt, challenge;
        final Map<String, String> headers;
        PendingRequest(String gt, String challenge, Map<String, String> headers) {
            this.gt = gt; this.challenge = challenge; this.headers = headers;
        }
    }
    private static volatile PendingRequest pendingRequest;

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
        notifier.notifyListeners(context.getString(R.string.geetest_need_verification));
        verificationLatch = new CountDownLatch(1);
        verificationResult = null;

        // 拉起前台 Activity 进行验证
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Constants.ACTION_HANDLE_CAPTCHA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);

        // 阻塞后台线程，等待前台验证完成（最多 5 分钟）
        try {
            android.util.Log.e("VenusCaptcha", "Background: waiting on latch...");
            verificationLatch.await(5, TimeUnit.MINUTES);
            android.util.Log.e("VenusCaptcha", "Background: latch released, continuing task");
        } catch (InterruptedException e) {
            android.util.Log.e("VenusCaptcha", "Background: latch interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public GT3GeetestUtils getGeetestUtils() {
        return gt3Utils;
    }

    @Override
    public void destroyButton() {
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
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
        notifier.notifyListeners(taskName + context.getString(R.string.geetest_waiting));
    }

    @Override
    public void updateTaskStatusInProgress(String taskName) {
        notifier.notifyListeners(taskName + context.getString(R.string.geetest_done));
    }

    /**
     * 由前台 Activity 调用，通知验证成功。
     */
    public static void notifyVerificationSuccess(Map<String, String> geetCode) {
        android.util.Log.e("VenusCaptcha", "notifyVerificationSuccess: setting result directly");
        verificationResult = geetCode;
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
        android.util.Log.e("VenusCaptcha", "notifyVerificationSuccess: latch released");
    }

    /**
     * 获取前台验证完成后存储的 geetest 结果。
     * 后台任务在 latch 释放后调用此方法获取验证代码。
     */
    public static synchronized Map<String, String> getGeetestResult() {
        Map<String, String> result = verificationResult;
        verificationResult = null;
        return result;
    }

    /**
     * 保存后台任务 API1 获取的 gt 和 challenge，供前台使用同一 challenge 进行验证。
     */
    public static void savePendingChallenge(String gt, String challenge) {
        PendingRequest existing = pendingRequest;
        pendingRequest = new PendingRequest(gt, challenge, existing != null ? existing.headers : null);
    }

    /**
     * 消费并清除后台任务保存的 gt 和 challenge。
     * @return [0]=gt, [1]=challenge，如果没有则返回 null。
     */
    public static String[] consumePendingChallenge() {
        PendingRequest req = pendingRequest;
        if (req != null && req.gt != null && req.challenge != null) {
            // 仅清除 gt/challenge，保留 headers
            pendingRequest = new PendingRequest(null, null, req.headers);
            return new String[]{req.gt, req.challenge};
        }
        return null;
    }

    /**
     * 保存后台任务的请求 headers（含 Cookie），供前台 API2 二次验证使用。
     */
    public static void savePendingHeaders(Map<String, String> headers) {
        PendingRequest existing = pendingRequest;
        Map<String, String> copiedHeaders = headers != null ? new HashMap<>(headers) : null;
        pendingRequest = new PendingRequest(
                existing != null ? existing.gt : null,
                existing != null ? existing.challenge : null,
                copiedHeaders);
    }

    /**
     * 消费并清除后台任务保存的 headers。
     * @return headers 副本，如果没有则返回 null。
     */
    public static Map<String, String> consumePendingHeaders() {
        PendingRequest req = pendingRequest;
        if (req != null && req.headers != null) {
            // 仅清除 headers，保留 gt/challenge
            pendingRequest = new PendingRequest(req.gt, req.challenge, null);
            return req.headers;
        }
        return null;
    }

    /**
     * 由前台 Activity 调用，通知验证失败。
     */
    public static void notifyVerificationFailure(String error) {
        android.util.Log.e("VenusCaptcha", "notifyVerificationFailure: " + error);
        verificationResult = null;
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
    }

}
