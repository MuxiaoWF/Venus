package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「关于」抽屉内容：2 行只读文本（版本号 / 开源协议）+ 2 行外链。
 *
 * 外链行统一用 [SettingLinkRow]；只读文本 4dp 上下留白，无触控语义，不必占 44dp。
 *
 * @param appVersion 已格式化的版本文案（`R.string.about_version`）
 */
@Composable
fun AboutSettingPane(
    appVersion: String,
    onGithubClick: () -> Unit,
    onBlogClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DrawerPane(modifier) {
        AboutTextLine(appVersion)
        AboutTextLine(stringResource(R.string.about_license))
        SettingLinkRow(text = stringResource(R.string.github_source), onClick = onGithubClick)
        SettingLinkRow(text = stringResource(R.string.my_blog), onClick = onBlogClick)
    }
}

/** 只读文本行：body 字号 + onSurfaceVariant。 */
@Composable
private fun AboutTextLine(text: String) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_1)),
    )
}

/**
 * [AboutSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class AboutSettingPaneView {

    private val appVersionState = mutableStateOf("")
    private var onGithubClickListener: Runnable? = null
    private var onBlogClickListener: Runnable? = null

    /** 版本文案；Java 侧 setAppVersion(...) 直接触发重组。 */
    var appVersion: String
        get() = appVersionState.value
        set(value) {
            appVersionState.value = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            AboutSettingPane(
                appVersion = appVersionState.value,
                onGithubClick = { onGithubClickListener?.run() },
                onBlogClick = { onBlogClickListener?.run() },
            )
        }
    }

    fun setOnGithubClickListener(listener: Runnable?) {
        onGithubClickListener = listener
    }

    fun setOnBlogClickListener(listener: Runnable?) {
        onBlogClickListener = listener
    }
}

@Preview(
    name = "关于 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "关于 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun AboutSettingPanePreview() {
    VenusTheme {
        AboutSettingPane(
            appVersion = stringResource(R.string.about_version, "0.0.8"),
            onGithubClick = {},
            onBlogClick = {},
        )
    }
}
