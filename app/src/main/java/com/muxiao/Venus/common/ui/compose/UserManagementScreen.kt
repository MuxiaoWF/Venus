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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/** 一张用户卡的数据。`displayName` 由 Java 侧拼好（`R.string.user_label_with_server`）：i18n 留在有 Context 的一侧，UI 只排版。 */
@Immutable
class UserCardRow(
    val username: String,
    val displayName: String,
    /** 当前默认账号：名称后显示「默认」chip。 */
    val isDefault: Boolean,
    /** 凭证入口（云游戏 + 森空岛合并为一枚）；null = 两个功能都没开启，卡片上不出现。合并是为了不让两枚状态块与「头像 + 名称 + 已登录」抢层级。 */
    val credential: CredentialChipRow? = null,
    /** 任务摘要（如「签到 12 项 · 今日 8/12」）；null = 不显示。点击直达设置页并锁定编辑目标。 */
    val taskSummary: String? = null,
)

/**
 * 用户卡上的凭证入口数据。
 * @param label 由 Java 侧拼好：只开一个功能时是该功能状态（「云游戏 1/2」），两个都开时是汇总（「凭证 3/3」）
 * @param ready 只决定配色，不代表能不能点
 */
@Immutable
class CredentialChipRow(
    val label: String,
    val ready: Boolean,
)

/** 用户管理页的全部可变状态。与 pane 同一约定：宿主是受控组件——Java 只写状态，视觉全部由状态派生，不逐控件 setText。 */
@Stable
class UserManagementUiState {
    var users by mutableStateOf<List<UserCardRow>>(emptyList())
    var dateChip by mutableStateOf("")
    var subtitle by mutableStateOf("")
    /** 空态文案；非空即显示空态并隐藏区块标签。 */
    var emptyText by mutableStateOf<String?>(null)
    var bottomClearancePx by mutableIntStateOf(0)
    /** 头像刷新令牌：换头像后自增。头像文件按用户名定址、原地覆盖，没有它列表里的旧图不会更新。 */
    var avatarVersion by mutableIntStateOf(0)
}

/** Java 友好的「传用户名」回调（minSdk 23 下不用 java.util.function）。 */
fun interface UsernameConsumer {
    fun accept(username: String)
}

/**
 * 用户管理页（命令台风格）。列表用 `LazyColumn` + `animateItem()` 做增删重排动画（无变化的重组不闪动），
 * 卡片底为 [LensGlass]；底栏覆盖高度由宿主喂成状态，列表底部留白与 FAB 底距同时跟随。
 */
@Composable
fun UserManagementScreen(
    state: UserManagementUiState,
    onAddUserClick: () -> Unit,
    onAvatarClick: (String) -> Unit,
    onRenameClick: (String) -> Unit,
    onReloginClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onCredentialClick: (String) -> Unit,
    onTaskSummaryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)
    val reducedMotion = rememberReducedMotion()

    // 页眉随滚动收缩 / 展开（与首页同一套手感）
    val headerCollapse = rememberHeaderCollapseState()
    val headerScroll = rememberHeaderCollapseConnection(headerCollapse)
    val listState = rememberLazyListState()
    val listAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    headerCollapse.ExpandWhenAtTop(listAtTop)

    Box(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize()
            .nestedScroll(headerScroll),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommandHeader(
                title = stringResource(R.string.user_management_title),
                chipText = state.dateChip,
                subtitle = state.subtitle,
                collapse = headerCollapse,
            )

            // 正文：左右 gutter、上 space_2、下 space_4 + 底栏避让 + space_bottom_fab（给 FAB 让位）
            LazyColumn(
                state = listState,
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
                        avatarVersion = state.avatarVersion,
                        onAvatarClick = onAvatarClick,
                        onRenameClick = onRenameClick,
                        onReloginClick = onReloginClick,
                        onDeleteClick = onDeleteClick,
                        onCredentialClick = onCredentialClick,
                        onTaskSummaryClick = onTaskSummaryClick,
                        // 结构性增删/重排的让位动画；「减少动态效果」下不挂，避免列表整体位移
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
                    imageVector = VenusIcons.UserPlus,
                    contentDescription = null,
                )
            },
            text = { Text(text = stringResource(R.string.add_or_login_user)) },
        )
    }
}

/** 空态：图标 + 文案居中。Compose 无 `drawableTop` 等价属性，改用 Column 纵排，也便于给图标单独着色。 */
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
            imageVector = VenusIcons.User,
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
 * 信息行整行点击 = 重命名，头像单独可点唤起头像操作；两个点击区互不干扰（内层 `clickable` 优先消费）。
 */
