package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 主题圆点的序号常量（与 Java 侧 saveAndApplyTheme 的主题序号一致）。 */
const val THEME_INDEX_SYSTEM = 0
const val THEME_INDEX_BLUE = 1
const val THEME_INDEX_GREEN = 2
const val THEME_INDEX_RED = 3
const val THEME_INDEX_YELLOW = 4
const val THEME_INDEX_CYAN = 5
const val THEME_INDEX_PURPLE = 6

/** 深浅模式分段序号（对应 THEME_VARIANT_DEFAULT / LIGHT / DARK）。 */
const val THEME_VARIANT_INDEX_FOLLOW = 0
@Suppress("unused") // 三档分段常量集合的完整组成（跟随/浅色/深色），保留具名语义避免魔法数字
const val THEME_VARIANT_INDEX_LIGHT = 1
const val THEME_VARIANT_INDEX_DARK = 2

/**
 * 设置页「主题」抽屉内容：七枚主题圆点（FlowRow 流式换行）+ 深浅模式药丸分段。
 * 选中圆点用 onSurface 内描环表达（对应 bg_theme_dot_ring 的 foreground 覆盖）。
 *
 * @param selectedTheme 选中的主题序号（0 = 系统动态取色，1-6 = 蓝绿红黄青紫）
 * @param selectedVariant 深浅模式序号（0 跟随 / 1 浅色 / 2 深色）
 */
@Composable
fun ThemeSettingPane(
    selectedTheme: Int,
    onThemeSelect: (Int) -> Unit,
    selectedVariant: Int,
    onVariantSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        Text(
            text = stringResource(R.string.select_app_theme),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        FlowRow(
            modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_1)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_3)),
            // 360dp 上 7 枚圆点会折成两行，行距收紧到 4dp 省高度
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
        ) {
            ThemeDot(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                selected = selectedTheme == THEME_INDEX_SYSTEM,
                contentDescription = stringResource(R.string.theme_system),
                onClick = { onThemeSelect(THEME_INDEX_SYSTEM) },
                leadingIcon = painterResource(R.drawable.ic_brand),
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThemeDot(
                color = colorResource(R.color.blue_theme_primary),
                selected = selectedTheme == THEME_INDEX_BLUE,
                contentDescription = stringResource(R.string.theme_blue),
                onClick = { onThemeSelect(THEME_INDEX_BLUE) },
            )
            ThemeDot(
                color = colorResource(R.color.green_theme_primary),
                selected = selectedTheme == THEME_INDEX_GREEN,
                contentDescription = stringResource(R.string.theme_green),
                onClick = { onThemeSelect(THEME_INDEX_GREEN) },
            )
            ThemeDot(
                color = colorResource(R.color.red_theme_primary),
                selected = selectedTheme == THEME_INDEX_RED,
                contentDescription = stringResource(R.string.theme_red),
                onClick = { onThemeSelect(THEME_INDEX_RED) },
            )
            ThemeDot(
                color = colorResource(R.color.yellow_theme_primary),
                selected = selectedTheme == THEME_INDEX_YELLOW,
                contentDescription = stringResource(R.string.theme_yellow),
                onClick = { onThemeSelect(THEME_INDEX_YELLOW) },
            )
            ThemeDot(
                color = colorResource(R.color.cyan_theme_primary),
                selected = selectedTheme == THEME_INDEX_CYAN,
                contentDescription = stringResource(R.string.theme_cyan),
                onClick = { onThemeSelect(THEME_INDEX_CYAN) },
            )
            ThemeDot(
                color = colorResource(R.color.purple_theme_primary),
                selected = selectedTheme == THEME_INDEX_PURPLE,
                contentDescription = stringResource(R.string.theme_purple),
                onClick = { onThemeSelect(THEME_INDEX_PURPLE) },
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(
            text = stringResource(R.string.theme_variant_setting),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        val variantOptions = listOf(
            stringResource(R.string.theme_follow),
            stringResource(R.string.theme_light),
            stringResource(R.string.theme_dark),
        )
        // 分段控件收口到 VenusSegmented（玻璃轨道 + 实心选中段）
        VenusSegmented(
            options = variantOptions,
            selectedIndex = selectedVariant,
            onSelect = onVariantSelect,
        )
    }
}

/**
 * 单枚主题圆点：选中环 = 外圈内缩 2dp + onSurface 描边。不能用 `border` 直接画在外圈——
 * 环与外缘之间要留 2dp 间隙，贴着边缘描边会显得过粗。
 */
@Composable
private fun ThemeDot(
    color: Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: Painter? = null,
    iconTint: Color = Color.Unspecified,
) {
    Box(
        modifier = modifier
            .size(dimensionResource(R.dimen.theme_dot_size))
            .clip(CircleShape)
            // 纯色圆点无文字，必须显式挂 semantics，否则读屏只能读到“按钮”、无法区分主题。
            .semantics { this.contentDescription = contentDescription }
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(dimensionResource(R.dimen.space_hairline))
                    .border(
                        width = dimensionResource(R.dimen.stroke_width_selected),
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * [ThemeSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class ThemeSettingPaneView {

    private val themeState = mutableIntStateOf(THEME_INDEX_SYSTEM)
    private val variantState = mutableIntStateOf(THEME_VARIANT_INDEX_FOLLOW)

    private var onThemeSelectListener: IndexConsumer? = null
    private var onVariantSelectListener: IndexConsumer? = null

    /** 选中主题序号；Java 侧 setSelectedTheme(...) 直接触发重组。 */
    var selectedTheme: Int
        get() = themeState.intValue
        set(value) {
            themeState.intValue = value
        }

    var selectedVariant: Int
        get() = variantState.intValue
        set(value) {
            variantState.intValue = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            ThemeSettingPane(
                selectedTheme = themeState.intValue,
                onThemeSelect = { index ->
                    themeState.intValue = index
                    onThemeSelectListener?.accept(index)
                },
                selectedVariant = variantState.intValue,
                onVariantSelect = { index ->
                    variantState.intValue = index
                    onVariantSelectListener?.accept(index)
                },
            )
        }
    }

    fun setOnThemeSelectListener(listener: IndexConsumer?) {
        onThemeSelectListener = listener
    }

    fun setOnVariantSelectListener(listener: IndexConsumer?) {
        onVariantSelectListener = listener
    }
}

@Preview(
    name = "主题 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "主题 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun ThemeSettingPanePreview() {
    VenusTheme {
        ThemeSettingPane(
            selectedTheme = THEME_INDEX_BLUE,
            onThemeSelect = {},
            selectedVariant = THEME_VARIANT_INDEX_FOLLOW,
            onVariantSelect = {},
        )
    }
}
