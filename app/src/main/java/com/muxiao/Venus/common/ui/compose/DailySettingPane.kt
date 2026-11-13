package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 游戏签到勾选顺序（与 Java 侧 `dailyBindings` 的下标契约一致，改顺序须同步 SettingsFragment）。
 */
private val DAILY_CHIP_LABEL_RES = intArrayOf(
    R.string.genshin_impact,
    R.string.zenless_zone_zero,
    R.string.star_rail,
    R.string.honkai_impact_3,
    R.string.honkai_impact_2,
    R.string.tears_of_themis,
    R.string.dabieye,
    R.string.bengyuanling,
    R.string.xingbugudi,
)

/**
 * 设置页「米游币签到」抽屉内容：启用开关 + 说明副文本 + 9 个多选 FilterChip。
 *
 * @param chipChecked 各游戏勾选态（长度 9，顺序见 [DAILY_CHIP_LABEL_RES]）
 * @param chipsEnabled 整组 Chip 是否可点（国际服等场景由外部禁用）
 */
@Composable
fun DailySettingPane(
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    chipChecked: List<Boolean>,
    onChipToggle: (index: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chipsEnabled: Boolean = true,
) {
    DrawerPane(modifier) {
        // 说明（34 字，2~3 行）内联为开关行副标题，避免多起一段
        SettingSwitchRow(
            title = stringResource(R.string.enable_feature),
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
            subtitle = stringResource(R.string.bbs_daily_tip),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        FilterChipFlow(
            labels = DAILY_CHIP_LABEL_RES.map { stringResource(it) },
            checked = chipChecked,
            enabled = chipsEnabled,
            visible = List(DAILY_CHIP_LABEL_RES.size) { true },
            onToggle = onChipToggle,
        )
    }
}

/**
 * [DailySettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView` 在
 * 设置页的同一组合里调用，Java 侧只负责 `setXxx(...)` 回填状态。
 */
class DailySettingPaneView {

    private val switchState = mutableStateOf(true)
    private val chipsState = mutableStateListOf<Boolean>().apply {
        repeat(DAILY_CHIP_LABEL_RES.size) { add(false) }
    }
    private var chipsEnabledState = mutableStateOf(true)
    private var onSwitchChangeListener: BooleanConsumer? = null
    private var onChipChangeListener: IndexedCheckedConsumer? = null

    /** 总开关；Java 侧 setSwitchChecked(...) 直接触发重组（对话框回滚也走这里）。 */
    var switchChecked: Boolean
        get() = switchState.value
        set(value) {
            switchState.value = value
        }

    fun setChipChecked(index: Int, checked: Boolean) {
        if (index in chipsState.indices) chipsState[index] = checked
    }

    @Composable
    fun Render() {
        VenusTheme {
            DailySettingPane(
                switchChecked = switchState.value,
                onSwitchChange = { checked ->
                    switchState.value = checked
                    onSwitchChangeListener?.accept(checked)
                },
                chipChecked = chipsState.toList(),
                onChipToggle = { index, checked ->
                    if (index in chipsState.indices) chipsState[index] = checked
                    onChipChangeListener?.accept(index, checked)
                },
                chipsEnabled = chipsEnabledState.value,
            )
        }
    }

    fun setOnSwitchChangeListener(listener: BooleanConsumer?) {
        onSwitchChangeListener = listener
    }

    fun setOnChipChangeListener(listener: IndexedCheckedConsumer?) {
        onChipChangeListener = listener
    }
}

@Preview(
    name = "米游币签到 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "米游币签到 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun DailySettingPanePreview() {
    VenusTheme {
        DailySettingPane(
            switchChecked = true,
            onSwitchChange = {},
            chipChecked = List(DAILY_CHIP_LABEL_RES.size) { it == 6 || it == 7 },
            onChipToggle = { _, _ -> },
        )
    }
}
