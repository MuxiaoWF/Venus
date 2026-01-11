package com.muxiao.Venus.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;

/**
 * 可折叠卡片视图类
 * 提供可展开/收起的内容区域
 */
public class CollapsibleCardView extends FrameLayout {

    private MaterialTextView titleText; // 标题文本
    private ImageView toggleIcon; // 切换图标视图
    private ViewGroup contentLayout; // 内容布局容器
    private boolean isExpanded = true; // 是否处于展开状态

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
    private void init(AttributeSet attrs) {
        LayoutInflater.from(getContext()).inflate(R.layout.style_collapsible_card_view, this, true);

        titleText = findViewById(R.id.title_text);
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

        toggleIcon.setOnClickListener(v -> toggle());
        // 由属性控制初始展开/折叠
        if (!isExpanded) {
            contentLayout.setVisibility(View.GONE);
            toggleIcon.setImageResource(R.drawable.ic_expand);
        } else {
            toggleIcon.setImageResource(R.drawable.ic_collapse);
        }
    }

    /**
     * 设置卡片标题
     *
     * @param title 标题文本
     */
    public void setTitle(String title) {
        titleText.setText(title);
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
        if (contentView.getParent() != null) {
            // 如果视图已经有父视图，先从父视图中移除
            ((ViewGroup) contentView.getParent()).removeView(contentView);
        }
        contentLayout.addView(contentView);
    }

    /**
     * 通过布局资源ID设置内容
     *
     * @param layoutResId 布局资源ID
     */
    public void setContent(int layoutResId) {
        contentLayout.removeAllViews();
        LayoutInflater.from(getContext()).inflate(layoutResId, contentLayout, true);
    }

    /**
     * 展开内容区域
     */
    public void expand() {
        if (isExpanded) return;

        // 延迟到有宽度时再执行（防止 getWidth()==0）
        if (getWidth() == 0) {
            post(this::expand);
            return;
        }

        contentLayout.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, getWidth()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        // 立即把高度设为 0，确保不会把内容瞬间显示完整
        ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
        lp.height = 0;
        contentLayout.setLayoutParams(lp);
        contentLayout.setVisibility(View.VISIBLE);
        // 开始动画到 targetHeight
        animateHeight(0, contentLayout.getMeasuredHeight());
        toggleIcon.setImageResource(R.drawable.ic_collapse);
        isExpanded = true;
    }

    /**
     * 收起内容区域
     */
    public void collapse() {
        if (!isExpanded) return;

        int initialHeight = contentLayout.getHeight();
        animateHeight(initialHeight, 0);
        toggleIcon.setImageResource(R.drawable.ic_expand);
        isExpanded = false;
    }

    /**
     * 切换展开/收起状态
     */
    public void toggle() {
        if (isExpanded)
            collapse();
        else
            expand();
    }

    /**
     * 获取当前展开状态
     *
     * @return true表示已展开，false表示已收起
     */
    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * 高度变化动画
     *
     * @param startHeight 起始高度
     * @param endHeight   结束高度
     */
    private void animateHeight(int startHeight, int endHeight) {
        final boolean expanding = endHeight > 0;

        Animation animation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int newHeight = (int) (startHeight + (endHeight - startHeight) * interpolatedTime);
                ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
                lp.height = newHeight;
                contentLayout.setLayoutParams(lp);
            }
        };

        animation.setDuration(300);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // 动画结束时修正为 WRAP_CONTENT 或 GONE
                ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                contentLayout.setLayoutParams(lp);

                if (!expanding) {
                    contentLayout.setVisibility(View.GONE);
                } else {
                    contentLayout.setVisibility(View.VISIBLE);
                }
                contentLayout.clearAnimation();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        contentLayout.startAnimation(animation);
    }
}
