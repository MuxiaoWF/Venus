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
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;
import com.muxiao.Venus.common.AppExecutors;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;

/**
 * 前台Service：在后台执行签到任务，显示持久进度通知，持有WakeLock防止CPU休眠。
 * 通过广播与 HomeFragment 通信任务状态，支持用户通过通知栏取消任务。
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
    public static final String EXTRA_TASK_NAME = "task_name";
    public static final String EXTRA_TASK_STATUS = "task_status";

    private Future<?> currentTaskFuture;
    private NotificationCompat.Builder notificationBuilder;
    private com.muxiao.Venus.common.Notification notificationHelper;
    private tools.StatusNotifier notifier;
    private PowerManager.WakeLock wakeLock;

    private static volatile ForegroundTaskService instance;
    /** 后台任务已变更任务状态但尚未被 UI 消费的脏标记（onResume 据此决定是否重建列表）。 */
    private static volatile boolean sStatusDirty = false;

    /** 是否有前台任务 Service 实例在运行（用于判断是否可取消后台任务）。 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 消费「状态已变更」脏标记：返回当前值并复位，供 onResume 决定是否重建任务列表。 */
    public static boolean consumeStatusDirty() {
        boolean dirty = sStatusDirty;
        sStatusDirty = false;
        return dirty;
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

    /** 向应用内广播单条任务状态变更（task_name + status 枚举名），供 HomeFragment 实时更新列表图标。 */
    private void broadcastTaskStatus(String taskName, TaskItem.TaskStatus status) {
        Intent intent = new Intent(ACTION_TASK_STATUS_UPDATED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TASK_NAME, taskName);
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
        tools.cleanOldLogs(this);

        // 添加日志写入监听器
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

            // 添加取消按钮
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
     * 在 IO 线程中执行全部签到任务。
     * 先获取 10 分钟 WakeLock 防 CPU 休眠，再提交 TaskExecutor 并回调状态到通知/小组件；
     * 无论成功或异常，finally 中都会释放资源并 stopSelf()。
     */
    private void executeTasks(String userId) {
        // 获取 WakeLock，防止 CPU 休眠导致任务中断
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Venus:TaskWakeLock");
        // 最长10分钟，超时自动释放防止泄漏
        wakeLock.acquire(10 * 60 * 1000L);

        // 记录当前用户供 Widget 显示
        new TaskStatusManager(this).setCurrentUser(userId != null ? userId : "");

        try {
        currentTaskFuture = AppExecutors.get().io().submit(() -> {
            try {
                TaskSettings settings = TaskSettings.fromPreferences(this);
                int totalTasks = settings.getTaskNames(ForegroundTaskService.this).size();
                AtomicInteger completedTasks = new AtomicInteger(0);

                GeetestController geetestController = new BackgroundGeetestController(ForegroundTaskService.this, notifier);

                TaskExecutor taskExecutor = new TaskExecutor(
                        this, userId, notifier, geetestController,
                        new TaskExecutor.Callback() {
                            @Override
                            public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                // 持久化任务状态供 Widget 读取
                                saveTaskStatus(taskName, status);
                                if (status == TaskItem.TaskStatus.COMPLETED) {
                                    completedTasks.incrementAndGet();
                                }
                                updateServiceNotification(taskName, status,
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
                broadcastState(false);
                releaseWakeLock();
                dismissForegroundNotification();
                instance = null;
                stopSelf();
            }
        });
        } catch (Exception e) {
            broadcastState(false);
            releaseWakeLock();
            dismissForegroundNotification();
            instance = null;
            stopSelf();
        }
    }

    /** 将任务状态持久化到 TaskStatusManager 并刷新小组件。 */
    private void saveTaskStatus(String taskName, TaskItem.TaskStatus status) {
        TaskStatusManager manager = new TaskStatusManager(this);
        switch (status) {
            case COMPLETED:
                manager.markCompleted(taskName);
                break;
            case ERROR:
                manager.markError(taskName);
                break;
            case IN_PROGRESS:
                manager.markStatus(taskName, TaskStatusManager.STATUS_IN_PROGRESS);
                break;
            case WARNING:
                manager.markStatus(taskName, TaskStatusManager.STATUS_WARNING);
                break;
            case CANCELLED:
                manager.markStatus(taskName, TaskStatusManager.STATUS_CANCELLED);
                break;
            default:
                break;
        }
        // 通知 Widget 刷新
        TaskWidgetProvider.refreshAllWidgets(this);
        // 标记本机 UI 脏位并向应用内广播单条任务状态，供 HomeFragment 实时/恢复刷新列表图标
        sStatusDirty = true;
        broadcastTaskStatus(taskName, status);
    }

    /** 根据任务状态拼出通知文案，并更新前台进度通知的进度与文本。 */
    private void updateServiceNotification(String taskName, TaskItem.TaskStatus status,
                                            int progress, int max) {
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
        // 刷新小组件，恢复运行按钮状态
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
        // 刷新小组件，恢复运行按钮状态
        TaskWidgetProvider.refreshAllWidgets(this);
    }

    /**
     * Android 15+（API 35）对 dataSync 前台服务设 24 小时累计 6 小时配额：超时后系统回调本方法，
     * 服务必须在数秒内 stopSelf()，否则抛
     * RemoteServiceException: "A foreground service of type dataSync did not stop within its timeout"。
     * 本应用单次任务受 WakeLock 10 分钟上限约束，正常不可能触及配额，此处按官方要求兜底走取消流程。
     * 低版本设备不会回调本方法（编译期需 compileSdk ≥ 35）。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        tools.writeLog(this, "dataSync foreground service timed out, stopping.");
        cancelTask();
    }
}
