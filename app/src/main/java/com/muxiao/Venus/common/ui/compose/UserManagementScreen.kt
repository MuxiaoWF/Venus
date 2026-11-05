package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools

/**
 * 一张用户卡的数据（原 `item_user_management.xml` 的绑定结果）。
 *
 * `displayName` 由 Java 侧用 `R.string.user_label_with_server` 拼好——
 * 格式化留在有 Context 的一侧，Compose 只负责排版，避免把 i18n 逻辑搬进 UI。
 */
@Immutable
class UserCardRow(
    val username: String,
    val displayName: String,
    /** 当前默认账号：名称后显示「默认」chip。 */
    val isDefault: Boolean,
)

/**
 * 用户管理页的全部可变状态。
 *
 * 与既有 pane 的「宿主是受控组件」约定一致：Java 只写状态，视觉全部由状态派生，
 * 不再逐控件 `setText` / `setVisibility`。
 */
@Stable
class UserManagementUiState {
    var users by mutableStateOf<List<UserCardRow>>(emptyList())
    var dateChip by mutableStateOf("")
    var subtitle by mutableStateOf("")
    /** 空态文案；非空即显示空态并隐藏区块标签。 */
    var emptyText by mutableStateOf<String?>(null)
    var bottomClearancePx by mutableIntStateOf(0)
}

/** Java 友好的「传用户名」回调（minSdk 23 下不用 java.util.function）。 */
fun interface UsernameConsumer {
    fun accept(username: String)
}

/**
 * 用户管理页（命令台风格）的 Compose 实现，替代 `fragment_user_management.xml`
 * 与 `item_user_management.xml` 两个布局。
 *
 * 与原实现的对应关系：
 * - 原 `NestedScrollView + LinearLayout + 逐条 inflate` → `LazyColumn`（列表天然可复用）
 * - 原 `LayoutTransition`（仅结构性增删时播放）→ `animateItem()`
 *   （逐项 diff 动画，无变化的重组不会闪动，等价且更省）
 * - 原 `LensGlassDrawable.apply(itemView, shape_corner_md)` → 卡片下的 [LensGlass]
 *   （回退底为同源的 `bg_glass_card`）
 * - 原 `BottomBarOverlay.attachClearance/attachMargin` → 由宿主把覆盖高度喂成状态，
 *   列表底部留白与 FAB 底距同时跟随
 */
@Composable
fun UserManagementScreen(
    state: UserManagementUiState,
    onAddUserClick: () -> Unit,
    onAvatarClick: (String) -> Unit,
    onRenameClick: (String) -> Unit,
    onReloginClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)
    val reducedMotion = rememberReducedMotion()

    Box(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandHeader(
                title = stringResource(R.string.user_management_title),
                chipText = state.dateChip,
                subtitle = state.subtitle,
            )

            // 正文：等价于 Widget.Venus.ScreenScaffold（左右 gutter、上 space_2、下 space_4）
            // + BottomBarOverlay 的底部避让 + 原容器自身的 space_bottom_fab（给 FAB 让位）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_4) + clearance +
                        dimensionResource(R.dimen.space_bottom_fab),
                ),
            ) {
                item(key = "section") {
                    val emptyText = state.emptyText
                    if (emptyText != null) {
                        EmptyUserPrompt(emptyText)
                    } else {
                        SectionLabel(stringResource(R.string.section_accounts))
                    }
                }
                itemsIndexed(
                    items = state.users,
                    key = { _, user -> user.username },
                ) { index, user ->
                    UserCard(
                        user = user,
                        onAvatarClick = onAvatarClick,
                        onRenameClick = onRenameClick,
                        onReloginClick = onReloginClick,
                        onDeleteClick = onDeleteClick,
                        // 结构性增删/重排的让位动画：与 View 侧 LayoutTransition 同一用途。
                        // 「减少动态效果」下不挂，避免列表整体位移。
                        modifier = Modifier
                            .padding(top = if (index == 0) 0.dp else dimensionResource(R.dimen.gap_card))
                            .then(if (reducedMotion) Modifier else Modifier.animateItem()),
                    )
                }
            }
        }

        // 添加/登录用户：扩展 FAB，锚定右下角并抬到底栏之上
        ExtendedFloatingActionButton(
            onClick = onAddUserClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = dimensionResource(R.dimen.space_4),
                    bottom = dimensionResource(R.dimen.space_4) + clearance,
                ),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_user_plus),
                    contentDescription = null,
                )
            },
            text = { Text(text = stringResource(R.string.add_or_login_user)) },
        )
    }
}

