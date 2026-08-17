package com.muxiao.Venus.Home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.listitem.ListItemViewHolder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
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

        // 默认隐藏删除线覆盖层，仅 CANCELLED 态显示
        holder.statusIconStrike.setVisibility(View.GONE);

        int tintColor;
        switch (task.getStatus()) {
            case IN_PROGRESS:
                holder.statusIcon.setImageResource(R.drawable.ic_pending);
                tintColor = neutralTint(context);
                holder.taskProgress.setVisibility(View.VISIBLE);
                holder.taskProgress.setIndeterminate(true);
                break;
            case COMPLETED:
                holder.statusIcon.setImageResource(R.drawable.ic_check);
                tintColor = colorPrimary;
                holder.taskProgress.setVisibility(View.GONE);
                holder.taskProgress.setIndeterminate(false);
                break;
            case ERROR:
                holder.statusIcon.setImageResource(R.drawable.ic_error);
                tintColor = colorError;
                holder.taskProgress.setVisibility(View.GONE);
                holder.taskProgress.setIndeterminate(false);
                break;
            case WARNING:
                holder.statusIcon.setImageResource(R.drawable.ic_warning);
                tintColor = ContextCompat.getColor(context, R.color.status_warning);
                holder.taskProgress.setVisibility(View.VISIBLE);
                holder.taskProgress.setIndeterminate(true);
                break;
            case CANCELLED:
                // 灰色图标 + 高对比删除线（colorError 斜线覆盖，明显可辨）
                holder.statusIcon.setImageResource(R.drawable.ic_pending);
                tintColor = colorGray;
                holder.taskProgress.setVisibility(View.GONE);
                holder.taskProgress.setIndeterminate(false);
                holder.statusIconStrike.setVisibility(View.VISIBLE);
                holder.statusIconStrike.setImageTintList(ColorStateList.valueOf(colorError));
                break;
            default: // PENDING
                holder.statusIcon.setImageResource(R.drawable.ic_pending);
                tintColor = colorGray;
                holder.taskProgress.setVisibility(View.GONE);
                holder.taskProgress.setIndeterminate(false);
                break;
        }

        // 状态色着色
        holder.statusIcon.setImageTintList(ColorStateList.valueOf(tintColor));

        // 项间发丝分割线：首条隐藏（避免顶端生硬分割），其后每条之上显示 1dp outlineVariant 线
        holder.itemDivider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);

        // M3 List-Item 位置状态：首/中/尾/单 自动应用 M3 圆角与选中背景
        holder.bind(position, getItemCount());

        animateEnter(holder, position, context);
    }

    /** 中性强调色（进行中/待定/取消态的图标着色）。 */
    private int neutralTint(Context context) {
        return AppCompatResources.getColorStateList(context, R.color.tint_neutral).getDefaultColor();
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
        private final LinearProgressIndicator taskProgress;
        private final ImageView statusIcon;
        private final ImageView statusIconStrike;
        private final View itemDivider;

        public TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            taskName = itemView.findViewById(R.id.task_name);
            statusIcon = itemView.findViewById(R.id.status_icon);
            taskProgress = itemView.findViewById(R.id.task_progress);
            statusIconStrike = itemView.findViewById(R.id.status_icon_strike);
            itemDivider = itemView.findViewById(R.id.item_divider);
        }
    }
}
