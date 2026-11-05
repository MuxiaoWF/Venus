package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「服务器类型」抽屉内容（原 res/layout/item_setting_server_segmented.xml）。
 *
 * 等价于原 `MaterialButtonToggleGroup(singleSelection, selectionRequired)`：
 * 国服 / 国际服互斥选择，选中即回调，由 Java 侧落盘并切换国际服隐藏项。
 * 采用 Compose [SingleChoiceSegmentedButtonRow] + [SegmentedButton] 单选分段。
 *
 * @param selectedIndex 0 = 国服，1 = 国际服
 * @param onSelect 切换回调
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
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimensionResource(R.dimen.space_4),
                end = dimensionResource(R.dimen.space_4),
                top = dimensionResource(R.dimen.space_1),
                bottom = dimensionResource(R.dimen.space_4),
            ),
    ) {
        options.forEachIndexed { index, text ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                // 按位置给出形状：首尾圆角、中间段直角，相邻段共用边框
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = text,
                        style = LocalVenusTextStyles.current.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** [ServerSettingPane] 的 View 互操作宿主。 */
class ServerSettingPaneView(context: Context) : AbstractComposeView(context) {

    private val selectedState = mutableIntStateOf(0)
    private var onSelectListener: IndexConsumer? = null

    /** 0 = 国服，1 = 国际服；Java 侧 setSelectedIndex(...) 直接触发重组。 */
    var selectedIndex: Int
        get() = selectedState.intValue
        set(value) {
            selectedState.intValue = value
        }

    @Composable
    override fun Content() {
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

@Preview(name = "设置 · 服务器类型", showBackground = true)
@Composable
private fun ServerSettingPanePreview() {
    VenusTheme {
        ServerSettingPane(selectedIndex = 0, onSelect = {})
    }
}
