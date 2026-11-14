package com.muxiao.Venus.widget;

import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import androidx.core.content.ContextCompat;

import com.muxiao.Venus.R;
import com.muxiao.Venus.common.LocaleHelper;
import com.muxiao.Venus.Home.TaskDescriptor;
import com.muxiao.Venus.Home.TaskSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为 Widget 的 ListView 提供任务列表数据。
 *
 * <p>数据口径与首页任务列表完全一致：同一 {@link TaskSettings} 快照、同一状态桶、同一凭证就绪表。
 * 状态按「稳定任务 id」查（名称随语言变化，旧版按名查会在切语言后整表回落待执行）。
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
        return new TaskRemoteViewsFactory(LocaleHelper.wrap(getApplicationContext()));
    }

    private static class TaskRemoteViewsFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<String> taskIds = new ArrayList<>();
        private final List<String> taskNames = new ArrayList<>();
        private final List<String> taskStatuses = new ArrayList<>();
        /** 是否具备执行条件（凭证就绪）：与应用内「未配置」划线同源，见 {@link TaskSettings#getTaskReadiness}。 */
        private final List<Boolean> taskReady = new ArrayList<>();

        TaskRemoteViewsFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            loadData();
        }

        @Override
        public void onDataSetChanged() {
            clear();
            loadData();
        }

        // 读取任务配置、每个任务的状态与凭证就绪情况，重建四个平行列表（id/名/状态/就绪）
        private void loadData() {
            TaskSettings settings = TaskSettings.fromPreferences(context);
            // 状态按用户分桶：展示小组件目标用户（当前选中，兜底最后运行）的完成情况
            String targetUser = TaskWidgetReceiver.resolveTargetUser(context);
            Map<String, String> statuses = new TaskStatusManager(context).getAllStatuses(targetUser);
            // 就绪表：与应用内一致——未配置凭证的任务照样列出，但划线灰显（执行入口会跳过）
            Map<String, Boolean> readiness = settings.getTaskReadiness(context, targetUser);

            for (TaskDescriptor task : settings.getTasks(context)) {
                taskIds.add(task.id);
                taskNames.add(task.name);
                String status = statuses.get(task.id);
                taskStatuses.add(status != null ? status : TaskStatusManager.STATUS_PENDING);
                Boolean ready = readiness.get(task.id);
                taskReady.add(ready == null || ready);
            }
        }

        private void clear() {
            taskIds.clear();
            taskNames.clear();
            taskStatuses.clear();
            taskReady.clear();
        }

        @Override
        public void onDestroy() {
            clear();
        }

        @Override
        public int getCount() {
            return taskIds.size();
        }

        @Override
        // 根据任务状态渲染列表项（图标与颜色），并设置点击 fill-in intent
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= taskIds.size()) {
                return null;
            }

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_task_item);
            String name = taskNames.get(position);
            String status = taskStatuses.get(position);
            boolean ready = taskReady.get(position);

            if (ready) {
                views.setTextViewText(R.id.widget_task_name, name);
                views.setTextColor(R.id.widget_task_name,
                        ContextCompat.getColor(context, R.color.widget_text_primary));
                applyStatus(views, status);
            } else {
                // 未配置凭证：与应用内「未配置」项同款——名称划线 + 置灰，图标用破折号占位。
                // 旧版小组件不读就绪表，这类任务显示「○ 待执行」，用户会以为它会被执行（实际被跳过）。
                SpannableString struck = new SpannableString(name);
                struck.setSpan(new StrikethroughSpan(), 0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                views.setTextViewText(R.id.widget_task_name, struck);
                views.setTextColor(R.id.widget_task_name,
                        ContextCompat.getColor(context, R.color.widget_text_secondary));
                views.setTextViewText(R.id.widget_task_status_icon, "—");
                views.setTextColor(R.id.widget_task_status_icon,
                        ContextCompat.getColor(context, R.color.widget_text_secondary));
            }

            Intent fillIn = new Intent();
            views.setOnClickFillInIntent(R.id.widget_task_item_root, fillIn);

            return views;
        }

        /**
         * 状态图标与颜色：与主 app 的状态语义色同源（status_* / M3 语义色，
         * RemoteViews 不解析 ?attr，故用 ContextCompat 解析 plain color，昼夜自动切换）。
         * 逐分支与 {@code TaskStatusManager.toTaskStatus} 的语义映射保持一致：warning 旧版已不再产生，
         * 仍按「进行中」呈现，避免当日残留被画成警告色。
         */
        private void applyStatus(RemoteViews views, String status) {
            switch (status) {
                case TaskStatusManager.STATUS_COMPLETED:
                    views.setTextViewText(R.id.widget_task_status_icon, "✓");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.status_success));
                    break;
                case TaskStatusManager.STATUS_IN_PROGRESS:
                case TaskStatusManager.STATUS_WARNING:
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.widget_status_in_progress));
                    break;
                case TaskStatusManager.STATUS_ERROR:
                    views.setTextViewText(R.id.widget_task_status_icon, "✗");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.blue_theme_error));
                    break;
                case TaskStatusManager.STATUS_CANCELLED:
                    // 与应用内「已取消」对齐：中性灰 + 圆圈斜线，不再与「待执行」同图标
                    views.setTextViewText(R.id.widget_task_status_icon, "⊘");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.widget_text_secondary));
                    break;
                default: // PENDING
                    views.setTextViewText(R.id.widget_task_status_icon, "○");
                    views.setTextColor(R.id.widget_task_status_icon,
                            ContextCompat.getColor(context, R.color.widget_text_secondary));
                    break;
            }
        }

        @Override
        // 数据重载期间 launcher 会短暂显示加载视图；默认加载视图（居中 spinner）与条目布局完全不同，
        // 整表重建时会肉眼可见地闪一下。返回与条目同构的空白 RemoteViews，让重载前后视觉连续
        // （onDataSetChanged 完成后立即被真实条目覆盖）。
        public RemoteViews getLoadingView() {
            return new RemoteViews(context.getPackageName(), R.layout.widget_task_item);
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        // hasStableIds() 为 true 时 item id 必须由「项目本身」决定：原实现返回 position，用户增删游戏导致
        // 任务顺序变化时 launcher 会把新顺序的条目误判为同一项而错误复用旧视图。
        public long getItemId(int position) {
            if (position < 0 || position >= taskIds.size()) return 0;
            // 32 位非负稳定 id：顺序变化不再误判为同一项，且不会与 Adapter.NO_ID(-1) 冲突。
            // 用任务 id（而非显示名）取哈希：切语言后同一任务仍保持同一 id，避免整表重绑。
            return taskIds.get(position).hashCode() & 0xFFFFFFFFL;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
