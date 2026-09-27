package com.muxiao.Venus.common.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.muxiao.Venus.R;

import org.intellij.lang.annotations.Language;

/**
 * 液态玻璃透镜 Drawable——按 Kyant0/AndroidLiquidGlass（SleepDown-Schedule 的玻璃基础库）
 * 材质配方在 View 体系 / minSdk 23 上的实现：
 *
 * <ul>
 *   <li><b>采样源（清晰档 + 磨砂档）</b>：清晰档为 {@link GlassBackdrop} 的壁纸小样
 *       （约 1/2 尺度），底栏区域叠加 {@link ContentStripSampler} 采样的真实身后内容
 *       条带（对应参考库 LayerBackdrop 采样「玻璃身后的层」）；磨砂档为清晰档的
 *       金字塔高斯近似——透镜整块从磨砂档取样，shader 只做折射（对应参考库
 *       「backdrop 先 blur、lens 只折射」的配方）。
 *       条带离屏采样期间本类进入捕获模式（setCaptureActive），树内嵌套透镜一律
 *       画静态回退底，防止玻璃采样玻璃的自引用；</li>
 *   <li><b>lens 边缘折射（API 33+，AGSL 逐像素）</b>：dp 口径——带宽 {@link #LENS_HEIGHT_DP}
 *       （对应 lensHeight）、边缘最大取样外推 {@link #LENS_AMOUNT_DP}（对应 lensAmount）。
 *       RuntimeShader 内按符号距离场（圆角矩形/圆）逐像素计算内法线与位移（平方衰减曲线，
 *       对应 Kyant lens 的折射方程），并做 RGB 色散三采样（对应 chromaticAberration）与
 *       vibrancy 饱和度提升——与参考库同一条方程，不再被网格顶点插值量化；</li>
 *   <li><b>lens 边缘折射（API &lt; 33，drawBitmapMesh 回退）</b>：网格顶点按幂次重映射向
 *       边缘集中，折射带内顶点密度约为均匀网格的 2 倍以上，尽量保住弯折观感；</li>
 *   <li><b>表面 tint</b>：对应 onDrawSurface 的 drawRect(surfaceColor)（glass_tint 资源，
 *       昼夜适配）+ Screen 混合的 sparkle 白（对应 BlendMode.Screen 一笔）；</li>
 *   <li><b>边缘高光 + 内阴影</b>：对应 Highlight.Default（顶部亮、底部回光的渐变描边）与
 *       InnerShadow（底部内侧柔和暗带，BlurMaskFilter 实现）——两者构成玻璃的「厚度感」；</li>
 *   <li><b>按压液态形变</b>：可点击宿主按压时放大 {@link #PRESS_EXPANSION_DP} 并向指尖方向
 *       微位移（tanh 曲线），对应 LiquidButton 的 pressExpansion + InteractiveHighlight；</li>
 *   <li><b>回退</b>：无背景图时绘制接管前的原玻璃渐变 drawable，静态蒙版观感不丢。</li>
 * </ul>
 *
 * 取景偏移在每次绘制时按 host 与背景图视图（background_image）的<b>窗口坐标差</b>实时
 * 计算——页面滚动、ViewPager 翻页、insets 变化天然正确，无需逐处对齐。
 * 只能经 {@link #apply(View, float)} 安装：该方法负责接管背景、注册滚动/布局/背景变更的失效监听。
 */
public final class LensGlassDrawable extends Drawable {

    /**
     * 内容条带离屏采样标记（ContentStripSampler 置位）：采样绘制内容容器时，
     * 树内嵌套的小透镜（状态圈/玻璃药丸/图标）一律走静态回退底——否则玻璃
     * 采样玻璃会自引用，且嵌套折射在离屏路径上成本爆炸。volatile 保证采样
     * 置位对同帧内所有透镜实例立即可见。
     */
    private static volatile boolean captureActive;

    /**
     * 采样源档位：false（默认）取纯壁纸小样；true 取「壁纸+内容条带」合成图。
     * 仅底栏这类需要折射「身后真实内容」的大面板透镜开启——嵌套小透镜若也吃
     * 合成图，滚进条带范围时会采样到自己的残影（自引用 → 发虚/叠加模糊）。
     */
    private boolean sampleContentStrip;

    /** 开启合成图采样（仅底栏级透镜调用，见 {@link #sampleContentStrip} 注释）。 */
    public void enableContentStripSampling() {
        sampleContentStrip = true;
        invalidateSelf();
    }

    /** 离屏内容采样开关（仅 ContentStripSampler 在采样绘制前后切换）。 */
    public static void setCaptureActive(boolean active) {
        captureActive = active;
    }

    /**
     * lensHeight：折射带宽默认值（dp），Kyant button 12dp；panel 口径 24dp 由 apply 指定。
     */
    private static final float LENS_HEIGHT_DP = 12f;

    /** lensAmount：边缘最大取样外推默认值（dp），Kyant button 24dp；panel 口径 34dp 由 apply 指定。 */
    private static final float LENS_AMOUNT_DP = 24f;

    /** 玻璃后内容饱和度（>1 提升），对应 Kyant vibrancy()。 */
    private static final float SATURATION = 1.25f;

    /** 内阴影带宽（dp），对应 Kyant InnerShadow radius（panel 10dp）。 */
    private static final float INNER_SHADOW_DP = 8f;

