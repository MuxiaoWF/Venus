package com.muxiao.Venus.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;

/**
 * 可折叠卡片视图
 */
public class CollapsibleCardView extends FrameLayout {

    private ImageView toggleIcon;
    private ViewGroup contentLayout;
    private boolean isExpanded = true;
    // 完全展开时的高度
    private int expandedHeight = -1;
    private ValueAnimator heightAnimator;

    /**
     * 构造函数，用于代码创建实例
     *
     * @param context 上下文环境
     */
    public CollapsibleCardView(@NonNull Context context) {
        super(context);
        init(null);
    }

    /**
     * 构造函数，用于XML布局中使用
     *
     * @param context 上下文环境
     * @param attrs   XML属性集合
     */
    public CollapsibleCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    /**
     * 构造函数，带默认样式
     *
     * @param context      上下文环境
     * @param attrs        XML属性集合
     * @param defStyleAttr 默认样式属性
     */
    public CollapsibleCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    /**
     * 初始化方法，设置视图和属性
     *
     * @param attrs XML属性集合，可为null
     */
    private void init(@Nullable AttributeSet attrs) {
        LayoutInflater.from(getContext()).inflate(R.layout.style_collapsible_card_view, this, true);

        MaterialTextView titleText = findViewById(R.id.title_text);
        toggleIcon = findViewById(R.id.toggle_icon);
        contentLayout = findViewById(R.id.content_layout);

        if (attrs != null) {
            try (TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CollapsibleCardView)) {
                String title = a.getString(R.styleable.CollapsibleCardView_cardTitle);
                if (title != null)
                    titleText.setText(title);
                // 初始状态的处理
                this.isExpanded = a.getBoolean(R.styleable.CollapsibleCardView_initialExpanded, true);
            }
        }

        toggleIcon.setImageResource(R.drawable.ic_0collapse_180expand);
        toggleIcon.setOnClickListener(v -> toggle());

        // 在layout完成后获取真实高度
        contentLayout.getViewTreeObserver()
                .addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        contentLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        expandedHeight = contentLayout.getHeight();
                        if (!isExpanded) {
                            setContentHeight(0);
                            contentLayout.setVisibility(GONE);
                            toggleIcon.setRotation(180f);
                        } else {
                            toggleIcon.setRotation(0f);
                        }
                    }
                });
    }

    /**
     * 获取内容布局容器
     *
     * @return 内容布局的ViewGroup对象
     */
    public ViewGroup getContentLayout() {
        return contentLayout;
    }

    /**
     * 设置内容视图
     *
     * @param contentView 要显示的内容视图
     */
    public void setContent(View contentView) {
        contentLayout.removeAllViews();
        if (contentView.getParent() != null)
            // 如果视图已经有父视图，先从父视图中移除
            ((ViewGroup) contentView.getParent()).removeView(contentView);
        contentLayout.addView(contentView);
        requestRecalculateHeight();
    }

    /**
     * 通过布局资源ID设置内容
     *
     * @param layoutResId 布局资源ID
     */
    public void setContent(int layoutResId) {
        contentLayout.removeAllViews();
        LayoutInflater.from(getContext()).inflate(layoutResId, contentLayout, true);
        requestRecalculateHeight();
    }

    /**
     * 获取当前内容区域是否已展开
     *
     * @return true表示已展开，false表示已折叠
     */
    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * 切换内容区域为展开或折叠
     */
    public void toggle() {
        if (isExpanded) collapse();
        else expand();
    }

    /**
     * 展开内容区域
     */
    public void expand() {
        if (isExpanded) return;

        cancelHeightAnim();

        contentLayout.setVisibility(View.VISIBLE);
        animateHeight(getCurrentHeight(), expandedHeight, true);
        animateToggleIcon(0f);
        isExpanded = true;
    }

    /**
     * 收起内容区域
     */
    public void collapse() {
        if (!isExpanded) return;

        cancelHeightAnim();

        animateHeight(getCurrentHeight(), 0, false);
        animateToggleIcon(180f);
        isExpanded = false;
    }

    /**
     * 取得当前内容区域的高度
     */
    private int getCurrentHeight() {
        ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
        return lp.height >= 0 ? lp.height : contentLayout.getHeight();
    }

    /**
     * 设置内容高度
     */
    private void setContentHeight(int height) {
        ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
        lp.height = height;
        contentLayout.setLayoutParams(lp);
    }

    /**
     * 内容高度动画
     *
     * @param start     起始高度
     * @param end       目标高度
     * @param expanding 是否正在展开
     */
    private void animateHeight(int start, int end, boolean expanding) {
        heightAnimator = ValueAnimator.ofInt(start, end);
        heightAnimator.setDuration(300).setInterpolator(new AccelerateDecelerateInterpolator());

        heightAnimator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            setContentHeight(value);
        });

        heightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!expanding)
                    contentLayout.setVisibility(View.GONE);
            }
        });

        heightAnimator.start();
    }

    /**
     * 取消高度动画
     */
    private void cancelHeightAnim() {
        if (heightAnimator != null && heightAnimator.isRunning())
            heightAnimator.cancel();
    }

    /**
     * 绘制旋转图标
     */
    private void animateToggleIcon(float rotation) {
        toggleIcon.animate()
                .rotation(rotation)
                .setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /**
     * 当内容变化 / 屏幕变化时重新计算展开高度
     */
    private void requestRecalculateHeight() {
        expandedHeight = -1;
        contentLayout.post(() -> expandedHeight = contentLayout.getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw)
            requestRecalculateHeight();
    }
}
