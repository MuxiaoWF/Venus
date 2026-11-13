package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.muxiao.Venus.R
import kotlin.math.roundToInt

/**
 * 图库列表项的数据快照。
 *
 * 对应原 `parseImageData` 产出的 Map；Java 侧仍在后台线程解析 JSON，
 * 只在主线程把结果映射成这个不可变对象交给 Compose，避免把 `Map<String, Object>` 泄漏进组合层。
 */
@Immutable
class ImagePost(
    val title: String,
    val author: String,
    val description: String,
    val time: String,
    val coverUrl: String?,
)

/**
 * 图库页的可变状态：游戏 / 分类选择、榜单 Tab、图片列表、多选态、加载与下载进度。
 *
 * 下拉与 Tab 的「当前项」是纯展示状态，Java 侧仍持有 `currentCategoryId` / `currentListTypes`
 * 这些业务状态；两边通过回调对齐，避免同一份选择在两个地方各存一份而不同步。
 */
@Stable
class ImageUiState {
    var forums by mutableStateOf<List<String>>(emptyList())
    var forumIndex by mutableIntStateOf(0)
    var categories by mutableStateOf<List<String>>(emptyList())
    var categoryIndex by mutableIntStateOf(-1)
    var tabs by mutableStateOf<List<String>>(emptyList())
    var tabIndex by mutableIntStateOf(0)
    var items by mutableStateOf<List<ImagePost>>(emptyList())
    var selectionMode by mutableStateOf(false)
    var selected by mutableStateOf<Set<Int>>(emptySet())

    /** 首屏 / 切榜单加载中（驱动骨架屏，替代原来"空白 + Snackbar"） */
    var loading by mutableStateOf(false)

    /** 多选下载进度（0 = 未在下载），由 Java 侧逐张回报 */
    var downloadDone by mutableIntStateOf(0)
    var downloadTotal by mutableIntStateOf(0)

    /** 最近一次点击的卡片窗口矩形（left, top, width, height），供 Activity 做缩放转场起点 */
    var clickBounds by mutableStateOf<IntArray?>(null)
}

/**
 * 图库页（原 `activity_image.xml` + `ImageAdapter` 的 Compose 重绘版）。
 *
 * <h3>与迁移初版的差异</h3>
 * 初版是「不透明 M3 AppBar + 两个描边下拉 + Tab 行」的 1:1 还原，固定占约 170dp；
 * 重绘后改为「壁纸透出的玻璃顶栏 + 一条可横滑的筛选 chip 行」：
 * <ul>
 *   <li>顶栏玻璃采样本页内容（`VenusBackdropHost` 的内容源），图片会从玻璃下滚过；</li>
 *   <li>顶栏随滚动跟手折叠：上滑（继续往下浏览）时筛选行按手势逐像素收起，顶栏收到 56dp
 *       标题行；反向滑动立即展开，松手过半自动对齐。列表 padding 与顶栏同步收放；</li>
 *   <li>多选不再只有一个工具栏图标：底部浮出玻璃操作条，含选中数、下载进度与取消。</li>
 * </ul>
 *
 * @param state 页面状态
 * @param onBack 返回（多选态时由 Java 先退出多选）
 * @param onForumSelected 游戏 chip 下拉选中（下标对应 Java 侧 forumNames）
 * @param onCategorySelected 分类 chip 下拉选中
 * @param onTabSelected 榜单 chip 选中
 * @param onItemClick 点卡片：多选态切换选中，否则进全屏
 * @param onItemLongClick 长按卡片：进入多选并选中
 * @param onDownloadClick 点击下载
 * @param onCancelSelection 取消多选
 * @param onRetry 空态/失败态重试
 */
