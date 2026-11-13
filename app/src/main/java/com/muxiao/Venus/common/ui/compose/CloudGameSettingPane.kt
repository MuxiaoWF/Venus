package com.muxiao.Venus.common.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 云游戏子项序号（与 Java 侧 CLOUD_GAME_GENSHIN / CLOUD_GAME_ZZZ 顺序一致）。 */
const val CLOUD_GAME_INDEX_GENSHIN = 0
const val CLOUD_GAME_INDEX_ZZZ = 1

/** 云游戏勾选顺序（与 Java 侧 `cloudGameKeys` 的下标契约一致，改顺序须同步 SettingsFragment）。 */
private val CLOUD_GAME_CHIP_LABEL_RES = intArrayOf(
    R.string.cloud_game_genshin,
    R.string.cloud_game_zzz,
)

/**
 * 设置页「云游戏签到」抽屉：开关行（说明内联为副标题）→ 账号归属 → 云游戏多选 chip → 动作行（获取 + 手动填写同一行）。
 * Token 只有一个入口：token 按云游戏服务签发，云原神（hk4e_cg_cn）与云绝区零（nap_cn）互不通用、必须存两份，
 * 但共用同一通行证登录态，一次 WebView 会话即可先后抓到两份，由抓取页按请求域名识别归属，不必先选游戏。
 * 勾选与 Token 是两件事：勾选决定是否执行、Token 决定能否签到；只勾不配会以「未配置 Token」失败——有意为之的引导，比静默跳过更易发现漏配。
 * 密度口径见 SettingRows.kt：长说明独立成 [SettingHint]、配置进度作 primaryBadge、兜底动作与主按钮同行。
 * @param chipVisible 国际服无云绝区零钱包接口，由外部置 false 隐藏
 * @param accountLabel 当前账号名（token 按用户存放，须标明归属）；null = 未登录
 */
