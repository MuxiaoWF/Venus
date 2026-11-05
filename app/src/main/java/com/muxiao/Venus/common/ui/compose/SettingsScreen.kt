package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一扇设置抽屉（替代 `CollapsibleCardView`）。
 *
 * 与原控件的分工一致：**只负责折叠交互与折叠态摘要**，展开内容仍由 Java 侧生成的
 * Compose 宿主（`XxxSettingPaneView`）提供——那 12 个窗格的状态与回调逻辑不搬家，
 * 只是宿主的壳从 View 换成了 Compose。
 *
 * 与原 `CollapsibleCardView` 的行为差异（均为有意为之）：
 * - 不再缓存/动画「展开高度」：Compose 每帧按内容实测高度布局，`notifyContentChanged()`
 *   这类「内容长高要通知父容器重算」的补丁彻底不需要了；
 * - 展开后自动把整卡带入视口改由 [BringIntoViewRequester] 完成（原来是
 *   `requestRectangleOnScreen`），解决底部抽屉展开后被底栏遮挡的问题。
 */
@Stable
class SettingsCard(@StringRes internal val titleRes: Int) {

    /** 折叠态标题行右侧的 mono 摘要（如「已开启」「国服」）。 */
    var value by mutableStateOf("")
        private set

    /** 摘要颜色（ARGB）；0 表示回退到主题的 onSurfaceVariant。 */
    internal var valueColorArgb by mutableIntStateOf(0)

    internal var isExpanded by mutableStateOf(false)
    internal var isVisible by mutableStateOf(true)
    internal var contentView by mutableStateOf<View?>(null)

    fun setValue(value: CharSequence) {
        this.value = value.toString()
    }

    fun setValueColor(color: Int) {
        valueColorArgb = color
    }

    /** 设置展开内容（Compose 宿主视图）。 */
    fun setContent(view: View) {
        contentView = view
    }

    /** 显隐（对齐 `View.setVisibility`：仅 VISIBLE 视为显示）。 */
    fun setVisibility(visibility: Int) {
        isVisible = visibility == View.VISIBLE
    }

    fun toggle() {
        isExpanded = !isExpanded
    }
}

/**
 * 展开内容的测试标记。
 *
 * 抽屉内容是一个真实 View（窗格宿主经 [AndroidView] 桥接），Compose 的语义树看不进
 * View 内部，因此用标记表示「这一扇抽屉当前是展开的」——UI 测试据此断言折叠交互。
 */
const val SETTINGS_DRAWER_CONTENT_TAG = "settings-drawer-content"

/** 设置页的可变状态（页眉版本 chip + 底栏避让高度）。 */
@Stable
class SettingsUiState {
    var versionChip by mutableStateOf("")
    var bottomClearancePx by mutableIntStateOf(0)
}

/**
 * 设置页的 Compose 实现，替代 `fragment_settings.xml` 与 `style_collapsible_card_view.xml`。
 *
 * 结构等价于原 XML：页眉固定 + 正文滚动；正文按「签到 / 配置 / 应用 / 其他」四组，
 * 每组以 mono 区块标签开头，组内每行之间一条发丝线，组尾多留 space_2。
 *
 * 12 个 [SettingsCard] 由宿主按固定顺序构造并逐个传入——顺序即版式，
 * 与原来 XML 里的书写顺序一一对应。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    daily: SettingsCard,
    gameDaily: SettingsCard,
    server: SettingsCard,
    config: SettingsCard,
    update: SettingsCard,
    cache: SettingsCard,
    language: SettingsCard,
    theme: SettingsCard,
    background: SettingsCard,
    notification: SettingsCard,
    skland: SettingsCard,
    about: SettingsCard,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)

    Column(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize(),
    ) {
        CommandHeader(
            title = stringResource(R.string.settings_title),
            chipText = state.versionChip,
            subtitle = stringResource(R.string.settings_subtitle),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = gutter,
                    end = gutter,
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_2) + clearance,
                ),
        ) {
            SectionLabel(stringResource(R.string.section_sign_in_settings))
            SettingsCardItem(daily)
            VenusDivider()
            SettingsCardItem(gameDaily)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_config),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            SettingsCardItem(server)
            VenusDivider()
            SettingsCardItem(config)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_app),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            SettingsCardItem(update)
            VenusDivider()
            SettingsCardItem(cache)
            VenusDivider()
            SettingsCardItem(language)
            VenusDivider()
            SettingsCardItem(theme)
            VenusDivider()
            SettingsCardItem(background)
            VenusDivider()
            SettingsCardItem(notification)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_other),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            SettingsCardItem(skland)
            VenusDivider()
            SettingsCardItem(about)

            // 查看榜单图：独立表面行按钮，与四个分组平级
            GlassRowButton(
                iconRes = R.drawable.ic_image,
                text = stringResource(R.string.view_miyoushe_ranking),
                onClick = onImageClick,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_bottom_fab)))
        }
    }
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
 * 一扇抽屉：标题行（标题 + 摘要 + 箭头）与展开内容。
 *
 * 对应原 `style_collapsible_card_view.xml` 的透明变体：
 * 标题行左右 space_2、上下 space_4；展开内容整体缩进 space_2，左侧画 2dp 引导线
 * （原来由 `bg_drawer_rail` + `InsetDrawable` 表达），表示「从属于父行」的层级。
 */
