package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 验证码验证的公共逻辑，供 BBSDaily 和 BBSGameDaily 共用。
 */
public class CaptchaVerificationHelper {

    private Map<String, String> geetCode = null;
    private volatile boolean verificationComplete = false;

    private final Context context;
    private final GeetestController gt3Controller;
    private final tools.StatusNotifier notifier;
    private final Notification notification;

    public CaptchaVerificationHelper(Context context, GeetestController gt3Controller, tools.StatusNotifier notifier, Notification notification) {
        this.context = context;
        this.gt3Controller = gt3Controller;
        this.notifier = notifier;
        this.notification = notification;
    }

    /** 返回验证得到的 geetest 结果；未完成或失败时可能为 null。 */
    public Map<String, String> getGeetCode() {
        return geetCode;
    }

    /**
     * 发起极验验证并注册回调。
     * 先重置 verificationComplete，验证成功时写入 geetCode 并唤醒等待线程；
     * 失败时将 geetCode 置为 null 同样唤醒；结果供 waitForCompletion() 阻塞消费。
     *
     * @param headers  含 Cookie 的请求头，供 API2 二次验证绑定会话
     * @param taskName 任务名，用于状态提示
     */
    public void performVerificationWithCallback(Map<String, String> headers, String taskName) {
        verificationComplete = false;
        gt3Controller.updateTaskStatusWaring(taskName);
        Geetest.geetest(context, headers, new GeetestVerificationCallback() {
            @Override
            public void onVerificationSuccess(Map<String, String> code) {
                notification.dismissErrorNotification();
                gt3Controller.destroyButton();
                gt3Controller.updateTaskStatusInProgress(taskName);
                setGeetCodeAndComplete(code);
            }

            @Override
            public void onVerificationFailed(String error) {
                notifier.notifyListeners(context.getString(R.string.geetest_failed, error));
                gt3Controller.destroyButton();
                setGeetCodeAndComplete(null);
            }
        }, gt3Controller);
    }

    /**
     * 阻塞等待验证完成。
     */
    public synchronized void waitForCompletion() {
        while (!verificationComplete) {
            try { this.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** 写入验证结果（成功后非 null；失败传 null）并置 verificationComplete，唤醒 waitForCompletion。 */
    private synchronized void setGeetCodeAndComplete(Map<String, String> code) {
        if (code != null || geetCode == null) {
            geetCode = code;
        }
        verificationComplete = true;
        notifyAll();
    }
}
