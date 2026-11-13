package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.muxiao.Venus.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏浏览的一页：一张图 + 它所属帖子的元信息。
 *
 * 原 `ImagePagerAdapter` 把「帖子 × 图片」拍平成 ImageItem 列表，元信息随每张图重复携带；
 * 迁移后保持同样的拍平方式（`position` 语义不变，下载与计数都以拍平后的页为准）。
 */
@Immutable
class FullscreenPage(
    val imageUrl: String?,
    val title: String,
    val author: String,
    val time: String,
    val description: String,
)

/** 全屏页状态：页列表 + 当前页（Java 侧下载按当前页取 URL）。 */
@Stable
class FullscreenUiState {
    var pages by mutableStateOf<List<FullscreenPage>>(emptyList())
    var currentPage by mutableIntStateOf(0)
}

/**
 * 全屏图片查看（`ViewPager2` + `TouchImageView` 的 Compose 重绘版）。
 *
 * <h3>重绘要点</h3>
 * <ul>
 *   <li><b>纯黑画布</b>：图片铺满整屏（去掉原 8dp 内边距），四周不再是主题底色；</li>
 *   <li><b>顶部压暗条</b>：返回 / 页码 / 下载都挪到顶部，页码不再随底部卡一起滑出，
 *       放大后也知道自己在第几张；</li>
 *   <li><b>底部信息卡收起态 56dp</b>：默认只显示标题 + 作者，点开才展开描述，
 *       默认遮挡从屏高 30% 降到 8%；</li>
 *   <li><b>点击切换沉浸</b> + <b>下滑关闭</b>（放大态禁用，避免与拖动冲突）。</li>
 * </ul>
 *
 * <h3>为什么这里不用 LensGlass</h3>
 * 全屏页的背景就是照片本身，玻璃透镜需要「先录制内容、再折射」，等于每帧重录一整屏大图，
 * 缩放/拖动时会明显掉帧。这里改用半透明白 16% 表面 + 发丝描边，
 * 在照片上是同一种"浮在内容之上"的观感，成本为 0。
 *
 * 手势分级（与旧版一致并新增两项）：
 * 1. 双指捏合缩放 1~8x、放大后拖动（位移钳制）、双击 1x/2.5x；
 * 2. 放大后关闭 pager 横滑，把横向手势整体让给图片；
 * 3. 新增：未放大时下滑超过 160dp 关闭页面（Initial pass 抢在子节点之前判定，
 *    只认单指竖直位移，出现第二指立即放弃）。松手分两种收尾——
 *    达阈值则在本页把内容继续滑出屏幕后回调 `onBack`（配合
 *    `fullscreen_exit_fade`，避免系统默认的横向 Activity 转场与下拉方向相悖），
 *    未达阈值则弹簧回弹；
 * 4. 新增：单击切换浮层显隐。
 */
