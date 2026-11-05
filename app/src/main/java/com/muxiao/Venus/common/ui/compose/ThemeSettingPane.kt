package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * 设置页「主题」抽屉内容（原 res/layout/item_setting_theme.xml）。
 *
 * 结构：七枚主题圆点（FlowRow 流式换行，放不下自动折行）+ 深浅模式药丸分段。
 * 选中圆点用 onSurface 内描环表达（对应原 bg_theme_dot_ring 的 foreground 覆盖）。
 *
 * @param selectedTheme 选中的主题序号（0 = 系统动态取色，1-6 = 蓝绿红黄青紫）
 * @param onThemeSelect 主题点选回调
 * @param selectedVariant 深浅模式序号（0 跟随 / 1 浅色 / 2 深色）
 * @param onVariantSelect 深浅模式切换回调
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
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        FlowRow(
            modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_1)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_3)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
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

        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        Text(
            text = stringResource(R.string.theme_variant_setting),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        val variantOptions = listOf(
            stringResource(R.string.theme_follow),
            stringResource(R.string.theme_light),
            stringResource(R.string.theme_dark),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            variantOptions.forEachIndexed { index, text ->
                SegmentedButton(
                    selected = selectedVariant == index,
                    onClick = { onVariantSelect(index) },
                    // 按位置给出形状：首尾圆角、中间段直角，相邻段共用边框
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = variantOptions.size,
                    ),
                    label = {
                        Text(
                            text = text,
                            style = LocalVenusTextStyles.current.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/**
 * 单枚主题圆点，等价于 `Widget.Venus.ThemeDot`（28dp 药丸 + backgroundTint）。
 *
 * 选中环还原 bg_theme_dot_ring：2dp 内缩 + 2.5dp onSurface 描边。
 * 之所以不用 `border` 直接画在外圈：原实现是 foreground 覆盖，环与外缘之间留了 2dp 间隙，
 * 直接描边会贴着边缘、观感偏“粗”。
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
            // 原 XML 用 android:contentDescription 标注圆点语义（纯色圆点无文字），
            // 这里必须显式挂 semantics，否则读屏只能读到“按钮”而无法区分主题。
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

/** [ThemeSettingPane] 的 View 互操作宿主。 */
class ThemeSettingPaneView(context: Context) : AbstractComposeView(context) {

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

    /** 深浅模式序号。 */
    var selectedVariant: Int
        get() = variantState.intValue
        set(value) {
            variantState.intValue = value
        }

    @Composable
    override fun Content() {
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

@Preview(name = "设置 · 主题", showBackground = true)
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
