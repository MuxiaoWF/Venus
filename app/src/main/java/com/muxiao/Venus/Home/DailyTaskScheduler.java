package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.SCHEDULED_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SCHEDULED_TASK_TIME;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetReceiver;

import java.io.IOException;
import java.util.List;
import java.util.Calendar;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 每日定时签到调度（WorkManager 自链式 OneTimeWorkRequest）。
 *
 * <p>不用 PeriodicWorkRequest：周期任务的执行时机是「周期内的浮动窗口」，无法贴近用户设定时刻；
 * 自链式把 initialDelay 精确对齐到设定时刻，执行完由 {@link DailyTaskWorker} 以 {@link #CHAIN_POLICY}
 * 挂接下一次请求。进程死亡时 WorkManager 会自动重跑被中断的工作，链条因此自愈；冷启动再由
 * {@link #ensureScheduled(Context)} 以 KEEP 幂等兜底，覆盖「断链 + 长期不打开应用」的场景。
 *
 * <p>入口按策略分工：设置变更 {@link #applySchedule(Context)}（REPLACE 重建链条）；冷启动
 * {@link #ensureScheduled(Context)}（KEEP，链存活则不动）；执行器续链 {@link #enqueueNext}
 * （{@link #CHAIN_POLICY} = APPEND_OR_REPLACE，挂在当前工作之后，链尾已终态则另起新序列自愈）。
 */
public final class DailyTaskScheduler {

    /** 唯一工作名：调度、续链、修复共用同一名字，靠 ExistingWorkPolicy 区分语义。 */
    public static final String UNIQUE_WORK_NAME = "venus_daily_task_work";

    /** WorkData 键：同日重试次数（自链实现，不用 Result.retry，避免与续链互相干扰）。 */
    public static final String KEY_ATTEMPT = "scheduled_attempt";

    /**
     * WorkData 键：同日重试的部分执行名单（稳定任务 id 数组，见 {@link TaskIds}；缺省 = 全量执行）。
     * 验证码失败项不在名单内——它们不参与自动重试，改为通知用户手动处理。
     * <p>旧版本这里存的是本地化任务名（键名 {@code scheduled_only_names}）：跨版本读到旧值一律按全量执行，
     * 见 {@code DailyTaskWorker#resolveOnlyIds}。
     */
    public static final String KEY_ONLY_IDS = "scheduled_only_ids";

    /**
     * WorkData 键：本轮是否为「验证完成后的续跑」。
     * <p>续跑不看定时开关——它是用户刚刚手动完成验证后的收尾，与「定时任务是否开启」无关；
     * 缺省（false）仍受开关约束，保持定时链路原语义。
     */
    public static final String KEY_RESUME = "scheduled_resume";

    /**
     * WorkData 键：续跑轮的目标账号（{@link #KEY_RESUME} 为真时必带）。
     *
     * <p>为什么必须显式带：验证请求与凭证都按账号存放，续跑要重跑的正是<b>那个挂起账号</b>的任务。
     * 旧实现不传用户、Worker 用 {@code resolveTargetUser} 重新取「当前账号」——用户验证期间切过账号，
     * 续跑就会跑到别人的桶里，挂起项永远停在待验证。
     *
     * <p>旧版本入队的 resume 工作没有这个键：Worker 侧读到 null 时回退到 {@code resolveTargetUser}，
     * 保持升级瞬间在途工作的行为（而不是直接丢弃）。
     */
    public static final String KEY_RESUME_USER = "scheduled_resume_user";

    /** 当日错过后补跑的延迟（分钟）。 */
    private static final long CATCH_UP_DELAY_MINUTES = 15;

    /** 同日最大执行次数：首次 + 2 次重试（存在非验证码失败任务即触发重试）。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 同日重试的默认等待时间（分钟）：业务类/无法判类别的失败。 */
    public static final long RETRY_DELAY_MINUTES = 30;

    /** 网络类失败（超时/断连/DNS/SSL）的同日重试等待（分钟）：网络恢复通常很快，比默认间隔短。 */
    public static final long NETWORK_RETRY_DELAY_MINUTES = 10;

    /** 服务端限流（HTTP 429）的同日重试等待（分钟）：等限流窗口过去，比默认间隔长。 */
    public static final long RATE_LIMIT_RETRY_DELAY_MINUTES = 60;

    /** 手动运行占用时的复查等待（分钟）：手动轮结束后短延时复查，保住当日兜底。 */
    public static final long MANUAL_BUSY_RECHECK_DELAY_MINUTES = 5;

    /**
     * 挂起等待人机验证时的兜底续跑间隔（分钟）：用户一直不点验证通知时也留一次机会，
     * 避免当轮停在「待验证」直到次日。用户完成验证后由 {@link #enqueueCaptchaResume} 立即触发，不必等它。
     */
    public static final long CAPTCHA_RETRY_DELAY_MINUTES = 20;

    /**
     * 续链策略用 {@code APPEND_OR_REPLACE} 而非 {@code APPEND}：APPEND 把新工作挂到同名链尾的全部叶子并继承其终态，
     * 某叶子处于 CANCELLED/FAILED（被系统停止、取消、被 REPLACE 打断）时，APPEND 出的工作会一并被判终态而永不执行
     * ——链条静默死亡，当天再无信号，只能等下次冷启动修复。APPEND_OR_REPLACE 探测到叶子终态就另起新序列，
     * 即「续链失败自愈」，正常路径行为与 APPEND 一致（WorkManager ≥ 2.6）。
     */
    static final ExistingWorkPolicy CHAIN_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE;

    private static final String DEFAULT_TIME = "07:30";

    private DailyTaskScheduler() {
    }

    /** 定时签到开关是否已开启（读 settings_prefs，与 TaskSettings 同源直读）。 */
    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(SCHEDULED_TASK_ENABLED, false);
    }

    /** 读取设定的执行时刻，返回 {hour, minute}；未设置/损坏时回落 07:30。 */
    public static int[] getConfiguredTime(Context context) {
        String raw = prefs(context).getString(SCHEDULED_TASK_TIME, DEFAULT_TIME);
        int hour = 7, minute = 30;
        if (raw.matches("\\d{1,2}:\\d{2}")) {
            try {
                String[] parts = raw.split(":");
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                if (h >= 0 && h < 24 && m >= 0 && m < 60) {
                    hour = h;
                    minute = m;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new int[]{hour, minute};
    }

    /**
     * 设置变更后的统一入口：开启则按设定时刻重建调度（REPLACE，会取消执行中的旧链），关闭则取消全部待执行工作。
     */
    public static void applySchedule(Context context) {
        if (isEnabled(context)) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE, delayToNextOccurrence(context));
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
        }
    }

    /** 关闭定时签到：取消待执行工作（执行中的工作由 Worker 通过开关短路自行收尾）。 */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    /**
     * 进程冷启动修复（KEEP 幂等：链存活则不动作）。断链重建按「当天必跑一次」：时刻未到 → 今天到点；
     * 时刻已过且当日未全部完成 → 15 分钟后补跑；已完成 → 明天到点。
     */
    public static void ensureScheduled(Context context) {
        if (!isEnabled(context)) return;
        enqueueNext(context, ExistingWorkPolicy.KEEP, computeRepairDelay(context));
    }

    /**
     * 排下一次工作（全量执行，attempt 从 1 起算）。所有入口共用：constraints 保证有网才跑，
     * 退避策略兜底进程死亡重跑。
     *
     * @param policy 设置变更 REPLACE / 冷启动修复 KEEP / 执行器续链 {@link #CHAIN_POLICY}
     */
    static void enqueueNext(Context context, ExistingWorkPolicy policy, long delay) {
        enqueueNext(context, policy, delay, 1, null);
    }

    /** 排下一次工作；onlyIds 非空时写入部分执行名单（同日重试只重跑非验证码失败项）。 */
    static void enqueueNext(Context context, ExistingWorkPolicy policy, long delay,
                            int attempt, Set<String> onlyIds) {
        enqueue(context, policy, delay, attempt, onlyIds, false, null);
    }

    /**
     * 实际入队。
     *
     * @param resume         「验证完成后的续跑」标记，Worker 据此跳过定时开关的判断
     * @param resumeUserId   续跑目标账号（仅 resume 为真时写入）；为空表示旧版入队的工作
     */
    private static void enqueue(Context context, ExistingWorkPolicy policy, long delay,
                                int attempt, Set<String> onlyIds, boolean resume,
                                String resumeUserId) {
        Data.Builder data = new Data.Builder().putInt(KEY_ATTEMPT, attempt);
        if (resume) {
            data.putBoolean(KEY_RESUME, true);
            if (resumeUserId != null && !resumeUserId.isEmpty())
                data.putString(KEY_RESUME_USER, resumeUserId);
        }
        if (onlyIds != null && !onlyIds.isEmpty())
            data.putStringArray(KEY_ONLY_IDS, onlyIds.toArray(new String[0]));
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DailyTaskWorker.class)
                .setInitialDelay(Math.max(0, delay), TimeUnit.MILLISECONDS)
                .setInputData(data.build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request);
    }

    /**
     * 用户完成人机验证后触发一次立即续跑：只重跑<b>该账号</b>挂起过的任务，凭证由 {@code CaptchaStore} 注入请求头。
     *
     * <p>正常情况下用 {@code REPLACE} 而非 {@link #CHAIN_POLICY}：当前链尾是「明天到点」那一个节点，
     * APPEND 会留下两个待执行节点（立即续跑 + 明天），而续跑轮结束后又会自己挂一个明天，导致次日双跑。
     * REPLACE 把它替换成立即执行，链恢复单节点，Worker 结束后照常挂下次。
     *
     * <p><b>例外</b>：定时 Worker 正在跑时不能 REPLACE——它就在这条唯一链上，REPLACE 会取消它自己，
     * 正在执行的签到被 {@code isStopped()} 短路成「已取消」。这种情形改为追加到链尾，它那轮结束后立刻接着跑。
     *
     * @param userId 验证归属的账号：必须原样传到 Worker，续跑轮据此定位状态桶与凭证（不能由 Worker 自行取当前账号）
     */
    public static void enqueueCaptchaResume(Context context, String userId,
                                            java.util.Collection<String> taskIds) {
        Set<String> onlyIds = taskIds == null || taskIds.isEmpty()
                ? null : new java.util.LinkedHashSet<>(taskIds);
        if (DailyTaskWorker.isRunning()) {
            tools.writeLog(context, "captcha resume appended: scheduled worker still running (" + userId + ")");
            enqueue(context, CHAIN_POLICY, TimeUnit.SECONDS.toMillis(10), 1, onlyIds, true, userId);
            return;
        }
        // 前台轮/后台服务在跑时不丢弃，改为短延迟入队（旧实现直接 return）：验证刚完成时那一轮通常正处在
        // 收尾，丢掉续跑会让用户看着「待验证」白等兜底的 20 分钟。
        // 同时把前台轮计入判断：它虽然不在 WorkManager 里（REPLACE 不会取消它），但 Worker 起来后会因
        // 「手动运行占用」被挡回并 15 秒重排，0 延迟入队只是白启一次 Worker。
        long delay = (ForegroundTaskService.isRunning() || HomeFragment.isTaskRunning())
                ? TimeUnit.SECONDS.toMillis(10) : 0L;
        if (delay > 0)
            tools.writeLog(context, "captcha resume delayed " + delay + "ms: another round still running");
        // resume 标记让 Worker 跳过定时开关判断：用户刚完成验证，与此开关无关
        enqueue(context, ExistingWorkPolicy.REPLACE, delay, 1, onlyIds, true, userId);
    }

    /**
     * 续跑轮自身被占用时的短延迟重排：保留 resume 标记、目标账号与部分执行名单。
     * <p>不能用 {@link #enqueueNext(Context, ExistingWorkPolicy, long)}——它会丢掉这些 WorkData，
     * 把「只重跑该账号的挂起项」退化成「全量执行且受定时开关约束」。
     */
    static void enqueueResumeLater(Context context, String userId,
                                   java.util.Collection<String> taskIds, long delayMs) {
        Set<String> onlyIds = taskIds == null || taskIds.isEmpty()
                ? null : new java.util.LinkedHashSet<>(taskIds);
        enqueue(context, ExistingWorkPolicy.REPLACE, Math.max(0, delayMs), 1, onlyIds, true, userId);
    }

    /**
     * 挂起兜底续跑（Worker 自链专用）：与 {@link #enqueueResumeLater} 的唯一区别是用
     * {@link #CHAIN_POLICY} 追加到链尾而非 REPLACE——调用方正是链上正在运行的 Worker，
     * REPLACE 会把它自己取消掉。
     *
     * <p>同样必须带 resume 标记与目标账号：Worker 侧据此跳过「当日全完成」幂等检查，
     * 且 {@code resolveOnlyIds} 直接采信名单而不是按当前状态二次过滤（挂起项可能已被复位成待执行）。
     *
     * @param attempt 让 Worker 的重试额度继续累加，达到 {@link #MAX_ATTEMPTS} 后由它改排明天
     */
    static void enqueueCaptchaRetry(Context context, String userId,
                                    java.util.Collection<String> taskIds,
                                    long delayMs, int attempt) {
        Set<String> onlyIds = taskIds == null || taskIds.isEmpty()
                ? null : new java.util.LinkedHashSet<>(taskIds);
        enqueue(context, CHAIN_POLICY, Math.max(0, delayMs), attempt, onlyIds, true, userId);
    }

    /** 距「下一个设定时刻」的毫秒数（严格未来，不做当日补跑判断）。 */
    static long delayToNextOccurrence(Context context) {
        return delayToTarget(context, false);
    }

    /** 冷启动修复的延迟：今天时刻未到 → 到点执行；已过且当日未跑完 → 补跑；已跑完 → 明天到点。 */
    private static long computeRepairDelay(Context context) {
        return delayToTarget(context, true);
    }

    /** @param allowCatchUp true 时对「今日时刻已过 + 当日未全部完成 + 有可跑任务」返回补跑延迟 */
    private static long delayToTarget(Context context, boolean allowCatchUp) {
        // 仍按应用语言环境取设置与用户（用户可见文案、状态判定链路统一走同一 Context）
        Context localized = LocaleHelper.wrap(context.getApplicationContext());
        int[] time = getConfiguredTime(localized);
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, time[0]);
        target.set(Calendar.MINUTE, time[1]);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (now.before(target)) return target.getTimeInMillis() - now.getTimeInMillis();

        // 今日时刻已过
        if (allowCatchUp && shouldCatchUpToday(localized)) return TimeUnit.MINUTES.toMillis(CATCH_UP_DELAY_MINUTES);
        target.add(Calendar.DAY_OF_MONTH, 1);
        return target.getTimeInMillis() - now.getTimeInMillis();
    }

    /** 当日是否需要补跑：任务已配置、能确定用户、且未全部完成。 */
    private static boolean shouldCatchUpToday(Context context) {
        String userId = TaskWidgetReceiver.resolveTargetUser(context);
        if (userId.isEmpty()) return false;
        TaskSettings settings = TaskSettings.fromPreferences(context, userId);
        if (settings.hasAnyTaskDisabled()) return false;
        return !allEnabledTasksCompletedToday(context, userId);
    }

    /**
     * 当日启用的任务是否全部完成（幂等去重依据：手动跑过/上一轮已跑完则不再重复执行）。
     * 仅 {@code status == completed} 算完成，失败/进行中都不算。判定按稳定任务 id 进行，与语言无关。
     */
    public static boolean allEnabledTasksCompletedToday(Context context, String userId) {
        Context localized = LocaleHelper.wrap(context.getApplicationContext());
        // 该账号自己的任务集合：不同账号的任务列表不同，「全部完成」只能按各自配置判定
        TaskSettings settings = TaskSettings.fromPreferences(localized, userId);
        if (settings.hasAnyTaskDisabled()) return true;
        List<String> taskIds = settings.getTaskIds();
        if (taskIds.isEmpty()) return true;
        TaskStatusManager statusManager = new TaskStatusManager(localized);
        for (String taskId : taskIds) {
            if (!TaskStatusManager.STATUS_COMPLETED.equals(statusManager.getStatus(userId, taskId)))
                return false;
        }
        return true;
    }

    /**
     * 按失败类别给同日重试的等待分钟：429 &gt; 默认 &gt; 网络类。判定走异常类型链（{@code tools.normalizeRequestError}
     * 归一化网络异常时保留 cause），不解析本地化文案；判不出类别时回落默认间隔。
     */
    static long retryDelayMinutes(Throwable cause) {
        boolean network = false;
        Throwable t = cause;
        while (t != null) {
            if (t instanceof tools.HttpStatusCodeException
                    && ((tools.HttpStatusCodeException) t).getStatusCode() == 429)
                return RATE_LIMIT_RETRY_DELAY_MINUTES;
            if (t instanceof IOException) network = true;
            t = t.getCause() == t ? null : t.getCause();
        }
        return network ? NETWORK_RETRY_DELAY_MINUTES : RETRY_DELAY_MINUTES;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
    }
}
