package com.muxiao.Venus.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.muxiao.Venus.Home.DailyTaskWorker;
import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.LocaleHelper;

/** 接收小组件「运行」按钮的广播，启动前台任务服务。 */
public class TaskWidgetReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN_TASKS = "com.muxiao.Venus.widget.RUN_TASKS";

    /**
     * 解析小组件的目标用户（运行与展示共用）：优先 App 内当前选中用户（UserManager，
     * 跟随首页下拉切换），未选择时兜底「最后一次运行的用户」（TaskStatusManager.current_user）。
     */
    /**
     * 解析本轮任务的目标账号：优先当前选中，兜底「最后一次运行任务的用户」。
     *
     * <p>兜底来源（{@code TaskStatusManager.current_user}）不会被 {@code removeUser} 清理，可能指向已删除的账号；
     * 直接用它跑任务会给不存在的账号建孤儿任务配置（{@code task_profile_*}）与状态桶。故统一在这里判存在性，
     * 删号后回落到空串，由各入口按「无账号」处理。
     */
    public static String resolveTargetUser(Context context) {
        UserManager userManager = new UserManager(context);
        String userId = userManager.getCurrentUser();
        if (userId == null || userId.isEmpty())
            userId = new TaskStatusManager(context).getCurrentUser();
        if (userId == null || userId.isEmpty() || !userManager.isUserExists(userId)) return "";
        return userId;
    }

    /** 批量运行的目标账号：全部账号（含跨服），当前账号排最前。 */
    public static java.util.List<String> resolveTargetUsers(Context context) {
        UserManager userManager = new UserManager(context);
        java.util.List<String> all = userManager.getAllUsernames();
        java.util.List<String> ordered = new java.util.ArrayList<>(all.size());
        String current = resolveTargetUser(context);
        if (!current.isEmpty() && all.contains(current)) ordered.add(current);
        for (String userId : all) {
            if (!userId.equals(current)) ordered.add(userId);
        }
        return ordered;
    }

    @Override
    // 拦截 RUN_TASKS 广播：任务未在运行、任务已配置且能确定用户时，启动前台任务服务执行签到
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_RUN_TASKS.equals(intent.getAction())) return;
        // 并发防护：后台服务在跑时 isRunning() 为真；但 App 内「前台任务」不走 Service，
        // 必须一并检查 HomeFragment.isTaskRunning()，否则前台运行中点小组件会双重并发执行；
        // 定时签到执行器同理（无 Service 宿主），三者互斥
        if (ForegroundTaskService.isRunning() || HomeFragment.isTaskRunning()
                || DailyTaskWorker.isRunning()) return;

        Context localized = LocaleHelper.wrap(context);
        // 后台任务开关校验：小组件触发与首页「后台运行」走同一条 Service 链路，
        // 开关关闭时一并拦下，保证「关闭后台任务 = 停止所有 Service 自动化」口径一致
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.Prefs.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(Constants.Prefs.BACKGROUND_TASK_ENABLED, false)) {
            Toast.makeText(context, localized.getString(R.string.widget_bg_disabled), Toast.LENGTH_SHORT).show();
            return;
        }
        // 先定目标账号再校验任务
        // 小组件按钮语义 = 跑一遍所有账号
        java.util.List<String> allUsers = resolveTargetUsers(context);
        if (allUsers.isEmpty()) {
            Toast.makeText(context, localized.getString(R.string.widget_no_user), Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> users = com.muxiao.Venus.Home.MultiUserRunner.filterRunnable(context, allUsers);
        if (users.isEmpty()) {
            // 有账号但都没配任务
            Toast.makeText(context, localized.getString(R.string.err_set_task_first), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(context, ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_IDS, users.toArray(new String[0]));
        // 单账号时同时写兼容字段
        if (users.size() == 1) serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, users.get(0));
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
