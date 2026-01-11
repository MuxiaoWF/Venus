package com.muxiao.Venus.Home;

import java.util.Map;

/**
 * 验证码验证完成回调接口
 */
public interface GeetestVerificationCallback {
    void onVerificationSuccess(Map<String, String> geetest_code);

    void onVerificationFailed(String error);
}
