package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools

/* ===== 来源行状态（对齐原 LinkFragment.ChipState） ===== */

/** 待获取：中性 chip，图标圆为灰表面。 */
const val LINK_CHIP_PENDING = 0

/** 获取中：主色 chip + 刷新图标无限旋转。 */
const val LINK_CHIP_FETCHING = 1

/** 已获取：成功绿 chip，图标圆点回主色底。 */
const val LINK_CHIP_FETCHED = 2

/* ===== VPN 抓包行语义状态（对齐原 LinkFragment.VpnStatus） ===== */

const val VPN_STATUS_DEFAULT = 0
const val VPN_STATUS_RUNNING = 1
const val VPN_STATUS_SUCCESS = 2
const val VPN_STATUS_WARN = 3

/**
 * 一条抽卡链接结果：标签（`UID 100000001` 或渠道名）+ 完整 URL。
 *
 * 原实现是 `buildLinkRow` 里命令式拼 `LinearLayout`，迁移后只保留数据，
 * 由 Compose 渲染，Java 侧只负责把 `Map<Integer, String>` 转成这个列表。
 */
@Immutable
class LinkRow(val label: String, val url: String)

/** 单个来源行（原神 / 绝区零）的可变状态。 */
@Stable
class LinkSourceState {
    var state by mutableIntStateOf(LINK_CHIP_PENDING)
    var result by mutableStateOf<List<LinkRow>>(emptyList())
    var error by mutableStateOf<String?>(null)
}

/**
 * 抽卡链接页的全部可变状态。
 *
 * 与设置页各 pane 的「宿主是受控组件」约定一致：Java 只写状态，Compose 只读状态；
 * 状态切换后的视觉（chip 文案/配色/图标底/旋转）全部由 [LinkScreen] 依 [LinkSourceState.state] 派生，
 * Java 不再逐控件 setText/setBackground/setTint。
 */
@Stable
class LinkUiState {
    var users by mutableStateOf<List<String>>(emptyList())
    var selectedUserIndex by mutableIntStateOf(-1)
    var oversea by mutableStateOf(false)
    var avatarUsername by mutableStateOf<String?>(null)
    /** 头像刷新令牌：换头像后自增，头像文件按用户名定址、原地覆盖，无此令牌不会重载。 */
    var avatarVersion by mutableIntStateOf(0)
    var vpnStatusText by mutableStateOf("")
    var vpnStatusState by mutableIntStateOf(VPN_STATUS_DEFAULT)
    var vpnResult by mutableStateOf<LinkRow?>(null)
    var bottomClearancePx by mutableIntStateOf(0)
    val sources = mutableStateListOf(LinkSourceState(), LinkSourceState())
}

/** Java 友好的「传 URL」回调（minSdk 23 下不用 java.util.function）。 */
fun interface UrlConsumer {
    fun accept(url: String)
}

/**
 * 抽卡链接页（命令台来源列表）的 Compose 实现。
 *
 * 与原 `fragment_link.xml` 的对应关系：
 * - 页眉固定、正文滚动（原 ConstraintLayout + NestedScrollView）
 * - 账号行 = 头像徽标 + 无框下拉 + 上下箭头；无用户时整行变「前往添加」引导
 * - 原神 / 绝区零 = 点行获取，结果与就地错误长在行下
 * - 云游戏 = 导航行；VPN 抓包 = 导航行 + 常驻结果区
 *
 * 玻璃：4 个图标圆 + 2 个状态 chip + 每条结果的复制按钮，均经 [LensGlass] 复用
 * `LensGlassDrawable`（与原实现同一套折射渲染，无观感差异）。
 */
