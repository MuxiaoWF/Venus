package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.wrapContentWidth
import com.muxiao.Venus.R

/** 森空岛子游戏复选框序号（与 Java 侧 SKLAND_ARKNIGHTS_ENABLED / SKLAND_ENDFIELD_ENABLED 顺序一致）。 */
const val SKLAND_INDEX_ARKNIGHTS = 0
const val SKLAND_INDEX_ENDFIELD = 1

/**
 * 设置页「森空岛」抽屉内容（原 res/layout/item_setting_skland.xml）。
 *
 * 结构：启用开关 → 当前账号与凭证状态 → 两份游戏复选框（随开关整体启用/禁用）→ 两条外链 →
 * 三条说明副文本 → 居中「保存森空岛设置」按钮（点击弹出 token 输入框）。
 *
 * <p>为什么要把账号显示出来：token **按用户存放**，切换账号后这里看到的是另一个人的配置。
 * 不标注归属的话，用户会以为「刚配好的 token 凭空消失了」。
 *
 * @param enabled 功能总开关
 * @param onEnabledChange 总开关回调
 * @param arknightsChecked 明日方舟子项
 * @param endfieldChecked 终末地子项
 * @param onSubItemChange 子项回调（下标见 `SKLAND_INDEX_*`）
 * @param onLoginLinkClick 点击「前往森空岛登录」
 * @param onCookieLinkClick 点击「前往获取内容」
 * @param onSaveClick 点击保存按钮（弹出 token 对话框）
 * @param accountLabel 当前账号名；为 null 时隐藏归属行（未登录）
 * @param tokenConfigured 该账号是否已配置 token
 */
