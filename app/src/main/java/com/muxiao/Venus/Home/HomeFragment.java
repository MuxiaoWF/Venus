package com.muxiao.Venus.Home;

import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.views.GT3GeetestButton;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.AppExecutors;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.Logger;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskWidgetProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 主页Fragment：用户选择下拉框、任务列表展示、签到执行（前台/后台）、
 * 任务状态实时更新、日志查看、配置更新入口。
 */
@AndroidEntryPoint
public class HomeFragment extends Fragment {

    /** 向宿主 Activity 广播“本 Fragment 已就绪”，用于触发后台人机验证（替代 postDelayed 轮询）。 */
    public static final String RESULT_HOME_READY = "home_fragment_ready";

    /** 初始化：设置 Fragment 转场动画。 */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tools.setupFragmentTransitions(this);
    }

    private UserManager userManager;
    @Inject AppExecutors appExecutors;
    private ExecutorService executorService;
    private LinearLayout geetestContainer;
    private static volatile Future<?> currentTaskFuture;
    private MaterialAutoCompleteTextView user_dropdown;
    private MaterialButton start_daily_btn;
    private MaterialButton start_daily_bg_btn;
    private MaterialButton cancel_daily_btn;
    private TextInputLayout user_dropdown_layout;
    private TaskAdapter taskAdapter;
    private View taskListEmptyView;
    private ViewGroup homeContentContainer;
    /** Adapter 的渲染缓冲区（唯一真相在 HomeViewModel 的 TaskUiState 中）。 */
    private final List<TaskItem> taskList = new ArrayList<>();
    private HomeViewModel viewModel;
    private File logFile; // 日志文件路径
    private BroadcastReceiver taskStateReceiver;
    private BroadcastReceiver taskStatusReceiver;

    private final GeetestController controller = new GeetestController() {
        private GT3GeetestUtils gt3GeetestUtils;
        public GT3GeetestButton geetestButton;

        @Override
        public void createUtils() {
            if (gt3GeetestUtils == null) {
                android.app.Activity activity = getActivity();
                if (activity != null)
                    gt3GeetestUtils = new GT3GeetestUtils(activity);
            }
        }

        @Override
        public void createButton(GT3ConfigBean gt3ConfigBean) {
            Logger.debug("VenusCaptcha", "Foreground controller createButton called");
            createUtils();
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                Logger.debug("VenusCaptcha", "Running on UI thread, creating button");
                try {
                    // 动态创建GT3GeetestButton
                    // 如果按钮已存在，先销毁
                    if (geetestButton != null && geetestContainer != null) {
                        geetestContainer.removeView(geetestButton);
                        geetestButton = null;
                    }
                    // 创建新的GT3GeetestButton实例
                    geetestButton = new GT3GeetestButton(requireContext());
                    // 设置布局参数
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            (int) (290 * getResources().getDisplayMetrics().density),
                            (int) (44 * getResources().getDisplayMetrics().density)
                    );
                    params.setMargins(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
                    params.gravity = Gravity.CENTER_HORIZONTAL;
                    geetestButton.setLayoutParams(params);
                    // 添加到容器中
                    geetestContainer.addView(geetestButton);
                    geetestContainer.setVisibility(View.VISIBLE);
                    gt3GeetestUtils.init(gt3ConfigBean);
                    geetestButton.setGeetestUtils(gt3GeetestUtils);
                } catch (Exception e) {
                    Logger.e("Error creating GT3GeetestButton", e);
                }
            });
        }

        @Override
        public void destroyButton() {
            android.app.Activity destroyActivity = getActivity();
            if (destroyActivity == null) return;
            destroyActivity.runOnUiThread(() -> {
                // 销毁按钮
                if (geetestButton != null && geetestContainer != null) {
                    geetestContainer.removeView(geetestButton);
                    geetestButton = null;
                    geetestContainer.setVisibility(View.GONE);
                }
            });
            // 销毁工具
            destroyUtils();
        }

        @Override
        public GT3GeetestUtils getGeetestUtils() {
            return gt3GeetestUtils;
        }

        @Override
        public void destroyUtils() {
            if (gt3GeetestUtils != null) {
                gt3GeetestUtils.destory();
                gt3GeetestUtils = null;
            }
        }

        @Override
        public void updateTaskStatusWaring(String taskName) {
            updateTaskStatus(taskName, TaskItem.TaskStatus.WARNING);
        }

        @Override
        public void updateTaskStatusInProgress(String taskName) {
            updateTaskStatus(taskName, TaskItem.TaskStatus.IN_PROGRESS);
        }
    };

    /** 构建主页 UI：绑定视图、初始化下拉框/任务列表、注册按钮与日志查看；并以 ViewModel 单向数据流渲染。 */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        // ViewModel 作为 UI 状态单一可信数据源（P0-2）
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        user_dropdown = view.findViewById(R.id.user_dropdown);
        start_daily_btn = view.findViewById(R.id.start_daily);
        cancel_daily_btn = view.findViewById(R.id.cancel_daily);
        start_daily_bg_btn = view.findViewById(R.id.start_daily_bg);
        MaterialButton view_log_btn = view.findViewById(R.id.view_log_btn);
        RecyclerView tasksRecyclerView = view.findViewById(R.id.tasks_recycler_view);
        taskListEmptyView = view.findViewById(R.id.task_list_empty_view);
        geetestContainer = view.findViewById(R.id.geetest_container);
        user_dropdown_layout = view.findViewById(R.id.user_dropdown_layout);
        homeContentContainer = view.findViewById(R.id.home_content_container);

        // 提示框
        CollapsibleCardView homeInfoCard = view.findViewById(R.id.home_info_card);
        homeInfoCard.setContent(R.layout.item_home_daily_info);

        // 初始化
        userManager = new UserManager(requireContext());
        executorService = appExecutors.io();
        controller.createUtils();
        tools.cleanOldLogs(requireContext());
        logFile = tools.getTodayLogFile(requireContext());
        // 初始化任务列表（经 ViewModel 单向数据流，数据访问已下沉到 VM）
        viewModel.refreshTaskItems();

        // 输出文本监听器
        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(message -> tools.writeLog(requireContext(), message));

        // 初始化下拉框
        boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
        List<String> usernames = userManager.getUsernamesByServerType(isOversea);
        user_dropdown.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                usernames
        ));
        // 设置当前用户为默认选中项（当前用户由 ViewModel 持有，避免 Fragment 与 VM 双份真相）
        String initialUser = userManager.getCurrentUser();
        if (!initialUser.isEmpty() && usernames.contains(initialUser)) {
            viewModel.setCurrentUser(initialUser);
            user_dropdown.setText(initialUser, false);
        } else if (!usernames.isEmpty()) {
            // 如果当前用户不存在或为空，设置为第一个用户
            viewModel.setCurrentUser(usernames.get(0));
            user_dropdown.setText(usernames.get(0), false);
        } else {
            viewModel.setCurrentUser("");
        }
        user_dropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String picked = (String) parent.getItemAtPosition(position);
            viewModel.setCurrentUser(picked);
            userManager.setCurrentUser(picked);
        });

        // 设置任务列表RecyclerView
        tasksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        tasksRecyclerView.setItemAnimator(new com.muxiao.Venus.common.ScaleInItemAnimator(false));
        taskAdapter = new TaskAdapter(taskList);
        tasksRecyclerView.setAdapter(taskAdapter);
        // 单向数据流：观察 ViewModel 的不可变 UiState，一次性渲染任务列表 + 全部按钮可见性
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);

        // 启动任务按钮
        start_daily_btn.setOnClickListener(v -> {
            if (homeInfoCard.isExpanded())
                homeInfoCard.toggle();

            try (FileWriter ignored = new FileWriter(logFile, false)) {
                // false 覆盖模式，清空文件
            } catch (IOException e) {
                show_error_dialog(requireContext(), getString(R.string.err_clear_log_failed));
            }
            tools.writeLogSeparator(requireContext());
            // 新一轮运行：复位全部任务状态（含已完成）为未完成，避免重跑时先显示旧状态图标
            viewModel.resetForNewRun();
            // 记录当前用户供 Widget 显示
            viewModel.persistCurrentUserForWidget();

            // 单一状态切换取代原先 4 处 setVisibility：可见性由 TaskUiState 派生
            viewModel.setRunMode(HomeViewModel.RunMode.FOREGROUND);

            currentTaskFuture = executorService.submit(() -> {
                try {
                    TaskSettings settings = TaskSettings.fromPreferences(requireContext());
                    if (settings.hasAnyTaskDisabled()) {
                        android.app.Activity activity = getActivity();
                        if (activity != null) activity.runOnUiThread(() -> show_error_dialog(requireContext(), getString(R.string.err_set_task_first)));
                        return;
                    }
                    android.app.Activity taskActivity = getActivity();
                    if (taskActivity == null) return;
                    TaskExecutor taskExecutor = new TaskExecutor(
                            taskActivity, viewModel.getCurrentUser(), notifier, controller,
                            new TaskExecutor.Callback() {
                                @Override
                                public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                    updateTaskStatus(taskName, status);
                                }
                                @Override
                                public void onAllTasksCompleted() {
                                    notifier.notifyListeners(getString(R.string.task_completed));
                                }
                                @Override
                                public void onError(String message) {
                                    android.app.Activity a = getActivity();
                                    if (a != null) a.runOnUiThread(() -> show_error_dialog(requireContext(), message));
                                }
                                @Override
                                public boolean isCancelled() {
                                    return isTaskCancelled();
                                }
                            });
                    taskExecutor.executeAll(settings);
                } finally {
                    android.app.Activity finallyActivity = getActivity();
                    if (finallyActivity != null) {
                        finallyActivity.runOnUiThread(() -> {
                            controller.destroyButton();
                            controller.createUtils();
                            // 回到空闲态：下拉框/启动按钮恢复、取消按钮隐藏、后台按钮按设置恢复
                            viewModel.refreshBgFeatureEnabled();
                            viewModel.setRunMode(HomeViewModel.RunMode.IDLE);
                        });
                    }
                }
            });
        });

        // 取消任务按钮
        cancel_daily_btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_confirm_cancel))
                .setMessage(getString(R.string.msg_confirm_cancel_current_task))
                .setPositiveButton(getString(R.string.btn_ok), (dialog, which) -> {
                    if (currentTaskFuture != null) {
                        currentTaskFuture.cancel(true); // 尝试取消任务
                        controller.destroyButton(); // 销毁验证码按钮
                        controller.createUtils(); // 创建验证码Utils以备下次使用
                    }
                    notifier.notifyListeners(getString(R.string.task_cancelled));
                })
                .setNegativeButton(getString(R.string.btn_continue_task), null)
                .show());

        // 后台运行/取消按钮
        start_daily_bg_btn.setOnClickListener(v -> {
            if (ForegroundTaskService.isRunning()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.dialog_confirm_cancel))
                        .setMessage(getString(R.string.msg_confirm_cancel_background_task))
                .setPositiveButton(getString(R.string.btn_ok), (dialog, which) -> {
                    Intent stopIntent = new Intent(requireContext(), ForegroundTaskService.class);
                    stopIntent.setAction(ForegroundTaskService.ACTION_STOP_TASK);
                    requireContext().startService(stopIntent);
                    // 不在此立即置 IDLE：等待服务 cancelTask() 广播 is_running=false，
                    // 由 applyBackgroundRunning(false) 统一切回 IDLE，避免按钮状态提前翻转
                })
                        .setNegativeButton(getString(R.string.btn_continue_task), null)
                        .show();
                return;
            }

            if (homeInfoCard.isExpanded()) homeInfoCard.toggle();
            checkAndStartTask(viewModel.getCurrentUser());
        });

        // 根据设置显示/隐藏后台运行按钮（经 UiState 派生）
        viewModel.refreshBgFeatureEnabled();

        // 检查是否需要处理后台人机验证
        if (getActivity() != null && getActivity().getIntent() != null
                && Constants.ACTION_HANDLE_CAPTCHA.equals(getActivity().getIntent().getAction())) {
            getActivity().getIntent().setAction(null);
            view.postDelayed(this::performBackgroundCaptchaVerification, 300);
        }

        // 查看日志按钮
        view_log_btn.setOnClickListener(v -> {
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

                    // 创建可滚动的 TextView
                    android.widget.ScrollView scrollView = new android.widget.ScrollView(requireContext());
                    int padding = (int) (16 * getResources().getDisplayMetrics().density);
                    scrollView.setPadding(padding, padding / 2, padding, 0);

                    com.google.android.material.textview.MaterialTextView logTextView =
                            new com.google.android.material.textview.MaterialTextView(requireContext());
                    logTextView.setText(content.toString());
                    logTextView.setTextSize(12);
                    logTextView.setTextIsSelectable(true);
                    logTextView.setTypeface(android.graphics.Typeface.MONOSPACE);
                    scrollView.addView(logTextView);

                    // 显示日志内容对话框
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.dialog_log_title, logFile.getName()))
                            .setView(scrollView)
                            .setPositiveButton(getString(R.string.btn_ok), null)
                            .setNegativeButton(getString(R.string.btn_clear_log), (dialog, which) -> {
                                try {
                                    FileWriter writer = new FileWriter(logFile, false);
                                    writer.write("");
                                    writer.close();
                                    tools.showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_log_cleared));
                                } catch (IOException e) {
                                    tools.show_error_dialog(requireContext(), getString(R.string.err_clear_log_failed));
                                }
                            })
                            .show();
                } else {
                    tools.showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_no_log));
                }
            } catch (IOException e) {
                tools.show_error_dialog(requireContext(), getString(R.string.err_read_log_failed, e.getMessage()));
            }
        });
        return view;
    }

    /**
     * 由 MainActivity 调用，处理后台人机验证通知跳转。
     * 在前台使用本地 GeetestController 展示验证 UI，完成后通过广播回传结果给后台 Service。
     */
    public void performBackgroundCaptchaVerification() {
        Logger.debug("VenusCaptcha", "performBackgroundCaptchaVerification called, controller=" + controller);
        // Geetest.geetest() 包含同步网络请求，必须在后台线程执行
        new Thread(() -> {
            try {
                // 优先使用后台任务保存的 headers（含 Cookie），确保 API2 二次验证能正确绑定会话
                Map<String, String> headers = BackgroundGeetestController.consumePendingHeaders();
                if (headers == null) {
                    Logger.debug("VenusCaptcha", "No pending headers, using fresh BBS headers");
                    headers = new HeaderManager(requireContext()).get_bbs_headers();
                } else {
                    Logger.debug("VenusCaptcha", "Using pending headers from background task");
                }

                // 检查是否有后台任务保存的 challenge（避免重复 API1 导致 challenge 不匹配）
                String[] savedChallenge = BackgroundGeetestController.consumePendingChallenge();
                Notification notification = new Notification(requireContext());
                GeetestVerificationCallback callback = new GeetestVerificationCallback() {
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
                        BackgroundGeetestController.notifyVerificationFailure(error);
                        notification.sendErrorNotification(getString(R.string.notif_captcha_failed), error, true);
                    }
                };

                // 使用后台任务的 challenge 进行验证（同一 challenge，避免 1034 循环）
                if (savedChallenge != null) {
                    Logger.debug("VenusCaptcha", "Using saved challenge: gt=" + savedChallenge[0]);
                    Geetest.geetestWithChallenge(requireContext(), savedChallenge[0], savedChallenge[1], headers, callback, controller);
                } else {
                    Logger.debug("VenusCaptcha", "No saved challenge, calling API1");
                    Geetest.geetest(requireContext(), headers, callback, controller);
                }
            } catch (Exception e) {
                Logger.e("Exception in performBackgroundCaptchaVerification", e);
                try {
                    BackgroundGeetestController.notifyVerificationFailure(e.getMessage());
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 渲染 ViewModel 的不可变 UiState：任务列表 + 全部按钮/下拉框可见性。
     * 这是本 Fragment 唯一改动 UI 可见性的入口，避免多处 setVisibility 互相覆盖。
     */
    @SuppressLint("NotifyDataSetChanged")
    private void render(HomeViewModel.TaskUiState state) {
        if (state == null) return;

        if (!tools.isReducedMotionEnabled(requireContext()) && homeContentContainer != null)
            TransitionManager.beginDelayedTransition(homeContentContainer, new Fade());

        // 任务列表
        synchronized (taskList) {
            taskList.clear();
            taskList.addAll(state.items);
            if (taskListEmptyView != null)
                taskListEmptyView.setVisibility(state.items.isEmpty() ? View.VISIBLE : View.GONE);
            if (taskAdapter != null) {
                // 数据整体替换：用 notifyDataSetChanged 一次性刷新。
                // 不能用 notifyItemRangeRemoved + notifyItemRangeInserted——后者会触发
                // ScaleInItemAnimator 对“所有条目”做淡出/缩放入场，导致每次任务状态变更时整列闪动；
                // 且 oldSize≠newSize 时还存在通知与实际条目数不一致的崩溃隐患。
                // ScaleInItemAnimator.animateChange 已禁用 change 动画，更新时只安静重绑；
                // 首次加载的入场动画由 TaskAdapter.animateEnter() 负责，不受影响。
                taskAdapter.notifyDataSetChanged();
            }
        }

        // 可见性一律由 UiState 派生
        if (user_dropdown_layout != null)
            user_dropdown_layout.setVisibility(state.userDropdownVisible() ? View.VISIBLE : View.GONE);
        if (cancel_daily_btn != null)
            cancel_daily_btn.setVisibility(state.cancelButtonVisible() ? View.VISIBLE : View.GONE);
        if (start_daily_btn != null)
            start_daily_btn.setVisibility(state.startButtonVisible() ? View.VISIBLE : View.GONE);
        if (start_daily_bg_btn != null) {
            start_daily_bg_btn.setVisibility(state.bgButtonVisible() ? View.VISIBLE : View.GONE);
            start_daily_bg_btn.setText(state.bgButtonShowsCancel()
                    ? getString(R.string.cancel_task)
                    : getString(R.string.background_running));
        }
    }

    /**
     * 更新特定任务的状态（持久化 + 发布快照 + 刷新小组件均下沉到 ViewModel）。
     *
     * @param taskName 任务名称
     * @param status   任务状态
     */
    private void updateTaskStatus(String taskName, TaskItem.TaskStatus status) {
        viewModel.updateTaskStatus(taskName, status);
    }

    /** 启动后台任务前校验：任务已配置、已选用户、通知权限、电池优化豁免，全部通过才拉起 Service。 */
    private void checkAndStartTask(String userId) {
        TaskSettings settings = TaskSettings.fromPreferences(requireContext());
        if (settings.hasAnyTaskDisabled()) {
            show_error_dialog(requireContext(), getString(R.string.err_set_task_first));
            return;
        }
        if (userId == null || userId.isEmpty()) {
            show_error_dialog(requireContext(), getString(R.string.msg_select_user_first));
            return;
        }

        Notification notification = new Notification(requireContext());
        if (notification.areNotificationsDisabled()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_need_notification_permission))
                    .setMessage(getString(R.string.msg_notification_required_for_background))
                    .setPositiveButton(getString(R.string.btn_go_to_settings), (d, w) -> startActivity(notification.getNotificationSettingsIntent()))
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show();
            return;
        }

        if (!BatteryHelper.isIgnoringBatteryOptimizations(requireContext())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.dialog_need_battery_optimization))
                    .setMessage(getString(R.string.msg_battery_optimization_hint))
                    .setPositiveButton(getString(R.string.btn_go_to_settings), (d, w) -> BatteryHelper.openVendorBatterySettings(requireContext()))
                    .setNegativeButton(getString(R.string.btn_ignore), (d, w) -> startTaskService(userId))
                    .show();
            return;
        }

        startTaskService(userId);
    }

    /** 真正启动前台任务 Service，并切到 BACKGROUND 运行态、提示已启动。 */
    private void startTaskService(String userId) {
        Intent serviceIntent = new Intent(requireContext(), ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, userId);
        androidx.core.content.ContextCompat.startForegroundService(requireContext(), serviceIntent);
        // 新一轮运行：复位全部任务状态（含已完成）为未完成，避免重跑时先显示旧状态图标
        viewModel.resetForNewRun();
        // 单一状态切换取代原 updateBgButtonState(true)：后台按钮文案/可见性由 UiState 派生
        viewModel.setRunMode(HomeViewModel.RunMode.BACKGROUND);
        tools.showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_bg_task_started));
    }

    /** 恢复时注册状态广播、按真实运行状态对齐 UI、刷新用户下拉框与小组件，并通知宿主已就绪（触发后台验证）。 */
    @Override
    public void onResume() {
        super.onResume();
        // 注册后台任务状态广播接收器
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
                requireContext(), taskStateReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        // onResume 对齐真实运行状态：前台线程是否存活 + 后台服务是否在跑（前台优先），并刷新后台按钮开关
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
                    viewModel.updateTaskStatus(name, status);
                } catch (IllegalArgumentException ignored) {
                }
            }
        };
        IntentFilter statusFilter = new IntentFilter(ForegroundTaskService.ACTION_TASK_STATUS_UPDATED);
        androidx.core.content.ContextCompat.registerReceiver(
                requireContext(), taskStatusReceiver, statusFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);

        // 更新下拉框中的用户列表（当前用户单一来源为 ViewModel）
        if (userManager != null && user_dropdown != null) {
            viewModel.setCurrentUser(userManager.getCurrentUser());
            String currentUser = viewModel.getCurrentUser();
            boolean isOversea = MiHoYoBBSConstants.is_oversea(requireContext());
            List<String> usernames = userManager.getUsernamesByServerType(isOversea);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    usernames
            );
            user_dropdown.setAdapter(adapter);

            // 恢复之前选中的用户（如果仍然存在于列表中）
            if (!currentUser.isEmpty() && usernames.contains(currentUser))
                user_dropdown.setText(currentUser, false);
            else if (!usernames.isEmpty())
                user_dropdown.setText(usernames.get(0), false);
            else
                user_dropdown.setText("", false);
        }

        // 刷新小组件
        TaskWidgetProvider.refreshAllWidgets(requireContext());

        if (!isTaskRunning()) {
            // 始终触发 TaskStatusManager.ensureToday()：跨天回到前台时即使任务集合与脏标记都无变化，
            // 也必须重建列表并清除昨日状态（否则日期变更后「已完成」会被当成当日状态沿用）。
            TaskSettings settings = TaskSettings.fromPreferences(requireContext());
            viewModel.refreshTaskItems(settings);
        }

        // 通知宿主 Activity 本 Fragment 已就绪（用于触发后台人机验证，替代 postDelayed 轮询）
        getParentFragmentManager().setFragmentResult(RESULT_HOME_READY, new Bundle());
    }

    /** 暂停时注销后台任务状态广播接收器，避免泄漏。 */
    @Override
    public void onPause() {
        super.onPause();
        if (taskStateReceiver != null) {
            requireContext().unregisterReceiver(taskStateReceiver);
            taskStateReceiver = null;
        }
        if (taskStatusReceiver != null) {
            requireContext().unregisterReceiver(taskStatusReceiver);
            taskStatusReceiver = null;
        }
    }

    /** 销毁时清理极验按钮资源（共享线程池不可 shutdown）。 */
    @Override
    public void onDestroy() {
        super.onDestroy();
        // 注意：executorService 现为 AppExecutors 共享线程池，禁止 shutdown。

        // 清理GT相关资源
        controller.destroyButton();
    }

    /**
     * 判断是否正在运行任务
     */
    public static boolean isTaskRunning() {
        return currentTaskFuture != null && !currentTaskFuture.isDone();
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
}
