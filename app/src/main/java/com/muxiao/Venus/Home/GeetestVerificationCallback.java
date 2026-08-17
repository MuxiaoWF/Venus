package com.muxiao.Venus.Home;

import java.util.Map;

/**
 * 验证码验证完成回调接口。
 */
public interface GeetestVerificationCallback {
    /** 验证成功，geetest_code 含二次验证结果（x-rpc-challenge/validate/seccode）。 */
    void onVerificationSuccess(Map<String, String> geetest_code);

    /** 验证失败或被用户关闭，error 为原因描述。 */
    void onVerificationFailed(String error);
}