@Composable
fun LinkScreen(
    state: LinkUiState,
    onUserSelected: (Int) -> Unit,
    onEmptyUserClick: () -> Unit,
    onSourceClick: (Int) -> Unit,
    onRefreshClick: (Int) -> Unit,
    onCloudClick: () -> Unit,
    onVpnClick: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)

    Column(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize(),
    ) {
        // 页眉（固定，不随正文滚动）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = gutter,
                    end = gutter,
                    top = dimensionResource(R.dimen.space_5),
                    bottom = dimensionResource(R.dimen.space_3),
                ),
        ) {
            Text(text = stringResource(R.string.venus_eyebrow), style = textStyles.eyebrow)
            Text(
                text = stringResource(R.string.gacha_link_title),
                style = textStyles.headerTitle,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
            )
            Text(
                text = stringResource(R.string.gacha_link_subtitle),
                style = textStyles.headerSubtitle,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
            )
        }

        // 正文：等价于 Widget.Venus.ScreenScaffold（左右 gutter、上 space_2、下 space_4）
        // + BottomBarOverlay 的底部避让（原来由 attachClearance 直接改 padding）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = gutter,
                    end = gutter,
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_4) + clearance,
                ),
        ) {
            SectionLabel(stringResource(R.string.section_account))
            AccountRow(state, onUserSelected, onEmptyUserClick)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_sources),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            SourceRow(
                titleRes = R.string.genshin_impact,
                iconRes = R.drawable.ic_sparkles,
                source = state.sources[0],
                onClick = { onSourceClick(0) },
                onRefreshClick = { onRefreshClick(0) },
                onCopy = onCopy,
            )
            VenusDivider()
            SourceRow(
                titleRes = R.string.zenless_zone_zero,
                iconRes = R.drawable.ic_zap,
                source = state.sources[1],
                onClick = { onSourceClick(1) },
                onRefreshClick = { onRefreshClick(1) },
                onCopy = onCopy,
            )
            VenusDivider()

            NavRow(
                iconRes = R.drawable.ic_cloud,
                title = stringResource(R.string.cloud_gaming),
                subtitle = stringResource(R.string.cloud_open_hint),
                onClick = onCloudClick,
            )
            NavRow(
                iconRes = R.drawable.ic_link,
                title = stringResource(R.string.vpn_capture_title),
                // 原 status_vpn 默认态即「操作提示」，Java 未设置时回落同一句文案
                subtitle = state.vpnStatusText.ifEmpty { stringResource(R.string.vpn_capture_hint) },
                subtitleColor = vpnStatusColor(state.vpnStatusState),
                onClick = onVpnClick,
            )
            // VPN 捕获结果常驻区：切页/切后台回来仍在，可重复复制
            state.vpnResult?.let { LinkResultRow(row = it, onCopy = onCopy) }
        }
    }
}

/** VPN 行副标题的颜色语义（对齐原 setStatusVpn 的四态着色）。 */
@Composable
private fun vpnStatusColor(status: Int): Color = when (status) {
    VPN_STATUS_RUNNING -> MaterialTheme.colorScheme.primary
    VPN_STATUS_SUCCESS -> colorResource(R.color.status_success)
    VPN_STATUS_WARN -> colorResource(R.color.status_warning)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.sectionLabel,
        modifier = modifier.padding(bottom = dimensionResource(R.dimen.space_1)),
    )
}

/**
 * 账号上下文行：头像 + 用户名（带服务器后缀）+ 上下箭头。
 *
 * 有用户时点整行展开下拉；无用户时整行变成引导（原 updateUserGuideState 的
 * 「hint + 跳转用户管理页」语义），点击回调由 Java 注入。
 */
@Composable
private fun AccountRow(
    state: LinkUiState,
    onUserSelected: (Int) -> Unit,
    onEmptyUserClick: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    var expanded by remember { mutableStateOf(false) }
    val hasUsers = state.users.isNotEmpty()
    val serverSuffix = stringResource(if (state.oversea) R.string.server_os else R.string.server_cn)
    val label = if (hasUsers) {
        state.users.getOrNull(state.selectedUserIndex)
            ?.let { "$it [$serverSuffix]" }
            .orEmpty()
    } else {
        stringResource(R.string.home_no_user_hint)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (hasUsers) expanded = true else onEmptyUserClick() }
                .padding(vertical = dimensionResource(R.dimen.space_2)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VenusAvatar(username = state.avatarUsername, refreshToken = state.avatarVersion)
            Text(
                text = label,
                style = textStyles.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = dimensionResource(R.dimen.space_3)),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevrons_up_down),
                contentDescription = stringResource(R.string.select_user),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 原 MaterialAutoCompleteTextView.showDropDown()：列表项为「头像 + 原始用户名」
        // （item_user_dropdown 不带服务器后缀，后缀只在行内展示）
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.users.forEachIndexed { index, username ->
                DropdownMenuItem(
                    text = { Text(text = username, style = textStyles.bodyLarge) },
                    leadingIcon = { VenusAvatar(username = username) },
                    onClick = {
                        expanded = false
                        onUserSelected(index)
                    },
                )
            }
        }
    }
}

