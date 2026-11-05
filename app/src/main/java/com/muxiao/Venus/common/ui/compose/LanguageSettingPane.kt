package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.AbstractComposeView
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
    val textStyles = LocalVenusTextStyles.current
    var expanded by remember { mutableStateOf(false) }
    DrawerPane(modifier) {
        Text(text = stringResource(R.string.select_language), style = textStyles.drawerLabel)
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
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
                label = { Text(stringResource(R.string.select_language)) },
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

/** [LanguageSettingPane] 的 View 互操作宿主。 */
class LanguageSettingPaneView(context: Context) : AbstractComposeView(context) {

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
    override fun Content() {
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

@Preview(name = "设置 · 语言", showBackground = true)
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