@Composable
fun CloudGameSettingPane(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    chipChecked: List<Boolean>,
    onChipToggle: (index: Int, checked: Boolean) -> Unit,
    configuredCount: Int,
    configuredTotal: Int,
    missingGames: String?,
    onFetchClick: () -> Unit,
    onManualClick: () -> Unit,
    modifier: Modifier = Modifier,
    chipVisible: List<Boolean> = List(CLOUD_GAME_CHIP_LABEL_RES.size) { true },
    accountLabel: String? = null,
    /** false = 该账号不是当前登录账号（从用户管理页指定），归属文案要明说，避免误以为是当前账号的配置。 */
    accountIsCurrent: Boolean = true,
) {
    DrawerPane(modifier) {
        SettingSwitchRow(
            title = stringResource(R.string.enable_feature),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        // cloud_game_tip 约 6 行：塞进开关行做副标题会让整段说明变成开关点击区（误触）且被 maxLines 截断
        Spacer(Modifier.height(dimensionResource(R.dimen.space_hairline)))
        SettingHint(text = stringResource(R.string.cloud_game_tip), maxLines = 6)
        // token 按用户存放：不标出归属账号，切号后会以为配置丢了；目标账号还可能是从用户管理页指定的其它账号
        if (accountLabel != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            SettingHint(
                text = stringResource(
                    if (accountIsCurrent) R.string.credential_account
                    else R.string.credential_account_other,
                    accountLabel,
                ),
                tone = if (accountIsCurrent) SettingTone.Info else SettingTone.Warning,
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        FilterChipFlow(
            labels = CLOUD_GAME_CHIP_LABEL_RES.map { stringResource(it) },
            checked = chipChecked,
            enabled = enabled,
            visible = chipVisible,
            onToggle = onChipToggle,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        SettingActionRow(
            primaryText = stringResource(R.string.cloud_game_fetch_token),
            // 「N/M 已配置」是点击的结果，属于按钮本身不独占一行；复数取总数（句式里数词是分母）
            primaryBadge = pluralStringResource(
                R.plurals.cloud_game_configured_summary,
                configuredTotal,
                configuredCount,
                configuredTotal,
            ),
            onPrimaryClick = onFetchClick,
            secondaryText = stringResource(R.string.cloud_game_token_manual),
            onSecondaryClick = onManualClick,
        )
        if (missingGames != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            SettingHint(
                text = stringResource(R.string.cloud_game_missing_tokens, missingGames),
                tone = SettingTone.Warning,
            )
        }
    }
}

/** [CloudGameSettingPane] 的状态宿主：只承载 `mutableStateOf` 状态与回调，`Render()` 由 `SettingsScreenView` 在同一组合里调用。 */
class CloudGameSettingPaneView {

    private val enabledState = mutableStateOf(false)
    private val chipsState = mutableStateListOf<Boolean>().apply {
        repeat(CLOUD_GAME_CHIP_LABEL_RES.size) { add(false) }
    }
    private val visibleState = mutableStateListOf<Boolean>().apply {
        repeat(CLOUD_GAME_CHIP_LABEL_RES.size) { add(true) }
    }
    private val configuredCountState = mutableIntStateOf(0)
    private val configuredTotalState = mutableIntStateOf(CLOUD_GAME_CHIP_LABEL_RES.size)
    private val missingGamesState = mutableStateOf<String?>(null)
    private val accountLabelState = mutableStateOf<String?>(null)
    private val accountIsCurrentState = mutableStateOf(true)

    private var onEnabledChangeListener: BooleanConsumer? = null
    private var onChipChangeListener: IndexedCheckedConsumer? = null
    private var onFetchClickListener: Runnable? = null
    private var onManualClickListener: Runnable? = null

    var enabledChecked: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
        }

    fun setChipChecked(index: Int, checked: Boolean) {
        if (index in chipsState.indices) chipsState[index] = checked
    }

    /** 国际服隐藏云绝区零（无对应钱包接口）。 */
    fun setChipVisible(index: Int, visible: Boolean) {
        if (index in visibleState.indices) visibleState[index] = visible
    }

    /** 刷新 token 配置进度；[missingGames] 全部配置好时传 null 即不显示提示行（国际服总数只有 1）。 */
    fun setTokenStatus(configured: Int, total: Int, missingGames: String?) {
        configuredCountState.intValue = configured
        configuredTotalState.intValue = total
        missingGamesState.value = missingGames
    }

    @Composable
    fun Render() {
        VenusTheme {
            CloudGameSettingPane(
                enabled = enabledState.value,
                onEnabledChange = { checked ->
                    enabledState.value = checked
                    onEnabledChangeListener?.accept(checked)
                },
                chipChecked = chipsState.toList(),
                onChipToggle = { index, checked ->
                    if (index in chipsState.indices) chipsState[index] = checked
                    onChipChangeListener?.accept(index, checked)
                },
                configuredCount = configuredCountState.intValue,
                configuredTotal = configuredTotalState.intValue,
                missingGames = missingGamesState.value,
                onFetchClick = { onFetchClickListener?.run() },
                onManualClick = { onManualClickListener?.run() },
                chipVisible = visibleState.toList(),
                accountLabel = accountLabelState.value,
                accountIsCurrent = accountIsCurrentState.value,
            )
        }
    }

    /** 归属账号名（token 按用户存放，须标明归属）；null = 未登录；[isCurrent] false = 指定的非当前账号。 */
    fun setAccountLabel(label: String?, isCurrent: Boolean = true) {
        accountLabelState.value = label
        accountIsCurrentState.value = isCurrent
    }

    fun setOnEnabledChangeListener(listener: BooleanConsumer?) {
        onEnabledChangeListener = listener
    }

    fun setOnChipChangeListener(listener: IndexedCheckedConsumer?) {
        onChipChangeListener = listener
    }

    /** 一键获取：打开 WebView 自动抓取（两款游戏共用同一入口，按域名自动识别归属）。 */
    fun setOnFetchClickListener(listener: Runnable?) {
        onFetchClickListener = listener
    }

    /** 手动填写：先选游戏再粘贴 token（自动抓取失败时的兜底）。 */
    fun setOnManualClickListener(listener: Runnable?) {
        onManualClickListener = listener
    }
}

@Preview(
    name = "云游戏 · 360dp 深色",
    widthDp = 360,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "云游戏 · 411dp 浅色",
    widthDp = 411,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun CloudGameSettingPanePreview() {
    VenusTheme {
        CloudGameSettingPane(
            enabled = true,
            onEnabledChange = {},
            chipChecked = List(CLOUD_GAME_CHIP_LABEL_RES.size) { true },
            onChipToggle = { _, _ -> },
            configuredCount = 1,
            configuredTotal = 2,
            missingGames = "云绝区零",
            onFetchClick = {},
            onManualClick = {},
        )
    }
}
