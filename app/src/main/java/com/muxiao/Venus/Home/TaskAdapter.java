package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.listitem.ListItemViewHolder;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;
import com.muxiao.Venus.common.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    private final List<TaskItem> taskList;
    // 已播放过入场动画的位置，避免滚动复用（onBind 重入）时反复闪动
    private final Set<Integer> animated = new HashSet<>();

    public TaskAdapter(List<TaskItem> taskList) {
        this.taskList = taskList;
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_task_list, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        Context context = holder.itemView.getContext();
        TaskItem task = taskList.get(position);
        holder.taskName.setText(task.getName());

        // 语义色：解析主题属性（与 Material 主题调色板一致）。
        // 注意：这些 attr 由 Material 库声明，用 Material 的 R.attr 引用以保证可解析。
        int colorPrimary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1B6FE0);
        int colorError = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, 0xFFB3261E);
        int colorGray = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575);
        int colorSuccess = ContextCompat.getColor(context, R.color.status_success);

        // 默认隐藏删除线覆盖层，仅 CANCELLED 态显示
        holder.statusIconStrike.setVisibility(View.GONE);

        // 状态圈圈底 + 状态图形 + 右侧状态（终态=chip，过程态=mono 文案），对齐设计稿状态语义
        holder.taskProgress.setVisibility(View.VISIBLE);
        holder.taskProgress.setBackground(null);
        holder.taskProgress.setTextColor(colorGray);
        holder.taskStatusChip.setVisibility(View.GONE);
        switch (task.getStatus()) {
            case IN_PROGRESS:
                // 主色环 + 主色点；右侧 mono RUNNING（设计稿活状态）
                holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_running);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorPrimary));
                holder.taskProgress.setText(R.string.task_status_running);
                holder.taskProgress.setTextColor(colorPrimary);
                break;
            case COMPLETED:
            case WARNING:
                // 成功容器绿圈 + 语义成功 check；右侧「已签」chip（WARNING=已签过，同归已签语义）
                holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_success);
                holder.statusIcon.setImageResource(R.drawable.ic_check);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorSuccess));
                holder.taskProgress.setVisibility(View.GONE);
                showChip(holder, context, R.string.task_chip_done,
                        ContextCompat.getColor(context, R.color.status_success_container),
                        ContextCompat.getColor(context, R.color.status_on_success_container));
                break;
            case ERROR:
                // 描边圈 + 错误色图形；右侧「失败」chip（错误容器底）
                holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_pending);
                holder.statusIcon.setImageResource(R.drawable.ic_error);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorError));
                holder.taskProgress.setVisibility(View.GONE);
                showChip(holder, context, R.string.task_chip_failed,
                        MaterialColors.getColor(context, com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6),
                        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B));
                break;
            case CANCELLED:
                // 灰色点 + 高对比删除线（colorError 斜线覆盖，明显可辨）；右侧 mono 已取消
                holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_pending);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorGray));
                holder.statusIconStrike.setVisibility(View.VISIBLE);
                holder.statusIconStrike.setImageTintList(ColorStateList.valueOf(colorError));
                holder.taskProgress.setText(R.string.task_status_cancelled);
                break;
            default: // PENDING
                // 描边圈 + 灰点；右侧 mono PENDING
                holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_pending);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorGray));
                holder.taskProgress.setText(R.string.task_status_pending);
                break;
        }

        // 项间发丝分割线：首条隐藏（避免顶端生硬分割），其后每条之上显示 1dp outlineVariant 线
        holder.itemDivider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);

        // M3 List-Item 位置状态：首/中/尾/单 自动应用 M3 圆角与选中背景
        holder.bind(position, getItemCount());

        animateEnter(holder, position, context);
    }

    /** 终态右侧 chip：设置文案、底色与文字色后显示。
     *  底图统一为 bg_chip（胶囊），fillColor 通过 backgroundTint 给定语义色。 */
    private void showChip(TaskViewHolder holder, Context context, int textRes,
                          int fillColor, int textColor) {
        holder.taskProgress.setVisibility(View.GONE);
        holder.taskStatusChip.setVisibility(View.VISIBLE);
        holder.taskStatusChip.setText(textRes);
        holder.taskStatusChip.setBackgroundResource(R.drawable.bg_chip);
        holder.taskStatusChip.setBackgroundTintList(ColorStateList.valueOf(fillColor));
        holder.taskStatusChip.setTextColor(textColor);
    }

    /**
     * 列表项入场微动效：淡入 + 轻微上移，使用 M3 emphasized-decelerate 缓动。
     * 系统「减少动态效果」开启时直接落位，不播放动画。
     */
    private void animateEnter(TaskViewHolder holder, int position, Context context) {
        if (animated.contains(position)) return;
        if (tools.isReducedMotionEnabled(context)) {
            animated.add(position);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        holder.itemView.setAlpha(0f);
        holder.itemView.setTranslationY((int) (8f * density));
        holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(context.getResources().getInteger(R.integer.motion_duration_medium))
                .setInterpolator(AnimationUtils.loadInterpolator(context, R.anim.emphasized_decelerate))
                .withEndAction(() -> animated.add(position))
                .start();
    }

    @Override
    public int getItemCount() {
        return taskList.size();
    }

    public static class TaskViewHolder extends ListItemViewHolder {
        private final MaterialTextView taskName;
        private final MaterialTextView taskProgress;
        private final MaterialTextView taskStatusChip;
        private final ImageView statusIcon;
        private final ImageView statusIconStrike;
        private final FrameLayout statusCircle;
        private final View itemDivider;

        public TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            taskName = itemView.findViewById(R.id.task_name);
            statusIcon = itemView.findViewById(R.id.status_icon);
            taskProgress = itemView.findViewById(R.id.task_progress);
            taskStatusChip = itemView.findViewById(R.id.task_status_chip);
            statusIconStrike = itemView.findViewById(R.id.status_icon_strike);
            statusCircle = itemView.findViewById(R.id.status_circle);
            itemDivider = itemView.findViewById(R.id.item_divider);
        }
    }
}