@Composable
fun FullscreenImageScreen(
    state: FullscreenUiState,
    initialPage: Int,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    downloading: Boolean = false,
) {
    if (state.pages.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = initialPage) { state.pages.size }
    // 当前页是否处于放大态：放大时 pager 让出横向手势，避免拖动图片被翻页抢走
    var zoomed by remember { mutableStateOf(false) }
    // 沉浸：单击图片切换浮层显隐
    var chromeVisible by remember { mutableStateOf(true) }
    // 底部信息卡展开态（收起态 56dp）
    var infoExpanded by remember { mutableStateOf(false) }
    // 下滑关闭的跟随位移
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val dismissThreshold = with(LocalDensity.current) {
        dimensionResource(R.dimen.space_9).toPx() * 4f
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        // 翻页后新图是 fitCenter 原始比例，缩放态必须复位，否则 pager 会一直锁着不翻页
        zoomed = false
        infoExpanded = false
    }

    val reducedMotion = rememberReducedMotionCompat()
    val duration = LocalResources.current.getInteger(R.integer.motion_duration_medium)
    val decelerate = rememberVenusEasing(R.anim.emphasized_decelerate)
    val accelerate = rememberVenusEasing(R.anim.emphasized_accelerate)
    val chromeEnter: EnterTransition = if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(tween(duration, easing = decelerate))
    }
    val chromeExit: ExitTransition = if (reducedMotion) {
        ExitTransition.None
    } else {
        fadeOut(tween(duration / 2, easing = accelerate))
    }
    val cardEnter: EnterTransition = if (reducedMotion) {
        EnterTransition.None
    } else {
        slideInVertically(tween(duration, easing = decelerate)) { it } +
            fadeIn(tween(duration, easing = decelerate))
    }
    val cardExit: ExitTransition = if (reducedMotion) {
        ExitTransition.None
    } else {
        slideOutVertically(tween(duration, easing = accelerate)) { it } +
            fadeOut(tween(duration, easing = accelerate))
    }

    val scope = rememberCoroutineScope()
    val containerHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    // 滑出屏幕的目标位移：拿不到实际高度时按阈值倍数兜底，保证一定滑出视口
    val exitDistancePx = if (containerHeightPx > 0f) containerHeightPx else dismissThreshold * 6f
    // 已在播放「滑出 → finish」的收尾动画：期间不再接受新手势，避免重复触发
    var dismissing by remember { mutableStateOf(false) }
    // 回弹动画的句柄：新手势按下时取消，否则动画会和手指抢 dragOffset
    var settleJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 手势挂在不动的那一层：内容偏移后坐标不漂移，拖动全程手感一致
            .pointerInput(zoomed, dismissing) {
                if (zoomed || dismissing) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    // 上一轮回弹还没跑完就再次按下：立刻让出控制权
                    settleJob?.cancel()
                    var totalX = 0f
                    var totalY = 0f
                    var claiming = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // 出现第二根手指 = 用户在缩放，立即放弃关闭手势
                        if (event.changes.size > 1) {
                            claiming = false
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y
                        if (!claiming && totalY > viewConfiguration.touchSlop && totalY > abs(totalX)) {
                            claiming = true
                        }
                        if (claiming) {
                            // 抢在图片的缩放/拖动之前消费，避免同时平移图片
                            change.consume()
                            dragOffset = totalY.coerceAtLeast(0f)
                        }
                    }
                    when {
                        // 达阈值：先在本页把内容滑出屏幕，再真正 finish。
                        // 否则 finish 会走系统默认的横向 Activity 转场，方向与「向下拖」相反，
                        // 这正是下拉过程中「页面还在往左退」的割裂感来源。
                        claiming && dragOffset > dismissThreshold -> {
                            dismissing = true
                            settleJob?.cancel()
                            settleJob = scope.launch {
                                animate(
                                    initialValue = dragOffset,
                                    targetValue = exitDistancePx,
                                    animationSpec = tween(
                                        durationMillis = 140,
                                        easing = FastOutLinearInEasing,
                                    ),
                                ) { value, _ -> dragOffset = value }
                                onBack()
                            }
                        }
                        // 未达阈值（含多指中断）：弹簧回弹，不再瞬间归零
                        dragOffset > 0f -> settleJob = scope.launch {
                            animate(
                                initialValue = dragOffset,
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { value, _ -> dragOffset = value }
                        }
                    }
                }
            },
    ) {
        // 背景层：本页窗口是透明的（Theme.Venus.Fullscreen），黑底随下拉渐隐，
        // 下层图库页由此逐渐透出，松手时内容已滑出、背景已透明，衔接没有黑闪。
        // 用 drawBehind 在绘制阶段读 dragOffset：只重绘、不重组，拖动全程不掉帧。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val fade = (dragOffset / (dismissThreshold * 2.5f)).coerceIn(0f, 1f)
                    drawRect(Color.Black, alpha = 1f - fade)
                },
        )

        // 内层才是跟着手指走的内容：下移 + 轻微缩小（接近 iOS 的下拉关闭手感）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .graphicsLayer {
                    val pull = (dragOffset / dismissThreshold).coerceIn(0f, 1f)
                    val scale = 1f - 0.08f * pull
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = state.pages.getOrNull(page)
                ZoomableImagePage(
                    url = item?.imageUrl,
                    onZoomChanged = { isZoomed -> if (zoomed != isZoomed) zoomed = isZoomed },
                    onTap = {
                        chromeVisible = !chromeVisible
                        if (!chromeVisible) infoExpanded = false
                    },
                )
            }

            // ===== 顶部压暗条：返回 / 页码 / 下载 =====
            AnimatedVisibility(
                visible = chromeVisible && !zoomed,
                enter = chromeEnter,
                exit = chromeExit,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                FullscreenTopBar(
                    pageIndex = pagerState.currentPage,
                    pageCount = state.pages.size,
                    downloading = downloading,
                    onBack = onBack,
                    onDownload = onDownload,
                )
            }

            // ===== 底部信息卡：默认 56dp 收起态 =====
            AnimatedVisibility(
                visible = chromeVisible && !zoomed,
                enter = cardEnter,
                exit = cardExit,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val item = state.pages.getOrNull(pagerState.currentPage)
                if (item != null) {
                    FullscreenInfoCard(
                        item = item,
                        expanded = infoExpanded,
                        onToggle = { infoExpanded = !infoExpanded },
                    )
                }
            }
        }   // 内层（跟随位移/缩放的内容）
    }       // 外层（黑底 + 手势）
}

