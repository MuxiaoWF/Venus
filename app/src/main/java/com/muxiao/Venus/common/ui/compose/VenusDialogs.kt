package com.muxiao.Venus.common.ui.compose

import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R

/**
 * Compose 版对话框门面（Java 友好）。
 *
 * 背景：主界面迁移后 Material 对话框组件（`MaterialAlertDialogBuilder` /
 * `MaterialTimePicker` / `TextInputEditText`）不再进新代码，统一由本类以
 * **Compose material3 AlertDialog** 渲染。调用侧保持命令式一行调用，
 * 语义与原 Builder 版一一对应（confirm = 正/负按钮，items = 列表单选，
 * input = 带校验输入，timePicker = 时间选择）。
 *
 * 宿主窗口用 [ComponentDialog]：它是 [androidx.lifecycle.LifecycleOwner] 并会向
 * window decorView 安装 ViewTree*Owner，`ComposeView` 因此可直接作为其内容
 * （appcompat 的 `AlertDialog` 不具备该能力）。
 *
 * 主题：对话框内经 [VenusTheme] 读取 Activity 主题属性——6 套 ThemeOverlay、
 * 深浅模式与动态取色下与页面观感一致（对话框 window 沿用 Activity 的 theme）。
 */
object VenusDialogs {

    /** 输入校验：返回错误文案则对话框保持打开并就地显示错误；返回 null 表示通过（关闭并回调）。 */
    fun interface InputValidator {
        fun validate(text: String): String?
    }

    /** 输入通过后的回调（接收 trim 后的文本）。 */
    fun interface TextConsumer {
        fun accept(text: String)
    }

    /** 时间选择确认回调（24 小时制整数）。 */
    fun interface TimeConsumer {
        fun accept(hour: Int, minute: Int)
    }

    // ===== 确认框（正/负按钮） =====

