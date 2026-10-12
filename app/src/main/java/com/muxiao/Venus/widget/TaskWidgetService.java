package com.muxiao.Venus.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.ContextCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.Home.TaskSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为 Widget 的 ListView 提供任务列表数据。
 */
public class TaskWidgetService extends RemoteViewsService {

    @Override
    // 用 LocaleHelper 包裹上下文，使小组件内的任务名等文本遵循设置语言
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    // 返回工厂实例（使用本地化 ApplicationContext），为列表提供任务项视图
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        // 用 wrap 后的 Application Context，确保任务名等文本遵循设置语言
        return new TaskRemoteViewsFactory(LocaleHelper.wrap(getApplicationContext()));
    }

    private static class TaskRemoteViewsFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<String> taskNames = new ArrayList<>();
        private final List<String> taskStatuses = new ArrayList<>();

        TaskRemoteViewsFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            loadData();
        }

        @Override
        public void onDataSetChanged() {
            taskNames.clear();
            taskStatuses.clear();
            loadData();
        }

        // 读取任务配置与每个任务的状态，重建任务名/状态两个列表
        private void loadData() {
            TaskSettings settings = TaskSettings.fromPreferences(context);
            List<String> names = settings.getTaskNames(context);
            // 状态按用户分桶：展示小组件目标用户（当前选中，兜底最后运行）的完成情况
            Map<String, String> statuses = new TaskStatusManager(context)
                    .getAllStatuses(TaskWidgetReceiver.resolveTargetUser(context));

            for (String name : names) {
                taskNames.add(name);
                String status = statuses.get(name);
                taskStatuses.add(status != null ? status : TaskStatusManager.STATUS_PENDING);
            }
        }

        @Override
        public void onDestroy() {
            taskNames.clear();
            taskStatuses.clear();
        }

        @Override
        public int getCount() {
            return taskNames.size();
        }

        @Override
        // 根据任务状态渲染列表项（图标与颜色），并设置点击 fill-in intent
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= taskNames.size()) {
                return null;
            }

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_task_item);
            String name = taskNames.get(position);
            String status = taskStatuses.get(position);

            views.setTextViewText(R.id.widget_task_name, name);

            // 状态图标和颜色：与主 app 的状态语义色同源（status_* / M3 语义色，
            // RemoteViews 不解析 ?attr，故用 ContextCompat 解析 plain color，昼夜自动切换）
            switch (status) {
                case TaskStatusManager.STATUS_COMPLETED:
                    views.setTextViewText(R.id.widget_task_status_icon, "✓");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.status_success));
                    break;
                case TaskStatusManager.STATUS_IN_PROGRESS:
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.widget_status_in_progress));
                    break;
                case TaskStatusManager.STATUS_ERROR:
                    views.setTextViewText(R.id.widget_task_status_icon, "✗");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.blue_theme_error));
                    break;
                case TaskStatusManager.STATUS_WARNING:
                    views.setTextViewText(R.id.widget_task_status_icon, "◆");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.status_warning));
                    break;
                default: // PENDING
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.widget_text_secondary));
                    break;
            }

            // 设置 fill-in intent 用于列表项点击
            Intent fillIn = new Intent();
            views.setOnClickFillInIntent(R.id.widget_task_item_root, fillIn);

            return views;
        }

        @Override
        // 数据重载期间 launcher 会短暂显示加载视图；默认加载视图（居中 spinner）与条目布局
        // 完全不同，整表重建时表现为肉眼可见的"闪烁"。返回与条目同构的空白 RemoteViews，
        // 让重载前后视觉保持连续（onDataSetChanged 完成后立即被真实条目覆盖）。
        public RemoteViews getLoadingView() {
            return new RemoteViews(context.getPackageName(), R.layout.widget_task_item);
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        // hasStableIds() 为 true 时 item id 必须由「项目本身」决定。原实现返回 position，
        // 当用户在设置里增删游戏导致任务顺序变化时，launcher 会把新顺序的条目误判为同一项而复用旧视图。
        public long getItemId(int position) {
            if (position < 0 || position >= taskNames.size()) return 0;
            // 32 位非负稳定 id：顺序变化不再误判为同一项，且不会与 Adapter.NO_ID(-1) 冲突
            return taskNames.get(position).hashCode() & 0xFFFFFFFFL;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
