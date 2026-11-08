package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
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
 * 设置页「云游戏签到」抽屉内容。
 *
 * 结构：启用开关 → 说明副文本 → 2 个多选 FilterChip（决定哪些云游戏进入任务列表）
 * → 统一获取入口（一键抓取 + 手动填写兜底）→ 已配置进度与缺失提示。
 *
 * <p>**Token 为什么只有一个入口**：云游戏 token（`x-rpc-combo_token`）按云游戏服务签发，
 * 云原神（hk4e_cg_cn）与云绝区零（nap_cn）互不通用，所以存储上必须是两份；但两者共用
 * 同一个米哈游通行证登录态，进同一次 WebView 会话就能先后抓到两份，故**获取动作**合并为
 * 一个入口，由抓取页按请求域名自动识别归属，不必让用户先选游戏再进。
 *
 * <p>勾选与 Token 是两件事：**勾选决定任务是否执行，Token 决定能否签到成功**。
 * 只勾选不配 Token 时任务会以「未配置 Token」失败——这是有意为之的引导，
 * 比「静默跳过」更容易让用户发现漏配。
 *
 * @param enabled 功能总开关
 * @param onEnabledChange 总开关回调
 * @param chipChecked 各云游戏勾选态（长度 2，顺序见 [CLOUD_GAME_CHIP_LABEL_RES]）
 * @param onChipToggle 某云游戏勾选变更回调
 * @param configuredCount 已配置 token 的云游戏数量
 * @param configuredTotal 当前服可用的云游戏总数（国际服仅云原神，为 1）
 * @param missingGames 未配置的游戏名（逗号分隔）；全部配置好时为 null，不显示提示行
 * @param onFetchClick 一键获取（打开 WebView 自动抓取）
 * @param onManualClick 手动填写（先选游戏再粘贴）
 * @param chipVisible 国际服无云绝区零钱包接口，由外部置 false 隐藏
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
) {
    val textStyles = LocalVenusTextStyles.current
    val tipStyle = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    DrawerPane(modifier) {
        LabeledSwitchRow(
            text = stringResource(R.string.enable_feature),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        // token 按用户存放：不标出当前账号，切号后会以为配置丢了
        if (accountLabel != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(R.string.credential_account, accountLabel),
                style = tipStyle,
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Text(text = stringResource(R.string.cloud_game_tip), style = tipStyle)
        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        FilterChipFlow(
            labels = CLOUD_GAME_CHIP_LABEL_RES.map { stringResource(it) },
            checked = chipChecked,
            enabled = enabled,
            visible = chipVisible,
            onToggle = onChipToggle,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
        OutlinedButton(
            onClick = onFetchClick,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.cloud_game_fetch_token),
                style = textStyles.labelMedium,
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(
                    R.string.cloud_game_configured_summary, configuredCount, configuredTotal
                ),
                style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        if (missingGames != null) {
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(R.string.cloud_game_missing_tokens, missingGames),
                style = tipStyle,
            )
        }
        TextButton(onClick = onManualClick) {
            Text(
                text = stringResource(R.string.cloud_game_token_manual),
                style = textStyles.labelMedium,
            )
        }
    }
}

/** [CloudGameSettingPane] 的 View 互操作宿主。 */
class CloudGameSettingPaneView(context: Context) : AbstractComposeView(context) {

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

    /**
     * 刷新 token 配置进度。
     *
     * @param configured 已配置数量
     * @param total 当前服可用的云游戏总数
     * @param missingGames 未配置的游戏名（逗号分隔），全部配置好时传 null
     */
    fun setTokenStatus(configured: Int, total: Int, missingGames: String?) {
        configuredCountState.intValue = configured
        configuredTotalState.intValue = total
        missingGamesState.value = missingGames
    }

    @Composable
    override fun Content() {
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
            )
        }
    }

    /** 当前账号名（token 按用户存放，界面需标明归属）；null 表示未登录。 */
    fun setAccountLabel(label: String?) {
        accountLabelState.value = label
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

@Preview(name = "设置 · 云游戏签到", showBackground = true)
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
