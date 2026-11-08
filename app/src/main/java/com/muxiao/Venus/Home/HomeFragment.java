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
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.PageController;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.ui.compose.BottomBarOverlay;
import com.muxiao.Venus.common.ui.compose.VenusDialogs;
import com.muxiao.Venus.common.ui.compose.HomeScreenKt;
import com.muxiao.Venus.common.ui.compose.HomeScreenView;
import com.muxiao.Venus.common.ui.compose.HomeTaskRow;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * 主页控制器（原 Fragment 收敛为控制器，外壳迁移到 Compose 后不再持有
 * Fragment 生命周期）：用户选择下拉框、任务列表展示、签到执行（前台/后台）、
 * 任务状态实时更新、日志查看、配置更新入口。
 *
 * <p>生命周期契约见 {@link PageController}：show/hide 由 MainActivity 按页面
 * 选中态与 Activity 恢复态折算调用；createView 在外壳首次组合时调用一次；
 * release 在 Activity 销毁时调用。</p>
 */
public class HomeFragment implements PageController {

    private final MainActivity activity;

    private UserManager userManager;
    /**
     * Compose 宿主：本页唯一的 UI 入口。
     *
     * <p>原实现持有 20 多个 View 字段（下拉框、四个按钮、各种 TextView、
     * RecyclerView + TaskAdapter），全部收敛到此处；本类只做「取数据 → 写状态 → 处理回调」。</p>
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
    private File logFile; // 日志文件路径
    private BroadcastReceiver taskStateReceiver;
    private BroadcastReceiver taskStatusReceiver;
    /** UiState 观察者（show 期间注册 / release 时移除，替代原 getViewLifecycleOwner 观察）。 */
    private final Observer<HomeViewModel.TaskUiState> uiObserver = this::render;

    /** 主页就绪回调：MainActivity 据此触发后台人机验证（替代原 FragmentResult 广播）。 */
    private Runnable homeReadyCallback;

