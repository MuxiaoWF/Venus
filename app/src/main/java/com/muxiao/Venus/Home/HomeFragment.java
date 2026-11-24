package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.show_error_dialog;
import static com.muxiao.Venus.common.tools.show_warning_dialog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.views.GT3GeetestButton;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Setting.SettingsFragment;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.AccountStatusKind;
import com.muxiao.Venus.common.ui.compose.HomeAccountStatus;
import com.muxiao.Venus.common.ui.compose.HomeScreenKt;
import com.muxiao.Venus.common.ui.compose.HomeScreenView;
import com.muxiao.Venus.common.ui.compose.HomeTaskRow;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * 主页控制器（外壳迁移到 Compose 后不再持有 Fragment 生命周期）：用户下拉、任务列表、前台/后台执行、
 * 状态实时更新、日志查看与配置入口。
 *
 * <p>生命周期契约见 {@link PageController}：show/hide 由 MainActivity 按页面选中态与 Activity 恢复态折算调用；
 * createView 在外壳首次组合时调用一次；release 在 Activity 销毁时调用。
 */
public class HomeFragment implements PageController {

    private final MainActivity activity;

    private UserManager userManager;
    /**
     * Compose 宿主：本页唯一 UI 入口，视图字段全部收敛于此；本类只做「取数据 → 写状态 → 处理回调」。
     */
    private HomeScreenView screenView;
    private static volatile Future<?> currentTaskFuture;
    /** 当前下拉框中的用户名（顺序即下标契约，Compose 回调按下标取人）。 */
    private List<String> currentUsernames = new ArrayList<>();
    /** 底栏覆盖高度订阅（弱引用持有，必须由本类强持有并在 release 解除）。 */
    private final Runnable bottomBarListener = () -> {
        if (screenView != null) screenView.setBottomClearance(BottomBarOverlay.get());
    };
    private HomeViewModel viewModel;

    /** show() 早于 createView 到达的挂起标记（见 show() 守卫与 createView 末尾补发）。 */
    private boolean pendingShow;
    private BroadcastReceiver taskStateReceiver;
    private BroadcastReceiver taskStatusReceiver;
    /** UiState 观察者（show 期间注册 / release 时移除，替代原 getViewLifecycleOwner 观察）。 */
    private final Observer<HomeViewModel.TaskUiState> uiObserver = this::render;

    /** 主页就绪回调：MainActivity 据此触发后台人机验证（替代原 FragmentResult 广播）。 */
    private Runnable homeReadyCallback;

    /**
     * 前台验证流程是否在进行（单飞保护）：通知点击、`ACTION_CAPTCHA_REQUIRED` 广播、onResume 发现待验证标记
     * 三个入口可能同时触发，重复走一遍会各发一次 API1、各消费一次 challenge，表现为验证始终不通过。
     *
     * <p>{@link #captchaStartedAt} 给它一个有效期（与后台协调者的 5 分钟等待同量级）：用户中途离开、
     * 验证 UI 被销毁但没有回调收尾时，标记不会永久卡住后续任务的验证。
     */
    private volatile boolean captchaInProgress;
    private volatile long captchaStartedAt;
    /**
     * 当前正在验证的 challenge。单飞只拦「同一份 challenge 的重复触发」（通知/广播/onResume 三入口），
     * 换了 challenge 就属于新一轮验证，必须放行——否则上一轮没点开就被取消时，标记会卡住 5 分钟，
     * 期间所有新挂起都弹不出验证框。
     */
    private volatile String captchaInFlightChallenge;

    /**
     * 前台挂起失败时的兜底名单（按账号）：取不到挑战时 pending 不落库，验证成功后靠它触发续跑。
     */
    private final java.util.Map<String, java.util.List<String>> captchaFallbackIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 最近一次验证结果（solution）的归属用户。
     *
     * <p>与「当前展示用户」是两件事：挂起可能发生在 A，用户验证时前台已切到 B，或验证框是在
     * pending 过期后由通知重入的。轮末收口若拿 {@code viewModel.getCurrentUser()} 去读 solution，
     * 会因 {@link CaptchaStore#getSolution} 的按用户校验而读到 null，于是三个分支全部落空、
     * 最后落到「重新挂起」——用户看到的是「明明验过了，框又弹出来，状态还是待验证」。
     */
    private volatile String captchaSolutionUserId;

    /**
     * 本轮运行期间完成了人机验证。
     *
     * <p>置真后由前台轮在 {@code executeAll} 返回处立即**同线程补跑**挂起项（见
     * {@link #rerunSolvedCaptchaInRound}），而不是排一个 Worker：Worker 起来会撞「手动运行占用」
     * 判断，只能 15 秒一次重排到本轮结束——用户盯着的其实不是验证，而是剩余任务的 2~9 秒风控延时。
     */
    private volatile boolean captchaSolvedInRound;

    /**
     * 挂起流程（取 API1 挑战 + 落库）正在执行。
     *
     * <p>与 {@link #captchaInProgress} 是两件事：这个表示「挑战还没拿到」，那个表示「验证框已经在屏上」。
     * 两者之间的空档里，用户会先看到列表变成「待验证」——如果这时他点任务行，旧实现会自己再发一次 API1，
     * 于是同一轮出现两份互不匹配的 challenge：后落库的那份才是续跑要用的，用户在前一份上验完等于白做
     * （现象就是「点了条目、验完了，任务还是不动」）。有这个标记，点击路径会等挂起流程自己回调。
     */
    private volatile boolean captchaSuspending;

    /**
     * 轮末收口标记：{@code executeAll} 已返回、任务全部结束，只剩 finally 里的收尾。
     * {@link #isTaskRunning()} 据此提前返回 false——否则轮末排的「验证完成续跑」会被 Worker 的
     * 「手动运行占用」判断挡回，白等 5 分钟复查。
     */
    private static volatile boolean sRoundClosing;

    /**
     * 单飞保护的有效期：超过这个时间视为上一轮已作废，允许重新触发。
     * 极验 challenge 本身约 10 分钟失效，这里的 5 分钟只需要覆盖「用户打开验证框、中途离开、
     * UI 被销毁但没回调收尾」这一种情形，取与 challenge 寿命同量级即可。
     */
    private static final long CAPTCHA_TRIGGER_TTL_MS = 5 * 60 * 1000L;

    /** 前台验证队列：多账号排队，一次只挂一个极验控件。 */
    private final CaptchaQueue captchaQueue = new CaptchaQueue();

    /**
     * 任务提示转发器：单账号运行、重试失败、批量运行三条前台路径共用。
     *
     * <p>原先是 {@link #createView} 的局部变量，靠匿名类闭包被那几个监听器捕获——批量入口
     * {@link #runBatchAccount} 是独立方法，作用域里拿不到它，故提为字段。落盘监听在构造时绑定一次：
     * createView 是外壳首次组合时才调用的惰性入口，若等它再绑，期间由通知/广播触发的运行提示就全部丢失。
     */
    private final tools.StatusNotifier notifier = new tools.StatusNotifier();

    /**
     * 批量运行中当前正在跑的账号（与界面展示账号无关）。
     */
    private volatile String runningRoundUser;

    /**
     * 应用内批量轮是否还在跑。
     *
     * <p>验证码成功时必须区分「单账号轮」与「批量轮」：单账号轮可以复用本页的 {@code TaskExecutor}
     * 立刻补跑挂起项；批量轮此刻还在跑别的账号，补跑只能排带 {@code KEY_RESUME_USER} 的续跑 Worker，
     * 否则 {@link #captchaSolvedInRound} 会被置真却没人消费——用户看到的就是「验完了，待验证还在那里」。
     */
    private volatile boolean batchRoundActive;

    /** 回前台自愈的节流（见 {@link #maybeResumeCaptchaOnResume()}）：避免反复切页反复 REPLACE 续跑轮。 */
    private static final long AUTO_RESUME_THROTTLE_MS = 60_000L;
    private volatile long lastAutoResumeAt;

    public HomeFragment(MainActivity activity) {
        this.activity = activity;
        // 只绑一次：StatusNotifier 不是广播，漏绑时 notifyListeners 会静默丢弃所有任务进度。
        notifier.addListener(message -> tools.writeLog(activity, message));
    }

    /** 就绪回调注入（MainActivity 在 onCreate 里注册）。 */
    public void setHomeReadyCallback(Runnable callback) {
        homeReadyCallback = callback;
    }

    /** 控制器是否已就绪（视图已创建）：人机验证触发链的守卫条件。 */
    public boolean isReady() {
        return screenView != null;
    }

    /**
     * 构建主页 UI（外壳首次组合时调用一次）：绑定视图、初始化下拉与任务列表、注册按钮与日志查看，并按
     * ViewModel 的单一数据流渲染。
     */
    public View createView(Context context) {
        if (screenView != null) return screenView; // 幂等：外壳重组不会重复装配
        HomeScreenView root = new HomeScreenView(context);
        screenView = root;

        // ViewModel 作为 UI 状态单一可信数据源（Activity 作用域，与原 Fragment 作用域等效）
        viewModel = new ViewModelProvider(activity).get(HomeViewModel.class);

        // 页眉日期 chip 在用户上下文就绪后按「状态归属日」渲染（见 updateDateChip），此处不预置今天的日期：
        // 预置会在跨天/看昨日状态时先闪一个错误日期

        // 内容不做容器级避让：列表底部留出底栏覆盖高度，末项可完整滚出玻璃区（把高度喂成组合状态）
        BottomBarOverlay.addListener(bottomBarListener);
        root.setBottomClearance(BottomBarOverlay.get());

        userManager = new UserManager(context);
        controller.createUtils();
        tools.cleanOldLogs(context);

        // 初始化下拉框（当前用户由 ViewModel 持有，避免控制器与 VM 双份真相）
        // 账号列表取全部账号（含跨服），服别由行内后缀表达
        List<String> usernames = userManager.getAllUsernames();
        String initialUser = userManager.getCurrentUser();
        if (!initialUser.isEmpty() && usernames.contains(initialUser)) {
            viewModel.setCurrentUser(initialUser);
        } else if (!usernames.isEmpty()) {
            viewModel.setCurrentUser(usernames.get(0));
        } else {
            viewModel.setCurrentUser("");
        }
        // 账号行/下拉数据：必须先确定当前用户再重建列表，
        // 否则读到空桶导致任务全部显示 pending（原实现刷新先于用户初始化，分桶后不成立）
        applyUserList(usernames);
        viewModel.refreshTaskItems();
        updateDateChip();
        root.setOnUserSelectedListener(index -> {
            if (index < 0 || index >= currentUsernames.size()) return;
            String picked = currentUsernames.get(index);
            viewModel.setCurrentUser(picked);
            userManager.setCurrentUser(picked);
            // 切换账号：账号行文案、下拉选中项、头像与按账号分桶的任务列表一起重建。
            // 只刷新头像与任务列表会漏掉账号行文案——那一个是独立写进 Compose 状态的。
            renderUserContext();
        });
        // 状态条与下拉同义：点击某账号 = 主列表切到它。回传的是账号存储 id——
        // 状态条只展示「配置了任务的账号」，行下标与全量账号列表不对齐，不能按下标映射
        root.setOnAccountStatusSelectedListener(username -> {
            if (username.isEmpty()) return;
            if (username.equals(viewModel.getCurrentUser())) return;
            viewModel.setCurrentUser(username);
            userManager.setCurrentUser(username);
            renderUserContext();
        });
        // 无用户空态引导：账号行点击跳转用户管理页（而非弹出一个空下拉）
        root.setOnJumpToUsersListener(() -> activity.navigateToPage(1));

        // 单向数据流：观察 ViewModel 的不可变 UiState，一次性渲染任务列表 + 全部按钮可见性
        viewModel.getUiState().observeForever(uiObserver);

        root.setOnStartDailyListener(() -> {
            // 无用户时引导添加而非运行后报错
            if (interceptIfNoUser()) return;
            // 定时任务执行中不允许叠加手动运行：两路 TaskExecutor 的状态写入会互相覆盖
            if (DailyTaskWorker.isRunning()) {
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_scheduled_task_running));
                return;
            }
            // 当日已全部完成：先问一句再跑，不在这里擅自重复签到（确认回调里才走下面的运行体）
            String current = viewModel.getCurrentUser();
            Runnable run = () -> {
            // 开跑前记下本轮目标：进程被杀 / 页面被回收后凭它补跑未完成项。
            // 用户主动取消会在取消回调里清快照，不在这里误判成中断。
            RunRecovery.start(context, java.util.Collections.singletonMap(current,
                    TaskSettings.fromPreferences(context, current).getTaskIds()));
            // 清空今日日志失败只提示、不阻断本轮任务（与原有行为一致）；属轻微故障，用 Snackbar 而非错误弹窗
            if (!clearLogFile())
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_clear_log_failed));
            tools.writeLogSeparator(context);
            // 新一轮运行：仅复位瞬态、保留当日终态；任务被真正执行到时才逐个切回进行中，中途终止不丢已完成记录
            viewModel.resetForNewRun();
            viewModel.persistCurrentUserForWidget();

            // 清空触达集合后切 FOREGROUND 并重建列表：未触达的已完成任务派生为 RETAINED 展示态
            viewModel.beginRun();
            viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);
            viewModel.refreshTaskItems();
            // 本轮的运行账号必须在启动时刻钉死：任务跑着的时候用户可以从账号行切到别的账号，
            // 而验证码请求/凭证/状态桶都按账号隔离，后面任何一处读「当前展示账号」都会写错桶。
            final String runUser = viewModel.getCurrentUser();
            captchaSolvedInRound = false;
            captchaSolutionUserId = null;

