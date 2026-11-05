package com.muxiao.Venus.common.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃外观规格：单颗玻璃的**全部可调指标**。
 *
 * 设计目标：把库 `Modifier.drawBackdrop` 渲染链上的每个环节抽成独立字段，调整观感只改
 * 数据、不动渲染逻辑。字段顺序即官方规定的效果应用顺序——颜色滤镜 ⇒ 模糊 ⇒ 透镜，
 * 其后是表面材质与边缘光/阴影。每个字段上方都有独立注释，调参时直接对照即可。
 *
 * 全局调参改 [GlassSpecs] 预设；个别调用点用 `copy(...)` 覆盖，例如：
 * ```
 * LensGlass(spec = GlassSpecs.Standard.copy(blurRadiusDp = 0f), circle = true)
 * ```
 *
 * 兼容性：blur 仅 API 31+ 生效，lens / 边缘光 AGSL 仅 API 33+ 生效；更低版本由库内部
 * 静默跳过对应效果，玻璃退化为半透明表面材质（缺磨砂/折射，材质仍在）。
 */
@Immutable
data class GlassSpec(
    // ===== 1. 颜色滤镜（最先应用，作用于背后内容，库 colorControls）=====

    /** 亮度偏移：0 = 不调整；正值变亮、负值变暗。 */
    val brightness: Float = 0f,

    /** 对比度：1 = 不调整；>1 明暗更分明，<1 更柔和。 */
    val contrast: Float = 1f,

    /** 饱和度：1 = 原色；1.5 即官方 vibrancy，让透过玻璃的颜色更鲜艳；调低会发灰。 */
    val saturation: Float = 1.5f,

    // ===== 2. 高斯模糊（磨砂感，仅 API 31+）=====

    /** 模糊半径（dp）：0 = 不模糊（纯靠折射，最通透）；2~8dp 磨砂感递增。 */
    val blurRadiusDp: Float = 1f,

    /** 模糊边缘处理：默认 Clamp = 拉伸边缘像素、无透明缝，一般不需要改。 */
    val blurEdgeTreatment: TileMode = TileMode.Clamp,

    // ===== 3. 透镜折射（玻璃“镜片”效果，仅 API 33+，库 lens）=====

    /** 边缘折射高度（dp）：决定“镜片厚度”，建议 ≤ 形状最小圆角。 */
    val refractionHeightDp: Float = 16f,

    /** 折射位移量（dp）：越大边缘扭曲越明显，建议 ≤ 组件最小边长。 */
    val refractionAmountDp: Float = 12f,

    /** 纵向景深：开启后边缘带轻微明暗立体感（库 lens 的 depthEffect）。 */
    val depthEffect: Boolean = true,

    /** 色散：开启后边缘出现 RGB 分离的彩虹边（库 lens 的 chromaticAberration）。 */
    val chromaticAberration: Boolean = true,

    // ===== 4. 表面材质（半透明纯色，叠在折射上，保证文字可读）=====

    /** 表面颜色：默认 Unspecified = 由 LensGlass 自动取主题 surfaceContainer（跟随 6 套主题与日夜）；显式指定则覆盖。 */
    val surfaceColor: Color = Color.Unspecified,

    /** 表面不透明度（0..1）：越大文字越清晰、通透感越弱；官方口径 0.05~0.5；低版本无 lens 时这是唯一的玻璃材质。 */
    val surfaceAlpha: Float = 0.2f,

    // ===== 5. 边缘光 / 阴影（传 null = 关闭该环节）=====

    /** 边缘高光：玻璃边缘那圈亮边；null = 关闭。 */
    val highlight: Highlight? = Highlight.Default,

    /** 外投影：玻璃悬浮在背景上的投影；null = 关闭。 */
    val shadow: Shadow? = Shadow.Default,

    /** 内阴影：玻璃边缘向内的暗边；默认 null = 不画。 */
    val innerShadow: InnerShadow? = null,
)

/**
 * 玻璃外观预设（按使用场景命名）。**全局调参改这里的字段即可**；个别调用点再用
 * `copy()` 就地微调。每个预设都是不可变单例，可安全地在任意组合/线程间共享。
 */
object GlassSpecs {

    /** 页内通用玻璃（卡片 / 按钮 / 图标圆 / 芯片）：官方 vibrancy + 轻模糊 + 透镜口径。 */
    val Standard: GlassSpec = GlassSpec()

    /** 底栏悬浮面板：边缘镜片更厚（18/26 标定）。 */
    val BottomBar: GlassSpec = GlassSpec(
        refractionHeightDp = 18f,
        refractionAmountDp = 26f,
        surfaceAlpha = 0.4f,
    )

    /** 底栏选中态药丸：小折射 + 更轻表面，语义独立便于单独调整。 */
    val BottomBarIndicator: GlassSpec = GlassSpec(
        refractionHeightDp = 12f,
        refractionAmountDp = 24f,
        surfaceAlpha = 0.5f,
    )

    /**
     * 纯折射口径（官方滚动卡 / 控制中心）：**关闭模糊**、表面极轻，最通透、最贴近
     * Apple Liquid Glass 的清透感，适合壁纸本身干净、或想最大化折射表现时。
     */
    @Suppress("unused") // 设计系统可选预设：当前界面未采用，作为通透风格规格随预设组保留
    val Luminous: GlassSpec = GlassSpec(
        blurRadiusDp = 0f,
    )
}
