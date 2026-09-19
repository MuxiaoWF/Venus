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

import com.muxiao.Venus.common.HttpStatusCodeException;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetReceiver;

import java.io.IOException;
import java.util.List;
import java.util.Calendar;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 每日定时签到调度器（WorkManager 实现）。
 *
 * <p>为什么用「自链式 OneTimeWorkRequest」而不用 PeriodicWorkRequest：
 * 周期任务的执行时机是「周期内的浮动窗口」，无法贴近用户设定的时刻；自链式把
 * initialDelay 精确对齐到设定时刻，执行完毕后由 {@link DailyTaskWorker} 在结尾
 * 以 {@link ExistingWorkPolicy#APPEND} 挂接下一次请求。进程死亡时 WorkManager 会
 * 自动重跑被中断的工作，链条因此自愈；应用冷启动时 {@link #ensureScheduled(Context)}
 * 再以 KEEP 幂等兜底，覆盖「断链 + 长期不打开应用」的场景。</p>
 *
 * <p>调度入口按策略分工：
 * <ul>
 *   <li>设置开关/时间变更 → {@link #applySchedule(Context)}（REPLACE，重建链条）；</li>
 *   <li>进程冷启动 → {@link #ensureScheduled(Context)}（KEEP，链存活则不动）；</li>
 *   <li>执行器结尾续链 → {@link #enqueueNext(Context, ExistingWorkPolicy, long, int)}
 *       （APPEND，挂在当前工作之后，不会自取消）。</li>
 * </ul></p>
 */
public final class DailyTaskScheduler {

    /** 唯一工作名：调度、续链、修复共用同一名字，靠 ExistingWorkPolicy 区分语义。 */
    public static final String UNIQUE_WORK_NAME = "venus_daily_task_work";

    /** WorkData 键：同日重试次数（自链实现，不用 Result.retry，避免与续链互相干扰）。 */
    public static final String KEY_ATTEMPT = "scheduled_attempt";

    /**
     * WorkData 键：同日重试的部分执行名单（本地化任务名数组；缺省 = 全量执行）。
     * 验证码失败项不在名单内——它们不参与自动重试，改为通知用户手动处理。
     */
    public static final String KEY_ONLY_NAMES = "scheduled_only_names";

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
        if (raw != null && raw.matches("\\d{1,2}:\\d{2}")) {
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
     * 设置变更后的统一入口：开启则按设定时刻重建调度（REPLACE，会取消执行中的旧链），
     * 关闭则取消全部待执行工作。
     */
    public static void applySchedule(Context context) {
        if (isEnabled(context)) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE, delayToNextOccurrence(context), 1);
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
        }
    }

    /** 关闭定时签到：取消待执行工作（执行中的工作由 Worker 通过开关短路自行收尾）。 */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    /**
     * 进程冷启动修复（KEEP 幂等：链存活则不动作）。断链重建时按「当天必跑一次」口径：
     * 时刻未到 → 今天到点；时刻已过且当日未全部完成 → 15 分钟后补跑；已完成 → 明天到点。
     */
    public static void ensureScheduled(Context context) {
        if (!isEnabled(context)) return;
        enqueueNext(context, ExistingWorkPolicy.KEEP, computeRepairDelay(context), 1);
    }

    /**
     * 排下一次工作（全量执行）。所有入口共用：constraints 保证有网才跑，退避策略兜底进程死亡重跑。
     *
     * @param policy 设置变更 REPLACE / 冷启动修复 KEEP / 执行器续链 APPEND
     * @param delay  距执行的毫秒数
     * @param attempt 本次链路上的第几次执行（用于同日重试上限）
     */
    static void enqueueNext(Context context, ExistingWorkPolicy policy, long delay, int attempt) {
        enqueueNext(context, policy, delay, attempt, null);
    }

    /**
     * 排下一次工作；onlyNames 非空时写入部分执行名单（同日重试只重跑非验证码失败项）。
     */
    static void enqueueNext(Context context, ExistingWorkPolicy policy, long delay,
                            int attempt, Set<String> onlyNames) {
        Data.Builder data = new Data.Builder().putInt(KEY_ATTEMPT, attempt);
        if (onlyNames != null && !onlyNames.isEmpty())
            data.putStringArray(KEY_ONLY_NAMES, onlyNames.toArray(new String[0]));
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

    /** 距「下一个设定时刻」的毫秒数（严格未来，不做当日补跑判断）。 */
    static long delayToNextOccurrence(Context context) {
        return delayToTarget(context, false);
    }

    /**
     * 冷启动修复的延迟：今天时刻未到 → 到点执行；已过且当日未跑完 → 补跑；
     * 已跑完 → 明天到点。
     */
    private static long computeRepairDelay(Context context) {
        return delayToTarget(context, true);
    }

    /**
     * @param allowCatchUp true 时对「今日时刻已过 + 当日未全部完成 + 有可跑任务」返回补跑延迟
     */
    private static long delayToTarget(Context context, boolean allowCatchUp) {
        // 统一使用应用语言环境：任务名等持久化键与语言相关
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
        if (userId == null || userId.isEmpty()) return false;
        TaskSettings settings = TaskSettings.fromPreferences(context);
        if (settings.hasAnyTaskDisabled()) return false;
        return !allEnabledTasksCompletedToday(context, userId);
    }

    /**
     * 当日启用的任务是否全部已完成（幂等去重依据：手动跑过/上一轮已跑完则不再重复执行）。
     * 仅统计 {@code status == completed}，失败/进行中都不算完成。
     */
    public static boolean allEnabledTasksCompletedToday(Context context, String userId) {
        Context localized = LocaleHelper.wrap(context.getApplicationContext());
        TaskSettings settings = TaskSettings.fromPreferences(localized);
        if (settings.hasAnyTaskDisabled()) return true;
        List<String> names = settings.getTaskNames(localized);
        if (names.isEmpty()) return true;
        TaskStatusManager statusManager = new TaskStatusManager(localized);
        for (String name : names) {
            if (!TaskStatusManager.STATUS_COMPLETED.equals(statusManager.getStatus(userId, name)))
                return false;
        }
        return true;
    }

    /**
     * 按失败类别给出同日重试的等待分钟数：HTTP 429 &gt; 默认 &gt; 网络类。
     * 判定走异常类型链（{@code tools.normalizeRequestError} 归一化网络异常时保留 cause），
     * 不解析本地化文案；判定不出类别时回落默认间隔。
     */
    static long retryDelayMinutes(Throwable cause) {
        boolean network = false;
        Throwable t = cause;
        while (t != null) {
            if (t instanceof HttpStatusCodeException
                    && ((HttpStatusCodeException) t).getStatusCode() == 429)
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
