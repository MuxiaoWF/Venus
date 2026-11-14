package com.muxiao.Venus.Home;

import android.content.Context;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * 任务调度器：按 TaskSettings 用线程池并发执行米游币/游戏/森空岛签到；子任务经 Callback 上报状态，支持中途取消。
 */
public class TaskExecutor {

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }

    public interface Callback {
        /**
         * 单任务状态变更。
         *
         * @param taskId 稳定任务 id（{@link TaskIds}），持久化与跨组件匹配一律用它；需要展示时用
         *               {@link TaskIds#displayName(Context, String)} 换算
         */
        void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status);
        void onAllTasksCompleted();
        void onError(String message);
        boolean isCancelled();

        /**
         * 单任务失败回调（带原始异常）：默认退化为 {@link #onError(String)}。
         * 需要按失败类别定制重试策略的调用方（定时链路）覆写本方法。
         */
        default void onTaskError(String taskId, String message, Throwable cause) {
            onError(message);
        }

        /**
         * 任务因需人机验证而挂起。调用方收集这些信号，轮末统一取一次挑战并落库，等用户完成验证后
         * 由续跑轮带上凭证重跑（见 {@link CaptchaStore}）。默认忽略——前台单任务链路自己就能处理。
         */
        default void onCaptchaRequired(CaptchaRequiredException error) {
        }
    }

    private final Context context;
    private final String userId;
    private final tools.StatusNotifier notifier;
    private final GeetestController controller;
    private final Callback callback;

    /**
     * 游戏签到专用线程池（并发度 2，进程级、禁止 shutdown）。
     * <p>
     * 刻意不复用共享 IO 池：executeGameDaily 自身已占用共享池一个线程并阻塞等结果，子任务若同池提交，
     * 在双核设备（4 线程）且米游币/森空岛任务占满其余线程时，游戏任务只能排队到它们全部结束——并发
     * 形同虚设。专用小池让游戏签到恒以并发 2 执行，与其他任务互不抢占。
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
     * 按设置并发执行所有启用的签到任务：提交到共享 IO 池后等待全部完成；任一层级抛错仅提示不中断其余任务，
     * 全部结束且未被取消则回调 onAllTasksCompleted()。
     */
    public void executeAll(TaskSettings settings) {
        executeAll(settings, null);
    }

    /**
     * 同 {@link #executeAll(TaskSettings)}，但 onlyIds 非空时只执行 id 命中的任务（「重试失败任务」的部分执行入口）。
     * <p>
     * onlyIds 为稳定任务 id（{@link TaskIds}），与 {@code TaskStatusManager} 持久化键严格同源——不再用本地化
     * 任务名匹配：名称随语言变化，跨语言重试会全部落空。游戏签到/云游戏按「单游戏」粒度落 id。
     * 候选任务全被过滤时视为无事可做，直接回调完成而非空跑一轮。
     */
    public void executeAll(TaskSettings settings, java.util.Set<String> onlyIds) {
        boolean runAll = onlyIds == null || onlyIds.isEmpty();
        notifier.notifyListeners(context.getString(R.string.task_mgr_start, userId));
        List<Future<?>> futures = new ArrayList<>();
        // 凭证就绪表：云游戏/森空岛未配置 token 的任务在列表里已划线，这里同样跳过，
        // 既不浪费请求也不把「没配」误报成「失败」。
        java.util.Map<String, Boolean> readiness = settings.getTaskReadiness(context, userId);
        // 统一线程池（AppExecutors）：三类签到可并行；共享池不可 shutdown，取消时改为 future.cancel(true)。
        // 经 AppExecutors.submit() 门面提交（不暴露 AutoCloseable 的 ExecutorService），避免 Lint 误报。
        if (settings.isDailyEnabled() && (runAll || onlyIds.contains(TaskIds.BBS_DAILY)))
            futures.add(AppExecutors.get().submit(() -> executeBbsDaily(settings.getDailyForums())));
        if (settings.isGameDailyEnabled()) {
            List<String> games = filterGames(settings.getGameDailyGames(), onlyIds, runAll);
            if (!games.isEmpty())
                futures.add(AppExecutors.get().submit(() -> executeGameDaily(games)));
        }
        // 云游戏：每个游戏独立提交（无「父任务阻塞等待子任务」结构），
        // 因此与游戏签到共用共享池也不会造成线程饥饿；单次仅 1~2 个轻量 GET 请求。
        if (settings.isCloudGameEnabled()) {
            List<String> cloudGames = filterCloudGames(settings.getCloudGames(), onlyIds, runAll);
            if (cloudGames.isEmpty()) {
                // 总开关开着但一款都没勾：与「游戏签到」同口径给出可读提示，而不是静默无任务
                if (runAll) notifier.notifyListeners(context.getString(R.string.task_cloud_game_no_game));
            } else {
                for (String gameName : cloudGames) {
                    String taskId = TaskIds.cloudGame(gameName);
                    if (!isReady(readiness, taskId, CloudGameDaily.displayName(context, gameName))) continue;
                    futures.add(AppExecutors.get().submit(() -> executeCloudGame(gameName)));
                }
            }
        }
        if (settings.isSklandArknightsEnabled() && (runAll || onlyIds.contains(TaskIds.SKLAND_ARKNIGHTS))) {
            if (isReady(readiness, TaskIds.SKLAND_ARKNIGHTS, TaskIds.displayName(context, TaskIds.SKLAND_ARKNIGHTS)))
                futures.add(AppExecutors.get().submit(() -> executeSklandDaily(SklandDaily.GAME_ARKNIGHTS)));
        }
        if (settings.isSklandEndfieldEnabled() && (runAll || onlyIds.contains(TaskIds.SKLAND_ENDFIELD))) {
            if (isReady(readiness, TaskIds.SKLAND_ENDFIELD, TaskIds.displayName(context, TaskIds.SKLAND_ENDFIELD)))
                futures.add(AppExecutors.get().submit(() -> executeSklandDaily(SklandDaily.GAME_ENDFIELD)));
        }

        // 过滤后无任何可执行目标（如失败任务已被用户改配置移除）：不空跑，直接走完成回调
        if (futures.isEmpty()) {
            if (!callback.isCancelled()) callback.onAllTasksCompleted();
            return;
        }
        if (!awaitAll(futures)) return;
        if (!callback.isCancelled()) {
            callback.onAllTasksCompleted();
        }
    }

    /**
     * 凭证未配置的任务直接跳过。任务列表里已用删除线标出，这里再补一条进度提示：否则用户看到项还在、
     * 结束后状态仍是「待执行」，会以为漏跑。
     *
     * @param taskId 任务的稳定 id（readiness 键）
     * @param label  提示里展示的名字（云游戏用游戏名，森空岛用任务名本身）
     */
    private boolean isReady(java.util.Map<String, Boolean> readiness, String taskId, String label) {
        Boolean ready = readiness.get(taskId);
        if (ready == null || ready) return true;
        notifier.notifyListeners(context.getString(R.string.task_skipped_unconfigured, label));
        return false;
    }

    /**
     * 游戏签到按 id 过滤：runAll 原样返回全部；否则只保留 {@code game_signin:<game>} 命中 onlyIds 的游戏
     * （可能为空，由调用方跳过提交）。
     */
    private List<String> filterGames(String[] games, java.util.Set<String> onlyIds, boolean runAll) {
        List<String> result = new ArrayList<>();
        if (games == null) return result;
        for (String game : games) {
            if (runAll || onlyIds.contains(TaskIds.gameSignIn(game))) result.add(game);
        }
        return result;
    }

    /** 取消全部子任务，用于等待过程中被中断/取消时的收尾。 */
    private static void cancelAll(List<Future<?>> futures) {
        for (Future<?> future : futures) future.cancel(true);
    }

    /**
     * 等待全部子任务完成（{@link #executeAll} 与 {@link #executeGameDaily} 共用的等待与异常语义）：
     * 子任务抛错仅提示、不中断其余任务；等待期间被中断/取消则取消其余子任务。
     *
     * @return {@code true} 全部完成；{@code false} 被中断/取消，调用方应立即返回
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
     * 取异常链最内层消息：ExecutionException 的文案形如 "java.lang.RuntimeException: xxx"，直接展示会暴露异常类名。
     */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message != null ? message : cause.toString();
    }

    /**
     * 包裹单个任务的执行与状态上报：先置 IN_PROGRESS，完成置 COMPLETED、取消置 CANCELLED、
     * 需人机验证置 WAITING_CAPTCHA（挂起，不计失败）、其余异常置 ERROR 并回调 onTaskError；
     * 中断时还原中断标志。
     *
     * @param taskId 稳定任务 id：回调与持久化都走它，展示名只在错误文案里现算
     */
    private void executeTask(String taskId, RunnableWithException task) {
        callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.IN_PROGRESS);
        try {
            task.run();
            if (callback.isCancelled()) {
                callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.CANCELLED);
            } else {
                callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.COMPLETED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.CANCELLED);
        } catch (CaptchaRequiredException e) {
            // 挂起：本轮不再继续这个任务，也不按失败计——用户完成验证后由续跑轮带着凭证重跑。
            // 必须在 catch (Exception) 之前：它是 RuntimeException，落到通用分支会被误报成失败。
            callback.onCaptchaRequired(e);
            callback.onTaskStatusChanged(e.getTaskId(), TaskItem.TaskStatus.WAITING_CAPTCHA);
        } catch (Exception e) {
            if (callback.isCancelled()) {
                callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.CANCELLED);
                return;
            }
            callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.ERROR);
            callback.onTaskError(taskId,
                    context.getString(R.string.task_failed_format, TaskIds.displayName(context, taskId),
                            e.getMessage() != null ? e.getMessage() : e.toString()),
                    e);
        }
    }

    /** 待执行目标为空（未勾选任何板块/游戏）的统一处理：任务置错误态 + 回调错误提示，调用方据此直接 return。 */
    private void reportEmptyTargets(String taskId, int emptyMessageRes) {
        callback.onTaskStatusChanged(taskId, TaskItem.TaskStatus.ERROR);
        callback.onError(context.getString(emptyMessageRes));
    }

    /** 执行米游币社区签到：未配置板块时报错，否则提交 BBSDaily.runTask。 */
    private void executeBbsDaily(String[] forums) {
        if (forums == null || forums.length == 0) {
            reportEmptyTargets(TaskIds.BBS_DAILY, R.string.task_bbs_daily_no_forum);
            return;
        }
        executeTask(TaskIds.BBS_DAILY, () -> {
            BBSDaily bbsDaily = new BBSDaily(context, userId, notifier, controller);
            bbsDaily.runTask(forums);
        });
    }

    /**
     * 并发执行游戏签到（并发度 2，由 {@link #GAME_SIGN_POOL} 保证）。每款游戏内部仍保留请求前后 2~9 秒随机
     * 风控延时，跨游戏并行只提高请求密度、不改变单游戏节奏，429 仍由 {@code BBSGameDaily} 冷却逻辑兜底。
     */
    private void executeGameDaily(List<String> games) {
        if (games == null || games.isEmpty()) {
            // 「游戏签到总开关开着但一款没勾」时列表里没有任何游戏行，写状态只会留下一个野键
            // （旧版即写入了未格式化的 R.string.task_name_game_sign_in），故只提示不落状态。
            callback.onError(context.getString(R.string.task_game_sign_in_no_game));
            return;
        }
        List<Future<?>> futures = new ArrayList<>(games.size());
        for (String gameName : games) {
            if (callback.isCancelled()) break;
            futures.add(GAME_SIGN_POOL.submit(() -> {
                String displayName = MiHoYoBBSConstants.game_to_display_name(context, gameName);
                executeTask(TaskIds.gameSignIn(gameName), () -> {
                    BBSGameDaily gameModule = new BBSGameDaily(context, userId, gameName, notifier, controller);
                    notifier.notifyListeners(context.getString(R.string.task_sign_preparing, displayName));
                    gameModule.run();
                });
            }));
        }
        if (!awaitAll(futures)) return;
        notifier.notifyListeners(context.getString(R.string.task_game_sign_in_done));
    }

    /** 云游戏按 id 过滤：口径同 {@link #filterGames}，id 为 {@code cloud_game:<game>}。 */
    private List<String> filterCloudGames(String[] games, java.util.Set<String> onlyIds, boolean runAll) {
        List<String> result = new ArrayList<>();
        if (games == null) return result;
        for (String game : games) {
            if (runAll || onlyIds.contains(TaskIds.cloudGame(game))) result.add(game);
        }
        return result;
    }

    /**
     * 执行单个云游戏签到（云原神 / 云绝区零）。token 未配置/失效均以异常抛出，由 {@link #executeTask} 记为 ERROR。
     */
    private void executeCloudGame(String gameName) {
        String displayName = CloudGameDaily.displayName(context, gameName);
        executeTask(TaskIds.cloudGame(gameName), () -> {
            CloudGameDaily cloudGame = new CloudGameDaily(context, userId, gameName, notifier);
            notifier.notifyListeners(context.getString(R.string.task_sign_preparing, displayName));
            cloudGame.run();
        });
    }

    /** 执行森空岛签到（指定游戏：明日方舟或终末地）。 */
    private void executeSklandDaily(int gameId) {
        String taskId = gameId == SklandDaily.GAME_ARKNIGHTS
                ? TaskIds.SKLAND_ARKNIGHTS : TaskIds.SKLAND_ENDFIELD;
        executeTask(taskId, () -> {
            SklandDaily skland = new SklandDaily(context, userId, notifier, gameId);
            skland.run();
        });
    }
}