    /** 边缘 RGB 色散开关（chromaticAberration）：红/蓝通道在折射带内反向微偏。 */
    private static final boolean CHROMATIC_ABERRATION = true;

    /** 色散强度：红通道位移 ×(1+spread)，蓝通道 ×(1−spread)。0.1 让边缘出现可辨认的
     *  红/蓝镶边——色散是「这是玻璃」最强的视觉证词，过轻则被磨砂雾化吃掉。 */
    private static final float CHROMATIC_SPREAD = 0.1f;

    /** 轮壁霜化强度（0-1）：折射带最外缘的取样色向 tint 雾色收敛的比例。
     *  实色邻居（实心按钮/图标笔画/文字）被拉入后只留下淡淡的色晕，不再形成
     *  饱和色带（蓝按钮涂抹）或深色鬼影（药丸内的图标副本）——壁读作磨砂而非脏。 */
    private static final float EDGE_FROST = 0.5f;

    /** 按压放大高度（dp），对应 LiquidButton pressExpansion 默认 4dp。 */
    private static final float PRESS_EXPANSION_DP = 4f;

    /**
     * AGSL 逐像素透镜着色器（API 33+）——Kyant rounded-rect lens 方程的直译：
     * SDF 求符号距离 → 距离在折射带 [-band, 0] 内按平方衰减取位移量 →
     * 沿内法线外推取样（透镜壁弯折）→ 可选 RGB 色散 → vibrancy 饱和度。
     * uFrost 由 BitmapShader（带取景矩阵）注入磨砂小样；所有长度均为 px。
     */
    @Language("AGSL")
    private static final String LENS_SHADER_SRC =
            "uniform shader uFrost;\n" +         // 磨砂采样源（GlassBackdrop 金字塔高斯小样）
            "uniform float2 uSize;\n" +
            "uniform float uCornerRadius;\n" +   // < 0 表示圆形（椭圆取景）
            "uniform float uBand;\n" +
            "uniform float uAmount;\n" +
            "uniform float3 uFrostColor;\n" +    // 霜化目标色（glass_tint 的 RGB，昼夜适配）
            "uniform float uChromatic;\n" +      // 0/1
            "\n" +
            "float sdShape(float2 p) {\n" +
            "    float r = uCornerRadius;\n" +
            "    if (r < 0.0) {\n" +
            "        float2 c = uSize * 0.5;\n" +
            "        return length(p - c) - min(uSize.x, uSize.y) * 0.5;\n" +
            "    }\n" +
            "    float2 he = uSize * 0.5 - float2(r, r);\n" +
            "    float2 q = abs(p - uSize * 0.5) - he;\n" +
            "    return length(max(q, float2(0.0, 0.0)))\n" +
            "         + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "\n" +
            "float bandInfluence(float d) {\n" +
            "    return clamp(1.0 + d / uBand, 0.0, 1.0);\n" +
            "}\n" +
            "\n" +
            "float2 refractOffset(float2 p, float d, float scale) {\n" +
            "    if (d <= -uBand || uAmount <= 0.0) {\n" +
            "        return float2(0.0, 0.0);\n" +
            "    }\n" +
            "    float e = 0.5;\n" +
            "    float2 g = float2(\n" +
            "        sdShape(p + float2(e, 0.0)) - sdShape(p - float2(e, 0.0)),\n" +
            "        sdShape(p + float2(0.0, e)) - sdShape(p - float2(0.0, e)));\n" +
            "    float len = length(g);\n" +
            "    if (len < 0.0001) {\n" +
            "        return float2(0.0, 0.0);\n" +
            "    }\n" +
            "    float influence = bandInfluence(d);\n" +
            "    return (g / len) * (uAmount * influence * influence * scale);\n" +
            "}\n" +
            "\n" +
            // 磨砂已在采样源端预烘焙（GlassBackdrop 金字塔高斯小样），shader 只做
            // 折射位移与轮壁霜化——对应 Kyant 库「backdrop 先 blur、lens 只折射」的
            // 配方。旧方案在 shader 内做 5 点十字近似模糊：核方向性强，文字笔画被
            // 拖成水平/垂直条纹（磨砂发脏的根源）；带内位移作用于磨砂源，弯折经
            // 卡片边缘等低频结构仍清晰可辨。
            "float4 sampleLens(float2 p, float d, float scale) {\n" +
            "    float influence = (d > -uBand) ? bandInfluence(d) : 0.0;\n" +
            "    float4 c = float4(uFrost.eval(p + refractOffset(p, d, scale)));\n" +
            "    c.rgb = mix(c.rgb, uFrostColor, influence * " + EDGE_FROST + ");\n" +
            "    return c;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 fragCoord) {\n" +
            "    float d = sdShape(fragCoord);\n" +
            "    float4 col;\n" +
            "    if (uChromatic > 0.5 && d > -uBand) {\n" +
            "        col.r = sampleLens(fragCoord, d, 1.0 + " + CHROMATIC_SPREAD + ").r;\n" +
            "        col.g = sampleLens(fragCoord, d, 1.0).g;\n" +
            "        col.b = sampleLens(fragCoord, d, 1.0 - " + CHROMATIC_SPREAD + ").b;\n" +
            "        col.a = 1.0;\n" +
            "    } else {\n" +
            "        col = sampleLens(fragCoord, d, 1.0);\n" +
            "    }\n" +
            // 注意：AGSL/SkSL 不接受 'f' 浮点后缀，SATURATION 经 Float.toString
            // 输出即为合法字面量（如 "1.25"），不可拼接 "f"——曾因此导致 shader
            // 在所有设备上编译失败、静默降级到低密度 mesh（块状折射伪影）
            "    float lum = dot(col.rgb, float3(0.2126, 0.7152, 0.0722));\n" +
            "    col.rgb = clamp(lum + (col.rgb - lum) * " + SATURATION + ", 0.0, 1.0);\n" +
            "    return half4(col);\n" +
            "}\n";