/** 顶部压暗条：渐变 scrim 保证浅色照片上图标仍可读。 */
@Composable
private fun FullscreenTopBar(
    pageIndex: Int,
    pageCount: Int,
    downloading: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    horizontal = dimensionResource(R.dimen.space_4),
                    vertical = dimensionResource(R.dimen.space_2),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeCircle(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(
                        horizontal = dimensionResource(R.dimen.space_3),
                        vertical = dimensionResource(R.dimen.space_1),
                    ),
            ) {
                Text(
                    text = stringResource(
                        R.string.fullscreen_page_indicator,
                        pageIndex + 1,
                        pageCount,
                    ),
                    style = textStyles.monoValue,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            ChromeCircle(
                iconRes = R.drawable.ic_download,
                contentDescription = stringResource(R.string.download_image),
                onClick = onDownload,
                // 下载中：图标位置换成一圈进度，避免用户以为点了没反应
                busy = downloading,
            )
        }
    }
}

/**
 * 顶栏圆形按钮：与本项目其它顶栏（图库 / 登录 / OAuth / 云游戏）同一颗 [GlassCircleButton]，
 * 只是表面换成照片上更合适的「白色 16%」——`surfaceContainer` 在暗色照片上会糊成一团灰。
 *
 * <h3>这里仍然没有折射的原因</h3>
 * 见本文件头「为什么这里不用 LensGlass」：本页没有 `VenusBackdropHost`，采样源为 null，
 * 透镜按既定降级只画表面材质。所以拿到的是统一外观，不是每帧重录整屏照片的开销。
 *
 * 下载中不换按钮、只换内芯：进度环顶掉图标，避免用户以为点空。
 */
@Composable
private fun ChromeCircle(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    GlassCircleButton(
        onClick = onClick,
        enabled = !busy,
        spec = GlassSpecs.Standard.copy(surfaceColor = Color.White, surfaceAlpha = 0.16f),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_md)),
                strokeWidth = dimensionResource(R.dimen.progress_track_thickness),
                color = Color.White,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_sm)),
            )
        }
    }
}

/**
 * 底部信息卡：收起态 56dp（标题 + 作者），点开才展开描述。
 *
 * 用半透明黑而非不透明 surfaceContainer——照片在黑底上，半透明卡能透出底部内容，
 * 视觉上"浮"在图上而不像贴了一块板。
 */
