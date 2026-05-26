package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

public class TaskExecutor {

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }

    public interface Callback {
        void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status);
        void onAllTasksCompleted();
        void onError(String message);
        boolean isCancelled();
    }

    private final Context context;
    private final String userId;
    private final tools.StatusNotifier notifier;
    private final GeetestController controller;
    private final Callback callback;

    public TaskExecutor(Context context, String userId,
                        tools.StatusNotifier notifier, GeetestController controller,
                        Notification notification, Callback callback) {
        this.context = context;
        this.userId = userId;
        this.notifier = notifier;
        this.controller = controller;
        this.callback = callback;
    }

    public void executeAll(TaskSettings settings) {
        try {
            if (settings.isDailyEnabled()) {
                executeBbsDaily(settings.getDailyForums());
            }
            if (callback.isCancelled()) return;

            if (settings.isGameDailyEnabled()) {
                executeGameDaily(settings.getGameDailyGames());
            }
            if (callback.isCancelled()) return;

            if (settings.isSklandEnabled()) {
                executeSklandDaily();
            }

            callback.onAllTasksCompleted();
        } catch (Exception e) {
            notifier.notifyListeners("任务执行异常: " + e.getMessage());
        }
    }

    private void executeTask(String name, RunnableWithException task) {
        callback.onTaskStatusChanged(name, TaskItem.TaskStatus.IN_PROGRESS);
        try {
            task.run();
            if (callback.isCancelled()) {
                callback.onTaskStatusChanged(name, TaskItem.TaskStatus.CANCELLED);
            } else {
                callback.onTaskStatusChanged(name, TaskItem.TaskStatus.COMPLETED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onTaskStatusChanged(name, TaskItem.TaskStatus.CANCELLED);
        } catch (Exception e) {
            if (callback.isCancelled()) {
                callback.onTaskStatusChanged(name, TaskItem.TaskStatus.CANCELLED);
                return;
            }
            callback.onTaskStatusChanged(name, TaskItem.TaskStatus.ERROR);
            callback.onError(name + "失败：" + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private void executeBbsDaily(String[] forums) {
        if (userId.isEmpty()) {
            callback.onError("未选择用户");
            return;
        }
        if (forums == null || forums.length == 0) {
            callback.onTaskStatusChanged("米游币签到", TaskItem.TaskStatus.ERROR);
            callback.onError("米游币签到失败，请先去设置里设置勾选至少一个获取米游币的板块");
            return;
        }
        executeTask("米游币签到", () -> {
            BBSDaily bbsDaily = new BBSDaily(context, userId, notifier, controller);
            bbsDaily.runTask(forums);
        });
    }

    private void executeGameDaily(String[] games) {
        if (userId.isEmpty()) {
            callback.onError("未选择用户");
            return;
        }
        if (games == null || games.length == 0) {
            callback.onTaskStatusChanged("游戏签到", TaskItem.TaskStatus.ERROR);
            callback.onError("游戏签到失败，请先去设置里设置勾选获取至少一个游戏进行签到");
            return;
        }
        for (String gameName : games) {
            if (callback.isCancelled()) return;
            executeTask(gameName + "签到", () -> {
                BBSGameDaily gameModule = new BBSGameDaily(context, userId, gameName, notifier, controller);
                notifier.notifyListeners("正在进行" + gameName + "签到");
                gameModule.run();
            });
        }
        notifier.notifyListeners("游戏签到完成");
    }

    private void executeSklandDaily() {
        executeTask("森空岛签到", () -> {
            SklandDaily skland = new SklandDaily(context, notifier);
            skland.run();
        });
    }
}
