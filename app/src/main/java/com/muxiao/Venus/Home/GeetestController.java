package com.muxiao.Venus.Home;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;

/**
 * 创建流程：createUtils -> createButton -> destroyButton
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
     * 销毁验证码按钮
     */
    void destroyButton();

    /**
     * 更新任务状态为警告
     */
    void updateTaskStatusWaring(String taskName);

    /**
     * 更新任务状态为正常运行
     */
    void updateTaskStatusInProgress(String taskName);

    /**
     * 一次人机验证以失败告终（用户未在窗口内完成/验证超时/验证管线异常），
     * taskName 为触发验证的任务名（与任务状态持久化键同源）。
     * 仅供后台链路统计「验证码失败任务」（同日重试据此排除）；前台实现无需关心。
     */
    default void onCaptchaFailed(String taskName) {
    }
}
