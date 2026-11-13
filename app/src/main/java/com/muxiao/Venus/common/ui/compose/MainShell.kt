package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools
import kotlin.math.abs
import kotlin.math.roundToInt

/** 宽屏（Expanded）判定口径，与原 layout-w840dp/activity_main.xml 的 840dp 断点一致。 */
private val WIDE_SCREEN_MIN_DP = 840.dp

/** 页面数（主页 / 用户管理 / 抽卡链接 / 设置），与原 bottom_nav_menu.xml 一致。 */
private const val PAGE_COUNT = 4

/** 底栏 Tab 项（图标 + 标签资源），顺序即页面下标。 */
private data class NavItem(val iconRes: Int, val labelRes: Int)

private val NAV_ITEMS = listOf(
    NavItem(R.drawable.ic_home, R.string.nav_home),
    NavItem(R.drawable.ic_user, R.string.nav_user_management),
    NavItem(R.drawable.ic_link, R.string.nav_gacha_link),
    NavItem(R.drawable.ic_settings, R.string.nav_settings),
)

/**
 * 主界面外壳：玻璃采样源子树（背景图片层 + HorizontalPager 四页）+ 液态玻璃底栏（宽屏 ≥840dp 为 NavigationRail）。
 * Pager 用 `beyondViewportPageCount` 全量常驻（等价原 offscreenPageLimit(3)，四页宿主视图不拆卸）；切页动效走每页
 * `graphicsLayer` 淡入 + 缩放，尊重系统「减少动态效果」；背景为 Compose Image（centerCrop），位图由 MainActivity 后台预合成后发布。
 * 采样链见 GlassBackdrop.kt：基础源（底色 + 背景图）供页内玻璃，内容源（再加 Pager）供底栏；宽屏无底栏故不挂内容源。
 */
class MainShellView(context: Context) : AbstractComposeView(context) {

    /** 采样源内容视图：背景 + HorizontalPager（不含底栏），供 GraphicsLayer 录制。 */
    val pagerHost = PagerHostView(context)

    /** 当前选中页（底栏高亮 / Rail 选中态）。 */
    internal val currentPage = mutableIntStateOf(0)

    /**
     * 页面连续位置（含拖动/动画偏移）= currentPage + currentPageOffsetFraction，由 PagerHostView 逐帧更新，
     * 驱动底栏单颗药丸横向滑动；手势拖动与点击 animateScrollToPage 都连续，避免「先切页再动药丸」。
     */
    internal val pagePosition = mutableFloatStateOf(0f)

    private var pageFactory: ((index: Int, context: Context) -> View)? = null
    private var onPageSelectedListener: IndexConsumer? = null

    internal data class Jump(val index: Int, val smooth: Boolean)

    init {
        // 显式着主题底色：闪屏库会在首帧前后重置主题，窗口背景回落系统动态色（偏蓝）
        setBackgroundColor(
            MaterialColors.getColor(context, android.R.attr.colorBackground, Color.TRANSPARENT)
        )
        pagerHost.shell = this
    }

    /** 页面视图工厂：外壳首次组合时逐页调用一次（每页一个，顺序即下标）。 */
    fun setPageFactory(factory: (index: Int, context: Context) -> View) {
        pageFactory = factory
        pagerHost.pageFactory = factory
    }

    /** 页面选中回调（含首次组合的初始页 0），MainActivity 据此做控制器 show/hide。 */
    fun setOnPageSelectedListener(listener: IndexConsumer?) {
        onPageSelectedListener = listener
    }

    /** 发布背景位图（MainActivity 后台预合成的成品；null = 纯色基底模式）。重采由 Content 的 LaunchedEffect(bg) 处理。 */
    fun publishBackground(bitmap: Bitmap?) {
        pagerHost.backgroundBitmap = bitmap
    }

    /** 程序化切页（替代原 viewPager.setCurrentItem；任意线程可调，内部 marshal 到主线程）。 */
    fun setCurrentPage(index: Int, smooth: Boolean) {
        post { pagerHost.requestPage(index, smooth) }
    }

