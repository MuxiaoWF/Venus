package com.muxiao.Venus.common;

import android.animation.AnimatorListenerAdapter;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView item 添加动画：从底部淡入并轻微缩放
 */
public class ScaleInItemAnimator extends DefaultItemAnimator {

    public ScaleInItemAnimator() {
        setAddDuration(300);
        setRemoveDuration(200);
        setMoveDuration(300);
        setChangeDuration(250);
    }

    @Override
    public boolean animateAdd(@NonNull RecyclerView.ViewHolder holder) {
        dispatchAddStarting(holder);

        holder.itemView.setAlpha(0f);
        holder.itemView.setScaleX(0.92f);
        holder.itemView.setScaleY(0.92f);
        holder.itemView.setTranslationY(40f);

        holder.itemView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(getAddDuration())
                .setInterpolator(AnimationUtils.loadInterpolator(
                        holder.itemView.getContext(),
                        android.R.interpolator.fast_out_slow_in))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        dispatchAddFinished(holder);
                    }
                })
                .start();
        return true;
    }

    @Override
    public boolean animateRemove(@NonNull RecyclerView.ViewHolder holder) {
        dispatchRemoveStarting(holder);

        holder.itemView.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(getRemoveDuration())
                .setInterpolator(AnimationUtils.loadInterpolator(
                        holder.itemView.getContext(),
                        android.R.interpolator.fast_out_linear_in))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        dispatchRemoveFinished(holder);
                    }
                })
                .start();
        return true;
    }
}
