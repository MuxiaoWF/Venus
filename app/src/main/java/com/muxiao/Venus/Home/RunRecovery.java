package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 手动运行链的中断恢复：把「本轮准备跑哪些账号的哪些任务」持久化成快照，
 * 进程被杀 / Activity 销毁 / 服务被回收后，回前台或冷启动时补排未完成项。
 *
 * <p>为什么需要快照：{@link TaskStatusManager} 只能表达「某任务当前是什么状态」，
 * 表达不出「这一轮本来打算跑哪些任务」。运行中途被杀时，已完成项是 completed、
 * 正在跑的是 in_progress、而<b>还没轮到的账号/任务仍是上一轮的 completed 或 pending</b>——
 * 单看状态无法区分「本轮已成功」与「本轮还没开始」。因此必须在开跑前记下目标清单，
 * 恢复时按清单反查状态，只补 pending / in_progress 的项。
 *
 * <p>与用户主动取消的边界：取消路径必须调 {@link #finish(Context)} 清快照；
 * 取消后留下的 cancelled 状态也会被恢复逻辑跳过（双保险）。
 *
 * <p>与定时链的关系：定时链本身由 WorkManager 自愈，不写本快照；本快照只覆盖
 * 首页前台单账号 / 批量、后台 Service 这三条手动入口。
 */
public final class RunRecovery {

    /** settings_prefs 下的快照键。 */
    private static final String PREF_SNAPSHOT = "manual_run_snapshot";

    /** 快照最长有效时间：超过一天视为陈旧（跨天由定时链负责补跑），直接丢弃。 */
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private static final Gson GSON = new Gson();

    private static final class Snapshot {
        long startedAt;
        String date;
        final Map<String, List<String>> targets = new LinkedHashMap<>();
    }

    private RunRecovery() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(Constants.Prefs.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String today(Context context) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    /** 开跑前记下本轮目标。已存在的旧快照直接覆盖：同一时刻只会有一轮手动运行。 */
    public static void start(Context context, Map<String, ? extends Collection<String>> targets) {
        if (targets == null || targets.isEmpty()) return;
        Snapshot snapshot = new Snapshot();
        snapshot.startedAt = System.currentTimeMillis();
        snapshot.date = today(context);
        for (Map.Entry<String, ? extends Collection<String>> e : targets.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue().isEmpty())
                continue;
            snapshot.targets.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        if (snapshot.targets.isEmpty()) return;
        // 必须同步落盘：这是「开跑前」的最后一道持久化，进程马上就要被任务占满，
        // apply() 的异步写盘可能在进程被杀时丢失，恢复时就找不到清单了。
        prefs(context).edit().putString(PREF_SNAPSHOT, GSON.toJson(snapshot)).apply();
    }

    /** 一轮正常结束或用户主动取消时调用：清掉快照，避免下次启动误判为中断。 */
    public static void finish(Context context) {
        prefs(context).edit().remove(PREF_SNAPSHOT).apply();
    }

    /**
     * 回前台 / 冷启动时自愈：存在快照且当前无运行态时，按快照反查状态并补排未完成项。
     *
     * <p>跳过 completed（已签过，不重复）、waiting_captcha（归验证码链路）、
     * cancelled / error（用户取消或任务自身失败，不在这里自动重试）。
     * 只补 pending 与 in_progress——后者正是进程死亡时的残留态。
     *
     * @return 是否补排过（调用方据此决定是否刷新界面）
     */
    public static boolean recover(Context context) {
        SharedPreferences sp = prefs(context);
        String raw = sp.getString(PREF_SNAPSHOT, "");
        if (raw.isEmpty()) return false;
        Snapshot snapshot;
        try {
            snapshot = GSON.fromJson(raw, Snapshot.class);
        } catch (RuntimeException e) {
            sp.edit().remove(PREF_SNAPSHOT).apply();
            return false;
        }
        if (snapshot == null || snapshot.targets == null || snapshot.targets.isEmpty()) {
            sp.edit().remove(PREF_SNAPSHOT).apply();
            return false;
        }
        // 跨天或过旧：定时链会按「当天必跑一次」补，这里的旧快照不再有意义
        boolean stale = !today(context).equals(snapshot.date)
                || System.currentTimeMillis() - snapshot.startedAt > MAX_AGE_MS
                || snapshot.startedAt <= 0;
        if (stale) {
            sp.edit().remove(PREF_SNAPSHOT).apply();
            return false;
        }
        // 仍有任一路在运行时不抢：等它结束后的下一次 show()/冷启动再处理，
        // 否则两路 TaskExecutor 同时写状态桶会互相覆盖。
        if (HomeFragment.isTaskRunning()
                || ForegroundTaskService.isRunning()
                || DailyTaskWorker.isRunning()) return false;

        TaskStatusManager statusManager = new TaskStatusManager(context);
        boolean queued = false;
        boolean first = true;
        for (Map.Entry<String, List<String>> e : snapshot.targets.entrySet()) {
            String user = e.getKey();
            if (user == null || user.isEmpty() || e.getValue() == null) continue;
            Map<String, String> statuses = statusManager.getAllStatuses(user);
            List<String> incomplete = new ArrayList<>();
            for (String id : e.getValue()) {
                if (id == null || id.isEmpty()) continue;
                String status = statuses.get(id);
                if (status == null) status = TaskStatusManager.STATUS_PENDING;
                if (TaskStatusManager.STATUS_COMPLETED.equals(status)
                        || TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(status)
                        || TaskStatusManager.STATUS_CANCELLED.equals(status)
                        || TaskStatusManager.STATUS_ERROR.equals(status)) continue;
                incomplete.add(id);
            }
            if (incomplete.isEmpty()) continue;
            tools.writeLog(context, "RunRecovery: resume " + incomplete.size()
                    + " task(s) for " + user + " after interrupted run");
            // 第一个账号 REPLACE 掉「明天到点」那个节点以便立即执行；其余账号追加到链尾串行跑
            DailyTaskScheduler.enqueueInterruptedResume(context, user, incomplete, first);
            first = false;
            queued = true;
        }
        // 同样同步清除：apply() 未落盘就再次回到前台时会把同一批 pending 项重复排一次续跑
        sp.edit().remove(PREF_SNAPSHOT).apply();
        return queued;
    }

    /** 供界面提示用的只读查询：是否存在未处理的中断快照。 */
    public static boolean hasPendingRecovery(Context context) {
        String raw = prefs(context).getString(PREF_SNAPSHOT, "");
        return !raw.isEmpty();
    }

}
