package com.muxiao.Venus.Home;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

/**
 * 极验控件的生命周期契约：createUtils → createButton → destroyButton。
 *
 * <p>只有「应用前台」的实现能真正承载验证 UI；后台链路不再驱动极验 SDK（见
 * {@link BackgroundGeetestController}），任务命中 1034 时改为挂起，由 {@link CaptchaSuspension}
 * 在轮末统一发起一次验证请求。
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

    /** 是否具备 UI 承载能力。后台实现返回 false，Geetest 据此拒绝弹窗（避免在无界面上下文里构造 SDK）。 */
    default boolean hasUi() {
        return true;
    }
}

/**
 * 后台占位实现：**不承载验证 UI，也不阻塞任何线程**。
 *
 * <p>历史实现让后台线程在 {@code createButton} 里等一个全局 latch（最长 5 分钟）。这有三个硬伤：
 * 定时链路的 WorkManager 窗口被吃掉一半；多个任务并发撞 1034 时会覆盖同一把 latch、必然丢任务；
 * 等待结果只活在内存里，跨轮或进程死亡即丢。现在的模型是「挂起-续跑」：任务抛出
 * {@link CaptchaRequiredException} 结束本轮，前台完成验证后凭证落库，下一轮续跑带上。
 *
 * <p>因此本类只作为 {@link GeetestController} 的类型占位存在——{@code createButton} 若被调用，
 * 说明有代码在后台错误地驱动了极验 SDK，直接抛错暴露，而不是静默等待。
 */
class BackgroundGeetestController implements GeetestController {

    @Override
    public boolean hasUi() {
        return false;
    }

    @Override
    public void createUtils() {
        // 后台不需要 SDK 实例：构造 GT3GeetestUtils 需要 Activity 上下文，在 Service/Worker 里建出来也用不上
    }

    @Override
    public void createButton(GT3ConfigBean gt3ConfigBean) {
        throw new IllegalStateException(
                "BackgroundGeetestController cannot host captcha UI; throw CaptchaRequiredException instead");
    }

    @Override
    public GT3GeetestUtils getGeetestUtils() {
        return null;
    }

    @Override
    public void destroyButton() {
    }
}
