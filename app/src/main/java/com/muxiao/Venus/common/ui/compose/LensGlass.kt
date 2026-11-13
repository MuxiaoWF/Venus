package com.muxiao.Venus.common.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.muxiao.Venus.R

/**
 * 液态玻璃透镜（Kyant0/AndroidLiquidGlass 薄封装）。
 *
 * 渲染链路（库 `Modifier.drawBackdrop`）：采样源层 → 颜色滤镜 → GPU 高斯模糊（0 则跳过）
 * → AGSL 透镜折射（含可选景深/色散）→ 边缘光 / 外投影 / 内阴影 → 半透明纯色表面 → 内容。
 *
 * 所有外观指标收口在 [spec]（[GlassSpec]），本文件不持可调外观常量。表面色默认取主题
 * `surfaceContainer`；不要额外叠渐变 drawable——与真 lens 重复堆叠会发白发灰。
 *
 * 兼容性降级（minSdk 23）：blur ≥ API 31，lens / 边缘光（RuntimeShader）≥ API 33；更低版本
 * 由库静默跳过，退化为半透明表面（材质仍在，缺折射/磨砂）。
 *
 * 不挂按压形变与涟漪，点击反馈由上层内容的 clickable 提供。无采样链（[backdrop] 与
 * [LocalGlassBackdrop] 均为 null，如独立窗口）时采样 `emptyBackdrop`，仅绘制半透明表面。
 *
 * @param spec 外观规格，默认 [GlassSpecs.Standard]
 * @param cornerRadiusDp 与 [cornerRadiusPx] 二选一：资源本就是 dp，省掉调用处
 *        `with(density) { toPx() }`，新代码优先用它
 * @param backdrop 采样源。null = [LocalGlassBackdrop]（底色+壁纸，无自引用）；底栏玻璃显式
 *        传内容源（壁纸+页面，列表滚进玻璃下方）。见 GlassBackdrop.kt 文件头
 */
@Composable
fun LensGlass(
    modifier: Modifier = Modifier,
    spec: GlassSpec = GlassSpecs.Standard,
    circle: Boolean = false,
    pill: Boolean = false,
    cornerRadiusPx: Float = 0f,
    cornerRadiusDp: Dp? = null,
    semanticStrokeColor: Int = 0,
    backdrop: GlassBackdropState? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val state = backdrop ?: LocalGlassBackdrop.current
    val density = LocalDensity.current
    // 形状：圆 / 药丸（RoundedCornerShape(50) = 高度一半）/ 指定圆角。lens 要求 CornerBasedShape。
    val shape: Shape = when {
        circle -> CircleShape
        pill -> RoundedCornerShape(50)
        cornerRadiusDp != null -> RoundedCornerShape(cornerRadiusDp)
        else -> RoundedCornerShape(with(density) { cornerRadiusPx.toDp() })
    }
    val hairlineWidth = dimensionResource(R.dimen.stroke_width_hairline)

    // 表面色：显式指定优先，否则取主题 surfaceContainer（跟随换肤/日夜）。
    val resolvedSurface = if (spec.surfaceColor.isSpecified) {
        spec.surfaceColor
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    // 边缘光/阴影为 null 时整体传 null（库对应环节直接不挂载）；非 null 时包成取值 lambda。
    val highlightProvider: (() -> Highlight?)? = spec.highlight?.let { h -> { h } }
    val shadowProvider: (() -> Shadow?)? = spec.shadow?.let { s -> { s } }
    val innerShadowProvider: (() -> InnerShadow?)? = spec.innerShadow?.let { i -> { i } }

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = state?.backdrop ?: emptyBackdrop(),
                shape = { shape },
                highlight = highlightProvider,
                shadow = shadowProvider,
                innerShadow = innerShadowProvider,
                effects = {
                    // 效果顺序按库官方约定：颜色滤镜 ⇒ 模糊 ⇒ 透镜。
                    // blur/lens 在 API 31/33 以下由库内部静默跳过（见类头兼容性降级）。
                    colorControls(
                        brightness = spec.brightness,
                        contrast = spec.contrast,
                        saturation = spec.saturation,
                    )
                    if (spec.blurRadiusDp > 0f) {
                        blur(spec.blurRadiusDp.dp.toPx(), spec.blurEdgeTreatment)
                    }
                    lens(
                        refractionHeight = spec.refractionHeightDp.dp.toPx(),
                        refractionAmount = spec.refractionAmountDp.dp.toPx(),
                        depthEffect = spec.depthEffect,
                        chromaticAberration = spec.chromaticAberration,
                    )
                },
                onDrawSurface = {
                    // 半透明纯色表面（官方 onDrawSurface 模式）：叠在折射之上保证文字可读，
                    // 通透感由 surfaceAlpha 控制；低版本无 lens 时这是唯一的玻璃材质。
                    drawRect(color = resolvedSurface, alpha = spec.surfaceAlpha)
                    // 语义描边（如任务状态圈的状态色），沿透镜形状轮廓
                    if (semanticStrokeColor != 0) {
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = Color(semanticStrokeColor),
                            style = Stroke(hairlineWidth.toPx()),
                        )
                    }
                },
            )
            // 内容变化信号（GlassBackdrop.kt 文件头）：滚动/Pager/背景发布 bump contentVersion
            // → 同布局节点整链重画 → 重采样源层，是切页滚动全程折射实时跟随的关键。
            .then(
                state?.let { GlassMotionElement(it.contentVersion) } ?: Modifier
            ),
        content = content,
    )
}
