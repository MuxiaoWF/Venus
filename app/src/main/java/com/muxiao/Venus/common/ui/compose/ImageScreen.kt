package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.muxiao.Venus.R

/**
 * 图库列表项的数据快照。
 *
 * 对应原 `parseImageData` 产出的 Map；迁移后 Java 侧仍在后台线程解析 JSON，
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
 * 图库页的可变状态：游戏 / 分类下拉、榜单 Tab、图片列表、多选态。
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
}

/**
 * 图库页（原 `activity_image.xml` + `ImageAdapter`）。
 *
 * 结构对齐原 CoordinatorLayout：
 * - `AppBarLayout` → 顶部 `Surface`（surfaceContainer 底 + level3 阴影）：工具栏 / 两个筛选下拉 / 榜单 Tab
 * - `RecyclerView(GridLayoutManager(2))` → [LazyVerticalGrid]，卡片复用 [ImageCard]
 *
 * 原 ActionBar 的标题与下载菜单改为在 Compose 内派生：
 * 标题在普通态是「图片浏览」、多选态是「N 项已选择」，下载图标仅在多选态出现。
 *
 * @param state 页面状态
 * @param onBack 返回（多选态时由 Java 先退出多选）
 * @param onForumSelected 游戏下拉选中（下标对应 Java 侧 forumNames）
 * @param onCategorySelected 分类下拉选中
 * @param onTabSelected 榜单 Tab 选中
 * @param onItemClick 点卡片：多选态切换选中，否则进全屏
 * @param onItemLongClick 长按卡片：进入多选并选中
 * @param onDownloadClick 点击下载图标
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
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val title = if (state.selectionMode) {
        pluralStringResource(
            R.plurals.toolbar_items_selected,
            state.selected.size,
            state.selected.size,
        )
    } else {
        stringResource(R.string.toolbar_image_browse)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 顶部应用栏（原 AppBarLayout：surfaceContainer 底 + level3 阴影） =====
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = dimensionResource(R.dimen.elevation_level3),
        ) {
            Column(modifier = Modifier.statusBarsPadding()) {
                // 工具栏（原 MaterialToolbar + 由本页提升的 ActionBar：返回箭头 / 标题 / 下载项）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensionResource(R.dimen.row_min_height))
                        .padding(horizontal = dimensionResource(R.dimen.space_2)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.content_description_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
                    // 原 menu 里的 action_download：仅长按进入多选后可见
                    if (state.selectionMode) {
                        IconButton(onClick = onDownloadClick) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = stringResource(R.string.download_image),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // 筛选区：游戏 / 分类两个下拉（原两块 TextInputLayout.ExposedDropdownMenu，等宽各占 3/6）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimensionResource(R.dimen.space_4)),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                ) {
                    FilterDropdown(
                        label = stringResource(R.string.select_image_source),
                        value = state.forums.getOrNull(state.forumIndex).orEmpty(),
                        options = state.forums,
                        onSelect = onForumSelected,
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = dimensionResource(R.dimen.space_2)),
                    )
                    FilterDropdown(
                        label = stringResource(R.string.type),
                        value = state.categories.getOrNull(state.categoryIndex)
                            ?: stringResource(R.string.image_type),
                        options = state.categories,
                        onSelect = onCategorySelected,
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = dimensionResource(R.dimen.space_2)),
                    )
                }

                // 榜单 Tab（原 TabLayout + bg_tab_pill：选中项 primary_alpha_10 圆角填充 + 主色文字）
                if (state.tabs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dimensionResource(R.dimen.space_2)),
                        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
                    ) {
                        state.tabs.forEachIndexed { index, label ->
                            val selected = index == state.tabIndex
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_md)))
                                    .background(
                                        if (selected) colorResource(R.color.primary_alpha_10) else Color.Transparent,
                                    )
                                    .clickable { onTabSelected(index) }
                                    .padding(vertical = dimensionResource(R.dimen.space_3)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    style = textStyles.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ===== 图片网格（原 RecyclerView + GridLayoutManager(2)：左右 space_3、上 space_3、下 space_9） =====
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = dimensionResource(R.dimen.space_3),
                end = dimensionResource(R.dimen.space_3),
                top = dimensionResource(R.dimen.space_3),
                bottom = dimensionResource(R.dimen.space_9),
            ),
        ) {
            itemsIndexed(state.items) { index, post ->
                SelectableImageCard(
                    post = post,
                    selected = index in state.selected,
                    onClick = { onItemClick(index) },
                    onLongClick = { onItemLongClick(index) },
                )
            }
        }
    }
}

/** 卡片 + 点击/长按：多选与全屏的分派留给 Java（业务判断不重复实现一份）。 */
@Composable
private fun SelectableImageCard(
    post: ImagePost,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier.combinedClickable(
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

/**
 * 筛选下拉：等价于 `Widget.Venus.Dropdown`（OutlinedBox ExposedDropdownMenu，圆角 shape_corner_sm）。
 *
 * 不用 `ExposedDropdownMenuBox` + `OutlinedTextField`：后者的浮动标签态、内边距与
 * 原 TextInputLayout 差异较大，这里直接画「发丝描边容器 + 上标签下值」，
 * 高度与观感更贴近原控件，也避免引入实验性菜单锚点 API。
 */
@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
            color = Color.Transparent,
            border = BorderStroke(
                width = dimensionResource(R.dimen.stroke_width_hairline),
                color = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = dimensionResource(R.dimen.space_3),
                    vertical = dimensionResource(R.dimen.space_2),
                ),
            ) {
                Text(
                    text = label,
                    style = textStyles.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = textStyles.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(text = option, style = textStyles.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

/**
 * 图库页的 Compose 宿主。
 *
 * 与其它整屏迁移一致：Java 只写状态（`setForums` / `setCategories` / `setTabs` / `setItems` /
 * `setSelection`），点击与选择回调由 Java 注入，业务判断（缓存、下载、网络）全部留在 Activity。
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

    /** 多选态与选中下标集合（Java 侧 `selectedItems` 为业务真源，这里只做投影）。 */
    fun setSelection(selectionMode: Boolean, selected: Collection<Int>) {
        uiState.selectionMode = selectionMode
        uiState.selected = selected.toSet()
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
}
