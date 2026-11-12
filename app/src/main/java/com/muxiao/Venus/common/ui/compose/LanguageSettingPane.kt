package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「语言」抽屉内容（原 res/layout/item_setting_language.xml）。
 *
 * 等价于原 `TextInputLayout(Widget.Venus.Dropdown) + MaterialAutoCompleteTextView`：
 * 只读下拉框 + 展开菜单，选中后回调下标，由 Java 侧落盘并 `recreate()` 应用语言。
 *
 * @param options 语言名列表（顺序与 `SELECTED_LANGUAGE` 的下标一致）
 * @param selectedIndex 当前选中下标
 * @param onSelect 选中某语言后的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingPane(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    DrawerPane(modifier) {
        // 原先这里先写一行「选择语言」标签、再放带同名 label 的输入框——两处都在重复抽屉标题。
        // 密度版：标签行删除，输入框也不再带 floating label，直接显示当前语言
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = options.getOrElse(selectedIndex) { "" },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.select_language)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
                colors = OutlinedTextFieldDefaults.colors(
                    // 原 XML 显式指定 android:textColor="?attr/colorOnSurface"
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}

/**
 * [LanguageSettingPane] 的状态宿主（原 View 互操作宿主）。
 *
 * 现只承载 `mutableStateOf` 状态与回调，`Render()` 由 `SettingsScreenView` 在同一组合里调用。
 */
class LanguageSettingPaneView {

    private val optionsState = mutableStateOf(emptyList<String>())
    private val selectedState = mutableIntStateOf(0)
    private var onSelectListener: IndexConsumer? = null

    var options: List<String>
        get() = optionsState.value
        set(value) {
            optionsState.value = value
        }

    var selectedIndex: Int
        get() = selectedState.intValue
        set(value) {
            selectedState.intValue = value
        }

    @Composable
    fun Render() {
        VenusTheme {
            LanguageSettingPane(
                options = optionsState.value,
                selectedIndex = selectedState.intValue,
                onSelect = { index ->
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
    name = "语言 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "语言 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun LanguageSettingPanePreview() {
    val options = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_zh),
        stringResource(R.string.language_zh_tw),
        stringResource(R.string.language_en),
    )
    VenusTheme {
        LanguageSettingPane(options = options, selectedIndex = 0, onSelect = {})
    }
}
