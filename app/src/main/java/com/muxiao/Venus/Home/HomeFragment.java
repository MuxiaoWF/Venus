package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.views.GT3GeetestButton;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.MiHoYoBBSConstants;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;
import com.muxiao.Venus.widget.TaskStatusManager;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 主页Fragment：用户选择下拉框、任务列表展示、签到执行（前台/后台）、
 * 任务状态实时更新、日志查看、配置更新入口。
 */
public class HomeFragment extends Fragment {

    {
        setEnterTransition(new android.transition.Fade(android.transition.Fade.IN).setDuration(300));
    }
    private UserManager userManager;
    private ExecutorService executorService;
    private LinearLayout geetestContainer;
    private static volatile Future<?> currentTaskFuture;
    private String currentUser;
    private MaterialAutoCompleteTextView user_dropdown;
    private MaterialButton start_daily_btn;
    private MaterialButton start_daily_bg_btn;
    private TaskAdapter taskAdapter;
    private List<TaskItem> taskList;
    private File logFile; // 日志文件路径
    private BroadcastReceiver taskStateReceiver;

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
            android.util.Log.e("VenusCaptcha", "Foreground controller createButton called");
            createUtils();
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                android.util.Log.e("VenusCaptcha", "Running on UI thread, creating button");
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
                    android.util.Log.e("Geetest", "Error creating GT3GeetestButton", e);
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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        user_dropdown = view.findViewById(R.id.user_dropdown);
        start_daily_btn = view.findViewById(R.id.start_daily);
        MaterialButton cancel_daily_btn = view.findViewById(R.id.cancel_daily);
        start_daily_bg_btn = view.findViewById(R.id.start_daily_bg);
        MaterialButton view_log_btn = view.findViewById(R.id.view_log_btn);
        RecyclerView tasksRecyclerView = view.findViewById(R.id.tasks_recycler_view);
        geetestContainer = view.findViewById(R.id.geetest_container);
        TextInputLayout user_dropdown_layout = view.findViewById(R.id.user_dropdown_layout);

        View bottomPaddingView = view.findViewById(R.id.bottom_padding_view);
        if (getActivity() instanceof MainActivity)
            ((MainActivity) getActivity()).applyBottomPadding(bottomPaddingView);

        // 提示框
        CollapsibleCardView homeInfoCard = view.findViewById(R.id.home_info_card);
        homeInfoCard.setContent(R.layout.item_home_daily_info);

        // 初始化
        userManager = new UserManager(requireContext());
        executorService = Executors.newSingleThreadExecutor();
        controller.createUtils();
        tools.cleanOldLogs(requireContext());
        logFile = tools.getTodayLogFile(requireContext());
        // 初始化任务列表
        initializeTaskList();

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
        // 设置当前用户为默认选中项
        currentUser = userManager.getCurrentUser();
        if (!currentUser.isEmpty() && usernames.contains(currentUser)) {
            user_dropdown.setText(currentUser, false);
        } else if (!usernames.isEmpty()) {
            // 如果当前用户不存在或为空，设置为第一个用户
            currentUser = usernames.get(0);
            user_dropdown.setText(currentUser, false);
        }
        user_dropdown.setOnItemClickListener((parent, view1, position, id) -> {
            currentUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(currentUser);
        });

        // 设置任务列表RecyclerView
        tasksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        tasksRecyclerView.setItemAnimator(new com.muxiao.Venus.common.ScaleInItemAnimator());
        taskAdapter = new TaskAdapter(taskList);
        tasksRecyclerView.setAdapter(taskAdapter);

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
            // 刷新任务列表状态
            resetTaskList();
            // 记录当前用户供 Widget 显示
            new TaskStatusManager(requireContext()).setCurrentUser(currentUser != null ? currentUser : "");

