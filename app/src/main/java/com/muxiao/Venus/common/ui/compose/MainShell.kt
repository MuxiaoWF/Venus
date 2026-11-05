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
 * 主界面外壳（activity_main.xml 的 Compose 终态）：玻璃采样源子树（背景图片层 +
 * HorizontalPager 四页内容）+ 液态玻璃底栏（宽屏为 NavigationRail）。
 *
 * <p>迁移自 ViewPager2 + BottomNavigationView + 背景 ImageView 三件 View：
 * - 页面容器 → [HorizontalPager]（`beyondViewportPageCount` 全量常驻，等价原
 *   `offscreenPageLimit(3)`——四页的 Compose 宿主视图不拆卸）；
 * - 页面切换动效 → 每页 `graphicsLayer`（轻微淡入 + 缩放，与原 applyPageTransform 同参数，
 *   尊重系统「减少动态效果」）；
 * - 底栏 → Compose 手写玻璃底栏 [VenusBottomBar]（[LensGlass] 面板 18/26dp + 选中态
 *   药丸 12/24dp）——样式链/选中态/度量与迁移前的 Material 控件一致；
 * - 背景 → Compose Image（centerCrop），位图由 MainActivity 后台预合成后发布。
 *
 * <p>玻璃采样链（GlassBackdrop.kt，Kyant0/AndroidLiquidGlass 库 LayerBackdrop + 双采样源
 * 架构）：**基础源**（底色 + 背景图，不含透镜）供页内玻璃采样——结构上无自引用；
 * **内容源**（底色 + 背景图 + Pager）供底栏玻璃采样（列表滚进玻璃下方），底栏在源子树
 * 之外同样无反馈。宽屏（≥840dp）无底栏，不挂载内容源，与原 NavigationRail 布局同构。
 */
class MainShellView(context: Context) : AbstractComposeView(context) {

    /** 采样源内容视图：背景 + HorizontalPager（不含底栏），供 GraphicsLayer 录制。 */
    val pagerHost = PagerHostView(context)

    /** 当前选中页（底栏高亮 / Rail 选中态）。 */
    internal val currentPage = mutableIntStateOf(0)

    /**
     * 页面连续位置（含拖动/动画偏移）= currentPage + currentPageOffsetFraction。
     * 由 PagerHostView 逐帧更新，驱动底栏单颗药丸横向滑动，与 HorizontalPager 完全同步
     * （手势拖动与点击 animateScrollToPage 都连续），避免“先切页再动药丸”。
     */
    internal val pagePosition = mutableFloatStateOf(0f)

    private var pageFactory: ((index: Int, context: Context) -> View)? = null
    private var onPageSelectedListener: IndexConsumer? = null

    internal data class Jump(val index: Int, val smooth: Boolean)

