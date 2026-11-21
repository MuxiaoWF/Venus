package com.muxiao.Venus.Home;

import dagger.hilt.android.AndroidEntryPoint;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;
import com.muxiao.Venus.common.AppExecutors;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

/**
 * 前台 Service：后台执行签到任务，持 WakeLock 防 CPU 休眠，经广播与 HomeFragment 同步任务状态，通知栏可取消。
 */
@AndroidEntryPoint
public class ForegroundTaskService extends Service {
    public static final String ACTION_START_TASK = "com.muxiao.Venus.START_TASK";
    public static final String ACTION_STOP_TASK = "com.muxiao.Venus.STOP_TASK";
    public static final String ACTION_TASK_STATE_CHANGED = "com.muxiao.Venus.TASK_STATE_CHANGED";
    /** 单账号（兼容旧调用方）。 */
    public static final String EXTRA_USER_ID = "user_id";
    /** 批量账号名单（String[]），优先于 {@link #EXTRA_USER_ID}。 */
    public static final String EXTRA_USER_IDS = "user_ids";
    public static final String EXTRA_IS_RUNNING = "is_running";

    /** 逐条任务状态变更广播（供 HomeFragment 实时/恢复刷新列表图标）。 */
    public static final String ACTION_TASK_STATUS_UPDATED = "com.muxiao.Venus.TASK_STATUS_UPDATED";
    /** 任务稳定 id（{@link TaskIds}）：跨组件传递一律用 id，显示名由接收方按当前语言换算。 */
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_TASK_STATUS = "task_status";
    /** 状态归属的运行用户：后台运行期间切换用户时，HomeFragment 凭此过滤非当前展示用户的状态。 */
    public static final String EXTRA_TASK_USER_ID = "task_user_id";

    private Future<?> currentTaskFuture;
    private NotificationCompat.Builder notificationBuilder;
    private com.muxiao.Venus.common.Notification notificationHelper;
    private tools.StatusNotifier notifier;
    /** WakeLock 租约：按账号边界续租。 */
    private WakeLockLease wakeLockLease;
    /** 任务状态写入器：构造含跨日校验，运行期间复用单一实例，避免每条状态重建。 */
    private TaskStatusManager statusManager;
    /** 本次运行的用户：任务状态按用户分桶，批量运行期间随账号切换。 */
    private volatile String runningUserId = "";

    /** 进程级镜像：正在执行的账号（批量链随账号推进）。首页状态条用它给「运行中」账号着色；空串 = 无运行。 */
    private static volatile String sRunningUser = "";

    /** 正在执行的账号（首页状态条判定「运行中」用）。 */
    public static String getRunningUser() {
        return sRunningUser;
    }

    private static volatile ForegroundTaskService instance;

    /** 是否有前台任务 Service 实例在运行（用于判断是否可取消后台任务）。 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 包裹 Context 以应用当前语言地区设置，确保 Service 内文案本地化。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    /** 向应用内广播任务运行状态变更（running），供 HomeFragment 同步后台按钮。 */
    private void broadcastState(boolean running) {
        Intent intent = new Intent(ACTION_TASK_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_RUNNING, running);
        sendBroadcast(intent);
    }

