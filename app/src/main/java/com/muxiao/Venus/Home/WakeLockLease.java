package com.muxiao.Venus.Home;

import android.content.Context;
import android.os.PowerManager;

/**
 * WakeLock 租约：单次窗口 {@link #DEFAULT_LEASE_MS}，每个账号边界续租一次。
 *
 * <p>非引用计数模式：acquire 即重置超时，release 即释放。
 */
public final class WakeLockLease {

    private static final String TAG = "Venus:TaskWakeLock";

    /** 单次租约窗口。 */
    public static final long DEFAULT_LEASE_MS = 5 * 60 * 1000L;

    private final PowerManager.WakeLock lock;
    private final long leaseMs;

    public WakeLockLease(Context context) {
        this(context, DEFAULT_LEASE_MS);
    }

    public WakeLockLease(Context context, long leaseMs) {
        PowerManager pm = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        this.lock = pm == null ? null
                : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        if (lock != null) lock.setReferenceCounted(false);
        this.leaseMs = Math.max(30_000L, leaseMs);
    }

    /** 首次获取租约。 */
    public void start() {
        acquire();
    }

    /** 续租：账号开始/结束时调用。 */
    public void renew() {
        acquire();
    }

    private void acquire() {
        if (lock == null) return;
        try {
            lock.acquire(leaseMs);
        } catch (RuntimeException e) {
            // 拿不到锁不影响正确性
            android.util.Log.w(TAG, "wake lock acquire failed: " + e.getMessage());
        }
    }

    /** 释放租约（幂等）。 */
    public void release() {
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) {
            // 已被系统回收，忽略
        }
    }
}
