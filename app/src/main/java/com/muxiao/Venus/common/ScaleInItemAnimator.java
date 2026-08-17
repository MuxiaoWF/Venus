package com.muxiao.Venus.common;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.ViewPropertyAnimator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.muxiao.Venus.R;

/**
 * RecyclerView item 添加动画：从底部淡入并轻微缩放。
 * 时长/缓动取自 M3 Motion Token，并尊重系统「减少动态效果」。
 */
public class ScaleInItemAnimator extends DefaultItemAnimator {

    private final boolean withScale;

    public ScaleInItemAnimator() {
        this(true);
    }

    public ScaleInItemAnimator(boolean withScale) {
        this.withScale = withScale;
    }

    /**
     * 禁用 change 动画：先重置新视图属性避免回收复用残留中间态，再立即结束新旧视图的 change。
     */
    @Override
    public boolean animateChange(@NonNull RecyclerView.ViewHolder holder,
                                 @NonNull RecyclerView.ViewHolder newHolder,
                                 int fromLeft, int fromTop, int toLeft, int toTop) {
        // 重置新视图的属性，防止从回收池复用时残留 alpha=0 等动画中间状态
        newHolder.itemView.setAlpha(1f);
        newHolder.itemView.setScaleX(1f);
        newHolder.itemView.setScaleY(1f);
        newHolder.itemView.setTranslationX(0f);
        newHolder.itemView.setTranslationY(0f);
        // 禁用 change 动画，防止数据更新时的交叉淡入
        dispatchChangeFinished(holder, true);
        dispatchChangeFinished(newHolder, false);
        return false;
    }

    /**
     * 新增 item 从底部淡入（可选缩放），开启「减少动态效果」时直接显示不带动画。
     */
    @Override
    public boolean animateAdd(@NonNull RecyclerView.ViewHolder holder) {
        dispatchAddStarting(holder);

        Context context = holder.itemView.getContext();
        if (tools.isReducedMotionEnabled(context)) {
            holder.itemView.setAlpha(1f);
            holder.itemView.setScaleX(1f);
            holder.itemView.setScaleY(1f);
            holder.itemView.setTranslationY(0f);
            dispatchAddFinished(holder);
            return false;
        }

        int duration = context.getResources().getInteger(R.integer.motion_duration_medium);
        // M3 emphasized-decelerate：cubic-bezier(0.1, 0.7, 0.1, 1.0)
        Interpolator emphasizedDecelerate = AnimationUtils.loadInterpolator(context, R.anim.emphasized_decelerate);

        holder.itemView.setAlpha(0f);
        holder.itemView.setScaleX(withScale ? 0.92f : 1f);
        holder.itemView.setScaleY(withScale ? 0.92f : 1f);
        holder.itemView.setTranslationY(withScale ? 40f : 0f);

        ViewPropertyAnimator addAnim = holder.itemView.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(emphasizedDecelerate);
        if (withScale) {
            addAnim.scaleX(1f).scaleY(1f).translationY(0f);
        }
        addAnim.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                dispatchAddFinished(holder);
            }
        }).start();
        return true;
    }

    /**
     * 移除 item 淡出（可选缩放），开启「减少动态效果」时直接结束不带动画。
     */
    @Override
    public boolean animateRemove(@NonNull RecyclerView.ViewHolder holder) {
        dispatchRemoveStarting(holder);

        Context context = holder.itemView.getContext();
        if (tools.isReducedMotionEnabled(context)) {
            dispatchRemoveFinished(holder);
            return false;
        }

        int duration = context.getResources().getInteger(R.integer.motion_duration_short);
        // M3 emphasized-accelerate：cubic-bezier(0.3, 0, 0.8, 0.2)
        Interpolator emphasizedAccelerate = AnimationUtils.loadInterpolator(context, R.anim.emphasized_accelerate);

        ViewPropertyAnimator removeAnim = holder.itemView.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(emphasizedAccelerate);
        if (withScale) {
            removeAnim.scaleX(0.92f).scaleY(0.92f);
        }
        removeAnim.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                dispatchRemoveFinished(holder);
            }
        }).start();
        return true;
    }
}
