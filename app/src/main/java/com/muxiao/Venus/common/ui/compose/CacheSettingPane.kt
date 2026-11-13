package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「缓存管理」抽屉内容：一行——左侧缓存占用文案 + 右侧「清理缓存」按钮（44dp 单行）。
 *
 * @param cacheSize 已本地化并格式化好的缓存占用文案（由 Java 侧计算后回填）
 */
@Composable
fun CacheSettingPane(
    cacheSize: String,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DrawerPane(modifier) {
        SettingRow(
            title = cacheSize,
            trailing = {
                OutlinedButton(
                    onClick = onClearClick,
                    shape = CircleShape,
                    modifier = Modifier.heightIn(min = dimensionResource(R.dimen.setting_action_height)),
                ) {
                    Text(
                        text = stringResource(R.string.clear_cache),
                        style = LocalVenusTextStyles.current.labelMedium,
                    )
                }
            },
        )
    }
}

/**
 * [CacheSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class CacheSettingPaneView {

    private val cacheSizeState = mutableStateOf("")
    private var onClearClickListener: Runnable? = null

    /** 缓存占用文案；Java 侧 setCacheSize(...) 直接触发重组。 */
    var cacheSize: String
        get() = cacheSizeState.value
        set(value) {
            cacheSizeState.value = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            CacheSettingPane(
                cacheSize = cacheSizeState.value,
                onClearClick = { onClearClickListener?.run() },
            )
        }
    }

    fun setOnClearClickListener(listener: Runnable?) {
        onClearClickListener = listener
    }
}

@Preview(
    name = "缓存管理 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "缓存管理 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun CacheSettingPanePreview() {
    VenusTheme {
        CacheSettingPane(
            cacheSize = stringResource(R.string.current_cache_size),
            onClearClick = {},
        )
    }
}
