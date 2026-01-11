package com.muxiao.Venus.Home;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

/**
 * 创建流程：createUtils -> createButton -> destroyButton( -> destroyUtils)
 */
public interface GeetestController {
    /**
     * 创建验证码Utils，越早越好，最好onCreate中
     */
    void createUtils();

    /**
     * 绑定并创建验证码按钮
     *
     * @param gt3ConfigBean 极验验证码配置
     */
    void createButton(GT3ConfigBean gt3ConfigBean);

    /**
     * 获取验证码Utils
     *
     * @return 验证码Utils
     */
    GT3GeetestUtils getGeetestUtils();

    /**
     * 销毁验证码按钮（内含destroyUtils）
     */
    void destroyButton();

    /**
     * 销毁验证码Utils
     */
    void destroyUtils();
}
