package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 游戏签到勾选顺序（与 Java 侧 `gameDailyBindings` 的下标契约一致，改顺序须同步 SettingsFragment）。
 * 末两位（崩坏2 / 未定事件簿）在国际服不可用，由 `setChipVisible` 隐藏。
 */
private val GAME_CHIP_LABEL_RES = intArrayOf(
    R.string.genshin_impact,
    R.string.zenless_zone_zero,
    R.string.star_rail,
    R.string.honkai_impact_3,
    R.string.honkai_impact_2,
    R.string.tears_of_themis,
)

/** 国际服不支持的游戏签到下标（对应原 setupServerSelection 里传入的两个 Chip）。 */
const val GAME_CHIP_INDEX_HR2 = 4
const val GAME_CHIP_INDEX_WEIDING = 5

/**
 * 设置页「游戏签到」抽屉内容（原 res/layout/item_setting_game_daily.xml）。
 *
 * 结构：启用开关 + 6 个多选 FilterChip（游戏签到覆盖的游戏）。
 *
 * @param switchChecked 总开关
 * @param onSwitchChange 总开关变更回调
 * @param chipChecked 各游戏勾选态（长度 6，顺序见 [GAME_CHIP_LABEL_RES]）
 * @param onChipToggle 某游戏勾选变更回调
 * @param chipsEnabled 整组 Chip 是否可点
 */
@Composable
fun GameDailySettingPane(
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    chipChecked: List<Boolean>,
    onChipToggle: (index: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chipsEnabled: Boolean = true,
    chipVisible: List<Boolean> = List(GAME_CHIP_LABEL_RES.size) { true },
) {
    DrawerPane(modifier) {
        LabeledSwitchRow(
            text = stringResource(R.string.enable_feature),
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        FilterChipFlow(
            labels = GAME_CHIP_LABEL_RES.map { stringResource(it) },
            checked = chipChecked,
            enabled = chipsEnabled,
            visible = chipVisible,
            onToggle = onChipToggle,
        )
    }
}

/** [GameDailySettingPane] 的 View 互操作宿主。 */
class GameDailySettingPaneView(context: Context) : AbstractComposeView(context) {

    private val switchState = mutableStateOf(true)
    private val chipsState = mutableStateListOf<Boolean>().apply {
        repeat(GAME_CHIP_LABEL_RES.size) { add(false) }
    }
    private val visibleState = mutableStateListOf<Boolean>().apply {
        repeat(GAME_CHIP_LABEL_RES.size) { add(true) }
    }
    private val chipsEnabledState = mutableStateOf(true)
    private var onSwitchChangeListener: BooleanConsumer? = null
    private var onChipChangeListener: IndexedCheckedConsumer? = null

    var switchChecked: Boolean
        get() = switchState.value
        set(value) {
            switchState.value = value
        }

    fun setChipChecked(index: Int, checked: Boolean) {
        if (index in chipsState.indices) chipsState[index] = checked
    }

    /** 国际服隐藏不支持的游戏（替代原实现里对 Chip 直接 setVisibility）。 */
    fun setChipVisible(index: Int, visible: Boolean) {
        if (index in visibleState.indices) visibleState[index] = visible
    }

    @Composable
    override fun Content() {
        VenusTheme {
            GameDailySettingPane(
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
                chipVisible = visibleState.toList(),
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

@Preview(name = "设置 · 游戏签到", showBackground = true)
@Composable
private fun GameDailySettingPanePreview() {
    VenusTheme {
        GameDailySettingPane(
            switchChecked = true,
            onSwitchChange = {},
            chipChecked = List(GAME_CHIP_LABEL_RES.size) { it < 2 },
            onChipToggle = { _, _ -> },
        )
    }
}
