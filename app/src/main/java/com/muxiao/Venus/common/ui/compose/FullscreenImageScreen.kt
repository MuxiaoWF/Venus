package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.muxiao.Venus.R
import androidx.core.graphics.createBitmap

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
 * 全屏图片查看（原 `activity_fullscreen_image.xml` + `ViewPager2` + `ImagePagerAdapter`）。
 *
 * 三处结构性替换：
 * 1. `ViewPager2` → [HorizontalPager]（页面左右切换、当前页回调语义一致）
 * 2. `TouchImageView` → Compose 原生缩放（[pointerInput] 双指捏合 + 双击缩放 + [graphicsLayer]
 *    位移钳制）：第三方缩放 View 依赖拆除，手势行为对齐（捏合 1~8x、双击切换 1x/2.5x、
 *    放大后拖动、缩回 1x 复位）
 * 3. 缩放与翻页的手势冲突：原实现靠 `requestDisallowInterceptTouchEvent` 在触摸层拦截，
 *    Compose 里改为「放大后关闭 pager 的 userScrollEnabled」，把横向手势整体让给图片
 *
 * 转场：原先是 `transitionName` 共享元素缩放，Compose 无 View 等价物（2A 方案），
 * 已降级为 Activity 淡入淡出，见 `ImageActivity.openFullscreen`。
 */
@Composable
fun FullscreenImageScreen(
    state: FullscreenUiState,
    initialPage: Int,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.pages.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = initialPage) { state.pages.size }
    // 当前页是否处于放大态：放大时 pager 让出横向手势，避免拖动图片被翻页抢走
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        // 翻页后新图是 fitCenter 原始比例，缩放态必须复位，否则 pager 会一直锁着不翻页
        zoomed = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = state.pages.getOrNull(page)
            ZoomableImagePage(
                url = item?.imageUrl,
                onZoomChanged = { isZoomed -> if (zoomed != isZoomed) zoomed = isZoomed },
            )
        }

        // 悬浮返回按钮（原 IconButton.Filled.Tonal：48dp 圆形、surfaceContainer 底、level3 阴影）
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = dimensionResource(R.dimen.elevation_level3),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    start = dimensionResource(R.dimen.space_4),
                    top = dimensionResource(R.dimen.space_2),
                )
                .size(dimensionResource(R.dimen.touch_target_min)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.content_description_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 底部信息卡：放大时滑出屏幕，缩回原比例时滑回（原 hideBottomInfo/showBottomInfo 的等价实现）
        val reducedMotion = rememberReducedMotionCompat()
        val duration = LocalResources.current.getInteger(R.integer.motion_duration_medium)
        val decelerate = rememberVenusEasing(R.anim.emphasized_decelerate)
        val accelerate = rememberVenusEasing(R.anim.emphasized_accelerate)
        val enter: EnterTransition = if (reducedMotion) {
            EnterTransition.None
        } else {
            slideInVertically(tween(duration, easing = decelerate)) { it } + fadeIn(tween(duration, easing = decelerate))
        }
        val exit: ExitTransition = if (reducedMotion) {
            ExitTransition.None
        } else {
            slideOutVertically(tween(duration, easing = accelerate)) { it } + fadeOut(tween(duration, easing = accelerate))
        }

        AnimatedVisibility(
            visible = !zoomed,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val item = state.pages.getOrNull(pagerState.currentPage)
            if (item != null) {
                val textStyles = LocalVenusTextStyles.current
                Surface(
                    shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_lg)),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = dimensionResource(R.dimen.stroke_width_hairline),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shadowElevation = dimensionResource(R.dimen.card_elevation_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.space_4)),
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = dimensionResource(R.dimen.space_5),
                            end = dimensionResource(R.dimen.space_5),
                            top = dimensionResource(R.dimen.space_5),
                            bottom = dimensionResource(R.dimen.space_6),
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                style = textStyles.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${pagerState.currentPage + 1}/${state.pages.size}",
                                style = textStyles.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = dimensionResource(R.dimen.space_4)),
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
                        ) {
                            Text(
                                text = stringResource(R.string.image_author_prefix) + item.author,
                                style = textStyles.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (item.time.isEmpty()) {
                                    ""
                                } else {
                                    stringResource(R.string.image_time_prefix) + item.time
                                },
                                style = textStyles.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        // 描述为空时整行不占位（原实现是 setVisibility(GONE)）
                        if (item.description.isNotEmpty()) {
                            Text(
                                text = item.description,
                                style = textStyles.body,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
                            )
                        }
                        Button(
                            onClick = onDownload,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = dimensionResource(R.dimen.space_5)),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                            )
                            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                            Text(text = stringResource(R.string.download_image))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单页图片：Compose 原生缩放 + Glide 加载 + 圆形进度。
 *
 * 加载参数与原 `ImagePagerAdapter` 完全一致（`override(屏宽, 屏高)` + `fitCenter`），
 * 失败落 `ic_error`；加载完成/失败都会关掉进度条。
 *
 * 缩放手势（替代 TouchImageView 桥接）：双指捏合 1~8x、放大后拖动（位移按
 * 缩放后的显示尺寸钳制，不让图片飞出容器）、双击在 1x/2.5x 间切换；
 * `currentZoom > 1f` 视为放大并回调（驱动底部信息卡显隐）。
 */
@Composable
private fun ZoomableImagePage(url: String?, onZoomChanged: (Boolean) -> Unit) {
    var loading by remember(url) { mutableStateOf(!url.isNullOrEmpty()) }
    var failed by remember(url) { mutableStateOf(false) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current
    // 配置感知的窗口像素尺寸，替代非配置感知的 context.resources.displayMetrics
    val windowSize = LocalWindowInfo.current.containerSize

    DisposableEffect(url) {
        var disposed = false
        var target: CustomTarget<Drawable>? = null
        if (!url.isNullOrEmpty()) {
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

    // 放大态上报（驱动底部信息卡显隐 + 关闭 pager 横滑）
    LaunchedEffect(scale) { onZoomChanged(scale > 1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 原 item_fullscreen_image 根布局的 padding（space_2，clipToPadding=false）
            .padding(dimensionResource(R.dimen.space_2)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            url.isNullOrEmpty() || failed -> Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = stringResource(R.string.fullscreen_image),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimensionResource(R.dimen.space_9)),
            )
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
                    .pointerInput(url) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        })
                    },
            )
        }

        if (loading) {
            // 原 XML：indicatorSize=touch_target_min(48dp) + trackThickness=progress_track_thickness(4dp)。
            // M3 的 Compose 版没有 trackThickness，环宽对应的是 strokeWidth。
            CircularProgressIndicator(
                modifier = Modifier.size(dimensionResource(R.dimen.touch_target_min)),
                strokeWidth = dimensionResource(R.dimen.progress_track_thickness),
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

    fun setOnBackClickListener(listener: Runnable?) {
        onBackListener = listener
    }

    fun setOnDownloadClickListener(listener: Runnable?) {
        onDownloadListener = listener
    }
}