@Composable
private fun SettingsCardItem(card: SettingsCard) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val bringIntoView = remember { BringIntoViewRequester() }
    val duration = LocalResources.current.getInteger(R.integer.motion_duration_medium)
    val decelerate = rememberVenusEasing(R.anim.emphasized_decelerate)
    val inset = dimensionResource(R.dimen.space_2)
    val railWidthPx = with(density) { dimensionResource(R.dimen.space_hairline).toPx() }
    // 竖线底端内缩：不与下方分隔横线相交，留出间隔
    val railBottomInsetPx = with(density) { dimensionResource(R.dimen.space_2).toPx() }
    // 引导线颜色与 Widget.Venus.Divider 同源（outlineVariant）
    val railColor = MaterialTheme.colorScheme.outlineVariant

    // 箭头：展开 0°（朝下）、折叠 180°（朝上），与 ic_0collapse_180expand 语义一致
    val rotation by animateFloatAsState(
        targetValue = if (card.isExpanded) 0f else 180f,
        animationSpec = if (reducedMotion) tween(0) else tween(duration, easing = decelerate),
        label = "toggle",
    )

    // 展开完成后把整卡带入视口（原来在高度动画结束时 requestRectangleOnScreen）。
    // 必须等布局落定再调用：bringIntoView 在未 attach 的节点上会抛 IllegalStateException，
    // 「减少动态效果」下退化为等一帧，与原实现 `post { ... }` 的时序一致。
    LaunchedEffect(card.isExpanded) {
        if (!card.isExpanded) return@LaunchedEffect
        delay((if (reducedMotion) 16L else duration.toLong()).milliseconds)
        bringIntoView.bringIntoView()
    }

    AnimatedVisibility(
        visible = card.isVisible,
        enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn(tween(duration, easing = decelerate)),
        exit = if (reducedMotion) fadeOut(tween(0)) else fadeOut(tween(duration / 2)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                    .clickable { card.toggle() }
                    .padding(
                        start = inset,
                        end = inset,
                        top = dimensionResource(R.dimen.space_4),
                        bottom = dimensionResource(R.dimen.space_4),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(card.titleRes),
                    style = textStyles.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (card.value.isNotEmpty()) {
                    Text(
                        text = card.value,
                        style = textStyles.monoValue,
                        color = if (card.valueColorArgb != 0) Color(card.valueColorArgb)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = dimensionResource(R.dimen.space_2),
                            end = dimensionResource(R.dimen.space_1),
                        ),
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.expand_collapse),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(dimensionResource(R.dimen.space_1))
                        .rotate(rotation),
                )
            }

            AnimatedVisibility(
                visible = card.isExpanded,
                enter = if (reducedMotion) fadeIn(tween(0))
                else expandVertically(tween(duration, easing = decelerate)),
                exit = if (reducedMotion) fadeOut(tween(0))
                else shrinkVertically(tween(duration, easing = decelerate)),
            ) {
                val content = card.contentView
                if (content != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SETTINGS_DRAWER_CONTENT_TAG)
                            .padding(start = inset)
                            // 左侧引导线：2dp 竖线，表达抽屉内容从属于标题行；
                            // 底端内缩 space_2，不与下方分隔横线相接
                            .drawBehind {
                                drawRect(
                                    color = railColor,
                                    topLeft = Offset.Zero,
                                    size = Size(railWidthPx, size.height - railBottomInsetPx),
                                )
                            },
                    ) {
                        AndroidView(
                            // 折叠→展开会重建 AndroidView 节点：旧节点可能尚未释放，
                            // 目标 View 还挂在它名下，先摘下再交给新节点，避免
                            // "The specified child already has a parent"
                            factory = {
                                (content.parent as? ViewGroup)?.removeView(content)
                                content
                            },
                            // 内容在引导线右侧留出间距：竖线 2dp + space_2(8dp)，不紧贴竖线
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = dimensionResource(R.dimen.space_hairline) + dimensionResource(R.dimen.space_2)),
                        )
                    }
                }
            }
        }
    }
}

