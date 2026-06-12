package com.muxiao.Venus.Home;

import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 验证码验证的公共逻辑，供 BBSDaily 和 BBSGameDaily 共用。
 */
public class CaptchaVerificationHelper {

    private Map<String, String> geetCode = null;
    private volatile boolean verificationComplete = false;

    private final GeetestController gt3Controller;
    private final tools.StatusNotifier notifier;
    private final Notification notification;

    public CaptchaVerificationHelper(GeetestController gt3Controller, tools.StatusNotifier notifier, Notification notification) {
        this.gt3Controller = gt3Controller;
        this.notifier = notifier;
        this.notification = notification;
    }

    public Map<String, String> getGeetCode() {
        return geetCode;
    }

    public void performVerificationWithCallback(Map<String, String> headers, String taskName) {
        verificationComplete = false;
        CaptchaVerifier.performVerification(gt3Controller, notifier, notification, taskName, headers,
                new CaptchaVerifier.VerificationCallback() {
                    @Override
                    public void onSuccess(Map<String, String> code) {
                        setGeetCodeAndComplete(code);
                    }

                    @Override
                    public void onFailure() {
                        setGeetCodeAndComplete(null);
                    }
                });
        if (gt3Controller instanceof BackgroundGeetestController) {
            new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    Map<String, String> result = BackgroundGeetestController.getGeetestResult();
                    if (result != null) {
                        setGeetCodeAndComplete(result);
                        return;
                    }
                }
            }).start();
        }
    }

    /**
     * 阻塞等待验证完成。
     */
    public synchronized void waitForCompletion() {
        while (!verificationComplete) {
            try { this.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private synchronized void setGeetCodeAndComplete(Map<String, String> code) {
        geetCode = code;
        verificationComplete = true;
        notifyAll();
    }
}
