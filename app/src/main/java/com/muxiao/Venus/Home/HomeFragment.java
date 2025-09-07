package com.muxiao.Venus.Home;

import static com.muxiao.Venus.common.fixed.Genshin_act_id;
import static com.muxiao.Venus.common.fixed.Honkai2_act_id;
import static com.muxiao.Venus.common.fixed.Honkai3rd_act_id;
import static com.muxiao.Venus.common.fixed.HonkaiStarRail_act_id;
import static com.muxiao.Venus.common.fixed.TearsOfThemis_act_id;
import static com.muxiao.Venus.common.fixed.ZZZ_act_id;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
import com.muxiao.Venus.common.fixed;
import com.muxiao.Venus.common.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class HomeFragment extends Fragment {
    private MaterialAutoCompleteTextView user_dropdown;
    private UserManager userManager;
    private MaterialTextView status_text;
    private ScrollView daily_scroll_view;
    private ExecutorService executorService;
    public GT3GeetestButton geetestButton;
    public GT3GeetestUtils gt3GeetestUtils;
    private boolean isTaskRunning = false; // 添加任务运行状态标志
    private Future<?> currentTaskFuture; // 用于取消任务
    private final AtomicBoolean isTaskCancelled = new AtomicBoolean(false); // 任务取消标志

    private View contentLayout;
    private boolean isExpanded = true;

    // 定义一个接口
    public interface GT3ButtonController {
        void init(GT3ConfigBean gt3ConfigBean);

        GT3GeetestUtils getGeetestUtils();

        void cleanUtils();

        void Gone();

        void Visible();
    }

    private final GT3ButtonController controller = new GT3ButtonController() {
        @Override
        public void init(GT3ConfigBean gt3ConfigBean) {
            requireActivity().runOnUiThread(() -> {
                // 确保清理之前的实例
                if (gt3GeetestUtils != null) {
                    gt3GeetestUtils.destory();
                    gt3GeetestUtils = null;
                }
                
                gt3GeetestUtils = new GT3GeetestUtils(requireActivity());
                gt3GeetestUtils.init(gt3ConfigBean);
                geetestButton.setGeetestUtils(gt3GeetestUtils);
                geetestButton.setVisibility(View.VISIBLE);
                geetestButton.setClickable(true); // 确保按钮可点击
                geetestButton.setEnabled(true); // 确保按钮启用
            });
        }

        @Override
        public void Gone() {
            requireActivity().runOnUiThread(() -> {
                geetestButton.setVisibility(View.GONE);
                geetestButton.setClickable(false); // 设置为不可点击
            });
        }

        @Override
        public void Visible() {
            requireActivity().runOnUiThread(() -> {
                geetestButton.setVisibility(View.VISIBLE);
                geetestButton.setClickable(true); // 确保按钮可点击
                geetestButton.setEnabled(true); // 确保按钮启用
            });
        }

        @Override
        public void cleanUtils() {
            requireActivity().runOnUiThread(() -> {
                if (gt3GeetestUtils != null) {
                    gt3GeetestUtils.destory(); // 正确销毁
                    gt3GeetestUtils = null;
                }
                // 重置按钮状态
                if (geetestButton != null) {
                    geetestButton.setVisibility(View.GONE);
                    geetestButton.setClickable(false);
                }
            });
        }

        @Override
        public GT3GeetestUtils getGeetestUtils() {
            return gt3GeetestUtils;
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        user_dropdown = view.findViewById(R.id.user_dropdown);
        status_text = view.findViewById(R.id.status_text);
        MaterialButton start_daily_btn = view.findViewById(R.id.start_daily);
        MaterialButton cancel_daily_btn = view.findViewById(R.id.cancel_daily); // 取消按钮
        daily_scroll_view = view.findViewById(R.id.daily_scroll_view);
        geetestButton = view.findViewById(R.id.btn_geetest);

        // 初始化折叠功能相关视图
        MaterialButton toggleButton = view.findViewById(R.id.toggle_button);
        contentLayout = view.findViewById(R.id.content_layout);

        userManager = new UserManager(requireContext());
        executorService = Executors.newFixedThreadPool(3);

        // 确保GT按钮初始状态正确
        geetestButton.setVisibility(View.GONE);
        geetestButton.setClickable(false);

        // 初始化下拉框
        updateDropdown();

        // 设置下拉框选择事件
        user_dropdown.setOnItemClickListener((parent, view1, position, id) -> {
            String selectedUser = (String) parent.getItemAtPosition(position);
            userManager.setCurrentUser(selectedUser);
            status_text.setText(new StringBuilder("已选择用户: " + selectedUser + "\n"));
        });

        tools.StatusNotifier notifier = new tools.StatusNotifier();
        notifier.addListener(status -> requireActivity().runOnUiThread(() -> {
            status_text.append(status + "\n");
            // 滚动到底部
            daily_scroll_view.post(() -> daily_scroll_view.fullScroll(View.FOCUS_DOWN));
        }));
        
        // 启动任务按钮
        start_daily_btn.setOnClickListener(v -> {
            // 检查任务是否已经在运行
            if (isTaskRunning) return; // 如果任务正在运行，则不执行任何操作
            
            isTaskRunning = true; // 设置任务为运行状态
            isTaskCancelled.set(false); // 重置取消标志
            cancel_daily_btn.setVisibility(View.VISIBLE); // 显示取消按钮
            start_daily_btn.setVisibility(View.GONE); // 隐藏启动按钮
            
            // 在每次新任务开始前清理之前的GT实例
            controller.cleanUtils();

            currentTaskFuture = executorService.submit(() -> {
                isExpanded = false;
                requireActivity().runOnUiThread(() -> contentLayout.setVisibility(View.GONE));
                try {
                    Map<String, Object> settings = get_settings();
                    if (isTaskCancelled.get()) return; // 检查是否已取消
                    
                    if (!Objects.equals(settings.get("daily_switch"), true) || !Objects.equals(settings.get("game_daily_switch"), true)) {
                        requireActivity().runOnUiThread(() -> show_error_dialog("请先去设置里设置任务"));
                        return; // 添加return以避免继续执行
                    }
                    if (isTaskCancelled.get()) return; // 检查是否已取消
                    
                    if (Objects.equals(settings.get("daily_switch"), true)) {
                        Daily b = new Daily(requireActivity(), userManager.getCurrentUser(), notifier, controller);
                        String[] daily = (String[]) settings.get("daily");
                        if (daily != null && daily.length > 0) {
                            b.initBbsTask(daily);
                            b.runTask(true, true, true, true);
                        } else
                            requireActivity().runOnUiThread(() -> show_error_dialog("米游币签到失败，请先去设置里设置勾选获取米游币板块"));
                    }
                    if (isTaskCancelled.get()) return; // 检查是否已取消
                    
                    if (Objects.equals(settings.get("game_daily_switch"), true)) {
                        String[] game = (String[]) settings.get("game_daily");
                        if (game != null && game.length > 0)
                            run_BBSGame_task(userManager.getCurrentUser(), game, notifier, controller);
                        else
                            requireActivity().runOnUiThread(() -> show_error_dialog("游戏签到失败，请先去设置里设置勾选获取游戏签到板块"));
                    }
                    if (isTaskCancelled.get()) return; // 检查是否已取消
                    
                    notifier.notifyListeners("任务完成");
                } catch (Exception e) {
                    if (isTaskCancelled.get()) return; // 如果是取消导致的异常，忽略
                    String error_message = e.getMessage() != null ? e.getMessage() : e.toString();
                    requireActivity().runOnUiThread(() -> show_error_dialog(error_message));
                } finally {
                    // 任务完成后重置运行状态
                    isTaskRunning = false;
                    isTaskCancelled.set(false);
                    // 确保GT按钮在任务完成后恢复可用状态
                    requireActivity().runOnUiThread(() -> {
                        if (geetestButton != null) {
                            geetestButton.setClickable(true);
                            geetestButton.setEnabled(true);
                        }
                        // 恢复按钮状态
                        cancel_daily_btn.setVisibility(View.GONE);
                        start_daily_btn.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        // 取消任务按钮
        cancel_daily_btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("确认取消")
                .setMessage("确定要取消当前任务吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    isTaskCancelled.set(true); // 设置取消标志
                    if (currentTaskFuture != null)
                        currentTaskFuture.cancel(true); // 尝试取消任务
                    status_text.append("任务已取消\n");
                    // 恢复按钮状态
                    cancel_daily_btn.setVisibility(View.GONE);
                    start_daily_btn.setVisibility(View.VISIBLE);
                    isTaskRunning = false;
                    // 清理GT相关资源
                    controller.cleanUtils();
                })
                .setNegativeButton("继续任务", null)
                .show());
        
        // 设置折叠按钮点击事件
        toggleButton.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            if (isExpanded)
                contentLayout.setVisibility(View.VISIBLE);
            else
                contentLayout.setVisibility(View.GONE);
        });

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        // 清理GT相关资源
        controller.cleanUtils();

        // 确保销毁GT3GeetestUtils
        if (gt3GeetestUtils != null) {
            gt3GeetestUtils.destory();
            gt3GeetestUtils = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次恢复Fragment时更新下拉框
        updateDropdown();
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

    private void updateDropdown() {
        List<String> usernames = userManager.getUsernames();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                usernames
        );
        user_dropdown.setAdapter(adapter);

        // 设置当前用户为默认选中项
        String currentUser = userManager.getCurrentUser();
        if (!currentUser.isEmpty() && usernames.contains(currentUser)) {
            user_dropdown.setText(currentUser, false);
            status_text.setText(new StringBuilder("已选择用户: " + currentUser + "\n"));
        }
    }

    /**
     * 显示错误信息并提供复制
     *
     * @param error_message 错误信息
     */
    private void show_error_dialog(String error_message) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("错误")
                .setMessage(error_message)
                .setPositiveButton("复制错误信息", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("错误信息", error_message);
                    clipboard.setPrimaryClip(clip);
                    requireActivity().runOnUiThread(() ->
                            android.widget.Toast.makeText(requireContext(), "错误信息已复制到剪切板", android.widget.Toast.LENGTH_SHORT).show()
                    );
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 运行米游社游戏签到任务
     *
     * @param game           游戏名（崩坏2，崩坏3，未定事件簿，原神，星铁，绝区零）
     * @param statusNotifier 状态通知器
     */
    public void run_BBSGame_task(String userID, String[] game, tools.StatusNotifier statusNotifier, GT3ButtonController gt3Controller) {
        List<Map<String, Object>> list = new ArrayList<>(Arrays.asList(new HashMap<>() {{
            put("game_print_name", "崩坏2");
            put("game_name", "honkai2");
        }}, new HashMap<>() {{
            put("game_print_name", "崩坏3");
            put("game_name", "honkai3rd");
        }}, new HashMap<>() {{
            put("game_print_name", "未定事件簿");
            put("game_name", "tears_of_themis");
        }}, new HashMap<>() {{
            put("game_print_name", "原神");
            put("game_name", "genshin");
        }}, new HashMap<>() {{
            put("game_print_name", "星铁");
            put("game_name", "honkai_sr");
        }}, new HashMap<>() {{
            put("game_print_name", "绝区零");
            put("game_name", "zzz");
        }}));
        for (Map<String, Object> map : list) {
            String game_print_name = (String) map.get("game_print_name");
            String game_name = (String) map.get("game_name");
            for (String s : game) {
                if (s.equals(game_print_name)) {
                    BBSGameDaily game_module = null;
                    if (game_name != null) {
                        switch (game_name) {
                            case "honkai2":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("崩坏2"), "崩坏学园2", Honkai2_act_id, "舰长", statusNotifier, gt3Controller);
                                game_module.init();
                                break;
                            case "honkai3rd":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("崩坏3"), "崩坏3", Honkai3rd_act_id, "舰长", statusNotifier, gt3Controller);
                                game_module.init();
                                break;
                            case "tears_of_themis":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("未定事件簿"), "未定事件簿", TearsOfThemis_act_id, "律师", statusNotifier, gt3Controller);
                                game_module.init();
                                break;
                            case "genshin":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("原神"), "原神", Genshin_act_id, "旅行者", statusNotifier, gt3Controller);
                                game_module.init();
                                break;
                            case "honkai_sr":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("星铁"), "崩坏: 星穹铁道", HonkaiStarRail_act_id, "开拓者", statusNotifier, gt3Controller);
                                game_module.init();
                                break;
                            case "zzz":
                                game_module = new BBSGameDaily(requireActivity(), userID, fixed.name_to_game_id("绝区零"), "绝区零", ZZZ_act_id, "绳匠", statusNotifier, gt3Controller);
                                game_module.rewards_api = "https://act-nap-api.mihoyo.com/event/luna/zzz/home";
                                game_module.is_sign_api = "https://act-nap-api.mihoyo.com/event/luna/zzz/info";
                                game_module.sign_api = "https://act-nap-api.mihoyo.com/event/luna/zzz/sign";
                                game_module.init();
                                break;
                        }
                    }
                    statusNotifier.notifyListeners("正在进行" + game_print_name + "签到");
                    if (game_module != null) {
                        game_module.signAccount();
                    }
                    try {
                        Thread.sleep(new Random().nextInt(10 - 5 + 1) + 2 * 1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Thread系统错误" + e);
                    }
                }
            }
        }
        statusNotifier.notifyListeners("游戏签到完成");
    }

}
