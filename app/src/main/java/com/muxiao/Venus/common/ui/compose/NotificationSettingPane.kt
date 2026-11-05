package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「通知 / 后台运行」抽屉内容（原 res/layout/item_setting_notification.xml）。
 *
 * 结构：后台运行开关 + 说明；定时签到开关 + 说明 + 验证码提示 + 可选的时间行；电池优化豁免行。
 *
 * @param backgroundChecked 后台运行开关
 * @param onBackgroundChange 后台运行开关回调
 * @param scheduledChecked 定时签到开关
 * @param onScheduledChange 定时签到开关回调
 * @param scheduledTimeVisible 是否显示「执行时刻」行（开关打开才显示）
 * @param scheduledTimeText 已格式化的执行时刻文案
 * @param onScheduledTimeClick 点击时间行（弹出时间选择器）
 * @param batteryValueText 电池豁免状态文案
 * @param batteryValueColor 电池豁免状态颜色（0 表示用默认次要色）
 * @param onBatteryRowClick 点击电池豁免行
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
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    DrawerPane(modifier) {
        LabeledSwitchRow(
            text = stringResource(R.string.enable_background_running),
            checked = backgroundChecked,
            onCheckedChange = onBackgroundChange,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        Text(
            text = stringResource(R.string.background_running_desc),
            style = textStyles.bodySmall.copy(color = secondary),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        LabeledSwitchRow(
            text = stringResource(R.string.enable_scheduled_task),
            checked = scheduledChecked,
            onCheckedChange = onScheduledChange,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        Text(
            text = stringResource(R.string.scheduled_task_desc),
            style = textStyles.bodySmall.copy(color = secondary),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        Text(
            text = stringResource(R.string.scheduled_task_captcha_hint),
            style = textStyles.bodySmall.copy(color = colorResource(R.color.status_warning)),
        )
        if (scheduledTimeVisible) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            // 执行时刻行：图标 + 标签 + 等宽时刻 + 展开箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onScheduledTimeClick)
                    .padding(
                        horizontal = dimensionResource(R.dimen.space_2),
                        vertical = dimensionResource(R.dimen.space_1),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule),
                    contentDescription = stringResource(R.string.scheduled_task_time_label),
                    tint = secondary,
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                Text(
                    text = stringResource(R.string.scheduled_task_time_label),
                    style = textStyles.drawerLabel,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = scheduledTimeText,
                    style = textStyles.monoValue.copy(color = MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(270f),
                )
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        // 电池优化豁免行：图标 + 两行文案 + 状态值
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBatteryRowClick)
                .padding(
                    horizontal = dimensionResource(R.dimen.space_2),
                    vertical = dimensionResource(R.dimen.space_1),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_zap),
                contentDescription = stringResource(R.string.battery_row_label),
                tint = secondary,
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_row_label),
                    style = textStyles.drawerLabel,
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_hairline)))
                Text(
                    text = stringResource(R.string.battery_row_desc),
                    style = textStyles.bodySmall.copy(color = secondary),
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = batteryValueText,
                style = textStyles.monoValue.copy(
                    color = if (batteryValueColor == 0) secondary else Color(batteryValueColor),
                ),
            )
        }
    }
}

/** [NotificationSettingPane] 的 View 互操作宿主。 */
class NotificationSettingPaneView(context: Context) : AbstractComposeView(context) {

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

    /** 已格式化的执行时刻文案。 */
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
    override fun Content() {
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

@Preview(name = "设置 · 通知", showBackground = true)
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
