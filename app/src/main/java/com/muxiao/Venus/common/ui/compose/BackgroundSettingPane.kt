package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 淡化风格预设序号（BACKGROUND_PRESET：0 轻盈 / 1 标准 / 2 浓郁）。0 号暂无代码直接引用，
 * 但分段控件按下标取值、历史偏好也可能存 0，删掉会让「0 = 轻盈」失去锚点，故保留。
 */
@Suppress("unused")
const val BG_PRESET_INDEX_LIGHT = 0
const val BG_PRESET_INDEX_STANDARD = 1
const val BG_PRESET_INDEX_RICH = 2

/**
 * 设置页「背景图片」抽屉内容。
 *
 * 结构：两张等宽按钮（选择/清除）+ 淡化风格三档药丸分段 + 不透明度滑块。
 * 风格管质感、滑条管浓度，两者正交。
 *
 * @param presetIndex 淡化风格序号（0 轻盈 / 1 标准 / 2 浓郁）
 * @param alphaPercent 不透明度百分比（0-90，步长 5）
 * @param onAlphaChangeFinished 松手回调（用于提示「已设置」）
 */
@Composable
fun BackgroundSettingPane(
    presetIndex: Int,
    onPresetSelect: (Int) -> Unit,
    onSelectClick: () -> Unit,
    onClearClick: () -> Unit,
    alphaPercent: Float,
    onAlphaChange: (Float) -> Unit,
    onAlphaChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        Text(
            text = stringResource(R.string.select_background_image),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        // 两个等宽按钮收口到 SettingButtonPairRow（更新窗格共用同一组件）
        SettingButtonPairRow(
            leftText = stringResource(R.string.select_image),
            onLeftClick = onSelectClick,
            rightText = stringResource(R.string.clear_background),
            onRightClick = onClearClick,
        )

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(
            text = stringResource(R.string.background_style),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        val presetOptions = listOf(
            stringResource(R.string.bg_preset_light),
            stringResource(R.string.bg_preset_standard),
            stringResource(R.string.bg_preset_rich),
        )
        // 预设序号本身就是 0/1/2，与列表下标一致，可直接把 presetIndex 当选中下标
        VenusSegmented(
            options = presetOptions,
            selectedIndex = presetIndex,
            onSelect = onPresetSelect,
        )

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        // Material3 1.4 的 Slider 已无 label 形参，故把百分比常驻在标题行右侧：
        // 拖动过程同样可读，且不依赖实验性 API。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.background_opacity),
                style = textStyles.drawerLabel,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${alphaPercent.toInt()}%",
                style = textStyles.monoValue.copy(color = MaterialTheme.colorScheme.primary),
            )
        }
        // 0..90 每 5 一格共 19 个刻度，故 steps = 17
        Slider(
            value = alphaPercent,
            onValueChange = onAlphaChange,
            onValueChangeFinished = onAlphaChangeFinished,
            valueRange = 0f..90f,
            steps = 17,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

/**
 * [BackgroundSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class BackgroundSettingPaneView {

    private val presetState = mutableIntStateOf(BG_PRESET_INDEX_STANDARD)
    private val alphaState = mutableFloatStateOf(45f)

    private var onPresetSelectListener: IndexConsumer? = null
    private var onSelectClickListener: Runnable? = null
    private var onClearClickListener: Runnable? = null
    private var onAlphaChangeListener: FloatConsumer? = null
    private var onAlphaChangeFinishedListener: Runnable? = null

    /** 淡化风格序号；Java 侧 setPresetIndex(...) 直接触发重组。 */
    var presetIndex: Int
        get() = presetState.intValue
        set(value) {
            presetState.intValue = value
        }

    /** 不透明度百分比（0-90）；Java 侧 setAlphaPercent(...) 直接触发重组。 */
    var alphaPercent: Float
        get() = alphaState.floatValue
        set(value) {
            alphaState.floatValue = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            BackgroundSettingPane(
                presetIndex = presetState.intValue,
                onPresetSelect = { index ->
                    presetState.intValue = index
                    onPresetSelectListener?.accept(index)
                },
                onSelectClick = { onSelectClickListener?.run() },
                onClearClick = { onClearClickListener?.run() },
                alphaPercent = alphaState.floatValue,
                onAlphaChange = { percent ->
                    alphaState.floatValue = percent
                    onAlphaChangeListener?.accept(percent)
                },
                onAlphaChangeFinished = { onAlphaChangeFinishedListener?.run() },
            )
        }
    }

    fun setOnPresetSelectListener(listener: IndexConsumer?) {
        onPresetSelectListener = listener
    }

    fun setOnSelectClickListener(listener: Runnable?) {
        onSelectClickListener = listener
    }

    fun setOnClearClickListener(listener: Runnable?) {
        onClearClickListener = listener
    }

    fun setOnAlphaChangeListener(listener: FloatConsumer?) {
        onAlphaChangeListener = listener
    }

    fun setOnAlphaChangeFinishedListener(listener: Runnable?) {
        onAlphaChangeFinishedListener = listener
    }
}

@Preview(
    name = "背景图片 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "背景图片 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun BackgroundSettingPanePreview() {
    VenusTheme {
        BackgroundSettingPane(
            presetIndex = BG_PRESET_INDEX_STANDARD,
            onPresetSelect = {},
            onSelectClick = {},
            onClearClick = {},
            alphaPercent = 45f,
            onAlphaChange = {},
            onAlphaChangeFinished = {},
        )
    }
}
