package com.muxiao.Venus.common.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 液态玻璃透镜的采样源：壁纸轻模糊小样 +（可选）底栏区域内容条带的合成小样，
 * 及二者的<b>磨砂小样</b>（金字塔高斯近似）。
 *
 * <p>无背景图时经 {@link #setSolidSurface} 发布「主题底色纯色基底」（锚为铺满窗口
 * 的内容视图，几何公式与壁纸路径完全同构，退化为均匀 1/2 缩放）：底栏级透镜（吃
 * 内容条带）继续采样「底色 + 真实身后内容」合成图，玻璃照常磨砂折射；嵌套小透镜
 * 不激活（身后没有可采样的壁纸，激活会把真实内容挡成纯色，比回退底更差）。
 * 有背景图时，清晰档采样源 = 壁纸小样，
 * 叠加 {@link ContentStripSampler} 发布的底栏区域<b>内容条带</b>（滚动的卡片/
 * 列表等真实身后内容）。条带为内容视图的离屏绘制，半透明处透出壁纸——与玻璃
 * 身后的真实像素构成一致，折射带拉进的不再是不存在的壁纸（对应 Kyant0/
 * AndroidLiquidGlass 的 LayerBackdrop 采样「玻璃身后的层」）。</p>
 *
 * <p>磨砂档（{@link #getFrostBitmap()}）为清晰档的金字塔高斯近似：透镜整块从
 * 磨砂档取样，shader 只做折射位移——对应参考库「backdrop 先 blur、lens 只折射」
 * 的配方，取代旧方案在 AGSL 内做逐像素近似模糊（5 点十字核方向性强，文字笔画
 * 被拖成水平/垂直条纹）。取景对齐由透镜按 host 与 {@link #getBackdropView()}
 * （activity_main 的 background_image）的窗口坐标差实时计算。</p>
 *
 * <p>线程约定：set/publishStrip/rebuildComposite 均在 UI 线程执行（透镜绘制也在
 * UI 线程），原地重绘复用位图，滚动期间零分配；合成/磨砂小样对象恒定，透镜侧的
 * BitmapShader 缓存不会因内容更新而重建。</p>
 *
 * <p>生命周期：MainActivity.setupBackground 在 UI 线程发布/清除壁纸小样；
 * 监听列表用弱引用持有（透镜随视图生命周期消亡，不反向保活）。</p>
 */
public final class GlassBackdrop {

    /** 原始壁纸小样（未合成内容条带，重合成的基准）。 */
    private static Bitmap wallpaperSample;
    /**
     * 合成采样小样：壁纸小样 + 内容条带，原地重绘复用。
     * null = 无壁纸或无条带，{@link #getBitmap()} 回落壁纸小样本身。
     */
    private static Bitmap compositeSample;
    private static Canvas compositeCanvas;
    /** 磨砂小样（双缓冲当前面）：合成小样的金字塔高斯近似（透镜内部磨砂取样源），
     *  尺寸为合成小样 1/2。被 shader 取样期间内容恒定不写，见 rebuildComposite。 */
    private static Bitmap frostSample;
    /** 磨砂小样双缓冲背面：下一次重建的写入面，与 frostSample 每次发布互换。 */
    private static Bitmap frostBack;
    /** 壁纸磨砂小样：壁纸小样的金字塔高斯近似（无条带期间与嵌套小透镜的磨砂源）。 */
    private static Bitmap wallpaperFrost;
    /** 磨砂金字塔的复用中间层/画布/画笔（UI 线程专用，发布路径零分配）。 */
    private static Bitmap[] frostLevels = new Bitmap[0];
    private static final Canvas frostCanvas = new Canvas();
    private static final Matrix frostDrawMatrix = new Matrix();
    private static final Paint frostPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** 金字塔最小层阈值（小样 px，设备无关常数）：越小磨砂越强。 */
    private static final int FROST_MIN_DIM_PX = 64;
    /** 磨砂金字塔循环轮数：每轮「降采样链+放大链」叠一层核宽，两轮 ≈ 屏显 4dp 档磨砂。 */
    private static final int FROST_CYCLES = 2;
    /** 内容条带图像与其覆盖的窗口坐标矩形（full-res，由 ContentStripSampler 发布）。 */
    private static Bitmap contentStrip;
    private static Rect stripWindowRect;
    private static final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static WeakReference<View> backdropRef = new WeakReference<>(null);
    /**
     * 纯色基底模式（无背景图）：wallpaperSample 为主题底色纯色小样，坐标锚为
     * 铺满窗口的内容视图（android.R.id.content）。锚与窗口同尺寸，透镜取景与
     * 条带合成的 centerCrop 公式对该锚退化为均匀 1/2 缩放——与壁纸路径共用
     * 全部几何代码，仅采样源内容由图片换成纯色。
     */
    private static boolean solidSurface;
    private static int solidColor;
    /** 布局监听当前挂载的锚（防重复挂载 + 供刷新入口取实时尺寸）。 */
    private static WeakReference<View> solidListenerAnchor = new WeakReference<>(null);
    /** 锚尺寸变化（首次布局/旋转/分屏）时重建纯色基底小样。 */
    private static final View.OnLayoutChangeListener solidLayoutListener =
            (v, l, t, r, b, ol, ot, or2, ob) -> refreshSolidSample();
    private static final List<WeakReference<Runnable>> listeners = new ArrayList<>();

    private GlassBackdrop() {
    }

    /**
     * 发布轻模糊底衬与背景来源视图（UI 线程调用）；bitmap 为 null 等价于 clear。
     * 壁纸小样更新后旧内容条带作废（采样器监听壁纸变化后立即重采）。
     *
     * @param light 轻模糊小样（液态玻璃透镜的取景源，透镜清晰度的关键）
     */
    public static void set(Bitmap light, View backdropView) {
        set(light, backdropView, false);
    }

    /** 完整发布入口：solid = 纯色基底模式（无壁纸路径内部复用，见 refreshSolidSample）。 */
    private static void set(Bitmap light, View backdropView, boolean solid) {
        solidSurface = solid;
        wallpaperSample = light;
        backdropRef = new WeakReference<>(backdropView);
        if (light != null && !light.isRecycled()) {
            // 壁纸磨砂小样：嵌套小透镜与「无条带期间」透镜内部磨砂的取样源（低频重建）
            wallpaperFrost = buildFrostBlur(light, wallpaperFrost);
        } else {
            wallpaperFrost = null;
            frostSample = null;
            frostBack = null;
        }
        // 预分配合成小样：首次发布内容条带时的 ~2.6MB 位图分配（约 10-25ms）
        // 从滚动中途挪到壁纸发布时刻——低频且用户对切换壁纸瞬间的卡顿不敏感
        if (light != null && !light.isRecycled()
                && (compositeSample == null || compositeSample.isRecycled()
                || compositeSample.getWidth() != light.getWidth()
                || compositeSample.getHeight() != light.getHeight())) {
            compositeSample = Bitmap.createBitmap(light.getWidth(), light.getHeight(),
                    Bitmap.Config.ARGB_8888);
            compositeCanvas = new Canvas(compositeSample);
        }
        contentStrip = null;
        stripWindowRect = null;
        rebuildComposite();
        notifyListeners();
    }

    /** 完全清除采样源与纯色基底状态（透镜全部回退渲染；仅错误兜底路径使用）。 */
    public static void clear() {
        solidSurface = false;
        View old = solidListenerAnchor.get();
        if (old != null) old.removeOnLayoutChangeListener(solidLayoutListener);
        solidListenerAnchor = new WeakReference<>(null);
        set(null, null, false);
    }

    /** 纯色基底模式查询：透镜据此决定嵌套小透镜是否保持回退渲染。 */
    public static boolean isSolidSurface() {
        return solidSurface;
    }

    /**
     * 无背景图时发布「主题底色纯色基底」（UI 线程调用）：以 anchor（铺满窗口的
     * 内容视图，如 android.R.id.content）为坐标锚生成 1/2 尺度纯色小样，走与
     * 壁纸完全相同的合成/磨砂/取景管线——底栏透镜（enableContentStripSampling）
     * 继续采样「底色 + 真实身后内容」（条带经 ContentStripSampler），玻璃不因
     * 缺壁纸而整条停摆成无模糊的静态半透明表面。
     *
     * <p>anchor 未布局（宽或高为 0，如 onCreate 阶段调用）时挂起，由
     * solidLayoutListener 在首次布局后自动补发；旋转/分屏的尺寸变化同样经布局
     * 监听重建小样。重复调用同锚时只更新颜色，不重挂监听。</p>
     */
    public static void setSolidSurface(int color, View anchor) {
        solidSurface = true;
        solidColor = color;
        if (anchor != null && solidListenerAnchor.get() != anchor) {
            View old = solidListenerAnchor.get();
            if (old != null) old.removeOnLayoutChangeListener(solidLayoutListener);
            anchor.addOnLayoutChangeListener(solidLayoutListener);
            solidListenerAnchor = new WeakReference<>(anchor);
        }
        refreshSolidSample();
    }

    /** 按锚当前尺寸重建纯色小样并发布（同锚同尺寸时跳过，条带/磨砂状态全保持）。 */
    private static void refreshSolidSample() {
        View anchor = solidListenerAnchor.get();
        if (!solidSurface || anchor == null
                || anchor.getWidth() == 0 || anchor.getHeight() == 0) {
            return;
        }
        int sw = Math.max(1, anchor.getWidth() / 2);
        int sh = Math.max(1, anchor.getHeight() / 2);
        if (backdropRef.get() == anchor && wallpaperSample != null
                && !wallpaperSample.isRecycled()
                && wallpaperSample.getWidth() == sw
                && wallpaperSample.getHeight() == sh) {
            return;
        }
        Bitmap light = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        light.eraseColor(solidColor);
        set(light, anchor, true);
    }

    /** 透镜实际取用的采样源：有内容条带时为合成小样，否则为纯壁纸小样。 */
    public static Bitmap getBitmap() {
        return compositeSample != null ? compositeSample : wallpaperSample;
    }

    /**
     * 纯壁纸小样（不含内容条带）。嵌套小透镜（状态圈/玻璃按钮/chip）必须用这份：
     * 它们位于条带覆盖范围内时，若采样合成图会取到<b>自己的折射残影</b>——
     * 采样源里有上一帧的自己，叠在清晰内容下读作发虚（自引用反馈）。
     * 只有底栏这类「采样身后层」的大面板透镜才用合成图。
     */
    public static Bitmap getWallpaperBitmap() {
        return wallpaperSample;
    }

    /**
     * 透镜内部磨砂的取样源（清晰档的金字塔高斯近似，尺寸为清晰档 1/2）：
     * 有内容条带时为合成小样的磨砂版，否则为壁纸磨砂版。
     */
    public static Bitmap getFrostBitmap() {
        return compositeSample != null ? frostSample : wallpaperFrost;
    }

    /** 壁纸磨砂小样（不含内容条带）。嵌套小透镜的磨砂源，防自引用（同 getWallpaperBitmap）。 */
    public static Bitmap getWallpaperFrostBitmap() {
        return wallpaperFrost;
    }

    /**
     * 采样源坐标锚：有背景图时为 activity_main 的 background_image（centerCrop
     * 取景），纯色基底模式为铺满窗口的内容视图（均匀 1/2 缩放取景）。
     * 供透镜按窗口坐标差对齐取景、条带合成按同一公式映射窗口矩形。
     */
    public static View getBackdropView() {
        return backdropRef.get();
    }

    /**
     * 发布底栏区域内容条带（UI 线程，{@link ContentStripSampler} 调用）。
     * strip 为条带图像（任意尺度），windowRect 为其覆盖的窗口坐标矩形；
     * 合成时按 backdropView 的 centerCrop 映射换算到小样坐标。
     * 只重绘合成小样并通知透镜失效——不反向通知采样器（条带发布由采样器的
     * 滚动/布局监听驱动，反向通知会造成静止时 30fps 空转采样循环）。
     */
    public static void publishStrip(Bitmap strip, Rect windowRect) {
        if (wallpaperSample == null || strip == null || strip.isRecycled()
                || windowRect == null || windowRect.isEmpty())
            return;
        contentStrip = strip;
        stripWindowRect = new Rect(windowRect);
        android.os.Trace.beginSection("strip.rebuildComposite");
        rebuildComposite();
        android.os.Trace.endSection();
        android.os.Trace.beginSection("strip.notifyListeners");
        notifyListeners();
        android.os.Trace.endSection();
    }

    /**
     * 重绘合成小样（原地复用位图，滚动期间零分配）。UI 线程调用。
     * 坐标换算与 LensGlassDrawable.draw 的取景矩阵同一条 centerCrop 公式：
     * 窗口点 w → 小样点 b = (w - bgLoc - offset) / scale。
     */
    private static void rebuildComposite() {
        View bg = backdropRef.get();
        if (wallpaperSample == null || contentStrip == null || stripWindowRect == null
                || bg == null || bg.getWidth() == 0 || bg.getHeight() == 0) {
            compositeSample = null;
            compositeCanvas = null;
            return;
        }
        int bw = wallpaperSample.getWidth();
        int bh = wallpaperSample.getHeight();
        if (compositeSample == null
                || compositeSample.getWidth() != bw || compositeSample.getHeight() != bh) {
            compositeSample = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            compositeCanvas = new Canvas(compositeSample);
        }
        compositeCanvas.drawBitmap(wallpaperSample, 0, 0, null);

        float scale = Math.max(bg.getWidth() / (float) bw, bg.getHeight() / (float) bh);
        int[] bgLoc = new int[2];
        bg.getLocationInWindow(bgLoc);
        float offX = (bg.getWidth() - bw * scale) / 2f;
        float offY = (bg.getHeight() - bh * scale) / 2f;
        RectF dst = new RectF(
                (stripWindowRect.left - bgLoc[0] - offX) / scale,
                (stripWindowRect.top - bgLoc[1] - offY) / scale,
                (stripWindowRect.right - bgLoc[0] - offX) / scale,
                (stripWindowRect.bottom - bgLoc[1] - offY) / scale);
        compositeCanvas.drawBitmap(contentStrip, null, dst, stripPaint);
        // 磨砂小样随合成图同步重建。**双缓冲写入**：金字塔要分多步重写同一位图，
        // 中间态（首轮 src→frost 后仅有一次双线性，等效模糊半径骤减）若被
        // RenderThread 的纹理上传读到——UI 线程重写与 RenderThread 为上一帧
        // display list 上传纹理是并发关系——磨砂会闪现一帧「文字变清晰」。
        // 因此写入背面（frostBack）再交换引用：被取样面在绑定期间内容恒定；
        // 两面按尺寸复用，滚动期间零分配。旧活动面转背面时其纹理可能仍被
        // RenderThread 使用一帧，两帧后才可能被写入，天然安全。
        Bitmap built = buildFrostBlur(compositeSample, frostBack);
        frostBack = frostSample;
        frostSample = built;
    }

    /**
     * 金字塔磨砂：把 src 逐级对半降采样（双线性，每级即一层 box 低通）至最小边
     * ≤ {@link #FROST_MIN_DIM_PX}，再逐级双线性放大回 frost（尺寸恒为 src 的 1/2），
     * 循环 {@link #FROST_CYCLES} 轮加大核宽。等效各向同性高斯，没有逐像素近似采样
     * 的方向性拖影；最小层阈值是设备无关 px 常数，小样尺寸随密度等比放大，降采样
     * 级数自动随密度加深——屏显磨砂半径跨设备一致。
     * 中间层/画布/矩阵全部复用：滚动期间的条带发布路径零分配。
     */
    private static Bitmap buildFrostBlur(Bitmap src, Bitmap frost) {
        int fw = Math.max(1, src.getWidth() / 2);
        int fh = Math.max(1, src.getHeight() / 2);
        if (frost == null || frost.isRecycled()
                || frost.getWidth() != fw || frost.getHeight() != fh) {
            frost = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        }
        int lw = fw;
        int lh = fh;
        int depth = 0;
        while (Math.min(lw, lh) > FROST_MIN_DIM_PX && depth < 6) {
            lw = Math.max(1, lw / 2);
            lh = Math.max(1, lh / 2);
            depth++;
        }
        if (frostLevels.length != depth) {
            frostLevels = new Bitmap[depth]; // 旧层仅本数组持有，弃给 GC
        }
        for (int i = 0; i < depth; i++) {
            Bitmap level = frostLevels[i];
            int iw = Math.max(1, fw >> (i + 1));
            int ih = Math.max(1, fh >> (i + 1));
            if (level == null || level.isRecycled()
                    || level.getWidth() != iw || level.getHeight() != ih) {
                level = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
                frostLevels[i] = level;
            }
        }
        for (int cycle = 0; cycle < FROST_CYCLES; cycle++) {
            Bitmap from = cycle == 0 ? src : frost;
            // 降采样链：src（首轮）→ frost → L1 → … → Ld；后续轮从 frost → L1 起
            // （首轮 i=0 的 src→frost 已完成，避免 frost→frost 自身恒等绘制）
            for (int i = cycle == 0 ? 0 : 1; i <= depth; i++) {
                from = drawFrostStep(from, i == 0 ? frost : frostLevels[i - 1]);
            }
            // 放大链：Ld → … → L1 → frost（逐级双线性回填）
            for (int i = depth - 1; i >= 0; i--) {
                from = drawFrostStep(from, i == 0 ? frost : frostLevels[i - 1]);
            }
        }
        frostCanvas.setBitmap(null);
        return frost;
    }

    /** 金字塔单步：把 from 双线性缩放绘制进 dst，返回 dst。 */
    private static Bitmap drawFrostStep(Bitmap from, Bitmap dst) {
        frostDrawMatrix.setScale(
                dst.getWidth() / (float) from.getWidth(),
                dst.getHeight() / (float) from.getHeight());
        frostCanvas.setBitmap(dst);
        frostCanvas.drawBitmap(from, frostDrawMatrix, frostPaint);
        return dst;
    }

    public static void addListener(Runnable listener) {
        listeners.add(new WeakReference<>(listener));
        // 顺手清理已被回收的监听，避免长驻 Activity 下列表无界增长
        Iterator<WeakReference<Runnable>> it = listeners.iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) it.remove();
        }
    }

    private static void notifyListeners() {
        for (WeakReference<Runnable> ref : listeners) {
            Runnable listener = ref.get();
            if (listener != null) listener.run();
        }
    }
}
