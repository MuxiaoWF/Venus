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
import com.muxiao.Venus.common.ui.LensGlassDrawable;
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
        // 完成/警告专用语义绿：独立于主题主色（主色同时被「进行中」使用），
        // 昼夜两套色值见 colors.xml / values-night/colors.xml 的 status_success 组
        int colorSuccess = ContextCompat.getColor(context, R.color.status_success);
        int colorSuccessContainer = ContextCompat.getColor(context, R.color.status_success_container);
        int colorOnSuccessContainer = ContextCompat.getColor(context, R.color.status_on_success_container);

        // 默认隐藏删除线覆盖层，仅 CANCELLED 态显示
        holder.statusIconStrike.setVisibility(View.GONE);

        // 状态圆液态玻璃化（「玻璃底 + 语义色描边」方案）：玻璃恒为玻璃，
        // 状态由语义色描边 + 图标 tint 承载，不再整块换填充色。
        // 回退底固定为描边圈（无背景图时保留圈形），语义描边在玻璃/回退两模式下都绘制。
        if (holder.statusLens == null) {
            holder.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_pending);
            LensGlassDrawable.apply(holder.statusCircle, -1f);
            holder.statusLens = (LensGlassDrawable) holder.statusCircle.getBackground();
        }
        int colorOutlineVariant = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0xFFE0E0E0);

        // 状态圈圈底 + 状态图形 + 右侧状态（终态=chip，过程态=mono 文案），对齐设计稿状态语义
        holder.taskProgress.setVisibility(View.VISIBLE);
        holder.taskProgress.setBackground(null);
        holder.taskProgress.setTextColor(colorGray);
        holder.taskStatusChip.setVisibility(View.GONE);
        switch (task.getStatus()) {
            case IN_PROGRESS:
            case WARNING:
                // 主色环 + 主色点；右侧 mono RUNNING（设计稿活状态）。
                // WARNING 仅来自旧版本残留的持久化数据（等待验证码曾短暂写入 warning，
                // 现已改为全程保持 IN_PROGRESS），按进行中渲染以避免误显示为已完成。
                holder.statusLens.setSemanticStrokeColor(colorPrimary);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorPrimary));
                holder.taskProgress.setText(R.string.task_status_running);
                holder.taskProgress.setTextColor(colorPrimary);
                break;
            case RETAINED:
                // 「沿用上次结果」展示态：描边圈 + 绿色 check +「沿用」chip。
                // 与 COMPLETED 的语义绿描边区分——表示当日已完成，但本轮运行尚未执行到，
                // 绿色结果系沿用上一次；执行到时会先切 RUNNING 再恢复语义绿描边。
                holder.statusLens.setSemanticStrokeColor(colorOutlineVariant);
                holder.statusIcon.setImageResource(R.drawable.ic_check);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorSuccess));
                holder.taskProgress.setVisibility(View.GONE);
                showChip(holder, R.string.task_chip_retained,
                        colorSuccessContainer, colorOnSuccessContainer);
                break;
            case COMPLETED:
                // 语义绿描边 + 绿色 check；右侧「已签」chip。
                // 完成态用固定语义绿而非主题主色：主色与「进行中」态同源导致完成不显眼，
                // 且绿=成功的语义与桌面小组件（✓ 用 status_success）保持一致，昼夜自动适配。
                holder.statusLens.setSemanticStrokeColor(colorSuccess);
                holder.statusIcon.setImageResource(R.drawable.ic_check);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorSuccess));
                holder.taskProgress.setVisibility(View.GONE);
                showChip(holder, R.string.task_chip_done,
                        colorSuccessContainer, colorOnSuccessContainer);
                break;
            case ERROR:
                // 错误红描边 + 大号「×」；右侧「失败」chip（错误容器底）
                holder.statusLens.setSemanticStrokeColor(colorError);
                holder.statusIcon.setImageResource(R.drawable.ic_task_error);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorError));
                holder.taskProgress.setVisibility(View.GONE);
                showChip(holder, R.string.task_chip_failed,
                        MaterialColors.getColor(context, com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6),
                        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B));
                break;
            case CANCELLED:
                // 灰点 + 错误色「×」覆盖（划掉待执行点，明显可辨）；右侧 mono 已取消
                holder.statusLens.setSemanticStrokeColor(colorOutlineVariant);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorGray));
                holder.statusIconStrike.setVisibility(View.VISIBLE);
                holder.statusIconStrike.setImageTintList(ColorStateList.valueOf(colorError));
                holder.taskProgress.setText(R.string.task_status_cancelled);
                break;
            default: // PENDING
                // 描边圈 + 灰点；右侧 mono PENDING
                holder.statusLens.setSemanticStrokeColor(colorOutlineVariant);
                holder.statusIcon.setImageResource(R.drawable.ic_dot);
                holder.statusIcon.setImageTintList(ColorStateList.valueOf(colorGray));
                holder.taskProgress.setText(R.string.task_status_pending);
                break;
        }

        // 项间发丝分割线：首条隐藏（避免顶端生硬分割），其后每条之上显示 1dp outlineVariant 线
        holder.itemDivider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);

        // M3 List-Item 位置状态：首/中/尾/单 自动应用 M3 圆角与选中背景
        holder.bind(position, getItemCount());

        // 状态切换强调动效：同一 holder 上一次绑定的任务与状态均已知、且状态发生变化时，
        // 状态图标做轻微 scale-in（M3：进入元素 emphasized-decelerate），
        // 让 RUNNING → 已签/失败 的状态跃迁可被感知。
        // 滚动复用（onViewRecycled 后重绑）与首次入场不重放；尊重「减少动态效果」。
        boolean statusChanged = task.getName().equals(holder.boundTaskName)
                && holder.boundStatus != null && holder.boundStatus != task.getStatus();
        holder.boundTaskName = task.getName();
        holder.boundStatus = task.getStatus();
        if (statusChanged && !tools.isReducedMotionEnabled(context)) {
            holder.statusIcon.setAlpha(0f);
            holder.statusIcon.setScaleX(0.6f);
            holder.statusIcon.setScaleY(0.6f);
            holder.statusIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(context.getResources().getInteger(R.integer.motion_duration_medium))
                    .setInterpolator(AnimationUtils.loadInterpolator(context, R.anim.emphasized_decelerate))
                    .start();
        }

        animateEnter(holder, position, context);
    }

    /**
     * 回收回池前重置绑定踪迹与图标变换残留，防止复用后：
     * 1) 残留 scale/alpha 动画中间态；
     * 2) boundTaskName 记账过期导致新位置误触发状态切换动效。
     */
    @Override
    public void onViewRecycled(@NonNull TaskViewHolder holder) {
        holder.boundTaskName = null;
        holder.boundStatus = null;
        holder.statusIcon.setAlpha(1f);
        holder.statusIcon.setScaleX(1f);
        holder.statusIcon.setScaleY(1f);
        super.onViewRecycled(holder);
    }

    /** 终态右侧 chip：设置文案、底色与文字色后显示。
     *  底图统一为 bg_chip（胶囊），fillColor 通过 backgroundTint 给定语义色。 */
    private void showChip(TaskViewHolder holder, int textRes,
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
                // 结束回调里经 holder 反查当前位置记账（不捕获绑定期的 position：
                // 数据刷新后旧位置即失效，RecyclerAdapter 检查亦要求动态反查）
                .withEndAction(() -> {
                    int animatedPosition = holder.getBindingAdapterPosition();
                    if (animatedPosition != RecyclerView.NO_POSITION) animated.add(animatedPosition);
                })
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
        /** 状态圆的透镜（首次绑定时安装，复用期间只换语义描边色）。 */
        LensGlassDrawable statusLens;
        // 状态切换动效记账：上次绑定的任务名与状态（onViewRecycled 时重置）。
        // 用任务名而非 position 做条目身份：position 在数据刷新后会漂移，
        // 存入 holder 跨绑定使用属「把 position 当固定值」的反模式（lint RecyclerView）。
        String boundTaskName = null;
        TaskItem.TaskStatus boundStatus = null;

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