    private final View host;
    /** 圆角半径 px；&lt; 0 表示圆形（椭圆取景）。 */
    private final float cornerRadius;
    private final boolean oval;
    /** 回退 drawable（无背景图时显示的静态表面）；状态圆宿主可经 setFallbackDrawable 换底。 */
    private Drawable fallback;
    /** 本实例的 lens 口径（dp）：button 默认 12/24，大面板（底栏等）24/34。 */
    private final float lensHeightDp;
    private final float lensAmountDp;
    /** AGSL 可用性：API 33+ 初始为真，RuntimeShader 编译异常时降级为 mesh 路径。 */
    private boolean useAgsl;

    /** 回退 drawable 的透明度（setAlpha 语义转发，透镜本体不参与透明度降级）。 */
    private int fallbackAlpha = 255;
    /** 语义描边色（0 = 无）：状态靠描边 + 图标/文字色表达，不再整块填充。 */
    private int semanticStrokeColor;
    /** 宿主屏幕密度（构造时缓存，供折射口径按尺寸钳制换算）。 */
    private final float displayDensity;

    /** AGSL：逐像素透镜着色器与背景取样器（API 33+ 专用，与 mesh 路径互斥）。 */
    private RuntimeShader lensShader;
    private BitmapShader backdropShader;
    /** 磨砂小样取样器（uFrost 输入）：CLAMP 同 backdropShader，缓存键为位图身份。 */
    private BitmapShader frostShader;
    private Bitmap frostShaderBitmap;

    /** 进程级共享的编译产物（编译失败时置位，实例各自降级 mesh）。 */
    private static RuntimeShader sharedLensShader;
    private static boolean sharedShaderFailed;

    /**
     * 获取进程级共享的 {@link RuntimeShader}。安全性：所有透镜绘制都在 UI 线程
     * 串行执行，且每次 drawAgsl 全量重设 9 个 uniform 后立即 draw——实例间无状态
     * 泄漏。收益：ViewPager2 重建离屏 Fragment 视图时每个新实例不再各自触发
     * AGSL 编译（页面切换的 100ms+ 大帧主因之一），编译成本压缩为进程内一次。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static RuntimeShader acquireSharedShader() {
        if (sharedLensShader == null && !sharedShaderFailed) {
            try {
                sharedLensShader = new RuntimeShader(LENS_SHADER_SRC);
            } catch (RuntimeException e) {
                sharedShaderFailed = true;
                android.util.Log.e("LensGlassDrawable",
                        "RuntimeShader compile failed, fallback to mesh", e);
            }
        }
        return sharedShaderFailed ? null : sharedLensShader;
    }
    private Bitmap shaderBitmap;

    /** mesh 回退用：裁剪中间图用：带 FILTER_BITMAP 与饱和度 ColorFilter（vibrancy）。 */
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** 网格绘制用：饱和度已在中间图烘焙，保持素 Paint。 */
    private final Paint meshPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** AGSL 透镜绘制 Paint（shader = RuntimeShader）。 */
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** 表面 tint 填充。 */
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Screen 混合的 sparkle 白。 */
    private final Paint sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 边缘渐变描边（Highlight.Default + border 合一）。 */
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 语义描边：状态色细环（running=主色 / 完成=成功绿 / 失败=错误红），叠在材质栈之上。 */
    private final Paint semanticStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 内阴影：宽描边 + BlurMaskFilter，配合 clip 只留内侧柔带。 */
    private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    /** 磨砂小样的取景矩阵：matrix ∘ ×尺度比（磨砂小样为清晰档的 1/2 尺度）。 */
    private final Matrix frostMatrix = new Matrix();
    private final int[] hostLoc = new int[2];
    private final int[] bgLoc = new int[2];

    private Path clipPath;
    /** 描边路径：比轮廓内缩半个描边宽，让 highlight/rim 全部落在形状内。 */
    private Path edgePath;
    /**
     * 语义描边专用路径：内缩半个语义描边宽（1.5dp/2），整圈落在视图边界内。
     * 不能复用 edgePath——那是按 1.5px 高光描边设计的 0.75px 内缩；语义描边宽
     * 1.5dp（px 随密度放大），复用会让外半环伸出视图边界被硬裁（xxhdpi 裁 1/3、
     * xxxhdpi 裁 37%，且裁切边无抗锯齿），状态圈读作残缺的环。
     */
    private Path semanticStrokePath;
    private Path innerShadowPath;
    /**
     * 半尺度中间裁剪图（仅 mesh 回退路径）：玻璃区域（含折射外推边距）从轻模糊源裁出。
     * 轻模糊源本身约 1/4 尺度已充分低通，半尺度中间图足以表达折射位移，
     * 内存与每帧裁剪成本均为全尺度的 1/4。
     */
    private Bitmap intermediate;
    private Canvas intermediateCanvas;
    private int margin;
    private final float halfScale = 0.5f;
    /** drawBitmapMesh 顶点数组：中间图网格 → 视图目标位置（边缘顶点按折射位移收拢）。 */
    private float[] verts;
    private int meshCols;
    private int meshRows;
    /** mesh 口径缓存（ensureShaders 与 draw() 同公式钳制，供顶点预算与绘制共用）。 */
    private float meshBandPx;
    private float meshAmountPx;
    private int cachedW = -1;
    private int cachedH = -1;

