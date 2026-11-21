package com.muxiao.Venus.Home;

import android.content.Context;
import android.os.SystemClock;

import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 多账号串行调度：按给定顺序逐个账号执行，账号间插入冷却。
 *
 * <p>单账号异常记录后继续，取消则中断。调用方：{@link ForegroundTaskService}、{@link HomeFragment}。
 */
public final class MultiUserRunner {

    private static final String TAG = "MultiUserRunner";

    /** 账号间默认冷却（毫秒）。 */
    public static final long DEFAULT_GAP_MS = 3000L;

    /** 单个账号的执行单元，由调用方实现（各入口的进度上报、验证码处理、状态持久化都不同）。 */
    public interface Step {
        /**
         * 执行一个账号的全部启用任务。
         *
         * @param userId 目标账号
         * @param index  本账号在批次中的下标（0 基）
         * @param total  批次账号总数
         */
        void runAccount(String userId, int index, int total);
    }

    private MultiUserRunner() {
    }

    /**
     * 串行跑完一批账号。
     *
     * @param users  目标账号，顺序即执行顺序（调用方通常把「当前账号」排在最前）
     * @param gapMs  账号间冷却毫秒；{@code <= 0} 表示不等待
     * @param step   单账号执行单元
     * @param cancel 取消判定：返回 true 时不再启动后续账号（已启动的账号由 Step 自己中断）
     * @return 实际执行过的账号数（不含被取消跳过的）
     */
    public static int run(Context context, List<String> users, long gapMs,
                          Step step, CancelCheck cancel) {
        if (users == null || users.isEmpty()) return 0;
        int executed = 0;
        for (int i = 0; i < users.size(); i++) {
            if (cancel != null && cancel.isCancelled()) {
                tools.writeLog(context, TAG + ": batch cancelled at " + i + "/" + users.size());
                break;
            }
            String userId = users.get(i);
            if (userId == null || userId.isEmpty()) continue;
            if (i > 0 && gapMs > 0) {
                // 冷却期间轮询取消标志
                long until = SystemClock.elapsedRealtime() + gapMs;
                while (SystemClock.elapsedRealtime() < until) {
                    if (cancel != null && cancel.isCancelled()) break;
                    SystemClock.sleep(200);
                }
                if (cancel != null && cancel.isCancelled()) break;
            }
            tools.writeLog(context, TAG + ": account " + (i + 1) + "/" + users.size()
                    + " start (" + userId + ")");
            try {
                step.runAccount(userId, i, users.size());
                executed++;
            } catch (RuntimeException e) {
                // 单账号异常不中断整批
                Logger.e(TAG + ": account failed, continue: " + userId, e);
                tools.writeLog(context, TAG + ": account " + userId + " aborted: " + e.getMessage());
            }
        }
        tools.writeLog(context, TAG + ": batch finished, " + executed + "/" + users.size() + " account(s)");
        return executed;
    }

    /** 取消判定（函数式，避免调用方为它建一个类）。 */
    @FunctionalInterface
    public interface CancelCheck {
        boolean isCancelled();
    }

    /** 便捷方法：跑全部账号、使用默认冷却。 */
    public static int run(Context context, List<String> users, Step step, CancelCheck cancel) {
        return run(context, users, DEFAULT_GAP_MS, step, cancel);
    }

    /** 过滤掉四类任务全关的账号，保持入参顺序。 */
    public static List<String> filterRunnable(Context context, List<String> users) {
        List<String> result = new ArrayList<>();
        if (users == null) return result;
        for (String userId : users) {
            if (userId == null || userId.isEmpty()) continue;
            if (TaskSettings.fromPreferences(context, userId).hasAnyTaskDisabled()) continue;
            result.add(userId);
        }
        return result;
    }
}
