package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                        Callback callback) {
        this.context = context;
        this.userId = userId;
        this.notifier = notifier;
        this.controller = controller;
        this.callback = callback;
    }

    public void executeAll(TaskSettings settings) {
        notifier.notifyListeners("任务管理器 开始执行，用户: " + userId);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {

            if (settings.isDailyEnabled()) {
                futures.add(pool.submit(() -> executeBbsDaily(settings.getDailyForums())));
            }
            if (settings.isGameDailyEnabled()) {
                futures.add(pool.submit(() -> executeGameDaily(settings.getGameDailyGames())));
            }
            if (settings.isSklandEnabled()) {
                futures.add(pool.submit(this::executeSklandDaily));
            }

            // 等待所有任务完成，处理异常
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    notifier.notifyListeners("任务管理器 执行异常: " + e.getMessage());
                }
            }
        }

        if (!callback.isCancelled()) {
            callback.onAllTasksCompleted();
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
        if (forums == null || forums.length == 0) {
            callback.onTaskStatusChanged("米游币签到", TaskItem.TaskStatus.ERROR);
            callback.onError("米游币签到 失败，请先在设置中勾选至少一个板块");
            return;
        }
        executeTask("米游币签到", () -> {
            BBSDaily bbsDaily = new BBSDaily(context, userId, notifier, controller);
            bbsDaily.runTask(forums);
        });
    }

    private void executeGameDaily(String[] games) {
        if (games == null || games.length == 0) {
            callback.onTaskStatusChanged("游戏签到", TaskItem.TaskStatus.ERROR);
            callback.onError("游戏签到 失败，请先在设置中勾选至少一个游戏");
            return;
        }
        for (String gameName : games) {
            if (callback.isCancelled()) return;
            executeTask(gameName + "签到", () -> {
                BBSGameDaily gameModule = new BBSGameDaily(context, userId, gameName, notifier, controller);
                notifier.notifyListeners(gameName + "签到 正在准备...");
                gameModule.run();
            });
        }
        notifier.notifyListeners("游戏签到 全部完成");
    }

    private void executeSklandDaily() {
        executeTask("森空岛签到", () -> {
            SklandDaily skland = new SklandDaily(context, notifier);
            skland.run();
        });
    }
}
