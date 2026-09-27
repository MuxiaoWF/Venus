package com.muxiao.Venus.common.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

/**
 * 底栏玻璃的内容条带采样器：把底栏身后的<b>真实内容</b>离屏采样进透镜采样源。
 *
 * <p>内容避让拆除后，滚动的卡片/列表会真实滚到底栏玻璃下方——若采样源仍只有
 * 壁纸，玻璃会「透出不存在的壁纸」，卡片在上边缘被硬切（读作遮挡）。本采样器
 * 把底栏附近条带（栏体 + 折射外推余量）从内容容器离屏绘制出来，经
 * {@link GlassBackdrop#publishStrip} 与壁纸小样合成，玻璃折射的即是真实的
 * 身后内容——对应 Kyant0/AndroidLiquidGlass 的 LayerBackdrop 采样「玻璃身后的层」。</p>
 *
 * <p>节流：滚动/布局变化合并为一次条带重采样，静止不采样；条带只覆盖底栏附近
 * 区域（全宽 × 栏高 + 外推余量）且 1/2 尺度，单次成本可控。调度用 {@link
 * Choreographer} 帧对齐：动画阶段回调先于本帧 traversal（draw）执行，采样 →
 * 发布 → 透镜失效全部落在绘制之前，同一帧玻璃即取到当前帧内容——零滞后、
 * 零漂移；此前 Handler.postDelayed 定时与 vsync 不对齐，采样常落在本帧绘制
 * 之后（生效延后一帧以上且间隔抖动），页面切换时玻璃内容走走停停、收尾追帧
 * 跳跃。</p>
 *
 * <p>递归防护：离屏绘制期间 {@link LensGlassDrawable#setCaptureActive} 置位，
 * 树内嵌套小透镜（状态圈/玻璃药丸/图标）一律画静态回退底——玻璃采样玻璃会
 * 自引用，且嵌套折射在离屏路径上成本爆炸。</p>
 */
public final class ContentStripSampler {

    /** 条带重采样基准间隔（ms）：与帧率同频（60fps）。调度帧对齐（Choreographer
     *  动画阶段先于本帧 draw），采样→发布→绘制同帧完成，玻璃内容零滞后。
     *  主页等轻页面单次离屏绘制 2~4ms，可承受逐帧采样；重页面由自适应退避
     *  自动拉大间隔（过渡期上限 48ms，静止期上限 200ms）。 */
    private static final long CAPTURE_INTERVAL_MS = 16;
    /** 自适应退避上限（ms）：离屏绘制成本高的页面（软件路径全树遍历，设置页可达
     *  50~150ms/次）按实测成本倍增间隔，最低降到 5fps——掉帧换采样时延，保滚动流畅。 */
    private static final long MAX_INTERVAL_MS = 200;
    /** 单次离屏绘制预算（ms）：超过一个标准帧时长即触发退避。 */
    private static final long CAPTURE_BUDGET_MS = 14;
    /** 标准帧周期（ms）：帧对齐调度用——delay = 采样间隔 − 帧周期，间隔恰为一帧时贴下一帧。 */
    private static final long FRAME_PERIOD_MS = 16;
    /** 折射带外推量（dp）：与底栏透镜 panel 口径 lensAmount（MainActivity 传 34f）一致。 */
    private static final float LENS_AMOUNT_DP = 34f;
    /** 条带顶边在折射外推量之上的安全余量（dp），覆盖取景对齐的亚像素误差。 */
    private static final float SAFETY_DP = 8f;
    /** 条带降采样系数：与壁纸小样 1/2 尺度对齐，合成时不再被二次压缩，AGSL 放大后文字可辨。 */
    private static final float CAPTURE_SCALE = 0.5f;
    /** 过渡期自适应退避上限（ms）：过渡要跟踪动画，但高成本页面（设置页全树遍历
     *  50~150ms/次）逐帧采样会把切换动画本身拖出掉帧——按实测成本退避，上限
     *  48ms 保证动画期间仍以 ≥20fps 跟踪；静止期维持 200ms 深退避。 */
    private static final long MAX_TRANSITION_INTERVAL_MS = 48;
    /** 过渡静默窗口（ms）：最后一个滚动/布局事件后经过该时长仍无事件，判定过渡结束。
     *  平滑切换由 SCROLL_STATE_IDLE 即时收尾；instant 切换（smoothScroll=false）
     *  无 IDLE 事件，靠本窗口兜底——比固定超时精确得多。 */
    private static final long TRANSITION_QUIET_MS = 80;

