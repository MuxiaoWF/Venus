package com.muxiao.Venus.common.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「自动打码」抽屉：开关 + 识别组件管理。状态值与回调由 Java 侧驱动。
 */
@Composable
fun AutoCaptchaSettingPane(
    enabled: Boolean,
    assetsReady: Boolean,
    assetsVersion: String,
    installBusy: Boolean,
    progressStatus: String,
    progressPercent: Int,
    onEnabledChange: (Boolean) -> Unit,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        SettingHint(text = stringResource(R.string.auto_captcha_hint))
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        val canEnable = assetsReady && !installBusy
        SettingSwitchRow(
            title = stringResource(R.string.auto_captcha_switch),
            subtitle = stringResource(R.string.auto_captcha_switch_subtitle),
            checked = enabled && assetsReady,
            enabled = canEnable,
            note = if (!assetsReady && !installBusy) stringResource(R.string.auto_captcha_assets_missing) else null,
            noteColor = colorResource(R.color.status_warning),
            onCheckedChange = onEnabledChange,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        SettingValueRow(
            label = stringResource(R.string.auto_captcha_assets_state),
            value = assetsVersion,
        )
        if (installBusy) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            Column(Modifier.fillMaxWidth()) {
                if (progressPercent >= 0) {
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (progressStatus.isNotEmpty()) {
                    Text(
                        text = progressStatus,
                        style = textStyles.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
                    )
                }
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        GlassActionRow(
            text = stringResource(if (installBusy) R.string.auto_captcha_cancel
            else R.string.auto_captcha_download),
            icon = if (installBusy) null else VenusIcons.Download,
            onClick = onInstallClick,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        GlassActionRow(
            text = stringResource(R.string.auto_captcha_uninstall),
            icon = VenusIcons.Trash,
            onClick = onUninstallClick,
        )
    }
}

    /** 状态宿主：承载状态与回调，Render() 由 SettingsScreenView 在组合里调用。 */
class AutoCaptchaSettingPaneView {

    private val enabledState = mutableStateOf(false)
    private val assetsReadyState = mutableStateOf(false)
    private val assetsVersionState = mutableStateOf("")
    private val installBusyState = mutableStateOf(false)
    private val progressStatusState = mutableStateOf("")
    private val progressPercentState = mutableIntStateOf(-1)

    private var onEnabledChangeListener: BooleanConsumer? = null
    private var onInstallClickListener: Runnable? = null
    private var onUninstallClickListener: Runnable? = null

    @Composable
    fun Render() {
        VenusTheme {
            AutoCaptchaSettingPane(
                enabled = enabledState.value,
                assetsReady = assetsReadyState.value,
                assetsVersion = assetsVersionState.value,
                installBusy = installBusyState.value,
                progressStatus = progressStatusState.value,
                progressPercent = progressPercentState.intValue,
                onEnabledChange = { onEnabledChangeListener?.accept(it) },
                onInstallClick = { onInstallClickListener?.run() },
                onUninstallClick = { onUninstallClickListener?.run() },
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    /** 组件是否就绪（native 库 + 模型齐全）：未就绪时开关禁用。 */
    fun setAssetsReady(ready: Boolean) {
        assetsReadyState.value = ready
    }

    /** 组件状态摘要（未安装 / vX 已安装 / 缺 native 库 / 安装失败…）。 */
    fun setAssetsVersion(value: String) {
        assetsVersionState.value = value
    }

    fun setInstallBusy(busy: Boolean) {
        installBusyState.value = busy
        if (!busy) {
            progressStatusState.value = ""
            progressPercentState.intValue = -1
        }
    }

    fun setProgress(percent: Int, status: String) {
        progressPercentState.intValue = percent
        progressStatusState.value = status
    }

    fun setOnEnabledChangeListener(listener: BooleanConsumer?) {
        onEnabledChangeListener = listener
    }

    fun setOnInstallClickListener(listener: Runnable?) {
        onInstallClickListener = listener
    }

    fun setOnUninstallClickListener(listener: Runnable?) {
        onUninstallClickListener = listener
    }
}

@Preview(name = "自动打码 · 360dp 深色", widthDp = 360, showBackground = true)
@Composable
private fun AutoCaptchaSettingPanePreview() {
    VenusTheme {
        AutoCaptchaSettingPane(
            enabled = true,
            assetsReady = true,
            assetsVersion = "v1 已安装",
            installBusy = false,
            progressStatus = "",
            progressPercent = -1,
            onEnabledChange = {},
            onInstallClick = {},
            onUninstallClick = {},
        )
    }
}
