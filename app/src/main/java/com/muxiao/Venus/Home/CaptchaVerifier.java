package com.muxiao.Venus.Home;

import com.muxiao.Venus.common.tools;

import java.util.Map;

/**
 * 人机验证工具类，统一 BBSDaily 和 BBSGameDaily 的验证逻辑
 */
public class CaptchaVerifier {

    public interface VerificationCallback {
        void onSuccess(Map<String, String> geetCode);
        void onFailure();
    }

    public static void performVerification(
            GeetestController controller,
            tools.StatusNotifier notifier,
            String taskName,
            Map<String, String> headers,
            VerificationCallback callback) {

        controller.updateTaskStatusWaring(taskName);
        Geetest.geetest(headers, new GeetestVerificationCallback() {
            @Override
            public void onVerificationSuccess(Map<String, String> code) {
                notifier.notifyListeners("人机验证成功，继续执行签到...");
                controller.destroyButton();
                controller.updateTaskStatusInProgress(taskName);
                callback.onSuccess(code);
            }

            @Override
            public void onVerificationFailed(String error) {
                notifier.notifyListeners("人机验证失败: " + error);
                controller.destroyButton();
                callback.onFailure();
            }
        }, controller);
    }
}