/** 区块标签（`Widget.Venus.SectionLabel`）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.sectionLabel,
        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_1)),
    )
}

/**
 * 空态：图标 + 文案居中。
 *
 * 原 XML 用 `android:drawableTop` + `drawablePadding` 挂在 TextView 上；
 * Compose 没有等价属性，改用 Column 纵排——观感一致且便于给图标单独着色。
 */
@Composable
private fun EmptyUserPrompt(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimensionResource(R.dimen.space_7)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_user),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(dimensionResource(R.dimen.space_6)),
        )
        Text(
            text = text,
            style = LocalVenusTextStyles.current.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_3)),
        )
    }
}

/**
 * 单张用户卡：信息行（头像 + 名称/默认 chip + 登录状态 + chevron）+ 附着操作栏（重新登录 | 删除）。
 *
 * 信息行整行点击 = 重命名（保持原 `rename_user_dialog` 入口）；头像单独可点唤起头像操作，
 * 两个点击区互不干扰（Compose 内层 `clickable` 优先消费）。
 */
@Composable
private fun UserCard(
    user: UserCardRow,
    onAvatarClick: (String) -> Unit,
    onRenameClick: (String) -> Unit,
    onReloginClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val cornerPx = with(density) { dimensionResource(R.dimen.shape_corner_md).toPx() }
    val cornerDp = dimensionResource(R.dimen.shape_corner_md)
    val cornerShape = remember(cornerDp) { RoundedCornerShape(cornerDp) }

    Box(modifier = modifier.fillMaxWidth()) {
        // 用户卡液态玻璃透镜：接管卡面，自带圆角轮廓裁剪子内容与光边
        LensGlass(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
        )
        Column(modifier = Modifier.clip(cornerShape)) {
            // 信息行（点击 = 重命名）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRenameClick(user.username) }
                    .padding(
                        horizontal = dimensionResource(R.dimen.space_4),
                        vertical = dimensionResource(R.dimen.gap_row),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(dimensionResource(R.dimen.icon_avatar_md))
                        .clip(CircleShape)
                        .clickable { onAvatarClick(user.username) },
                ) {
                    VenusAvatar(
                        username = user.username,
                        size = dimensionResource(R.dimen.icon_avatar_md),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = dimensionResource(R.dimen.space_3)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName,
                            style = textStyles.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (user.isDefault) DefaultChip()
                    }
                    LoggedInStatus()
                }

                // 原 XML 的 android:rotation="270"：chevron 向下 → 指向右
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(270f),
                )
            }

            // 附着操作栏：与容器同边界，对半切分
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.touch_target_min)),
            ) {
                CardAction(
                    text = stringResource(R.string.re_login),
                    iconRes = R.drawable.ic_refresh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onReloginClick(user.username) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(
                    modifier = Modifier
                        .width(dimensionResource(R.dimen.divider_height))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                CardAction(
                    text = stringResource(R.string.delete_user),
                    iconRes = R.drawable.ic_trash,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { onDeleteClick(user.username) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 「已登录」状态行：成功绿 check + 同色 mono 文案。 */
@Composable
private fun LoggedInStatus() {
    val success = colorResource(R.color.status_success)
    Row(
        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_hairline)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = success,
            modifier = Modifier.size(dimensionResource(R.dimen.status_icon_size)),
        )
        Text(
            text = stringResource(R.string.user_logged_in_status),
            style = LocalVenusTextStyles.current.monoValue,
            color = success,
            modifier = Modifier.padding(start = dimensionResource(R.dimen.space_1)),
        )
    }
}

/** 「默认」chip：主色 10% 底 + 主色 mono 文案。等价于 `bg_chip` + `backgroundTint=primary_alpha_10`。 */
@Composable
private fun DefaultChip() {
    Text(
        text = stringResource(R.string.user_chip_default),
        style = LocalVenusTextStyles.current.monoValue,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = dimensionResource(R.dimen.space_2))
            .clip(CircleShape)
            .background(colorResource(R.color.primary_alpha_10))
            .padding(
                horizontal = dimensionResource(R.dimen.space_2),
                vertical = dimensionResource(R.dimen.space_hairline),
            ),
    )
}

/**
 * 操作栏按钮：图标 + 文案，居中。
 *
 * 原实现是 `Widget.Venus.Button.Text`（MaterialButton）带 `app:icon`；
 * Compose 侧用 Row + `clickable` 复刻，涟漪由 `clickable` 的 indication 提供，
 * 且天然被卡片的圆角裁剪（与原 `MaterialCardView` 的子内容裁剪一致）。
 */
@Composable
private fun CardAction(
    text: String,
    iconRes: Int,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
        )
        Text(
            text = text,
            style = LocalVenusTextStyles.current.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(start = dimensionResource(R.dimen.space_2)),
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
 * 用户管理页的 Compose 宿主。
 *
 * 用法（`UserManagementFragment.onCreateView`）：`new UserManagementScreenView(ctx)` 直接作为
 * 根视图返回，之后所有 UI 更新都走 `setUsers` / `setSubtitle` / `setEmptyText` 等方法。
 */
class UserManagementScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = UserManagementUiState()

    private var onAddUserClickListener: Runnable? = null
    private var onAvatarClickListener: UsernameConsumer? = null
    private var onRenameClickListener: UsernameConsumer? = null
    private var onReloginClickListener: UsernameConsumer? = null
    private var onDeleteClickListener: UsernameConsumer? = null

    @Composable
    override fun Content() {
        VenusTheme {
            UserManagementScreen(
                state = uiState,
                onAddUserClick = { onAddUserClickListener?.run() },
                onAvatarClick = { onAvatarClickListener?.accept(it) },
                onRenameClick = { onRenameClickListener?.accept(it) },
                onReloginClick = { onReloginClickListener?.accept(it) },
                onDeleteClick = { onDeleteClickListener?.accept(it) },
            )
        }
    }

    /** 重建用户卡列表（顺序即显示顺序）。 */
    fun setUsers(users: List<UserCardRow>) {
        uiState.users = ArrayList(users)
    }

    /** 页眉右上角 chip 文案（`MM/dd E`）。 */
    fun setDateChip(text: String) {
        uiState.dateChip = text
    }

    /** 副标题（带账号计数）。 */
    fun setSubtitle(text: String) {
        uiState.subtitle = text
    }

    /** 空态文案；传 null 收起空态、显示区块标签。 */
    fun setEmptyText(text: String?) {
        uiState.emptyText = text
    }

    /** 底栏覆盖高度（px）：列表底部留白与 FAB 底距同时跟随。 */
    fun setBottomClearance(px: Int) {
        uiState.bottomClearancePx = px
    }

    fun setOnAddUserClickListener(listener: Runnable?) {
        onAddUserClickListener = listener
    }

    fun setOnAvatarClickListener(listener: UsernameConsumer?) {
        onAvatarClickListener = listener
    }

    fun setOnRenameClickListener(listener: UsernameConsumer?) {
        onRenameClickListener = listener
    }

    fun setOnReloginClickListener(listener: UsernameConsumer?) {
        onReloginClickListener = listener
    }

    fun setOnDeleteClickListener(listener: UsernameConsumer?) {
        onDeleteClickListener = listener
    }
}

@Preview(name = "用户管理 · 有账号", showBackground = true)
@Composable
private fun UserManagementScreenPreview() {
    VenusTheme {
        UserManagementScreen(
            state = UserManagementUiState().apply {
                dateChip = "11/05 周三"
                subtitle = "管理登录账号 · 共 2 个账号"
                users = listOf(
                    UserCardRow("muxiao", "muxiao · 国服", true),
                    UserCardRow("venus", "venus · 国际服", false),
                )
            },
            onAddUserClick = {},
            onAvatarClick = {},
            onRenameClick = {},
            onReloginClick = {},
            onDeleteClick = {},
        )
    }
}

@Preview(name = "用户管理 · 空态", showBackground = true)
@Composable
private fun UserManagementScreenEmptyPreview() {
    VenusTheme {
        UserManagementScreen(
            state = UserManagementUiState().apply {
                dateChip = "11/05 周三"
                subtitle = "管理登录账号 · 共 0 个账号"
                emptyText = "当前服务器还没有登录账号，点右下角添加"
            },
            onAddUserClick = {},
            onAvatarClick = {},
            onRenameClick = {},
            onReloginClick = {},
            onDeleteClick = {},
        )
    }
}
