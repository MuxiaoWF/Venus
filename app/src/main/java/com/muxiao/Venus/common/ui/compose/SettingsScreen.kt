package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.muxiao.Venus.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一扇设置抽屉：只负责折叠交互与折叠态摘要，展开内容由 Java 侧持有的窗格宿主（`XxxSettingPaneView`）提供。
 * 高度不缓存也不做动画——Compose 每帧按内容实测布局；展开后把整卡带入视口走 [BringIntoViewRequester]，
 * 解决底部抽屉展开后被底栏遮挡的问题。
 */
@Stable
class SettingsCard(@StringRes internal val titleRes: Int, defaultExpanded: Boolean = false) {

    /** 折叠态标题行右侧的 mono 摘要（如「已开启」「国服」）。 */
    var value by mutableStateOf("")
        private set

    /** 摘要颜色（ARGB）；0 表示回退到主题的 onSurfaceVariant。 */
    internal var valueColorArgb by mutableIntStateOf(0)

    internal var isExpanded by mutableStateOf(defaultExpanded)
    internal var isVisible by mutableStateOf(true)

    /**
     * 展开内容的组合内容，由 [SettingsScreenView] 构造时注入。窗格只是 `mutableStateOf` + `Render()`，直接在本组合里渲染；
     * 不要再退回 `AndroidView` 塞内层 ComposeView——那会让折叠/展开重建节点（须手动 removeView 才不报 child already has a parent），
     * 且内层测量多走一趟 View→Compose，高度收敛慢一帧。
     */
    internal var paneContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    fun setValue(value: CharSequence) {
        this.value = value.toString()
    }

    fun setValueColor(color: Int) {
        valueColorArgb = color
    }

    /** 注入展开内容的组合内容（`SettingsScreenView` 内部调用，Java 侧不再直接持有窗格 View）。 */
    fun setPaneContent(content: @Composable () -> Unit) {
        paneContent = content
    }

    /** 显隐（对齐 `View.setVisibility`：仅 VISIBLE 视为显示）。 */
    fun setVisibility(visibility: Int) {
        isVisible = visibility == View.VISIBLE
    }

    fun toggle() {
        isExpanded = !isExpanded
    }

    /** 跳转定位请求计数：自增即执行一次 bringIntoView。与 [isExpanded] 解耦——抽屉已展开时 `LaunchedEffect(isExpanded)` 不会再触发。 */
    internal var scrollRequest by mutableIntStateOf(0)
        private set

    /** 展开并滚动到可见（跨页跳转定位：用户管理页点凭证状态、任务列表点未配置项）；已展开时只补一次滚动。 */
    fun expand() {
        isExpanded = true
        scrollRequest++
    }
}

/**
 * 展开内容的测试标记：内容已是同一组合里的 Composable，仍保留它——断言「展开时有内容 / 折叠时没有」比逐条断言文案更稳定。
 */
const val SETTINGS_DRAWER_CONTENT_TAG = "settings-drawer-content"

/**
 * 「编辑目标账号」抽屉的账号行模型。[id] 是存储 id（头像按它定址），[label] 是展示名，[selected] = 正在编辑它。
 */
@Immutable
class SettingAccountChip(
    val id: String,
    val label: String,
    val selected: Boolean = false,
)

/** 设置页的可变状态（页眉版本 chip + 底栏避让高度）。编辑目标账号状态由 [TaskAccountPaneView] 自持。 */
@Stable
class SettingsUiState {
    var versionChip by mutableStateOf("")
    var bottomClearancePx by mutableIntStateOf(0)
}