    private final View content;
    private final View overlayBar;
    /** 静默窗口等非帧节奏任务仍走 Handler；采样调度走 Choreographer（帧对齐）。 */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    /**
     * 帧对齐采样回调：Choreographer 动画阶段先于本帧 traversal 执行——采样 →
     * publishStrip（合成+磨砂重建）→ 透镜 invalidate 全部落在绘制之前，本帧
     * draw 即取到当前帧内容。此前 Handler 定时采样与 vsync 无对齐，常落在
     * 本帧绘制之后（生效延后 1~2 帧且间隔漂移），切页时玻璃走走停停、收尾跳跃。
     */
    private final Choreographer.FrameCallback captureFrameCallback = frameTimeNanos -> capture();

    /** 当前生效的采样间隔（ms）：自适应退避在基准与上限之间浮动。 */
    private long currentIntervalMs = CAPTURE_INTERVAL_MS;
    /** 节流标志：已有待执行的采样任务时，后续事件不再重置延迟——否则持续滚动中
     *  回调被 remove 不断推迟，采样只会在事件流停止后执行（「松手才刷新」）。 */
    private boolean capturePending;
    /** 页面切换过渡中（ViewPager2 DRAGGING/SETTLING 或 instant 切换的超时窗口内）：
     *  内容在真实滑动，退避让位于跟踪——退避上限收紧到 MAX_TRANSITION_INTERVAL_MS。 */
    private boolean transitionActive;
    /** 过渡收尾兜底任务：instant 切换无 IDLE 事件时按超时结束过渡状态。 */
    private final Runnable transitionEndRunnable = this::onTransitionEnd;

    /**
     * 帧对齐调度一次采样：delay 为相对「下一帧」的额外等待（0 = 贴下一帧）。
     * 采样间隔换算：delay = interval − 帧周期（间隔恰为一帧时逐帧采样）。
     */
    private void postCapture(long intervalMs) {
        if (capturePending) return;
        capturePending = true;
        long delay = Math.max(0, intervalMs - FRAME_PERIOD_MS);
        if (delay == 0) choreographer.postFrameCallback(captureFrameCallback);
        else choreographer.postFrameCallbackDelayed(captureFrameCallback, delay);
    }

    /** 撤销已排期的采样任务（Choreographer 回调 + 节流标志一并复位）。 */
    private void cancelCapture() {
        choreographer.removeFrameCallback(captureFrameCallback);
        capturePending = false;
    }

    /** 条带图像与其画布（原地复用，尺寸变化才重建）。 */
    private Bitmap strip;
    private Canvas stripCanvas;

    public ContentStripSampler(@NonNull View content, @NonNull View overlayBar) {
        this.content = content;
        this.overlayBar = overlayBar;
    }

