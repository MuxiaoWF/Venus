package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.compose.material3.ColorScheme
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
 * Venus 的 Compose 主题（增量迁移专用，最小实现）。
 *
 * 设计约束来自项目既有主题体系，务必保留：
 * 1. 应用主题是 `Theme.Material3Expressive.DynamicColors.DayNight`，且运行时通过
 *    `getTheme().applyStyle(ThemeOverlay.Venus.Xxx)` 切换 6 套调色板（见 Setting 页配色选择）。
 *    因此**不能**在 Compose 侧写死色值——这里逐项读取当前 theme 已解析的属性值，
 *    保证 Compose 与 XML 视图共用同一套颜色，切主题/切日夜自动跟随。
 * 2. 只映射本阶段迁移布局真正用到的角色，不整体搬运 XML 主题（迁移 skill 的约束）。
 * 3. 字号沿用 `TextAppearance.Venus.*` 的既有刻度（DrawerLabel = 13sp）。
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
                // 对应 TextAppearance.Venus.DrawerLabel（BodyMedium + 13sp + colorOnSurface）
                drawerLabel = typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colorScheme.onSurface,
                ),
                // 对应 TextAppearance.Venus.Body（BodyMedium，颜色由调用方或 LocalContentColor 决定）
                body = typography.bodyMedium,
                // 对应 TextAppearance.Venus.Body.Small（辅助说明文字）
                bodySmall = typography.bodySmall,
                // 对应 TextAppearance.Venus.Label.Medium（按钮内小号标签）
                labelMedium = typography.labelMedium,
                // 对应 TextAppearance.Venus.Label.Large（行内大号标签，如取消任务/用户管理操作）
                labelLarge = typography.labelLarge,
                // 对应 TextAppearance.Venus.Title（TitleMedium.Emphasized）
                title = typography.titleMedium,
                // 对应 TextAppearance.Venus.Title.Large（TitleLarge.Emphasized）
                titleLarge = typography.titleLarge,
                // 对应 TextAppearance.Venus.ToolbarTitle（HeadlineSmall.Emphasized）
                toolbarTitle = typography.headlineSmall,
                // 对应 TextAppearance.Venus.RowSubtitle（BodySmall + colorOnSurfaceVariant）
                rowSubtitle = typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
                // 对应 TextAppearance.Venus.Eyebrow（等宽 10sp 粗体，字距 0.15）
                eyebrow = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.15.em,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 对应 Widget.Venus.SectionLabel（Eyebrow + 字距 0.25 的区块标签）
                sectionLabel = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.25.em,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 对应 TextAppearance.Venus.HeaderTitle（28sp Emphasized）
                headerTitle = typography.headlineLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).em,
                    color = colorScheme.onSurface,
                ),
                // 对应 TextAppearance.Venus.HeaderSubtitle（13sp 副标题）
                headerSubtitle = typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 对应 TextAppearance.Venus.Body.Large
                bodyLarge = typography.bodyLarge,
                // 对应 TextAppearance.Venus.RowTitle（BodyLarge + colorOnSurface）
                rowTitle = typography.bodyLarge.copy(color = colorScheme.onSurface),
                // 对应 TextAppearance.Venus.MonoValue（等宽 10sp 值摘要）
                monoValue = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                ),
                // 链接结果 URL：等宽 labelSmall，沿用其默认 11sp 字号（不覆盖），主色 onSurface
                urlMono = typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface,
                ),
            )
        }
        CompositionLocalProvider(LocalVenusTextStyles provides textStyles, content = content)
    }
}

/** Venus 语义文本样式（对齐 res/values/styles.xml 的 TextAppearance.Venus.*）。 */
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
 * 把当前 theme 的 M3 颜色角色映射为 Compose [ColorScheme]。
 *
 * 用 [MaterialColors.getColor] 读取已解析值（而非资源里的字面量），
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

    // 日夜两个构造器只为让 Compose 侧正确判定亮暗（影响默认内容色/涟漪等内部行为）；
    // 具体色值都来自当前 theme，因此两套传同一批角色。
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