/**
 * 来源行：图标圆 + 标题/状态 + 状态 chip + 刷新圆。
 *
 * 三个状态的差异全部在此派生（原来散在 `setChipState` 的 30 行里）：
 * - PENDING：灰 chip + 灰描边，图标圆灰表面，不转
 * - FETCHING：主色 chip + 主色描边，刷新图标无限旋转
 * - FETCHED：成功绿 chip + 成功绿描边，图标圆回主色底（`bg_icon_circle`）
 */
@Composable
private fun SourceRow(
    titleRes: Int,
    iconRes: Int,
    source: LinkSourceState,
    onClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val success = colorResource(R.color.status_success)

    val chipText = when (source.state) {
        LINK_CHIP_FETCHING -> stringResource(R.string.gacha_fetching)
        LINK_CHIP_FETCHED -> stringResource(R.string.gacha_fetched)
        else -> stringResource(R.string.gacha_chip_pending)
    }
    val statusText = when (source.state) {
        LINK_CHIP_FETCHING -> stringResource(R.string.gacha_fetching)
        LINK_CHIP_FETCHED -> stringResource(R.string.gacha_fetched)
        else -> stringResource(R.string.gacha_not_fetched)
    }
    val accent = when (source.state) {
        LINK_CHIP_FETCHING -> primary
        LINK_CHIP_FETCHED -> success
        else -> neutral
    }
    val stroke = when (source.state) {
        LINK_CHIP_FETCHING -> primary
        LINK_CHIP_FETCHED -> success
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val iconTint = if (source.state == LINK_CHIP_FETCHED) primary else neutral

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.gap_row)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标圆：玻璃恒为玻璃，状态只体现在图标着色上
            Box(modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_circle))) {
                LensGlass(
                    modifier = Modifier.matchParentSize(),
                    circle = true,
                )
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensionResource(R.dimen.space_2)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = dimensionResource(R.dimen.space_3)),
            ) {
                Text(text = stringResource(titleRes), style = textStyles.rowTitle)
                Text(
                    text = statusText,
                    style = textStyles.monoValue,
                    color = accent,
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_hairline)),
                )
            }
            StatusChip(
                chipState = source.state,
                text = chipText,
                textColor = accent,
                strokeColor = stroke,
            )
            RefreshBadge(
                spinning = source.state == LINK_CHIP_FETCHING,
                onClick = onRefreshClick,
            )
        }

        ResultArea(source = source, onCopy = onCopy)
    }
}

/** 状态 chip：玻璃胶囊 + 语义色描边（对应原 `bg_chip` 被透镜接管后的观感）。 */
@Composable
private fun StatusChip(
    chipState: Int,
    text: String,
    textColor: Color,
    strokeColor: Color,
) {
    val textStyles = LocalVenusTextStyles.current
    val reducedMotion = rememberReducedMotion()
    val alpha = remember { Animatable(1f) }
    // 缓动与时长必须在组合期取（@Composable 不能出现在 LaunchedEffect 的挂起块里）
    val easing = rememberVenusEasing(R.anim.emphasized_decelerate)
    val shortDuration = LocalResources.current.getInteger(R.integer.motion_duration_short)
    // 首帧不重放淡入（原实现：lastChipStates 同态时不闪动）
    var lastState by remember { mutableIntStateOf(chipState) }

    LaunchedEffect(chipState) {
        if (chipState == lastState) return@LaunchedEffect
        lastState = chipState
        if (reducedMotion) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = shortDuration, easing = easing),
        )
    }

    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha.value }) {
        LensGlass(
            modifier = Modifier.matchParentSize(),
            pill = true,
            semanticStrokeColor = strokeColor.toArgb(),
        )
        Text(
            text = text,
            style = textStyles.monoValue,
            color = textColor,
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.space_2),
                vertical = dimensionResource(R.dimen.space_hairline),
            ),
        )
    }
}

/**
 * 刷新圆：获取中无限旋转（原 `ObjectAnimator` + LinearInterpolator，1000ms/圈）。
 *
 * 只旋转图标本体：原实现旋转整个 View 是为了让圆内壁纸一起转，而圆形取景下
 * 玻璃旋转与不旋转在视觉上等价，旋转图标即可，且省掉对透镜 View 的动画驱动。
 */
