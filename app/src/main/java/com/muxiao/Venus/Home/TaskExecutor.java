package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 任务调度器：根据 TaskSettings 配置，使用线程池并发执行米游币签到、游戏签到、森空岛签到。
 * 每个子任务通过 Callback 上报状态（进行中/完成/失败/取消），支持中途取消。
 */
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
        notifier.notifyListeners(context.getString(R.string.task_mgr_start, userId));
        List<Future<?>> futures = new ArrayList<>();
        // 3个线程：米游币签到、游戏签到、森空岛签到可并行
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {

            if (settings.isDailyEnabled()) {
                futures.add(pool.submit(() -> executeBbsDaily(settings.getDailyForums())));
            }
            if (settings.isGameDailyEnabled()) {
                futures.add(pool.submit(() -> executeGameDaily(settings.getGameDailyGames())));
            }
            if (settings.isSklandArknightsEnabled()) {
                futures.add(pool.submit(() -> executeSklandDaily(SklandDaily.GAME_ARKNIGHTS)));
            }
            if (settings.isSklandEndfieldEnabled()) {
                futures.add(pool.submit(() -> executeSklandDaily(SklandDaily.GAME_ENDFIELD)));
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
                    notifier.notifyListeners(context.getString(R.string.task_mgr_error, e.getMessage()));
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
            callback.onError(context.getString(R.string.task_failed_format, name, e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private void executeBbsDaily(String[] forums) {
        if (forums == null || forums.length == 0) {
            callback.onTaskStatusChanged(context.getString(R.string.task_name_bbs_daily), TaskItem.TaskStatus.ERROR);
            callback.onError(context.getString(R.string.task_bbs_daily_no_forum));
            return;
        }
        executeTask(context.getString(R.string.task_name_bbs_daily), () -> {
            BBSDaily bbsDaily = new BBSDaily(context, userId, notifier, controller);
            bbsDaily.runTask(forums);
        });
    }

    private void executeGameDaily(String[] games) {
        if (games == null || games.length == 0) {
            callback.onTaskStatusChanged(context.getString(R.string.task_game_sign_in), TaskItem.TaskStatus.ERROR);
            callback.onError(context.getString(R.string.task_game_sign_in_no_game));
            return;
        }
        for (String gameName : games) {
            if (callback.isCancelled()) return;
            String displayName = MiHoYoBBSConstants.game_to_display_name(context, gameName);
            executeTask(context.getString(R.string.task_name_game_sign_in, displayName), () -> {
                BBSGameDaily gameModule = new BBSGameDaily(context, userId, gameName, notifier, controller);
                notifier.notifyListeners(context.getString(R.string.task_sign_preparing, displayName));
                gameModule.run();
            });
        }
        notifier.notifyListeners(context.getString(R.string.task_game_sign_in_done));
    }

    private void executeSklandDaily(int gameId) {
        String taskName = gameId == SklandDaily.GAME_ARKNIGHTS
                ? context.getString(R.string.task_name_skland_arknights)
                : context.getString(R.string.task_name_skland_endfield);
        executeTask(taskName, () -> {
            SklandDaily skland = new SklandDaily(context, notifier, gameId);
            skland.run();
        });
    }
}