@Composable
fun ImageScreen(
    state: ImageUiState,
    onBack: () -> Unit,
    onForumSelected: (Int) -> Unit,
    onCategorySelected: (Int) -> Unit,
    onTabSelected: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    onItemLongClick: (Int) -> Unit,
    onDownloadClick: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val rowHeight = dimensionResource(R.dimen.row_min_height)
    val chipHeight = dimensionResource(R.dimen.gallery_chip_height)
    val gapSmall = dimensionResource(R.dimen.space_2)

    // 可收起的只有「筛选 chip 行 + 它上下各 8dp 的呼吸」这一段：标题行（返回 + 标题）常驻 56dp。
    // chip 行上下对称留白：贴着顶栏底边（含 20dp 圆角）会让 chip 与玻璃边缘粘在一起，
    // 看起来像"没有间隔"，所以下留白与上留白同宽。
    // 折叠量交给 HeaderCollapseState 统一驱动（与四个主页面的页眉同一套手感）：
    // 手指拖动逐像素跟随、松手过半对齐、惯性不参与、回顶自动展开。
    // 区别只是这里范围是常量，直接回填给它，不必像页眉那样等布局实测。
    val chipBlock = chipHeight + gapSmall * 2
    val collapseRangePx = with(LocalDensity.current) { chipBlock.toPx() }
    val collapse = rememberHeaderCollapseState()
    SideEffect { collapse.updateRange(collapseRangePx) }
    // 展开程度 1 → 0，供顶栏做高度裁切与渐隐
    val expandedFraction = 1f - collapse.fraction

    // 手势 ↔ 顶栏：上滑（内容向上滚、继续往下浏览）先收顶栏，下滑（往回翻）先展开，
    // 顶栏走完自己的行程才把剩余位移交给列表。
    val collapseOnScroll = rememberHeaderCollapseConnection(collapse)

    // 回到顶部后不能再有收起态：列表 padding 是展开态预留的，收起时第一行上方会露出空档
    val atTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }
    collapse.ExpandWhenAtTop(atTop)

    // 每张卡片的窗口坐标（仅用于转场起点，点击时读一次）
    val cardBounds = remember { mutableStateMapOf<Int, Rect>() }
    // 浮层不占布局，列表要给顶栏让位。padding 跟着折叠量一起收：
    // 顶栏少一段，列表就从少一段的位置开始，两者始终贴合，不会在顶栏下方露出壁纸断带。
    val topInset = statusTop + rowHeight + chipBlock + gapSmall
    // 收起量（正值 dp）：offsetPx 是负的，取反即"顶栏少了多少高度"
    val collapsedDp = with(LocalDensity.current) { (-collapse.offsetPx).toDp() }
    val contentTopInset = topInset - collapsedDp
    val bottomInset = if (state.selectionMode) {
        dimensionResource(R.dimen.space_9) * 2
    } else {
        dimensionResource(R.dimen.space_9)
    }

    VenusBackdropHost(
        modifier = modifier,
        overlay = {
            GalleryTopBar(
                state = state,
                expandedFraction = expandedFraction,
                onBack = onBack,
                onForumSelected = onForumSelected,
                onCategorySelected = onCategorySelected,
                onTabSelected = onTabSelected,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            GallerySelectionBar(
                state = state,
                onDownloadClick = onDownloadClick,
                onCancelSelection = onCancelSelection,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) {
        when {
            state.items.isEmpty() && state.loading -> SkeletonGrid(topInset = topInset)
            state.items.isEmpty() -> EmptyGallery(
                topInset = topInset,
                onRetry = onRetry,
            )
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                // 底部让开导航栏：EdgeToEdge 下否则最后一行会被手势条压住
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    // 顶栏折叠由本列表的滚动驱动（见上方 collapseOnScroll）
                    .nestedScroll(collapseOnScroll),
                contentPadding = PaddingValues(
                    start = dimensionResource(R.dimen.space_2),
                    end = dimensionResource(R.dimen.space_2),
                    top = contentTopInset,
                    bottom = bottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
            ) {
                itemsIndexed(state.items) { index, post ->
                    SelectableImageCard(
                        post = post,
                        selected = index in state.selected,
                        onPositioned = { rect -> cardBounds[index] = rect },
                        onClick = {
                            // 先记下被点卡片的窗口坐标，再交给 Java 打开全屏
                            cardBounds[index]?.let { rect ->
                                state.clickBounds = intArrayOf(
                                    rect.left.roundToInt(),
                                    rect.top.roundToInt(),
                                    rect.width.roundToInt(),
                                    rect.height.roundToInt(),
                                )
                            }
                            onItemClick(index)
                        },
                        onLongClick = { onItemLongClick(index) },
                    )
                }
            }
        }
    }
}

/**
 * 玻璃顶栏：标题行（返回 + 标题）+ 筛选 chip 行。
 *
 * 玻璃表面铺到状态栏下沿（`statusBarsPadding` 作用在内容而非容器），
 * 这样状态栏区域也是同一块玻璃，不会在状态栏下方出现一条壁纸硬边。
 *
 * 收起由 [expandedFraction] 连续驱动（1 = 全展开，0 = 只剩标题行）：
 * 高度按比例裁切、chip 行同比例渐隐，贴底对齐使 chip 行看起来向上滑出顶栏；
 * 不做 `AnimatedVisibility`，因为它只能表达「有 / 无」，跟不上手指。
 */
@Composable
private fun GalleryTopBar(
    state: ImageUiState,
    expandedFraction: Float,
    onBack: () -> Unit,
    onForumSelected: (Int) -> Unit,
    onCategorySelected: (Int) -> Unit,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    // 收起段高度 = chip 行 + 上下各 8dp（与 ImageScreen 的 chipBlock 同一口径，改一处必改另一处）
    val filterHeight = (dimensionResource(R.dimen.gallery_chip_height) +
        dimensionResource(R.dimen.space_2) * 2) * expandedFraction
    // 顶栏固定页面标题：选中数的唯一展示位是底部操作条（GallerySelectionBar）。
    // 两处都放会让同一句计数上下各出现一次，且顶栏是标题语义、不适合承载计数。
    val title = stringResource(R.string.toolbar_image_browse)
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        bottomStart = dimensionResource(R.dimen.gallery_bar_corner),
                        bottomEnd = dimensionResource(R.dimen.gallery_bar_corner),
                    ),
                ),
        ) {
            LensGlass(modifier = Modifier.matchParentSize(), spec = GlassSpecs.Standard)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensionResource(R.dimen.row_min_height))
                        .padding(horizontal = dimensionResource(R.dimen.space_2)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(
                        iconRes = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.content_description_back),
                        onClick = onBack,
                    )
                    Text(
                        text = title,
                        style = textStyles.toolbarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = dimensionResource(R.dimen.space_1)),
                    )
                }
                // 收起段：只减高度，不压缩内容（贴底 + 裁切），文字不会被挤扁
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(filterHeight)
                        .clipToBounds()
                        .alpha(expandedFraction),
                ) {
                    GalleryFilterRow(
                        state = state,
                        onForumSelected = onForumSelected,
                        onCategorySelected = onCategorySelected,
                        onTabSelected = onTabSelected,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** 一条可横滑的 chip 行：两个下拉（游戏 / 分类）+ 榜单分段。 */
@Composable
private fun GalleryFilterRow(
    state: ImageUiState,
    onForumSelected: (Int) -> Unit,
    onCategorySelected: (Int) -> Unit,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        // requiredHeight：父容器（顶栏折叠段）会把高度裁到 0~48dp，普通 height 会被压缩，
        // required 让 chip 行保持原高，由外部 clipToBounds 裁切，chip 不会被挤扁。
        // 高度含上下各 8dp 留白（chip 垂直居中），使 chip 不贴顶栏底边/圆角
        modifier = modifier
            .fillMaxWidth()
            .requiredHeight(
                dimensionResource(R.dimen.gallery_chip_height) +
                    dimensionResource(R.dimen.space_2) * 2,
            ),
        contentPadding = PaddingValues(horizontal = dimensionResource(R.dimen.space_4)),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            DropdownChip(
                text = state.forums.getOrNull(state.forumIndex)
                    ?: stringResource(R.string.select_image_source),
                options = state.forums,
                onSelect = onForumSelected,
            )
        }
        item {
            DropdownChip(
                text = state.categories.getOrNull(state.categoryIndex)
                    ?: stringResource(R.string.image_type),
                options = state.categories,
                onSelect = onCategorySelected,
            )
        }
        state.tabs.forEachIndexed { index, label ->
            item {
                GalleryChip(
                    text = label,
                    selected = index == state.tabIndex,
                    onClick = { onTabSelected(index) },
                )
            }
        }
    }
}

