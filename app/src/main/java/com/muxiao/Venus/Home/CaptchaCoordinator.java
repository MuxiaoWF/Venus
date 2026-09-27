package com.muxiao.Venus.Home;

import com.muxiao.Venus.common.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 前后台人机验证协调契约（解耦目标）。
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

/**
 * CaptchaCoordinator 的默认实现（同文件包私有）：以实例状态取代全局静态字段。
 * 行为与原 BackgroundGeetestController 的静态字段完全一致，状态收敛到单一协调者。
 */
class DefaultCaptchaCoordinator implements CaptchaCoordinator {

    private volatile CountDownLatch latch = new CountDownLatch(1);
    private volatile Map<String, String> verificationResult;
    private volatile PendingRequest pendingRequest;

    @Override
    public void reset() {
        latch = new CountDownLatch(1);
        verificationResult = null;
    }

    @Override
    public void await() throws InterruptedException {
        if (!latch.await(5, TimeUnit.MINUTES)) {
            // 超时未等到验证结果：向上层返回后由 verificationResult==null 走失败分支
            Logger.w("DefaultCaptchaCoordinator: 等待人机验证超时（5 分钟）");
        }
    }

    @Override
    public void countDown() {
        latch.countDown();
    }

    @Override
    public void notifyVerificationSuccess(Map<String, String> geetCode) {
        verificationResult = geetCode;
        latch.countDown();
    }

    @Override
    public void notifyVerificationFailure() {
        verificationResult = null;
        latch.countDown();
    }

    @Override
    public synchronized Map<String, String> consumeResult() {
        Map<String, String> result = verificationResult;
        verificationResult = null;
        return result;
    }

    @Override
    public void savePendingChallenge(String gt, String challenge) {
        PendingRequest existing = pendingRequest;
        pendingRequest = new PendingRequest(gt, challenge, existing != null ? existing.headers : null);
    }

    @Override
    public String[] consumePendingChallenge() {
        PendingRequest req = pendingRequest;
        if (req != null && req.gt != null && req.challenge != null) {
            pendingRequest = new PendingRequest(null, null, req.headers);
            return new String[]{req.gt, req.challenge};
        }
        return null;
    }

    @Override
    public void savePendingHeaders(Map<String, String> headers) {
        PendingRequest existing = pendingRequest;
        Map<String, String> copied = headers != null ? new HashMap<>(headers) : null;
        pendingRequest = new PendingRequest(
                existing != null ? existing.gt : null,
                existing != null ? existing.challenge : null,
                copied);
    }

    @Override
    public Map<String, String> consumePendingHeaders() {
        PendingRequest req = pendingRequest;
        if (req != null && req.headers != null) {
            pendingRequest = new PendingRequest(req.gt, req.challenge, null);
            return req.headers;
        }
        return null;
    }

    /** 不可变快照：保证 gt/challenge/headers 复合操作的原子性 */
    private static final class PendingRequest {
        final String gt, challenge;
        final Map<String, String> headers;

        PendingRequest(String gt, String challenge, Map<String, String> headers) {
            this.gt = gt;
            this.challenge = challenge;
            this.headers = headers;
        }
    }
}