/** 表面行按钮（`Widget.Venus.RowButton`）：液态玻璃 8dp 圆角 + 行内图标 + 文案。 */
@Composable
private fun GlassRowButton(
    iconRes: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { dimensionResource(R.dimen.shape_corner_xs).toPx() }
    Box(modifier = modifier.fillMaxWidth()) {
        LensGlass(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.touch_target_min))
                .clickable(onClick = onClick)
                .padding(horizontal = dimensionResource(R.dimen.space_4)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
            )
            Text(
                text = text,
                style = LocalVenusTextStyles.current.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = dimensionResource(R.dimen.space_3)),
            )
        }
    }
}

/** 系统「减少动态效果」开关：与 View 侧同一判定口径。 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { tools.isReducedMotionEnabled(context) }
}

/**
 * 设置页的 Compose 宿主。
 *
 * 12 个抽屉在此**急切构造**并对外暴露：`SettingsFragment` 的用法与原
 * `CollapsibleCardView` 完全一致（`setContent` / `setValue` / `setValueColor` /
 * `setVisibility`），因此那 12 套窗格的状态与回调逻辑一行都不用改。
 */
class SettingsScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = SettingsUiState()

    val dailyCard = SettingsCard(R.string.miyoushe_coin_sign_in)
    val gameDailyCard = SettingsCard(R.string.game_sign_in)
    val serverCard = SettingsCard(R.string.server_type)
    val configCard = SettingsCard(R.string.config_info)
    val updateCard = SettingsCard(R.string.update)
    val cacheCard = SettingsCard(R.string.cache)
    val languageCard = SettingsCard(R.string.language)
    val themeCard = SettingsCard(R.string.theme)
    val backgroundCard = SettingsCard(R.string.background)
    val notificationCard = SettingsCard(R.string.background_running)
    val sklandCard = SettingsCard(R.string.skland_sign_in)
    val aboutCard = SettingsCard(R.string.about)

    private var onImageClickListener: Runnable? = null

    @Composable
    override fun Content() {
        VenusTheme {
            SettingsScreen(
                state = uiState,
                daily = dailyCard,
                gameDaily = gameDailyCard,
                server = serverCard,
                config = configCard,
                update = updateCard,
                cache = cacheCard,
                language = languageCard,
                theme = themeCard,
                background = backgroundCard,
                notification = notificationCard,
                skland = sklandCard,
                about = aboutCard,
                onImageClick = { onImageClickListener?.run() },
            )
        }
    }

    /** 页眉右上角版本 chip（`v2.4.0` 形式）。 */
    fun setVersionChip(text: String) {
        uiState.versionChip = text
    }

    /** 底栏覆盖高度（px）：正文底部留白，等价于原来的 BottomBarOverlay.attachClearance。 */
    fun setBottomClearance(px: Int) {
        uiState.bottomClearancePx = px
    }

    fun setOnImageClickListener(listener: Runnable?) {
        onImageClickListener = listener
    }
}

@Preview(name = "设置 · 折叠", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    val cards = remember {
        listOf(
            SettingsCard(R.string.miyoushe_coin_sign_in),
            SettingsCard(R.string.game_sign_in),
            SettingsCard(R.string.server_type),
            SettingsCard(R.string.config_info),
            SettingsCard(R.string.update),
            SettingsCard(R.string.cache),
            SettingsCard(R.string.language),
            SettingsCard(R.string.theme),
            SettingsCard(R.string.background),
            SettingsCard(R.string.background_running),
            SettingsCard(R.string.skland_sign_in),
            SettingsCard(R.string.about),
        )
    }
    VenusTheme {
        SettingsScreen(
            state = SettingsUiState().apply { versionChip = "v2.4.0" },
            daily = cards[0].apply { setValue("已开启"); setValueColor(0xFF146511.toInt()) },
            gameDaily = cards[1].apply { setValue("4/5 已开启") },
            server = cards[2].apply { setValue("国服") },
            config = cards[3].apply { setValue("已配置") },
            update = cards[4].apply { setValue("自动检查") },
            cache = cards[5].apply { setValue("12.4 MB") },
            language = cards[6].apply { setValue("简体中文") },
            theme = cards[7].apply { setValue("默认 · 跟随系统") },
            background = cards[8].apply { setValue("默认") },
            notification = cards[9].apply { setValue("已开启") },
            skland = cards[10].apply { setValue("已关闭") },
            about = cards[11].apply { setValue("开源许可 · 隐私政策") },
            onImageClick = {},
        )
    }
}