            user_dropdown_layout.setVisibility(View.GONE);// 隐藏用户下拉框
            cancel_daily_btn.setVisibility(View.VISIBLE); // 显示取消按钮
            start_daily_btn.setVisibility(View.GONE); // 隐藏启动按钮
            start_daily_bg_btn.setVisibility(View.GONE); // 隐藏后台运行按钮

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
                            taskActivity, currentUser, notifier, controller,
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
                            user_dropdown_layout.setVisibility(View.VISIBLE);
                            cancel_daily_btn.setVisibility(View.GONE);
                            start_daily_btn.setVisibility(View.VISIBLE);
                            updateBgButtonVisibility(); // 恢复后台按钮可见性
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
                            updateBgButtonState(false);
                        })
                        .setNegativeButton(getString(R.string.btn_continue_task), null)
                        .show();
                return;
            }

            if (homeInfoCard.isExpanded()) homeInfoCard.toggle();
            checkAndStartTask(currentUser);
        });

        // 根据设置显示/隐藏后台运行按钮
        updateBgButtonVisibility();

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
        android.util.Log.i("VenusCaptcha", "performBackgroundCaptchaVerification called, controller=" + controller);
        // Geetest.geetest() 包含同步网络请求，必须在后台线程执行
        new Thread(() -> {
            try {
                // 优先使用后台任务保存的 headers（含 Cookie），确保 API2 二次验证能正确绑定会话
                Map<String, String> headers = BackgroundGeetestController.consumePendingHeaders();
                if (headers == null) {
                    android.util.Log.e("VenusCaptcha", "No pending headers, using fresh BBS headers");
                    headers = new HeaderManager(requireContext()).get_bbs_headers();
                } else {
                    android.util.Log.e("VenusCaptcha", "Using pending headers from background task");
                }

                // 检查是否有后台任务保存的 challenge（避免重复 API1 导致 challenge 不匹配）
                String[] savedChallenge = BackgroundGeetestController.consumePendingChallenge();
                Notification notification = new Notification(requireContext());
                GeetestVerificationCallback callback = new GeetestVerificationCallback() {
                    @Override
                    public void onVerificationSuccess(Map<String, String> geetestCode) {
                        android.util.Log.e("VenusCaptcha", "Verification SUCCESS, sending broadcast...");
                        controller.destroyButton();
                        String resultJson = new com.google.gson.Gson().toJson(geetestCode);
                        android.util.Log.e("VenusCaptcha", "Broadcast resultJson=" + resultJson);
                        BackgroundGeetestController.notifyVerificationSuccess(geetestCode);
                        android.util.Log.e("VenusCaptcha", "Broadcast sent OK");
                        notification.dismissErrorNotification();
                    }

                    @Override
                    public void onVerificationFailed(String error) {
                        android.util.Log.e("VenusCaptcha", "Verification FAILED: " + error);
                        controller.destroyButton();
                        BackgroundGeetestController.notifyVerificationFailure(error);
                        notification.sendErrorNotification(getString(R.string.notif_captcha_failed), error, true);
                    }
                };

                // 使用后台任务的 challenge 进行验证（同一 challenge，避免 1034 循环）
                if (savedChallenge != null) {
                    android.util.Log.e("VenusCaptcha", "Using saved challenge: gt=" + savedChallenge[0]);
                    Geetest.geetestWithChallenge(requireContext(), savedChallenge[0], savedChallenge[1], headers, callback, controller);
                } else {
                    android.util.Log.e("VenusCaptcha", "No saved challenge, calling API1");
                    Geetest.geetest(requireContext(), headers, callback, controller);
                }
            } catch (Exception e) {
                android.util.Log.e("VenusCaptcha", "Exception in performBackgroundCaptchaVerification", e);
                try {
                    BackgroundGeetestController.notifyVerificationFailure(e.getMessage());
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 初始化任务列表
     */
    private void initializeTaskList() {
        if (taskList != null) {
            replaceAllTasks(buildTaskItems());
        } else {
            taskList = new ArrayList<>(buildTaskItems());
        }
    }

    /**
     * 重置任务列表状态
     */
    private void resetTaskList() {
        replaceAllTasks(buildTaskItems());
    }

    private void replaceAllTasks(List<TaskItem> newItems) {
        int oldSize = taskList.size();
        taskList.clear();
        taskList.addAll(newItems);
        if (taskAdapter != null) {
            if (oldSize > 0) taskAdapter.notifyItemRangeRemoved(0, oldSize);
            if (!newItems.isEmpty()) taskAdapter.notifyItemRangeInserted(0, newItems.size());
        }
    }

    private List<TaskItem> buildTaskItems() {
        return buildTaskItems(TaskSettings.fromPreferences(requireContext()));
    }

    private List<TaskItem> buildTaskItems(TaskSettings settings) {
        TaskStatusManager statusManager = new TaskStatusManager(requireContext());
        List<TaskItem> items = new ArrayList<>();
        for (String taskName : settings.getTaskNames(requireContext())) {
            TaskItem item = new TaskItem(taskName);
            String status = statusManager.getStatus(taskName);
            switch (status) {
                case TaskStatusManager.STATUS_COMPLETED:
                    item.setStatus(TaskItem.TaskStatus.COMPLETED);
                    break;
                case TaskStatusManager.STATUS_ERROR:
                    item.setStatus(TaskItem.TaskStatus.ERROR);
                    break;
                case TaskStatusManager.STATUS_IN_PROGRESS:
                    item.setStatus(TaskItem.TaskStatus.IN_PROGRESS);
                    break;
                case TaskStatusManager.STATUS_WARNING:
                    item.setStatus(TaskItem.TaskStatus.WARNING);
                    break;
                case TaskStatusManager.STATUS_CANCELLED:
                    item.setStatus(TaskItem.TaskStatus.CANCELLED);
                    break;
                default:
                    break;
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 更新特定任务的状态
     *
     * @param taskName 任务名称
     * @param status   任务状态
     */
    private void updateTaskStatus(String taskName, TaskItem.TaskStatus status) {
        // 持久化任务状态供 Widget 读取
        saveTaskStatus(taskName, status);

        android.app.Activity statusActivity = getActivity();
        if (statusActivity == null) return;
        statusActivity.runOnUiThread(() -> {
            if (taskAdapter != null && taskList != null) {
                for (int i = 0; i < taskList.size(); i++) {
                    if (taskList.get(i).getName().equals(taskName)) {
                        taskList.get(i).setStatus(status);
                        taskAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
            // 通知 Widget 刷新
            TaskWidgetProvider.refreshAllWidgets(requireContext());
        });
    }

    private void saveTaskStatus(String taskName, TaskItem.TaskStatus status) {
        TaskStatusManager manager = new TaskStatusManager(requireContext());
        manager.setCurrentUser(currentUser != null ? currentUser : "");
        switch (status) {
            case COMPLETED:
                manager.markCompleted(taskName);
                break;
            case ERROR:
                manager.markError(taskName);
                break;
            case IN_PROGRESS:
                manager.markStatus(taskName, TaskStatusManager.STATUS_IN_PROGRESS);
                break;
            case WARNING:
                manager.markStatus(taskName, TaskStatusManager.STATUS_WARNING);
                break;
            case CANCELLED:
                manager.markStatus(taskName, TaskStatusManager.STATUS_CANCELLED);
                break;
            default:
                break;
        }
    }

    private void updateBgButtonState(boolean running) {
        if (start_daily_bg_btn == null) return;
        start_daily_bg_btn.setText(running ? getString(R.string.cancel_task) : getString(R.string.background_running));
        // 后台运行时隐藏前台按钮，停止后恢复
        if (start_daily_btn != null)
            start_daily_btn.setVisibility(running ? View.GONE : View.VISIBLE);
    }

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

    private void startTaskService(String userId) {
        Intent serviceIntent = new Intent(requireContext(), ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, userId);
        androidx.core.content.ContextCompat.startForegroundService(requireContext(), serviceIntent);
        updateBgButtonState(true);
        tools.showCustomSnackbar(getView(), requireContext(), getString(R.string.snack_bg_task_started));
    }

    private void updateBgButtonVisibility() {
        if (start_daily_bg_btn == null) return;
        android.app.Activity bgActivity = getActivity();
        if (bgActivity == null) return;
        SharedPreferences prefs = bgActivity.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(BACKGROUND_TASK_ENABLED, false);
        start_daily_bg_btn.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 注册后台任务状态广播接收器
        taskStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra(ForegroundTaskService.EXTRA_IS_RUNNING, false);
                updateBgButtonState(running);
            }
        };
        IntentFilter filter = new IntentFilter(ForegroundTaskService.ACTION_TASK_STATE_CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(
                requireContext(), taskStateReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        updateBgButtonState(ForegroundTaskService.isRunning());
        updateBgButtonVisibility();

        // 更新下拉框中的用户列表
        if (userManager != null && user_dropdown != null) {
            currentUser = userManager.getCurrentUser();
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
            if (taskList == null) {
                initializeTaskList();
                return;
            }
            TaskSettings settings = TaskSettings.fromPreferences(requireContext());
            List<String> newTasks = settings.getTaskNames(requireContext());
            boolean hasChanges = taskList.size() != newTasks.size();
            if (!hasChanges)
                for (int i = 0; i < taskList.size(); i++)
                    if (!taskList.get(i).getName().equals(newTasks.get(i))) {
                        hasChanges = true;
                        break;
                    }
            if (hasChanges) {
                replaceAllTasks(buildTaskItems(settings));
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (taskStateReceiver != null) {
            requireContext().unregisterReceiver(taskStateReceiver);
            taskStateReceiver = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown())
            executorService.shutdown();

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
