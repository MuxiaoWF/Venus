package com.muxiao.Venus.Home;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

/**
 * 极验控件的生命周期契约：createUtils → createButton → destroyButton。
 *
 * <p>只有「应用前台」存在实现（见 {@code HomeFragment}）。后台链路完全不驱动极验 SDK：
 * 任务命中 1034 时抛出 {@link CaptchaRequiredException} 结束本轮，由 {@link CaptchaSuspension}
 * 把 challenge 落库并在轮末统一发起一次验证请求——这也正是 {@code TaskExecutor} 及其任务模块
 * 都不再接收控制器的原因（旧版曾传入一个「无 UI 占位实现」）。
 */
public interface GeetestController {
    /** 创建验证码 Utils，越早越好（Activity onCreate）。 */
    void createUtils();

    /** 绑定并创建验证码按钮。 */
    void createButton(GT3ConfigBean gt3ConfigBean);

    /** 底层 GT3GeetestUtils：Geetest 用它触发 getGeetest()/showSuccessDialog()。 */
    GT3GeetestUtils getGeetestUtils();

    /** 销毁验证码按钮。 */
    void destroyButton();
}