    init {
        // 与原 activity_main.xml 根节点同语义：显式着主题底色——闪屏库会在首帧前后
        // 重置主题，窗口背景回落系统动态色（偏蓝），显式着色保证主题淡色底
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

    /** 发布背景位图（MainActivity 后台预合成的成品；null = 无背景图，纯色基底模式）。
     *  重采请求由 Content 的 LaunchedEffect(bg) 处理（此处调用时 backdrops 可能未创建）。 */
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
            // 双采样源（见 GlassBackdrop.kt 文件头）：base = 底色+壁纸（页内玻璃采样，
            // 不含透镜 → 无自引用）；content = 底色+壁纸+页面（底栏玻璃采样，底栏在源外）
            val backdrops = rememberGlassBackdrops()
            pagerHost.baseBackdrop = backdrops.base
            pagerHost.contentBackdrop = backdrops.content
            // 用 LocalWindowInfo.containerSize（像素）取窗口宽度，再按当前密度换算为 dp，
            // 替代不感知窗口变化的 Configuration.screenWidthDp。
            val wide = with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width.toDp() >= WIDE_SCREEN_MIN_DP
            }
            // 采样源子树的纯色基底（无背景图时玻璃折射主题底色，与原
            // GlassBackdrop.setSolidSurface 的采样锚底色同源）
            val baseColor = remember {
                androidx.compose.ui.graphics.Color(
                    MaterialColors.getColor(
                        context, android.R.attr.colorBackground, Color.WHITE
                    )
                )
            }
            val bg = pagerHost.backgroundBitmap
            // 背景位图发布/变更不经滚动信号（contentVersion 不会被 bump），源节点自身
            // 会随重组重录，但玻璃透镜链不会自动重画——在这里以 bg 为 key 显式失效两个
            // 源，保证壁纸切换后玻璃折射与磨砂立即跟随新壁纸。
            LaunchedEffect(bg) {
                backdrops.base.requestCapture()
                backdrops.content.requestCapture()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                // 基础源：底色 + 背景图（页内玻璃的采样源）。屏幕上被内容源完全覆盖，
                // 只为录制而画；静止壁纸的录制随本节点 draw 推进，成本趋近于零。
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
                    // 宽屏：左 NavigationRail + 右内容区（无底栏，与原 w840dp 布局同构）。
                    // 无玻璃采样内容源 → 不挂载 content source（省每帧录制与快照）。
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
                    // 内容源：底色 + 背景图 + Pager（底栏玻璃的采样源）。页内透镜输出
                    // 会进入此源层，底栏折射「滚进玻璃下的玻璃」语义正确且无反馈环。
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
 * Pager 宿主：仅承载 HorizontalPager（背景层在外壳，保持采样源子树与原 viewPager 一致）。
 * 独立成 AbstractComposeView 的原因：玻璃采样需要「子树 = 四页内容」与底栏分树，
 * 避免底栏玻璃采到自己（自引用残影）。
 */
// 非 private：MainActivity 引用该类型（public 属性要求类型可见性不低于属性本身，
// Kotlin 有效可见性规则）
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
            // 独立 composition（AndroidView 子树不继承外壳的 CompositionLocal）：
            // 两个采样源都要在此重新提供。页内透镜默认读 base（LocalGlassBackdrop），
            // 滚动旁听读 content（LocalGlassContentBackdrop → 重录内容源）。
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

        // 带玻璃的滚动页面必须挂 glassScrollMotion（Compose 滚动不触发 View 滚动回调）：
        // HorizontalPager 的拖动/惯性经 nestedScroll 汇到这里，逐帧驱动采样源重录
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

        // 翻页（点击 Tab 的 animateScrollToPage / 手势拖拽）不产生 LazyColumn 那样的
        // nestedScroll 消费——页面位移只体现在 pagerState 的 offset 上，捕获门控会把
        // 这类「无 motion 的内容变化」拦下，底栏折射就停留在切页前的内容。逐帧旁听
        // pager 位移推进内容源重采，切页全程底栏折射实时跟随。
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                .collect { position ->
                    contentBackdrop?.onContentMoved()
                    // 逐帧同步连续位置给外壳，驱动底栏药丸与页面同帧滑动。
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
 * 底部导航容器高度。M3 NavigationBar 规范为 80dp（内容区 64 + 上下 8），本项目先收窄到
 * 64dp，再略微回调到 68dp：内部内容（选中药丸 40dp + 标签行高约 16dp ≈ 56dp）放得下，
 * 点击区 68dp（≥ 触摸目标 48dp），多出的 4dp 让标签文字与面板底部边缘留出间隙、不贴边。
 */
private val BOTTOM_BAR_HEIGHT = 68.dp

/** 图标尺寸（M3 NavigationBar 规范）。 */
private val BOTTOM_BAR_ICON_SIZE = 24.dp

/**
 * 玻璃底栏（Compose 原生实现，替代原 Material3Expressive `BottomNavigationView`
 * + `LensGlassDrawable` 的 View 桥接）。
 *
 * 与旧实现的关键差异：**单颗药丸在 4 个 tab 之间横向滑动**，位置由 [pagePosition]
 * （HorizontalPager 的 currentPage + offsetFraction 连续值）驱动，手势拖动与点击切页时
 * 药丸都与页面**同帧同步**，不再各自淡入淡出、也不先切页再动药丸。
 *
 * 度量与样式：
 * - 容器 68dp 高、背景 = [LensGlass]（面板 18/26dp、圆角 shape_corner_lg），表面透度
 *   统一在 [GlassSpecs.BottomBar] 的 surfaceAlpha（不再外部读取资源计算）；
 * - 单颗药丸 64×40dp、药丸形、玻璃 [GlassSpecs.BottomBarIndicator]；
 * - 每个 tab 的图标/标签颜色、图标上移、标签显隐都由该 tab 的连续选中分数（0..1）插值，
 *   滑动时相邻 tab 平滑过渡。
 *
 * 根节点 onGloballyPositioned 把「玻璃面板高 + 导航栏 inset + 底边距」发布给
 * [BottomBarOverlay]。
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
                // 单颗滑动药丸：水平位置 = 连续页位置 × tab 宽，在目标 tab 内居中。
                // left 做边界限制：overscroll 时药丸停在面板边缘、不溢出。
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

/** 单个 tab 点击区：图标（选中上移）+ 标签（选中淡入），颜色随连续选中分数插值。
 *  不需要 RowScope：调用处的 Row 已提供 modifier.weight 所需作用域，本函数内部不使用 Row 成员。 */
@Composable
private fun SlidingTabButton(
    item: NavItem,
    selection: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(item.labelRes)
    // 颜色在未选 onSurfaceVariant → 选中 onSecondaryContainer 之间按分数连续插值
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
    // 纵贯全高，底部让出导航栏 inset（原 ViewCompat 监听给 rail 加 bottomPadding）
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
