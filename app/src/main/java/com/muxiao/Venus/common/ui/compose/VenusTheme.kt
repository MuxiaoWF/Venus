package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.muxiao.Venus.Setting.SettingsFragment

/**
 * Venus 的 Compose 主题：语义色单一事实源在 [VenusColorSchemes]（Kotlin 常量 + material3
 * 动态取色），按用户选择的主题下标与深浅色直接取 ColorScheme，不再读 XML 主题属性。
 * 字号按角色在此定义（对应 XML 的 TextAppearance 体系已随 styles.xml 删除）。
 */
@Composable
fun VenusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = LocalConfiguration.current.isNightMode()
    // 主题偏好切换经 Activity recreate 生效（SettingsFragment.recreate()），remember 即可
    val themeIndex = remember(context) { SettingsFragment.getSelectedThemeIndex(context) }
    val colorScheme = remember(context, dark, themeIndex) {
        VenusColorSchemes.forIndex(context, themeIndex, dark)
    }

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
