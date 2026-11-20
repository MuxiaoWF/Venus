package com.muxiao.Venus.common.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.muxiao.Venus.R

/**
 * Venus 通用输入框（统一 `shape_corner_sm` 圆角 + 键盘动作），登录页与设置页共用。
 *
 * @param label 浮动标签；null = 不带标签（语言抽屉用 placeholder 代替，避免与抽屉标题重复）
 * @param readOnly 只读（下拉 / 展示型字段）：仍可点击展开菜单，但不接受输入
 * @param modifier 调用方前置修饰符（如 `menuAnchor`）
 */
@Composable
internal fun VenusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    minLines: Int = 1,
    maxLines: Int = 1,
    trailingIcon: (@Composable () -> Unit)? = null,
    onSubmit: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // 单行由 maxLines 派生：两者同时给会在 M3 里互相压制
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { text -> { Text(text) } },
        placeholder = placeholder?.let { text -> { Text(text) } },
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
    )
}

/**
 * 密码输入框：`VisualTransformation` 已废弃，改用 [OutlinedSecureTextField] + [rememberTextFieldState]，
 * 遮罩由 `textObfuscationMode` 表达；对外仍是 value/onValueChange，调用方无需改动。
 */
@Composable
internal fun VenusPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(value)
    // 外部 → 内部：state 只取初值，父级改写 value 时必须回写
    LaunchedEffect(value) {
        if (state.text.toString() != value)
            state.setTextAndPlaceCursorAtEnd(value)
    }
    // 内部 → 外部：逐次转发；不在闭包里比较 value（会捕获旧值），回写循环由上面的判断挡住
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onValueChange(it) }
    }
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(
                    text = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    style = LocalVenusTextStyles.current.labelMedium,
                )
            }
        },
        // 该回调自带「覆盖默认行为」语义（不自动收键盘）；需要默认动作时在体内调 performDefaultAction()
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        onKeyboardAction = KeyboardActionHandler { onSubmit() },
        // 用 Hidden 而非 System：后者在系统开启「显示密码」时会短暂显示末字符
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
    )
}
