package com.muxiao.Venus.Home;

import dagger.hilt.android.AndroidEntryPoint;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;
import com.muxiao.Venus.common.AppExecutors;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

/**
 * 前台 Service：后台执行签到任务，持 WakeLock 防 CPU 休眠，经广播与 HomeFragment 同步任务状态，通知栏可取消。
 */
@AndroidEntryPoint
public class ForegroundTaskService extends Service {
    public static final String ACTION_START_TASK = "com.muxiao.Venus.START_TASK";
    public static final String ACTION_STOP_TASK = "com.muxiao.Venus.STOP_TASK";
    public static final String ACTION_TASK_STATE_CHANGED = "com.muxiao.Venus.TASK_STATE_CHANGED";
    public static final String EXTRA_USER_ID = "user_id";
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
    private PowerManager.WakeLock wakeLock;
    /** 任务状态写入器：构造含跨日校验，运行期间复用单一实例，避免每条状态重建。 */
    private TaskStatusManager statusManager;
    /** 本次运行的用户：任务状态按用户分桶，逐条状态写入时按此定位桶。 */
    private volatile String runningUserId = "";

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
            String userId = intent.getStringExtra(EXTRA_USER_ID);
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
            executeTasks(userId);
        }

        return START_NOT_STICKY;
    }

    /**
     * 在 IO 线程执行全部签到任务：先取 10 分钟 WakeLock 防 CPU 休眠，再提交 TaskExecutor 并把状态回传通知/小组件；
     * 无论成功或异常，finally 都释放资源并 stopSelf()。
     *
     * <p>复位/持久化/小组件刷新一律放在提交的执行线程内：本方法由 {@code onStartCommand} 在主线程调用，而这些
     * 动作会触碰 DataStore（首次构造还会同步阻塞读首帧）、跨天时的整仓读写与历史日志落盘、AppWidget 更新，
     * 放主线程等于在服务启动路径上做磁盘 IO（慢机可能卡顿甚至 ANR）。
     */
    private void executeTasks(String userId) {
        // 获取 WakeLock，防止 CPU 休眠导致任务中断
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Venus:TaskWakeLock");
        // 最长10分钟，超时自动释放防止泄漏
        wakeLock.acquire(10 * 60 * 1000L);

        runningUserId = userId != null ? userId : "";

        try {
            currentTaskFuture = AppExecutors.get().submit(() -> {
                try {
                    // 新一轮运行：仅复位该用户桶内的瞬态，保留当日终态——重跑中途终止不会抹掉已完成记录。
                    // App 内「开始任务」已由 resetForNewRun 复位过（重复复位无副作用），但小组件入口
                    // TaskWidgetReceiver 没有对应调用，统一由服务侧复位以保证三条入口一致。
                    statusManager.resetTransientStatuses(runningUserId, TaskSettings.fromPreferences(this).getTaskIds());
                    // 立即把复位结果反映到小组件（任务首个状态回调前有 2~9s 随机风控延时）
                    TaskWidgetProvider.refreshAllWidgets(this);
                    // 记录本次运行的用户：小组件目标用户的兜底（历史日志归属由分桶自带）
                    statusManager.setCurrentUser(runningUserId);

                    TaskSettings settings = TaskSettings.fromPreferences(this);
                    int totalTasks = settings.getTaskIds().size();
                    AtomicInteger completedTasks = new AtomicInteger(0);

                    GeetestController geetestController = new BackgroundGeetestController(ForegroundTaskService.this, notifier);

                    TaskExecutor taskExecutor = new TaskExecutor(
                            this, userId, notifier, geetestController,
                            new TaskExecutor.Callback() {
                                @Override
                                public void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status) {
                                    saveTaskStatus(taskId, status);
                                    if (status == TaskItem.TaskStatus.COMPLETED) {
                                        completedTasks.incrementAndGet();
                                    }
                                    updateServiceNotification(taskId, status,
                                            completedTasks.get(), totalTasks);
                                }

                                @Override
                                public void onAllTasksCompleted() {
                                    notificationHelper.completeProgressNotification(
                                            "Venus", getString(R.string.notif_all_tasks_done));
                                }

                                @Override
                                public void onError(String message) {
                                    notificationHelper.sendErrorNotification(getString(R.string.notif_task_failed), message, true);
                                }

                                @Override
                                public boolean isCancelled() {
                                    return Thread.currentThread().isInterrupted()
                                            || (currentTaskFuture != null && currentTaskFuture.isCancelled());
                                }
                            });

                    taskExecutor.executeAll(settings);
                } finally {
                    finishTask();
                }
            });
        } catch (Exception e) {
            // 仅提交阶段可能同步抛错（如线程池拒绝）；任务体内的异常由 Callback 上报
            finishTask();
        }
    }

    /**
     * 任务收尾：广播停止、释放 WakeLock、撤销前台通知、注销实例并停止自身。
     * 正常结束、提交失败、取消三条路径共用，避免同一套收尾动作在多处重复。
     */
    private void finishTask() {
        broadcastState(false);
        releaseWakeLock();
        dismissForegroundNotification();
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
                                            int progress, int max) {
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
                statusText = getString(R.string.notif_waiting_verification, taskName);
                break;
            case CANCELLED:
                statusText = getString(R.string.notif_cancelled);
                break;
            default:
                statusText = getString(R.string.notif_preparing);
                break;
        }
        notificationHelper.updateProgressNotification(
                notificationBuilder, "Venus", statusText, progress, max);
    }

    /** 取消进行中的任务：中断 future、释放 WakeLock、撤销前台通知并停止自身。 */
    private void cancelTask() {
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
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