    private LensGlassDrawable(View host, float cornerRadius, Drawable fallback,
            float lensHeightDp, float lensAmountDp) {
        this.host = host;
        this.cornerRadius = cornerRadius;
        this.oval = cornerRadius < 0;
        this.fallback = fallback;
        this.lensHeightDp = lensHeightDp;
        this.lensAmountDp = lensAmountDp;
        this.useAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        float density = host.getResources().getDisplayMetrics().density;
        this.displayDensity = density;
        edgePaint.setStyle(Paint.Style.STROKE);
        innerShadowPaint.setStyle(Paint.Style.STROKE);
        semanticStrokePaint.setStyle(Paint.Style.STROKE);
        semanticStrokePaint.setStrokeWidth(1.5f * density);
        // vibrancy：mesh 路径的饱和度提升烘焙进中间图（AGSL 路径在着色器内做）
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(SATURATION);
        backdropPaint.setColorFilter(new ColorMatrixColorFilter(saturation));
        Context ctx = host.getContext();
        tintPaint.setColor(ContextCompat.getColor(ctx, R.color.glass_tint));
        sparklePaint.setColor(ContextCompat.getColor(ctx, R.color.glass_sparkle));
        sparklePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
    }

    /**
     * 把 view 的背景接管为透镜 drawable（button 默认口径 12/24dp），并注册几何失效监听。
     * 接管前的原背景保留为无背景图时的回退渲染。
     * 可点击宿主额外挂按压液态形变（放大 + 指尖跟随位移）。
     *
     * @param cornerRadiusPx 圆角半径 px；传负值表示圆形（图标圆等）
     */
    public static void apply(View host, float cornerRadiusPx) {
        apply(host, cornerRadiusPx, LENS_HEIGHT_DP, LENS_AMOUNT_DP, true);
    }

