package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每日定时签到执行器：WorkManager 到点拉起，在 Worker 线程内直接驱动 {@link TaskExecutor}
 * （与手动后台运行共用执行链路），用普通通知展示进度，状态照常写入 {@link TaskStatusManager}
 * （小组件/首页自动联动）。
 *
 * <p>不在 Worker 里拉起 {@link ForegroundTaskService}：Android 12+ 限制后台启动前台服务，定时触发时进程
 * 通常不在前台，startForegroundService 会抛 ForegroundServiceStartNotAllowedException；Worker 由
 * JobScheduler 调度、系统执行期间持有 WakeLock，无需自持。单轮与手动运行一样控制在 10 分钟内
 * （多任务风控延时叠加的上限），超出该量级才需要长运行 Worker 方案。
 *
 * <p>续链契约：doWork 结束前必须用 {@link DailyTaskScheduler#CHAIN_POLICY}（APPEND_OR_REPLACE）挂下一次
 * 请求（同日重试或明日到点），否则链条断裂只能靠冷启动修复；链尾已落入取消/失败终态时 APPEND_OR_REPLACE
 * 会另起新序列，避免后续续链被一并判终态而静默死亡。开关关闭时 isEnabled 短路不再续链，链条自然终止。
 * 同日重试只重跑非验证码失败项——验证码失败需人工介入，自动重试大概率再次超时，改为通知用户手动处理。
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

        // 开关已关闭（设置变更会取消工作，此处兜底）：不再续链。
        // 续跑轮例外：它是用户刚完成人机验证后的收尾，与「定时任务是否开启」无关。
        boolean resume = getInputData().getBoolean(DailyTaskScheduler.KEY_RESUME, false);
        if (!resume && !DailyTaskScheduler.isEnabled(appContext)) {
            tools.writeLog(context, TAG + ": scheduled run skipped (disabled)");
            return Result.success();
        }

        // 批量链：本轮只跑名单里的一个账号（用 getNullableStringArray：缺失键返回 null，
        // 与「有批量名单但为空」区分开；getStringArray 已废弃且会把两者都抹成空数组）
        String[] batchUsers = getInputData().getNullableStringArray(DailyTaskScheduler.KEY_USERS);
        int batchIndex = getInputData().getInt(DailyTaskScheduler.KEY_USER_INDEX, 0);
        boolean batch = batchUsers != null && batchUsers.length > 1;
        if (batch && batchIndex >= batchUsers.length) batchIndex = batchUsers.length - 1;
        // 链首开始时置批量标记
        if (batch && batchIndex == 0 && !DailyTaskScheduler.isBatchActive(context))
            DailyTaskScheduler.setBatchActive(context, true);

        // 本轮目标账号：续跑轮取 WorkData，缺省回落当前账号
        String resumeUser = resume
                ? getInputData().getString(DailyTaskScheduler.KEY_RESUME_USER) : null;
        String userId;
        if (batch) {
            userId = batchUsers[batchIndex];
        } else {
            userId = (resumeUser != null && !resumeUser.isEmpty())
                    ? resumeUser : TaskWidgetReceiver.resolveTargetUser(context);
        }

        // 挂起/排队期间账号可能已被用户删除：续跑名单来自 WorkData，绕过了 resolveTargetUser 的存在性校验。
        // 不拦住的话会给不存在的账号建孤儿任务配置（task_profile_*）与状态桶，并把那一轮记成失败。
        if (!userId.isEmpty() && !new com.muxiao.Venus.User.UserManager(context).isUserExists(userId)) {
            tools.writeLog(context, TAG + ": target user no longer exists, skip this round: " + userId);
            if (batch) return chainBatch(context, batchUsers, batchIndex + 1);
            DailyTaskScheduler.enqueueTomorrow(context);
            return Result.success();
        }

        // 与手动入口互斥：前台/后台任务在跑则短延时后复查而非直接排明天，手动轮结束（含失败收场）后
        // 仍能当日兜底；若手动轮已跑完全部任务，下方幂等检查会直接排明天，复查不产生多余执行。
        // 续跑轮改用秒级复查：它是用户刚完成验证后的收尾，那个「在跑」的轮次往往只剩收尾动作，
        // 按 5 分钟复查等于让用户盯着「待验证」干等（旧行为就是这样丢的）。
        if (ForegroundTaskService.isRunning() || HomeFragment.isTaskRunning()) {
            long delayMs = resume
                    ? TimeUnit.SECONDS.toMillis(15)
                    : TimeUnit.MINUTES.toMillis(DailyTaskScheduler.MANUAL_BUSY_RECHECK_DELAY_MINUTES);
            tools.writeLog(context, TAG + ": another round active, recheck in " + delayMs + "ms");
            if (batch) {
                // 批量链被手动轮占用：15 秒后重试同一账号
                DailyTaskScheduler.enqueueBatchNext(context, java.util.Arrays.asList(batchUsers),
                        batchIndex, TimeUnit.SECONDS.toMillis(15));
            } else if (resume) {
                // 必须保留 resume 标记、目标账号与名单，否则重排后退化成「全量执行 + 受定时开关约束」
                String[] rawIds = getInputData()
                        .getNullableStringArray(DailyTaskScheduler.KEY_ONLY_IDS);
                DailyTaskScheduler.enqueueResumeLater(context, userId,
                        rawIds == null ? null : java.util.Arrays.asList(rawIds), delayMs);
            } else {
                DailyTaskScheduler.enqueueNext(context,
                        DailyTaskScheduler.CHAIN_POLICY, delayMs);
            }
            return Result.success();
        }

        // 任务配置按账号：读本轮的运行账号，而不是全局配置
        TaskSettings settings = TaskSettings.fromPreferences(context, userId);
        if (userId.isEmpty() || settings.hasAnyTaskDisabled()) {
            tools.writeLog(context, TAG + ": no user or no task enabled, skip this round");
            if (batch) return chainBatch(context, batchUsers, batchIndex + 1);
            DailyTaskScheduler.enqueueTomorrow(context);
            return Result.success();
        }

        // 同日重试的部分执行名单：只重跑仍处于 ERROR 状态的项（用户手动修复过的自动剔除）
        Set<String> onlyIds = resolveOnlyIds(context, userId);
        // 续跑轮清理：名单里已从设置中移除的任务永远不会被执行，残留的「待验证」没人推进，
        // 只有把它复位成「待执行」才不会让列表永久卡在一个点不动的状态
        if (resume && onlyIds != null) dropDisabledTargets(context, userId, onlyIds, settings);
        if (onlyIds != null && onlyIds.isEmpty()) {
            // 名单内失败项已全部被手动修复：无需重跑，直接排明天
            tools.writeLog(context, TAG + ": retry list all resolved, skip this round");
            if (batch) return chainBatch(context, batchUsers, batchIndex + 1);
            DailyTaskScheduler.enqueueTomorrow(context);
            return Result.success();
        }

        // 幂等去重：当日启用任务已全部完成则不重复执行；续跑轮例外。
        // 批量链仅在链首做全局判定。
        if (!resume) {
            boolean alreadyDone = batch && batchIndex == 0
                    ? DailyTaskScheduler.allTargetsCompletedToday(context)
                    : DailyTaskScheduler.allEnabledTasksCompletedToday(context, userId);
            if (alreadyDone) {
                tools.writeLog(context, TAG + ": all tasks completed today, skip this round");
                if (batch) {
                    return batchIndex == 0
                            ? finishBatch(context)
                            : chainBatch(context, batchUsers, batchIndex + 1);
                }
                DailyTaskScheduler.enqueueTomorrow(context);
                return Result.success();
            }
        }

        tools.writeLog(context, context.getString(R.string.task_mgr_start, userId)
                + " (" + TAG + " attempt " + attempt + ")");

        // 新一轮运行口径与手动运行一致：复位瞬态、保留当日终态、记录运行用户
        TaskStatusManager statusManager = new TaskStatusManager(context);
        statusManager.setCurrentUser(userId);
        statusManager.resetTransientStatuses(userId, settings.getTaskIds());
        TaskWidgetProvider.refreshAllWidgets(appContext);
        tools.writeLogSeparator(context);

        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(message -> tools.writeLog(context, message));
        Notification notificationHelper = new Notification(context);
        NotificationCompat.Builder progressBuilder = notificationHelper
                .createProgressNotification("Venus", context.getString(R.string.notif_executing_task));

        int totalTasks = settings.getTaskIds().size();
        AtomicInteger completedTasks = new AtomicInteger(0);
        // 本轮以 ERROR 告终的任务 id（多任务并行回调，用并发安全集合）
        Set<String> errorIds = new CopyOnWriteArraySet<>();
        // 各失败任务按异常类别判定的重试等待分钟数（多任务并行回调，用并发安全 Map）
        Map<String, Long> retryDelayHints = new ConcurrentHashMap<>();
        // 因需人机验证而挂起的任务：轮末合并成一次验证请求（见 CaptchaSuspension）
        Set<String> captchaTaskIds = new CopyOnWriteArraySet<>();
        AtomicReference<Map<String, String>> captchaHeaders = new AtomicReference<>();
        // 控制器实例需在执行后读取「验证码失败任务」，提取为局部变量
        GeetestController bgController = new BackgroundGeetestController();

        TaskExecutor taskExecutor = new TaskExecutor(
                context, userId, notifier,
                bgController,
                new TaskExecutor.Callback() {
                    @Override
                    public void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status) {
                        // 定时链路是写入方：持久化 + 刷小组件 + 广播（首页只同步 UI 快照）
                        statusManager.applyStatus(userId, taskId, status);
                        TaskWidgetProvider.refreshAllWidgets(appContext);
                        broadcastTaskStatus(appContext, userId, taskId, status);
                        if (status == TaskItem.TaskStatus.COMPLETED) completedTasks.incrementAndGet();
                        if (status == TaskItem.TaskStatus.ERROR) errorIds.add(taskId);
                        updateProgressNotification(context, notificationHelper, progressBuilder,
                                taskId, status, completedTasks.get(), totalTasks);
                    }

                    @Override
                    public void onAllTasksCompleted() {
                        // 有挂起待验证的任务时本轮并未真正跑完：等续跑轮收尾再报完成
                        if (!captchaTaskIds.isEmpty()) return;
                        notificationHelper.completeProgressNotification("Venus",
                                context.getString(R.string.notif_all_tasks_done));
                    }

                    @Override
                    public void onCaptchaRequired(CaptchaRequiredException error) {
                        // 本轮不等人：只记下挂起项，轮末统一取一次挑战并落库
                        captchaTaskIds.add(error.getTaskId());
                        captchaHeaders.compareAndSet(null, error.getHeaders());
                    }

                    @Override
                    public void onError(String message) {
                        // 定时运行用户不在场：错误必须强制可见（同一通知 ID 互相覆盖不堆积）
                        notificationHelper.sendErrorNotification(
                                context.getString(R.string.notif_task_failed), message, true);
                    }

                    @Override
                    public void onTaskError(String taskId, String message, Throwable cause) {
                        onError(message);
                        // 按失败类别（HTTP 429 / 网络类 / 其他）记录重试等待，供续链决策取最保守值
                        retryDelayHints.put(taskId, DailyTaskScheduler.retryDelayMinutes(cause));
                    }

                    @Override
                    public boolean isCancelled() {
                        // 设置关闭/系统取消 → WorkManager 置 stopped，TaskExecutor 据此收束子任务
                        return isStopped();
                    }
                });

        boolean executed = false;
        // 本轮是否成功挂起到「等待用户验证」
        boolean suspended = false;
        try {
            taskExecutor.executeAll(settings, onlyIds);
            executed = true;
            // 轮末收口：把本轮挂起的人机验证需求合并成一次，落库并发通知，随后结束本轮。
            // 用户完成验证后凭证由 CaptchaStore 落库，下一轮续跑自动带上——Worker 不再等人。
            if (!captchaTaskIds.isEmpty()) {
                suspended = CaptchaSuspension.suspend(context, userId, captchaTaskIds,
                        captchaHeaders.get(), notificationHelper,
                        // 定时运行：用户不在场，通知是唯一的验证入口
                        true);
                if (!suspended) {
                    // 连挑战都取不到：挂起不成立，按失败处理并进入常规重试，避免停在「待验证」
                    for (String id : captchaTaskIds) {
                        statusManager.applyStatus(userId, id, TaskItem.TaskStatus.ERROR);
                        errorIds.add(id);
                    }
                    TaskWidgetProvider.refreshAllWidgets(appContext);
                }
            }
        } catch (RuntimeException e) {
            // 仅提交阶段可能同步抛错（如线程池拒绝）；任务体内异常已由 Callback 上报
            tools.writeLog(context, TAG + ": executeAll failed: " + e.getMessage());
            notificationHelper.sendErrorNotification(
                    context.getString(R.string.notif_task_failed),
                    e.getMessage() != null ? e.getMessage() : e.toString(), true);
        } finally {
            // 无条件撤销进度通知
            notificationHelper.cancelProgressNotification();
        }

        // 批量链：不逐账号重试，只排下一个账号
        if (batch) {
            if (suspended)
                tools.writeLog(context, TAG + ": account suspended for captcha, continue batch: " + userId);
            return chainBatch(context, batchUsers, batchIndex + 1);
        }

        // 续链决策：验证码失败不自动重试（用户不在场时大概率再次超时），改为发通知让用户手动处理
        // （首页「重试失败任务」；次日到点的定时执行仍会再试）。其余失败在重试额度内只重跑这些项（名单随 WorkData 传递）。
        if (DailyTaskScheduler.isEnabled(appContext)) {
            if (suspended) {
                // 已挂起等人：排一次兜底续跑（用户可能一直不点通知），额度内 20 分钟后再试。
                // 用户完成验证时前台会主动排一次立即续跑，与之互不冲突——都是同一条链上的新节点。
                // 必须带 resume 标记：丢掉它会让下一轮按「当前状态」二次过滤名单，而被上一轮
                // resetTransientStatuses 复位成 pending 的挂起项会被全部筛掉，续跑轮静默空跑。
                if (attempt < DailyTaskScheduler.MAX_ATTEMPTS)
                    DailyTaskScheduler.enqueueCaptchaRetry(context, userId, captchaTaskIds,
                            DailyTaskScheduler.CAPTCHA_RETRY_DELAY_MINUTES * 60_000L,
                            attempt + 1);
                else
                    DailyTaskScheduler.enqueueTomorrow(context);
            } else if (!executed) {
                // 任务体整体未执行（提交阶段异常）：按全量重试处理
                if (attempt < DailyTaskScheduler.MAX_ATTEMPTS)
                    DailyTaskScheduler.enqueueNext(context, DailyTaskScheduler.CHAIN_POLICY,
                            DailyTaskScheduler.RETRY_DELAY_MINUTES * 60_000L, attempt + 1, null);
                else
                    DailyTaskScheduler.enqueueTomorrow(context);
            } else {
                // 挂起项已在上面按 suspended 分支处理，这里只剩真正的失败项
                Set<String> retryable = new HashSet<>(errorIds);
                if (!retryable.isEmpty() && attempt < DailyTaskScheduler.MAX_ATTEMPTS) {
                    long retryDelayMinutes = retryDelayMinutesFor(retryable, retryDelayHints);
                    tools.writeLog(context, TAG + ": " + retryable.size() + " task(s) failed, retry in "
                            + retryDelayMinutes + " min");
                    DailyTaskScheduler.enqueueNext(context, DailyTaskScheduler.CHAIN_POLICY,
                            retryDelayMinutes * 60_000L, attempt + 1, retryable);
                } else {
                    DailyTaskScheduler.enqueueTomorrow(context);
                }
            }
        }

        return Result.success();
    }

    /**
     * 批量链续链：还有账号就排下一个，否则清标记并排明天。用 {@code CHAIN_POLICY} 追加。
     */
    private Result chainBatch(Context context, String[] users, int nextIndex) {
        if (nextIndex < users.length) {
            tools.writeLog(context, TAG + ": batch next account " + (nextIndex + 1) + "/" + users.length);
            DailyTaskScheduler.enqueueBatchNext(context, java.util.Arrays.asList(users), nextIndex,
                    MultiUserRunner.DEFAULT_GAP_MS);
        } else {
            tools.writeLog(context, TAG + ": batch finished, schedule next day");
            DailyTaskScheduler.finishBatch(context);
        }
        return Result.success();
    }

    /** 整批无事可做：清批量标记并排明天。 */
    private Result finishBatch(Context context) {
        DailyTaskScheduler.finishBatch(context);
        return Result.success();
    }

    /**
     * 同日重试的等待分钟数：取失败名单内各项判定结果的最保守值（未判到类别的按默认间隔计），
     * 避免混合类别时用偏短间隔提前撞上尚未恢复的故障（如限流窗口未过）。
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
     * 解析同日重试名单：上一轮经 WorkData 传入（稳定任务 id），只保留当前仍处于 ERROR 的项。
     * 无名单（首跑/全量重试）返回 {@code null} 表示全量执行。
     *
     * <p>旧版本写入的是本地化任务名：认不出的条目直接丢弃，全部丢弃时按全量执行——
     * 宁可多跑一轮，也不要因为跨版本键格式不同而静默跳过。
     */
    private Set<String> resolveOnlyIds(Context context, String userId) {
        String[] raw = getInputData().getNullableStringArray(DailyTaskScheduler.KEY_ONLY_IDS);
        if (raw == null || raw.length == 0) return null;
        boolean resume = getInputData().getBoolean(DailyTaskScheduler.KEY_RESUME, false);
        Map<String, String> statuses = new TaskStatusManager(context).getAllStatuses(userId);
        Set<String> only = new HashSet<>();
        boolean anyKnown = false;
        for (String id : raw) {
            if (!TaskIds.isKnown(id)) continue;
            anyKnown = true;
            String status = statuses.get(id);
            // 待验证项必须保留：它正是续跑轮要重跑的目标（验证已通过时直接成功，未通过会再次挂起）。
            // 只认 ERROR 会把它们全部过滤掉，续跑轮变成空跑。
            if (TaskStatusManager.STATUS_ERROR.equals(status)
                    || TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(status)) {
                only.add(id);
            } else if (resume) {
                // 续跑轮的 WorkData 名单本身就是权威（「验证已通过、待重跑」），不再按当前状态二次过滤：
                // 状态可能已被上一轮 reset 成 pending，按状态筛会把目标项全丢掉，续跑轮静默空跑
                only.add(id);
            }
        }
        return anyKnown ? only : null;
    }

    /**
     * 续跑轮清理：把名单里已不在当前设置中的任务移除，并把它们残留的「待验证」复位为「待执行」。
     *
     * <p>{@code TaskExecutor.executeAll} 只提交设置里启用的任务，名单里被关掉的项永远不会被执行，
     * 状态就永久停在「待验证」——点它还会反复弹出与目标无关的验证框。复位是唯一能让用户回到
     * 正常流程的收口。
     */
    private static void dropDisabledTargets(Context context, String userId,
                                            Set<String> onlyIds, TaskSettings settings) {
        Set<String> enabled = new HashSet<>(settings.getTaskIds());
        TaskStatusManager statusManager = new TaskStatusManager(context);
        for (String id : new ArrayList<>(onlyIds)) {
            if (enabled.contains(id)) continue;
            onlyIds.remove(id);
            statusManager.markStatus(userId, id, TaskStatusManager.STATUS_PENDING);
            tools.writeLog(context, TAG + ": captcha target dropped (no longer enabled): " + id);
        }
    }

    /** 将单条任务状态广播给首页（与 ForegroundTaskService 同一 Action/Extras 契约，载荷为任务 id）。 */
    private static void broadcastTaskStatus(Context context, String userId,
                                            String taskId, TaskItem.TaskStatus status) {
        Intent intent = new Intent(ForegroundTaskService.ACTION_TASK_STATUS_UPDATED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_USER_ID, userId);
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_ID, taskId);
        intent.putExtra(ForegroundTaskService.EXTRA_TASK_STATUS, status.name());
        context.sendBroadcast(intent);
    }

    /** 依据任务状态更新进度通知文案（与 ForegroundTaskService 的文案映射保持一致）。 */
    private static void updateProgressNotification(Context context, Notification helper,
                                                   NotificationCompat.Builder builder,
                                                   String taskId, TaskItem.TaskStatus status,
                                                   int progress, int max) {
        String taskName = TaskIds.displayName(context, taskId);
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
            case WAITING_CAPTCHA:
                // WARNING 是旧版「等待验证」的持久化值，两者文案一致
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