@Composable
fun SklandSettingPane(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    arknightsChecked: Boolean,
    endfieldChecked: Boolean,
    onSubItemChange: (index: Int, checked: Boolean) -> Unit,
    onLoginLinkClick: () -> Unit,
    onCookieLinkClick: () -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier,
    accountLabel: String? = null,
    tokenConfigured: Boolean = false,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val tipStyle = textStyles.bodySmall.copy(color = secondary)
    DrawerPane(modifier) {
        LabeledSwitchRow(
            text = stringResource(R.string.enable_feature),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        if (accountLabel != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(
                    R.string.credential_account_status,
                    accountLabel,
                    stringResource(
                        if (tokenConfigured) R.string.user_cred_ready else R.string.user_cred_missing
                    ),
                ),
                style = textStyles.bodySmall.copy(
                    color = if (tokenConfigured) colorResource(R.color.status_success) else secondary
                ),
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        // 原 XML 用 ChipGroup 包两个 MaterialCheckBox（padding=space_3），等价于可折行的 Checkbox 行
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
        ) {
            SklandCheckbox(
                text = stringResource(R.string.arknights),
                checked = arknightsChecked,
                enabled = enabled,
                onCheckedChange = { onSubItemChange(SKLAND_INDEX_ARKNIGHTS, it) },
            )
            SklandCheckbox(
                text = stringResource(R.string.endfield),
                checked = endfieldChecked,
                enabled = enabled,
                onCheckedChange = { onSubItemChange(SKLAND_INDEX_ENDFIELD, it) },
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Row(modifier = Modifier.fillMaxWidth()) {
            SklandLink(
                text = stringResource(R.string.go_to_login_skland),
                onClick = onLoginLinkClick,
                modifier = Modifier.weight(1f),
            )
            // 原 XML：左链接 marginEnd=space_2、右链接 marginStart=space_2，合计 16dp
            Spacer(Modifier.width(dimensionResource(R.dimen.space_4)))
            SklandLink(
                text = stringResource(R.string.go_to_get_content),
                onClick = onCookieLinkClick,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        Text(text = stringResource(R.string.skland_403_tip), style = tipStyle)
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(text = stringResource(R.string.skland_midnight_tip), style = tipStyle)
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(text = stringResource(R.string.skland_content_tip), style = tipStyle)

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        OutlinedButton(
            onClick = onSaveClick,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally),
        ) {
            Text(
                text = stringResource(R.string.save_skland_settings),
                style = textStyles.labelMedium,
            )
        }
    }
}

/** 复选框行：Checkbox + 文案，整体可点（对应 MaterialCheckBox 的文本点击区）。 */
@Composable
private fun SklandCheckbox(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(dimensionResource(R.dimen.space_3)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(
            text = text,
            style = LocalVenusTextStyles.current.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 外链行：下划线文案 + 尾随链接图标。
 *
 * 原 XML 用 `android:drawableEnd` 贴在 0dp/weight=1 的 TextView 尾部（即行右端），
 * 这里以「Text weight 撑开 + Image 靠右」还原，图标沿用 vector 自带的 colorPrimary。
 */
@Composable
private fun SklandLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = LocalVenusTextStyles.current.body.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.Underline,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
        Image(
            painter = painterResource(R.drawable.ic_link_url),
            contentDescription = null,
        )
    }
}

/** [SklandSettingPane] 的 View 互操作宿主。 */
class SklandSettingPaneView(context: Context) : AbstractComposeView(context) {

    private val enabledState = mutableStateOf(false)
    private val arknightsState = mutableStateOf(false)
    private val endfieldState = mutableStateOf(false)
    private val accountLabelState = mutableStateOf<String?>(null)
    private val tokenConfiguredState = mutableStateOf(false)

    private var onEnabledChangeListener: BooleanConsumer? = null
    private var onSubItemChangeListener: IndexedCheckedConsumer? = null
    private var onLoginLinkClickListener: Runnable? = null
    private var onCookieLinkClickListener: Runnable? = null
    private var onSaveClickListener: Runnable? = null

    /** 功能总开关；Java 侧 setEnabledChecked(...) 直接触发重组，并联动子项启用态。 */
    var enabledChecked: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
        }

    var arknightsChecked: Boolean
        get() = arknightsState.value
        set(value) {
            arknightsState.value = value
        }

    var endfieldChecked: Boolean
        get() = endfieldState.value
        set(value) {
            endfieldState.value = value
        }

    @Composable
    override fun Content() {
        VenusTheme {
            SklandSettingPane(
                enabled = enabledState.value,
                onEnabledChange = { checked ->
                    enabledState.value = checked
                    onEnabledChangeListener?.accept(checked)
                },
                arknightsChecked = arknightsState.value,
                endfieldChecked = endfieldState.value,
                onSubItemChange = { index, checked ->
                    if (index == SKLAND_INDEX_ARKNIGHTS) arknightsState.value = checked
                    else if (index == SKLAND_INDEX_ENDFIELD) endfieldState.value = checked
                    onSubItemChangeListener?.accept(index, checked)
                },
                onLoginLinkClick = { onLoginLinkClickListener?.run() },
                onCookieLinkClick = { onCookieLinkClickListener?.run() },
                onSaveClick = { onSaveClickListener?.run() },
                accountLabel = accountLabelState.value,
                tokenConfigured = tokenConfiguredState.value,
            )
        }
    }

    /** 当前账号名（凭证按用户存放，界面必须标明归属）；null 表示未登录。 */
    fun setAccountLabel(label: String?) {
        accountLabelState.value = label
    }

    /** 当前账号是否已配置森空岛 token。 */
    fun setTokenConfigured(configured: Boolean) {
        tokenConfiguredState.value = configured
    }

    fun setOnEnabledChangeListener(listener: BooleanConsumer?) {
        onEnabledChangeListener = listener
    }

    fun setOnSubItemChangeListener(listener: IndexedCheckedConsumer?) {
        onSubItemChangeListener = listener
    }

    fun setOnLoginLinkClickListener(listener: Runnable?) {
        onLoginLinkClickListener = listener
    }

    fun setOnCookieLinkClickListener(listener: Runnable?) {
        onCookieLinkClickListener = listener
    }

    fun setOnSaveClickListener(listener: Runnable?) {
        onSaveClickListener = listener
    }
}

@Preview(name = "设置 · 森空岛", showBackground = true)
@Composable
private fun SklandSettingPanePreview() {
    VenusTheme {
        SklandSettingPane(
            enabled = true,
            onEnabledChange = {},
            arknightsChecked = true,
            endfieldChecked = false,
            onSubItemChange = { _, _ -> },
            onLoginLinkClick = {},
            onCookieLinkClick = {},
            onSaveClick = {},
        )
    }
}
