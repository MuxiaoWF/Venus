package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.tools.show_error_dialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.fragment.app.Fragment;

import com.geetest.sdk.GT3ConfigBean;
import com.geetest.sdk.GT3GeetestUtils;
import com.geetest.sdk.views.GT3GeetestButton;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.CollapsibleCardView;
import com.muxiao.Venus.common.Notification;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        Notification notification = new Notification(requireContext());
        user_dropdown = view.findViewById(R.id.user_dropdown);
        MaterialTextView status_text = view.findViewById(R.id.status_text);
        MaterialButton start_daily_btn = view.findViewById(R.id.start_daily);
        MaterialButton cancel_daily_btn = view.findViewById(R.id.cancel_daily);
        ScrollView daily_scroll_view = view.findViewById(R.id.daily_scroll_view);
        geetestContainer = view.findViewById(R.id.geetest_container);

        // 提示框
        CollapsibleCardView homeInfoCard = view.findViewById(R.id.home_info_card);
        homeInfoCard.setContent(inflater.inflate(R.layout.item_home_daily_info, homeInfoCard.getContentLayout(), false));

        // 初始化
        user_manager = new UserManager(requireContext());
        executorService = Executors.newSingleThreadExecutor();
        controller.createUtils();

        // 初始化下拉框
        List<String> usernames = user_manager.getUsernames();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                usernames
        );
        user_dropdown.setAdapter(adapter);
        // 设置当前用户为默认选中项
        current_user = user_manager.getCurrentUser();
        if (!current_user.isEmpty() && usernames.contains(current_user))
            user_dropdown.setText(current_user, false);
        else if (!usernames.isEmpty())
            // 如果当前用户不存在或为空，设置为第一个用户
            user_dropdown.setText(usernames.get(0), false);
        user_dropdown.setOnItemClickListener((parent, view1, position, id) -> {
            current_user = (String) parent.getItemAtPosition(position);
            user_manager.setCurrentUser(current_user);
            status_text.setText(new StringBuilder("已选择用户: " + current_user + "\n"));
        });

        // 输出文本监听器
        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(status -> requireActivity().runOnUiThread(() -> {
            status_text.append(status + "\n");
            // 滚动到底部
            daily_scroll_view.post(() -> daily_scroll_view.fullScroll(View.FOCUS_DOWN));
        }));
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
            SharedPreferences prefs = requireActivity().getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
            boolean notificationEnabled = prefs.getBoolean("notification_switch", false);

            // 如果用户开启了通知功能，但系统通知权限未开启，则发送引导通知
            if (notificationEnabled && !notification.areNotificationsEnabled()) {
                // 实际权限未开启，提示用户去设置
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("需要通知权限")
                        .setMessage("您已开启通知功能，但系统通知权限尚未开启。是否前往系统设置页面开启权限？")
                        .setPositiveButton("去设置", (dialog, which) -> notification.goToNotificationSettings())
                        .setNegativeButton("稍后再说", (dialog, which) -> {
                            // 用户选择稍后处理，将开关状态设为关闭并保存
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean("notification_switch", false);
                            editor.apply();
                        }).setOnCancelListener(dialog -> {
                            // 用户取消对话框，也将开关状态设为关闭并保存
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean("notification_switch", false);
                            editor.apply();
                        }).show();
                return;
            }
            notification.sendNormalNotification("Venus", "开始任务");
            // 在开始新任务前清除之前的输出
            status_text.setText(new StringBuilder("已选择用户: " + current_user + "\n"));

            cancel_daily_btn.setVisibility(View.VISIBLE); // 显示取消按钮
            start_daily_btn.setVisibility(View.GONE); // 隐藏启动按钮
            user_dropdown.setEnabled(false); // 禁用用户下拉菜单

            currentTaskFuture = executorService.submit(() -> {
                try {
                    Map<String, Object> settings = get_settings();
                    if (Objects.equals(settings.get("daily_switch"), false) && Objects.equals(settings.get("game_daily_switch"), false)) {
                        requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "请先去设置里设置任务"));
                        return;
                    }
                    // 判断是否运行米游币签到任务
                    if (Objects.equals(settings.get("daily_switch"), true)) {
                        BBSDaily b = new BBSDaily(requireActivity(), user_manager.getCurrentUser(), notifier, controller);
                        String[] daily = (String[]) settings.get("daily");
                        if (daily != null && daily.length > 0)
                            b.runTask(daily, new boolean[]{true, true, true, true});
                        else
                            requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "米游币签到失败，请先去设置里设置勾选至少一个获取米游币的板块"));
                    }
                    if (isTaskCancelled()) return; // 检查线程中断状态
                    // 判断是否运行游戏签到任务
                    if (Objects.equals(settings.get("game_daily_switch"), true)) {
                        String[] game = (String[]) settings.get("game_daily");
                        if (game != null && game.length > 0) {
                            for (String game_name : game) {
                                if (isTaskCancelled()) return; // 检查线程中断状态
                                BBSGameDaily game_module = new BBSGameDaily(requireActivity(), user_manager.getCurrentUser(), game_name, notifier, controller);
                                notifier.notifyListeners("正在进行" + game_name + "签到");
                                game_module.signAccount();
                                notification.sendNormalNotification(game_name, game_name + "签到完成");
                            }
                            notifier.notifyListeners("游戏签到完成");
                        } else
                            requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), "游戏签到失败，请先去设置里设置勾选获取至少一个游戏进行签到"));
                    }
                    notifier.notifyListeners("任务完成");
                    notification.sendNormalNotification("Venus", "任务完成");
                } catch (InterruptedException e) {
                    // 任务被中断，检查是否真的是被取消
                    if (currentTaskFuture != null && currentTaskFuture.isCancelled()) {
                        Thread.currentThread().interrupt(); // 重新设置中断状态
                        return; // 任务已被取消，直接返回
                    }
                    // 如果不是被取消，继续处理异常
                    String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                    notification.sendErrorNotification("任务中断", error_message);
                    requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), error_message));
                } catch (Exception e) {
                    if (isTaskCancelled()) return; // 如果是取消导致的异常，忽略
                    String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                    notification.sendErrorNotification("任务失败", error_message);
                    requireActivity().runOnUiThread(() -> show_error_dialog(requireContext(), error_message));
                } finally {
                    requireActivity().runOnUiThread(() -> {
                        controller.destroyButton(); // 销毁验证码按钮
                        controller.createUtils(); // 创建验证码工具
                        // 恢复按钮状态
                        cancel_daily_btn.setVisibility(View.GONE);
                        start_daily_btn.setVisibility(View.VISIBLE);
                        // 恢复下拉框
                        user_dropdown.setEnabled(true);
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
                    status_text.append("任务已取消\n");
                    notification.sendNormalNotification("Venus", "任务已取消");
                })
                .setNegativeButton("继续任务", null)
                .show());

        return view;
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
            if (!current_user.isEmpty() && usernames.contains(current_user)) {
                user_dropdown.setText(current_user, false);
            } else if (!usernames.isEmpty()) {
                // 如果之前选中的用户不存在了，设置为第一个用户
                user_dropdown.setText(usernames.get(0), false);
            }
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
        SharedPreferences prefs = requireActivity().getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        boolean daily_switch = prefs.getBoolean("daily_switch_button", true);
        boolean game_daily_switch = prefs.getBoolean("game_daily_switch_button", true);
        boolean daily_genshin = prefs.getBoolean("daily_checkbox_genshin", false);
        boolean daily_zzz = prefs.getBoolean("daily_checkbox_zzz", false);
        boolean daily_srg = prefs.getBoolean("daily_checkbox_srg", false);
        boolean daily_hr3 = prefs.getBoolean("daily_checkbox_hr3", false);
        boolean daily_hr2 = prefs.getBoolean("daily_checkbox_hr2", false);
        boolean daily_weiding = prefs.getBoolean("daily_checkbox_weiding", false);
        boolean daily_dabieye = prefs.getBoolean("daily_checkbox_dabieye", true);
        boolean daily_hna = prefs.getBoolean("daily_checkbox_hna", false);
        boolean game_daily_genshin = prefs.getBoolean("game_daily_checkbox_genshin", false);
        boolean game_daily_zzz = prefs.getBoolean("game_daily_checkbox_zzz", false);
        boolean game_daily_srg = prefs.getBoolean("game_daily_checkbox_srg", false);
        boolean game_daily_hr3 = prefs.getBoolean("game_daily_checkbox_hr3", false);
        boolean game_daily_hr2 = prefs.getBoolean("game_daily_checkbox_hr2", false);
        boolean game_daily_weiding = prefs.getBoolean("game_daily_checkbox_weiding", false);
        Map<String, Object> settings = new HashMap<>();
        settings.put("daily_switch", daily_switch);
        settings.put("game_daily_switch", game_daily_switch);
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