            runForegroundRound(runUser, null);
            };
            // 全部完成时弹确认，确认后的回调执行上面的 run；否则直接 run
            if (current != null && !current.isEmpty()
                    && DailyTaskScheduler.allEnabledTasksCompletedToday(context, current)) {
                VenusDialogs.confirm(activity,
                        activity.getString(R.string.dialog_rerun_title),
                        activity.getString(R.string.msg_rerun_confirm),
                        activity.getString(R.string.btn_rerun),
                    run,
                        activity.getString(R.string.btn_cancel));
            } else {
                run.run();
            }
        });

        // 「全部账号」批量入口
        root.setOnRunAllListener(() -> {
            if (DailyTaskWorker.isRunning()) {
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_scheduled_task_running));
                return;
            }
            List<String> targets = MultiUserRunner.filterRunnable(context, userManager.getAllUsernames());
            if (targets.isEmpty()) {
                if (interceptIfNoUser()) return;
                runOnUi(() -> show_warning_dialog(context, activity.getString(R.string.err_set_task_first)));
                return;
            }
            Runnable run = () -> {
                if (!clearLogFile())
                    tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_clear_log_failed));
                tools.writeLogSeparator(context);
                viewModel.resetForNewRun();
                viewModel.persistCurrentUserForWidget();
                runBatchAccounts(targets, null);
            };
            // 批量入口同样要确认：所有目标账号当日启用任务都完成时才问，避免误触重复签到
            boolean allDone = true;
            for (String user : targets) {
                if (!DailyTaskScheduler.allEnabledTasksCompletedToday(context, user)) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                VenusDialogs.confirm(activity,
                        activity.getString(R.string.dialog_rerun_title),
                        activity.getString(R.string.msg_rerun_confirm),
                        activity.getString(R.string.btn_rerun),
                    run,
                        activity.getString(R.string.btn_cancel));
            } else {
                run.run();
            }
        });

        // 重试失败任务：只重跑当日 ERROR 状态的任务，已完成项不重复执行
        root.setOnRetryFailedListener(() -> {
            // 无用户时引导添加（与启动按钮同口径）
            if (interceptIfNoUser()) return;
            // 定时任务执行中不允许叠加（两路 TaskExecutor 的状态写入会互相覆盖）
            if (DailyTaskWorker.isRunning()) {
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_scheduled_task_running));
                return;
            }
            // 跨账号收集失败项；多账号时走批量串行，单账号保持原路径
            Map<String, Set<String>> failedByUser = collectFailedByUser();
            if (failedByUser.isEmpty()) return;
            tools.writeLogSeparator(context);
            if (failedByUser.size() > 1) {
                runBatchAccounts(new ArrayList<>(failedByUser.keySet()), failedByUser);
                return;
            }
            String onlyUser = failedByUser.keySet().iterator().next();
            Set<String> failedIds = failedByUser.get(onlyUser);
            if (!onlyUser.equals(viewModel.getCurrentUser())) {
                runBatchAccounts(new ArrayList<>(failedByUser.keySet()), failedByUser);
                return;
            }
            // 按钮可见性由 ERROR 状态派生，正常不会为空；切用户/改配置等竞态下兜底
            if (failedIds == null || failedIds.isEmpty()) return;

            // 刻意不调用 resetForNewRun：保留已完成/失败终态，重跑项执行到时逐个覆盖；未触达的已完成项
            // 在重试期间按 RETAINED 展示
            viewModel.beginRun();
            viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);
            viewModel.refreshTaskItems();
            // 同「运行签到」：把运行账号钉死，验证码与状态写入都不跟随展示账号漂移
            // 只补跑这批失败项：中断恢复时名单来自这里，不会顺带把已完成任务重跑一遍
            RunRecovery.start(context, java.util.Collections.singletonMap(onlyUser, failedIds));
            captchaSolvedInRound = false;
            captchaSolutionUserId = null;
            runForegroundRound(onlyUser, failedIds);
        });

        root.setOnCancelDailyListener(() -> VenusDialogs.confirm(
                activity,
                activity.getString(R.string.dialog_confirm_cancel),
                activity.getString(R.string.msg_confirm_cancel_current_task),
                activity.getString(R.string.btn_ok),
                () -> {
                    // 用户主动中断：撤销快照，回前台时不做「意外中断」补跑
                    RunRecovery.finish(context);
                    if (currentTaskFuture != null) {
                        currentTaskFuture.cancel(true);
                        controller.destroyButton();
                        controller.createUtils();
                    }
                    notifier.notifyListeners(activity.getString(R.string.task_cancelled));
                },
                activity.getString(R.string.btn_continue_task)));

        // 点「未配置」任务项：直达设置页对应凭证抽屉（划线只提示不解决，这里补上后半程）
        root.setOnDisabledTaskClickListener(this::onDisabledTaskClick);
        // 点「待验证」任务项：重新拉起验证。通知是本页之外的唯一入口，被划掉后这条列表入口就是兜底
        root.setOnCaptchaClickListener(this::performBackgroundCaptchaVerification);

        root.setOnBackgroundClickListener(() -> {
            if (ForegroundTaskService.isRunning()) {
                VenusDialogs.confirm(
                        activity,
                        activity.getString(R.string.dialog_confirm_cancel),
                        activity.getString(R.string.msg_confirm_cancel_background_task),
                        activity.getString(R.string.btn_ok),
                        () -> {
                            // 主动取消后台任务：服务侧 cancelTask 也会清，这里先清避免服务已被回收时漏网
                            RunRecovery.finish(context);
                            Intent stopIntent = new Intent(context, ForegroundTaskService.class);
                            stopIntent.setAction(ForegroundTaskService.ACTION_STOP_TASK);
                            context.startService(stopIntent);
                            // 不在此立即置 IDLE：等待服务 cancelTask() 广播 is_running=false，
                            // 由 applyBackgroundRunning(false) 统一切回 IDLE，避免按钮状态提前翻转
                        },
                        activity.getString(R.string.btn_continue_task));
                return;
            }

            // 可跑账号 ≥2 时询问范围：仅当前账号 / 全部账号（复用小组件 RUN_TASKS 的批量链路，
            // Service 侧本就支持 EXTRA_USER_IDS；前台「全部账号」入口的应用内批量不受影响）
            java.util.List<String> runnableUsers =
                    MultiUserRunner.filterRunnable(context, userManager.getAllUsernames());
            if (runnableUsers.size() >= 2) {
                VenusDialogs.confirm(
                        activity,
                        activity.getString(R.string.dialog_bg_scope_title),
                        activity.getResources().getQuantityString(
                                R.plurals.dialog_bg_scope_message, runnableUsers.size(), runnableUsers.size()),
                        activity.getString(R.string.btn_bg_all_accounts, runnableUsers.size()),
                        () -> checkAndStartTask(runnableUsers),
                        activity.getString(R.string.btn_bg_current_account),
                        () -> checkAndStartTask(java.util.Collections
                                .singletonList(viewModel.getCurrentUser())));
                return;
            }

            checkAndStartTask(java.util.Collections.singletonList(viewModel.getCurrentUser()));
        });

        // 根据设置显示/隐藏后台运行按钮（经 UiState 派生）
        viewModel.refreshBgFeatureEnabled();

        root.setOnViewLogListener(() -> {
            try {
                // 每次点击都按「当天」重新定址：控制器可能在前一天创建，缓存旧路径会在跨天后
                // 读到空文件（而写入方已切到今天的文件），表现为「跑完任务却没有日志」。
                File logFile = tools.getTodayLogFile(activity);
                if (logFile.exists() && logFile.length() > 0) {
                    List<String> lines = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                        String line;
                        while ((line = reader.readLine()) != null)
                            lines.add(line);
                    }
                    java.util.Collections.reverse(lines);
                    StringBuilder content = new StringBuilder();
                    for (String l : lines) content.append(l).append("\n");

                    // 日志内容对话框（Compose 版：等宽可滚动文本 + 清空入口）
                    VenusDialogs.scrolledText(
                            activity,
                            activity.getString(R.string.dialog_log_title, logFile.getName()),
                            content.toString(),
                            activity.getString(R.string.btn_ok),
                            activity.getString(R.string.btn_clear_log),
                            () -> {
                                if (clearLogFile())
                                    tools.showCustomSnackbar(screenView, context, activity.getString(R.string.snack_log_cleared));
                                else
                                    tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_clear_log_failed));
                            });
                } else {
                    tools.showCustomSnackbar(screenView, context, activity.getString(R.string.snack_no_log));
                }
            } catch (IOException e) {
                tools.show_error_dialog(context, activity.getString(R.string.err_read_log_failed, tools.describeError(e)));
            }
        });

        // 视图已就绪：主动通知宿主一次。show() 依赖外壳的页选回调，理论上会在同一帧稍后执行并再通知一次
        // （宿主侧按 captchaPending 去重，重复无副作用）；这里兜住「页选回调早于 createView 而 show() 被
        // 守卫挡掉、之后再无第二次回调」的时序，否则后台正在等待的验证码永远不会被挂出来。
        if (homeReadyCallback != null) homeReadyCallback.run();
        return root;
    }

    private final GeetestController controller = new GeetestController() {
        private GT3GeetestUtils gt3GeetestUtils;
        public GT3GeetestButton geetestButton;

        @Override
        public void createUtils() {
            if (gt3GeetestUtils == null) {
                gt3GeetestUtils = new GT3GeetestUtils(activity);
            }
        }

        @Override
        public void createButton(GT3ConfigBean gt3ConfigBean) {
            Logger.debug("VenusCaptcha", "Foreground controller createButton called");
            createUtils();
            runOnUi(() -> {
                Logger.debug("VenusCaptcha", "Running on UI thread, creating button");
                // 容器由 Compose 宿主急切创建（见 HomeScreenView.geetestContainer），
                // SDK 只认真实 ViewGroup，故这层桥接保留
                if (screenView == null) {
                    // 没有视图可挂载：放开单飞保护，否则 5 分钟内再也弹不出验证框，
                    // 用户只会看到列表停在「待验证」
                    Logger.debug("VenusCaptcha", "createButton aborted: screenView is null");
                    finishCaptchaAttempt();
                    return;
                }
                LinearLayout container = screenView.getGeetestContainer();
                try {
                    // 动态创建 GT3GeetestButton；已存在则先销毁
                    if (geetestButton != null) {
                        container.removeView(geetestButton);
                        geetestButton = null;
                    }
                    geetestButton = new GT3GeetestButton(activity);
                    // 内容居中 + 显式横向：SDK 官方 XML 用法即 center/horizontal，但 SDK 内部不设，
                    // LinearLayout 默认 TOP|START，定高 44dp 时剩余部分表现为按钮底部一条空白（白底渐变上极显眼）。
                    geetestButton.setGravity(Gravity.CENTER);
                    geetestButton.setOrientation(LinearLayout.HORIZONTAL);
                    // 布局参数：宽 290dp、高 44dp，刻意不设垂直外边距——上下留白由容器 marginTop 与分隔线
                    // 各出 space_4 提供（见 HomeScreen 留白契约），再叠加会变成上下不对称的观感。
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            (int) (290 * getResources().getDisplayMetrics().density),
                            (int) (44 * getResources().getDisplayMetrics().density)
                    );
                    params.gravity = Gravity.CENTER_HORIZONTAL;
                    geetestButton.setLayoutParams(params);
                    container.addView(geetestButton);
                    screenView.setGeetestVisible(true);
                    // 兜底重建：run 结束时的 destroyButton 会把 utils 置空，若两者交错到这之前，
                    // 这里会是 null —— 直接 init 会 NPE，被下面的 catch 吞掉，表现为「按钮不出现」。
                    if (gt3GeetestUtils == null) gt3GeetestUtils = new GT3GeetestUtils(activity);
                    gt3GeetestUtils.init(gt3ConfigBean);
                    geetestButton.setGeetestUtils(gt3GeetestUtils);
                } catch (Exception e) {
                    // 验证控件没建出来 = 本轮验证不可能完成，必须显式收尾并告知用户。
                    Logger.e("Error creating GT3GeetestButton", e);
                    finishCaptchaAttempt();
                    // 应用内弹窗提示：这条链路只可能发生在界面里，没必要再发系统通知
                    show_error_dialog(activity,
                            activity.getString(R.string.notif_captcha_failed),
                            activity.getString(R.string.geetest_ui_init_failed));
                }
            });
        }

        @Override
        public void destroyButton() {
            runOnUi(() -> {
                if (geetestButton != null && screenView != null) {
                    screenView.getGeetestContainer().removeView(geetestButton);
                    geetestButton = null;
                    screenView.setGeetestVisible(false);
                }
            });
            destroyUtils();
        }

        @Override
        public GT3GeetestUtils getGeetestUtils() {
            return gt3GeetestUtils;
        }

        /** 销毁验证码工具（destroyButton 内部调用，无需经接口暴露）。 */
        public void destroyUtils() {
            if (gt3GeetestUtils != null) {
                gt3GeetestUtils.destory();
                gt3GeetestUtils = null;
            }
        }
    };

    /**
     * 由 MainActivity 调用，展示人机验证 UI；验证结果落库后触发续跑，不再「回传」给等待中的线程。
     *
     * <p>数据全部来自 {@link CaptchaStore}：后台挂起时已把 gt/challenge/headers 与挂起任务 id 落库，
     * 这里只读不消费——用户第一次关了验证框，再点通知仍能用同一份 challenge 重试。
     *
     * <p>入口不止一个（通知点击、`ACTION_CAPTCHA_REQUIRED` 广播、onResume 发现待验证标记），三处可能在同一
     * 时刻各触发一次；重复进来会各自弹一个验证框、各消费一次 challenge。故用 {@link #captchaInProgress}
     * 做单飞保护，由本轮的成功/失败/异常三条出口复位。
     */
    private void finishCaptchaAttempt() {
        captchaInProgress = false;
        captchaInFlightChallenge = null;
    }

    /** 为当前展示账号弹出验证。 */
    public void performBackgroundCaptchaVerification() {
        performBackgroundCaptchaVerificationFor(viewModel.getCurrentUser());
    }

    /**
     * 为指定账号弹出验证。
     *
     * <p>公开给 MainActivity：通知/广播到达时挂起账号已经确定，不能让「当前展示账号」在控制器就绪前
     * 再漂移一次——否则 pending 读的是 A，验证 UI 和续跑却按 B 走。
     */
    public void performBackgroundCaptchaVerificationFor(String targetUser) {
        // 读目标账号自己的 pending
        // 主线程同步读缓存（ConfigRepository 是热读门面，不碰磁盘），用 challenge 判重：
        // 同一份 challenge 的重复触发才拦，换了 challenge 一律放行
        final String viewingUser = targetUser == null ? "" : targetUser;
        if (viewingUser.isEmpty()) return;
        CaptchaStore.Pending current = CaptchaStore.getPending(activity, viewingUser);
        // 挑战还在取（挂起流程在跑）：此刻绝不能自己再发一次 API1。用户在空档里点任务行时，旧实现会
        // 另取一份 challenge 并先弹出验证框，随后挂起流程落库的却是另一份——两份互不匹配，用户在前一份
        // 上验完，续跑带过去的凭证无效。挂起流程结束时会带着同一份 challenge 回调本方法。
        if (current == null && captchaSuspending) {
            Logger.debug("VenusCaptcha", "challenge request in flight, wait for it instead of starting another");
            return;
        }
        // 已经验证过、只是还没续跑（轮末漏掉 / 进程被杀 / 续跑被丢弃）：再弹一次验证毫无意义——
        // 新 challenge 与已有 solution 无关，用户做完还是同一个结果，表现为「点了条目又冒一个无效验证框」。
        // 直接走续跑；没有可续跑的项就把残留的「待验证」复位，避免列表永久卡在这个状态。
        if (current == null) {
            if (CaptchaStore.getSolution(activity, viewingUser) != null) {
                Logger.debug("VenusCaptcha", "solution already present, resume instead of showing captcha again");
                resumeAfterCaptcha(viewingUser, collectWaitingCaptchaIds(viewingUser));
                // 已无需验证：出队并推进下一个
                finishQueuedCaptcha(viewingUser);
                return;
            }
        }
        String challengeKey = current != null ? current.challenge : null;
        boolean sameChallenge = challengeKey != null && challengeKey.equals(captchaInFlightChallenge);
        if (captchaInProgress && sameChallenge
                && System.currentTimeMillis() - captchaStartedAt < CAPTCHA_TRIGGER_TTL_MS) {
            Logger.debug("VenusCaptcha", "verification already in progress for same challenge, skip");
            return;
        }
        Logger.debug("VenusCaptcha", "performBackgroundCaptchaVerification, pending="
                + (current != null) + ", challenge=" + challengeKey);
        captchaInProgress = true;
        captchaInFlightChallenge = challengeKey;
        captchaStartedAt = System.currentTimeMillis();
        // Geetest.geetest() 包含同步网络请求，必须在后台线程执行
        AppExecutors.get().execute(() -> {
            // 归属账号先定下来：catch 分支也要用它标记「已看过」，不能只在 try 内可见
            final String solveUserId = current != null && current.userId != null
                    ? current.userId : viewingUser;
            try {
                // 记下 solution 的归属者：验证可能在前台切了账号之后完成，轮末收口要用它读 solution
                captchaSolutionUserId = solveUserId;
                // 优先用挂起时保存的 headers（含 Cookie），确保 API2 二次验证绑定同一会话；
                // 拿不到就按当前用户现构一份完整的——裸 get_bbs_headers() 里没有 Cookie，
                // API2 二次验证会绑定不到账号会话，用户验完拿到的是无效凭证。
                Map<String, String> headers = current != null && current.headers != null
                        && !current.headers.isEmpty()
                        ? new HashMap<>(current.headers) : buildCaptchaHeaders(solveUserId);
                final java.util.List<String> suspendedIds =
                        mergeSuspendedIds(current, captchaFallbackIds.get(solveUserId), solveUserId);
                // 验证全链路按挂起账号的服别取域名/DS salt：与 buildCaptchaHeaders 同口径，不读全局 is_oversea
                final boolean solveOversea = new UserManager(activity).isOversea(solveUserId);

                Notification notification = new Notification(activity);
                Geetest.GeetestVerificationCallback callback = new Geetest.GeetestVerificationCallback() {
                    @Override
                    public void onVerificationSuccess(Map<String, String> geetestCode) {
                        Logger.debug("VenusCaptcha", "Verification SUCCESS, persisting solution");
                        // 验证结果也要进「查看日志」：此前只有 logcat 的 debug 行，用户侧日志里
                        // 只看到任务停在待验证、随后突然完成，无法判断验证是否真的成功过。
                        tools.writeLog(activity, "captcha solved: " + solveUserId);
                        controller.destroyButton();
                        // 结果落库：续跑轮的任务从这里取到凭证、注入请求头，无需再次验证。
                        // 按账号写：只影响触发验证的那个账号，不碰其他账号已验好的凭证
                        CaptchaStore.saveSolution(activity, solveUserId, geetestCode);
                        CaptchaStore.clearPending(activity, solveUserId);
                        notification.dismissCaptchaNotification();
                        finishCaptchaAttempt();
                        captchaFallbackIds.remove(solveUserId);
                        // 名单重算：验证期间可能又有任务撞 1034，开头那份快照里没有它们
                        java.util.List<String> resumeIds =
                                mergeSuspendedIds(null, suspendedIds, solveUserId);
                        // 只有「单账号前台轮」能复用本页 TaskExecutor 做轮内补跑：批量轮此刻还在跑别的
                        // 账号，settings/Executor 都是别人的，轮内补跑既拿不到正确配置也没人消费
                        // captchaSolvedInRound（旧实现的表现：验完了，A 一直停在待验证）。
                        // 批量轮直接排续跑 Worker，它按 KEY_RESUME_USER 认准挂起账号，并自行等本轮结束。
                        if (isTaskRunning() && !batchRoundActive) {
                            // 本轮还在跑：交给轮内补跑（executeAll 返回处立刻同线程重跑），不排 Worker。
                            // 排 Worker 没有意义：它会被「手动运行占用」挡回并 15 秒一次重排到本轮结束，
                            // 用户要盯着「待验证」等完剩余任务（每款游戏 2~9 秒风控延时），而挂起的
                            // 这个任务自己早已抛异常结束。旧实现的 defer 走的正是这条慢路径。
                            captchaSolvedInRound = true;
                            Logger.debug("VenusCaptcha", "round active, rerun in-round: " + resumeIds);
                        } else {
                            resumeAfterCaptcha(solveUserId, resumeIds);
                        }
                        // 验证完成：出队并推进下一个
                        finishQueuedCaptcha(solveUserId);
                    }

                    @Override
                    public void onVerificationFailed(String error) {
                        Logger.debug("VenusCaptcha", "Verification FAILED: " + error);
                        tools.writeLog(activity, activity.getString(R.string.notif_captcha_failed)
                                + " (" + solveUserId + "): " + error);
                        controller.destroyButton();
                        // 验证明确失败：把挂起任务判为失败，让「重试失败任务」按钮出现。
                        // 只改仍是 waiting_captcha 的项；suspendedIds 是验证开始时的权威名单，不受切账号影响。
                        markCaptchaTasksFailed(solveUserId, suspendedIds);
                        // 只标记「已看过」，保留 challenge：用户可再点一次通知重试同一份验证
                        CaptchaStore.markPendingSeen(activity, solveUserId);
                        notification.dismissCaptchaNotification();
                        // 验证发生在前台，失败也用对话框告知，不发系统通知
                        runOnUi(() -> show_error_dialog(activity,
                                activity.getString(R.string.notif_captcha_failed), error));
                        finishCaptchaAttempt();
                        // 失败也出队
                        finishQueuedCaptcha(solveUserId);
                    }
                };

                // 用后台挂起时的同一 challenge 验证：重复取 API1 会拿到不匹配的 challenge（表现为反复 1034）
                if (current != null && current.challenge != null && !current.challenge.isEmpty()) {
                    Logger.debug("VenusCaptcha", "Using pending challenge: gt=" + current.gt);
                    Geetest.geetestWithChallenge(activity, current.gt, current.challenge,
                            headers, callback, controller, solveOversea);
                } else {
                    Logger.debug("VenusCaptcha", "No pending challenge, calling API1");
                    Geetest.geetest(activity, headers, callback, controller, solveOversea);
                }
            } catch (Exception e) {
                Logger.e("Exception in performBackgroundCaptchaVerification", e);
                finishCaptchaAttempt();
                // 保留 pending：异常多为瞬时故障（网络/渲染），用户可再点通知用同一 challenge 重试。
                // 同时强制提示一次——否则界面上什么都没有，用户只会看到任务停在「待验证」。
                CaptchaStore.markPendingSeen(activity, solveUserId);
                runOnUi(() -> show_error_dialog(activity,
                        activity.getString(R.string.notif_captcha_failed),
                        activity.getString(R.string.geetest_captcha_failed_network)));
                // 异常也出队
                finishQueuedCaptcha(solveUserId);
            }
        });
    }

    /**
     * 从持久层反查「当前处于待验证」的任务 id。
     *
     * <p>续跑名单的三个来源按可信度排序：pending 落库的名单 > 本轮内存兜底名单 > 这条持久层反查。
     * 最后一条覆盖的场景是：用户从列表点「待验证」重新唤起验证（此时 pending 可能已过期被清），
     * 或者应用被杀后重进——没有它，验证做完了却不知道该续跑谁，任务永远停在「待验证」。
     */
    private java.util.List<String> collectWaitingCaptchaIds(String userId) {
        java.util.List<String> ids = new ArrayList<>();
        if (userId == null || userId.isEmpty()) return ids;
        Map<String, String> statuses = new TaskStatusManager(activity).getAllStatuses(userId);
        for (Map.Entry<String, String> e : statuses.entrySet()) {
            if (TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(e.getValue())) ids.add(e.getKey());
        }
        Logger.debug("VenusCaptcha", "waiting captcha ids from store: " + ids);
        return ids;
    }

    /**
     * 前台运行结束时有任务挂在人机验证上：落库待验证请求并直接弹验证 UI。
     *
     * <p>应用此刻就在前台，不必等用户点通知——否则用户点了「开始任务」，只看到列表变成「待验证」，
     * 却没有任何可操作的入口。落库含一次 API1，放后台线程；弹 UI 回主线程。
     *
     * <p>**无论挂起是否成功都要弹**：`suspend` 失败通常是一次 API1 失败，此时 pending 没有落库，
     * 但验证仍然可以走 {@link Geetest#geetest}（它自己再取一次挑战）。早期实现只在成功时弹，
     * 结果就是「任务停在待验证、界面上什么都没有」——比验证失败更让人无从下手。
     */
    private void suspendAndShowCaptcha(String userId, Set<String> taskIds, Map<String, String> headers) {
        if (taskIds == null || taskIds.isEmpty()) return;
        // 归属账号：调用方给出正在跑的账号，缺省用当前展示账号
        final String owner = (userId == null || userId.isEmpty()) ? viewModel.getCurrentUser() : userId;
        if (owner.isEmpty()) return;
        // 已有挂起在途：入队，由在途那次结束后统一弹框
        if (captchaSuspending) {
            Logger.debug("VenusCaptcha", "suspend already in flight, queued: " + owner);
            captchaQueue.offer(owner);
            return;
        }
        captchaSuspending = true;
        Logger.debug("VenusCaptcha", "suspendAndShowCaptcha: " + owner + " / " + taskIds.size() + " task(s)");
        AppExecutors.get().execute(() -> {
            boolean suspended = false;
            try {
                suspended = CaptchaSuspension.suspend(
                        activity, owner, taskIds, headers, new Notification(activity),
                        // 应用内运行不发通知，验证 UI 由本方法挂到首页
                        false);
            } catch (Exception e) {
                Logger.e("VenusCaptcha: suspend failed", e);
                tools.writeLog(activity, activity.getString(R.string.captcha_challenge_failed)
                        + " (" + owner + ")");
            } finally {
                // 先放开再回调：performBackgroundCaptchaVerificationFor 靠这个标记区分
                // 「挑战还在取」与「挑战已拿到」，晚放会让自己的回调被挡掉
                captchaSuspending = false;
            }
            if (!suspended) {
                // pending 未落库：名单留在字段里供续跑使用
                captchaFallbackIds.put(owner, new ArrayList<>(taskIds));
                Logger.debug("VenusCaptcha", "suspend failed, fall back to inline verification for " + owner);
            }
            // 无论挂起成功与否都入队
            captchaQueue.offer(owner);
            runOnUi(() -> {
                // 挂起直接写持久层的 waiting_captcha，不经过 ViewModel；chip 的状态色与队列提示要在这里跟一次
                refreshAggregate();
                tryShowNextCaptcha();
            });
        });
    }

    /** 按队列顺序弹出下一个待验证账号（已有验证在途或挂起在途时不弹）。 */
    private void tryShowNextCaptcha() {
        if (captchaInProgress || captchaSuspending) return;
        String next = captchaQueue.peek();
        if (next == null || next.isEmpty()) return;
        Logger.debug("VenusCaptcha", "queue next: " + next + " (" + captchaQueue.size() + " waiting)");
        performBackgroundCaptchaVerificationFor(next);
    }

    /** 一次验证结束后的队列收尾：移出该账号并弹下一个。切换到主线程执行。 */
    private void finishQueuedCaptcha(String userId) {
        if (userId == null || userId.isEmpty()) return;
        runOnUi(() -> {
            captchaQueue.remove(userId);
            if (captchaQueue.isEmpty() && screenView != null) viewModel.refreshTaskItems();
            refreshAggregate();
            tryShowNextCaptcha();
        });
    }

    /**
     * 轮末收口：本轮有任务挂在人机验证上时的统一处理。
     *
     * <p>三种情形必须分开。旧实现只有「一律重新挂起并弹框」一条，于是：用户已经验完的那次被再弹一遍、
     * 正在输的那次被轮末的收尾直接销毁（按钮消失、challenge 作废）——这正是「验证了却没继续」的来源。
     */
    private void handleRoundEndCaptcha(String runUser, Set<String> taskIds, Map<String, String> headers) {
        // 先按「本轮运行账号」读验证结果，读不到再按 solution 的归属者读：验证期间切过账号时两者不同，
        // 拿展示用户去读会得到 null，于是 ① 落空 → 落到 ④ 重新挂起，又弹一次验证框。
        // runUser 由启动按钮在任务开始前钉死，不能在这里重读当前展示账号。
        String userId = (runUser == null || runUser.isEmpty()) ? viewModel.getCurrentUser() : runUser;
        boolean solved = CaptchaStore.getSolution(activity, userId) != null;
        String solutionOwner = captchaSolutionUserId;
        if (!solved && solutionOwner != null && !solutionOwner.isEmpty()
                && !solutionOwner.equals(userId)
                && CaptchaStore.getSolution(activity, solutionOwner) != null) {
            userId = solutionOwner;
            solved = true;
        }
        java.util.List<String> ids = mergeSuspendedIds(null, taskIds, userId);
        if (ids.isEmpty()) return;
        // ① 本轮真的验证完成了（挂起时会清掉旧 solution，此刻能读到就是这次的）：直接续跑
        if (solved) {
            // 验证成功时已经排过一次续跑：若那一轮已在执行（in_progress）或已经把目标项跑完
            // （completed），这里再 REPLACE 一次会取消正在跑的续跑轮、甚至让它重签到一遍。
            // 只有目标项仍停在「待验证 / 待执行」时才补排。
            if (!needsCaptchaResume(userId, ids)) {
                Logger.debug("VenusCaptcha", "round end: resume already handled " + ids);
                runOnUi(() -> viewModel.refreshTaskItems());
                return;
            }
            Logger.debug("VenusCaptcha", "round end: solution ready, resume " + ids);
            resumeAfterCaptcha(userId, ids);
            return;
        }
        // ② 验证框还在屏上：用户在操作，轮末既不能销毁它，也不能重发挑战（换 challenge 会让手上这份作废）
        if (captchaInProgress) {
            Logger.debug("VenusCaptcha", "round end: captcha in progress, leave it to the user");
            return;
        }
        // ③ pending 还在 = 验证框弹过、是用户自己关掉的：不再重弹（骚扰），保留它供列表/通知入口自行唤起
        if (CaptchaStore.getPending(activity, userId) != null) {
            Logger.debug("VenusCaptcha", "round end: pending kept, user dismissed the captcha");
            return;
        }
        // ④ 从未挂起成功（取挑战失败）：补一次，否则用户只能干等次日
        Logger.debug("VenusCaptcha", "round end: no captcha shown yet, opening it");
        suspendAndShowCaptcha(userId, new java.util.LinkedHashSet<>(ids), headers);
    }

    /**
     * 续跑名单里是否还有「还没开始跑」的项。
     *
     * <p>只有 {@code waiting_captcha}（仍在等）与 {@code pending}（被复位过）、以及尚未落库的项需要
     * 重新排续跑；{@code in_progress} 说明续跑轮已经在跑它，{@code completed}/{@code error} 是终态，
     * 再排一次只会让 REPLACE 取消正在执行的续跑轮、把已经签到的任务重跑一遍。
     *
     * <p>存在意义：验证成功时已立即排过续跑，轮末收口必须能识别「已经有人接手了」。
     */
    private boolean needsCaptchaResume(String userId, java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return false;
        Map<String, String> statuses = new TaskStatusManager(activity).getAllStatuses(userId);
        for (String id : ids) {
            String status = statuses.get(id);
            if (status == null
                    || TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(status)
                    || TaskStatusManager.STATUS_PENDING.equals(status)) return true;
        }
        return false;
    }

    /**
     * 合并本轮需要续跑的任务 id。三个来源取并集，而不是三选一：
     * <ul>
     *   <li>{@code pending.taskIds}：挂起落库那一刻已知的挂起项——立即挂起时只有第一个任务；</li>
     *   <li>本轮内存名单：挂起之后才撞 1034 的任务，落库时还没它们；</li>
     *   <li>持久层反查 {@code waiting_captcha}：应用被杀、或从通知/列表重入后唯一还活着的来源。</li>
     * </ul>
     */
    private java.util.List<String> mergeSuspendedIds(CaptchaStore.Pending pending,
                                                     java.util.Collection<String> roundIds,
                                                     String userId) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (pending != null) merged.addAll(pending.taskIds());
        if (roundIds != null) merged.addAll(roundIds);
        merged.addAll(collectWaitingCaptchaIds(userId));
        return new ArrayList<>(merged);
    }

    /**
     * 按当前用户现构一份可用于 API2 的请求头：基础 BBS 头 + Cookie。
     *
     * <p>只取 {@code get_bbs_headers()} 是不够的——里面没有 Cookie，API2 二次验证绑定不到账号会话，
     * 用户验完拿回的是无效凭证（续跑仍 1034，表现为「验证没用」）。
     */
    private Map<String, String> buildCaptchaHeaders(String userId) {
        // 按挂起账号的服务器类型构头：appId / package / Referer 随服不同，用全局值会在跨服账号上
        // 把验证绑定到错误的会话，表现为「验完了续跑还是 1034」
        boolean oversea = new UserManager(activity).isOversea(userId);
        Map<String, String> headers = new HashMap<>(new HeaderManager(activity, oversea).get_bbs_headers());
        String cookie = userId == null || userId.isEmpty()
                ? null : tools.buildUserCookie(activity, userId);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        else Logger.debug("VenusCaptcha", "buildCaptchaHeaders: no cookie for user=" + userId);
        return headers;
    }

    /**
     * 验证完成后触发续跑：只重跑本轮挂起过的任务，凭证已落库。
     *
     * <p>统一走定时链路的 Worker（而不是在本页起前台任务）：两条链路都能覆盖，且续跑本身可能耗时
     * 数分钟，不该绑在 Activity 生命周期上。Worker 的进度经广播回传，本页列表照常跟随。
     * 无挂起名单（例如用户从通知进来但 pending 已过期）时不触发，避免空跑一轮全量。
     */
    private void resumeAfterCaptcha(String userId, java.util.List<String> taskIds) {
        java.util.List<String> ids = taskIds;
        if (ids == null || ids.isEmpty()) {
            // 名单为空但验证确实完成了：多半是状态已被复位（跨天）或本轮没有把挂起项记下来。
            // 反查一次持久层，能捞到就照常续跑
            ids = collectWaitingCaptchaIds(userId);
        }
        if (ids.isEmpty()) {
            // 彻底没有可续跑的项：状态却还停在「待验证」，它已经是个卡死的展示态（不会再有人来推进）。
            // 复位成「待执行」，让用户回到正常流程，而不是对着一个永远不变的提示反复点。
            Logger.debug("VenusCaptcha", "no suspended tasks, reset stale waiting state");
            resetStaleWaitingState(userId);
            return;
        }
        Logger.debug("VenusCaptcha", "resume suspended tasks for " + userId + ": " + ids);
        // 必须带上验证归属的账号：Worker 若自行取「当前账号」，用户验证期间切过账号就会续跑到别人的桶
        DailyTaskScheduler.enqueueCaptchaResume(activity, userId, ids);
        runOnUi(() -> {
            viewModel.refreshTaskItems();
            refreshAggregate();
        });
    }

    /**
     * 轮内补跑：验证在本轮运行期间完成时，直接在当前线程把挂起项重跑一遍。
     *
     * <p>之所以不走 Worker：Worker 一启动就会撞 {@code DailyTaskWorker} 开头的「手动运行占用」判断，
     * 只能 15 秒一次重排到本轮结束——而本轮剩余的是其他任务（每款游戏 2~9 秒风控延时，六款分批
     * 就是一两分钟）。用户等的是这些延时，验证本身并不慢。同线程串行补跑既没有这个等待，也不会
     * 出现两路 {@code TaskExecutor} 并发写同一个状态桶（项目明确约束）。
     *
     * <p>补跑若再次挂起（凭证仍不被接受），{@code captchaTaskIds} 已被 {@code onCaptchaRequired} 重新
     * 收集，轮末收口会照常处理；异常路径跳过本方法时，轮末也会兜底排续跑。
     */
    private void rerunSolvedCaptchaInRound(TaskExecutor taskExecutor, TaskSettings settings, String runUser) {
        if (!captchaSolvedInRound) return;
        captchaSolvedInRound = false;
        String userId = (runUser == null || runUser.isEmpty()) ? viewModel.getCurrentUser() : runUser;
        // 配置与 Executor 都是 runUser 的：验证结果若属于别的账号，绝对不能拿过来重跑这一份配置
        if (captchaSolutionUserId != null && !captchaSolutionUserId.isEmpty()
                && !captchaSolutionUserId.equals(userId)) {
            Logger.debug("VenusCaptcha", "solution owner differs from round user, skip in-round rerun: "
                    + captchaSolutionUserId + " vs " + userId);
            return;
        }
        if (CaptchaStore.getSolution(activity, userId) == null) return;
        java.util.List<String> ids = collectWaitingCaptchaIds(userId);
        if (ids.isEmpty()) return;
        Logger.debug("VenusCaptcha", "in-round rerun after captcha: " + ids);
        taskExecutor.executeAll(settings, new java.util.LinkedHashSet<>(ids));
        // 补跑后仍停在「待验证」= 这份凭证没被服务端接受：清掉它。否则轮末 ① 会认定「已验证」，
        // 拿着同一份无效 solution 反复排续跑，用户看到的是「一直待验证、验证框却不出现」
        if (!collectWaitingCaptchaIds(userId).isEmpty()) {
            Logger.debug("VenusCaptcha", "in-round rerun still suspended, drop stale solution");
            // 只丢该账号的凭证：别的账号的验证结果与这次失败无关
            CaptchaStore.clearSolution(activity, userId);
        }
    }

    /**
     * 把指定账号仍停在「待验证」的任务判为失败，使「重试失败任务」按钮（只认 ERROR）可见。
     *
     * <p>用于两种终态：① 验证码明确失败（API2 不通过 / 用户关闭 / 网络失败）；② 超时未验证
     * （pending 已过期被清、solution 也不存在、没人会再推进）。只改仍是 {@code waiting_captcha} 的项，
     * 不覆盖已被续跑改成 completed / in_progress 的终态；也不清 pending——用户仍可点通知用同一 challenge
     * 重试，验证成功后续跑名单来自验证开始时的快照，不受这里的状态改写影响。
     */
    private void markCaptchaTasksFailed(String userId, java.util.Collection<String> ids) {
        if (userId == null || userId.isEmpty() || ids == null || ids.isEmpty()) return;
        TaskStatusManager statusManager = new TaskStatusManager(activity);
        boolean changed = false;
        for (String id : ids) {
            if (TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(statusManager.getStatus(userId, id))) {
                statusManager.markStatus(userId, id, TaskStatusManager.STATUS_ERROR);
                changed = true;
            }
        }
        if (!changed) return;
        tools.writeLog(activity, activity.getString(R.string.captcha_marked_failed) + " (" + userId + ")");
        TaskWidgetProvider.refreshAllWidgets(activity);
        runOnUi(() -> {
            viewModel.refreshTaskItems();
            refreshAggregate();
        });
    }

    /**
     * 把持久层残留的「待验证」复位为「待执行」。
     *
     * <p>只在「验证已完成、却找不到任何可续跑项」时调用：此时它不再表示「有人在等验证」，
     * 而是一个没有人会推进的陈旧状态。留着它，用户会反复点条目、反复弹出与目标无关的验证框。
     */
    private void resetStaleWaitingState(String userId) {
        if (userId == null || userId.isEmpty()) return;
        TaskStatusManager statusManager = new TaskStatusManager(activity);
        Map<String, String> statuses = statusManager.getAllStatuses(userId);
        boolean changed = false;
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            if (TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(entry.getValue())) {
                statusManager.markStatus(userId, entry.getKey(), TaskStatusManager.STATUS_PENDING);
                changed = true;
            }
        }
        if (changed) runOnUi(() -> {
            viewModel.refreshTaskItems();
            refreshAggregate();
        });
    }

    /**
     * 回到前台时的自愈：验证已完成（solution 有效）却仍有任务停在「待验证」，补排一次续跑。
     *
     * <p>触发条件刻意收得很紧——没有验证在途、没有轮次在跑、也没有待验证请求（pending 存在说明用户
     * 还没处理，不该替他做决定）——避免与正常链路抢同一份状态。
     */
    private void maybeResumeCaptchaOnResume() {
        if (captchaInProgress || captchaSuspending) return;
        if (ForegroundTaskService.isRunning() || DailyTaskWorker.isRunning()) return;
        String userId = viewModel.getCurrentUser();
        if (userId == null || userId.isEmpty()) return;
        // 只看当前账号的 pending：别的账号的待验证与本次自愈无关
        if (CaptchaStore.getPending(activity, userId) != null) return;
        java.util.List<String> ids = collectWaitingCaptchaIds(userId);
        if (ids.isEmpty()) return;
        if (CaptchaStore.getSolution(activity, userId) == null) {
            // pending 已过期被清、也没有验证结果，且没有在途验证/运行轮次：这份「待验证」没人会再推进。
            // 判为失败，让「重试失败任务」按钮出现，用户不必干等定时兜底或次日。标记后状态不再是
            // waiting_captcha，下次 show() 不会再进来，天然幂等。
            Logger.debug("VenusCaptcha", "onResume self-heal: captcha timed out, mark failed " + ids);
            markCaptchaTasksFailed(userId, ids);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoResumeAt < AUTO_RESUME_THROTTLE_MS) return;
        lastAutoResumeAt = now;
        Logger.debug("VenusCaptcha", "onResume self-heal: resume " + ids);
        DailyTaskScheduler.enqueueCaptchaResume(activity, userId, ids);
    }

    /**
     * 渲染 ViewModel 的不可变 UiState：任务行列表 + 运行形态。
     * <p>
     * 可见性不在此逐个 {@code setVisibility}：这里只把「原始输入」——运行形态、后台功能开关、任务状态——写进
     * Compose 状态，由 {@code HomeScreen} 按与 {@link HomeViewModel.TaskUiState} 相同的规则派生，规则只有一处可改。
     */
    private void render(HomeViewModel.TaskUiState state) {
        if (state == null || screenView == null) return;

        List<HomeTaskRow> rows = new ArrayList<>(state.items.size());
        int done = 0;
        int failed = 0;
        for (TaskItem item : state.items) {
            rows.add(new HomeTaskRow(item.getId(), item.getName(), toUiStatus(item.getStatus()), !item.isRunnable()));
            // DONE 计数与小组件 done_ 口径一致，只统计 COMPLETED + RETAINED：「已签过」由服务端返回后走
            // COMPLETED；等待人机验证期间保持 IN_PROGRESS 不计入；RETAINED（沿用上次结果）本质是当日已完成，
            // 计入以免运行期间计数回落。
            if (item.getStatus() == TaskItem.TaskStatus.COMPLETED
                    || item.getStatus() == TaskItem.TaskStatus.RETAINED) done++;
            if (item.getStatus() == TaskItem.TaskStatus.ERROR) failed++;
        }
        int total = rows.size();
        screenView.setTasks(rows);

        // 运行形态与功能开关：按钮/下拉可见性全部由 Compose 依这两个值派生
        screenView.setRunMode(toUiRunMode(state.runMode));
        screenView.setBgFeatureEnabled(state.bgFeatureEnabled);

        screenView.setBgButtonText(activity.getString(state.bgButtonShowsCancel()
                ? R.string.cancel_task
                : R.string.background_running));
        screenView.setRunHint(getResources().getQuantityString(R.plurals.run_remaining, total, total));
        screenView.setRetryCount(getResources().getQuantityString(R.plurals.retry_failed_count, failed, failed));
        String subtitle = activity.getString(R.string.home_subtitle_with_done,
                activity.getString(R.string.home_subtitle),
                getResources().getQuantityString(R.plurals.home_done_fmt, done, done, total));
        // 待验证队列：副标题追加「还有谁要验、还剩几个」
        if (!captchaQueue.isEmpty()) {
            subtitle = subtitle + " · " + activity.getString(R.string.captcha_queue_hint,
                    captchaQueue.peek(), captchaQueue.size());
        }
        screenView.setSubtitle(subtitle);
        screenView.setDoneLabel(activity.getString(R.string.task_done_fmt, done, total));
        // 账号状态 chip 的 done/total 与状态色来自持久层，不是 TaskUiState：Worker 续跑的逐条广播
        // 只会走到这里，若不在这里同步，验证码通过后任务已签完、chip 仍停在旧数字（要切页才刷新）。
        refreshAggregate();
    }

    /** TaskItem.TaskStatus → Compose 状态码（显式映射，不依赖枚举 ordinal）。 */
    private static int toUiStatus(TaskItem.TaskStatus status) {
        switch (status) {
            case IN_PROGRESS: return HomeScreenKt.TASK_IN_PROGRESS;
            case COMPLETED:   return HomeScreenKt.TASK_COMPLETED;
            case ERROR:       return HomeScreenKt.TASK_ERROR;
            case WARNING:     return HomeScreenKt.TASK_WARNING;
            case CANCELLED:   return HomeScreenKt.TASK_CANCELLED;
            case WAITING_CAPTCHA: return HomeScreenKt.TASK_WAITING_CAPTCHA;
            case RETAINED:    return HomeScreenKt.TASK_RETAINED;
            default:          return HomeScreenKt.TASK_PENDING;
        }
    }

    /** HomeViewModel.RunMode → Compose 运行形态码。 */
    private static int toUiRunMode(HomeViewModel.RunMode mode) {
        switch (mode) {
            case FOREGROUND: return HomeScreenKt.HOME_RUN_FOREGROUND;
            case BACKGROUND: return HomeScreenKt.HOME_RUN_BACKGROUND;
            default:         return HomeScreenKt.HOME_RUN_IDLE;
        }
    }

    /**
     * 点了任务列表里的「未配置」项：跳到设置页对应凭证抽屉。
     * <p>
     * 入参是稳定任务 id（不是显示名）：旧版按本地化名称逐个 getString 比对，切语言后必然对不上；
     * 云游戏 id 自带游戏标识，森空岛两款任务共用同一个 token、指向同一个抽屉。
     */
    private void onDisabledTaskClick(String taskId) {
        if (taskId == null) return;
        if (taskId.startsWith(TaskIds.CLOUD_GAME_PREFIX)) {
            activity.openSettingsPane(SettingsFragment.PANE_CLOUD_GAME);
            return;
        }
        if (TaskIds.SKLAND_ARKNIGHTS.equals(taskId) || TaskIds.SKLAND_ENDFIELD.equals(taskId)) {
            activity.openSettingsPane(SettingsFragment.PANE_SKLAND);
        }
    }

    /** 前台轮的状态回传：按运行账号持久化，只有展示账号与它一致时才刷本页列表。 */
    private void updateTaskStatus(String userId, String taskId, TaskItem.TaskStatus status) {
        viewModel.updateTaskStatus(userId, taskId, status);
        // chip 是跨账号聚合，不受「展示账号是否等于运行账号」约束：看别的账号时也要更新它的进度
        runOnUi(this::refreshAggregate);
    }

    /**
     * 页眉日期 chip：取「状态归属日（status_date）」而非渲染时刻。
     * <p>
     * 任务状态按日重置，归属日才是这组 ✓/✗ 的语义日期；小组件的日期 chip 同样读 status_date，
     * 两侧因此不会再出现「应用内显示今天、小组件显示昨天」的错位。
     */
    private void updateDateChip() {
        if (screenView == null || viewModel == null) return;
        String statusDate = new TaskStatusManager(activity).getStatusDate(viewModel.getCurrentUser());
        java.util.Date date;
        try {
            date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(statusDate);
        } catch (java.text.ParseException | NullPointerException e) {
            date = null;
        }
        if (date == null) date = new java.util.Date();
        screenView.setDateChip(new java.text.SimpleDateFormat("MM/dd E", java.util.Locale.getDefault())
                .format(date));
    }

    /** 同步当前选中用户的头像（空用户名 = 默认人像图标，由 Compose 侧统一处理）。 */
    private void loadUserAvatar() {
        if (screenView != null)
            screenView.setAvatarUsername(viewModel.getCurrentUser());
    }

    /**
     * 头像文件被替换或重置后强制重载。
     * <p>
     * 头像按用户名定址、换头像时原地覆盖文件，用户名与路径都没变——只调 {@link #loadUserAvatar()} 不会触发重组，
     * Glide 也会命中旧缓存。
     */
    public void refreshAvatar() {
        if (screenView != null) screenView.refreshAvatar();
    }

    /** 账号展示文案：用户名 + 该账号自己的服务器后缀（如「t [国服]」）。 */
    private String formatUserLabel(String username) {
        boolean oversea = userManager != null && userManager.isOversea(username);
        String serverName = activity.getString(oversea ? R.string.server_os : R.string.server_cn);
        // 存储 id 可能带后缀，展示前去掉
        String display = userManager != null ? userManager.displayName(username) : username;
        return display + " [" + serverName + "]";
    }

    /** 全部账号里是否同时存在国服与国际服（决定下拉项要不要逐个带服后缀）。 */
    private boolean hasMixedServers(List<String> usernames) {
        if (userManager == null || usernames == null) return false;
        boolean seenCn = false;
        boolean seenOs = false;
        for (String username : usernames) {
            if (userManager.isOversea(username)) seenOs = true;
            else seenCn = true;
            if (seenCn && seenOs) return true;
        }
        return false;
    }

    /**
     * 把账号列表灌给账号行与下拉：选中项、行内文案、头像一并更新。
     * <p>
     * 列表为全部账号（含跨服）；两种服共存时下拉项逐个带后缀。
     */
    private void applyUserList(List<String> usernames) {
        if (screenView == null) return;
        currentUsernames = usernames == null ? new ArrayList<>() : new ArrayList<>(usernames);
        String current = viewModel.getCurrentUser();
        int selectedIndex = currentUsernames.indexOf(current);
        boolean mixed = hasMixedServers(currentUsernames);
        List<String> dropdown = new ArrayList<>(currentUsernames.size());
        // 单服时下拉不带后缀，但账号本身可能是「名字@os」形态，两种分支都要过 displayName
        for (String username : currentUsernames)
            dropdown.add(mixed ? formatUserLabel(username) : userManager.displayName(username));
        // 头像键必须是存储 id：display 文本带后缀，拿它定址头像文件永远落空
        screenView.setUsers(dropdown, currentUsernames, selectedIndex);
        screenView.setUserLabel(current.isEmpty() ? "" : formatUserLabel(current));
        // 「全部账号」入口：账号数 ≥2 时可见
        screenView.setRunAllVisible(currentUsernames.size() > 1);
        screenView.setRunAllText(activity.getString(R.string.home_run_all, currentUsernames.size()));
        loadUserAvatar();
    }

    /**
     * 从持久化读回当前账号并重建账号上下文。用于「当前账号可能在页面外被改动」的时机：本页 show（可能刚从
     * 用户管理页切了账号）与用户管理页凭证 chip 的跨页切换。
     * <p>
     * 与 {@link #renderUserContext()} 的分工是数据流向：这里 SP → ViewModel，后者直接用 ViewModel 现值渲染。
     */
    public void refreshUserContext() {
        if (viewModel == null) return;
        if (userManager == null) userManager = new UserManager(activity);
        // 全部账号（含跨服）
        List<String> usernames = userManager.getAllUsernames();
        String current = userManager.getCurrentUser();
        // 当前账号可能已被删除（removeUser 会清掉当前用户标记）或被改名：回落到第一个可用账号。
        // 与 createView 的初始兜底同口径——不兜底会让账号行空着、任务按已不存在的用户分桶。
        if (!usernames.contains(current)) {
            current = usernames.isEmpty() ? "" : usernames.get(0);
            if (!current.isEmpty()) userManager.setCurrentUser(current);
        }
        viewModel.setCurrentUser(current);
        renderUserContext();
        // 账号切换即换了状态桶：日期 chip 的归属日随之变化
        updateDateChip();
    }

    /**
     * 按 ViewModel 的当前账号重建账号行文案 / 下拉选中项 / 头像，并重算按账号分桶的任务列表。
     * <p>
     * 三个入口共用：本页 show、首页下拉切换、用户管理页凭证 chip 切换。集中一处是因为漏掉任意一项都会留下
     * 「账号已切、界面还是旧账号」的残影——账号行文案直接写进 Compose 状态，不走 ViewModel 的 UiState。
     */
    private void renderUserContext() {
        if (screenView == null || userManager == null || viewModel == null) return;
        applyUserList(userManager.getAllUsernames());
        viewModel.refreshTaskItems();
        // 账号切换后状态条的「当前账号」描边也要跟上：selected 标记在 refreshAggregate 里按当前账号重算
        refreshAggregate();
    }

    /**
     * 无用户守卫：前台/后台运行入口共用。无用户时提示并跳转用户管理页，返回 true 表示已拦截。
     */
    private boolean interceptIfNoUser() {
        if (!viewModel.getCurrentUser().isEmpty()) return false;
        if (screenView != null)
            tools.showCustomSnackbar(screenView, activity, activity.getString(R.string.home_no_user_hint));
        activity.navigateToPage(1);
        return true;
    }

    /** 启动后台任务前校验：目标账号有效、通知权限、电池优化豁免，全部通过才拉起 Service。 */
    private void checkAndStartTask(java.util.List<String> users) {
        // 定时任务执行中不允许叠加后台运行：两路 TaskExecutor 的状态写入会互相覆盖
        if (DailyTaskWorker.isRunning()) {
            tools.showCustomSnackbar(screenView, activity, activity.getString(R.string.err_scheduled_task_running));
            return;
        }
        Context context = activity;
        // 单账号沿用原校验口径：按「将要运行的那个账号」读配置，与展示账号一致但语义更明确。
        // 批量名单已由 MultiUserRunner.filterRunnable 过滤（只保留至少勾了一项任务的账号），无需逐个校验
        if (users.size() == 1) {
            String userId = users.get(0);
            TaskSettings settings = TaskSettings.fromPreferences(context, userId);
            if (settings.hasAnyTaskDisabled()) {
                show_warning_dialog(context, activity.getString(R.string.err_set_task_first));
                return;
            }
            if (userId == null || userId.isEmpty()) {
                show_warning_dialog(context, activity.getString(R.string.msg_select_user_first));
                return;
            }
        }

        Notification notification = new Notification(context);
        if (notification.areNotificationsDisabled()) {
            VenusDialogs.confirm(
                    activity,
                    activity.getString(R.string.dialog_need_notification_permission),
                    activity.getString(R.string.msg_notification_required_for_background),
                    activity.getString(R.string.btn_go_to_settings),
                    () -> activity.startActivity(notification.getNotificationSettingsIntent()),
                    activity.getString(R.string.btn_cancel));
            return;
        }

        if (!BatteryHelper.isIgnoringBatteryOptimizations(context)) {
            VenusDialogs.confirm(
                    activity,
                    activity.getString(R.string.dialog_need_battery_optimization),
                    activity.getString(R.string.msg_battery_optimization_hint),
                    activity.getString(R.string.btn_go_to_settings),
                    () -> BatteryHelper.openVendorBatterySettings(context),
                    activity.getString(R.string.btn_ignore),
                    () -> {
                        // 「忽略」收口：尊重选择照常启动，但明确告知后果——
                        // 未豁免时熄屏后系统可能掐断后台网络（Doze / 厂商省电），任务会中断或失败
                        tools.showCustomSnackbar(screenView, activity,
                                activity.getString(R.string.msg_battery_ignored_warning));
                        startTaskService(users);
                    });
            return;
        }

        startTaskService(users);
    }

    /** 真正启动前台任务 Service，并切到 BACKGROUND 运行态、提示已启动。 */
    private void startTaskService(java.util.List<String> users) {
        Intent serviceIntent = new Intent(activity, ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        if (users.size() == 1) {
            serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, users.get(0));
        } else {
            // 批量：Service 内部按 MultiUserRunner 串行逐账号，进度通知自带「账号 i/n」前缀
            serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_IDS, users.toArray(new String[0]));
        }
        androidx.core.content.ContextCompat.startForegroundService(activity, serviceIntent);
        // 新一轮运行：仅复位瞬态（进行中/已取消），保留当日终态（与前台启动口径一致）
        viewModel.resetForNewRun();
        // 清空触达集合后切 BACKGROUND 并重建列表：未触达的已完成任务按「沿用上次结果」展示
        viewModel.beginRun();
        // 后台按钮文案/可见性由 UiState 派生，这里只切运行形态
        viewModel.setRunMode(HomeViewModel.RunMode.BACKGROUND);
        viewModel.refreshTaskItems();
        tools.showCustomSnackbar(screenView, activity,
                users.size() > 1
                        ? activity.getResources().getQuantityString(
                                R.plurals.snack_bg_task_started_all, users.size(), users.size())
                        : activity.getString(R.string.snack_bg_task_started));
    }

    /** 页面成为当前选中页 / Activity 恢复：注册状态广播、按真实运行状态对齐 UI、刷新用户下拉框与小组件，并通知宿主已就绪（触发后台验证）。 */
    @Override
    public void show() {
        // 外壳 Pager 的初始页回调可能先于 createView（viewModel 尚未就绪）到达：
        // 挂起待补发（见 createView 末尾），避免 NPE
        if (viewModel == null) {
            pendingShow = true;
            return;
        }
        Context context = activity;
        taskStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra(ForegroundTaskService.EXTRA_IS_RUNNING, false);
                // 后台服务广播的状态变更：经 ViewModel 统一处理（FOREGROUND 期间不会被误改为 IDLE）
                viewModel.applyBackgroundRunning(running);
            }
        };
        IntentFilter filter = new IntentFilter(ForegroundTaskService.ACTION_TASK_STATE_CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(
                context, taskStateReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        // show 对齐真实运行状态：前台线程是否存活 + 后台服务是否在跑（前台优先），并刷新后台按钮开关
        viewModel.syncRunMode(isTaskRunning(), ForegroundTaskService.isRunning());

        // 注册逐条状态广播接收器（仅本应用、RECEIVER_NOT_EXPORTED），让后台运行期间的状态实时回传列表
        taskStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String taskId = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_ID);
                String statusName = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_STATUS);
                if (taskId == null || statusName == null) return;
                try {
                    TaskItem.TaskStatus status = TaskItem.TaskStatus.valueOf(statusName);
                    // 后台服务是这条状态的写入方：已按运行用户持久化并刷新过小组件，本页只同步 UI 快照
                    // （广播携带运行用户，展示用户不一致时 ViewModel 内部会跳过，防串显）
                    String user = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_USER_ID);
                    viewModel.applyExternalTaskStatus(user, taskId, status);
                    // Worker/FGS 的进度来自广播；ViewModel 只在展示账号一致时发新快照，
                    // 而 chip 汇总所有账号——验完续跑时必须在这里独立刷新一次。
                    refreshAggregate();
                } catch (IllegalArgumentException ignored) {
                }
            }
        };
        IntentFilter statusFilter = new IntentFilter(ForegroundTaskService.ACTION_TASK_STATUS_UPDATED);
        androidx.core.content.ContextCompat.registerReceiver(
                context, taskStatusReceiver, statusFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);

        // 更新账号行与下拉中的用户列表（当前用户单一来源为 ViewModel）
        refreshUserContext();
        // 聚合条：多账号时显示账号数、完成进度、待验证数
        refreshAggregate();

        TaskWidgetProvider.refreshAllWidgets(context);

        // 清理可能残留的进度通知：正常路径都在轮末无条件撤销，这里兜住「进程被杀 / 系统回收 /
        // 旧版本遗留」——它是 ongoing，用户自己划不掉，不清理就只能干看着。
        if (!isTaskRunning() && !ForegroundTaskService.isRunning() && !DailyTaskWorker.isRunning()) {
            new Notification(activity).cancelProgressNotification();
        }

        if (!isTaskRunning()) {
            // 始终触发 TaskStatusManager.ensureToday()：跨天回到前台时即使任务集合与脏标记都无变化，
            // 也必须重建列表并清除昨日状态（否则日期变更后「已完成」会被当成当日状态沿用）。
            TaskSettings settings = TaskSettings.fromPreferences(context, viewModel.getCurrentUser());
            viewModel.refreshTaskItems(settings);
            // 中断续跑：手动运行被进程死亡 / 页面回收打断后，按开跑前记下的目标清单补跑未完成项
            RunRecovery.recover(context);
            // 自愈：验证做完了、续跑却没发生（轮末漏掉 / 进程被杀 / 续跑被丢弃）时补一次，
            // 否则用户只能看着「待验证」反复点条目，每点一次还多弹一个与目标无关的验证框
            maybeResumeCaptchaOnResume();
        }

        // 通知宿主本控制器已就绪（用于触发后台人机验证，替代 postDelayed 轮询）。
        // show() 一般在同一帧稍后执行并已通知过（宿主侧按 captchaPending 去重，重复调用无副作用）；
        // 这里在「视图已创建」时就补一次，兜住「外壳首帧未走页选回调」等时序变化——否则后台正在等的
        // 验证码会因为宿主永远收不到就绪信号而一直不显示。
        if (homeReadyCallback != null) homeReadyCallback.run();

        // 补发就绪前到达的 show（外壳初始页回调早于 createView 的竞态，见 show() 守卫）
        if (pendingShow) {
            pendingShow = false;
            show();
        }
    }

    /** 页面失去选中 / Activity 暂停：注销后台任务状态广播接收器，避免泄漏。 */
    @Override
    public void hide() {
        if (taskStateReceiver != null) {
            activity.unregisterReceiver(taskStateReceiver);
            taskStateReceiver = null;
        }
        if (taskStatusReceiver != null) {
            activity.unregisterReceiver(taskStatusReceiver);
            taskStatusReceiver = null;
        }
    }

    /** 销毁：解除底栏订阅与 UiState 观察，清空宿主引用（Activity 销毁时由 MainActivity 调用）。 */
    public void release() {
        BottomBarOverlay.removeListener(bottomBarListener);
        if (viewModel != null) viewModel.getUiState().removeObserver(uiObserver);
        controller.destroyButton();
        // Activity 销毁：清空验证队列
        captchaQueue.clear();
        // Activity 销毁：本轮前台验证作废。若这里不复位，验证 UI 被拆掉而没有回调收尾时，
        // 单飞标记会一直为真，后续任务的验证码就永远弹不出来。
        finishCaptchaAttempt();
        screenView = null;
    }

    /** 是否正在运行任务（前台线程存活）。 */
    public static boolean isTaskRunning() {
        // sRoundClosing：executeAll 已返回、只剩收尾，任务层面已经结束。语义是「有没有任务在执行」，
        // 不是「future 对象释放了没有」——轮末排的续跑轮会读这个值做并发防护，差这一步就会白等一次复查。
        return currentTaskFuture != null && !currentTaskFuture.isDone() && !sRoundClosing;
    }

    /**
     * 按账号收集当日 ERROR 任务，当前账号优先。
     *
     * <p>键与 {@code TaskStatusManager} 持久化键同源（稳定任务 id），「重试失败任务」据此按 id 过滤——
     * 不用显示名，避免切语言后整轮重试落空。
     */
    private Map<String, Set<String>> collectFailedByUser() {
        Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
        if (userManager == null) userManager = new UserManager(activity);
        String current = viewModel.getCurrentUser();
        List<String> users = new ArrayList<>(userManager.getAllUsernames());
        users.sort((a, b) -> Boolean.compare(!a.equals(current), !b.equals(current)));
        for (String user : users) {
            Set<String> failed = new HashSet<>();
            Map<String, String> statuses = new TaskStatusManager(activity).getAllStatuses(user);
            for (Map.Entry<String, String> e : statuses.entrySet())
                if (TaskStatusManager.STATUS_ERROR.equals(e.getValue())) failed.add(e.getKey());
            if (!failed.isEmpty()) result.put(user, failed);
        }
        return result;
    }

    /**
     * 应用内批量运行：账号串行，界面仍停留在当前账号。
     *
     * @param onlyIdsByUser 非空时按账号限定执行名单；null 表示每个账号全量
     */
    private void runBatchAccounts(List<String> users, Map<String, Set<String>> onlyIdsByUser) {
        if (users == null || users.isEmpty()) return;
        // 按账号记下本轮目标：批量串行时后续账号可能根本没轮到，单看状态无法区分
        // 「上一轮已完成」与「本轮还没开始」，必须靠这份快照才能在中断后补跑。
        java.util.Map<String, java.util.Set<String>> snapshotTargets = new java.util.LinkedHashMap<>();
        for (String user : users) {
            java.util.Set<String> ids = onlyIdsByUser == null ? null : onlyIdsByUser.get(user);
            if (ids == null || ids.isEmpty())
                ids = new java.util.LinkedHashSet<>(TaskSettings.fromPreferences(activity, user).getTaskIds());
            snapshotTargets.put(user, ids);
        }
        RunRecovery.start(activity, snapshotTargets);
        batchRoundActive = true;
        captchaSolvedInRound = false;
        captchaSolutionUserId = null;
        viewModel.beginRun();
        viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);
        viewModel.refreshTaskItems();
        currentTaskFuture = AppExecutors.get().submit(() -> {
            // 应用内运行：整轮抑制系统通知
            Notification.beginForegroundRun();
            try {
                MultiUserRunner.run(activity, users, MultiUserRunner.DEFAULT_GAP_MS,
                        (userId, index, total) -> runBatchAccount(userId, onlyIdsByUser),
                        this::isTaskCancelled);
            } finally {
                // 标记本轮收尾中
                sRoundClosing = true;
                try {
                    runningRoundUser = null;
                    runOnUi(() -> {
                        // 队列非空时保留验证控件
                        if (captchaQueue.isEmpty()) {
                            controller.destroyButton();
                            controller.createUtils();
                        }
                        viewModel.refreshBgFeatureEnabled();
                        viewModel.setRunMode(HomeViewModel.RunMode.IDLE);
                        viewModel.refreshTaskItems();
                        refreshAggregate();
                    });
                } finally {
                    sRoundClosing = false;
                    // 整批已收口（含取消），撤销中断快照
                    RunRecovery.finish(activity);
                    // 最后才放开批量标记：边界上到达的验证回调要么看到「仍批量」走续跑 Worker，
                    // 要么看到「整轮已结束」直接续跑，不会误置一个没人消费的轮内补跑标记
                    batchRoundActive = false;
                    Notification.endForegroundRun();
                }
            }
        });
    }

    /**
     * 前台单账号整轮运行（「运行签到」与「重试失败」共用）：提交后台整轮，跑完走统一轮末收口。
     *
     * @param runUser 本轮运行账号（启动时刻钉死，不随界面展示账号漂移）
     * @param onlyIds null = 全量执行；非 null = 只补跑这批任务 id（重试失败路径）
     */
    private void runForegroundRound(String runUser, java.util.Set<String> onlyIds) {
        currentTaskFuture = AppExecutors.get().submit(() -> {
            // 应用内运行：整轮抑制系统通知——用户就在界面上，进度看列表、错误走对话框。
            // 任务模块（BBSDaily/BBSGameDaily/…）的失败通知没有运行形态概念，判据统一收在 Notification。
            Notification.beginForegroundRun();
            // 挂起的验证需求：首个挂起即发起验证（不等轮末，见 onCaptchaRequired），后续挂起只并入名单
            Set<String> captchaTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.concurrent.atomic.AtomicBoolean captchaRequested =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<Map<String, String>> captchaHeaders =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                TaskSettings settings = TaskSettings.fromPreferences(activity, runUser);
                if (settings.hasAnyTaskDisabled()) {
                    // 引导性约束而非故障：用「提示」弹窗，不带「复制错误」入口
                    runOnUi(() -> show_warning_dialog(activity, activity.getString(R.string.err_set_task_first)));
                    return;
                }
                TaskExecutor taskExecutor = new TaskExecutor(
                        activity, runUser, notifier,
                        newForegroundCallback(runUser, captchaTaskIds, captchaRequested, captchaHeaders));
                // onlyIds 为 null 时全量；非 null 时 TaskExecutor 只提交 id 命中的任务（含单游戏粒度）
                if (onlyIds == null) taskExecutor.executeAll(settings);
                else taskExecutor.executeAll(settings, onlyIds);
                // 轮内补跑：验证在本轮进行中完成时立刻重跑挂起项，不必等整轮结束走 Worker
                rerunSolvedCaptchaInRound(taskExecutor, settings, runUser);
            } finally {
                // 任务已全部结束（executeAll 已返回），只剩本段收尾：让 isTaskRunning() 立刻反映这一点，
                // 否则下面排的续跑轮会被 Worker 的「手动运行占用」判断挡回，白等 5 分钟复查
                sRoundClosing = true;
                // 本轮结束：残留的「轮内补跑」标记作废（竞态下 executeAll 已返回、补跑没赶上），
                // 轮末收口会照常兜底排续跑，不会漏
                captchaSolvedInRound = false;
                try {
                    boolean captchaPending = !captchaTaskIds.isEmpty();
                    // 回到空闲态：按钮恢复由 UiState 派生；重建列表清除 RETAINED、恢复持久层原状。
                    // 有挂起项时不销毁验证控件——那是用户此刻唯一能操作的东西，轮末清掉等于把他手上的
                    // 验证作废（旧实现正是如此，表现为「点了条目、验到一半按钮消失」）。
                    runOnUi(() -> {
                        if (!captchaPending) {
                            controller.destroyButton();
                            controller.createUtils();
                        }
                        viewModel.refreshBgFeatureEnabled();
                        viewModel.setRunMode(HomeViewModel.RunMode.IDLE);
                        viewModel.refreshTaskItems();
                    });
                    // 用户主动取消时不弹验证：挂起项留在「待验证」，下次运行会自然重试
                    if (captchaPending && !isTaskCancelled())
                        handleRoundEndCaptcha(runUser, captchaTaskIds, captchaHeaders.get());
                    else if (captchaPending)
                        Logger.debug("VenusCaptcha", "skip showing captcha: task cancelled");
                } finally {
                    // 这几步必须在 finally 里：上面任何一步抛异常都不能留下「永久收尾中」或「永久静音」
                    sRoundClosing = false;
                    // 本轮已收口（含取消），撤销中断快照，下次启动不再补跑
                    RunRecovery.finish(activity);
                    // 收尾一定要清：否则后续所有运行（含后台）都会被永久静音
                    Notification.endForegroundRun();
                }
            }
        });
    }

    /**
     * 前台单账号回调（「运行签到」/「重试失败」共用）：状态写回、错误弹窗、
     * 首个验证挂起就地发起验证（不等轮末，理由见方法内注释）。
     */
    private TaskExecutor.Callback newForegroundCallback(
            String runUser,
            Set<String> captchaTaskIds,
            java.util.concurrent.atomic.AtomicBoolean captchaRequested,
            java.util.concurrent.atomic.AtomicReference<Map<String, String>> captchaHeaders) {
        return new TaskExecutor.Callback() {
            @Override
            public void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status) {
                updateTaskStatus(runUser, taskId, status);
            }

            @Override
            public void onAllTasksCompleted() {
                notifier.notifyListeners(activity.getString(R.string.task_completed));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> show_error_dialog(activity, message));
            }

            @Override
            public void onCaptchaRequired(CaptchaRequiredException error) {
                captchaTaskIds.add(error.getTaskId());
                captchaHeaders.compareAndSet(null, error.getHeaders());
                // 首个挂起就地发起验证，不再等本轮剩余任务跑完：它们还各有 2~9s 风控延时，
                // 用户会先看到列表变成「待验证」，验证框却要等几十秒才出来（旧行为），
                // 且这段空档里点任务行会另起一份 challenge，把真正的验证挤掉。
                if (captchaRequested.compareAndSet(false, true))
                    suspendAndShowCaptcha(runUser, captchaTaskIds, error.getHeaders());
            }

            @Override
            public boolean isCancelled() {
                return isTaskCancelled();
            }
        };
    }

    /** 批量运行中的单个账号：复位 → 执行 → 轮末把验证需求交给队列。 */
    private void runBatchAccount(String userId, Map<String, Set<String>> onlyIdsByUser) {
        runningRoundUser = userId;
        TaskSettings settings = TaskSettings.fromPreferences(activity, userId);
        if (settings.hasAnyTaskDisabled()) {
            Logger.debug("VenusBatch", "skip account without tasks: " + userId);
            return;
        }
        TaskStatusManager statusManager = new TaskStatusManager(activity);
        statusManager.resetTransientStatuses(userId, settings.getTaskIds());
        TaskWidgetProvider.refreshAllWidgets(activity);

        Set<String> captchaTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> captchaHeaders =
                new java.util.concurrent.atomic.AtomicReference<>();
        Set<String> onlyIds = onlyIdsByUser == null ? null : onlyIdsByUser.get(userId);

        TaskExecutor taskExecutor = new TaskExecutor(activity, userId, notifier,
                new TaskExecutor.Callback() {
                    @Override
                    public void onTaskStatusChanged(String taskId, TaskItem.TaskStatus status) {
                        // 本页无 Service 宿主：持久化 + 刷小组件 + 同步 UI 快照
                        statusManager.applyStatus(userId, taskId, status);
                        TaskWidgetProvider.refreshAllWidgets(activity);
                        viewModel.applyExternalTaskStatus(userId, taskId, status);
                        // 同上：批量跑在 A、界面看着 B 时，A 的 chip 进度也要跟着走
                        runOnUi(HomeFragment.this::refreshAggregate);
                    }

                    @Override
                    public void onAllTasksCompleted() {
                        notifier.notifyListeners(activity.getString(R.string.task_completed));
                    }

                    @Override
                    public void onError(String message) {
                        // 批量运行不弹模态框，改为提示条 + 日志
                        Logger.w("VenusBatch account " + userId + ": " + message);
                        runOnUi(() -> tools.showToast(screenView, activity, message));
                    }

                    @Override
                    public void onCaptchaRequired(CaptchaRequiredException error) {
                        captchaTaskIds.add(error.getTaskId());
                        captchaHeaders.compareAndSet(null, error.getHeaders());
                    }

                    @Override
                    public boolean isCancelled() {
                        return isTaskCancelled();
                    }
                });
        taskExecutor.executeAll(settings, onlyIds);

        // 该账号跑完：挂起项落库并交给验证队列
        if (!captchaTaskIds.isEmpty() && !isTaskCancelled()) {
            suspendAndShowCaptcha(userId, captchaTaskIds, captchaHeaders.get());
        }
    }

    /**
     * 多账号聚合：优先推「账号状态条」（逐账号进度 + 状态色，点击切列表），纯文本聚合条只是兜底。
     *
     * <p>可见性口径与 {@code MultiUserRunner.filterRunnable} 一致：只统计「至少勾了一项任务」的账号，
     * 四类全关的账号不出现——否则「3 个账号」里混进一个没配任务的，数字对不上用户认知。
     * 单账号（或全部没配任务）时两者都隐藏，不占位。
     */
    private void refreshAggregate() {
        if (screenView == null || viewModel == null) return;
        if (userManager == null) userManager = new UserManager(activity);
        List<String> users = userManager.getAllUsernames();
        if (users.size() < 2) {
            screenView.setAccountRows(java.util.Collections.emptyList(), false);
            screenView.setAggregateVisible(false);
            return;
        }
        Context localized = LocaleHelper.wrap(activity);
        TaskStatusManager statusManager = new TaskStatusManager(localized);
        String currentUser = viewModel.getCurrentUser();
        // 「运行中」账号：后台 Service 的批量链正在跑的，或前台批量（本类）正在跑的
        String runningUser = ForegroundTaskService.isRunning()
                ? ForegroundTaskService.getRunningUser() : runningRoundUser;
        int usableAccounts = 0, allDone = 0, allTotal = 0;
        java.util.List<HomeAccountStatus> rows = new ArrayList<>(users.size());
        for (String user : users) {
            TaskSettings settings = TaskSettings.fromPreferences(localized, user);
            if (settings.hasAnyTaskDisabled()) continue;
            usableAccounts++;
            int done = 0, total = 0, waiting = 0, failed = 0;
            for (String taskId : settings.getTaskIds()) {
                total++;
                String status = statusManager.getStatus(user, taskId);
                if (TaskStatusManager.STATUS_COMPLETED.equals(status)) done++;
                else if (TaskStatusManager.STATUS_WAITING_CAPTCHA.equals(status)) waiting++;
                else if (TaskStatusManager.STATUS_ERROR.equals(status)) failed++;
            }
            allDone += done;
            allTotal += total;
            // 状态优先级：运行中 > 待验证 > 失败 > 全部完成；进度数字恒显示 done/total
            int kind;
            if (user.equals(runningUser) && !runningUser.isEmpty()) {
                kind = AccountStatusKind.RUNNING;
            } else if (waiting > 0) {
                kind = AccountStatusKind.WAITING;
            } else if (failed > 0) {
                kind = AccountStatusKind.ERROR;
            } else if (total > 0 && done == total) {
                kind = AccountStatusKind.DONE;
            } else {
                kind = AccountStatusKind.NONE;
            }
            rows.add(new HomeAccountStatus(
                    user, userManager.displayName(user), done, total, kind, user.equals(currentUser)));
        }
        if (usableAccounts < 2 || allTotal == 0) {
            screenView.setAccountRows(java.util.Collections.emptyList(), false);
            screenView.setAggregateVisible(false);
            return;
        }
        screenView.setAccountRows(rows, true);
        // 状态条可见时不需要纯文本聚合（信息重叠）
        screenView.setAggregateVisible(false);
    }

    /** 在主线程执行；Activity 已销毁时静默忽略（后台线程回传 UI 的统一出口）。 */
    private void runOnUi(Runnable action) {
        activity.runOnUiThread(action);
    }

    private android.content.res.Resources getResources() {
        return activity.getResources();
    }

    /** 任务是否被取消（线程已中断或 future 已取消）。 */
    private boolean isTaskCancelled() {
        return Thread.currentThread().isInterrupted() ||
                (currentTaskFuture != null && currentTaskFuture.isCancelled());
    }

    /**
     * 清空今日日志文件（截断写），成功返回 true。路径每次现算，不能缓存到 createView 时刻：
     * 应用跨天常驻时缓存的会停留在昨天，清的和看的都不是写入方正在写的文件。
     */
    private boolean clearLogFile() {
        try (FileWriter ignored = new FileWriter(tools.getTodayLogFile(activity), false)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