/**
 * 设置页：页眉固定 + 正文滚动；正文按「签到 / 配置 / 应用 / 其他」四组，每组以 mono 区块标签开头，
 * 组内每行之间一条发丝线、组尾多留 space_2。14 个 [SettingsCard] 由宿主按固定顺序传入——顺序即版式。
 * 「编辑目标账号」卡置于签到组最顶部：下面四张任务卡（含森空岛）的读写都指向它，先选人再配置。
 * 服务器类型不在此页：它是「添加账号时登录哪一服」的选择，入口在用户管理页的添加流程里。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    daily: SettingsCard,
    gameDaily: SettingsCard,
    cloudGame: SettingsCard,
    targetAccount: SettingsCard,
    config: SettingsCard,
    update: SettingsCard,
    cache: SettingsCard,
    language: SettingsCard,
    theme: SettingsCard,
    background: SettingsCard,
    notification: SettingsCard,
    autoCaptcha: SettingsCard,
    skland: SettingsCard,
    about: SettingsCard,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)

    // 页眉随滚动收缩 / 展开；本页是 verticalScroll，回顶判定读 ScrollState.value
    val headerCollapse = rememberHeaderCollapseState()
    val headerScroll = rememberHeaderCollapseConnection(headerCollapse)
    val scrollState = rememberScrollState()
    val atTop by remember { derivedStateOf { scrollState.value == 0 } }
    headerCollapse.ExpandWhenAtTop(atTop)

    Column(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize()
            .nestedScroll(headerScroll),
    ) {
        CommandHeader(
            title = stringResource(R.string.settings_title),
            chipText = state.versionChip,
            subtitle = stringResource(R.string.settings_subtitle),
            collapse = headerCollapse,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(
                    start = gutter,
                    end = gutter,
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_2) + clearance,
                ),
        ) {
            SectionLabel(stringResource(R.string.section_sign_in_settings))

            // 编辑目标账号：标题行右侧 mono 显当前目标名，展开抽屉选人（含「应用到全部」）。
            // 四张任务卡（含森空岛）的读写都指向这个编辑目标
            SettingsCardItem(targetAccount)
            VenusDivider()
            SettingsCardItem(daily)
            VenusDivider()
            SettingsCardItem(gameDaily)
            VenusDivider()
            SettingsCardItem(cloudGame)
            VenusDivider()
            SettingsCardItem(skland)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_config),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
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
            SettingsCardItem(autoCaptcha)
            VenusDivider()

            SectionLabel(
                text = stringResource(R.string.section_other),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            SettingsCardItem(about)

            // 查看榜单图：独立表面行按钮，与四个分组平级；复用 GlassActionRow，不要再写九宫格 drawable 的私有版本
            GlassActionRow(
                icon = VenusIcons.Image,
                text = stringResource(R.string.view_miyoushe_ranking),
                onClick = onImageClick,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_bottom_fab)))
        }
    }
}

/**
 * 一扇抽屉：标题行（标题 + 摘要 + 箭头）与展开内容。标题行左右 space_2、上下 space_4；展开内容整体缩进 space_2，
 * 左侧 2dp 引导线表示「从属于父行」的层级，缩进量由 DrawerPane 的 space_3 提供，此处不要再叠加。
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
    // 引导线要正好框住内容本身：上让 DrawerPane 的 4dp 顶部内边距、下让 drawer_content_bottom(16dp) 收尾留白，
    // 否则竖线会伸出内容之外
    val railTopInsetPx = with(density) { dimensionResource(R.dimen.space_1).toPx() }
    val railBottomInsetPx = with(density) { dimensionResource(R.dimen.drawer_content_bottom).toPx() }
    // 引导线颜色与 Widget.Venus.Divider 同源（outlineVariant）
    val railColor = MaterialTheme.colorScheme.outlineVariant

    // 箭头：展开 0°（朝下）、折叠 180°（朝上），与 ic_0collapse_180expand 语义一致
    val rotation by animateFloatAsState(
        targetValue = if (card.isExpanded) 0f else 180f,
        animationSpec = if (reducedMotion) tween(0) else tween(duration, easing = decelerate),
        label = "toggle",
    )

    // 展开后把整卡带入视口，但必须等布局落定：bringIntoView 在未 attach 的节点上会抛 IllegalStateException；
    // 「减少动态效果」下退化为等一帧。跳过初组合：defaultExpanded 的卡（编辑目标账号）初值即为展开，
    // 不算「用户展开」动作，进页面时不应触发滚动定位
    val firstExpandObserved = remember { mutableStateOf(false) }
    LaunchedEffect(card.isExpanded) {
        if (!firstExpandObserved.value) {
            firstExpandObserved.value = true
            return@LaunchedEffect
        }
        if (!card.isExpanded) return@LaunchedEffect
        delay((if (reducedMotion) 16L else duration.toLong()).milliseconds)
        bringIntoView.bringIntoView()
    }

    // 跨页跳转定位：抽屉此前已展开时上面的 effect 不会再触发，这里按请求计数补一次滚动
    LaunchedEffect(card.scrollRequest) {
        if (card.scrollRequest == 0) return@LaunchedEffect
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
                    imageVector = VenusIcons.ChevronDown,
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
                val content = card.paneContent
                if (content != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SETTINGS_DRAWER_CONTENT_TAG)
                            .padding(start = inset)
                            // 左侧 2dp 引导线：高度取内容高度并上让 4dp、下让 16dp（与 DrawerPane 内边距同源），
                            // 既不穿过标题行，也不伸进分隔线前的收尾留白
                            .drawBehind {
                                drawRect(
                                    color = railColor,
                                    topLeft = Offset(0f, railTopInsetPx),
                                    size = Size(
                                        railWidthPx,
                                        (size.height - railTopInsetPx - railBottomInsetPx)
                                            .coerceAtLeast(0f),
                                    ),
                                )
                            },
                    ) {
                        // 这里不要再加缩进：与引导线的间距由 DrawerPane 的 space_3(12) 提供（12 - 2dp 线宽 = 10dp）；
                        // 窗格自己也已有 12dp，叠加会把内容推到 46dp，竖线看着"飘"在左边。
                        // 直接调用而非 AndroidView：展开/折叠不会重建节点。
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 「编辑目标账号」抽屉内容：全宽账号行（头像 + 名字 + 选中勾）+「正在编辑」说明 + 应用到全部入口。
 *
 * <p>由 Java 侧 [setAccounts]/[setEditingHint] 驱动；点击账号行回传其在列表中的下标
 * （列表与全量账号同序，Java 侧按下标切编辑目标）。选中行用 Check 图标标注，替代旧 chips 行的描边。
 */