@Composable
private fun FullscreenInfoCard(
    item: FullscreenPage,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    // 作者 / 时间合成一行：在组合作用域里先算好，别塞进 buildString（非组合上下文）
    val authorPrefix = stringResource(R.string.image_author_prefix)
    val authorLine = if (item.time.isEmpty()) {
        authorPrefix + item.author
    } else {
        authorPrefix + item.author + " · " + item.time
    }
    val shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_lg))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = dimensionResource(R.dimen.space_4),
                vertical = dimensionResource(R.dimen.space_3),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(
                    width = dimensionResource(R.dimen.stroke_width_hairline),
                    color = Color.White.copy(alpha = 0.16f),
                    shape = shape,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = dimensionResource(R.dimen.row_min_height))
                    .padding(
                        horizontal = dimensionResource(R.dimen.space_4),
                        vertical = dimensionResource(R.dimen.space_2),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = textStyles.title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_hairline)))
                    Text(
                        text = authorLine,
                        style = textStyles.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(
                        if (expanded) R.string.fullscreen_info_collapse else R.string.fullscreen_info_expand,
                    ),
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier
                        .padding(start = dimensionResource(R.dimen.space_2))
                        .rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    if (item.description.isNotEmpty()) {
                        Text(
                            text = item.description,
                            style = textStyles.body,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = dimensionResource(R.dimen.space_4),
                                end = dimensionResource(R.dimen.space_4),
                                bottom = dimensionResource(R.dimen.space_4),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单页图片：Compose 原生缩放 + Glide 加载。
 *
 * 加载参数与原 `ImagePagerAdapter` 一致（`override(屏宽, 屏高)` + `fitCenter`），
 * 失败时给出「重试」（原实现只画一个 ic_error，用户只能退出重进）。
 *
 * 缩放手势：双指捏合 1~8x、放大后拖动（位移按缩放后的显示尺寸钳制，不让图片飞出容器）、
 * 双击在 1x/2.5x 间切换；`currentZoom > 1f` 视为放大并回调（驱动浮层显隐与 pager 锁定）。
 */
@Composable
private fun ZoomableImagePage(
    url: String?,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var loading by remember(url) { mutableStateOf(!url.isNullOrEmpty()) }
    var failed by remember(url) { mutableStateOf(false) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    // 重试计数：作为加载副作用与缩放状态的 key，点重试即重新发起 Glide 请求
    var reloadKey by remember(url) { mutableIntStateOf(0) }
    val context = LocalContext.current
    // 配置感知的窗口像素尺寸，替代非配置感知的 context.resources.displayMetrics
    val windowSize = LocalWindowInfo.current.containerSize

    DisposableEffect(url, reloadKey) {
        var disposed = false
        var target: CustomTarget<Drawable>? = null
        if (!url.isNullOrEmpty()) {
            loading = true
            failed = false
            // Glide.load(String) 返回 RequestBuilder<Drawable>：目标必须是 Target<Drawable>，
            // 出图后统一转位图（BitmapDrawable 走零拷贝快路径）
            target = object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (!disposed) {
                        val bmp = resource.toBitmap()
                        if (bmp != null) bitmap = bmp else failed = true
                        loading = false
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    loading = false
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    failed = true
                    loading = false
                }
            }
            Glide.with(context)
                .load(url)
                .override(windowSize.width, windowSize.height)
                .fitCenter()
                .into(target)
        }
        onDispose {
            disposed = true
            try {
                target?.let { Glide.with(context).clear(it) }
            } catch (_: IllegalArgumentException) {
                // Activity 已销毁：Glide 自身会在 Activity 销毁时清理请求
            }
        }
    }

    // 缩放状态（换页即复位：state 均以 url 为 key remember）
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 放大态上报（驱动浮层显隐 + 关闭 pager 横滑）
    LaunchedEffect(scale) { onZoomChanged(scale > 1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url.isNullOrEmpty() || failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = stringResource(R.string.fullscreen_image),
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_lg)),
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
                Text(
                    text = stringResource(R.string.fullscreen_load_failed),
                    style = LocalVenusTextStyles.current.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable {
                            bitmap = null
                            failed = false
                            reloadKey++
                        }
                        .heightIn(min = dimensionResource(R.dimen.touch_target_min))
                        .padding(horizontal = dimensionResource(R.dimen.space_6)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                    )
                    Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                    Text(
                        text = stringResource(R.string.action_retry),
                        style = LocalVenusTextStyles.current.labelMedium,
                        color = Color.White,
                    )
                }
            }

            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.fullscreen_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .clipToBounds()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(url) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                clampImageOffset(offset + pan, bitmap!!, containerSize, newScale)
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(url, reloadKey) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    },
            )
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(dimensionResource(R.dimen.touch_target_min)),
                strokeWidth = dimensionResource(R.dimen.progress_track_thickness),
                color = Color.White,
            )
        }
    }
}