    public HomeFragment(MainActivity activity) {
        this.activity = activity;
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
     * 构建主页 UI（外壳首次组合时调用一次）：绑定视图、初始化下拉框/任务列表、
     * 注册按钮与日志查看；并以 ViewModel 单向数据流渲染。
     */
    public View createView(Context context) {
        if (screenView != null) return screenView; // 幂等：外壳重组不会重复装配
        HomeScreenView root = new HomeScreenView(context);
        screenView = root;

        // ViewModel 作为 UI 状态单一可信数据源（Activity 作用域，与原 Fragment 作用域等效）
        viewModel = new ViewModelProvider(activity).get(HomeViewModel.class);

        // 页眉日期 chip：MM/dd 周几
        root.setDateChip(new java.text.SimpleDateFormat("MM/dd E", java.util.Locale.getDefault())
                .format(new java.util.Date()));

        // 内容不再容器级避让：列表底部留出底栏覆盖高度，末项可完整滚出玻璃区
        // （原 BottomBarOverlay.attachClearance 的职责，改为把高度喂成组合状态）
        BottomBarOverlay.addListener(bottomBarListener);
        root.setBottomClearance(BottomBarOverlay.get());

        // 初始化
        userManager = new UserManager(context);
        controller.createUtils();
        tools.cleanOldLogs(context);
        logFile = tools.getTodayLogFile(context);

        // 输出文本监听器
        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(message -> tools.writeLog(context, message));

        // 初始化下拉框（当前用户由 ViewModel 持有，避免控制器与 VM 双份真相）
        boolean isOversea = MiHoYoBBSConstants.is_oversea(context);
        List<String> usernames = userManager.getUsernamesByServerType(isOversea);
        String initialUser = userManager.getCurrentUser();
        if (!initialUser.isEmpty() && usernames.contains(initialUser)) {
            viewModel.setCurrentUser(initialUser);
        } else if (!usernames.isEmpty()) {
            // 如果当前用户不存在或为空，设置为第一个用户
            viewModel.setCurrentUser(usernames.get(0));
        } else {
            viewModel.setCurrentUser("");
        }
        // 账号行/下拉数据：必须先确定当前用户再重建列表，
        // 否则读到空桶导致任务全部显示 pending（原实现刷新先于用户初始化，分桶后不成立）
        applyUserList(usernames, isOversea);
        viewModel.refreshTaskItems();
        root.setOnUserSelectedListener(index -> {
            if (index < 0 || index >= currentUsernames.size()) return;
            String picked = currentUsernames.get(index);
            viewModel.setCurrentUser(picked);
            userManager.setCurrentUser(picked);
            // 切换账号：账号行文案、下拉选中项、头像与按账号分桶的任务列表一起重建。
            // 只刷新头像与任务列表会漏掉账号行文案——那一个是独立写进 Compose 状态的。
            renderUserContext();
        });
        // 无用户空态引导：账号行点击跳转用户管理页（而非弹出一个空下拉）
        root.setOnJumpToUsersListener(() -> activity.navigateToPage(1));

        // 单向数据流：观察 ViewModel 的不可变 UiState，一次性渲染任务列表 + 全部按钮可见性
        viewModel.getUiState().observeForever(uiObserver);

        // 启动任务按钮
        root.setOnStartDailyListener(() -> {
            // 无用户时引导添加而非运行后报错
            if (interceptIfNoUser()) return;
            // 定时任务执行中不允许叠加手动运行：两路 TaskExecutor 的状态写入会互相覆盖
            if (DailyTaskWorker.isRunning()) {
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_scheduled_task_running));
                return;
            }
            // 清空今日日志失败只提示、不阻断本轮任务（与原有行为一致）；属轻微故障，用 Snackbar 而非错误弹窗
            if (!clearLogFile())
                tools.showCustomSnackbar(screenView, context, activity.getString(R.string.err_clear_log_failed));
            tools.writeLogSeparator(context);
            // 新一轮运行：仅复位瞬态（进行中/已取消），保留当日终态（已完成/已签过/失败）——
            // 重跑中各任务被真正执行到时才逐个切回进行中，中途终止不丢之前已完成的记录
            viewModel.resetForNewRun();
            // 记录当前用户供 Widget 显示
            viewModel.persistCurrentUserForWidget();

            // 新一轮运行：清空触达集合后切 FOREGROUND 并重建列表，
            // 未触达的已完成任务派生为「沿用上次结果」展示态（RETAINED）
            viewModel.beginRun();
            viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);
            viewModel.refreshTaskItems();

            currentTaskFuture = AppExecutors.get().submit(() -> {
                try {
                    TaskSettings settings = TaskSettings.fromPreferences(context);
                    if (settings.hasAnyTaskDisabled()) {
                        // 引导性约束而非故障：用「提示」弹窗，不带「复制错误」入口
                        runOnUi(() -> show_warning_dialog(context, activity.getString(R.string.err_set_task_first)));
                        return;
                    }
                    TaskExecutor taskExecutor = new TaskExecutor(
                            activity, viewModel.getCurrentUser(), notifier, controller,
                            new TaskExecutor.Callback() {
                                @Override
                                public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                    updateTaskStatus(taskName, status);
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
                                public boolean isCancelled() {
                                    return isTaskCancelled();
                                }
                            });
                    taskExecutor.executeAll(settings);
                }                 finally {
                    // 回到空闲态：下拉框/启动按钮恢复、取消按钮隐藏、后台按钮按设置恢复；
                    // 重建列表清除 RETAINED 展示态，恢复持久层原状
                    runOnUi(() -> {
                        controller.destroyButton();
                        controller.createUtils();
                        viewModel.refreshBgFeatureEnabled();
                        viewModel.setRunMode(HomeViewModel.RunMode.IDLE);
                        viewModel.refreshTaskItems();
                    });
                }
            });
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
            Set<String> failedNames = collectFailedTaskNames();
            // 按钮可见性由 ERROR 状态派生，正常不会为空；切用户/改配置等竞态下兜底
            if (failedNames.isEmpty()) return;
            tools.writeLogSeparator(context);

            // 刻意不调用 resetForNewRun：保留已完成/失败终态，重跑项执行到时逐个覆盖为新结果。
            // 清空触达集合后重建列表：未触达的已完成任务在重试期间按「沿用上次结果」展示
            viewModel.beginRun();
            viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);
            viewModel.refreshTaskItems();

