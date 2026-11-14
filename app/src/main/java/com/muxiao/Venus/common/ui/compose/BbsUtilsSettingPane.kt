package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 配置信息 KV 行的标签顺序（与 Java 侧 `displayCurrentConfigValues` 的下标契约一致）。 */
private val CONFIG_KEY_LABEL_RES = intArrayOf(
    R.string.config_key_local_time,
    R.string.config_key_cloud_time,
    R.string.config_key_bbs_version,
    R.string.config_key_salt_6x,
    R.string.config_key_salt_4x,
    R.string.config_key_lk2,
    R.string.config_key_k2,
)

/**
 * 设置页「配置信息」抽屉内容：说明副文本 + 7 行「标签左 / 等宽值右对齐」KV +
 * 全宽表面行按钮（更新配置）。
 *
 * @param values 7 个配置值（顺序见 [CONFIG_KEY_LABEL_RES]）
 */
@Composable
fun BbsUtilsSettingPane(
    values: List<String>,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        SettingHint(text = stringResource(R.string.refresh_tip))
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(
            text = stringResource(R.string.current_config_value),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        // 7 行 KV 收口到 SettingValueRow（32dp 紧凑行）
        CONFIG_KEY_LABEL_RES.forEachIndexed { index, labelRes ->
            SettingValueRow(
                label = stringResource(labelRes),
                value = values.getOrElse(index) { "" },
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        // 用 GlassActionRow（LensGlass + GlassSpecs.Inset），与页内其它玻璃同一套材质
        GlassActionRow(
            text = stringResource(R.string.update_config),
            icon = VenusIcons.Refresh,
            onClick = onUpdateClick,
        )
    }
}

/**
 * [BbsUtilsSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class BbsUtilsSettingPaneView {

    private val valuesState = mutableStateListOf<String>().apply {
        repeat(CONFIG_KEY_LABEL_RES.size) { add("") }
    }
    private var onUpdateClickListener: Runnable? = null

    /** 按下标回填配置值（顺序见 Compose 侧 CONFIG_KEY_LABEL_RES）；写入 SnapshotStateList 即触发重组。 */
    fun setValue(index: Int, value: String) {
        if (index in valuesState.indices) valuesState[index] = value
    }

    @Composable
    fun Render() {
        VenusTheme {
            BbsUtilsSettingPane(
                values = valuesState.toList(),
                onUpdateClick = { onUpdateClickListener?.run() },
            )
        }
    }

    fun setOnUpdateClickListener(listener: Runnable?) {
        onUpdateClickListener = listener
    }
}

@Preview(
    name = "配置信息 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "配置信息 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun BbsUtilsSettingPanePreview() {
    VenusTheme {
        BbsUtilsSettingPane(
            values = listOf("2026-11-05 08:00", "2026-11-04 22:10", "2.98.0", "abc123", "def456", "ghi789", "jkl012"),
            onUpdateClick = {},
        )
    }
}
