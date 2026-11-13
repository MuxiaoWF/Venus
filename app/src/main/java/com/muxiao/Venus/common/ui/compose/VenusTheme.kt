package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Venus 的 Compose 主题：运行时通过 `applyStyle(ThemeOverlay.Venus.Xxx)` 切换 6 套调色板，
 * 不能写死色值，故逐项读取 theme 已解析属性，与 XML 视图共用同一套颜色。
 * 只映射当前布局用到的角色；字号按角色在此定义（对应 XML 的 TextAppearance 体系已随 styles.xml 删除）。
 */
@Composable
fun VenusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = LocalConfiguration.current.isNightMode()
    val colorScheme = remember(context, dark) { context.venusColorScheme(dark) }

    MaterialTheme(colorScheme = colorScheme) {
        val typography = MaterialTheme.typography
        val textStyles = remember(colorScheme, typography) {
            VenusTextStyles(
                drawerLabel = typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colorScheme.onSurface,
                ),
                // 颜色由调用方或 LocalContentColor 决定
                body = typography.bodyMedium,
                bodySmall = typography.bodySmall,
                labelMedium = typography.labelMedium,
                labelLarge = typography.labelLarge,
                title = typography.titleMedium,
                titleLarge = typography.titleLarge,
                toolbarTitle = typography.headlineSmall,
                rowSubtitle = typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
                eyebrow = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.15.em,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 对应 Widget.Venus.SectionLabel
                sectionLabel = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.25.em,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant,
                ),
                headerTitle = typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).em,
                    color = colorScheme.onSurface,
                ),
                headerSubtitle = typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                ),
                bodyLarge = typography.bodyLarge,
                rowTitle = typography.bodyLarge.copy(color = colorScheme.onSurface),
                monoValue = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 链接结果 URL：沿用 labelSmall 默认 11sp，不覆盖字号
                urlMono = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface,
                ),
            )
        }
        // LocalContentColor 默认是 Color.Black 而非主题色：页面根多为普通 Column/Box +
        // 壁纸背景，未指定颜色的 Text 在深色下会看不见，故钉在 onSurface；
        // 自带容器色的组件（Button/Card/Surface/输入框）各自覆盖，不受影响。
        CompositionLocalProvider(
            LocalVenusTextStyles provides textStyles,
            LocalContentColor provides colorScheme.onSurface,
            content = content,
        )
    }
}

/** Venus 语义文本样式：字号/字距在此定义，经 `LocalVenusTextStyles` 取用（styles.xml 的 TextAppearance 体系已删除）。 */
@Immutable
class VenusTextStyles internal constructor(
    val drawerLabel: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val labelMedium: TextStyle,
    val labelLarge: TextStyle,
    val title: TextStyle,
    val titleLarge: TextStyle,
    val toolbarTitle: TextStyle,
    val rowSubtitle: TextStyle,
    val eyebrow: TextStyle,
    val sectionLabel: TextStyle,
    val headerTitle: TextStyle,
    val headerSubtitle: TextStyle,
    val bodyLarge: TextStyle,
    val rowTitle: TextStyle,
    val monoValue: TextStyle,
    val urlMono: TextStyle,
)

/** 仅供 VenusTheme 内部提供；未包裹 VenusTheme 时直接抛错，避免静默用错字号/颜色。 */
val LocalVenusTextStyles = staticCompositionLocalOf<VenusTextStyles> {
    error("Compose UI 必须包裹在 VenusTheme { } 内")
}

private fun Configuration.isNightMode(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * 把当前 theme 的 M3 角色映射为 [ColorScheme]：必须用 [MaterialColors.getColor] 读已解析值，
 * 才能跟上 DynamicColors 取色与 ThemeOverlay 运行时切换。
 */
private fun android.content.Context.venusColorScheme(dark: Boolean): ColorScheme {
    // colorPrimary / colorError 由 appcompat 声明，其余 M3 角色由 material 声明
    val primary = themeColor(AppCompatR.attr.colorPrimary)
    val onPrimary = themeColor(MaterialR.attr.colorOnPrimary)
    val primaryContainer = themeColor(MaterialR.attr.colorPrimaryContainer)
    val onPrimaryContainer = themeColor(MaterialR.attr.colorOnPrimaryContainer)
    val secondary = themeColor(MaterialR.attr.colorSecondary)
    val secondaryContainer = themeColor(MaterialR.attr.colorSecondaryContainer)
    val onSecondaryContainer = themeColor(MaterialR.attr.colorOnSecondaryContainer)
    val tertiary = themeColor(MaterialR.attr.colorTertiary)
    val tertiaryContainer = themeColor(MaterialR.attr.colorTertiaryContainer)
    val surface = themeColor(MaterialR.attr.colorSurface)
    val onSurface = themeColor(MaterialR.attr.colorOnSurface)
    val onSurfaceVariant = themeColor(MaterialR.attr.colorOnSurfaceVariant)
    val outline = themeColor(MaterialR.attr.colorOutline)
    val outlineVariant = themeColor(MaterialR.attr.colorOutlineVariant)
    val surfaceContainer = themeColor(MaterialR.attr.colorSurfaceContainer, surface)
    val surfaceContainerLow = themeColor(MaterialR.attr.colorSurfaceContainerLow, surfaceContainer)
    val surfaceContainerLowest = themeColor(MaterialR.attr.colorSurfaceContainerLowest, surface)
    val surfaceContainerHigh = themeColor(MaterialR.attr.colorSurfaceContainerHigh, surfaceContainer)
    val surfaceContainerHighest = themeColor(MaterialR.attr.colorSurfaceContainerHighest, surfaceContainerHigh)
    val error = themeColor(AppCompatR.attr.colorError)
    val onError = themeColor(MaterialR.attr.colorOnError)

    // 日夜构造器只为让 Compose 判定亮暗（影响默认内容色/涟漪等），色值都来自当前 theme，故两套传同一批角色。
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            tertiaryContainer = tertiaryContainer,
            surface = surface,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            error = error,
            onError = onError,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            tertiaryContainer = tertiaryContainer,
            surface = surface,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            error = error,
            onError = onError,
        )
    }
}

/** 读取 theme 属性色；属性缺失时回落到 [fallback]（默认取当前 theme 的 colorSurface）。 */
internal fun android.content.Context.themeColor(
    @AttrRes attr: Int,
    fallback: Color = Color.Unspecified,
): Color {
    val resolvedFallback = if (fallback == Color.Unspecified) {
        Color(MaterialColors.getColor(this, MaterialR.attr.colorSurface, android.graphics.Color.TRANSPARENT))
    } else {
        fallback
    }
    return Color(MaterialColors.getColor(this, attr, resolvedFallback.toArgb()))
}