    /** 启动监听：内容树内任意滚动 / 内容布局变化 → 节流重采样。 */
    public void start() {
        ViewTreeObserver.OnScrollChangedListener scrollListener = this::schedule;
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
                cancelCapture();
            }
        });
        if (content.isAttachedToWindow())
            content.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        content.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> schedule());
    }

    /** 外部失效（壁纸重新发布、底栏位置变化）→ 重新采样。 */
    public void invalidate() {
        schedule();
    }

    /** 页面切换开始（MainActivity 的 OnPageChangeCallback 接线）：玻璃贴帧跟踪滑动
     *  内容。退避间隔复位到基准——静止期可能带着上一页的深退避（最长 200ms）进入
     *  过渡，不复位则切换前几帧跟踪严重滞后。 */
    public void onTransitionStart() {
        transitionActive = true;
        currentIntervalMs = CAPTURE_INTERVAL_MS;
        handler.removeCallbacks(transitionEndRunnable);
        handler.postDelayed(transitionEndRunnable, TRANSITION_QUIET_MS);
        // 立即采一帧（贴下一帧）：onPageSelected 早于布局事件，若已有挂起的退避
        // 采样任务（最长 200ms），切换第一帧的跟踪会被它拖住
        cancelCapture();
        postCapture(CAPTURE_INTERVAL_MS);
    }

    /** 页面切换结束（SCROLL_STATE_IDLE 或兜底超时）：回基准间隔并立即补采——
     *  动画落定的同一时刻玻璃就对齐真实内容，不再滞留旧页面条带。 */
    public void onTransitionEnd() {
        if (!transitionActive) return;
        transitionActive = false;
        handler.removeCallbacks(transitionEndRunnable);
        // 退避间隔按「上一页的采样成本」调出，对新页面无效：直接回基准
        currentIntervalMs = CAPTURE_INTERVAL_MS;
        cancelCapture();
        capture();
        // 兜底补采：Fragment 视图 inflate / RecyclerView 异步回填可能晚于 IDLE 一两帧
        postCapture(CAPTURE_INTERVAL_MS * 2);
    }

    /** 节流调度：滚动/布局事件合并为一次条带重采样（UI 线程），帧对齐执行；
     *  过渡期与静止期共用自适应退避间隔（过渡上限更紧，见 MAX_TRANSITION_INTERVAL_MS）。 */
    private void schedule() {
        if (capturePending) return;
        postCapture(currentIntervalMs);
        if (transitionActive) {
            // 过渡中每个滚动/布局事件都重置静默窗口：事件停止 + 静默期 → 过渡结束
            // （平滑切换实际由 SCROLL_STATE_IDLE 先行收尾，此处主要服务 instant 切换）
            handler.removeCallbacks(transitionEndRunnable);
            handler.postDelayed(transitionEndRunnable, TRANSITION_QUIET_MS);
        }
    }

    /** 条带重采样（UI 线程）：离屏绘制内容条带 → 发布给 GlassBackdrop 合成。 */
    private void capture() {
        capturePending = false;
        // 坐标锚：有背景图为 background_image（centerCrop 取景），纯色基底模式
        // （无背景图）为铺满窗口的内容视图（GlassBackdrop.setSolidSurface 发布）
        View backdrop = GlassBackdrop.getBackdropView();
        if (backdrop == null || backdrop.getVisibility() != View.VISIBLE
                || backdrop.getWidth() == 0 || GlassBackdrop.getBitmap() == null
                || content.getWidth() == 0 || content.getHeight() == 0
                || overlayBar.getHeight() == 0
                || !content.isAttachedToWindow())
            return;

        int[] contentLoc = new int[2];
        int[] barLoc = new int[2];
        content.getLocationInWindow(contentLoc);
        overlayBar.getLocationInWindow(barLoc);
        float density = content.getResources().getDisplayMetrics().density;

        // 条带窗口矩形：全宽，从栏顶上方「折射外推量 + 安全余量」处到内容底（屏幕底）
        int stripTop = barLoc[1] - Math.round((LENS_AMOUNT_DP + SAFETY_DP) * density);
        Rect windowRect = new Rect(contentLoc[0], stripTop,
                contentLoc[0] + content.getWidth(), contentLoc[1] + content.getHeight());

        int stripW = Math.max(1, Math.round(windowRect.width() * CAPTURE_SCALE));
        int stripH = Math.max(1, Math.round(windowRect.height() * CAPTURE_SCALE));
        if (strip == null || strip.getWidth() != stripW || strip.getHeight() != stripH) {
            if (strip != null) strip.recycle();
            strip = Bitmap.createBitmap(stripW, stripH, Bitmap.Config.ARGB_8888);
            stripCanvas = new Canvas(strip);
        } else {
            strip.eraseColor(Color.TRANSPARENT);
        }

        // 窗口坐标 → 内容本地坐标（viewPager 通常位于窗口原点，但按实际偏移对齐）。
        // 每次采样前重置矩阵再 save：位图复用路径下 scale/translate 若叠加旧矩阵，
        // 第二次起画面会错位缩放；restore 只弹变换栈，不影响已绘制内容。
        int localLeft = windowRect.left - contentLoc[0];
        int localTop = windowRect.top - contentLoc[1];
        stripCanvas.setMatrix(null);
        stripCanvas.save();
        stripCanvas.scale(CAPTURE_SCALE, CAPTURE_SCALE);
        stripCanvas.translate(-localLeft, -localTop);
        stripCanvas.clipRect(localLeft, localTop,
                localLeft + windowRect.width(), localTop + windowRect.height());

        LensGlassDrawable.setCaptureActive(true);
        long costMs;
        try {
            // uptimeMillis 而非 uptimeNanos：后者 API 29+，minSdk 23 会 NoSuchMethodError
            long t0 = android.os.SystemClock.uptimeMillis();
            android.os.Trace.beginSection("strip.capture");
            content.draw(stripCanvas);
            android.os.Trace.endSection();
            costMs = android.os.SystemClock.uptimeMillis() - t0;
        } finally {
            stripCanvas.restore();
            LensGlassDrawable.setCaptureActive(false);
        }
        GlassBackdrop.publishStrip(strip, windowRect);

        // 自适应退避：离屏绘制是软件路径全树遍历，成本随页面树规模变化巨大
        // （RecyclerView 页 ~几 ms，设置页 NestedScrollView 可达 50~150ms/次）。
        // 超预算倍增间隔；过渡期上限收紧到 48ms——逐帧采样高成本页面会把切换
        // 动画本身拖出掉帧，48ms 保证动画期间 ≥20fps 跟踪；静止期上限 200ms。
        // 明显低于预算则逐级回落到基准 16ms。
        long cap = transitionActive ? MAX_TRANSITION_INTERVAL_MS : MAX_INTERVAL_MS;
        if (costMs > CAPTURE_BUDGET_MS) {
            if (currentIntervalMs < cap) {
                currentIntervalMs = Math.min(currentIntervalMs * 2, cap);
            }
        } else if (costMs < CAPTURE_BUDGET_MS / 2 && currentIntervalMs > CAPTURE_INTERVAL_MS) {
            currentIntervalMs = Math.max(CAPTURE_INTERVAL_MS, currentIntervalMs / 2);
        }
    }
}
