package com.muxiao.Venus.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.TaskSettings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 任务状态桌面小组件。
 * 显示今日各任务的完成情况，支持运行任务、点击打开App和手动刷新。
 */
public class TaskWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.muxiao.Venus.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refreshAllWidgets(context);
        }
    }

    @SuppressWarnings("deprecation")
    static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_task_status);

        // 标题栏点击 → 打开 App
        Intent launchIntent = new Intent(context, MainActivity.class);
        PendingIntent launchPi = PendingIntent.getActivity(context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_header, launchPi);

        // 运行按钮 → 启动任务（运行中显示加载状态）
        boolean isRunning = ForegroundTaskService.isRunning();
        Intent runIntent = new Intent(context, TaskWidgetReceiver.class);
        runIntent.setAction(TaskWidgetReceiver.ACTION_RUN_TASKS);
        PendingIntent runPi = PendingIntent.getBroadcast(context, 0, runIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_run, runPi);
        if (isRunning) {
            views.setTextViewText(R.id.btn_run, context.getString(R.string.widget_running));
            views.setBoolean(R.id.btn_run, "setEnabled", false);
        } else {
            views.setTextViewText(R.id.btn_run, context.getString(R.string.widget_run));
            views.setBoolean(R.id.btn_run, "setEnabled", true);
        }

        // 刷新按钮 → 刷新小组件
        Intent refreshIntent = new Intent(context, TaskWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        PendingIntent refreshPi = PendingIntent.getBroadcast(context, 1, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshPi);

        // 任务列表
        Intent serviceIntent = new Intent(context, TaskWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        views.setRemoteAdapter(R.id.widget_task_list, serviceIntent);

        // 列表项点击模板 → 打开 App
        Intent itemClickIntent = new Intent(context, MainActivity.class);
        PendingIntent itemClickPi = PendingIntent.getActivity(context, 2, itemClickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setPendingIntentTemplate(R.id.widget_task_list, itemClickPi);

        // 底部统计信息
        TaskSettings settings = TaskSettings.fromPreferences(context);
        TaskStatusManager statusManager = new TaskStatusManager(context);
        String[] taskNames = settings.getTaskNames(context).toArray(new String[0]);
        int completed = statusManager.getCompletedCount(taskNames);
        int total = taskNames.length;

        views.setTextViewText(R.id.widget_summary,
                context.getString(R.string.widget_task_summary, completed, total));
        views.setTextViewText(R.id.widget_date,
                new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date()));

        manager.updateAppWidget(widgetId, views);
        manager.notifyAppWidgetViewDataChanged(new int[]{widgetId}, R.id.widget_task_list);
    }

    public static void refreshAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, TaskWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(widget)) {
            updateWidget(context, manager, id);
        }
    }
}
