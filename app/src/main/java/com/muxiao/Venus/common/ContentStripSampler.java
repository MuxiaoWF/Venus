package com.muxiao.Venus.common;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

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
 * <p>节流：滚动/布局变化合并为 {@link #CAPTURE_INTERVAL_MS} 一次的条带重采样，
 * 静止不采样；条带只覆盖底栏附近区域（全宽 × 栏高 + 外推余量）且 1/2 尺度，
 * 单次成本可控。</p>
 *
 * <p>递归防护：离屏绘制期间 {@link LensGlassDrawable#setCaptureActive} 置位，
 * 树内嵌套小透镜（状态圈/玻璃药丸/图标）一律画静态回退底——玻璃采样玻璃会
 * 自引用，且嵌套折射在离屏路径上成本爆炸。</p>
 */
public final class ContentStripSampler {

    /** 条带重采样基准间隔（ms）：与帧率同频（60fps），滚动中玻璃内容跟踪的
     *  时延下限由此决定（发布+下一帧绘制各占一帧）。主页等轻页面单次离屏
     *  绘制 2~4ms，可承受逐帧采样；重页面由自适应退避自动拉大间隔。 */
    private static final long CAPTURE_INTERVAL_MS = 16;
    /** 自适应退避上限（ms）：离屏绘制成本高的页面（软件路径全树遍历，设置页可达
     *  50~150ms/次）按实测成本倍增间隔，最低降到 5fps——掉帧换采样时延，保滚动流畅。 */
    private static final long MAX_INTERVAL_MS = 200;
    /** 单次离屏绘制预算（ms）：超过一个标准帧时长即触发退避。 */
    private static final long CAPTURE_BUDGET_MS = 14;
    /** 折射带外推量（dp）：与底栏透镜 panel 口径 lensAmount（MainActivity 传 34f）一致。 */
    private static final float LENS_AMOUNT_DP = 34f;
    /** 条带顶边在折射外推量之上的安全余量（dp），覆盖取景对齐的亚像素误差。 */
    private static final float SAFETY_DP = 8f;
    /** 条带降采样系数：与壁纸小样 1/2 尺度对齐，合成时不再被二次压缩，AGSL 放大后文字可辨。 */
    private static final float CAPTURE_SCALE = 0.5f;
    /** 页面切换过渡期的采样间隔（ms）：动画仅几百 ms，用最高频率跟踪真实滑动内容。 */
    private static final long TRANSITION_INTERVAL_MS = 16;
    /** 过渡静默窗口（ms）：最后一个滚动/布局事件后经过该时长仍无事件，判定过渡结束。
     *  平滑切换由 SCROLL_STATE_IDLE 即时收尾；instant 切换（smoothScroll=false）
     *  无 IDLE 事件，靠本窗口兜底——比固定超时精确得多。 */
    private static final long TRANSITION_QUIET_MS = 80;

    private final View content;
    private final View overlayBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable captureRunnable = this::capture;

    private Bitmap strip;
    private Canvas stripCanvas;
    /** 当前生效的采样间隔（ms）：自适应退避在基准与上限之间浮动。 */
    private long currentIntervalMs = CAPTURE_INTERVAL_MS;
    /** 节流标志：已有待执行的采样任务时，后续事件不再重置延迟——否则持续滚动中
     *  回调被 removeCallbacks 不断推迟，采样只会在事件流停止后执行（「松手才刷新」）。 */
    private boolean capturePending;
    /** 页面切换过渡中（ViewPager2 DRAGGING/SETTLING 或 instant 切换的超时窗口内）：
     *  内容在真实滑动，退避让位于跟踪——固定最高频率采样。 */
    private boolean transitionActive;
    /** 过渡收尾兜底任务：instant 切换无 IDLE 事件时按超时结束过渡状态。 */
    private final Runnable transitionEndRunnable = this::onTransitionEnd;

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
                handler.removeCallbacks(captureRunnable);
                capturePending = false;
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

    /** 页面切换开始（MainActivity 的 OnPageChangeCallback 接线）：玻璃改用最高频率
     *  跟踪滑动内容，避免条带滞后于页面动画、落定后旧内容「闪动」一次才换新。 */
    public void onTransitionStart() {
        transitionActive = true;
        handler.removeCallbacks(transitionEndRunnable);
        handler.postDelayed(transitionEndRunnable, TRANSITION_QUIET_MS);
        // 立即采一帧：onPageSelected 早于布局事件，若已有挂起的退避采样任务
        // （最长 200ms），切换第一帧的跟踪会被它拖住
        handler.removeCallbacks(captureRunnable);
        capturePending = true;
        handler.post(captureRunnable);
    }

    /** 页面切换结束（SCROLL_STATE_IDLE 或兜底超时）：回基准间隔并立即补采——
     *  动画落定的同一时刻玻璃就对齐真实内容，不再滞留旧页面条带。 */
    public void onTransitionEnd() {
        if (!transitionActive) return;
        transitionActive = false;
        handler.removeCallbacks(transitionEndRunnable);
        // 退避间隔按「上一页的采样成本」调出，对新页面无效：直接回基准
        currentIntervalMs = CAPTURE_INTERVAL_MS;
        handler.removeCallbacks(captureRunnable);
        capturePending = false;
        capture();
        // 兜底补采：Fragment 视图 inflate / RecyclerView 异步回填可能晚于 IDLE 一两帧
        handler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS * 2);
    }

    /** 节流调度：滚动/布局事件合并为一次条带重采样（UI 线程）；
     *  过渡期固定 16ms 跟踪动画，平时用自适应退避间隔。 */
    private void schedule() {
        if (capturePending) return;
        capturePending = true;
        handler.postDelayed(captureRunnable,
                transitionActive ? TRANSITION_INTERVAL_MS : currentIntervalMs);
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
        ImageView backdrop = GlassBackdrop.getBackdropView();
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
        // （RecyclerView 页 ~几 ms，设置页 NestedScrollView 可达 50~150ms）。
        // 超预算倍增间隔（上限 200ms），明显低于预算则逐级回落到基准 16ms。
        // 过渡期不调退避：页面切换动画只有几百 ms，跟踪动画优先于采样成本。
        if (transitionActive) return;
        if (costMs > CAPTURE_BUDGET_MS) {
            if (currentIntervalMs < MAX_INTERVAL_MS) {
                currentIntervalMs = Math.min(currentIntervalMs * 2, MAX_INTERVAL_MS);
            }
        } else if (costMs < CAPTURE_BUDGET_MS / 2 && currentIntervalMs > CAPTURE_INTERVAL_MS) {
            currentIntervalMs = Math.max(CAPTURE_INTERVAL_MS, currentIntervalMs / 2);
        }
    }
}
