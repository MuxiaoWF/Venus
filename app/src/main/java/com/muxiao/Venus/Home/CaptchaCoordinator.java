package com.muxiao.Venus.Home;

import java.util.Map;

/**
 * 前后台人机验证协调契约（P0-4 解耦目标）。
 * 取代 BackgroundGeetestController 中散落的静态 CountDownLatch / 全局 pending 状态，
 * 将"后台→前台"的 Challenge/Headers 与"前台→后台"的结果传递集中到单一协调者实例，
 * 前后台通过接口契约通信，而非依赖静态全局变量。
 */
public interface CaptchaCoordinator {

    /** 开始一次验证：重置 latch 与结果（在后台线程调用） */
    void reset();

    /** 阻塞后台线程，等待前台验证完成（最多 5 分钟） */
    void await() throws InterruptedException;

    /** 释放等待（取消/销毁时） */
    void countDown();

    /** 前台验证成功：写入结果并释放 latch */
    void notifyVerificationSuccess(Map<String, String> geetCode);

    /** 前台验证失败：清除结果并释放 latch（失败详情由调用方自行记录日志） */
    void notifyVerificationFailure();

    /** 读取并清除验证结果（前台验证完成后由后台线程消费） */
    Map<String, String> consumeResult();

    /** 保存后台任务 API1 获取的 gt/challenge */
    void savePendingChallenge(String gt, String challenge);

    /** 消费并清除后台任务保存的 gt/challenge（[0]=gt, [1]=challenge） */
    String[] consumePendingChallenge();

    /** 保存后台任务的请求 headers（含 Cookie） */
    void savePendingHeaders(Map<String, String> headers);

    /** 消费并清除后台任务保存的 headers */
    Map<String, String> consumePendingHeaders();
}