    /** 向应用内广播单条任务状态变更（运行用户 + task id + status 枚举名），供 HomeFragment 实时更新列表图标。 */
    private void broadcastTaskStatus(String taskId, TaskItem.TaskStatus status) {
        Intent intent = new Intent(ACTION_TASK_STATUS_UPDATED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TASK_USER_ID, runningUserId);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_TASK_STATUS, status.name());
        sendBroadcast(intent);
    }

    /** 初始化：登记实例、创建通知助手与状态监听器，并清理旧日志。 */
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        broadcastState(true);
        notifier = new tools.StatusNotifier();
        notificationHelper = new com.muxiao.Venus.common.Notification(this);
        statusManager = new TaskStatusManager(this);
        tools.cleanOldLogs(this);

        notifier.addListener(message -> tools.writeLog(this, message));
    }

    /** 处理 START/STOP 指令：STOP 取消任务，START 拉起前台通知并派发执行线程。 */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_TASK.equals(intent.getAction())) {
            cancelTask();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START_TASK.equals(intent.getAction())) {
            java.util.List<String> users = resolveRequestedUsers(intent);
            notificationBuilder = notificationHelper.createProgressNotification("Venus", getString(R.string.notif_executing_task));

            Intent stopIntent = new Intent(this, ForegroundTaskService.class);
            stopIntent.setAction(ACTION_STOP_TASK);
            PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notificationBuilder.addAction(R.drawable.ic_notification, getString(R.string.notif_cancel), stopPending);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Constants.NOTIFICATION_ID_PROGRESS, notificationBuilder.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Constants.NOTIFICATION_ID_PROGRESS, notificationBuilder.build());
            }
            tools.writeLogSeparator(this);
            executeTasks(users);
        }

        return START_NOT_STICKY;
    }

    /**
     * 解析本次要跑的账号：名单优先，其次单值，最后回退当前账号。
     */
    private java.util.List<String> resolveRequestedUsers(Intent intent) {
        String[] rawUsers = intent.getStringArrayExtra(EXTRA_USER_IDS);
        if (rawUsers != null && rawUsers.length > 0)
            return new java.util.ArrayList<>(java.util.Arrays.asList(rawUsers));
        String single = intent.getStringExtra(EXTRA_USER_ID);
        if (single != null && !single.isEmpty())
            return java.util.Collections.singletonList(single);
        String fallback = com.muxiao.Venus.widget.TaskWidgetReceiver.resolveTargetUser(this);
        return fallback.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(fallback);
    }

    /**
     * 在 IO 线程执行签到任务：账号串行（{@link MultiUserRunner}），每账号开始/结束各续租一次 WakeLock。
     * 无论成功或异常，finally 都释放资源并 stopSelf()。
     *
     * <p>复位、持久化、小组件刷新放在执行线程内，避免在主线程做磁盘 IO。
     */
    private void executeTasks(java.util.List<String> users) {
        // 开跑前记下本轮目标：服务被系统回收 / 进程被杀后靠它补跑未完成项
        java.util.Map<String, java.util.Set<String>> targets = new java.util.LinkedHashMap<>();
        for (String user : users) {
            if (user == null || user.isEmpty()) continue;
            targets.put(user, new java.util.LinkedHashSet<>(
                    TaskSettings.fromPreferences(this, user).getTaskIds()));
        }
        RunRecovery.start(this, targets);
        // WakeLock 租约：按账号边界续租
        wakeLockLease = new WakeLockLease(this);
        wakeLockLease.start();

        try {
            currentTaskFuture = AppExecutors.get().submit(() -> {
                try {
                    MultiUserRunner.run(this, users, MultiUserRunner.DEFAULT_GAP_MS,
                            this::runOneAccount, this::isCancelled);
                } finally {
                    finishTask();
                }
            });
        } catch (Exception e) {
            // 仅提交阶段可能同步抛错（如线程池拒绝）；任务体内的异常由 Callback 上报
            finishTask();
        }
    }

    /** 取消判定：线程被中断或 future 被 cancel。 */
    private boolean isCancelled() {
        return Thread.currentThread().isInterrupted()
                || (currentTaskFuture != null && currentTaskFuture.isCancelled());
    }

    /**
     * 跑一个账号：复位瞬态 → 执行启用任务 → 收口人机验证需求。
     *
     * @param index 账号下标（0 基），用于进度文案与「最后一个账号」判定
     */
    private void runOneAccount(String userId, int index, int total) {
        runningUserId = userId == null ? "" : userId;
        sRunningUser = runningUserId;
        if (wakeLockLease != null) wakeLockLease.renew();
        try {
            // 仅复位该账号桶内的瞬态，保留当日终态
            statusManager.resetTransientStatuses(runningUserId,
                    TaskSettings.fromPreferences(this, runningUserId).getTaskIds());
            // 立即同步到小组件
            TaskWidgetProvider.refreshAllWidgets(this);
            // 记录本次运行账号
            statusManager.setCurrentUser(runningUserId);

            TaskSettings settings = TaskSettings.fromPreferences(this, runningUserId);
            int totalTasks = settings.getTaskIds().size();
            AtomicInteger completedTasks = new AtomicInteger(0);
            // 因需人机验证而挂起的任务：轮末合并成一次验证请求（见 CaptchaSuspension）
            Set<String> captchaTaskIds = ConcurrentHashMap.newKeySet();
            AtomicReference<Map<String, String>> captchaHeaders = new AtomicReference<>();

            GeetestController geetestController = new BackgroundGeetestController();

            TaskExecutor taskExecutor = new TaskExecutor(
                    this, runningUserId, notifier, geetestController,
                    new TaskExecutor.Callback() {
                        @Override
                        public void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status) {
                            saveTaskStatus(taskId, status);
                            if (status == TaskItem.TaskStatus.COMPLETED) {
                                completedTasks.incrementAndGet();
                            }
                            updateServiceNotification(taskId, status,
                                    completedTasks.get(), totalTasks, index, total);
                        }

                        @Override
                        public void onAllTasksCompleted() {
                            // 有挂起项时本轮未真正完成
                            if (!captchaTaskIds.isEmpty()) return;
                            // 批量运行时仅最后一个账号报「全部完成」
                            if (index == total - 1)
                                notificationHelper.completeProgressNotification(
                                        "Venus", getString(R.string.notif_all_tasks_done));
                        }

                        @Override
                        public void onCaptchaRequired(CaptchaRequiredException error) {
                            // 只记下挂起项，轮末统一处理
                            captchaTaskIds.add(error.getTaskId());
                            captchaHeaders.compareAndSet(null, error.getHeaders());
                        }

                        @Override
                        public void onError(String message) {
                            // 错误通知 ID 固定，批量时正文带账号名标明归属
                            String account = new com.muxiao.Venus.User.UserManager(
                                    ForegroundTaskService.this).displayName(userId);
                            notificationHelper.sendErrorNotification(
                                    getString(R.string.notif_task_failed),
                                    getString(R.string.notif_error_with_account, account, message), true);
                        }

                        @Override
                        public boolean isCancelled() {
                            return ForegroundTaskService.this.isCancelled();
                        }
                    });

            taskExecutor.executeAll(settings);

            // 轮末收口：把该账号挂起项合并成一次验证请求，落库并发通知
            if (!captchaTaskIds.isEmpty()) {
                // 用应用语言包裹的 Context，通知文案与界面一致
                boolean suspended = CaptchaSuspension.suspend(
                        LocaleHelper.wrap(this), runningUserId, captchaTaskIds,
                        captchaHeaders.get(), notificationHelper,
                        // 后台运行，通知是唯一验证入口
                        true);
                if (!suspended) {
                    // 取不到挑战则按失败处理
                    for (String id : captchaTaskIds)
                        statusManager.applyStatus(runningUserId, id, TaskItem.TaskStatus.ERROR);
                    TaskWidgetProvider.refreshAllWidgets(this);
                }
            }
        } finally {
            // 账号结束再续租，覆盖账号间冷却
            if (wakeLockLease != null) wakeLockLease.renew();
        }
    }

    /**
     * 任务收尾：广播停止、释放 WakeLock、撤销前台通知、注销实例并停止自身。
     * 正常结束、提交失败、取消三条路径共用，避免同一套收尾动作在多处重复。
     */
    private void finishTask() {
        // 本轮已收口，撤销中断快照；否则下次冷启动会误判为被杀而补跑
        RunRecovery.finish(this);
        broadcastState(false);
        releaseWakeLock();
        dismissForegroundNotification();
        // 再按 ID 显式撤一次
        if (notificationHelper != null) notificationHelper.cancelProgressNotification();
        sRunningUser = "";
        runningUserId = "";
        instance = null;
        stopSelf();
    }

    /** 将任务状态持久化到运行用户的桶并刷新小组件。 */
    private void saveTaskStatus(String taskId, TaskItem.TaskStatus status) {
        statusManager.applyStatus(runningUserId, taskId, status);
        TaskWidgetProvider.refreshAllWidgets(this);
        // 广播单条状态：HomeFragment 实时刷新与恢复时都靠它同步列表图标
        broadcastTaskStatus(taskId, status);
    }

    /** 根据任务状态拼出通知文案，并更新前台进度通知的进度与文本（文案用当前语言的显示名）。 */
    private void updateServiceNotification(String taskId, TaskItem.TaskStatus status,
                                            int progress, int max, int accountIndex, int accountTotal) {
        String taskName = TaskIds.displayName(this, taskId);
        String statusText;
        switch (status) {
            case IN_PROGRESS:
                statusText = getString(R.string.notif_executing, taskName, progress, max);
                break;
            case COMPLETED:
                statusText = getString(R.string.notif_completed, taskName, progress, max);
                break;
            case ERROR:
                statusText = getString(R.string.notif_error, taskName);
                break;
            case WARNING:
            case WAITING_CAPTCHA:
                // WARNING 是旧版「等待验证」的持久化值（会迁移到 WAITING_CAPTCHA），两者文案一致
                statusText = getString(R.string.notif_waiting_verification, taskName);
                break;
            case CANCELLED:
                statusText = getString(R.string.notif_cancelled);
                break;
            default:
                statusText = getString(R.string.notif_preparing);
                break;
        }
        // 批量运行才带账号前缀
        if (accountTotal > 1)
            statusText = getString(R.string.notif_with_account, accountIndex + 1, accountTotal, statusText);
        notificationHelper.updateProgressNotification(
                notificationBuilder, "Venus", statusText, progress, max);
    }

    /** 取消进行中的任务：中断 future、释放 WakeLock、撤销前台通知并停止自身。 */
    private void cancelTask() {
        // 用户主动取消 / 系统配额超时：不按「意外中断」补跑
        RunRecovery.finish(this);
        if (currentTaskFuture != null) {
            currentTaskFuture.cancel(true);
        }
        broadcastState(false);
        releaseWakeLock();
        dismissForegroundNotification();
        // 显式取消通知，确保在所有设备上都能销毁
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(Constants.NOTIFICATION_ID_PROGRESS);
        instance = null;
        TaskWidgetProvider.refreshAllWidgets(this);
        stopSelf();
    }

    /** 该 Service 不通过绑定通信，返回 null。 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 若仍持有则释放 WakeLock，避免 CPU 常驻。 */
    private void releaseWakeLock() {
        if (wakeLockLease != null) {
            wakeLockLease.release();
        }
    }

    /** 停止前台服务并移除进度通知（按系统版本选择移除方式）。 */
    @SuppressWarnings("deprecation")
    private void dismissForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    /** 销毁时释放 WakeLock、撤销通知、通知状态变更并刷新小组件。 */
    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        broadcastState(false);
        dismissForegroundNotification();
        // 同 finishTask：兜住 stopForeground 没清干净的情况（进程被杀/系统回收后重建等路径）
        if (notificationHelper != null) notificationHelper.cancelProgressNotification();
        instance = null;
        TaskWidgetProvider.refreshAllWidgets(this);
    }

    /**
     * Android 15+（API 35）对 dataSync 前台服务设 24 小时累计 6 小时配额：超时后系统回调本方法，服务须在数秒内
     * stopSelf()，否则抛 RemoteServiceException("A foreground service of type dataSync did not stop within its timeout")。
     * 本应用受 WakeLock 10 分钟上限约束，正常不可能触及配额，此处按官方要求兜底走取消流程；
     * 低版本设备不会回调本方法（编译期需 compileSdk ≥ 35）。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        tools.writeLog(this, "dataSync foreground service timed out, stopping.");
        cancelTask();
    }
}