/**
 * 把缩放后的拖动位移钳制在「缩放后的显示尺寸 − 容器尺寸」的一半以内：
 * 图片边缘不越过容器边界（缩回 1x 时位移恒为 0）。
 * 位移作用在未缩放的父坐标（graphicsLayer 平移在 scale 之后应用于屏幕空间），
 * 故直接以钳制后的屏幕空间边界为准。
 */
private fun clampImageOffset(offset: Offset, image: Bitmap, container: IntSize, scale: Float): Offset {
    if (container.width == 0 || container.height == 0 || image.width == 0 || image.height == 0) {
        return Offset.Zero
    }
    val fit = minOf(
        container.width / image.width.toFloat(),
        container.height / image.height.toFloat(),
    )
    val maxX = maxOf(0f, (image.width * fit * scale - container.width) / 2f)
    val maxY = maxOf(0f, (image.height * fit * scale - container.height) / 2f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** Drawable → Bitmap（BitmapDrawable 零拷贝；其余按内在尺寸软件绘制，失败回退错误图路径）。 */
private fun Drawable.toBitmap(): Bitmap? {
    (this as? BitmapDrawable)?.let { return it.bitmap }
    val w = intrinsicWidth.takeIf { it > 0 } ?: return null
    val h = intrinsicHeight.takeIf { it > 0 } ?: return null
    return try {
        createBitmap(w, h).also { bmp ->
            android.graphics.Canvas(bmp).also { canvas ->
                setBounds(0, 0, w, h)
                draw(canvas)
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** 全屏页的「减少动态效果」判定（与 View 侧 tools.isReducedMotionEnabled 同口径）。 */
@Composable
private fun rememberReducedMotionCompat(): Boolean {
    val context = LocalContext.current
    return remember(context) { com.muxiao.Venus.common.tools.isReducedMotionEnabled(context) }
}

/**
 * 全屏图片页的 Compose 宿主。
 *
 * 页面数据一次性下发（拍平后的页列表），Java 侧只保留下载与权限逻辑；
 * 当前页由宿主内部跟随 pager 更新，`getCurrentPage()` 供下载取图。
 */
class FullscreenImageScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = FullscreenUiState()

    private var initialPage by mutableIntStateOf(0)
    private var downloadingState = mutableStateOf(false)
    private var onBackListener: Runnable? = null
    private var onDownloadListener: Runnable? = null

    @Composable
    override fun Content() {
        VenusTheme {
            VenusPageRoot {
                FullscreenImageScreen(
                    state = uiState,
                    initialPage = initialPage,
                    onBack = { onBackListener?.run() },
                    onDownload = { onDownloadListener?.run() },
                    onPageChanged = { uiState.currentPage = it },
                    downloading = downloadingState.value,
                )
            }
        }
    }

    /** 下发拍平后的页列表与初始页（须在 setContentView 之前调用，保证首次组合即拿到数据）。 */
    fun setPages(pages: List<FullscreenPage>, startPage: Int) {
        uiState.pages = ArrayList(pages)
        uiState.currentPage = startPage
        initialPage = startPage
    }

    /** 当前页下标（下载按页取图）。 */
    fun getCurrentPage(): Int = uiState.currentPage

    /** 下载进行中：顶栏下载按钮换成进度环并禁用重复点击。 */
    fun setDownloading(downloading: Boolean) {
        downloadingState.value = downloading
    }

    fun setOnBackClickListener(listener: Runnable?) {
        onBackListener = listener
    }

    fun setOnDownloadClickListener(listener: Runnable?) {
        onDownloadListener = listener
    }
}
