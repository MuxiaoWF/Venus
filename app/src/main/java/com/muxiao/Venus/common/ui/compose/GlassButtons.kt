package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.muxiao.Venus.R

/**
 * 圆形玻璃按钮（外观 + 触控），内容由调用方给。
 *
 * <h3>为什么单开一层「只做外观」的 API</h3>
 * 顶栏按钮的内芯不一定是图标：全屏看图的下载按钮在下载中要换成不定长进度环。
 * 若只有 [GlassIconButton]，那种场景就得把玻璃表面重写一遍（正是本次要收敛掉的分叉）。
 *
 * <h3>层次顺序</h3>
 * ```
 * Box(size = touch_target_min)    ← 栅格 + 触控
 * ├── Box(size = 视觉直径)
 * │   ├── LensGlass(circle)       ← 玻璃表面（折射/磨砂/边缘光）
 * │   └── content()               ← 图标或进度环
 * └── Box(clickable)              ← 涟漪必须画在玻璃**之上**，否则被透镜遮住；
 *                                    clip(CircleShape) 让涟漪跟着圆裁，而不是方形水波
 * ```
 *
 * <h3>尺寸：视觉 40dp / 占位与触控 48dp</h3>
 * ```
 * Box(size = touch_target_min 48dp)      ← 栅格与触控范围，与 IconButton 一致，替换时行高不动
 * └── Box(size = icon_button_glass 40dp) ← 玻璃圆的实际直径（居中）
 * ```
 * 玻璃画满 48dp 时在 56dp 顶栏行里上下只剩 4dp、视觉上压过标题，所以直径单独收一档；
 * 触控仍按 48dp 判定，不影响可点性。
 *
 * 采样源缺失（独立窗口未接 `VenusBackdropHost`）时按 [LensGlass] 的既定降级：
 * 只画半透明表面 + 边缘光，不报错、不闪。
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
                // role = Button：读屏把这一圈识别成按钮（图标本身只是内容，不该被单独聚焦）
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        )
    }
}

/**
 * 顶栏图标按钮：玻璃圆 + 居中图标。
 *
 * 所有带返回/关闭符号的顶栏统一用它（图库、全屏看图、登录、OAuth、云游戏抓取），
 * 不要再写 `IconButton`：`IconButton` 只有圆形涟漪、没有实体，与页面其它玻璃控件不成体系。
 *
 * @param iconModifier 图标自身的修饰（如 chevron 用 `rotate(90f)` 转成返回箭头）
 * @param tint 图标色；默认取主题 `onSurface`（浅色照片/深色玻璃上可传 `Color.White`）
 */
@Composable
fun GlassIconButton(
    @DrawableRes iconRes: Int,
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
            painter = painterResource(iconRes),
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
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "返回",
                onClick = {},
            )
            GlassIconButton(
                iconRes = R.drawable.ic_chevron,
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
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}
