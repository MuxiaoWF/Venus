package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * 按设置并发执行所有启用的签到任务（米游币/游戏/森空岛）。
     * 提交到共享 IO 线程池后等待全部完成；任一层级抛错仅提示不中断其余任务，
     * 全部结束后若未被取消则回调 onAllTasksCompleted()。
     */
    public void executeAll(TaskSettings settings) {
        notifier.notifyListeners(context.getString(R.string.task_mgr_start, userId));
        List<Future<?>> futures = new ArrayList<>();
        // 统一线程池（AppExecutors）：米游币签到、游戏签到、森空岛签到可并行。
        // 注意：共享线程池不可 shutdown，取消时改为 future.cancel(true)；
        // 因此直接调用 AppExecutors.get().io().submit(...)，不持有 ExecutorService 变量，
        // 避免触发 “ExecutorService used without try-with-resources” 警告。
        try {

            if (settings.isDailyEnabled()) {
                futures.add(AppExecutors.get().io().submit(() -> executeBbsDaily(settings.getDailyForums())));
            }
            if (settings.isGameDailyEnabled()) {
                futures.add(AppExecutors.get().io().submit(() -> executeGameDaily(settings.getGameDailyGames())));
            }
            if (settings.isSklandArknightsEnabled()) {
                futures.add(AppExecutors.get().io().submit(() -> executeSklandDaily(SklandDaily.GAME_ARKNIGHTS)));
            }
            if (settings.isSklandEndfieldEnabled()) {
                futures.add(AppExecutors.get().io().submit(() -> executeSklandDaily(SklandDaily.GAME_ENDFIELD)));
            }

            // 等待所有任务完成，处理异常
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    for (Future<?> ff : futures) ff.cancel(true);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    notifier.notifyListeners(context.getString(R.string.task_mgr_error, e.getMessage()));
                }
            }
        } finally {
            futures.clear();
        }

        if (!callback.isCancelled()) {
            callback.onAllTasksCompleted();
        }
    }

    /**
     * 包裹单个任务的执行与状态上报：先置 IN_PROGRESS，正常完成置 COMPLETED，
     * 被取消置 CANCELLED，异常置 ERROR 并回调 onError；中断时还原中断标志。
     */
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

    /** 执行米游币社区签到：未配置板块时报错，否则提交 BBSDaily.runTask。 */
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

    /** 逐个执行游戏签到（BBSGameDaily.run），每款游戏独立上报状态。 */
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

    /** 执行森空岛签到（指定游戏：明日方舟或终末地）。 */
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
