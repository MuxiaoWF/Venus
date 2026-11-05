package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「关于」抽屉内容（原 res/layout/item_setting_about.xml）。
 *
 * 结构等价于原 XML：四行 Body 文本（版本号 / 开源协议 / 两个带尾随链接图标的外链），
 * 外层间距沿用 `Widget.Venus.DrawerPane`，逐行上下留白 space_2。
 *
 * @param appVersion 已格式化的版本文案（`R.string.about_version`）
 * @param onGithubClick 点击 Github 链接
 * @param onBlogClick 点击 Blog 链接
 */
@Composable
fun AboutSettingPane(
    appVersion: String,
    onGithubClick: () -> Unit,
    onBlogClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    // 原 XML 四行都是 textAppearance=Body + textColor=?attr/colorOnSurfaceVariant
    val bodyStyle = textStyles.body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimensionResource(R.dimen.space_4),
                end = dimensionResource(R.dimen.space_4),
                top = dimensionResource(R.dimen.gap_row),
                bottom = dimensionResource(R.dimen.gap_row),
            ),
    ) {
        AboutTextLine(appVersion, bodyStyle)
        AboutTextLine(stringResource(R.string.about_license), bodyStyle)
        AboutLinkLine(stringResource(R.string.github_source), bodyStyle, onGithubClick)
        AboutLinkLine(stringResource(R.string.my_blog), bodyStyle, onBlogClick)
    }
}

/** 静态文本行：对应 XML 里只设了 paddingVertical 的 MaterialTextView。 */
@Composable
private fun AboutTextLine(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_2)),
    )
}

/**
 * 外链行：文字（带下划线）+ 尾随链接图标。
 *
 * 原 XML 用 `android:drawableEnd` + `drawablePadding`，等价于本 Row 的「文字 + weight 撑开 + 图标」；
 * 按下反馈原为 `?attr/selectableItemBackground`，这里用 clickable 的默认有界涟漪等同。
 * 图标是 `?attr/colorPrimary` 描边的 16dp vector，不额外 tint，保留其自带主题色。
 */
@Composable
private fun AboutLinkLine(text: String, style: TextStyle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = style.copy(textDecoration = TextDecoration.Underline))
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.ic_link_url),
            contentDescription = null,
        )
    }
}

/**
 * [AboutSettingPane] 的 View 互操作宿主（Compose 入口必须在 Kotlin 侧构造）。
 */
class AboutSettingPaneView(context: Context) : AbstractComposeView(context) {

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
    override fun Content() {
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

@Preview(name = "设置 · 关于", showBackground = true)
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
