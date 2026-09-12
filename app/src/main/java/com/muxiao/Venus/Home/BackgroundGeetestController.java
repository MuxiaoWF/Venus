package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.Intent;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 后台人机验证控制器。
 * 遇到验证时直接拉起 MainActivity 完成前台验证，
 * 后台线程阻塞等待验证结果，完成后继续执行任务。
 * 全局验证状态已收敛到 CaptchaCoordinator 单一协调者（P0-4 解耦）。
 */
public class BackgroundGeetestController implements GeetestController {

    private final Context context;
    private final tools.StatusNotifier notifier;
    private GT3GeetestUtils gt3Utils;

    // 全局验证状态收敛到单一协调者实例（取代原静态 CountDownLatch / pending 状态）
    private static final CaptchaCoordinator coordinator = new DefaultCaptchaCoordinator();

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
        Logger.debug("VenusCaptcha", "BackgroundGeetestController.createButton called");
        notifier.notifyListeners(context.getString(R.string.geetest_need_verification));
        coordinator.reset();

        // 拉起前台 Activity 进行验证
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Constants.ACTION_HANDLE_CAPTCHA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);

        // 阻塞后台线程，等待前台验证完成（最多 5 分钟）
        try {
            Logger.debug("VenusCaptcha", "Background: waiting on latch...");
            coordinator.await();
            Logger.debug("VenusCaptcha", "Background: latch released, continuing task");
        } catch (InterruptedException e) {
            Logger.debug("VenusCaptcha", "Background: latch interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public GT3GeetestUtils getGeetestUtils() {
        return gt3Utils;
    }

    @Override
    public void destroyButton() {
        coordinator.countDown();
    }

    @Override
    public void updateTaskStatusWaring(String taskName) {
        notifier.notifyListeners(taskName + context.getString(R.string.geetest_waiting));
    }

    @Override
    public void updateTaskStatusInProgress(String taskName) {
        notifier.notifyListeners(taskName + context.getString(R.string.geetest_done));
    }

    // ===== 静态门面：供 Geetest / HomeFragment 按原调用方式使用（委托给协调者） =====

    /**
     * 由前台 Activity 调用，通知验证成功。
     */
    public static void notifyVerificationSuccess(Map<String, String> geetCode) {
        coordinator.notifyVerificationSuccess(geetCode);
    }

    /**
     * 获取前台验证完成后存储的 geetest 结果（消费并清除）。
     */
    public static synchronized Map<String, String> getGeetestResult() {
        return coordinator.consumeResult();
    }

    /**
     * 保存后台任务 API1 获取的 gt 和 challenge，供前台使用同一 challenge 进行验证。
     */
    public static void savePendingChallenge(String gt, String challenge) {
        coordinator.savePendingChallenge(gt, challenge);
    }

    /**
     * 消费并清除后台任务保存的 gt 和 challenge。
     */
    public static String[] consumePendingChallenge() {
        return coordinator.consumePendingChallenge();
    }

    /**
     * 保存后台任务的请求 headers（含 Cookie），供前台 API2 二次验证使用。
     */
    public static void savePendingHeaders(Map<String, String> headers) {
        coordinator.savePendingHeaders(headers);
    }

    /**
     * 消费并清除后台任务保存的 headers。
     */
    public static Map<String, String> consumePendingHeaders() {
        return coordinator.consumePendingHeaders();
    }

    /**
     * 由前台 Activity 调用，通知验证失败。
     */
    public static void notifyVerificationFailure() {
        coordinator.notifyVerificationFailure();
    }
}