class TaskAccountPaneView {

    private val accountsState = mutableStateOf<List<SettingAccountChip>>(emptyList())
    private val editingHintState = mutableStateOf("")

    private var onSelectAccountListener: IndexConsumer? = null
    private var onApplyAllListener: Runnable? = null

    @Composable
    fun Render() {
        val textStyles = LocalVenusTextStyles.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = dimensionResource(R.dimen.space_1),
                    bottom = dimensionResource(R.dimen.drawer_content_bottom),
                ),
        ) {
            accountsState.value.forEachIndexed { index, account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                        .clickable { onSelectAccountListener?.accept(index) }
                        .padding(
                            horizontal = dimensionResource(R.dimen.space_2),
                            vertical = dimensionResource(R.dimen.space_2),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                ) {
                    VenusAvatar(
                        username = account.id,
                        size = dimensionResource(R.dimen.icon_badge_md),
                    )
                    Text(
                        text = account.label,
                        style = textStyles.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (account.selected) {
                        Icon(
                            imageVector = VenusIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hint = editingHintState.value
                if (hint.isNotEmpty()) {
                    Text(
                        text = hint,
                        style = textStyles.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = dimensionResource(R.dimen.space_2),
                                top = dimensionResource(R.dimen.space_2),
                                bottom = dimensionResource(R.dimen.space_2),
                            ),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                        .clickable { onApplyAllListener?.run() }
                        .padding(
                            horizontal = dimensionResource(R.dimen.space_2),
                            vertical = dimensionResource(R.dimen.space_2),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                ) {
                    Icon(
                        imageVector = VenusIcons.Copy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                    )
                    Text(
                        text = stringResource(R.string.settings_apply_all_accounts),
                        style = textStyles.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    /** 全量账号行（与全量账号列表同序）；空列表时仅剩「应用到全部」行（无账号时整卡由 Java 侧隐藏）。 */
    fun setAccounts(accounts: List<SettingAccountChip>) {
        accountsState.value = accounts
    }

    /** 「正在编辑：xxx」说明，与「应用到全部」同行左对齐；空串 = 隐藏（入口仍靠右）。 */
    fun setEditingHint(hint: String) {
        editingHintState.value = hint
    }

    fun setOnSelectAccountListener(listener: IndexConsumer?) {
        onSelectAccountListener = listener
    }

    fun setOnApplyAllListener(listener: Runnable?) {
        onApplyAllListener = listener
    }
}

/**
 * 设置页宿主：14 个抽屉（[SettingsCard]）与 14 个窗格状态宿主都在此急切构造并对外暴露，供 `SettingsFragment`
 * 调 setValue / setVisibility / expand / setXxx。接线在构造时完成（`card.setPaneContent { pane.Render() }`），
 * 使窗格组合直接长在设置页的同一 composition 里，不经 `AndroidView` 桥接。
 */
class SettingsScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = SettingsUiState()

    val dailyCard = SettingsCard(R.string.miyoushe_coin_sign_in)
    val gameDailyCard = SettingsCard(R.string.game_sign_in)
    val cloudGameCard = SettingsCard(R.string.cloud_game_sign_in)
    // 默认展开：编辑目标是四张任务卡读写的唯一事实，进页面直接可见可切
    val targetAccountCard = SettingsCard(R.string.settings_task_account_title, defaultExpanded = true)
    val configCard = SettingsCard(R.string.config_info)
    val updateCard = SettingsCard(R.string.update)
    val cacheCard = SettingsCard(R.string.cache)
    val languageCard = SettingsCard(R.string.language)
    val themeCard = SettingsCard(R.string.theme)
    val backgroundCard = SettingsCard(R.string.background)
    val notificationCard = SettingsCard(R.string.background_running)
    val autoCaptchaCard = SettingsCard(R.string.auto_captcha_title)
    val sklandCard = SettingsCard(R.string.skland_sign_in)
    val aboutCard = SettingsCard(R.string.about)

    // 窗格状态宿主：只持有 mutableStateOf 与回调，由 Java 侧 setXxx(...) 驱动
    val dailyPane = DailySettingPaneView()
    val gameDailyPane = GameDailySettingPaneView()
    val cloudGamePane = CloudGameSettingPaneView()
    val taskAccountPane = TaskAccountPaneView()
    val configPane = BbsUtilsSettingPaneView()
    val updatePane = UpdateSettingPaneView()
    val cachePane = CacheSettingPaneView()
    val languagePane = LanguageSettingPaneView()
    val themePane = ThemeSettingPaneView()
    val backgroundPane = BackgroundSettingPaneView()
    val notificationPane = NotificationSettingPaneView()
    val autoCaptchaPane = AutoCaptchaSettingPaneView()
    val sklandPane = SklandSettingPaneView()
    val aboutPane = AboutSettingPaneView()

    init {
        dailyCard.setPaneContent { dailyPane.Render() }
        gameDailyCard.setPaneContent { gameDailyPane.Render() }
        cloudGameCard.setPaneContent { cloudGamePane.Render() }
        targetAccountCard.setPaneContent { taskAccountPane.Render() }
        configCard.setPaneContent { configPane.Render() }
        updateCard.setPaneContent { updatePane.Render() }
        cacheCard.setPaneContent { cachePane.Render() }
        languageCard.setPaneContent { languagePane.Render() }
        themeCard.setPaneContent { themePane.Render() }
        backgroundCard.setPaneContent { backgroundPane.Render() }
        notificationCard.setPaneContent { notificationPane.Render() }
        autoCaptchaCard.setPaneContent { autoCaptchaPane.Render() }
        sklandCard.setPaneContent { sklandPane.Render() }
        aboutCard.setPaneContent { aboutPane.Render() }
    }

    private var onImageClickListener: Runnable? = null
    @Composable
    override fun Content() {
        VenusTheme {
            SettingsScreen(
                state = uiState,
                daily = dailyCard,
                gameDaily = gameDailyCard,
                cloudGame = cloudGameCard,
                targetAccount = targetAccountCard,
                config = configCard,
                update = updateCard,
                cache = cacheCard,
                language = languageCard,
                theme = themeCard,
                background = backgroundCard,
                notification = notificationCard,
                autoCaptcha = autoCaptchaCard,
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
            SettingsCard(R.string.cloud_game_sign_in),
            SettingsCard(R.string.settings_task_account_title),
            SettingsCard(R.string.config_info),
            SettingsCard(R.string.update),
            SettingsCard(R.string.cache),
            SettingsCard(R.string.language),
            SettingsCard(R.string.theme),
            SettingsCard(R.string.background),
            SettingsCard(R.string.background_running),
            SettingsCard(R.string.auto_captcha_title),
            SettingsCard(R.string.skland_sign_in),
            SettingsCard(R.string.about),
        )
    }
    VenusTheme {
        SettingsScreen(
            state = SettingsUiState().apply { versionChip = "v2.4.0" },
            daily = cards[0].apply { setValue("已开启"); setValueColor(0xFF146511.toInt()) },
            gameDaily = cards[1].apply { setValue("4/5 已开启") },
            cloudGame = cards[2].apply { setValue("已关闭") },
            targetAccount = cards[3].apply { setValue("张三") },
            config = cards[4].apply { setValue("已配置") },
            update = cards[5].apply { setValue("自动检查") },
            cache = cards[6].apply { setValue("12.4 MB") },
            language = cards[7].apply { setValue("简体中文") },
            theme = cards[8].apply { setValue("默认 · 跟随系统") },
            background = cards[9].apply { setValue("默认") },
            notification = cards[10].apply { setValue("已开启") },
            autoCaptcha = cards[11].apply { setValue("已关闭") },
            skland = cards[12].apply { setValue("已关闭") },
            about = cards[13].apply { setValue("开源许可 · 隐私政策") },
            onImageClick = {},
        )
    }
}
