package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import androidx.core.app.NotificationCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;
import com.muxiao.Venus.widget.TaskWidgetReceiver;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日定时签到执行器：WorkManager 到点拉起，在 Worker 线程内直接驱动
 * {@link TaskExecutor}（与手动后台运行共用执行链路），通过普通通知展示进度，
 * 状态照常写入 {@link TaskStatusManager}（小组件/首页自动联动）。
 *
 * <p>为什么不在 Worker 里拉起 {@link ForegroundTaskService}：Android 12+ 限制
 * 从后台启动前台服务，定时触发时进程通常不在前台，直接 startForegroundService
 * 会抛 ForegroundServiceStartNotAllowedException。Worker 本身由 JobScheduler
 * 调度、系统在执行期间持有 WakeLock，无需自持；单轮任务与手动运行一样控制在
 * 10 分钟内（多任务风控延时叠加的上限），超出该量级才需要长运行 Worker 方案。</p>
 *
 * <p>续链契约：doWork 结束前必须以 {@link androidx.work.ExistingWorkPolicy#APPEND}
 * 挂接下一次请求（同日重试或明日到点），否则链条断裂只能靠冷启动修复。关闭开关的
 * 场景由 isEnabled 短路：不再续链，链条自然终止。同日重试只重跑非验证码失败项——
 * 验证码失败需人工介入，自动重试大概率再次超时，改为通知用户手动处理。</p>
 */
public class DailyTaskWorker extends Worker {

    private static final String TAG = "DailyTaskWorker";

    /** 进程内「定时任务执行中」标记：手动入口（首页按钮/小组件）据此防双重并发。 */
    private static volatile boolean sRunning = false;

    /** 是否有定时签到任务正在执行（供手动入口并发防护）。 */
    public static boolean isRunning() {
        return sRunning;
    }

    public DailyTaskWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        sRunning = true;
        try {
            return runScheduledTasks();
        } finally {
            sRunning = false;
        }
    }

    private Result runScheduledTasks() {
        Context appContext = getApplicationContext();
        // 包裹语言环境：任务名/通知文案等本地化字符串须与手动运行一致
        Context context = LocaleHelper.wrap(appContext);
        int attempt = getInputData().getInt(DailyTaskScheduler.KEY_ATTEMPT, 1);

        // 开关已关闭（设置变更会取消工作，此处兜底）：不再续链
        if (!DailyTaskScheduler.isEnabled(appContext)) {
            tools.writeLog(context, TAG + ": scheduled run skipped (disabled)");
            return Result.success();
        }

        // 与手动入口互斥：前台/后台任务在跑则短延时后复查而非直接排明天——
        // 手动轮结束（含失败收场）后仍能当日兜底；若手动轮已跑完全部任务，
        // 下方幂等检查会直接排明天，复查本身不产生多余执行
        if (ForegroundTaskService.isRunning() || HomeFragment.isTaskRunning()) {
            tools.writeLog(context, TAG + ": manual run active, recheck in "
                    + DailyTaskScheduler.MANUAL_BUSY_RECHECK_DELAY_MINUTES + " min");
            DailyTaskScheduler.enqueueNext(context,
                    ExistingWorkPolicy.APPEND,
                    TimeUnit.MINUTES.toMillis(DailyTaskScheduler.MANUAL_BUSY_RECHECK_DELAY_MINUTES), 1);
            return Result.success();
        }

        String userId = TaskWidgetReceiver.resolveTargetUser(context);
        TaskSettings settings = TaskSettings.fromPreferences(context);
        if (userId.isEmpty() || settings.hasAnyTaskDisabled()) {
            tools.writeLog(context, TAG + ": no user or no task enabled, skip this round");
            DailyTaskScheduler.enqueueNext(context,
                    ExistingWorkPolicy.APPEND,
                    delayToNextOccurrence(), 1);
            return Result.success();
        }

        // 同日重试的部分执行名单：只重跑仍处于 ERROR 状态的项（用户手动修复过的自动剔除）
        Set<String> onlyNames = resolveOnlyNames(context, userId);
        if (onlyNames != null && onlyNames.isEmpty()) {
            // 名单内失败项已全部被手动修复：无需重跑，直接排明天
            tools.writeLog(context, TAG + ": retry list all resolved, skip this round");
            DailyTaskScheduler.enqueueNext(context, ExistingWorkPolicy.APPEND,
                    delayToNextOccurrence(), 1, null);
            return Result.success();
        }

        // 幂等去重：当日启用任务已全部完成（手动已跑/上轮已跑完）则不重复执行
        if (DailyTaskScheduler.allEnabledTasksCompletedToday(context, userId)) {
            tools.writeLog(context, TAG + ": all tasks completed today, skip this round");
            DailyTaskScheduler.enqueueNext(context,
                    ExistingWorkPolicy.APPEND,
                    delayToNextOccurrence(), 1);
            return Result.success();
        }

        tools.writeLog(context, context.getString(R.string.task_mgr_start, userId)
                + " (" + TAG + " attempt " + attempt + ")");

        // 新一轮运行口径与手动运行一致：复位瞬态、保留当日终态、记录运行用户
        TaskStatusManager statusManager = new TaskStatusManager(context);
        statusManager.setCurrentUser(userId);
        statusManager.resetTransientStatuses(userId, settings.getTaskNames(context));
        TaskWidgetProvider.refreshAllWidgets(appContext);
        tools.writeLogSeparator(context);

        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(message -> tools.writeLog(context, message));
        Notification notificationHelper = new Notification(context);
        NotificationCompat.Builder progressBuilder = notificationHelper
                .createProgressNotification("Venus", context.getString(R.string.notif_executing_task));

        int totalTasks = settings.getTaskNames(context).size();
        AtomicInteger completedTasks = new AtomicInteger(0);
        // 本轮以 ERROR 告终的任务名（多任务并行回调，用并发安全集合）
        Set<String> errorNames = new CopyOnWriteArraySet<>();
        // 各失败任务按异常类别判定的重试等待分钟数（多任务并行回调，用并发安全 Map）
        Map<String, Long> retryDelayHints = new ConcurrentHashMap<>();
        // 控制器实例需在执行后读取「验证码失败任务」，提取为局部变量
        BackgroundGeetestController bgController = new BackgroundGeetestController(context, notifier);

        TaskExecutor taskExecutor = new TaskExecutor(
                context, userId, notifier,
                bgController,
                new TaskExecutor.Callback() {
                    @Override
                    public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                        // 定时链路是写入方：持久化 + 刷小组件 + 广播（首页只同步 UI 快照）
                        statusManager.applyStatus(userId, taskName, status);
                        TaskWidgetProvider.refreshAllWidgets(appContext);
                        broadcastTaskStatus(appContext, userId, taskName, status);
                        if (status == TaskItem.TaskStatus.COMPLETED) completedTasks.incrementAndGet();
                        if (status == TaskItem.TaskStatus.ERROR) errorNames.add(taskName);
                        updateProgressNotification(context, notificationHelper, progressBuilder,
                                taskName, status, completedTasks.get(), totalTasks);
                    }

                    @Override
                    public void onAllTasksCompleted() {
                        notificationHelper.completeProgressNotification("Venus",
                                context.getString(R.string.notif_all_tasks_done));
                    }

                    @Override
                    public void onError(String message) {
                        // 定时运行用户不在场：错误必须强制可见（同一通知 ID 互相覆盖不堆积）
                        notificationHelper.sendErrorNotification(
                                context.getString(R.string.notif_task_failed), message, true);
                    }

                    @Override
                    public void onTaskError(String taskName, String message, Throwable cause) {
                        onError(message);
                        // 按失败类别（HTTP 429 / 网络类 / 其他）记录重试等待，供续链决策取最保守值
                        retryDelayHints.put(taskName, DailyTaskScheduler.retryDelayMinutes(cause));
                    }

                    @Override
                    public boolean isCancelled() {
                        // 设置关闭/系统取消 → WorkManager 置 stopped，TaskExecutor 据此收束子任务
                        return isStopped();
                    }
                });

        boolean executed = false;
        try {
            taskExecutor.executeAll(settings, onlyNames);
            executed = true;
        } catch (RuntimeException e) {
            // 仅提交阶段可能同步抛错（如线程池拒绝）；任务体内异常已由 Callback 上报
            tools.writeLog(context, TAG + ": executeAll failed: " + e.getMessage());
            notificationHelper.sendErrorNotification(
                    context.getString(R.string.notif_task_failed),
                    e.getMessage() != null ? e.getMessage() : e.toString(), true);
        } finally {
            // 被取消/异常收尾时进度通知不会经完成回调置换，这里兜底撤销，避免常驻
            if (!executed || isStopped())
                notificationHelper.cancelProgressNotification();
        }

        // 续链决策：验证码失败不自动重试——用户不在场时重试大概率再次超时，改为发通知
        // 告知用户手动处理（首页「重试失败任务」按钮；次日到点的定时执行仍会再试）。
        // 其余失败（网络等瞬时故障）在重试额度内只重跑这些项（部分执行名单随 WorkData 传递）。
        if (DailyTaskScheduler.isEnabled(appContext)) {
            if (!executed) {
                // 任务体整体未执行（提交阶段异常）：按全量重试处理
                if (attempt < DailyTaskScheduler.MAX_ATTEMPTS)
                    DailyTaskScheduler.enqueueNext(context, ExistingWorkPolicy.APPEND,
                            DailyTaskScheduler.RETRY_DELAY_MINUTES * 60_000L, attempt + 1, null);
                else
                    DailyTaskScheduler.enqueueNext(context, ExistingWorkPolicy.APPEND,
                            delayToNextOccurrence(), 1, null);
            } else {
                Set<String> retryable = new HashSet<>(errorNames);
                retryable.removeAll(bgController.getCaptchaFailedTasks());
                if (!retryable.isEmpty() && attempt < DailyTaskScheduler.MAX_ATTEMPTS) {
                    long retryDelayMinutes = retryDelayMinutesFor(retryable, retryDelayHints);
                    tools.writeLog(context, TAG + ": " + retryable.size() + " task(s) failed, retry in "
                            + retryDelayMinutes + " min");
                    DailyTaskScheduler.enqueueNext(context, ExistingWorkPolicy.APPEND,
                            retryDelayMinutes * 60_000L, attempt + 1, retryable);
                } else {
                    if (!bgController.getCaptchaFailedTasks().isEmpty()) {
                        notificationHelper.sendErrorNotification(
                                context.getString(R.string.notif_captcha_no_retry_title),
                                context.getString(R.string.notif_captcha_no_retry_fmt,
                                        TextUtils.join(", ", bgController.getCaptchaFailedTasks())),
                                true);
                    }
                    DailyTaskScheduler.enqueueNext(context, ExistingWorkPolicy.APPEND,
                            delayToNextOccurrence(), 1, null);
                }
            }
        }

        return Result.success();
    }

    /**
     * 同日重试的等待分钟数：取失败名单内各项判定结果的最保守值——
     * 未判到类别的项按默认间隔计，避免混合类别时用偏短的间隔
     * 提前撞上尚未恢复的故障（如限流窗口未过）。
     */
    private static long retryDelayMinutesFor(Set<String> retryable, Map<String, Long> hints) {
        long max = 0;
        for (String name : retryable) {
            Long hint = hints.get(name);
            long minutes = hint != null ? hint : DailyTaskScheduler.RETRY_DELAY_MINUTES;
            if (minutes > max) max = minutes;
        }
        return max;
    }

    /**
     * 解析同日重试的部分执行名单：上一轮经 WorkData 传入，只保留当前仍处于
     * ERROR 状态的项。无名单（首跑/全量重试）返回 {@code null} 表示全量执行。
     */
    private Set<String> resolveOnlyNames(Context context, String userId) {
        String[] raw = getInputData().getStringArray(DailyTaskScheduler.KEY_ONLY_NAMES);
        if (raw == null || raw.length == 0) return null;
        Map<String, String> statuses = new TaskStatusManager(context).getAllStatuses(userId);
        Set<String> only = new HashSet<>();
        for (String name : raw)
            if (TaskStatusManager.STATUS_ERROR.equals(statuses.get(name))) only.add(name);
        return only;
    }

    /** 距下一个设定时刻的毫秒数（明天到点或同日时刻未到则今天到点）。 */
    private long delayToNextOccurrence() {
        // 复用调度器的私有计算：经包内静态方法转发
        return DailyTaskScheduler.delayToNextOccurrence(getApplicationContext());
    }

    /** 将单条任务状态广播给首页（与 ForegroundTaskService 同一 Action/Extras 契约）。 */
    private static void broadcastTaskStatus(Context context, String userId,
                                            String taskName, TaskItem.TaskStatus status) {
        Intent intent = new Intent(ForegroundTaskService.ACTION_TASK_STATUS_UPDATED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_USER_ID, userId);
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_NAME, taskName);
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_STATUS, status.name());
        context.sendBroadcast(intent);
    }

    /** 依据任务状态更新进度通知文案（与 ForegroundTaskService 的文案映射保持一致）。 */
    private static void updateProgressNotification(Context context, Notification helper,
                                                   NotificationCompat.Builder builder,
                                                   String taskName, TaskItem.TaskStatus status,
                                                   int progress, int max) {
        String statusText;
        switch (status) {
            case IN_PROGRESS:
                statusText = context.getString(R.string.notif_executing, taskName, progress, max);
                break;
            case COMPLETED:
                statusText = context.getString(R.string.notif_completed, taskName, progress, max);
                break;
            case ERROR:
                statusText = context.getString(R.string.notif_error, taskName);
                break;
            case WARNING:
                statusText = context.getString(R.string.notif_waiting_verification, taskName);
                break;
            case CANCELLED:
                statusText = context.getString(R.string.notif_cancelled);
                break;
            default:
                statusText = context.getString(R.string.notif_preparing);
                break;
        }
        helper.updateProgressNotification(builder, "Venus", statusText, progress, max);
    }
}