@Composable
private fun RefreshBadge(spinning: Boolean, onClick: () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    val rotation = if (spinning && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "refresh")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
            label = "refresh-rotation",
        ).value
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .padding(start = dimensionResource(R.dimen.space_2))
            .size(dimensionResource(R.dimen.icon_badge_circle))
            .clickable(onClick = onClick),
    ) {
        LensGlass(modifier = Modifier.matchParentSize(), circle = true)
        Icon(
            painter = painterResource(R.drawable.ic_refresh),
            contentDescription = stringResource(R.string.get_gacha_link),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .padding(dimensionResource(R.dimen.space_2))
                .rotate(rotation),
        )
    }
}

/**
 * 行内结果区 + 就地错误。
 *
 * 两者互斥：错误出现时结果区收起（原 showInlineError 里 `result.removeAllViews()`），
 * 错误顶替结果的位置出现，不另起全局提示块。
 * 进入动效为淡入 + 8dp 上滑，时长取 motion_duration_medium；尊重「减少动态效果」。
 */
@Composable
private fun ResultArea(source: LinkSourceState, onCopy: (String) -> Unit) {
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val duration = LocalResources.current.getInteger(R.integer.motion_duration_medium)
    val easing = rememberVenusEasing(R.anim.emphasized_decelerate)
    val slidePx = with(density) { 8.dp.roundToPx() }

    val enter: EnterTransition = if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(durationMillis = duration, easing = easing)) +
            slideInVertically(animationSpec = tween(durationMillis = duration, easing = easing)) { slidePx }
    }

    AnimatedVisibility(
        visible = source.result.isNotEmpty(),
        enter = enter,
        exit = ExitTransition.None,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimensionResource(R.dimen.space_9)),
        ) {
            source.result.forEach { row -> LinkResultRow(row = row, onCopy = onCopy) }
        }
    }

    AnimatedVisibility(
        visible = source.error != null,
        enter = enter,
        exit = ExitTransition.None,
    ) {
        Text(
            text = source.error.orEmpty(),
            style = LocalVenusTextStyles.current.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimensionResource(R.dimen.space_9),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_2),
                ),
        )
    }
}

/**
 * 单条链接行：小字标签（UID / 渠道）+ 截断链接 + 玻璃复制按钮。
 *
 * 对应原 `buildLinkRow`：标签 10sp 等宽、URL 11sp 等宽单行截断、
 * 复制按钮 32dp（8dp 圆角玻璃 + 18dp 图标，左边距 12dp）。
 */
@Composable
private fun LinkResultRow(row: LinkRow, onCopy: (String) -> Unit) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val cornerPx = with(density) { dimensionResource(R.dimen.shape_corner_xs).toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = textStyles.monoValue.copy(letterSpacing = 0.05.em),
            )
            Text(
                text = row.url,
                style = textStyles.urlMono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
            )
        }
        Box(
            modifier = Modifier
                .padding(start = dimensionResource(R.dimen.space_3))
                .size(dimensionResource(R.dimen.icon_badge_md))
                .clickable { onCopy(row.url) },
            contentAlignment = Alignment.Center,
        ) {
            LensGlass(
                modifier = Modifier.matchParentSize(),
                cornerRadiusPx = cornerPx,
            )
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
            )
        }
    }
}

/** 导航行（云游戏 / VPN）：玻璃图标圆 + 标题/副标题 + 右向 chevron。 */
@Composable
private fun NavRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.gap_row)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_circle))) {
            LensGlass(modifier = Modifier.matchParentSize(), circle = true)
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(dimensionResource(R.dimen.space_2)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = dimensionResource(R.dimen.space_3)),
        ) {
            Text(text = title, style = textStyles.rowTitle)
            Text(
                text = subtitle,
                style = textStyles.monoValue,
                color = subtitleColor,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_hairline)),
            )
        }
        // 原 XML 的 android:rotation="270"：chevron 向下 → 指向右
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(270f),
        )
    }
}

/** 系统「减少动态效果」开关：与 View 侧同一判定口径。 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { tools.isReducedMotionEnabled(context) }
}

/**
 * 抽卡链接页的 Compose 宿主：把 [LinkUiState] 与 Java 侧回调收敛成普通 setter。
 *
 * 用法（LinkFragment.onCreateView）：new LinkScreenView(requireContext()) 作为根视图返回，
 * 之后所有 UI 更新都走 `setSourceState` / `setSourceResult` / `setVpnStatus` 等方法。
 */
class LinkScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = LinkUiState()

    private var onUserSelectedListener: IndexConsumer? = null
    private var onEmptyUserClickListener: Runnable? = null
    private var onSourceClickListener: IndexConsumer? = null
    private var onRefreshClickListener: IndexConsumer? = null
    private var onCloudClickListener: Runnable? = null
    private var onVpnClickListener: Runnable? = null
    private var onCopyListener: UrlConsumer? = null

    @Composable
    override fun Content() {
        VenusTheme {
            LinkScreen(
                state = uiState,
                onUserSelected = { onUserSelectedListener?.accept(it) },
                onEmptyUserClick = { onEmptyUserClickListener?.run() },
                onSourceClick = { onSourceClickListener?.accept(it) },
                onRefreshClick = { onRefreshClickListener?.accept(it) },
                onCloudClick = { onCloudClickListener?.run() },
                onVpnClick = { onVpnClickListener?.run() },
                onCopy = { onCopyListener?.accept(it) },
            )
        }
    }

    /** 填充账号下拉：原始用户名列表 + 当前选中下标（-1 = 无选中）+ 是否国际服。 */
    fun setUsers(users: List<String>, selectedIndex: Int, oversea: Boolean) {
        uiState.users = ArrayList(users)
        uiState.selectedUserIndex = selectedIndex
        uiState.oversea = oversea
    }

    /** 仅更新当前选中下标（下拉选中后行内文案要即时跟随，无需重设整个列表）。 */
    fun setSelectedUserIndex(index: Int) {
        uiState.selectedUserIndex = index
    }

    /** 账号行头像对应的用户名（null/空 = 无用户空态，显示默认人像）。 */
    fun setAvatarUsername(username: String?) {
        uiState.avatarUsername = username
    }

    /** 强制重载头像（换头像/重置后文件原地覆盖，仅刷新用户名不会更新图片）。 */
    fun refreshAvatar() {
        uiState.avatarVersion++
    }

    /** 设来源行状态（[LINK_CHIP_PENDING] / [LINK_CHIP_FETCHING] / [LINK_CHIP_FETCHED]）。 */
    fun setSourceState(gameType: Int, chipState: Int) {
        uiState.sources.getOrNull(gameType)?.state = chipState
    }

    /** 设来源行结果（空列表即收起结果区）。 */
    fun setSourceResult(gameType: Int, rows: List<LinkRow>) {
        uiState.sources.getOrNull(gameType)?.result = ArrayList(rows)
    }

    /** 设来源行就地错误；null = 收起错误。 */
    fun setSourceError(gameType: Int, message: String?) {
        uiState.sources.getOrNull(gameType)?.error = message
    }

    /** 设 VPN 行文案与语义色（[VPN_STATUS_DEFAULT] 等四态）。 */
    fun setVpnStatus(text: String, status: Int) {
        uiState.vpnStatusText = text
        uiState.vpnStatusState = status
    }

    /** 设 VPN 捕获结果常驻行；label 或 url 为 null 时收起。 */
    fun setVpnResult(label: String?, url: String?) {
        uiState.vpnResult = if (label == null || url == null) null else LinkRow(label, url)
    }

    /** 底栏覆盖高度（px）：正文底部留白，等价于原来的 BottomBarOverlay.attachClearance。 */
    fun setBottomClearance(px: Int) {
        uiState.bottomClearancePx = px
    }

    fun setOnUserSelectedListener(listener: IndexConsumer?) {
        onUserSelectedListener = listener
    }

    fun setOnEmptyUserClickListener(listener: Runnable?) {
        onEmptyUserClickListener = listener
    }

    fun setOnSourceClickListener(listener: IndexConsumer?) {
        onSourceClickListener = listener
    }

    fun setOnRefreshClickListener(listener: IndexConsumer?) {
        onRefreshClickListener = listener
    }

    fun setOnCloudClickListener(listener: Runnable?) {
        onCloudClickListener = listener
    }

    fun setOnVpnClickListener(listener: Runnable?) {
        onVpnClickListener = listener
    }

    fun setOnCopyListener(listener: UrlConsumer?) {
        onCopyListener = listener
    }
}
