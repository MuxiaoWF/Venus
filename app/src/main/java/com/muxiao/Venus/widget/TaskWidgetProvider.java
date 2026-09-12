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
import com.muxiao.Venus.common.LocaleHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 任务状态桌面小组件。
 * 显示今日各任务的完成情况，支持运行任务、点击打开App和手动刷新。
 */
public class TaskWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.muxiao.Venus.widget.REFRESH";

    /** 去抖窗口：任务执行期间状态变化密集（每条任务至少 2 次），窗口内的多次请求合并为一次刷新。 */
    private static final long REFRESH_DEBOUNCE_MS = 250;

    private static final android.os.Handler REFRESH_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 是否已有排期中的刷新（合并重复请求，避免取消/停止等多路径重复触发）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean REFRESH_SCHEDULED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 最近一次刷新请求的 Application Context（不持有 Activity，无泄漏风险）。 */
    private static volatile Context refreshContext;

    @Override
    @SuppressWarnings("deprecation")
    // 收到系统更新广播时，按本地化上下文逐个刷新小组件。
    // notifyAppWidgetViewDataChanged(int[], int) 在 API 31 标记过时，但 minSdk 场景必须沿用旧重载。
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Context localized = LocaleHelper.wrap(context);
        for (int appWidgetId : appWidgetIds) {
            updateWidget(localized, appWidgetManager, appWidgetId);
        }
        // 列表数据统一通知一次（原实现每个 widget 各通知一次，重复触发 RemoteViewsFactory 重建）
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_task_list);
    }

    @Override
    // 拦截 Refresh 广播并刷新全部小组件（其余动作交给父类）
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            refreshAllWidgets(context);
        }
    }

    @SuppressWarnings("deprecation")
    // 构建 RemoteViews：绑定标题/运行/刷新点击、任务列表适配器与完成统计
    static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_task_status);

        // 标题栏与列表项点击 → 打开 App（两个入口共用同一个 PendingIntent）
        PendingIntent launchPi = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_header, launchPi);
        views.setPendingIntentTemplate(R.id.widget_task_list, launchPi);

        // 运行按钮 → 广播启动任务；运行中显示加载文案并禁用
        Intent runIntent = new Intent(context, TaskWidgetReceiver.class)
                .setAction(TaskWidgetReceiver.ACTION_RUN_TASKS);
        views.setOnClickPendingIntent(R.id.btn_run, PendingIntent.getBroadcast(context, 0, runIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        boolean isRunning = ForegroundTaskService.isRunning();
        views.setTextViewText(R.id.btn_run, context.getString(
                isRunning ? R.string.widget_running : R.string.widget_run));
        views.setBoolean(R.id.btn_run, "setEnabled", !isRunning);

        // 刷新按钮 → 刷新小组件
        Intent refreshIntent = new Intent(context, TaskWidgetProvider.class).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(context, 1, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // 任务列表适配器
        views.setRemoteAdapter(R.id.widget_task_list, new Intent(context, TaskWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));

        // 底部统计信息
        String[] taskNames = TaskSettings.fromPreferences(context).getTaskNames(context).toArray(new String[0]);
        int completed = new TaskStatusManager(context).getCompletedCount(taskNames);
        views.setTextViewText(R.id.widget_summary,
                context.getString(R.string.widget_task_summary, completed, taskNames.length));
        views.setTextViewText(R.id.widget_date,
                new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date()));

        manager.updateAppWidget(widgetId, views);
    }

    /**
     * 请求刷新全部小组件实例。
     * <p>
     * 刻意做去抖合并：任务执行期间每条任务状态变化都会请求刷新，若逐次真正刷新，
     * 每次都要重建 RemoteViews + 两次系统 IPC（updateAppWidget / notifyAppWidgetViewDataChanged），
     * 还会让 launcher 反复向 RemoteViewsService 拉全量列表数据。合并后窗口内只刷新一次，
     * 且读到的必然是最新状态；调用方可以从任意线程调用。
     */
    public static void refreshAllWidgets(Context context) {
        if (context == null) return;
        refreshContext = context.getApplicationContext();
        if (REFRESH_SCHEDULED.getAndSet(true)) return;
        REFRESH_HANDLER.postDelayed(() -> {
            REFRESH_SCHEDULED.set(false);
            Context latest = refreshContext;
            if (latest != null) refreshAllWidgetsNow(latest);
        }, REFRESH_DEBOUNCE_MS);
    }

    /** 立即（同步）刷新全部小组件实例，供 {@link #refreshAllWidgets} 的去抖回调调用。 */
    @SuppressWarnings("deprecation")
    private static void refreshAllWidgetsNow(Context context) {
        Context localized = LocaleHelper.wrap(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(localized);
        ComponentName widget = new ComponentName(localized, TaskWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(widget);
        if (ids.length == 0) return;
        for (int id : ids) {
            updateWidget(localized, manager, id);
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_task_list);
    }
}
