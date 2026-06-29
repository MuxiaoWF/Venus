package com.muxiao.Venus.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.R;
import com.muxiao.Venus.User.UserManager;

/**
 * 接收小组件"运行"按钮的广播，启动前台任务服务。
 */
public class TaskWidgetReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN_TASKS = "com.muxiao.Venus.widget.RUN_TASKS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_RUN_TASKS.equals(intent.getAction())) return;
        if (ForegroundTaskService.isRunning()) return;

        // 优先从 TaskStatusManager 读取，fallback 到 UserManager
        String userId = new TaskStatusManager(context).getCurrentUser();
        if (userId == null || userId.isEmpty()) {
            userId = new UserManager(context).getCurrentUser();
        }
        if (userId == null || userId.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.widget_no_user), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(context, ForegroundTaskService.class);
        serviceIntent.setAction(ForegroundTaskService.ACTION_START_TASK);
        serviceIntent.putExtra(ForegroundTaskService.EXTRA_USER_ID, userId);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
