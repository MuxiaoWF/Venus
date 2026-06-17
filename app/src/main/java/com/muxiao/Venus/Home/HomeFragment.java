package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.BACKGROUND_TASK_ENABLED;
import static com.muxiao.Venus.common.Constants.Prefs.SETTINGS_PREFS_NAME;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.annotation.SuppressLint;
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
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.BatteryHelper;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.HeaderManager;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.TaskSettings;
import com.muxiao.Venus.common.tools;

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

public class HomeFragment extends Fragment {

    {
        setEnterTransition(new android.transition.Fade(android.transition.Fade.IN).setDuration(300));
    }
    private UserManager userManager;
    private ExecutorService executorService;
    private LinearLayout geetestContainer;
    private static Future<?> currentTaskFuture; // 任务
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
            if (gt3GeetestUtils == null)
                gt3GeetestUtils = new GT3GeetestUtils(getActivity());
        }

        @Override
        public void createButton(GT3ConfigBean gt3ConfigBean) {
            android.util.Log.e("VenusCaptcha", "Foreground controller createButton called");
            createUtils();
            requireActivity().runOnUiThread(() -> {
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
            requireActivity().runOnUiThread(() -> {
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
        List<String> usernames = userManager.getUsernames();
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

            // 清空上一次的日志并写入分隔符
            try {
                FileWriter writer = new FileWriter(logFile, false); // false 覆盖模式
                writer.write("");
                writer.close();
            } catch (IOException e) {
                show_error_dialog(requireContext(), "清空日志文件失败");
            }
            tools.writeLogSeparator(requireContext());
            // 刷新任务列表状态
            resetTaskList();

            user_dropdown_layout.setVisibility(View.GONE);// 隐藏用户下拉框
            cancel_daily_btn.setVisibility(View.VISIBLE); // 显示取消按钮
            start_daily_btn.setVisibility(View.GONE); // 隐藏启动按钮
            start_daily_bg_btn.setVisibility(View.GONE); // 隐藏后台运行按钮

            currentTaskFuture = executorService.submit(() -> {
                try {
                    TaskSettings settings = TaskSettings.fromPreferences(requireContext());
                    if (settings.hasAnyTaskDisabled()) {
                        requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "请先去设置里设置任务"));
                        return;
                    }
                    TaskExecutor taskExecutor = new TaskExecutor(
                            requireActivity(), currentUser, notifier, controller,
                            new TaskExecutor.Callback() {
                                @Override
                                public void onTaskStatusChanged(String taskName, TaskItem.TaskStatus status) {
                                    updateTaskStatus(taskName, status);
                                }
                                @Override
                                public void onAllTasksCompleted() {
                                    notifier.notifyListeners("任务完成");
                                }
                                @Override
                                public void onError(String message) {
                                    requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), message));
                                }
                                @Override
                                public boolean isCancelled() {
                                    return isTaskCancelled();
                                }
                            });
                    taskExecutor.executeAll(settings);
                } finally {
                    requireActivity().runOnUiThread(() -> {
                        controller.destroyButton();
                        controller.createUtils();
                        user_dropdown_layout.setVisibility(View.VISIBLE);
                        cancel_daily_btn.setVisibility(View.GONE);
                        start_daily_btn.setVisibility(View.VISIBLE);
                        updateBgButtonVisibility(); // 恢复后台按钮可见性
                    });
                }
            });
        });

        // 取消任务按钮
        cancel_daily_btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("确认取消")
                .setMessage("确定要取消当前任务吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    if (currentTaskFuture != null) {
                        currentTaskFuture.cancel(true); // 尝试取消任务
                        controller.destroyButton(); // 销毁验证码按钮
                        controller.createUtils(); // 创建验证码Utils以备下次使用
                    }
                    notifier.notifyListeners("任务已取消");
                })
                .setNegativeButton("继续任务", null)
                .show());

        // 后台运行/取消按钮
        start_daily_bg_btn.setOnClickListener(v -> {
            if (ForegroundTaskService.isRunning()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("确认取消")
                        .setMessage("确定要取消后台任务吗？")
                        .setPositiveButton("确定", (dialog, which) -> {
                            Intent stopIntent = new Intent(requireContext(), ForegroundTaskService.class);
                            stopIntent.setAction(ForegroundTaskService.ACTION_STOP_TASK);
                            requireContext().startService(stopIntent);
                            updateBgButtonState(false);
                        })
                        .setNegativeButton("继续任务", null)
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
                && "ACTION_HANDLE_CAPTCHA".equals(getActivity().getIntent().getAction())) {
            getActivity().getIntent().setAction(null);
            view.postDelayed(this::performBackgroundCaptchaVerification, 300);
        }

        // 查看日志按钮
        view_log_btn.setOnClickListener(v -> {
            try {
                if (logFile.exists() && logFile.length() > 0) {
                    List<String> lines = new ArrayList<>();
                    BufferedReader reader = new BufferedReader(new FileReader(logFile));
                    String line;
                    while ((line = reader.readLine()) != null)
                        lines.add(line);
                    reader.close();
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
                            .setTitle("日志 (" + logFile.getName() + ")")
                            .setView(scrollView)
                            .setPositiveButton("确定", null)
                            .setNegativeButton("清空日志", (dialog, which) -> {
                                try {
                                    FileWriter writer = new FileWriter(logFile, false);
                                    writer.write("");
                                    writer.close();
                                    tools.showCustomSnackbar(getView(), requireContext(), "日志已清空");
                                } catch (IOException e) {
                                    tools.show_error_dialog(requireContext(), "清空日志文件失败");
                                }
                            })
                            .show();
                } else {
                    tools.showCustomSnackbar(getView(), requireContext(), "暂无日志内容");
                }
            } catch (IOException e) {
                tools.show_error_dialog(requireContext(), "读取日志文件失败: " + e.getMessage());
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
                        BackgroundGeetestController.notifyVerificationSuccess(requireContext(), resultJson, geetestCode);
                        android.util.Log.e("VenusCaptcha", "Broadcast sent OK");
                        notification.dismissErrorNotification();
                    }

                    @Override
                    public void onVerificationFailed(String error) {
                        android.util.Log.e("VenusCaptcha", "Verification FAILED: " + error);
                        controller.destroyButton();
                        BackgroundGeetestController.notifyVerificationFailure(requireContext(), error);
                        notification.sendErrorNotification("人机验证失败", error, true);
                    }
                };

                // 使用后台任务的 challenge 进行验证（同一 challenge，避免 1034 循环）
                if (savedChallenge != null) {
                    android.util.Log.e("VenusCaptcha", "Using saved challenge: gt=" + savedChallenge[0]);
                    Geetest.geetestWithChallenge(savedChallenge[0], savedChallenge[1], headers, callback, controller);
                } else {
                    android.util.Log.e("VenusCaptcha", "No saved challenge, calling API1");
                    Geetest.geetest(headers, callback, controller);
                }
            } catch (Exception e) {
                android.util.Log.e("VenusCaptcha", "Exception in performBackgroundCaptchaVerification", e);
                try {
                    BackgroundGeetestController.notifyVerificationFailure(requireContext(), e.getMessage());
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * 初始化任务列表
     */
    private void initializeTaskList() {
        taskList = new ArrayList<>(buildTaskItems());
    }

    /**
     * 重置任务列表状态
     */
    @SuppressLint("NotifyDataSetChanged")
    private void resetTaskList() {
        taskList.clear();
        taskList.addAll(buildTaskItems());
        if (taskAdapter != null) taskAdapter.notifyDataSetChanged();
    }

    private List<TaskItem> buildTaskItems() {
        TaskSettings settings = TaskSettings.fromPreferences(requireContext());
        List<TaskItem> items = new ArrayList<>();
        for (String taskName : settings.getTaskNames())
            items.add(new TaskItem(taskName));
        return items;
    }

    /**
     * 更新特定任务的状态
     *
     * @param taskName 任务名称
     * @param status   任务状态
     */
    private void updateTaskStatus(String taskName, TaskItem.TaskStatus status) {
        requireActivity().runOnUiThread(() -> {
            if (taskAdapter != null && taskList != null) {
                for (int i = 0; i < taskList.size(); i++) {
                    if (taskList.get(i).getName().equals(taskName)) {
                        taskList.get(i).setStatus(status);
                        taskAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
        });
    }

    private void updateBgButtonState(boolean running) {
        if (start_daily_bg_btn == null) return;
        start_daily_bg_btn.setText(running ? "取消后台任务" : "后台运行");
        // 后台运行时隐藏前台按钮，停止后恢复
        if (start_daily_btn != null)
            start_daily_btn.setVisibility(running ? View.GONE : View.VISIBLE);
    }

    private void checkAndStartTask(String userId) {
        TaskSettings settings = TaskSettings.fromPreferences(requireContext());
        if (settings.hasAnyTaskDisabled()) {
            show_error_dialog(requireContext(), "请先去设置里设置任务");
            return;
        }
        if (userId == null || userId.isEmpty()) {
            show_error_dialog(requireContext(), "请先选择一个用户");
            return;
        }

        Notification notification = new Notification(requireContext());
        if (notification.areNotificationsDisabled()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("需要通知权限")
                    .setMessage("系统通知权限尚未开启，后台任务无法运行。是否前往系统设置开启？")
                    .setPositiveButton("去设置", (d, w) -> startActivity(notification.getNotificationSettingsIntent()))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        if (!BatteryHelper.isIgnoringBatteryOptimizations(requireContext())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("需要关闭电池优化")
                    .setMessage("系统电池优化可能会中断后台任务。请在电池设置中将本应用设为\"不受限制\"以确保任务正常执行。\n\n如看到厂商设置页面，请将本应用加入不限制名单。\n\n关闭后此提示将不再出现。")
                    .setPositiveButton("去设置", (d, w) -> BatteryHelper.openVendorBatterySettings(requireContext()))
                    .setNegativeButton("忽略", (d, w) -> startTaskService(userId))
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
        tools.showCustomSnackbar(getView(), requireContext(), "后台任务已启动，如遇到验证码会自动弹出，请留意通知");
    }

    private void updateBgButtonVisibility() {
        if (start_daily_bg_btn == null) return;
        SharedPreferences prefs = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
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
            List<String> usernames = userManager.getUsernames();
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
                // 如果之前选中的用户不存在了，设置为第一个用户
                user_dropdown.setText(usernames.get(0), false);
        }

        if (!isTaskRunning()) {
            if (taskList == null) {
                initializeTaskList();
                return;
            }
            TaskSettings settings = TaskSettings.fromPreferences(requireContext());
            List<String> newTasks = settings.getTaskNames();
            boolean hasChanges = taskList.size() != newTasks.size();
            if (!hasChanges)
                for (int i = 0; i < taskList.size(); i++)
                    if (!taskList.get(i).getName().equals(newTasks.get(i))) {
                        hasChanges = true;
                        break;
                    }
            if (hasChanges) resetTaskList();
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
