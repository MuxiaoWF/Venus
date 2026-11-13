package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「服务器类型」抽屉内容：国服 / 国际服互斥选择，选中即回调，由 Java 侧落盘并
 * 切换国际服隐藏项。
 *
 * 分段控件统一走 [VenusSegmented]，外层统一 [DrawerPane]，内边距与其它抽屉一致。
 *
 * @param selectedIndex 0 = 国服，1 = 国际服
 */
@Composable
fun ServerSettingPane(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        stringResource(R.string.server_cn),
        stringResource(R.string.server_os),
    )
    DrawerPane(modifier) {
        VenusSegmented(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
    }
}

/**
 * [ServerSettingPane] 的状态宿主：只承载状态与回调，`Render()` 由 `SettingsScreenView`
 * 在同一组合里调用。
 */
class ServerSettingPaneView {

    private val selectedState = mutableIntStateOf(0)
    private var onSelectListener: IndexConsumer? = null

    /** 0 = 国服，1 = 国际服；Java 侧 setSelectedIndex(...) 直接触发重组。 */
    var selectedIndex: Int
        get() = selectedState.intValue
        set(value) {
            selectedState.intValue = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            ServerSettingPane(
                selectedIndex = selectedState.intValue,
                onSelect = { index ->
                    // 受控组件：用户点选先落到自身状态再回调，Java 侧无需回写（重复点仍由 Java 判定）
                    selectedState.intValue = index
                    onSelectListener?.accept(index)
                },
            )
        }
    }

    fun setOnSelectListener(listener: IndexConsumer?) {
        onSelectListener = listener
    }
}

@Preview(
    name = "服务器类型 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "服务器类型 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun ServerSettingPanePreview() {
    VenusTheme {
        ServerSettingPane(selectedIndex = 0, onSelect = {})
    }
}
