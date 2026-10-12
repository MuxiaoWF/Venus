package com.muxiao.Venus.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import com.muxiao.Venus.Home.ForegroundTaskService;
import com.muxiao.Venus.Home.HomeFragment;
import com.muxiao.Venus.MainActivity;
import com.muxiao.Venus.R;
import com.muxiao.Venus.Home.TaskSettings;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.LocaleHelper;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /**
     * 最近一次刷新请求的 Application Context。
     * 用弱引用包装：静态字段直接持有 Context 类型会触发 Lint StaticFieldLeak；
     * 实际存的是进程级 Application Context（随进程存活，弱引用不会提前回收）。
     */
    private static volatile java.lang.ref.WeakReference<Context> refreshContextRef;

    /**
     * 最近一次真正渲染到桌面的画面签名（full=含统计/按钮/日期，list=仅列表条目数据）。
     * <p>
     * RemoteViews 的 ListView 没有差异更新，{@code notifyAppWidgetViewDataChanged} 一次
     * 就是整表销毁重建，肉眼可见地闪一下。任务执行期间每个状态变化都会请求刷新，
     * 其中不少请求的画面与桌面现状一致（复位、运行按钮态、统计文案各自变化或全未变化），
     * 用签名比对跳过「画面没变的刷新」，把列表重建收敛到「条目数据真的变了」的时刻。
     */
    private static volatile String lastRenderedFullSignature;
    private static volatile String lastRenderedListSignature;

    @Override
    @SuppressWarnings("deprecation")
    // 收到系统更新广播时，按本地化上下文逐个刷新小组件。
    // notifyAppWidgetViewDataChanged(int[], int) 在 API 31 标记过时，但 minSdk 场景必须沿用旧重载。
    // 系统路径（开机/添加小组件/包更新）无条件渲染：此时画面签名可能已失效（如 launcher 重建了视图树）。
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Context localized = LocaleHelper.wrap(context);
        WidgetSnapshot snapshot = WidgetSnapshot.load(localized);
        for (int appWidgetId : appWidgetIds) {
            updateWidget(localized, appWidgetManager, appWidgetId, snapshot);
        }
        // 列表数据统一通知一次（原实现每个 widget 各通知一次，重复触发 RemoteViewsFactory 重建）
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_task_list);
        rememberRendered(snapshot);
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
    static void updateWidget(Context context, AppWidgetManager manager, int widgetId, WidgetSnapshot snapshot) {
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
        // 与 TaskWidgetReceiver 的并发防护同口径：App 内前台任务不走 Service，需一并探测
        boolean isRunning = snapshot.isRunning;
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

        // 底部统计信息（状态按用户分桶：展示目标用户——当前选中，兜底最后运行——的完成情况）
        String[] taskNames = snapshot.taskNames.toArray(new String[0]);
        int completed = snapshot.completedCount();
        views.setTextViewText(R.id.widget_summary,
                context.getResources().getQuantityString(
                        R.plurals.widget_task_summary, completed, completed, taskNames.length));
        // 日期 chip 显示状态归属日（status_date）而非渲染时刻：任务状态按日重置，
        // 归属日才是这组 ✓/✗ 的语义日期。status_date 格式固定 yyyy-MM-dd，直接截取 MM/dd 段
        String statusDate = snapshot.statusDate;
        views.setTextViewText(R.id.widget_date, statusDate.length() >= 10
                ? statusDate.substring(5).replace('-', '/')
                : new SimpleDateFormat("MM/dd", Locale.getDefault()).format(new Date()));

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
        refreshContextRef = new WeakReference<>(context.getApplicationContext());
        if (REFRESH_SCHEDULED.getAndSet(true)) return;
        REFRESH_HANDLER.postDelayed(() -> {
            REFRESH_SCHEDULED.set(false);
            WeakReference<Context> ref = refreshContextRef;
            Context latest = ref != null ? ref.get() : null;
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
        WidgetSnapshot snapshot = WidgetSnapshot.load(localized);
        // 画面无任何可见变化：整次刷新跳过。任务侧每个状态变化都会请求刷新，
        // 不加此守卫时，未改变画面的请求（复位后重复请求、统计/按钮态单独变化等）
        // 也会触发整套重渲染与列表重建
        String fullSignature = snapshot.fullSignature();
        if (fullSignature.equals(lastRenderedFullSignature)) return;
        boolean listChanged = !snapshot.listSignature().equals(lastRenderedListSignature);
        lastRenderedFullSignature = fullSignature;
        lastRenderedListSignature = snapshot.listSignature();
        for (int id : ids) {
            updateWidget(localized, manager, id, snapshot);
        }
        // 列表条目数据未变时不通知数据变更：notifyAppWidgetViewDataChanged 会让 launcher
        // 重新拉取 RemoteViewsFactory 全量数据并整表重建列表，是小组件闪烁的直接来源；
        // 统计/按钮态等外层变化用 updateAppWidget 即可，无需牵连列表
        if (listChanged) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_task_list);
    }

    /** 记录「已渲染画面」的签名，供后续刷新请求做变更比对。 */
    private static void rememberRendered(WidgetSnapshot snapshot) {
        lastRenderedFullSignature = snapshot.fullSignature();
        lastRenderedListSignature = snapshot.listSignature();
    }

    /**
     * 渲染所需的全部可变数据快照：一次读取、渲染与签名同源，
     * 避免「签名与实际渲染内容不一致」导致漏刷或重复刷。
     */
    private static final class WidgetSnapshot {
        final String targetUser;
        final List<String> taskNames;
        final Map<String, String> statuses;
        final boolean isRunning;
        final String statusDate;
        /** 语言标识（设置项 + 跟随系统时的系统语言）：切换语言后即使数据未变也须重渲染文案。 */
        final String languageTag;

        private WidgetSnapshot(String targetUser, List<String> taskNames, Map<String, String> statuses,
                               boolean isRunning, String statusDate, String languageTag) {
            this.targetUser = targetUser;
            this.taskNames = taskNames;
            this.statuses = statuses;
            this.isRunning = isRunning;
            this.statusDate = statusDate;
            this.languageTag = languageTag;
        }

        /** 从各数据源读取当前时刻的完整快照（触发 {@code TaskStatusManager.ensureToday()} 跨天校验）。 */
        static WidgetSnapshot load(Context context) {
            String targetUser = TaskWidgetReceiver.resolveTargetUser(context);
            List<String> taskNames = TaskSettings.fromPreferences(context).getTaskNames(context);
            TaskStatusManager statusManager = new TaskStatusManager(context);
            Map<String, String> statuses = statusManager.getAllStatuses(targetUser);
            String statusDate = statusManager.getStatusDate(targetUser);
            SharedPreferences languagePrefs = context.getSharedPreferences(
                    Constants.Prefs.LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE);
            int selectedLanguage = languagePrefs.getInt(Constants.Prefs.SELECTED_LANGUAGE, 0);
            String languageTag = selectedLanguage + ":"
                    + (selectedLanguage == 0 ? Locale.getDefault().toLanguageTag() : "");
            return new WidgetSnapshot(targetUser, taskNames, statuses,
                    ForegroundTaskService.isRunning() || HomeFragment.isTaskRunning(),
                    statusDate, languageTag);
        }

        /** 统计快照内「已完成」的任务数（与 getCompletedCount 同口径：键序按任务名集合）。 */
        int completedCount() {
            int count = 0;
            for (String name : taskNames)
                if (TaskStatusManager.STATUS_COMPLETED.equals(statuses.get(name))) count++;
            return count;
        }

        /** 列表条目数据签名：语言 + 目标用户 + 任务集合 + 逐条状态。 */
        String listSignature() {
            StringBuilder sb = new StringBuilder(languageTag).append('|').append(targetUser);
            for (String name : taskNames)
                sb.append('|').append(name).append('=').append(statuses.get(name));
            return sb.toString();
        }

        /** 整体画面签名：列表签名 + 运行按钮态 + 状态归属日 + 完成统计。 */
        String fullSignature() {
            return listSignature() + '|' + isRunning + '|' + statusDate + '|' + completedCount();
        }
    }
}
