package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.Constants.Prefs.*;
import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.annotation.SuppressLint;
import android.content.Context;
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
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HomeFragment extends Fragment {
    private UserManager user_manager;
    private ExecutorService executorService;
    private LinearLayout geetestContainer;
    private static Future<?> currentTaskFuture; // 任务
    private String current_user;
    private MaterialAutoCompleteTextView user_dropdown;
    private TaskAdapter taskAdapter;
    private List<TaskItem> taskList;
    private File logFile; // 日志文件路径

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
            createUtils();
            requireActivity().runOnUiThread(() -> {
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
                gt3GeetestUtils.init(gt3ConfigBean);
                geetestButton.setGeetestUtils(gt3GeetestUtils);
            });
        }

        @Override
        public void destroyButton() {
            requireActivity().runOnUiThread(() -> {
                // 销毁按钮
                if (geetestButton != null && geetestContainer != null) {
                    geetestContainer.removeView(geetestButton);
                    geetestButton = null;
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
            updateTaskStatus(taskName, 4);
        }

        @Override
        public void updateTaskStatusInProgress(String taskName) {
            updateTaskStatus(taskName, 1);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        Notification notification = new Notification(requireContext());
        user_dropdown = view.findViewById(R.id.user_dropdown);
        MaterialButton start_daily_btn = view.findViewById(R.id.start_daily);
        MaterialButton cancel_daily_btn = view.findViewById(R.id.cancel_daily);
        MaterialButton view_log_btn = view.findViewById(R.id.view_log_btn);
        RecyclerView tasksRecyclerView = view.findViewById(R.id.tasks_recycler_view);
        geetestContainer = view.findViewById(R.id.geetest_container);
        TextInputLayout user_dropdown_layout = view.findViewById(R.id.user_dropdown_layout);

        // 提示框
        CollapsibleCardView homeInfoCard = view.findViewById(R.id.home_info_card);
        homeInfoCard.setContent(R.layout.item_home_daily_info);

        // 初始化
        user_manager = new UserManager(requireContext());
        executorService = Executors.newSingleThreadExecutor();
        controller.createUtils();
        File logDir = new File(requireContext().getExternalFilesDir(null), "logs");
        if (!logDir.exists())
            logDir.mkdirs();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String date = dateFormat.format(new Date());
        logFile = new File(logDir, "daily_task_log_" + date + ".txt");
        // 初始化任务列表
        initializeTaskList();

        // 输出文本监听器
        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(message -> requireActivity().runOnUiThread(() -> {
            // 写入日志文件
            try {
                SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                String timestamp = timeFormat.format(new Date());
                String logEntry = "[" + timestamp + "] " + message + "\n";

                FileWriter writer = new FileWriter(logFile, true); // true 追加模式
                writer.append(logEntry);
                writer.close();
            } catch (IOException e) {
                show_error_dialog(requireContext(), "写入日志文件失败");
            }
        }));

        // 初始化下拉框
        List<String> usernames = user_manager.getUsernames();
        user_dropdown.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                usernames
        ));
        // 设置当前用户为默认选中项
        current_user = user_manager.getCurrentUser();
        if (!current_user.isEmpty() && usernames.contains(current_user)) {
            user_dropdown.setText(current_user, false);
            notifier.notifyListeners("当前用户" + current_user);
        } else if (!usernames.isEmpty()) {
            // 如果当前用户不存在或为空，设置为第一个用户
            current_user = usernames.get(0);
            user_dropdown.setText(current_user, false);
            notifier.notifyListeners("当前用户" + current_user);
        }
        user_dropdown.setOnItemClickListener((parent, view1, position, id) -> {
            current_user = (String) parent.getItemAtPosition(position);
            user_manager.setCurrentUser(current_user);
            notifier.notifyListeners("当前用户" + current_user);
        });

        // 设置任务列表RecyclerView
        tasksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        taskAdapter = new TaskAdapter(taskList);
        tasksRecyclerView.setAdapter(taskAdapter);

        // 启动任务按钮
        start_daily_btn.setOnClickListener(v -> {
            if (current_user.isEmpty()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("未选择用户")
                        .setMessage("请先从下拉列表中选择一个用户再开始任务")
                        .setPositiveButton("确定", null)
                        .show();
                return;
            }

            if (homeInfoCard.isExpanded())
                homeInfoCard.toggle();

            // 检查通知设置
            SharedPreferences prefs = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
            boolean notificationEnabled = prefs.getBoolean(NOTIFICATION, false);

            // 如果用户开启了通知功能，但系统通知权限未开启，则发送引导通知
            if (notificationEnabled && notification.areNotificationsDisabled()) {
                // 实际权限未开启，提示用户去设置
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("需要通知权限")
                        .setMessage("您已开启通知功能，但系统通知权限尚未开启。是否前往系统设置页面开启权限？")
                        .setPositiveButton("去设置", (dialog, which) -> notification.goToNotificationSettings())
                        .setNegativeButton("稍后再说", (dialog, which) -> {
                            // 用户选择稍后处理，将开关状态设为关闭并保存
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean(NOTIFICATION, false);
                            editor.apply();
                        }).setOnCancelListener(dialog -> {
                            // 用户取消对话框，也将开关状态设为关闭并保存
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean(NOTIFICATION, false);
                            editor.apply();
                        }).show();
                return;
            }
            notification.sendNormalNotification("Venus", "开始任务");

            // 清空上一次的日志
            try {
                FileWriter writer = new FileWriter(logFile, false); // false 覆盖模式
                writer.write("");
                writer.close();
            } catch (IOException e) {
                show_error_dialog(requireContext(), "清空日志文件失败");
            }
            // 刷新任务列表状态
            resetTaskList();

            user_dropdown_layout.setVisibility(View.GONE);// 隐藏用户下拉框
            cancel_daily_btn.setVisibility(View.VISIBLE); // 显示取消按钮
            start_daily_btn.setVisibility(View.GONE); // 隐藏启动按钮
            view_log_btn.setVisibility(View.VISIBLE); // 显示查看日志按钮

            currentTaskFuture = executorService.submit(() -> {
                try {
                    Map<String, Object> settings = get_settings();
                    if (Objects.equals(settings.get(DAILY), false) && Objects.equals(settings.get(GAME_DAILY), false) && !sklandEnabled()) {
                        requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "请先去设置里设置任务"));
                        return;
                    }
                    // 米游币签到任务
                    if (Objects.equals(settings.get(DAILY), true)) {
                        updateTaskStatus("米游币签到", 1); // 开始执行，无错误
                        BBSDaily b = new BBSDaily(requireActivity(), user_manager.getCurrentUser(), notifier, controller);
                        String[] daily = (String[]) settings.get("daily");
                        if (daily != null && daily.length > 0) {
                            try {
                                b.runTask(daily);
                                // 如果没有错误才标记为完成
                                if (!isTaskCancelled() && !Thread.currentThread().isInterrupted())
                                    updateTaskStatus("米游币签到", 2); // 完成，无错误
                            } catch (Exception e) {
                                // 发生异常时标记为有错误
                                updateTaskStatus("米游币签到", 3); // 有错误
                                requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "米游币签到失败：" + e.getMessage()));
                            }
                        } else {
                            updateTaskStatus("米游币签到", 3); // 有错误
                            requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "米游币签到失败，请先去设置里设置勾选至少一个获取米游币的板块"));
                        }
                    }
                    if (isTaskCancelled()) return; // 检查线程中断状态
                    // 米游社游戏签到任务开始
                    if (Objects.equals(settings.get(GAME_DAILY), true)) {
                        String[] game = (String[]) settings.get("game_daily");
                        if (game != null && game.length > 0) {
                            for (String game_name : game) {
                                if (isTaskCancelled()) return; // 检查线程中断状态
                                updateTaskStatus(game_name + "签到", 1); // 开始执行，无错误
                                try {
                                    BBSGameDaily game_module = new BBSGameDaily(requireActivity(), user_manager.getCurrentUser(), game_name, notifier, controller);
                                    notifier.notifyListeners("正在进行" + game_name + "签到");
                                    game_module.run();
                                    notification.sendNormalNotification(game_name, game_name + "签到完成");
                                    // 成功完成
                                    updateTaskStatus(game_name + "签到", 2); // 完成，无错误
                                } catch (Exception e) {
                                    // 检查是否是因为任务被取消导致的异常，如果任务已被取消，则不显示错误消息
                                    if (isTaskCancelled()) return;
                                    updateTaskStatus(game_name + "签到", 3); // 有错误
                                    String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                                    notification.sendErrorNotification(game_name + "签到失败", error_message);
                                    requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), error_message));
                                }
                            }
                            notifier.notifyListeners("游戏签到完成");
                        } else {
                            updateTaskStatus("游戏签到", 3); // 有错误
                            requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "游戏签到失败，请先去设置里设置勾选获取至少一个游戏进行签到"));
                        }
                    }

                    if (isTaskCancelled()) return; // 检查线程中断状态
                    // 森空岛任务
                    if (sklandEnabled()) {
                        updateTaskStatus("森空岛签到", 1);
                        try {
                            SklandDaily skland = new SklandDaily(requireActivity(), notifier);
                            skland.run();
                            updateTaskStatus("森空岛签到", 2);
                        } catch (Exception e) {
                            // 检查是否是因为任务被取消导致的异常，如果任务已被取消，则不显示错误消息
                            if (isTaskCancelled()) return;
                            requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "森空岛签到失败：" + e.getMessage()));
                            updateTaskStatus("森空岛签到", 3);
                            notifier.notifyListeners("任务出现错误");
                        }
                    }
                    notifier.notifyListeners("任务完成");
                    notification.sendNormalNotification("Venus", "任务完成");
                } finally {
                    requireActivity().runOnUiThread(() -> {
                        controller.destroyButton(); // 销毁验证码按钮
                        controller.createUtils(); // 重新创建验证码工具
                        // 恢复按钮状态
                        user_dropdown_layout.setVisibility(View.VISIBLE); // 恢复下拉框
                        cancel_daily_btn.setVisibility(View.GONE); // 隐藏取消按钮
                        start_daily_btn.setVisibility(View.VISIBLE); // 恢复启动按钮
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
                    notification.sendNormalNotification("Venus", "任务已取消");
                })
                .setNegativeButton("继续任务", null)
                .show());

        // 查看日志按钮
        view_log_btn.setOnClickListener(v -> {
            try {
                if (logFile.exists() && logFile.length() > 0) {
                    StringBuilder content = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new FileReader(logFile));
                    String line;
                    while ((line = reader.readLine()) != null)
                        content.append(line).append("\n");
                    reader.close();

                    // 显示日志内容对话框
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("日志内容")
                            .setMessage(content.toString())
                            .setPositiveButton("确定", null)
                            .setNegativeButton("清空日志", (dialog, which) -> {
                                try {
                                    FileWriter writer = new FileWriter(logFile, false); // false表示覆盖模式
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
     * 初始化任务列表
     */
    private void initializeTaskList() {
        taskList = new ArrayList<>();
        // 根据设置初始化任务列表
        Map<String, Object> settings = get_settings();
        if (Objects.equals(settings.get(DAILY), true))
            taskList.add(new TaskItem("米游币签到", false));
        String[] game_daily = (String[]) settings.get("game_daily");
        if (Objects.equals(settings.get(GAME_DAILY), true) && game_daily != null)
            for (String gameName : game_daily)
                taskList.add(new TaskItem(gameName + "签到", false));
        if (sklandEnabled())
            taskList.add(new TaskItem("森空岛签到", false));
    }

    private boolean sklandEnabled() {
        return requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(SKLAND_ENABLED, false);
    }

    /**
     * 重置任务列表状态
     */
    @SuppressLint("NotifyDataSetChanged")
    private void resetTaskList() {
        // 重置所有任务状态为未开始未完成
        for (TaskItem task : taskList) {
            task.setCompleted(false);
            task.setInProgress(false);
        }
        // 根据当前设置更新任务列表
        Map<String, Object> settings = get_settings();
        List<TaskItem> newTaskList = new ArrayList<>();
        if (Objects.equals(settings.get(DAILY), true))
            newTaskList.add(new TaskItem("米游币签到", false));
        String[] game_daily = (String[]) settings.get("game_daily");
        if (Objects.equals(settings.get(GAME_DAILY), true) && game_daily != null)
            for (String gameName : game_daily)
                newTaskList.add(new TaskItem(gameName + "签到", false));
        if (sklandEnabled())
            newTaskList.add(new TaskItem("森空岛签到", false));
        taskList.clear();
        taskList.addAll(newTaskList);
        if (taskAdapter != null) taskAdapter.notifyDataSetChanged();
    }

    /**
     * 更新特定任务的状态
     *
     * @param taskName 任务名称
     * @param status   任务状态，1表示正在执行中，2表示已完成，3表示有错误，4表示有警告
     */
    private void updateTaskStatus(String taskName, int status) {
        requireActivity().runOnUiThread(() -> {
            if (taskAdapter != null && taskList != null) {
                for (int i = 0; i < taskList.size(); i++) {
                    if (taskList.get(i).getName().equals(taskName)) {
                        taskList.get(i).setInProgress(false);
                        taskList.get(i).setError(false);
                        taskList.get(i).setWarning(false);
                        taskList.get(i).setCompleted(false);
                        switch (status) {
                            case 1:
                                taskList.get(i).setInProgress(true);
                                break;
                            case 2:
                                taskList.get(i).setCompleted(true);
                                break;
                            case 3:
                                taskList.get(i).setError(true);
                                break;
                            case 4:
                                taskList.get(i).setWarning(true);
                                break;
                        }
                        taskAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 更新下拉框中的用户列表
        if (user_manager != null && user_dropdown != null) {
            current_user = user_manager.getCurrentUser();
            List<String> usernames = user_manager.getUsernames();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    usernames
            );
            user_dropdown.setAdapter(adapter);

            // 恢复之前选中的用户（如果仍然存在于列表中）
            if (!current_user.isEmpty() && usernames.contains(current_user))
                user_dropdown.setText(current_user, false);
            else if (!usernames.isEmpty())
                // 如果之前选中的用户不存在了，设置为第一个用户
                user_dropdown.setText(usernames.get(0), false);
        }

        if (!isTaskRunning()) {
            // 更新任务列表（仅在任务配置发生变化时）
            if (taskList == null) {
                initializeTaskList();
                return;
            }
            // 获取当前设置的任务列表
            Map<String, Object> settings = get_settings();
            List<String> newTasks = new ArrayList<>();
            if (Objects.equals(settings.get(DAILY), true))
                newTasks.add("米游币签到");
            String[] game_daily = (String[]) settings.get("game_daily");
            if (Objects.equals(settings.get(GAME_DAILY), true) && game_daily != null)
                for (String gameName : game_daily)
                    newTasks.add(gameName + "签到");
            if (Objects.equals(sklandEnabled(), true))
                newTasks.add("森空岛签到");
            // 检查是否有变化
            boolean hasChanges = false;
            if (taskList.size() != newTasks.size())
                hasChanges = true;
            else
                for (int i = 0; i < taskList.size(); i++)
                    if (!taskList.get(i).getName().equals(newTasks.get(i))) {
                        hasChanges = true;
                        break;
                    }
            if (hasChanges) resetTaskList();
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
     * 获取设置
     */
    private Map<String, Object> get_settings() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        boolean daily_switch = prefs.getBoolean(DAILY, true);
        boolean game_daily_switch = prefs.getBoolean(GAME_DAILY, true);
        boolean daily_genshin = prefs.getBoolean(DAILY_GENSHIN, false);
        boolean daily_zzz = prefs.getBoolean(DAILY_ZZZ, false);
        boolean daily_srg = prefs.getBoolean(DAILY_SRG, false);
        boolean daily_hr3 = prefs.getBoolean(DAILY_HR3, false);
        boolean daily_hr2 = prefs.getBoolean(DAILY_HR2, false);
        boolean daily_weiding = prefs.getBoolean(DAILY_WEIDING, false);
        boolean daily_dabieye = prefs.getBoolean(DAILY_DABIEYE, true);
        boolean daily_hna = prefs.getBoolean(DAILY_HNA, false);
        boolean game_daily_genshin = prefs.getBoolean(GAME_DAILY_GENSHIN, false);
        boolean game_daily_zzz = prefs.getBoolean(GAME_DAILY_ZZZ, false);
        boolean game_daily_srg = prefs.getBoolean(GAME_DAILY_SRG, false);
        boolean game_daily_hr3 = prefs.getBoolean(GAME_DAILY_HR3, false);
        boolean game_daily_hr2 = prefs.getBoolean(GAME_DAILY_HR2, false);
        boolean game_daily_weiding = prefs.getBoolean(GAME_DAILY_WEIDING, false);
        Map<String, Object> settings = new HashMap<>();
        settings.put(DAILY, daily_switch);
        settings.put(GAME_DAILY, game_daily_switch);
        ArrayList<String> daily = new ArrayList<>();
        if (daily_genshin) daily.add("原神");
        if (daily_zzz) daily.add("绝区零");
        if (daily_srg) daily.add("星铁");
        if (daily_hr3) daily.add("崩坏3");
        if (daily_hr2) daily.add("崩坏2");
        if (daily_weiding) daily.add("未定事件簿");
        if (daily_dabieye) daily.add("大别野");
        if (daily_hna) daily.add("崩坏因缘精灵");
        //转成String[]
        settings.put("daily", daily.toArray(new String[0]));
        ArrayList<String> game_daily = new ArrayList<>();
        if (game_daily_genshin) game_daily.add("原神");
        if (game_daily_zzz) game_daily.add("绝区零");
        if (game_daily_srg) game_daily.add("星铁");
        if (game_daily_hr3) game_daily.add("崩坏3");
        if (game_daily_hr2) game_daily.add("崩坏2");
        if (game_daily_weiding) game_daily.add("未定事件簿");
        settings.put("game_daily", game_daily.toArray(new String[0]));
        return settings;
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
