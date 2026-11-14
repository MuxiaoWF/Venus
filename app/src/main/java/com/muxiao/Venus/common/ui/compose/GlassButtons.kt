package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.muxiao.Venus.R

/**
 * 圆形玻璃按钮（外观 + 触控），内容由调用方给。
 *
 * 单开「只做外观」一层是因为内芯不一定是图标：全屏看图的下载按钮要换成不定长进度环，
 * 若只有 [GlassIconButton] 就得把玻璃表面重写一遍。
 *
 * 层次：Box(48dp，栅格+触控) → Box(视觉直径) 内嵌 LensGlass(circle) + content()；涟漪层必须
 * 画在玻璃**之上**（否则被透镜遮住），并 clip(CircleShape) 让涟漪随圆裁而非方形水波。
 *
 * 尺寸：视觉直径 40dp（icon_button_glass）、占位与触控 48dp（touch_target_min，与 IconButton
 * 一致，替换时行高不动）。玻璃画满 48dp 在 56dp 顶栏行里上下只剩 4dp、视觉压过标题，故直径
 * 单独收一档；触控仍按 48dp 判定。
 *
 * 采样源缺失（独立窗口未接 `VenusBackdropHost`）时按 [LensGlass] 降级：只画半透明表面，
 * 不报错、不闪。
 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    spec: GlassSpec = GlassSpecs.Standard,
    diameter: Dp = dimensionResource(R.dimen.icon_button_glass),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.size(dimensionResource(R.dimen.touch_target_min)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            LensGlass(modifier = Modifier.matchParentSize(), spec = spec, circle = true)
            content()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                // role = Button：读屏把这一圈识别成按钮，图标只是内容、不单独聚焦
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        )
    }
}

/**
 * 顶栏图标按钮：玻璃圆 + 居中图标。带返回/关闭符号的顶栏统一用它，不要再写 `IconButton`
 * ——后者只有圆形涟漪、没有实体，与页面其它玻璃控件不成体系。
 *
 * @param iconModifier 图标自身修饰（如 chevron 用 `rotate(90f)` 转成返回箭头）
 * @param tint 图标色，默认主题 `onSurface`（浅色照片/深色玻璃上可传 `Color.White`）
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    spec: GlassSpec = GlassSpecs.Standard,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconModifier: Modifier = Modifier,
) {
    GlassCircleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        spec = spec,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = iconModifier,
        )
    }
}

@Preview(
    name = "玻璃圆按钮 · 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GlassIconButtonPreview() {
    VenusTheme {
        Row(
            modifier = Modifier.padding(dimensionResource(R.dimen.space_4)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = VenusIcons.ArrowBack,
                contentDescription = "返回",
                onClick = {},
            )
            GlassIconButton(
                icon = VenusIcons.ChevronDown,
                contentDescription = "关闭",
                onClick = {},
                iconModifier = Modifier.rotate(90f),
            )
            // 照片/暗底口径：白表面 + 白图标
            GlassCircleButton(
                onClick = {},
                spec = GlassSpecs.Standard.copy(surfaceColor = Color.White, surfaceAlpha = 0.16f),
            ) {
                Icon(
                    imageVector = VenusIcons.Download,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}
