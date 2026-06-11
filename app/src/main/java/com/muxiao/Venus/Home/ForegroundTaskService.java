package com.muxiao.Venus.Home;

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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ForegroundTaskService extends Service {
    public static final String ACTION_START_TASK = "com.muxiao.Venus.START_TASK";
    public static final String ACTION_STOP_TASK = "com.muxiao.Venus.STOP_TASK";
    public static final String ACTION_TASK_STATE_CHANGED = "com.muxiao.Venus.TASK_STATE_CHANGED";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_IS_RUNNING = "is_running";

    private ExecutorService executorService;
    private Future<?> currentTaskFuture;
    private NotificationCompat.Builder notificationBuilder;
    private tools.StatusNotifier notifier;
    private PowerManager.WakeLock wakeLock;

    private static ForegroundTaskService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    private void broadcastState(boolean running) {
        Intent intent = new Intent(ACTION_TASK_STATE_CHANGED);
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

        // 添加日志写入监听器
        notifier.addListener(message -> {
            try {
                File logDir = new File(getExternalFilesDir(null), "logs");
                if (!logDir.exists()) logDir.mkdirs();
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                File logFile = new File(logDir, "daily_task_log_" + date + ".txt");

                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                FileWriter writer = new FileWriter(logFile, true);
                writer.append("[").append(timestamp).append("] ").append(message).append("\n");
                writer.close();
            } catch (IOException ignored) {}
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_TASK.equals(intent.getAction())) {
            cancelTask();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START_TASK.equals(intent.getAction())) {
            String userId = intent.getStringExtra(EXTRA_USER_ID);
            com.muxiao.Venus.common.Notification notificationHelper =
                    new com.muxiao.Venus.common.Notification(this);
            notificationBuilder = notificationHelper.createProgressNotification("Venus", "正在执行任务...");

            // 添加取消按钮
            Intent stopIntent = new Intent(this, ForegroundTaskService.class);
            stopIntent.setAction(ACTION_STOP_TASK);
            PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notificationBuilder.addAction(R.drawable.ic_notification, "取消", stopPending);

            startForeground(Constants.NOTIFICATION_ID_PROGRESS, notificationBuilder.build());
            executeTasks(userId);
        }

        return START_NOT_STICKY;
    }

    private void executeTasks(String userId) {
        // 获取 WakeLock，防止 CPU 休眠导致任务中断
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Venus:TaskWakeLock");
        wakeLock.acquire(30 * 60 * 1000L); // 最长30分钟，防止意外泄漏

        currentTaskFuture = executorService.submit(() -> {
            try {
                TaskSettings settings = TaskSettings.fromPreferences(this);
                com.muxiao.Venus.common.Notification notificationHelper =
                        new com.muxiao.Venus.common.Notification(this);

                int totalTasks = settings.getTaskNames().size();
                int[] completedTasks = {0};

                GeetestController geetestController = new BackgroundGeetestController(ForegroundTaskService.this, notifier);

                TaskExecutor taskExecutor = new TaskExecutor(
                        this, userId, notifier, geetestController, notificationHelper,
                        new TaskExecutor.Callback() {
                            @Override
                            public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                if (status == TaskItem.TaskStatus.COMPLETED) {
                                    completedTasks[0]++;
                                }
                                updateServiceNotification(taskName, status,
                                        completedTasks[0], totalTasks);
                            }

                            @Override
                            public void onAllTasksCompleted() {
                                notificationHelper.completeProgressNotification(
                                        notificationBuilder, "Venus", "所有任务已完成");
                            }

                            @Override
                            public void onError(String message) {
                                notificationHelper.sendErrorNotification("任务失败", message);
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
    }

    private void updateServiceNotification(String taskName, TaskItem.TaskStatus status,
                                            int progress, int max) {
        com.muxiao.Venus.common.Notification notificationHelper =
                new com.muxiao.Venus.common.Notification(this);
        String statusText;
        switch (status) {
            case IN_PROGRESS:
                statusText = "正在执行: " + taskName + " (" + progress + "/" + max + ")";
                break;
            case COMPLETED:
                statusText = "已完成: " + taskName + " (" + progress + "/" + max + ")";
                break;
            case ERROR:
                statusText = "失败: " + taskName;
                break;
            case WARNING:
                statusText = "等待验证: " + taskName;
                break;
            case CANCELLED:
                statusText = "已取消";
                break;
            default:
                statusText = "准备中...";
                break;
        }
        notificationHelper.updateProgressNotification(
                notificationBuilder, "Venus", statusText, progress, max);
    }

    private void cancelTask() {
        if (currentTaskFuture != null) {
            currentTaskFuture.cancel(true);
        }
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.cancel(Constants.NOTIFICATION_ID_PROGRESS);
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
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (executorService != null && !executorService.isShutdown())
            executorService.shutdown();
        instance = null;
    }
}
