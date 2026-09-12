package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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

    /**
     * 游戏签到专用线程池（并发度 2，进程级、禁止 shutdown）。
     * <p>
     * 刻意不复用共享 IO 池：executeGameDaily 自身已占用共享池的一个线程并阻塞等待结果，
     * 若游戏子任务也提交到同一池，在池较小（双核设备 = 4 线程）且米游币/森空岛任务占满
     * 其余线程时，游戏任务只能排队等到它们全部结束才开始——并发形同虚设。专用小池让
     * 游戏签到始终以固定并发 2 执行，与其他任务互不抢占。
     */
    private static final java.util.concurrent.ExecutorService GAME_SIGN_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Venus-GameSign");
                t.setDaemon(true);
                return t;
            });

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
        if (settings.isDailyEnabled())
            futures.add(AppExecutors.get().io().submit(() -> executeBbsDaily(settings.getDailyForums())));
        if (settings.isGameDailyEnabled())
            futures.add(AppExecutors.get().io().submit(() -> executeGameDaily(settings.getGameDailyGames())));
        if (settings.isSklandArknightsEnabled())
            futures.add(AppExecutors.get().io().submit(() -> executeSklandDaily(SklandDaily.GAME_ARKNIGHTS)));
        if (settings.isSklandEndfieldEnabled())
            futures.add(AppExecutors.get().io().submit(() -> executeSklandDaily(SklandDaily.GAME_ENDFIELD)));

        if (!awaitAll(futures)) return;
        if (!callback.isCancelled()) {
            callback.onAllTasksCompleted();
        }
    }

    /** 取消全部子任务，用于等待过程中被中断/取消时的收尾。 */
    private static void cancelAll(List<Future<?>> futures) {
        for (Future<?> future : futures) future.cancel(true);
    }

    /**
     * 等待全部子任务完成（{@link #executeAll} 与 {@link #executeGameDaily} 共用的等待与异常语义）：
     * 子任务抛错仅提示、不中断其余任务；等待期间被中断/取消则取消其余子任务。
     *
     * @return {@code true} 表示全部完成；{@code false} 表示被中断/取消，调用方应立即返回
     */
    private boolean awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                cancelAll(futures);
                Thread.currentThread().interrupt();
                return false;
            } catch (CancellationException e) {
                // 子任务被 future.cancel(true) 取消，属预期路径，不算错误
                cancelAll(futures);
                return false;
            } catch (ExecutionException e) {
                notifier.notifyListeners(context.getString(R.string.task_mgr_error, rootMessage(e)));
            }
        }
        return true;
    }

    /**
     * 取异常链中最内层消息：Future.get() 抛出的 ExecutionException 文案形如
     * "java.lang.RuntimeException: xxx"，直接展示会把异常类名暴露给用户。
     */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message != null ? message : cause.toString();
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

    /**
     * 待执行目标为空（用户未勾选任何板块/游戏）时的统一处理：
     * 任务置错误态 + 回调错误提示，调用方据此直接 return。
     */
    private void reportEmptyTargets(int taskNameRes, int emptyMessageRes) {
        callback.onTaskStatusChanged(context.getString(taskNameRes), TaskItem.TaskStatus.ERROR);
        callback.onError(context.getString(emptyMessageRes));
    }

    /** 执行米游币社区签到：未配置板块时报错，否则提交 BBSDaily.runTask。 */
    private void executeBbsDaily(String[] forums) {
        if (forums == null || forums.length == 0) {
            reportEmptyTargets(R.string.task_name_bbs_daily, R.string.task_bbs_daily_no_forum);
            return;
        }
        executeTask(context.getString(R.string.task_name_bbs_daily), () -> {
            BBSDaily bbsDaily = new BBSDaily(context, userId, notifier, controller);
            bbsDaily.runTask(forums);
        });
    }

    /**
     * 并发执行游戏签到（并发度 2，由 {@link #GAME_SIGN_POOL} 保证）。
     * <p>
     * 每款游戏内部仍保留原有的「请求前后 2~9 秒随机风控延时」，跨游戏并行只提高账号的
     * 请求密度、不改变单游戏节奏；429 限流仍由 {@code BBSGameDaily} 的冷却逻辑兜底。
     * 等待方式与 {@link #executeAll} 一致：任一游戏抛错仅提示不中断其余游戏。
     */
    private void executeGameDaily(String[] games) {
        if (games == null || games.length == 0) {
            reportEmptyTargets(R.string.task_game_sign_in, R.string.task_game_sign_in_no_game);
            return;
        }
        List<Future<?>> futures = new ArrayList<>(games.length);
        for (String gameName : games) {
            if (callback.isCancelled()) break;
            futures.add(GAME_SIGN_POOL.submit(() -> {
                String displayName = MiHoYoBBSConstants.game_to_display_name(context, gameName);
                executeTask(context.getString(R.string.task_name_game_sign_in, displayName), () -> {
                    BBSGameDaily gameModule = new BBSGameDaily(context, userId, gameName, notifier, controller);
                    notifier.notifyListeners(context.getString(R.string.task_sign_preparing, displayName));
                    gameModule.run();
                });
            }));
        }
        if (!awaitAll(futures)) return;
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
