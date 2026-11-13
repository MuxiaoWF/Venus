package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「更新」抽屉内容：自动检查更新行（文案 + Switch）+ 说明副文本 + 两个等宽按钮。
 *
 * @param autoUpdateChecked 自动检查更新开关状态
 * @param onCheckPanClick 从网盘下载
 */
@Composable
fun UpdateSettingPane(
    autoUpdateChecked: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onCheckGithubClick: () -> Unit,
    onCheckPanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DrawerPane(modifier) {
        // 说明作开关行副标题，避免多起一段
        SettingSwitchRow(
            title = stringResource(R.string.auto_check_update),
            checked = autoUpdateChecked,
            onCheckedChange = onAutoUpdateChange,
            subtitle = stringResource(R.string.auto_check_update_desc),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        SettingButtonPairRow(
            leftText = stringResource(R.string.check_update_github),
            onLeftClick = onCheckGithubClick,
            rightText = stringResource(R.string.check_update_pan),
            onRightClick = onCheckPanClick,
        )
    }
}

/**
 * [UpdateSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class UpdateSettingPaneView {

    private val autoUpdateState = mutableStateOf(true)
    private var onAutoUpdateChangeListener: BooleanConsumer? = null
    private var onCheckGithubClickListener: Runnable? = null
    private var onCheckPanClickListener: Runnable? = null

    /** 自动检查更新开关；Java 侧 setAutoUpdateChecked(...) 直接触发重组。 */
    var autoUpdateChecked: Boolean
        get() = autoUpdateState.value
        set(value) {
            autoUpdateState.value = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            UpdateSettingPane(
                autoUpdateChecked = autoUpdateState.value,
                onAutoUpdateChange = { checked ->
                    // 受控组件：先落自身状态再回调；Java 若拦截会回写 !checked 覆盖
                    autoUpdateState.value = checked
                    onAutoUpdateChangeListener?.accept(checked)
                },
                onCheckGithubClick = { onCheckGithubClickListener?.run() },
                onCheckPanClick = { onCheckPanClickListener?.run() },
            )
        }
    }

    fun setOnAutoUpdateChangeListener(listener: BooleanConsumer?) {
        onAutoUpdateChangeListener = listener
    }

    fun setOnCheckGithubClickListener(listener: Runnable?) {
        onCheckGithubClickListener = listener
    }

    fun setOnCheckPanClickListener(listener: Runnable?) {
        onCheckPanClickListener = listener
    }
}

@Preview(
    name = "更新 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "更新 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun UpdateSettingPanePreview() {
    VenusTheme {
        UpdateSettingPane(
            autoUpdateChecked = true,
            onAutoUpdateChange = {},
            onCheckGithubClick = {},
            onCheckPanClick = {},
        )
    }
}
