package com.muxiao.Venus.common.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.muxiao.Venus.R

/**
 * Venus 通用输入框（统一 `shape_corner_sm` 圆角 + 键盘动作），登录页与设置页共用。
 *
 * @param label 浮动标签；null = 不带标签（语言抽屉用 placeholder 代替，避免与抽屉标题重复）
 * @param readOnly 只读（下拉 / 展示型字段）：仍可点击展开菜单，但不接受输入
 * @param modifier 调用方前置修饰符（如 `menuAnchor`）
 * @param visualTransformation 视觉变换（密码框用它遮字符）
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
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
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
    )
}

/**
 * 密码输入框：Compose 未引入 material-icons-extended，用「显示 / 隐藏」文字按钮做可见性
 * 切换，且读屏可读。
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
    VenusTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        enabled = enabled,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        onSubmit = onSubmit,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(
                    text = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    style = LocalVenusTextStyles.current.labelMedium,
                )
            }
        },
    )
}