            currentTaskFuture = AppExecutors.get().submit(() -> {
                try {
                    TaskSettings settings = TaskSettings.fromPreferences(context);
                    if (settings.hasAnyTaskDisabled()) {
                        runOnUi(() -> show_warning_dialog(context, activity.getString(R.string.err_set_task_first)));
                        return;
                    }
                    TaskExecutor taskExecutor = new TaskExecutor(
                            activity, viewModel.getCurrentUser(), notifier, controller,
                            new TaskExecutor.Callback() {
                                @Override
                                public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                    updateTaskStatus(taskName, status);
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
                                public boolean isCancelled() {
                                    return isTaskCancelled();
                                }
                            });
                    // 部分执行：TaskExecutor 只提交名称命中的任务（含单游戏粒度）
                    taskExecutor.executeAll(settings, failedNames);
                }                 finally {
                    // 回到空闲态：可见性由 UiState 派生，重试按钮随 ERROR 清零自动隐藏；
                    // 重建列表清除 RETAINED 展示态，恢复持久层原状
                    runOnUi(() -> {
                        controller.destroyButton();
                        controller.createUtils();
                        viewModel.refreshBgFeatureEnabled();
                        viewModel.setRunMode(HomeViewModel.RunMode.IDLE);
                        viewModel.refreshTaskItems();
                    });
                }
            });
        });

        // 取消任务按钮
        root.setOnCancelDailyListener(() -> VenusDialogs.confirm(
                activity,
                activity.getString(R.string.dialog_confirm_cancel),
                activity.getString(R.string.msg_confirm_cancel_current_task),
                activity.getString(R.string.btn_ok),
                () -> {
                    if (currentTaskFuture != null) {
                        currentTaskFuture.cancel(true); // 尝试取消任务
                        controller.destroyButton(); // 销毁验证码按钮
                        controller.createUtils(); // 创建验证码Utils以备下次使用
                    }
                    notifier.notifyListeners(activity.getString(R.string.task_cancelled));
                },
                activity.getString(R.string.btn_continue_task)));

        // 点「未配置」任务项：直达设置页对应凭证抽屉（划线只提示不解决，这里补上后半程）
        root.setOnDisabledTaskClickListener(this::onDisabledTaskClick);

        // 后台运行/取消按钮
        root.setOnBackgroundClickListener(() -> {
            if (ForegroundTaskService.isRunning()) {
                VenusDialogs.confirm(
                        activity,
                        activity.getString(R.string.dialog_confirm_cancel),
                        activity.getString(R.string.msg_confirm_cancel_background_task),
                        activity.getString(R.string.btn_ok),
                        () -> {
                            Intent stopIntent = new Intent(context, ForegroundTaskService.class);
                            stopIntent.setAction(ForegroundTaskService.ACTION_STOP_TASK);
                            context.startService(stopIntent);
                            // 不在此立即置 IDLE：等待服务 cancelTask() 广播 is_running=false，
                            // 由 applyBackgroundRunning(false) 统一切回 IDLE，避免按钮状态提前翻转
                        },
                        activity.getString(R.string.btn_continue_task));
                return;
            }

            checkAndStartTask(viewModel.getCurrentUser());
        });

        // 根据设置显示/隐藏后台运行按钮（经 UiState 派生）
        viewModel.refreshBgFeatureEnabled();

        // 检查是否需要处理后台人机验证
        android.content.Intent launchIntent = activity.getIntent();
        if (launchIntent != null && Constants.ACTION_HANDLE_CAPTCHA.equals(launchIntent.getAction())) {
            launchIntent.setAction(null);
            screenView.postDelayed(this::performBackgroundCaptchaVerification, 300);
        }

        // 查看日志按钮
        root.setOnViewLogListener(() -> {
            try {
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
                if (screenView == null) return;
                LinearLayout container = screenView.getGeetestContainer();
                try {
                    // 动态创建GT3GeetestButton
                    // 如果按钮已存在，先销毁
                    if (geetestButton != null) {
                        container.removeView(geetestButton);
                        geetestButton = null;
                    }
                    // 创建新的GT3GeetestButton实例
                    geetestButton = new GT3GeetestButton(activity);
                    // 内容居中 + 显式横向：SDK 官方 XML 用法为
                    //   android:gravity="center" android:orientation="horizontal"
                    // 二者都不在 SDK 内部设置，LinearLayout 默认 gravity 为 TOP|START。
                    // 按钮内部内容（盾牌图标 + 「请点击验证」14sp 文案）自然高度仅约 20~30dp，
                    // 定高 44dp 且顶对齐时，剩余部分会表现为按钮底部一条空白（白底渐变上极显眼）。
                    geetestButton.setGravity(Gravity.CENTER);
                    geetestButton.setOrientation(LinearLayout.HORIZONTAL);
                    // 布局参数：宽度定尺 290dp、高度 44dp。**刻意不设垂直外边距**——
                    // 按钮上下的留白统一由容器的 marginTop 与紧随其后的分隔线 marginTop
                    // 各出 space_4 提供（见 HomeScreen 的留白契约）。
                    // 若在此再叠加垂直 margin，会变成「上 20dp / 下 28dp」的不对称观感。
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            (int) (290 * getResources().getDisplayMetrics().density),
                            (int) (44 * getResources().getDisplayMetrics().density)
                    );
                    params.gravity = Gravity.CENTER_HORIZONTAL;
                    geetestButton.setLayoutParams(params);
                    // 添加到容器中
                    container.addView(geetestButton);
                    screenView.setGeetestVisible(true);
                    gt3GeetestUtils.init(gt3ConfigBean);
                    geetestButton.setGeetestUtils(gt3GeetestUtils);
                } catch (Exception e) {
                    Logger.e("Error creating GT3GeetestButton", e);
                }
            });
        }

        @Override
        public void destroyButton() {
            runOnUi(() -> {
                // 销毁按钮
                if (geetestButton != null && screenView != null) {
                    screenView.getGeetestContainer().removeView(geetestButton);
                    geetestButton = null;
                    screenView.setGeetestVisible(false);
                }
            });
            // 销毁工具
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
     * 由 MainActivity 调用，处理后台人机验证通知跳转。
     * 在前台使用本地 GeetestController 展示验证 UI，完成后通过广播回传结果给后台 Service。
     */
    public void performBackgroundCaptchaVerification() {
        Logger.debug("VenusCaptcha", "performBackgroundCaptchaVerification called, controller=" + controller);
        // Geetest.geetest() 包含同步网络请求，必须在后台线程执行
        AppExecutors.get().execute(() -> {
            try {
                // 优先使用后台任务保存的 headers（含 Cookie），确保 API2 二次验证能正确绑定会话
                Map<String, String> headers = BackgroundGeetestController.consumePendingHeaders();
                if (headers == null) {
                    Logger.debug("VenusCaptcha", "No pending headers, using fresh BBS headers");
                    headers = new HeaderManager(activity).get_bbs_headers();
                } else {
                    Logger.debug("VenusCaptcha", "Using pending headers from background task");
                }

                // 检查是否有后台任务保存的 challenge（避免重复 API1 导致 challenge 不匹配）
                String[] savedChallenge = BackgroundGeetestController.consumePendingChallenge();
                Notification notification = new Notification(activity);
                Geetest.GeetestVerificationCallback callback = new Geetest.GeetestVerificationCallback() {
                    @Override
                    public void onVerificationSuccess(Map<String, String> geetestCode) {
                        Logger.debug("VenusCaptcha", "Verification SUCCESS, sending broadcast...");
                        controller.destroyButton();
                        String resultJson = new com.google.gson.Gson().toJson(geetestCode);
                        Logger.debug("VenusCaptcha", "Broadcast resultJson=" + resultJson);
                        BackgroundGeetestController.notifyVerificationSuccess(geetestCode);
                        Logger.debug("VenusCaptcha", "Broadcast sent OK");
                        notification.dismissErrorNotification();
                    }

                    @Override
                    public void onVerificationFailed(String error) {
                        Logger.debug("VenusCaptcha", "Verification FAILED: " + error);
                        controller.destroyButton();
                        BackgroundGeetestController.notifyVerificationFailure();
                        notification.sendErrorNotification(activity.getString(R.string.notif_captcha_failed), error, true);
                    }
                };

                // 使用后台任务的 challenge 进行验证（同一 challenge，避免 1034 循环）
                if (savedChallenge != null) {
                    Logger.debug("VenusCaptcha", "Using saved challenge: gt=" + savedChallenge[0]);
                    Geetest.geetestWithChallenge(activity, savedChallenge[0], savedChallenge[1], headers, callback, controller);
                } else {
                    Logger.debug("VenusCaptcha", "No saved challenge, calling API1");
                    Geetest.geetest(activity, headers, callback, controller);
                }
            } catch (Exception e) {
                Logger.e("Exception in performBackgroundCaptchaVerification", e);
                try {
                    BackgroundGeetestController.notifyVerificationFailure();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 渲染 ViewModel 的不可变 UiState：任务行列表 + 运行形态。
     *
     * <p>可见性不再在此逐个 {@code setVisibility}：这里只把「原始输入」——运行形态、
     * 后台功能开关、任务状态——写进 Compose 状态，由 {@code HomeScreen} 按与
     * {@link HomeViewModel.TaskUiState} 相同的规则派生可见性。
     * 规则因此只有一处可读、一处可改。</p>
     */
    private void render(HomeViewModel.TaskUiState state) {
        if (state == null || screenView == null) return;

        List<HomeTaskRow> rows = new ArrayList<>(state.items.size());
        int done = 0;
        int failed = 0;
        for (TaskItem item : state.items) {
            rows.add(new HomeTaskRow(item.getName(), toUiStatus(item.getStatus()), !item.isRunnable()));
            // DONE 计数与小组件 done_ 口径一致，仅统计 COMPLETED + RETAINED。
            // 「已签过」由服务端返回后任务正常走 COMPLETED；等待人机验证期间任务保持
            // IN_PROGRESS，不计入。RETAINED（沿用上次结果）本质是当日已完成，计入 done，
            // 避免运行期间计数回落。
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
        screenView.setSubtitle(activity.getString(R.string.home_subtitle_with_done,
                activity.getString(R.string.home_subtitle),
                getResources().getQuantityString(R.plurals.home_done_fmt, done, done, total)));
        screenView.setDoneLabel(activity.getString(R.string.task_done_fmt, done, total));
    }

    /** TaskItem.TaskStatus → Compose 状态码（显式映射，不依赖枚举 ordinal）。 */
    private static int toUiStatus(TaskItem.TaskStatus status) {
        switch (status) {
            case IN_PROGRESS: return HomeScreenKt.TASK_IN_PROGRESS;
            case COMPLETED:   return HomeScreenKt.TASK_COMPLETED;
            case ERROR:       return HomeScreenKt.TASK_ERROR;
            case WARNING:     return HomeScreenKt.TASK_WARNING;
            case CANCELLED:   return HomeScreenKt.TASK_CANCELLED;
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
     * 更新特定任务的状态（持久化 + 发布快照 + 刷新小组件均下沉到 ViewModel）。
     *
     * @param taskName 任务名称
     * @param status   任务状态
     */
    /**
     * 点了任务列表里的「未配置」项：跳到设置页对应凭证抽屉并展开。
     *
     * <p>任务名是本地化字符串，只能按「同一 getString 口径」反查：云游戏逐个游戏名比对，
     * 森空岛两款任务共用同一个 token、指向同一个抽屉。
     */
    private void onDisabledTaskClick(String taskName) {
        TaskSettings settings = TaskSettings.fromPreferences(activity);
        for (String game : settings.getCloudGames()) {
            if (taskName.equals(CloudGameDaily.taskName(activity, game))) {
                activity.openSettingsPane(SettingsFragment.PANE_CLOUD_GAME);
                return;
            }
        }
        if (taskName.equals(activity.getString(R.string.task_name_skland_arknights))
                || taskName.equals(activity.getString(R.string.task_name_skland_endfield))) {
            activity.openSettingsPane(SettingsFragment.PANE_SKLAND);
        }
    }

    private void updateTaskStatus(String taskName, TaskItem.TaskStatus status) {
        viewModel.updateTaskStatus(taskName, status);
    }

    /** 同步当前选中用户的头像（空用户名 = 默认人像图标，由 Compose 侧统一处理）。 */
    private void loadUserAvatar() {
        if (screenView != null)
            screenView.setAvatarUsername(viewModel.getCurrentUser());
    }

    /**
     * 头像文件被替换或重置后强制重载。
     *
     * <p>头像按用户名定址、换头像时是原地覆盖文件，用户名与路径都没变——
     * 只调 {@link #loadUserAvatar()} 不会触发重组，Glide 也会命中旧缓存。
     */
    public void refreshAvatar() {
        if (screenView != null) screenView.refreshAvatar();
    }

    /**
     * 账号上下文行显示文案：用户名 + 服务器后缀（如「t [国服]」，与设计稿一致）。
     */
    private String formatUserLabel(String username, boolean isOversea) {
        String serverName = activity.getString(isOversea ? R.string.server_os : R.string.server_cn);
        return username + " [" + serverName + "]";
    }

    /**
     * 把当前服务器的用户列表灌给账号行与下拉：选中项、行内文案、头像一并更新。
     *
     * <p>替代原 `updateUserGuideState` + `user_dropdown.setAdapter(...)` 两段命令式代码：
     * 空态（无用户）时的「尚未添加用户 · 点击前往添加」由 Compose 侧按列表为空派生，
     * 点击行为由宿主注入的 {@code onJumpToUsers} 承担。</p>
     */
    private void applyUserList(List<String> usernames, boolean isOversea) {
        if (screenView == null) return;
        currentUsernames = usernames == null ? new ArrayList<>() : new ArrayList<>(usernames);
        String current = viewModel.getCurrentUser();
        screenView.setUsers(currentUsernames, currentUsernames.indexOf(current));
        screenView.setUserLabel(current.isEmpty() ? "" : formatUserLabel(current, isOversea));
        loadUserAvatar();
    }

    /**
     * 从持久化读回当前账号并重建账号上下文。
     *
     * <p>用于「当前账号可能在页面外被改动」的时机：本页 show（用户可能刚从用户管理页切了账号）
     * 与用户管理页凭证 chip 的跨页切换。与 {@link #renderUserContext()} 的分工是数据流向——
     * 这里 SP → ViewModel，后者直接用 ViewModel 现值渲染。
     */
    public void refreshUserContext() {
        if (viewModel == null) return;
        if (userManager == null) userManager = new UserManager(activity);
        boolean isOversea = MiHoYoBBSConstants.is_oversea(activity);
        List<String> usernames = userManager.getUsernamesByServerType(isOversea);
        String current = userManager.getCurrentUser();
        // 当前账号可能已被删除（removeUser 会清掉当前用户标记）或被改名：回落到第一个可用账号。
        // 与 createView 的初始兜底同口径——不兜底会让账号行空着、任务按已不存在的用户分桶。
        if (!usernames.contains(current)) {
            current = usernames.isEmpty() ? "" : usernames.get(0);
            if (!current.isEmpty()) userManager.setCurrentUser(current);
        }
        viewModel.setCurrentUser(current);
        renderUserContext();
    }

    /**
     * 按 ViewModel 的当前账号重建账号行文案 / 下拉选中项 / 头像，并重算按账号分桶的任务列表。
     *
     * <p>三个入口共用：本页 show、首页下拉切换、用户管理页的凭证 chip 切换。
     * 集中在一处的原因——漏掉任意一项都会留下「账号已切、界面还是旧账号」的残影：
     * 账号行文案是直接写进 Compose 状态的，不走 ViewModel 的 UiState。
     */
    private void renderUserContext() {
        if (screenView == null || userManager == null || viewModel == null) return;
        boolean isOversea = MiHoYoBBSConstants.is_oversea(activity);
        applyUserList(userManager.getUsernamesByServerType(isOversea), isOversea);
        viewModel.refreshTaskItems();
    }

    /**
     * 无用户守卫：前台/后台运行签到入口共用。无用户时提示并跳转用户管理页，
     * 返回 true 表示已拦截（调用方不再继续执行）。
     */
    private boolean interceptIfNoUser() {
        if (!viewModel.getCurrentUser().isEmpty()) return false;
        if (screenView != null)
            tools.showCustomSnackbar(screenView, activity, activity.getString(R.string.home_no_user_hint));
        activity.navigateToPage(1);
        return true;
    }

    /** 启动后台任务前校验：任务已配置、已选用户、通知权限、电池优化豁免，全部通过才拉起 Service。 */
    private void checkAndStartTask(String userId) {
        // 定时任务执行中不允许叠加后台运行：两路 TaskExecutor 的状态写入会互相覆盖
        if (DailyTaskWorker.isRunning()) {
            tools.showCustomSnackbar(screenView, activity, activity.getString(R.string.err_scheduled_task_running));
            return;
        }
        Context context = activity;
        TaskSettings settings = TaskSettings.fromPreferences(context);
        if (settings.hasAnyTaskDisabled()) {
            show_warning_dialog(context, activity.getString(R.string.err_set_task_first));
            return;
        }
        if (userId == null || userId.isEmpty()) {
            show_warning_dialog(context, activity.getString(R.string.msg_select_user_first));
            return;
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
                    () -> startTaskService(userId));
            return;
        }

        startTaskService(userId);
    }

    /** 真正启动前台任务 Service，并切到 BACKGROUND 运行态、提示已启动。 */
    private void startTaskService(String userId) {
        Intent serviceIntent = new Intent(activity, ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, userId);
        androidx.core.content.ContextCompat.startForegroundService(activity, serviceIntent);
        // 新一轮运行：仅复位瞬态（进行中/已取消），保留当日终态（与前台启动口径一致）
        viewModel.resetForNewRun();
        // 清空触达集合后切 BACKGROUND 并重建列表：未触达的已完成任务按「沿用上次结果」展示
        viewModel.beginRun();
        // 单一状态切换取代原 updateBgButtonState(true)：后台按钮文案/可见性由 UiState 派生
        viewModel.setRunMode(HomeViewModel.RunMode.BACKGROUND);
        viewModel.refreshTaskItems();
        tools.showCustomSnackbar(screenView, activity, activity.getString(R.string.snack_bg_task_started));
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
        // 注册后台任务状态广播接收器
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

        // 注册后台任务逐条状态广播接收器（LocalBroadcast 等价：仅本应用、RECEIVER_NOT_EXPORTED），
        // 让后台运行期间的任务状态能实时回传到 App 内列表
        taskStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String name = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_NAME);
                String statusName = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_STATUS);
                if (name == null || statusName == null) return;
                try {
                    TaskItem.TaskStatus status = TaskItem.TaskStatus.valueOf(statusName);
                    // 后台服务是这条状态的写入方：已按运行用户持久化并刷新过小组件，本页只同步 UI 快照
                    // （广播携带运行用户，展示用户不一致时 ViewModel 内部会跳过，防串显）
                    String user = intent.getStringExtra(ForegroundTaskService.EXTRA_TASK_USER_ID);
                    viewModel.applyExternalTaskStatus(user, name, status);
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

        // 刷新小组件
        TaskWidgetProvider.refreshAllWidgets(context);

        if (!isTaskRunning()) {
            // 始终触发 TaskStatusManager.ensureToday()：跨天回到前台时即使任务集合与脏标记都无变化，
            // 也必须重建列表并清除昨日状态（否则日期变更后「已完成」会被当成当日状态沿用）。
            TaskSettings settings = TaskSettings.fromPreferences(context);
            viewModel.refreshTaskItems(settings);
        }

        // 通知宿主本控制器已就绪（用于触发后台人机验证，替代 postDelayed 轮询）
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
        screenView = null;
    }

    /**
     * 判断是否正在运行任务
     */
    public static boolean isTaskRunning() {
        return currentTaskFuture != null && !currentTaskFuture.isDone();
    }

    /**
     * 收集当前用户当日 ERROR 状态的任务名（TaskStatusManager 持久化键同源），
     * 供「重试失败任务」的部分执行入口按名过滤。
     */
    private Set<String> collectFailedTaskNames() {
        String user = viewModel.getCurrentUser();
        if (user == null || user.isEmpty()) return java.util.Collections.emptySet();
        Map<String, String> statuses = new TaskStatusManager(activity).getAllStatuses(user);
        Set<String> failed = new HashSet<>();
        for (Map.Entry<String, String> e : statuses.entrySet())
            if (TaskStatusManager.STATUS_ERROR.equals(e.getValue())) failed.add(e.getKey());
        return failed;
    }

    /** 在主线程执行；Activity 已销毁时静默忽略（后台线程回传 UI 的统一出口）。 */
    private void runOnUi(Runnable action) {
        activity.runOnUiThread(action);
    }

    private android.content.res.Resources getResources() {
        return activity.getResources();
    }

    /**
     * 检查任务是否被取消
     *
     * @return true 如果任务被取消
     */
    private boolean isTaskCancelled() {
        return Thread.currentThread().isInterrupted() ||
                (currentTaskFuture != null && currentTaskFuture.isCancelled());
    }

    /**
     * 清空今日日志文件（截断写），成功返回 true。
     * 统一入口替代原先两处 FileWriter 写法——其中一处未使用 try-with-resources，
     * 写失败时 FileWriter 不会被关闭，可能长期占用文件句柄。
     */
    private boolean clearLogFile() {
        try (FileWriter ignored = new FileWriter(logFile, false)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
