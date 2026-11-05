package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 淡化风格预设序号（对应 BACKGROUND_PRESET：0 轻盈 / 1 标准 / 2 浓郁）。 */
const val BG_PRESET_INDEX_LIGHT = 0
const val BG_PRESET_INDEX_STANDARD = 1
const val BG_PRESET_INDEX_RICH = 2

/**
 * 设置页「背景图片」抽屉内容（原 res/layout/item_setting_background_picture.xml）。
 *
 * 结构：两张等宽 Outlined 按钮（选择/清除）+ 淡化风格三档药丸分段 + 不透明度滑块。
 * 风格管质感、滑条管浓度，两者正交（见原 XML 注释）。
 *
 * @param presetIndex 淡化风格序号（0 轻盈 / 1 标准 / 2 浓郁）
 * @param onPresetSelect 风格切换回调
 * @param onSelectClick 点击「选择图片」
 * @param onClearClick 点击「清除背景」
 * @param alphaPercent 不透明度百分比（0-90，步长 5）
 * @param onAlphaChange 拖动中的值变化回调
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
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSelectClick,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.select_image),
                    style = textStyles.labelMedium,
                )
            }
            // 原 XML：左钮 marginEnd=space_1、右钮 marginStart=space_1，合计 8dp
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            OutlinedButton(
                onClick = onClearClick,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.clear_background),
                    style = textStyles.labelMedium,
                )
            }
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        Text(
            text = stringResource(R.string.background_style),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        val presetOptions = listOf(
            stringResource(R.string.bg_preset_light) to BG_PRESET_INDEX_LIGHT,
            stringResource(R.string.bg_preset_standard) to BG_PRESET_INDEX_STANDARD,
            stringResource(R.string.bg_preset_rich) to BG_PRESET_INDEX_RICH,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            presetOptions.forEachIndexed { index, (text, preset) ->
                SegmentedButton(
                    selected = presetIndex == preset,
                    onClick = { onPresetSelect(preset) },
                    // 按位置给出形状：首尾圆角、中间段直角，相邻段共用边框
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = presetOptions.size,
                    ),
                    label = {
                        Text(
                            text = text,
                            style = textStyles.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        // 原 XML 用 showValue + floating label 表达「拖到哪了」；
        // Material3 1.4 的 Slider 已无 label 形参，这里把百分比常驻在标题行右侧，
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
        // 原 XML：valueFrom=0 / valueTo=90 / stepSize=5 → 19 个刻度，故 steps = 17
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

/** [BackgroundSettingPane] 的 View 互操作宿主。 */
class BackgroundSettingPaneView(context: Context) : AbstractComposeView(context) {

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
    override fun Content() {
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

@Preview(name = "设置 · 背景图片", showBackground = true)
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
