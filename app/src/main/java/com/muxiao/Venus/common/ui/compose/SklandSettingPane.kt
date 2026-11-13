package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 森空岛子游戏复选框序号（与 Java 侧 SKLAND_ARKNIGHTS_ENABLED / SKLAND_ENDFIELD_ENABLED 顺序一致）。 */
const val SKLAND_INDEX_ARKNIGHTS = 0
const val SKLAND_INDEX_ENDFIELD = 1

/**
 * 设置页「森空岛」抽屉内容（原 res/layout/item_setting_skland.xml）。
 *
 * 结构（密度版，纵向由约 300dp 收到约 195dp）：
 * 开关行 → 当前账号与凭证状态 → 两个游戏复选行（随开关整体启用/禁用）→ 两条外链（同一行）
 * → 三条说明合并为一段 → 玻璃动作行「保存森空岛设置」。
 *
 * <p>为什么要把账号显示出来：token **按用户存放**，切换账号后这里看到的是另一个人的配置。
 * 不标注归属的话，用户会以为「刚配好的 token 凭空消失了」。
 *
 * <p>密度口径：
 * - 三条 tip（403 / 午夜 / 内容）本就是同一件事的三句补充，合并为一段按行分隔的说明，
 *   省掉两处 `Spacer(8) + 独立 Text`；
 * - 两个外链并排（各占一半），不再上下两条；
 * - 复选行用 [SettingCheckRow]（整行可点、44dp），替代原先带 12dp 内边距的自定义行；
 * - 保存按钮改 [GlassActionRow]，与页内其它玻璃动作行同一套材质。
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
    /** false = 该账号不是当前登录账号（从用户管理页指定），归属文案要明说。 */
    accountIsCurrent: Boolean = true,
) {
    DrawerPane(modifier) {
        SettingSwitchRow(
            title = stringResource(R.string.enable_feature),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        if (accountLabel != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            val status = stringResource(
                if (tokenConfigured) R.string.user_cred_ready else R.string.user_cred_missing
            )
            SettingHint(
                // 目标账号不是当前账号时用「正在配置：xxx（非当前账号）」，
                // 否则沿用「当前账号：xxx · 状态」
                text = stringResource(
                    if (accountIsCurrent) R.string.credential_account_status
                    else R.string.credential_account_status_other,
                    accountLabel,
                    status,
                ),
                tone = when {
                    !accountIsCurrent -> SettingTone.Warning
                    tokenConfigured -> SettingTone.Success
                    else -> SettingTone.Info
                },
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
        ) {
            SettingCheckRow(
                title = stringResource(R.string.arknights),
                checked = arknightsChecked,
                onCheckedChange = { onSubItemChange(SKLAND_INDEX_ARKNIGHTS, it) },
                enabled = enabled,
                fillWidth = false,
            )
            SettingCheckRow(
                title = stringResource(R.string.endfield),
                checked = endfieldChecked,
                onCheckedChange = { onSubItemChange(SKLAND_INDEX_ENDFIELD, it) },
                enabled = enabled,
                fillWidth = false,
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingLinkRow(
                text = stringResource(R.string.go_to_login_skland),
                onClick = onLoginLinkClick,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_3)))
            SettingLinkRow(
                text = stringResource(R.string.go_to_get_content),
                onClick = onCookieLinkClick,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        // 三条补充说明合并为一段。注意必须用 inline 的 map 再 joinToString：
        // joinToString 的 transform 是普通函数类型（非 inline），在里面调 stringResource 会报
        // "@Composable invocations can only happen from the context of a @Composable function"
        // maxLines 放到 6：三条中文按当前宽度最多换到 6 行，截断会丢"内容获取"这条关键提示
        SettingHint(
            text = listOf(
                R.string.skland_403_tip,
                R.string.skland_midnight_tip,
                R.string.skland_content_tip,
            ).map { stringResource(it) }.joinToString("\n"),
            maxLines = 6,
        )

        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        GlassActionRow(
            text = stringResource(R.string.save_skland_settings),
            onClick = onSaveClick,
        )
    }
}

/**
 * [SklandSettingPane] 的状态宿主（原 View 互操作宿主）。
 *
 * 现只承载 `mutableStateOf` 状态与回调，`Render()` 由 `SettingsScreenView` 在同一组合里调用。
 */
class SklandSettingPaneView {

    private val enabledState = mutableStateOf(false)
    private val arknightsState = mutableStateOf(false)
    private val endfieldState = mutableStateOf(false)
    private val accountLabelState = mutableStateOf<String?>(null)
    private val accountIsCurrentState = mutableStateOf(true)
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
    fun Render() {
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
                accountIsCurrent = accountIsCurrentState.value,
            )
        }
    }

    /**
     * 归属账号名（凭证按用户存放，界面必须标明归属）；null 表示未登录。
     *
     * @param isCurrent false = 该账号不是当前登录账号（设置了「配置目标账号」）
     */
    fun setAccountLabel(label: String?, isCurrent: Boolean = true) {
        accountLabelState.value = label
        accountIsCurrentState.value = isCurrent
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

@Preview(
    name = "森空岛 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "森空岛 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
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
            accountLabel = "MuxiaoWF",
            tokenConfigured = true,
        )
    }
}
