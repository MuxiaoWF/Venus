package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * 设置页「米游币签到」抽屉内容（原 res/layout/item_setting_bbs_daily.xml）。
 *
 * 结构：启用开关 + 说明副文本 + 9 个多选 FilterChip（米游社签到可覆盖的游戏）。
 *
 * @param switchChecked 总开关
 * @param onSwitchChange 总开关变更回调
 * @param chipChecked 各游戏勾选态（长度 9，顺序见 [DAILY_CHIP_LABEL_RES]）
 * @param onChipToggle 某游戏勾选变更回调
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
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        LabeledSwitchRow(
            text = stringResource(R.string.enable_feature),
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(
            text = stringResource(R.string.bbs_daily_tip),
            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        FilterChipFlow(
            labels = DAILY_CHIP_LABEL_RES.map { stringResource(it) },
            checked = chipChecked,
            enabled = chipsEnabled,
            visible = List(DAILY_CHIP_LABEL_RES.size) { true },
            onToggle = onChipToggle,
        )
    }
}

/** [DailySettingPane] 的 View 互操作宿主。 */
class DailySettingPaneView(context: Context) : AbstractComposeView(context) {

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
    override fun Content() {
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

@Preview(name = "设置 · 米游币签到", showBackground = true)
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