    @Composable
    override fun Content() {
        VenusTheme {
            // 双采样源（见 GlassBackdrop.kt）：base = 底色+壁纸（页内玻璃，无透镜 → 无自引用）；content = 再加 Pager（底栏玻璃）
            val backdrops = rememberGlassBackdrops()
            pagerHost.baseBackdrop = backdrops.base
            pagerHost.contentBackdrop = backdrops.content
            // 用 LocalWindowInfo.containerSize（像素）换算 dp，替代不感知窗口变化的 Configuration.screenWidthDp
            val wide = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp() >= WIDE_SCREEN_MIN_DP
            }
            // 采样源子树的纯色基底：无背景图时玻璃折射的就是这个主题底色
            val baseColor = remember {
                androidx.compose.ui.graphics.Color(
                    MaterialColors.getColor(
                        context, android.R.attr.colorBackground, Color.WHITE
                    )
                )
            }
            val bg = pagerHost.backgroundBitmap
            // 背景位图发布不经滚动信号（contentVersion 不 bump）：源节点随重组重录，但透镜链不会自动重画，
            // 故以 bg 为 key 显式失效两个源，保证换壁纸后折射与磨砂立即跟随
            LaunchedEffect(bg) {
                backdrops.base.requestCapture()
                backdrops.content.requestCapture()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                // 基础源：底色 + 背景图。屏幕上被内容源完全覆盖，只为录制而画，静止壁纸的录制成本趋近于零
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .glassBackdropSource(backdrops.base)
                        .background(baseColor)
                ) {
                    if (bg != null) {
                        Image(
                            bitmap = bg.asImageBitmap(),
                            contentDescription = stringResource(R.string.background_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                if (wide) {
                    // 宽屏：左 NavigationRail + 右内容区；无底栏故不挂内容源（省每帧录制）
                    LaunchedEffect(Unit) { BottomBarOverlay.update(0) }
                    Row(modifier = Modifier.fillMaxSize()) {
                        VenusNavRail(
                            selected = currentPage.intValue,
                            onSelect = { pagerHost.requestPage(it, smooth = true) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        AndroidPagerHost(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        )
                    }
                } else {
                    // 内容源：底色 + 背景图 + Pager。页内透镜输出会进入此层，底栏折射「滚进玻璃下的玻璃」正确且无反馈环
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .glassBackdropSource(backdrops.content)
                            .background(baseColor)
                    ) {
                        if (bg != null) {
                            Image(
                                bitmap = bg.asImageBitmap(),
                                contentDescription = stringResource(R.string.background_image),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        // 手机：内容直通屏幕底（可滚进玻璃下方），顶部让出状态栏
                        AndroidPagerHost(modifier = Modifier.fillMaxSize().statusBarsPadding())
                    }
                    VenusBottomBar(
                        pagePosition = pagePosition.floatValue,
                        onSelect = { pagerHost.requestPage(it, smooth = true) },
                        backdrop = backdrops.content,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                // 全局提示条宿主：主界面四页共用（二级页面各自持有一份）
                VenusSnackbarHost(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    /** pagerHost 的 AndroidView 承载（工厂返回 eager 实例，避免重复创建）。 */
    @Composable
    private fun AndroidPagerHost(modifier: Modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { pagerHost },
            modifier = modifier,
        )
    }

    internal fun onPageChangedFromPager(index: Int) {
        currentPage.intValue = index
        onPageSelectedListener?.accept(index)
    }
}

/**
 * Pager 宿主：仅承载 HorizontalPager（背景层在外壳）。独立成 AbstractComposeView 是为了让「子树 = 四页内容」与底栏分树，
 * 底栏玻璃才不会采到自己（自引用残影）。
 */
// 非 private：MainActivity 引用该类型（属性可见性不得高于其类型）
class PagerHostView(context: Context) : AbstractComposeView(context) {
    var shell: MainShellView? = null
    var pageFactory: ((index: Int, context: Context) -> View)? = null

    /** 基础采样源（底色+壁纸，页内玻璃透镜采样）。外壳组合期注入。 */
    internal var baseBackdrop: GlassBackdropState? = null

    /** 内容采样源（底色+壁纸+页面，glassScrollMotion 旁听目标）。外壳组合期注入。 */
    internal var contentBackdrop: GlassBackdropState? = null

    /** 背景位图状态（由外壳转发发布）。 */
    internal var backgroundBitmap by mutableStateOf<Bitmap?>(null)

    private var pendingJump by mutableStateOf<MainShellView.Jump?>(null)

    fun requestPage(index: Int, smooth: Boolean) {
        pendingJump = MainShellView.Jump(index, smooth)
    }

    @Composable
    override fun Content() {
        VenusTheme {
            // AndroidView 子树是独立 composition，不继承外壳的 CompositionLocal，两个采样源都要在此重新提供：
            // 页内透镜读 base，滚动旁听读 content
            CompositionLocalProvider(
                LocalGlassBackdrop provides baseBackdrop,
                LocalGlassContentBackdrop provides contentBackdrop,
            ) {
                PagerContent()
            }
        }
    }

    @Composable
    private fun PagerContent() {
        val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
        val reducedMotion = remember { tools.isReducedMotionEnabled(context) }

        // 带玻璃的滚动页面必须挂 glassScrollMotion：Pager 的拖动/惯性经 nestedScroll 汇到这里，逐帧驱动重录
        Box(modifier = Modifier.fillMaxSize().glassScrollMotion()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = PAGE_COUNT,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 原 applyPageTransform：当前页 100%，两侧页轻微淡出并缩小
                            if (!reducedMotion) {
                                val pos = index -
                                    (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                                val offset = abs(pos).coerceIn(0f, 1f)
                                alpha = 1f - 0.12f * offset
                                scaleX = 1f - 0.03f * offset
                                scaleY = 1f - 0.03f * offset
                            }
                        },
                    factory = { ctx ->
                        pageFactory?.invoke(index, ctx) ?: View(ctx)
                    },
                )
            }
        }

        // 翻页不产生 LazyColumn 那样的 nestedScroll 消费，位移只在 pagerState 的 offset 上——不逐帧旁听的话
        // 内容源不会重采，底栏折射会停留在切页前的内容
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                .collect { position ->
                    contentBackdrop?.onContentMoved()
                    // 逐帧同步连续位置给外壳，驱动底栏药丸与页面同帧滑动
                    shell?.pagePosition?.floatValue = position
                }
        }

        // 页选回调（含初始页 0）：控制器 show/hide 联动
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                shell?.onPageChangedFromPager(page)
            }
        }
        // Java 侧程序化切页消费（如验证码跳转 setCurrentPage(0, false)）
        LaunchedEffect(pendingJump) {
            val jump = pendingJump ?: return@LaunchedEffect
            if (jump.index != pagerState.currentPage) {
                if (jump.smooth) pagerState.animateScrollToPage(jump.index)
                else pagerState.scrollToPage(jump.index)
            }
            pendingJump = null
        }
    }
}

/**
 * 底栏容器高度 68dp：M3 规范 80dp（内容 64 + 上下 8）偏松——内部选中药丸 40dp + 标签行约 16dp ≈ 56dp 已放得下，
 * 68dp 的点击区（≥ 触摸目标 48dp）还留 4dp 让标签不贴面板底边。
 */
private val BOTTOM_BAR_HEIGHT = 68.dp

/** 图标尺寸（M3 NavigationBar 规范）。 */
private val BOTTOM_BAR_ICON_SIZE = 24.dp

/**
 * 玻璃底栏。单颗药丸在 4 个 tab 间横向滑动，位置由 [pagePosition]（Pager 的 currentPage + offsetFraction 连续值）驱动，
 * 手势与点击切页都同帧同步，不各自淡入淡出。容器 68dp、面板 [GlassSpecs.BottomBar]（18/26dp，圆角 shape_corner_lg）、
 * 药丸 64×40dp 用 [GlassSpecs.BottomBarIndicator]；每个 tab 的颜色/图标上移/标签显隐都按连续选中分数插值。
 * 根节点 onGloballyPositioned 把「面板高 + 导航栏 inset + 底边距」发布给 [BottomBarOverlay]。
 */
@Composable
private fun VenusBottomBar(
    pagePosition: Float,
    onSelect: (Int) -> Unit,
    backdrop: GlassBackdropState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val navInsetPx = WindowInsets.navigationBars.getBottom(density)
    val bottomMargin = dimensionResource(R.dimen.space_4)
    val cornerRadiusDp = dimensionResource(R.dimen.shape_corner_lg)
    val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }

    Box(
        modifier = modifier
            // 观察器在 padding 之前：上报高度恰为「面板 + inset + 底边距」覆盖口径
            .onGloballyPositioned { coords -> BottomBarOverlay.update(coords.size.height) }
            .padding(bottom = with(density) { navInsetPx.toDp() } + bottomMargin)
            // 悬浮药丸造型：左右不贴屏幕边缘（space_4 = 16dp 屏幕基准边距）
            .padding(horizontal = dimensionResource(R.dimen.space_4))
    ) {
        LensGlass(
            modifier = Modifier
                .fillMaxWidth()
                .height(BOTTOM_BAR_HEIGHT),
            // 表面透度统一在 GlassSpecs.BottomBar 的 surfaceAlpha，不再外部计算
            spec = GlassSpecs.BottomBar,
            cornerRadiusPx = cornerRadiusPx,
            // 底栏采样**内容源**（壁纸+页面）：列表内容滚进玻璃下方才被折射。
            // 底栏在内容源子树之外，自身输出不进快照——无自引用。
            backdrop = backdrop,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tabWidthPx = with(density) { maxWidth.toPx() } / PAGE_COUNT
                val indicatorWidthPx = with(density) {
                    dimensionResource(R.dimen.bottom_nav_indicator_width).toPx()
                }
                val pillHeight = dimensionResource(R.dimen.bottom_nav_indicator_expanded_height)
                // 药丸垂直：固定 40dp，中心在面板中心上移 8dp（包住选中上移的图标）
                val pillTopPx = with(density) {
                    (BOTTOM_BAR_HEIGHT / 2 - pillHeight / 2 - 8.dp).toPx()
                }
                // 药丸水平位置 = 连续页位置 × tab 宽并在目标 tab 内居中；clamp 到边界，overscroll 时不溢出面板
                val maxPillLeft = tabWidthPx * PAGE_COUNT - indicatorWidthPx
                LensGlass(
                    modifier = Modifier
                        .offset {
                            val left = (
                                pagePosition * tabWidthPx +
                                    (tabWidthPx - indicatorWidthPx) / 2
                                ).coerceIn(0f, maxPillLeft).roundToInt()
                            IntOffset(x = left, y = pillTopPx.roundToInt())
                        }
                        .size(
                            width = dimensionResource(R.dimen.bottom_nav_indicator_width),
                            height = pillHeight,
                        ),
                    spec = GlassSpecs.BottomBarIndicator,
                    pill = true,
                    backdrop = backdrop,
                )

                // 4 个 tab 点击区（在药丸之上，后绘制）
                Row(modifier = Modifier.fillMaxSize()) {
                    NAV_ITEMS.forEachIndexed { index, item ->
                        // 该 tab 连续选中分数：页位置落在 index 时为 1，滑向相邻页平滑到 0
                        val selection = (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
                        SlidingTabButton(
                            item = item,
                            selection = selection,
                            onClick = { onSelect(index) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** 单个 tab 点击区：图标（选中上移）+ 标签（选中淡入），颜色随连续选中分数插值；内部不用 Row 成员，故不需要 RowScope。 */
@Composable
private fun SlidingTabButton(
    item: NavItem,
    selection: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(item.labelRes)
    // 未选 onSurfaceVariant → 选中 onSecondaryContainer，按分数连续插值
    val contentColor = lerp(
        colorScheme.onSurfaceVariant,
        colorScheme.onSecondaryContainer,
        selection,
    )

    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { this.selected = selection >= 0.5f },
        contentAlignment = Alignment.Center,
    ) {
        // 图标：未选中在面板中心，选中上移 8dp（graphicsLayer 只做绘制平移，不触发重布局）
        Icon(
            painter = painterResource(item.iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier
                .size(BOTTOM_BAR_ICON_SIZE)
                .graphicsLayer { translationY = -8.dp.toPx() * selection },
        )
        // 标签：底部对齐、留 4dp 间隙，alpha 随选中分数，滑动时相邻标签交叉淡入
        Text(
            text = label,
            style = LocalVenusTextStyles.current.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = dimensionResource(R.dimen.space_1))
                .alpha(selection),
        )
    }
}

/** 宽屏 NavigationRail：无玻璃（与原 View 版一致），指示器/配色走 M3 默认。 */
@Composable
private fun VenusNavRail(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    // 纵贯全高，底部让出导航栏 inset
    NavigationRail(modifier = modifier.navigationBarsPadding()) {
        NAV_ITEMS.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = index == selected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colorScheme.onSecondaryContainer,
                    selectedTextColor = colorScheme.onSecondaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