/** 下拉 chip：文字 + 向下箭头，点开 M3 下拉菜单。 */
@Composable
private fun DropdownChip(
    text: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                .clickable(enabled = options.isNotEmpty()) { expanded = true }
                .heightIn(min = dimensionResource(R.dimen.gallery_chip_height))
                .padding(
                    start = dimensionResource(R.dimen.space_3),
                    end = dimensionResource(R.dimen.space_2),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = LocalVenusTextStyles.current.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevrons_up_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = dimensionResource(R.dimen.space_1))
                    .size(dimensionResource(R.dimen.icon_inline)),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(text = option, style = LocalVenusTextStyles.current.body) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

/** 榜单 chip：选中时实心主色容器（玻璃轨道上不再叠第二层透镜）。 */
@Composable
private fun GalleryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .heightIn(min = dimensionResource(R.dimen.gallery_chip_height))
            .padding(horizontal = dimensionResource(R.dimen.space_3)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LocalVenusTextStyles.current.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 多选操作条：选中数 / 下载进度 + 下载 + 取消，浮在列表底部。 */
@Composable
private fun GallerySelectionBar(
    state: ImageUiState,
    onDownloadClick: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.selectionMode,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        val textStyles = LocalVenusTextStyles.current
        val downloading = state.downloadTotal > 0
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = dimensionResource(R.dimen.space_4),
                    vertical = dimensionResource(R.dimen.space_3),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_lg))),
            ) {
                LensGlass(modifier = Modifier.matchParentSize(), spec = GlassSpecs.BottomBar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = dimensionResource(R.dimen.space_4),
                            vertical = dimensionResource(R.dimen.space_3),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (downloading) {
                                stringResource(
                                    R.string.gallery_downloading,
                                    state.downloadDone,
                                    state.downloadTotal,
                                )
                            } else {
                                // 与顶栏标题共用同一份计数文案（原 gallery_selected_count 已合并至此）
                                pluralStringResource(
                                    R.plurals.toolbar_items_selected,
                                    state.selected.size,
                                    state.selected.size,
                                )
                            },
                            style = textStyles.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (downloading) {
                            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
                            DownloadProgressBar(
                                fraction = if (state.downloadTotal == 0) 0f else {
                                    state.downloadDone.toFloat() / state.downloadTotal
                                },
                            )
                        }
                    }
                    // 下载
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = if (downloading) 0.5f else 1f),
                            )
                            .clickable(enabled = !downloading) { onDownloadClick() }
                            .heightIn(min = dimensionResource(R.dimen.gallery_chip_height))
                            .padding(horizontal = dimensionResource(R.dimen.space_4)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                        )
                        Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
                        Text(
                            text = stringResource(R.string.download_image),
                            style = textStyles.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = textStyles.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onCancelSelection() }
                            .padding(
                                horizontal = dimensionResource(R.dimen.space_3),
                                vertical = dimensionResource(R.dimen.space_2),
                            ),
                    )
                }
            }
        }
    }
}

