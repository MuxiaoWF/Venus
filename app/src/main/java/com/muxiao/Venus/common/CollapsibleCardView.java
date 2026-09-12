package com.muxiao.Venus.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
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
     * 代码创建时用构造，委托 {@link #init(AttributeSet)}（attrs 为 null）完成视图初始化。
     */
    public CollapsibleCardView(@NonNull Context context) {
        super(context);
        init(null);
    }

    /**
     * XML 布局 inflate 时用构造，attrs 会经 {@link #init(AttributeSet)} 读取 cardTitle / initialExpanded。
     */
    public CollapsibleCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    /**
     * 带默认样式的 XML 构造，转发给 {@link #init(AttributeSet)}。
     */
    public CollapsibleCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    /**
     * 统一初始化：inflate 布局、读取 cardTitle/initialExpanded 属性、绑定头部点击切换，
     * 并在首次 layout 完成后测量展开高度，按 initialExpanded（默认 false）决定初始折叠或展开。
     *
     * @param attrs XML属性集合，代码创建时为 null（使用默认标题与默认折叠）
     */
    private void init(@Nullable AttributeSet attrs) {
        LayoutInflater.from(getContext()).inflate(R.layout.style_collapsible_card_view, this, true);

        MaterialTextView titleText = findViewById(R.id.title_text);
        toggleIcon = findViewById(R.id.toggle_icon);
        contentLayout = findViewById(R.id.content_layout);
        View headerContainer = findViewById(R.id.header_container);

        if (attrs != null) {
            try (TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CollapsibleCardView)) {
                String title = a.getString(R.styleable.CollapsibleCardView_cardTitle);
                if (title != null)
                    titleText.setText(title);
                String value = a.getString(R.styleable.CollapsibleCardView_cardValue);
                if (value != null)
                    setValue(value);
                // 初始状态的处理（默认折叠：全部 13 处调用均传 false，故默认改为 false 以收敛样板）
                this.isExpanded = a.getBoolean(R.styleable.CollapsibleCardView_initialExpanded, false);
                // 透明变体：移除内部 MaterialCardView 的背景与描边，仅保留折叠交互。
                // 用于 Settings List 组内行，避免双重描边（分组外壳已承载边界）。
                boolean transparent = a.getBoolean(R.styleable.CollapsibleCardView_transparent, false);
                if (transparent && getChildCount() > 0
                        && getChildAt(0) instanceof com.google.android.material.card.MaterialCardView) {
                    com.google.android.material.card.MaterialCardView inner =
                            (com.google.android.material.card.MaterialCardView) getChildAt(0);
                    inner.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                    inner.setStrokeWidth(0);
                    // 二级关系：内容整体从父行标题缩进（insetHorizontal = 引导线位置）。
                    // 上下内边距交给各内容布局自带 padding（12dp），避免与 Rail 起止错位叠加；
                    // 展开内容左侧引导线（bg_drawer_rail）表达"从属于父行"的层级。
                    int inset = a.getDimensionPixelSize(R.styleable.CollapsibleCardView_insetHorizontal, 0);
                    if (contentLayout != null) {
                        contentLayout.setPadding(inset, 0, 0, 0);
                        // 命令台层级：展开内容左侧引导线，表达"从属于父行"
                        if (inset > 0) {
                            android.graphics.drawable.Drawable rail =
                                    androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.bg_drawer_rail);
                            if (rail != null)
                                contentLayout.setBackground(
                                        new android.graphics.drawable.InsetDrawable(rail, inset, 0, 0, 0));
                        }
                    }
                }
            }
        }

        toggleIcon.setImageResource(R.drawable.ic_0collapse_180expand);
        toggleIcon.setOnClickListener(v -> toggle());
        if (headerContainer != null)
            headerContainer.setOnClickListener(v -> toggle());

        // 在layout完成后获取真实高度
        contentLayout.getViewTreeObserver()
                .addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        contentLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        expandedHeight = measureContentHeight();
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
     * 返回内容容器，外部可向其中 addView 注入自定义内容视图。
     */
    public ViewGroup getContentLayout() {
        return contentLayout;
    }

    /**
     * 设置标题行右侧的 mono 值摘要（如「已开启」「4/5 已开启」「国服」）。
     */
    public void setValue(CharSequence value) {
        MaterialTextView valueText = findViewById(R.id.card_value);
        if (valueText != null)
            valueText.setText(value);
    }

    /**
     * 设置值摘要颜色（如「已开启」用语义成功色）；传 0 恢复默认次要色。
     */
    public void setValueColor(int color) {
        MaterialTextView valueText = findViewById(R.id.card_value);
        if (valueText == null) return;
        if (color != 0)
            valueText.setTextColor(color);
        else
            valueText.setTextColor(
                    MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575));
    }

    /**
     * 用布局资源 ID inflate 的视图替换内容区，随后触发展开高度重算。
     */
    public void setContent(int layoutResId) {
        contentLayout.removeAllViews();
        LayoutInflater.from(getContext()).inflate(layoutResId, contentLayout, true);
        requestRecalculateHeight();
    }

    /**
     * 在展开与折叠之间切换（已是目标状态时无操作）。
     */
    public void toggle() {
        if (isExpanded) collapse();
        else expand();
    }

    /**
     * 将内容区动画展开到已测量的展开高度；已展开时直接返回。
     */
    public void expand() {
        if (isExpanded) return;

        cancelHeightAnim();

        contentLayout.setVisibility(View.VISIBLE);
        if (expandedHeight <= 0)
            expandedHeight = measureContentHeight();
        animateHeight(getCurrentHeight(), expandedHeight, true);
        animateToggleIcon(0f);
        isExpanded = true;
    }

    /**
     * 将内容区动画收起至高度 0 并隐藏；已折叠时直接返回。
     */
    public void collapse() {
        if (!isExpanded) return;

        cancelHeightAnim();

        animateHeight(getCurrentHeight(), 0, false);
        animateToggleIcon(180f);
        isExpanded = false;
    }

    /**
     * 读取当前内容高度：LayoutParams.height >= 0 时直接采用，否则回退到实测高度。
     */
    private int getCurrentHeight() {
        ViewGroup.LayoutParams lp = contentLayout.getLayoutParams();
        return lp.height >= 0 ? lp.height : contentLayout.getHeight();
    }

    /**
     * 通过修改 LayoutParams.height 设置内容区高度（配合 requestLayout 触发动画重绘）。
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
        // 尊重系统「减少动态效果」：动画关闭时直接切换状态
        if (isAnimationDisabled()) {
            setContentHeight(expanding ? expandedHeight : 0);
            contentLayout.setVisibility(expanding ? View.VISIBLE : View.GONE);
            contentLayout.setAlpha(1f);
            toggleIcon.setRotation(expanding ? 0f : 180f);
            requestLayout();
            if (expanding)
                post(() -> requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, getWidth(), getHeight()), true));
            return;
        }

        int duration = getContext().getResources().getInteger(R.integer.motion_duration_medium);
        // M3 emphasized-decelerate 缓动：cubic-bezier(0.1, 0.7, 0.1, 1.0)
        Interpolator emphasizedDecelerate = AnimationUtils.loadInterpolator(getContext(), R.anim.emphasized_decelerate);

        heightAnimator = ValueAnimator.ofInt(start, end);
        heightAnimator.setDuration(duration);
        heightAnimator.setInterpolator(emphasizedDecelerate);

        // 仅做高度动画，去掉冗余的 alpha 渐显（高度展开即已揭示内容）
        heightAnimator.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            setContentHeight(value);
            requestLayout();
        });

        heightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!expanding) {
                    contentLayout.setVisibility(View.GONE);
                    contentLayout.setAlpha(1f);
                } else {
                    // 展开完成后请求滚动父容器把整卡带入视口：
                    // 修复设置页底部行（如“主题”）展开后内容落在视口外、被底栏遮挡的问题。
                    post(() -> requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, getWidth(), getHeight()), true));
                }
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
     * 将折叠箭头旋转到指定角度（0=展开、180=折叠）；开启「减少动态效果」时直接跳变不带动画。
     */
    private void animateToggleIcon(float rotation) {
        if (isAnimationDisabled()) {
            toggleIcon.setRotation(rotation);
            return;
        }
        toggleIcon.animate()
                .rotation(rotation)
                .setDuration(getContext().getResources().getInteger(R.integer.motion_duration_medium))
                .setInterpolator(AnimationUtils.loadInterpolator(getContext(), R.anim.emphasized_decelerate))
                .start();
    }

    /**
     * 当内容变化 / 屏幕变化时重新计算展开高度
     */
    private void requestRecalculateHeight() {
        expandedHeight = -1;
        contentLayout.post(() -> {
            expandedHeight = measureContentHeight();
            requestLayout();
        });
    }

    /**
     * 测量内容区的完整（展开态）高度。
     * 不能在折叠后直接读 getHeight()——此时 LayoutParams 高度为 0，
     * 会导致下次展开动画高度为 0 的 bug。
     * 宽度需取可靠来源：contentLayout 自身宽度优先，折叠（GONE/高度0）时可能为 0，
     * 回退到本 View 宽度，再不行用屏幕宽度；并扣除 contentLayout 左右 padding，
     * 否则分段按钮/RadioGroup 等横向 wrap 内容会被压成 0 宽、高度算小导致显示不全。
     */
    private int measureContentHeight() {
        if (contentLayout.getChildCount() == 0)
            return 0;
        View content = contentLayout.getChildAt(0);
        int width = contentLayout.getWidth();
        if (width <= 0) width = getWidth();
        if (width <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            width = dm.widthPixels;
        }
        // 扣除 contentLayout 自身的左右 padding，得到子 View 真实可用宽度
        width -= contentLayout.getPaddingLeft() + contentLayout.getPaddingRight();
        if (width < 0) width = 0;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(widthSpec, heightSpec);
        // 必须加上 contentLayout 自身的上下 padding，否则展开高度不含 padding，
        // 内容底部会被 contentLayout 的 bottom padding 挤出可视区域（显示不全）。
        return content.getMeasuredHeight()
                + contentLayout.getPaddingTop() + contentLayout.getPaddingBottom();
    }

    /**
     * 系统「减少动态效果」检测（动画时长缩放为 0 时视为关闭）
     */
    private boolean isAnimationDisabled() {
        try {
            float animatorScale = Settings.Global.getFloat(
                    getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            float transitionScale = Settings.Global.getFloat(
                    getContext().getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            return animatorScale == 0f || transitionScale == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 宽度变化（如旋转屏幕）后重新测量展开高度，避免动画使用过时尺寸。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw)
            requestRecalculateHeight();
    }
}
