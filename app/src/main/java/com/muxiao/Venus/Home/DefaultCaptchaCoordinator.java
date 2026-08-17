package com.muxiao.Venus.Home;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CaptchaCoordinator 的默认实现：以实例状态取代全局静态字段（P0-4）。
 * 行为与原 BackgroundGeetestController 的静态字段完全一致，状态收敛到单一协调者。
 */
public class DefaultCaptchaCoordinator implements CaptchaCoordinator {

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
        latch.await(5, TimeUnit.MINUTES);
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
    public void notifyVerificationFailure(String error) {
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
