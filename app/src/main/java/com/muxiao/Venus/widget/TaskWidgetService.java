package com.muxiao.Venus.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.common.TaskSettings;

import java.util.ArrayList;
import java.util.List;

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
            TaskStatusManager statusManager = new TaskStatusManager(context);

            for (String name : names) {
                taskNames.add(name);
                taskStatuses.add(statusManager.getStatus(name));
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

            // 状态图标和颜色
            switch (status) {
                case TaskStatusManager.STATUS_COMPLETED:
                    views.setTextViewText(R.id.widget_task_status_icon, "✓");
                    views.setTextColor(R.id.widget_task_status_icon, 0xFF4CAF50); // green
                    break;
                case TaskStatusManager.STATUS_IN_PROGRESS:
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon, 0xFF2196F3); // blue
                    break;
                case TaskStatusManager.STATUS_ERROR:
                    views.setTextViewText(R.id.widget_task_status_icon, "✗");
                    views.setTextColor(R.id.widget_task_status_icon, 0xFFF44336); // red
                    break;
                case TaskStatusManager.STATUS_WARNING:
                    views.setTextViewText(R.id.widget_task_status_icon, "◆");
                    views.setTextColor(R.id.widget_task_status_icon, 0xFFFF9800); // orange
                    break;
                default: // PENDING
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon, 0xFF9E9E9E); // grey
                    break;
            }

            // 设置 fill-in intent 用于列表项点击
            Intent fillIn = new Intent();
            views.setOnClickFillInIntent(R.id.widget_task_item_root, fillIn);

            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
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
