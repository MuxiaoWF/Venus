package com.muxiao.Venus.Home;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 前台Service：在后台执行签到任务，显示持久进度通知，持有WakeLock防止CPU休眠。
 * 通过广播与 HomeFragment 通信任务状态，支持用户通过通知栏取消任务。
 */
public class ForegroundTaskService extends Service {
    public static final String ACTION_START_TASK = "com.muxiao.Venus.START_TASK";
    public static final String ACTION_STOP_TASK = "com.muxiao.Venus.STOP_TASK";
    public static final String ACTION_TASK_STATE_CHANGED = "com.muxiao.Venus.TASK_STATE_CHANGED";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_IS_RUNNING = "is_running";

    private ExecutorService executorService;
    private Future<?> currentTaskFuture;
    private NotificationCompat.Builder notificationBuilder;
    private com.muxiao.Venus.common.Notification notificationHelper;
    private tools.StatusNotifier notifier;
    private PowerManager.WakeLock wakeLock;

    private static volatile ForegroundTaskService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    private void broadcastState(boolean running) {
        Intent intent = new Intent(ACTION_TASK_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_RUNNING, running);
        sendBroadcast(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        broadcastState(true);
        executorService = Executors.newSingleThreadExecutor();
        notifier = new tools.StatusNotifier();
        notificationHelper = new com.muxiao.Venus.common.Notification(this);
        tools.cleanOldLogs(this);

        // 添加日志写入监听器
        notifier.addListener(message -> tools.writeLog(this, message));
    }

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

    private void executeTasks(String userId) {
        // 获取 WakeLock，防止 CPU 休眠导致任务中断
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Venus:TaskWakeLock");
        // 最长10分钟，超时自动释放防止泄漏
        wakeLock.acquire(10 * 60 * 1000L);

        // 记录当前用户供 Widget 显示
        new TaskStatusManager(this).setCurrentUser(userId != null ? userId : "");

        try {
        currentTaskFuture = executorService.submit(() -> {
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
    }

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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @SuppressWarnings("deprecation")
    private void dismissForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        broadcastState(false);
        dismissForegroundNotification();
        if (executorService != null && !executorService.isShutdown())
            executorService.shutdown();
        instance = null;
        // 刷新小组件，恢复运行按钮状态
        TaskWidgetProvider.refreshAllWidgets(this);
    }
}