@Composable
private fun UserCard(
    user: UserCardRow,
    avatarVersion: Int,
    onAvatarClick: (String) -> Unit,
    onRenameClick: (String) -> Unit,
    onReloginClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onCredentialClick: (String) -> Unit,
    onTaskSummaryClick: (String) -> Unit,
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
                        refreshToken = avatarVersion,
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
                    CredentialEntry(
                        user = user,
                        onClick = { onCredentialClick(user.username) },
                    )
                    // 任务摘要：点击直达设置页并锁定编辑目标为该账号（一步替代「去设置 → 选账号」）
                    user.taskSummary?.let { summary ->
                        Text(
                            text = summary,
                            style = LocalVenusTextStyles.current.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_xs)))
                                .clickable { onTaskSummaryClick(user.username) }
                                .padding(vertical = dimensionResource(R.dimen.space_1)),
                        )
                    }
                }

                // chevron 默认向下，旋转 270° 指向右
                Icon(
                    imageVector = VenusIcons.ChevronDown,
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
                    icon = VenusIcons.Refresh,
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
                    icon = VenusIcons.Trash,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { onDeleteClick(user.username) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 卡片上的凭证入口：最多一枚。 */
@Composable
private fun CredentialEntry(
    user: UserCardRow,
    onClick: () -> Unit,
) {
    val credential = user.credential ?: return
    Row(modifier = Modifier.padding(top = dimensionResource(R.dimen.space_2))) {
        CredentialChip(
            label = credential.label,
            ready = credential.ready,
            onClick = onClick,
        )
    }
}

/** 单个凭证状态 chip：配色只表达凭证齐不齐（齐 = 成功容器色，否则中性），功能是否开启交给文案区分，避免两种含义共用一种视觉。 */
@Composable
private fun CredentialChip(label: String, ready: Boolean, onClick: () -> Unit) {
    val textColor = if (ready) colorResource(R.color.status_on_success_container)
    else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (ready) colorResource(R.color.status_success_container)
    else MaterialTheme.colorScheme.surfaceVariant
    Text(
        text = label,
        style = LocalVenusTextStyles.current.monoValue,
        color = textColor,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(
                horizontal = dimensionResource(R.dimen.space_2),
                vertical = dimensionResource(R.dimen.space_hairline),
            ),
    )
}

/** 「已登录」状态行：成功绿 check + 同色 mono 文案。 */
@Composable
private fun LoggedInStatus() {    val success = colorResource(R.color.status_success)
    Row(
        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_hairline)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = VenusIcons.Check,
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

/** 「默认」chip：主色 10% 底 + 主色 mono 文案。等价于 `bg_chip` + 主色 10% 透明度底。 */
@Composable
private fun DefaultChip() {
    Text(
        text = stringResource(R.string.user_chip_default),
        style = LocalVenusTextStyles.current.monoValue,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = dimensionResource(R.dimen.space_2))
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(
                horizontal = dimensionResource(R.dimen.space_2),
                vertical = dimensionResource(R.dimen.space_hairline),
            ),
    )
}

/** 操作栏按钮：图标 + 文案居中。用 Row + `clickable`（涟漪由 indication 提供，且天然被卡片圆角裁剪），不必套 MaterialButton。 */
@Composable
private fun CardAction(
    text: String,
    icon: ImageVector,
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
            imageVector = icon,
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

/** 用户管理页宿主：`UserManagementFragment` 直接 new 来当根视图，之后 UI 更新全走 setUsers / setSubtitle / setEmptyText。 */
class UserManagementScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = UserManagementUiState()

    private var onAddUserClickListener: Runnable? = null
    private var onAvatarClickListener: UsernameConsumer? = null
    private var onRenameClickListener: UsernameConsumer? = null
    private var onReloginClickListener: UsernameConsumer? = null
    private var onDeleteClickListener: UsernameConsumer? = null
    private var onCredentialClickListener: UsernameConsumer? = null
    private var onTaskSummaryClickListener: UsernameConsumer? = null

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
                onCredentialClick = { onCredentialClickListener?.accept(it) },
                onTaskSummaryClick = { onTaskSummaryClickListener?.accept(it) },
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

    /** 强制重载用户卡头像（换头像/重置后文件原地覆盖，仅刷新列表不会更新图片）。 */
    fun refreshAvatar() {
        uiState.avatarVersion++
    }

    /** 底栏覆盖高度（px）：列表底部留白与 FAB 底距同时跟随。 */
    fun setBottomClearance(px: Int) {
        uiState.bottomClearancePx = px
    }

    fun setOnAddUserClickListener(listener: Runnable?) {
        onAddUserClickListener = listener
    }

    /** 点击任务摘要行：直达设置页并锁定该账号为编辑目标。 */
    fun setOnTaskSummaryClickListener(listener: UsernameConsumer?) {
        onTaskSummaryClickListener = listener
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

    /** 点凭证入口：跳设置页配置。只开一个功能直达对应抽屉，两个都开由 Java 侧先弹选择——业务判断不进 UI 层。 */
    fun setOnCredentialClickListener(listener: UsernameConsumer?) {
        onCredentialClickListener = listener
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
                    UserCardRow("muxiao", "muxiao · 国服", true,
                        credential = CredentialChipRow("凭证 3/3", true)),
                    UserCardRow("venus", "venus · 国际服", false,
                        credential = CredentialChipRow("凭证 待配 2", false)),
                )
            },
            onAddUserClick = {},
            onAvatarClick = {},
            onRenameClick = {},
            onReloginClick = {},
            onDeleteClick = {},
            onCredentialClick = {},
            onTaskSummaryClick = {},
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
            onCredentialClick = {},
            onTaskSummaryClick = {},
        )
    }
}