/** 下载进度条：3dp 圆角轨道 + 主色进度（M3 LinearProgressIndicator 的高度不可控）。 */
@Composable
private fun DownloadProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.progress_track_thickness))
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** 卡片 + 点击/长按：多选与全屏的分派留给 Java（业务判断不重复实现一份）。 */
@Composable
private fun SelectableImageCard(
    post: ImagePost,
    selected: Boolean,
    onPositioned: (Rect) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates -> onPositioned(coordinates.boundsInWindow()) }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        ImageCard(
            imageUrl = post.coverUrl,
            title = post.title,
            author = post.author,
            description = post.description,
            time = post.time,
            selected = selected,
        )
    }
}

/** 首屏骨架：6 张呼吸占位卡，替代原来「空白 + Snackbar」。 */
@Composable
private fun SkeletonGrid(topInset: Dp) {
    val transition = rememberInfiniteTransition(label = "gallery-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(
                start = dimensionResource(R.dimen.space_2),
                end = dimensionResource(R.dimen.space_2),
                top = topInset,
            ),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
            ) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(dimensionResource(R.dimen.gallery_skeleton_height))
                            .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                            .background(color),
                    )
                }
            }
        }
    }
}

/** 空态：玻璃卡 + 文案 + 重试。 */
@Composable
private fun EmptyGallery(topInset: Dp, onRetry: () -> Unit) {
    val textStyles = LocalVenusTextStyles.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(dimensionResource(R.dimen.space_6)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_badge_lg)),
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
            Text(
                text = stringResource(R.string.gallery_empty),
                style = textStyles.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.gallery_empty_hint),
                style = textStyles.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_4)))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onRetry)
                    .heightIn(min = dimensionResource(R.dimen.touch_target_min))
                    .padding(horizontal = dimensionResource(R.dimen.space_6)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                Text(
                    text = stringResource(R.string.action_retry),
                    style = textStyles.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 图库页的 Compose 宿主。
 *
 * 与其它整屏迁移一致：Java 只写状态（`setForums` / `setCategories` / `setTabs` / `setItems` /
 * `setSelection` / `setLoading` / `setDownloadProgress`），点击与选择回调由 Java 注入，
 * 业务判断（缓存、下载、网络）全部留在 Activity。
 */
class ImageScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = ImageUiState()

    private var onBackListener: Runnable? = null
    private var onForumSelectedListener: IndexConsumer? = null
    private var onCategorySelectedListener: IndexConsumer? = null
    private var onTabSelectedListener: IndexConsumer? = null
    private var onItemClickListener: IndexConsumer? = null
    private var onItemLongClickListener: IndexConsumer? = null
    private var onDownloadClickListener: Runnable? = null
    private var onCancelSelectionListener: Runnable? = null
    private var onRetryClickListener: Runnable? = null

    @Composable
    override fun Content() {
        VenusTheme {
            VenusPageRoot {
                ImageScreen(
                    state = uiState,
                    onBack = { onBackListener?.run() },
                    onForumSelected = {
                        uiState.forumIndex = it
                        onForumSelectedListener?.accept(it)
                    },
                    onCategorySelected = {
                        uiState.categoryIndex = it
                        onCategorySelectedListener?.accept(it)
                    },
                    onTabSelected = {
                        uiState.tabIndex = it
                        onTabSelectedListener?.accept(it)
                    },
                    onItemClick = { onItemClickListener?.accept(it) },
                    onItemLongClick = { onItemLongClickListener?.accept(it) },
                    onDownloadClick = { onDownloadClickListener?.run() },
                    onCancelSelection = { onCancelSelectionListener?.run() },
                    onRetry = { onRetryClickListener?.run() },
                )
            }
        }
    }

    /** 游戏下拉项与当前选中下标。 */
    fun setForums(forums: List<String>, selectedIndex: Int) {
        uiState.forums = ArrayList(forums)
        uiState.forumIndex = selectedIndex
    }

    /** 分类下拉项与当前选中下标（-1 = 未选，显示 hint）。 */
    fun setCategories(categories: List<String>, selectedIndex: Int) {
        uiState.categories = ArrayList(categories)
        uiState.categoryIndex = selectedIndex
    }

    /** 榜单 Tab 文案与当前选中下标。 */
    fun setTabs(tabs: List<String>, selectedIndex: Int) {
        uiState.tabs = ArrayList(tabs)
        uiState.tabIndex = selectedIndex
    }

    /** 图片列表。 */
    fun setItems(items: List<ImagePost>) {
        uiState.items = ArrayList(items)
    }

    /** 最近一次点击卡片的窗口矩形（left, top, width, height）；无则 null。 */
    fun getLastClickBounds(): IntArray? = uiState.clickBounds

    /** 多选态与选中下标集合（Java 侧 `selectedItems` 为业务真源，这里只做投影）。 */
    fun setSelection(selectionMode: Boolean, selected: Collection<Int>) {
        uiState.selectionMode = selectionMode
        uiState.selected = selected.toSet()
    }

    /** 加载态：true 时首屏显示骨架卡（列表已有数据时不清屏）。 */
    fun setLoading(loading: Boolean) {
        uiState.loading = loading
    }

    /** 多选下载进度；total = 0 表示未在下载。 */
    fun setDownloadProgress(done: Int, total: Int) {
        uiState.downloadDone = done
        uiState.downloadTotal = total
    }

    fun setOnBackClickListener(listener: Runnable?) {
        onBackListener = listener
    }

    fun setOnForumSelectedListener(listener: IndexConsumer?) {
        onForumSelectedListener = listener
    }

    fun setOnCategorySelectedListener(listener: IndexConsumer?) {
        onCategorySelectedListener = listener
    }

    fun setOnTabSelectedListener(listener: IndexConsumer?) {
        onTabSelectedListener = listener
    }

    fun setOnItemClickListener(listener: IndexConsumer?) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: IndexConsumer?) {
        onItemLongClickListener = listener
    }

    fun setOnDownloadClickListener(listener: Runnable?) {
        onDownloadClickListener = listener
    }

    fun setOnCancelSelectionListener(listener: Runnable?) {
        onCancelSelectionListener = listener
    }

    fun setOnRetryClickListener(listener: Runnable?) {
        onRetryClickListener = listener
    }
}