    /** 双按钮确认框。负按钮传 null 则只显示正按钮；[onDismiss] 在对话框因任何原因关闭后回调。 */
    @JvmStatic
    @JvmOverloads
    fun confirm(
        activity: android.app.Activity,
        title: String,
        message: String?,
        positiveText: String,
        onPositive: Runnable?,
        negativeText: String? = null,
        onNegative: Runnable? = null,
        onDismiss: Runnable? = null,
    ) {
        showDialog(activity, onDismiss) { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = { if (!message.isNullOrEmpty()) Text(message) },
                confirmButton = {
                    TextButton(onClick = { onPositive?.run(); dismiss() }) { Text(positiveText) }
                },
                dismissButton = {
                    if (negativeText != null) {
                        TextButton(onClick = { onNegative?.run(); dismiss() }) { Text(negativeText) }
                    }
                },
            )
        }
    }

    /** 单按钮信息框（正按钮关闭）。 */
    @JvmStatic
    fun info(activity: android.app.Activity, title: String, message: String?, okText: String) {
        confirm(activity, title, message, okText, null)
    }

    // ===== 列表单选框 =====

    /** 列表选择框（对应 Builder.setItems）：点击任意项即关闭并回调下标。 */
    @JvmStatic
    @JvmOverloads
    fun items(
        activity: android.app.Activity,
        title: String,
        options: List<String>,
        onSelect: IndexConsumer,
        negativeText: String? = null,
    ) {
        showDialog(activity, null) { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = {
                    Column {
                        options.forEachIndexed { index, option ->
                            TextButton(
                                onClick = { onSelect.accept(index); dismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(option, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    if (negativeText != null) {
                        TextButton(onClick = dismiss) { Text(negativeText) }
                    }
                },
            )
        }
    }

    // ===== 输入框 =====

    /**
     * 带输入框的对话框（对应 Builder.setView(TextInputEditText)）。
     *
     * @param validator 非空时正按钮先校验：返回错误文案则对话框保持打开并就地显示；
     *                  null 表示不校验（点击正按钮即回调）。
     * @param cancelable 是否可点外部区域/返回键取消（原 `setCancelable`）。
     */
    @JvmStatic
    @JvmOverloads
    fun input(
        activity: android.app.Activity,
        title: String,
        message: String?,
        initialText: String?,
        hint: String?,
        positiveText: String,
        negativeText: String?,
        cancelable: Boolean = true,
        validator: InputValidator? = null,
        onAccepted: TextConsumer?,
    ) {
        showDialog(activity, null) { dismiss ->
            var text by remember { mutableStateOf(initialText ?: "") }
            var error by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { if (cancelable) dismiss() },
                title = { Text(title) },
                text = {
                    Column {
                        if (!message.isNullOrEmpty()) Text(message)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; error = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = dimensionResource(R.dimen.space_2)),
                            singleLine = true,
                            isError = error != null,
                            supportingText = {
                                val e = error
                                if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
                                else if (hint != null) Text(hint)
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = text.trim()
                        val violation = validator?.validate(value)
                        if (violation != null) {
                            error = violation
                        } else {
                            onAccepted?.accept(value)
                            dismiss()
                        }
                    }) { Text(positiveText) }
                },
                dismissButton = {
                    if (negativeText != null) {
                        TextButton(onClick = dismiss) { Text(negativeText) }
                    }
                },
            )
        }
    }

    // ===== 可滚动长文本（日志查看） =====

    /**
     * 等宽可滚动的长文本框（对应 Builder.setView(ScrollView + mono TextView)）。
     * 负按钮（如「清空日志」）可选。
     */
    @JvmStatic
    @JvmOverloads
    fun scrolledText(
        activity: android.app.Activity,
        title: String,
        content: String,
        positiveText: String,
        negativeText: String? = null,
        onNegative: Runnable? = null,
    ) {
        showDialog(activity, null) { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = {
                    SelectionContainer {
                        Text(
                            content,
                            style = LocalVenusTextStyles.current.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = dismiss) { Text(positiveText) }
                },
                dismissButton = {
                    if (negativeText != null) {
                        TextButton(onClick = { onNegative?.run(); dismiss() }) { Text(negativeText) }
                    }
                },
            )
        }
    }

    // ===== 时间选择 =====

    /**
     * 时间选择器（对应 MaterialTimePicker）：确认后回调 24 小时制整数；
     * 12/24 小时制由调用方按系统设置传入。
     */
    @JvmStatic
    @OptIn(ExperimentalMaterial3Api::class)
    fun timePicker(
        activity: android.app.Activity,
        is24Hour: Boolean,
        hour: Int,
        minute: Int,
        title: String,
        onConfirmed: TimeConsumer,
    ) {
        showDialog(activity, null) { dismiss ->
            val state = rememberTimePickerState(
                initialHour = hour,
                initialMinute = minute,
                is24Hour = is24Hour,
            )
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        onConfirmed.accept(state.hour, state.minute)
                        dismiss()
                    }) { Text(stringResource(R.string.btn_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.btn_cancel)) }
                },
            )
        }
    }

    // ===== 内部：Compose 对话框宿主 =====

    /**
     * 统一对话框装配：[ComponentDialog] + ComposeView。
     * 窗口背景透明（AlertDialog 自带表面），宽高 wrap 由组合内容决定。
     *
     * @param onWindowDismiss 对话框窗口关闭（任何途径）后的回调，可选。
     */
    private inline fun showDialog(
        activity: android.app.Activity,
        onWindowDismiss: Runnable?,
        crossinline content: @Composable (onDismiss: () -> Unit) -> Unit,
    ) {
        val dialog = ComponentDialog(activity)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val compose = ComposeView(activity)
        compose.setContent {
            VenusTheme {
                content { dialog.dismiss() }
            }
        }
        // onWindowDismiss 只应触发一次：dismissListener 在 dialog.dismiss() 内部路径触发
        if (onWindowDismiss != null) {
            dialog.setOnDismissListener { onWindowDismiss.run() }
        }
        dialog.setContentView(compose)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }
}
