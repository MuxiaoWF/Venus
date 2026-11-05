package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「更新」抽屉内容（原 res/layout/item_setting_update.xml）。
 *
 * 结构：自动检查更新行（文案 + Switch）+ 说明副文本 + 两个等宽 Outlined 胶囊按钮。
 *
 * @param autoUpdateChecked 自动检查更新开关状态
 * @param onAutoUpdateChange 开关变更回调
 * @param onCheckGithubClick 从 Github 检查更新
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
    val textStyles = LocalVenusTextStyles.current
    DrawerPane(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auto_check_update),
                style = textStyles.drawerLabel,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoUpdateChecked, onCheckedChange = onAutoUpdateChange)
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        Text(
            text = stringResource(R.string.auto_check_update_desc),
            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onCheckGithubClick,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.check_update_github),
                    style = textStyles.labelMedium,
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            OutlinedButton(
                onClick = onCheckPanClick,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.check_update_pan),
                    style = textStyles.labelMedium,
                )
            }
        }
    }
}

/** [UpdateSettingPane] 的 View 互操作宿主。 */
class UpdateSettingPaneView(context: Context) : AbstractComposeView(context) {

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
    override fun Content() {
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

@Preview(name = "设置 · 更新", showBackground = true)
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