    /**
     * 完整参数版接管。
     *
     * @param lensHeightDp        折射带宽（lensHeight）：大面板用 24dp，小控件 12dp
     * @param lensAmountDp        边缘最大取样外推（lensAmount）：大面板 34dp，小控件 24dp
     * @param interactiveEffects  是否挂交互装饰（按压液态形变 + 补涟漪前景）；
     *                            整条底栏这类大容器传 false，避免整栏形变
     */
    public static LensGlassDrawable apply(View host, float cornerRadiusPx,
            float lensHeightDp, float lensAmountDp, boolean interactiveEffects) {
        if (host == null || host.getBackground() instanceof LensGlassDrawable) return null;
        LensGlassDrawable lens = new LensGlassDrawable(host, cornerRadiusPx, host.getBackground(),
                lensHeightDp, lensAmountDp);
        host.setBackground(lens);
        // 自定义背景会顶掉原背景内嵌的涟漪（如 bg_glass_row_button）；可点击且无前景的补一层
        if (interactiveEffects && host.isClickable() && host.getForeground() == null) {
            int ripple = MaterialColors.getColor(host,
                    com.google.android.material.R.attr.colorOutlineVariant, 0x33000000);
            host.setForeground(new RippleDrawable(ColorStateList.valueOf(ripple), null, null));
        }
        // 按压液态形变：不消费触摸事件，click/长按/涟漪行为全部不受影响
        if (interactiveEffects && host.isClickable()) applyPressDeformation(host);
        // 滚动失效：attach 后挂到视图树观察者（树内任意滚动均回调），detach 时摘除防泄漏
        ViewTreeObserver.OnScrollChangedListener scrollListener = lens::invalidateSelf;
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
                lens.invalidateSelf();
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            }
        });
        if (host.isAttachedToWindow())
            host.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        host.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> lens.invalidateSelf());
        // 背景图在运行时被添加/移除/重合成（设置页切换）时切换透镜/回退渲染
        GlassBackdrop.addListener(lens::invalidateSelf);
        lens.invalidateSelf();
        return lens;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        Bitmap bmp = sampleContentStrip
                ? GlassBackdrop.getBitmap()
                : GlassBackdrop.getWallpaperBitmap();
        // 磨砂小样：透镜整块取样的实际来源（与清晰档同发布节奏，理论上恒就绪；
        // 兜底退清晰档——退化为无磨砂但渲染不中断）
        Bitmap frostBmp = sampleContentStrip
                ? GlassBackdrop.getFrostBitmap()
                : GlassBackdrop.getWallpaperFrostBitmap();
        if (frostBmp == null || frostBmp.isRecycled()) frostBmp = bmp;
        android.widget.ImageView bg = GlassBackdrop.getBackdropView();
        // captureActive：离屏采样期间嵌套透镜一律画回退底（见 setCaptureActive 注释）
        boolean lensActive = bmp != null && !bmp.isRecycled() && bg != null
                && bg.getWidth() > 0 && bg.getVisibility() == View.VISIBLE
                && !captureActive;
        if (!lensActive) {
            // 离屏采样期间嵌套透镜画<b>透明体</b>：回退玻璃底（半透明白填充+光边）会
            // 被上层玻璃再次折射，叠加成雾斑（设置页「查看榜单图」按钮压在底栏上方
            // 时的白雾）——采样源里只保留语义描边（状态圈色环是内容本身，非玻璃材质）。
            if (!captureActive && fallback != null) {
                fallback.setBounds(b);
                fallback.draw(canvas);
            }
            // 语义描边在回退模式同样绘制（TaskAdapter 的「玻璃底 + 语义色描边」方案
            // 依赖它承载状态色）。路径/描边资源在 ensureShaders 构建，lens 分支才会
            // 走到；纯回退场景（无背景图、从未进入 lens 分支）需先确保构建一次。
            ensureShaders(b.width(), b.height());
            drawSemanticStroke(canvas);
            return;
        }

        int w = b.width();
        int h = b.height();
        ensureShaders(w, h);
        // ensureShaders 失败（如 RuntimeShader 编译异常）时已自动降级，仍不满足则回退
        if (useAgsl ? lensShader == null : intermediate == null) {
            if (fallback != null) {
                fallback.setBounds(b);
                fallback.draw(canvas);
            }
            drawSemanticStroke(canvas);
            return;
        }

        // 取景矩阵（位图 → host 本地）：背景 centerCrop 对齐（同 background_image 的
        // scaleType）平移到 host 本地坐标。磨砂档与清晰档同几何（frost 为其 1/2 尺度，
        // 尺度比在 drawAgsl/drawMesh 内换算），带内与内部取样同源连续，整块玻璃
        // 读作一体材质。
        host.getLocationInWindow(hostLoc);
        bg.getLocationInWindow(bgLoc);
        float scale = Math.max(bg.getWidth() / (float) bmp.getWidth(),
                bg.getHeight() / (float) bmp.getHeight());
        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate((bg.getWidth() - bmp.getWidth() * scale) / 2f + bgLoc[0] - hostLoc[0],
                (bg.getHeight() - bmp.getHeight() * scale) / 2f + bgLoc[1] - hostLoc[1]);

        // 折射口径按宿主尺寸钳制：带宽 ≤ 短边 16%、外推 ≤ 短边 14%。
        // 关键约束「外推 < 带宽」：位移在带内从轮廓的峰值平滑衰减到 0，环带与内部
        // 融为一块连续渐变场——若外推远大于可见位移的衰减尺度，壁会读作独立的
        // 「折射环」，与平坦的内部割裂成两块区域。
        float bandPx = Math.min(lensHeightDp * displayDensity, Math.min(w, h) * 0.16f);
        float amountPx = Math.min(lensAmountDp * displayDensity, Math.min(w, h) * 0.14f);
        // RuntimeShader 为 API 33+：useAgsl 仅在 33+ 置位（构造函数），此处补内联
        // SDK 判定供 lint 的 NewApi 检查确认调用路径安全
        if (useAgsl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.os.Trace.beginSection("lens.drawAgsl");
            drawAgsl(canvas, b, bmp, frostBmp, w, h, bandPx, amountPx);
            android.os.Trace.endSection();
        } else {
            android.os.Trace.beginSection("lens.drawMesh");
            drawMesh(canvas, bmp, frostBmp);
            android.os.Trace.endSection();
        }

        // 4) 材质栈（两路共用）：clip 内 = 表面 tint → Screen sparkle → 底部内阴影；
        //    clip 外 = 边缘渐变描边（顶部亮 / 底部回光，Highlight.Default + border 合一）
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRect(b, tintPaint);
        canvas.drawRect(b, sparklePaint);
        canvas.drawPath(innerShadowPath, innerShadowPaint);
        canvas.restore();
        canvas.drawPath(edgePath, edgePaint);
        drawSemanticStroke(canvas);
    }

    /** API 33+：RuntimeShader 逐像素折射——磨砂小样经 BitmapShader（逆取景矩阵）注入。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void drawAgsl(Canvas canvas, Rect b, Bitmap bmp, Bitmap frostBmp, int w, int h,
            float bandPx, float amountPx) {
        if (backdropShader == null || shaderBitmap != bmp) {
            shaderBitmap = bmp;
            backdropShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        // BitmapShader 的 localMatrix 语义：画布点 p 的采样位图坐标 = localMatrix⁻¹(p)。
        // matrix 是「位图 → host 本地」，其逆正是 host 本地 → 位图——直接用 matrix 本身
        // 即可让 shader 按 host 本地坐标取到正确的壁纸位置
        backdropShader.setLocalMatrix(matrix);

        if (frostShader == null || frostShaderBitmap != frostBmp) {
            frostShaderBitmap = frostBmp;
            frostShader = new BitmapShader(frostBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        // frost 小样→host 取景 = matrix ∘ scale(sx, sy)（frost 像素 q 对应清晰档
        // 坐标 sx·qx, sy·qy）。必须用 preScale：preScale 后 M' = M×S，M'(q) =
        // M(S(q)) 才是「先缩放 frost 坐标再过取景矩阵」；postScale 会得到 S×M，
        // 采样坐标被错误缩放后越出 frost 小样边界，CLAMP 把整块玻璃内部钳到
        // 小样最后一行（竖条纹伪影）。x/y 比例分开取（清晰档宽高可为奇数）。
        float sx = (float) bmp.getWidth() / frostBmp.getWidth();
        float sy = (float) bmp.getHeight() / frostBmp.getHeight();
        frostMatrix.set(matrix);
        frostMatrix.preScale(sx, sy);
        frostShader.setLocalMatrix(frostMatrix);

        lensShader.setFloatUniform("uSize", w, h);
        lensShader.setFloatUniform("uCornerRadius", oval ? -1f : cornerRadius);
        lensShader.setFloatUniform("uBand", bandPx);
        lensShader.setFloatUniform("uAmount", amountPx);
        // 轮壁霜化目标色：glass_tint 的 RGB（昼夜资源限定符自动适配，alpha 不参与）
        int frostColor = ContextCompat.getColor(host.getContext(), R.color.glass_tint);
        lensShader.setFloatUniform("uFrostColor",
                ((frostColor >> 16) & 0xFF) / 255f,
                ((frostColor >> 8) & 0xFF) / 255f,
                (frostColor & 0xFF) / 255f);
        lensShader.setFloatUniform("uChromatic", CHROMATIC_ABERRATION ? 1f : 0f);
        lensShader.setInputShader("uFrost", frostShader);
        lensPaint.setShader(lensShader);

        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRect(b, lensPaint);
        canvas.restore();
    }

    /** API &lt; 33：drawBitmapMesh 回退——顶点已由 buildMeshVertices 按几何预计算。 */
    private void drawMesh(Canvas canvas, Bitmap bmp, Bitmap frostBmp) {
        // 1) 裁半尺度中间图：intermediate(px,py) = 磨砂小样(透镜显示位 (px/halfScale-margin, ...)
        //    的取样位)，四周 margin 为折射外推取样留余量（vibrancy ColorFilter 在此一并烘焙）。
        //    取磨砂小样而非清晰档：回退路径与 AGSL 同配方（整块磨砂 + 边缘折射）。
        //    矩阵复合：frost 像素 q → 清晰档坐标 (sx·qx, sy·qy) → host → intermediate，
        //    故 M = Half·Trans·matrix·Scale(sx,sy)——首个缩放必须 preScale（同 drawAgsl，
        //    postScale 会把缩放作用到最终 intermediate 坐标上导致取样错位）。
        float sx = (float) bmp.getWidth() / frostBmp.getWidth();
        float sy = (float) bmp.getHeight() / frostBmp.getHeight();
        drawMatrix.set(matrix);
        drawMatrix.preScale(sx, sy);
        drawMatrix.postTranslate(margin, margin);
        drawMatrix.postScale(halfScale, halfScale);
        intermediateCanvas.drawBitmap(frostBmp, drawMatrix, backdropPaint);

        // 2) 网格折射位移（对应 Kyant lens(height, amount)）：边缘顶点沿内法线收拢，
        //    边缘像素从轮廓外最多 amount 处取样（透镜壁弯折）。
        //    drawBitmapMesh 的源点是位图上的均匀网格（不可重映射），顶点只能给目标位置；
        //    任何只作用于目标点的 remap 都会变成空间扭曲（内部重影），故源点保持均匀。
        //    顶点目标位置仅依赖几何（尺寸/口径/形状），已在 ensureShaders 预算，绘制帧零计算。
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawBitmapMesh(intermediate, meshCols, meshRows, verts, 0, null, 0, meshPaint);
        canvas.restore();
    }

    /** 带符号边缘距离（&lt;0 在玻璃内）。圆角矩形用解析 SDF；圆形（正方形视图）用圆 SDF。 */
    private float sdf(float px, float py, int w, int h) {
        if (oval) {
            float dx = px - w / 2f;
            float dy = py - h / 2f;
            return (float) Math.sqrt(dx * dx + dy * dy) - Math.min(w, h) / 2f;
        }
        float r = cornerRadius;
        float qx = Math.abs(px - w / 2f) - (w / 2f - r);
        float qy = Math.abs(py - h / 2f) - (h / 2f - r);
        float ax = Math.max(qx, 0f);
        float ay = Math.max(qy, 0f);
        return (float) Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0f) - r;
    }

    /**
     * 尺寸变化时重建裁剪/描边/内阴影与折射资源（色值昼夜由资源限定符自动切换）。
     * AGSL 路径只建 RuntimeShader（背景位图经 BitmapShader 注入，无中间位图）；
     * mesh 路径建半尺度中间图 + 网格。绘制帧内零分配。
     */
    private void ensureShaders(int w, int h) {
        if (cachedW == w && cachedH == h) return;
        cachedW = w;
        cachedH = h;
        Context ctx = host.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        clipPath = new Path();
        edgePath = new Path();
        innerShadowPath = new Path();
        semanticStrokePath = new Path();
        float inset = 0.75f;
        // 语义描边内缩量 = 半个描边宽（semanticStrokePaint 宽度 1.5dp，构造时按
        // displayDensity 已换算为 px，与 ensureShaders 的 density 同源），
        // 保证 1.5dp 语义环完整落在视图边界内、外缘带抗锯齿不被裁切。
        float semanticInset = semanticStrokePaint.getStrokeWidth() * 0.5f;
        if (oval) {
            clipPath.addOval(0, 0, w, h, Path.Direction.CW);
            edgePath.addOval(inset, inset, w - inset, h - inset, Path.Direction.CW);
            semanticStrokePath.addOval(semanticInset, semanticInset,
                    w - semanticInset, h - semanticInset, Path.Direction.CW);
            innerShadowPath.addOval(inset, inset, w - inset, h - inset, Path.Direction.CW);
        } else {
            clipPath.addRoundRect(0, 0, w, h, cornerRadius, cornerRadius, Path.Direction.CW);
            edgePath.addRoundRect(inset, inset, w - inset, h - inset,
                    Math.max(0f, cornerRadius - inset), Math.max(0f, cornerRadius - inset),
                    Path.Direction.CW);
            semanticStrokePath.addRoundRect(semanticInset, semanticInset,
                    w - semanticInset, h - semanticInset,
                    Math.max(0f, cornerRadius - semanticInset),
                    Math.max(0f, cornerRadius - semanticInset), Path.Direction.CW);
            innerShadowPath.addRoundRect(inset, inset, w - inset, h - inset,
                    Math.max(0f, cornerRadius - inset), Math.max(0f, cornerRadius - inset),
                    Path.Direction.CW);
        }
        // 边缘描边：顶部亮（Highlight.Default）→ 中段几乎消失 → 底部回光 + 细 border
        edgePaint.setStrokeWidth(1.5f);
        edgePaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{
                ContextCompat.getColor(ctx, R.color.glass_highlight_top),
                0x00FFFFFF,
                ContextCompat.getColor(ctx, R.color.glass_highlight_bottom)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        // 内阴影：底部为主、顶部无，柔和暗带只保留在形状内侧（外半被 clip 裁掉）。
        // 按短边自适应收缩：8dp 暗带在 30dp 高的 chip 上会占掉四分之一，压成小件的「贴纸感」
        float minDimPx = Math.min(w, h);
        float shadowDp = Math.min(INNER_SHADOW_DP, minDimPx / density * 0.18f);
        float shadowRadiusPx = shadowDp * density;
        innerShadowPaint.setStrokeWidth(shadowRadiusPx * 2f);
        innerShadowPaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{
                0x00000000,
                ContextCompat.getColor(ctx, R.color.glass_inner_shadow)},
                new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        innerShadowPaint.setMaskFilter(new BlurMaskFilter(shadowRadiusPx * 0.6f,
                BlurMaskFilter.Blur.NORMAL));

        if (useAgsl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (lensShader == null) {
                RuntimeShader shared = acquireSharedShader();
                if (shared != null) {
                    lensShader = shared;
                } else {
                    // 编译异常：整实例降级到 drawBitmapMesh 回退
                    useAgsl = false;
                }
            }
            if (useAgsl) {
                // AGSL 路径无中间位图：释放 mesh 路径的遗留资源
                if (intermediate != null) {
                    intermediate.recycle();
                    intermediate = null;
                    intermediateCanvas = null;
                }
                return;
            }
        }

        // mesh 回退：折射外推边距 = lensAmount + 少量余量
        margin = Math.round(lensAmountDp * density) + 2;
        int iw = Math.max(1, Math.round((w + 2 * margin) * halfScale));
        int ih = Math.max(1, Math.round((h + 2 * margin) * halfScale));
        if (intermediate != null) intermediate.recycle();
        intermediate = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        intermediateCanvas = new Canvas(intermediate);
        // 折射口径与 draw() 同公式钳制；顶点几何只依赖尺寸/口径/形状，可一次性预算
        meshBandPx = Math.min(lensHeightDp * density, Math.min(w, h) * 0.16f);
        meshAmountPx = Math.min(lensAmountDp * density, Math.min(w, h) * 0.14f);
        // 网格密度：带宽内 ≥6 格。旧值按 lensHeight/2.5 推导，带宽内恒定仅 ~2.5 格，
        // 平滑位移曲线被 2 段线性逼近，折射内容读作被切分的小块（drawBitmapMesh 为
        // 分片双线性插值，单元边界处导数不连续，高对比内容上呈可见折痕）。
        float cell = Math.max(2f, meshBandPx * halfScale / 6f);
        meshCols = Math.max(3, Math.min(96, Math.round(iw / cell)));
        meshRows = Math.max(3, Math.min(96, Math.round(ih / cell)));
        verts = new float[(meshCols + 1) * (meshRows + 1) * 2];
        buildMeshVertices(w, h);
    }

    /** 预计算 mesh 顶点目标位置：仅几何依赖（尺寸/口径/形状），与采样内容和滚动位置无关。 */
    private void buildMeshVertices(int w, int h) {
        int iw = intermediate.getWidth();
        int ih = intermediate.getHeight();
        float band = meshBandPx;
        float maxDisp = meshAmountPx;
        int idx = 0;
        for (int row = 0; row <= meshRows; row++) {
            float iy = ih * row / (float) meshRows;
            float ly = iy / halfScale - margin;
            for (int col = 0; col <= meshCols; col++) {
                float ix = iw * col / (float) meshCols;
                float lx = ix / halfScale - margin;
                float d = sdf(lx, ly, w, h);
                float dispX = 0f;
                float dispY = 0f;
                if (d > -band) {
                    // influence: 轮廓处 1 → 带深 0，平方衰减模拟参考库的位移曲线
                    float influence = Math.max(0f, Math.min(1f, 1f + d / band));
                    float disp = maxDisp * influence * influence;
                    if (disp > 0f) {
                        float e = 0.5f;
                        float nx = sdf(lx + e, ly, w, h) - sdf(lx - e, ly, w, h);
                        float ny = sdf(lx, ly + e, w, h) - sdf(lx, ly - e, w, h);
                        float len = (float) Math.sqrt(nx * nx + ny * ny);
                        if (len > 1e-4f) {
                            dispX = nx / len * disp;
                            dispY = ny / len * disp;
                        }
                    }
                }
                verts[idx++] = lx - dispX;
                verts[idx++] = ly - dispY;
            }
        }
    }

    /**
     * 按压液态形变（对应 LiquidButton 的 pressExpansion + InteractiveHighlight）：
     * 按下时放大 {@link #PRESS_EXPANSION_DP} 并向指尖方向微位移（tanh 曲线），
     * 移动时实时跟随，抬起/取消弹回。不消费事件，click/涟漪/长按行为不变。
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void applyPressDeformation(View host) {
        float density = host.getResources().getDisplayMetrics().density;
        float expansion = PRESS_EXPANSION_DP * density;
        float[] offset = new float[2];
        // 当前进度由 scaleX 反推：progress = (scaleX - 1) * height / expansion
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(180L);
        animator.addUpdateListener(a -> applyDeformTransform(host, offset,
                (float) a.getAnimatedValue(), expansion));
        host.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    offset[0] = ev.getX() - v.getWidth() / 2f;
                    offset[1] = ev.getY() - v.getHeight() / 2f;
                    animator.cancel();
                    animator.setFloatValues(currentProgress(host, expansion), 1f);
                    animator.setDuration(120L);
                    animator.start();
                    break;
                case MotionEvent.ACTION_MOVE:
                    offset[0] = ev.getX() - v.getWidth() / 2f;
                    offset[1] = ev.getY() - v.getHeight() / 2f;
                    if (!animator.isRunning()) {
                        // 按住不动时动画已结束：实时重算形变，保证指尖跟随
                        applyDeformTransform(host, offset, 1f, expansion);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animator.cancel();
                    animator.setFloatValues(Math.max(0f, currentProgress(host, expansion)), 0f);
                    animator.setDuration(200L);
                    animator.start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 由当前 scaleX 反按压进度，供中断动画时无缝续接。 */
    private static float currentProgress(View host, float expansion) {
        float h = Math.max(1f, host.getHeight());
        return Math.max(0f, Math.min(1f, (host.getScaleX() - 1f) * h / expansion));
    }

    /** 进度 t 应用形变：放大 + 向指尖方向 tanh 位移（对应 InteractiveHighlight 的 layerBlock）。 */
    private static void applyDeformTransform(View host, float[] offset, float t, float expansion) {
        float h = Math.max(1f, host.getHeight());
        float minDim = Math.max(1f, Math.min(host.getWidth(), h));
        float scale = 1f + expansion / h * t;
        host.setScaleX(scale);
        host.setScaleY(scale);
        host.setTranslationX((float) (minDim * Math.tanh(0.05 * offset[0] / minDim) * t));
        host.setTranslationY((float) (minDim * Math.tanh(0.05 * offset[1] / minDim) * t));
    }

    /** 提供不透明圆角轮廓：MaterialCardView 等容器据此裁剪子内容、卡片阴影形状正确。 */
    @Override
    public void getOutline(@NonNull Outline outline) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (oval) outline.setOval(b);
        else outline.setRoundRect(b, cornerRadius);
    }

    @Override
    public void setAlpha(int alpha) {
        // 透镜本体不参与透明度降级（折射底衬 + 材质栈即最终观感）；
        // 透明度转发给回退 drawable：底栏等宿主在无背景图时保留原半透明表面观感
        if (fallbackAlpha != alpha) {
            fallbackAlpha = alpha;
            if (fallback != null) fallback.setAlpha(alpha);
            invalidateSelf();
        }
    }

    /**
     * 设置语义描边色（状态色细环）。0 = 清除。
     * 状态表达方式：玻璃恒为玻璃，状态由描边 + 图标/文字色承载（对应选定的
     * 「玻璃底 + 语义色描边」方案），不再整块换填充色。
     */
    public void setSemanticStrokeColor(int color) {
        if (semanticStrokeColor == color) return;
        semanticStrokeColor = color;
        semanticStrokePaint.setColor(color);
        invalidateSelf();
    }

    /** 语义描边绘制（玻璃模式与回退模式都生效；专用路径未就绪时跳过）。 */
    private void drawSemanticStroke(Canvas canvas) {
        if (semanticStrokeColor != 0 && semanticStrokePath != null) {
            canvas.drawPath(semanticStrokePath, semanticStrokePaint);
        }
    }

    /**
     * 替换回退 drawable（无背景图时显示的静态表面）。
     * 供状态圆（icon_genshin/zzz）这类「状态靠换底表达」的宿主使用：
     * 玻璃始终是玻璃，状态色只体现在回退底上。
     */
    public void setFallbackDrawable(Drawable drawable) {
        if (fallback == drawable) return;
        fallback = drawable;
        if (fallback != null) fallback.setAlpha(fallbackAlpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
