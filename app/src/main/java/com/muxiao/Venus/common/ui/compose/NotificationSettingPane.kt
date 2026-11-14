package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「通知 / 后台运行」抽屉内容。
 *
 * 结构：后台运行开关 → 定时签到开关（说明 + 验证码提醒同挂该行）→ 执行时刻导航行
 * → 电池优化豁免导航行。每个开关只占一行，验证码提醒作为 [SettingSwitchRow] 的 `note`
 * 挂在定时签到行而非单起段落；时刻行 / 电池行统一用 [SettingNavRow]。
 *
 * @param scheduledTimeVisible 是否显示「执行时刻」行（开关打开才显示）
 * @param batteryValueColor 电池豁免状态颜色（0 表示用默认次要色）
 */
@Composable
fun NotificationSettingPane(
    backgroundChecked: Boolean,
    onBackgroundChange: (Boolean) -> Unit,
    scheduledChecked: Boolean,
    onScheduledChange: (Boolean) -> Unit,
    scheduledTimeVisible: Boolean,
    scheduledTimeText: String,
    onScheduledTimeClick: () -> Unit,
    batteryValueText: String,
    batteryValueColor: Int,
    onBatteryRowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DrawerPane(modifier) {
        SettingSwitchRow(
            title = stringResource(R.string.enable_background_running),
            checked = backgroundChecked,
            onCheckedChange = onBackgroundChange,
            subtitle = stringResource(R.string.background_running_desc),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        SettingSwitchRow(
            title = stringResource(R.string.enable_scheduled_task),
            checked = scheduledChecked,
            onCheckedChange = onScheduledChange,
            subtitle = stringResource(R.string.scheduled_task_desc),
            // 验证码提醒是这一行的风险说明，不是独立段落
            note = stringResource(R.string.scheduled_task_captcha_hint),
            noteColor = colorResource(R.color.status_warning),
        )
        if (scheduledTimeVisible) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            SettingNavRow(
                title = stringResource(R.string.scheduled_task_time_label),
                leadingIcon = VenusIcons.Schedule,
                value = scheduledTimeText,
                valueColor = MaterialTheme.colorScheme.primary,
                onClick = onScheduledTimeClick,
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        SettingNavRow(
            title = stringResource(R.string.battery_row_label),
            subtitle = stringResource(R.string.battery_row_desc),
            leadingIcon = VenusIcons.Zap,
            value = batteryValueText,
            // 0 = AndroidColor.TRANSPARENT，表示"未指定"，回退到次要文字色
            valueColor = if (batteryValueColor == 0) Color.Unspecified else Color(batteryValueColor),
            onClick = onBatteryRowClick,
        )
    }
}

/**
 * [NotificationSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class NotificationSettingPaneView {

    private val backgroundState = mutableStateOf(false)
    private val scheduledState = mutableStateOf(false)
    private val timeVisibleState = mutableStateOf(false)
    private val timeTextState = mutableStateOf("")
    private val batteryTextState = mutableStateOf("")
    private val batteryColorState = mutableIntStateOf(AndroidColor.TRANSPARENT)

    private var onBackgroundChangeListener: BooleanConsumer? = null
    private var onScheduledChangeListener: BooleanConsumer? = null
    private var onScheduledTimeClickListener: Runnable? = null
    private var onBatteryRowClickListener: Runnable? = null

    var backgroundChecked: Boolean
        get() = backgroundState.value
        set(value) {
            backgroundState.value = value
        }

    var scheduledChecked: Boolean
        get() = scheduledState.value
        set(value) {
            scheduledState.value = value
        }

    /** 「执行时刻」行是否可见（开关打开才显示）。 */
    var scheduledTimeVisible: Boolean
        get() = timeVisibleState.value
        set(value) {
            timeVisibleState.value = value
        }

    var scheduledTimeText: String
        get() = timeTextState.value
        set(value) {
            timeTextState.value = value
        }

    /** 电池豁免状态文案；传 null 时按 0 颜色（默认次要色）处理。 */
    fun setBatteryValue(text: String, color: Int) {
        batteryTextState.value = text
        batteryColorState.intValue = color
    }

    @Composable
    fun Render() {
        VenusTheme {
            NotificationSettingPane(
                backgroundChecked = backgroundState.value,
                onBackgroundChange = { checked ->
                    backgroundState.value = checked
                    onBackgroundChangeListener?.accept(checked)
                },
                scheduledChecked = scheduledState.value,
                onScheduledChange = { checked ->
                    scheduledState.value = checked
                    onScheduledChangeListener?.accept(checked)
                },
                scheduledTimeVisible = timeVisibleState.value,
                scheduledTimeText = timeTextState.value,
                onScheduledTimeClick = { onScheduledTimeClickListener?.run() },
                batteryValueText = batteryTextState.value,
                batteryValueColor = batteryColorState.intValue,
                onBatteryRowClick = { onBatteryRowClickListener?.run() },
            )
        }
    }

    fun setOnBackgroundChangeListener(listener: BooleanConsumer?) {
        onBackgroundChangeListener = listener
    }

    fun setOnScheduledChangeListener(listener: BooleanConsumer?) {
        onScheduledChangeListener = listener
    }

    fun setOnScheduledTimeClickListener(listener: Runnable?) {
        onScheduledTimeClickListener = listener
    }

    fun setOnBatteryRowClickListener(listener: Runnable?) {
        onBatteryRowClickListener = listener
    }
}

@Preview(
    name = "通知 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "通知 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun NotificationSettingPanePreview() {
    VenusTheme {
        NotificationSettingPane(
            backgroundChecked = true,
            onBackgroundChange = {},
            scheduledChecked = true,
            onScheduledChange = {},
            scheduledTimeVisible = true,
            scheduledTimeText = "08:30",
            onScheduledTimeClick = {},
            batteryValueText = stringResource(R.string.battery_exempted_value),
            batteryValueColor = 0,
            onBatteryRowClick = {},
        )
    }
}
