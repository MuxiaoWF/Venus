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
import com.muxiao.Venus.common.TaskSettings;

/**
 * 接收小组件"运行"按钮的广播，启动前台任务服务。
 */
public class TaskWidgetReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN_TASKS = "com.muxiao.Venus.widget.RUN_TASKS";

    /**
     * 解析小组件的目标用户（运行与展示共用）：优先取 App 内当前选中的用户
     * （UserManager，跟随首页下拉切换），未选择时兜底「最后一次运行的用户」
     * （TaskStatusManager.current_user）。
     */
    public static String resolveTargetUser(Context context) {
        String userId = new UserManager(context).getCurrentUser();
        if (userId == null || userId.isEmpty())
            userId = new TaskStatusManager(context).getCurrentUser();
        return userId != null ? userId : "";
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
        // 任务配置校验：与 App 内两条入口的 hasAnyTaskDisabled 检查对齐，避免全部任务关闭时空跑一轮
        if (TaskSettings.fromPreferences(context).hasAnyTaskDisabled()) {
            Toast.makeText(context, localized.getString(R.string.err_set_task_first), Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = resolveTargetUser(context);
        if (userId.isEmpty()) {
            Toast.makeText(context, localized.getString(R.string.widget_no_user), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(context, ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, userId);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
