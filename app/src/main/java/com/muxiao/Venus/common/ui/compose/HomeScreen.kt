package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools
import androidx.compose.ui.platform.LocalResources

/* 运行形态：取值与 HomeViewModel.RunMode 对齐 */

const val HOME_RUN_IDLE = 0
const val HOME_RUN_FOREGROUND = 1
const val HOME_RUN_BACKGROUND = 2

/* 任务状态：语义对齐 TaskItem.TaskStatus，数值不复用其 ordinal */

const val TASK_PENDING = 0
const val TASK_IN_PROGRESS = 1
const val TASK_COMPLETED = 2
const val TASK_ERROR = 3
const val TASK_WARNING = 4
const val TASK_CANCELLED = 5

/** 仅展示态：当日已完成但本轮未执行到（绿色沿用上次结果）。 */
const val TASK_RETAINED = 6

/** 挂起等待用户完成人机验证：不是失败，凭证就绪后由续跑轮自动重跑。 */
const val TASK_WAITING_CAPTCHA = 7

/** Java 友好的「传任务名」回调（minSdk 23 下不用 java.util.function）。 */
fun interface TaskNameConsumer {
    fun accept(taskName: String)
}

/**
 * 一条任务行。
 *
 * [id] 是稳定任务 id（与语言无关，见 `TaskIds`）：作为列表 key 与「未配置项点击」的载荷，
 * 不用名称——名称随语言变化，当 key 会让切语言后整列重建、点击回跳也随之失配。
 * [disabled] = 「开关开着但凭证没配」：删除线 + 灰色，运行入口跳过；与 [status] 正交，配置补上后重新渲染即恢复。
 */
@Immutable
class HomeTaskRow(
    val id: String,
    val name: String,
    val status: Int,
    val disabled: Boolean = false,
)

/**
 * 主页的全部可变状态。可见性不在 Java 侧逐个 set，而由 [runMode] / [bgFeatureEnabled] / [tasks] 派生
 * （对应原 `HomeViewModel.TaskUiState` 的五个 `xxxVisible()`）——保证「同一时刻只有一套可见性真相」。
 */
@Stable
class HomeUiState {
    var users by mutableStateOf<List<String>>(emptyList())
    var selectedUserIndex by mutableIntStateOf(-1)
    /** 账号行展示文案（用户名 + 服务器后缀）；无用户时为空。 */
    var userLabel by mutableStateOf("")
    var avatarUsername by mutableStateOf<String?>(null)
    /** 头像刷新令牌：文件被替换后自增。路径不变（按用户名定址），没有它 Compose 不重组、Glide 还会命中旧缓存。 */
    var avatarVersion by mutableIntStateOf(0)
    var dateChip by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var doneLabel by mutableStateOf("")
    /** 主操作行右侧的剩余条数提示（`run_remaining` 复数文案）。 */
    var runHint by mutableStateOf("")
    var retryCount by mutableStateOf("")
    var bgButtonText by mutableStateOf("")
    /** 「全部账号（N）」入口文案与可见性。 */
    var runAllText by mutableStateOf("")
    var runAllVisible by mutableStateOf(false)
    /** 多账号聚合条文案与可见性。 */
    var aggregateText by mutableStateOf("")
    var aggregateVisible by mutableStateOf(false)
    var tasks by mutableStateOf<List<HomeTaskRow>>(emptyList())
    var runMode by mutableIntStateOf(HOME_RUN_IDLE)
    var bgFeatureEnabled by mutableStateOf(false)
    var geetestVisible by mutableStateOf(false)
    var bottomClearancePx by mutableIntStateOf(0)
}

/* 可见性派生：一一对应 TaskUiState 的 userDropdownVisible / cancelButtonVisible 等规则 */

private val HomeUiState.userVisible get() = runMode != HOME_RUN_FOREGROUND
private val HomeUiState.cancelVisible get() = runMode == HOME_RUN_FOREGROUND
private val HomeUiState.startVisible get() = runMode == HOME_RUN_IDLE
private val HomeUiState.bgVisible get() = bgFeatureEnabled && runMode != HOME_RUN_FOREGROUND
private val HomeUiState.retryVisible
    get() = runMode == HOME_RUN_IDLE && tasks.any { it.status == TASK_ERROR }

/**
 * 主页（命令台风格）：页眉固定 + 正文 `LazyColumn`；操作区三态用 `AnimatedVisibility`（expand/shrinkVertically，
 * 避免 ChangeBounds 塌缩时的残影与跳位）；日期 chip、重试行、后台行统一用 [LensGlass]。
 * 极验按钮容器是 SDK 命令式 `addView` 的真实 View，经 [AndroidView] 桥接，显隐由 [HomeUiState.geetestVisible] 驱动。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    geetestContainer: LinearLayout,
    onUserSelected: (Int) -> Unit,
    onJumpToUsers: () -> Unit,
    onStartDaily: () -> Unit,
    onRunAll: () -> Unit,
    onCancelDaily: () -> Unit,
    onRetryFailed: () -> Unit,
    onBackgroundClick: () -> Unit,
    onViewLog: () -> Unit,
    onDisabledTaskClick: (String) -> Unit,
    onCaptchaClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val clearance = with(density) { state.bottomClearancePx.toDp() }
    val gutter = dimensionResource(R.dimen.gutter_screen)
    val reducedMotion = rememberReducedMotion()
    // 进入/退出动效：减少动态效果时整体关闭（等价原 render 里的 isReducedMotionEnabled 判定）
    val enter: EnterTransition
    val exit: ExitTransition
    if (reducedMotion) {
        enter = EnterTransition.None
        exit = ExitTransition.None
    } else {
        val medium = LocalResources.current.getInteger(R.integer.motion_duration_medium)
        val short = LocalResources.current.getInteger(R.integer.motion_duration_short)
        val decelerate = rememberVenusEasing(R.anim.emphasized_decelerate)
        val accelerate = rememberVenusEasing(R.anim.emphasized_accelerate)
        enter = fadeIn(tween(medium, easing = decelerate)) +
            expandVertically(tween(medium, easing = decelerate))
        exit = fadeOut(tween(short, easing = accelerate)) +
            shrinkVertically(tween(short, easing = accelerate))
    }

    // 页眉随滚动收缩 / 展开：状态与滚动链归页面所有，列表只是被它推上去
    val headerCollapse = rememberHeaderCollapseState()
    val headerScroll = rememberHeaderCollapseConnection(headerCollapse)
    val listState = rememberLazyListState()
    val listAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    headerCollapse.ExpandWhenAtTop(listAtTop)

    Column(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize()
            .nestedScroll(headerScroll),
    ) {
        CommandHeader(
            title = stringResource(R.string.daily_tasks_title),
            chipText = state.dateChip,
            subtitle = state.subtitle,
            collapse = headerCollapse,
        )

        // 极验容器出现时确保它「立刻完整可见」：它是 LazyColumn 的 item（操作区之下、按钮正下方），
        // 懒加载只组合视口内的项，列表滚在别处时该项根本不存在，用户就看不到验证按钮。
        // 这里只用 scrollToItem（瞬时跳转，无动画）——旧版是布局里已 inflate 好的 View，
        // setVisibility 下一帧就出现；用 animateScrollToItem 会多出 300ms 动画，手感上就是「没立刻出现」。
        // geetestIndex 前面的固定项：账号区 3 项（前台运行期间隐藏）+ 操作区 6 项
        // （标签 / 开始 / 全部账号 / 取消 / 重试 / 后台）。在此之上加 item 时必须同步这里。
        val geetestIndex = (if (state.userVisible) 3 else 0) + 6
        LaunchedEffect(state.geetestVisible) {
            if (!state.geetestVisible) return@LaunchedEffect
            // 等一帧让 item 组合并参与布局，再判断是否真的完整可见
            withFrameNanos { }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == "geetest" }
            // 存在但只露出一角也算不可见：用视口边界判「完整可见」，而不是「存在」
            val fullyVisible = item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
            if (!fullyVisible) listState.scrollToItem((geetestIndex - 1).coerceAtLeast(0))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                top = dimensionResource(R.dimen.space_2),
                bottom = dimensionResource(R.dimen.space_4) + clearance,
            ),
        ) {
            // 账号上下文：前台运行期间标签 / 行 / 分割线整体隐藏
            if (state.userVisible) {
                item(key = "account-label") {
                    SectionLabel(stringResource(R.string.section_account))
                }
                item(key = "account-row") {
                    UserRow(
                        state = state,
                        onUserSelected = onUserSelected,
                        onJumpToUsers = onJumpToUsers,
                    )
                }
                item(key = "account-divider") { VenusDivider() }
            }

            item(key = "actions-label") {
                SectionLabel(
                    text = stringResource(R.string.section_actions),
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4)),
                )
            }
            item(key = "start-daily") {
                AnimatedVisibility(visible = state.startVisible, enter = enter, exit = exit) {
                    PrimaryRunRow(hint = state.runHint, onClick = onStartDaily)
                }
            }
            // 「全部账号」批量入口
            item(key = "run-all") {
                AnimatedVisibility(
                    visible = state.startVisible && state.runAllVisible,
                    enter = enter,
                    exit = exit,
                ) {
                    SurfaceRow(
                        icon = VenusIcons.Play,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = state.runAllText,
                        trailing = "",
                        trailingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onRunAll,
                    )
                }
            }
            item(key = "cancel-daily") {
                AnimatedVisibility(visible = state.cancelVisible, enter = enter, exit = exit) {
                    CancelRunButton(onClick = onCancelDaily)
                }
            }
            item(key = "retry-failed") {
                AnimatedVisibility(visible = state.retryVisible, enter = enter, exit = exit) {
                    SurfaceRow(
                        icon = VenusIcons.TaskError,
                        iconTint = colorResource(R.color.status_warning),
                        text = stringResource(R.string.retry_failed_tasks),
                        trailing = state.retryCount,
                        trailingColor = colorResource(R.color.status_warning),
                        onClick = onRetryFailed,
                    )
                }
            }
            item(key = "bg-run") {
                AnimatedVisibility(visible = state.bgVisible, enter = enter, exit = exit) {
                    SurfaceRow(
                        icon = VenusIcons.NotificationBell,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = state.bgButtonText,
                        trailing = stringResource(R.string.bg_hint_notify),
                        trailingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onBackgroundClick,
                    )
                }
            }
            // 极验按钮容器：放在操作区之下（「运行签到」等按钮的正下方），与原布局位置一致。
            //
            // 它是 SDK 命令式管理的真实 View（addView/removeView），经 AndroidView 桥接。
            // 这里必须显式 onRelease 把容器从宿主里摘下：容器是 HomeScreenView 持有的同一实例，
            // LazyColumn 划出视口回收本项后若仍挂在已废弃的宿主上，下次重新挂载会撞
            // 「The specified child already has a parent」。
            if (state.geetestVisible) {
                item(key = "geetest") {
                    AndroidView(
                        factory = { geetestContainer },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = gutter,
                                vertical = dimensionResource(R.dimen.space_4),
                            ),
                        onRelease = { view ->
                            (view.parent as? ViewGroup)?.removeView(view)
                        },
                    )
                }
            }
            item(key = "actions-divider") {
                Box(modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4))) {
                    VenusDivider()
                }
            }

            // 多账号聚合条
            if (state.aggregateVisible) {
                item(key = "aggregate") {
                    Text(
                        text = state.aggregateText,
                        style = textStyles.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_2)),
                    )
                }
            }

            item(key = "task-header") {
                TaskListHeader(
                    doneLabel = state.doneLabel,
                    onViewLog = onViewLog,
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_2)),
                )
            }
            if (state.tasks.isEmpty()) {
                item(key = "task-empty") { TaskListEmpty() }
            }
            itemsIndexed(
                items = state.tasks,
                key = { _, task -> task.id },
            ) { index, task ->
                // 逐项 diff 动画负责增删与让位；状态变更不走 animateItem，改由 [TaskRow] 对状态图标做 scale-in
                TaskRow(
                    task = task,
                    showDivider = index > 0,
                    // 两种可点项，都把「看到问题」接到「去解决」：
                    // 未配置 → 对应凭证抽屉；待验证 → 重新拉起人机验证（否则验证码入口只有一条通知，
                    // 通知被划掉后用户就再没有地方能唤起验证，只能干等次日）
                    onClick = {
                        if (task.status == TASK_WAITING_CAPTCHA) onCaptchaClick()
                        else onDisabledTaskClick(task.id)
                    },
                    modifier = if (reducedMotion) Modifier else Modifier.animateItem(),
                )
            }

            item(key = "footer") {
                Text(
                    text = stringResource(R.string.home_footer_note),
                    style = textStyles.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_5)),
                )
            }
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

/** 账号行：头像 + 用户名（带服务器后缀）+ 上下箭头，点击展开下拉；无用户时整行变引导（「尚未添加用户」+ 跳用户管理页）。 */
@Composable
private fun UserRow(
    state: HomeUiState,
    onUserSelected: (Int) -> Unit,
    onJumpToUsers: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    val hasUsers = state.users.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    val label = if (hasUsers) state.userLabel else stringResource(R.string.home_no_user_hint)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (hasUsers) expanded = true else onJumpToUsers() }
                .padding(vertical = dimensionResource(R.dimen.gap_row)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(dimensionResource(R.dimen.icon_badge_md))
                    .clip(CircleShape),
            ) {
                VenusAvatar(
                    username = state.avatarUsername,
                    size = dimensionResource(R.dimen.icon_badge_md),
                    refreshToken = state.avatarVersion,
                )
            }
            Text(
                text = label,
                style = textStyles.bodyLarge,
                // 空态提示必须显眼：主题默认 hint 色透明度低，压背景图上看不清，故用全强度 onSurface
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = dimensionResource(R.dimen.space_3)),
            )
            Icon(
                imageVector = VenusIcons.ChevronsUpDown,
                contentDescription = stringResource(R.string.select_user),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 下拉展开项 = 头像 + 原始用户名（item_user_dropdown 不带服务器后缀，后缀只在行内展示）
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.users.forEachIndexed { index, username ->
                DropdownMenuItem(
                    text = { Text(text = username, style = textStyles.bodyLarge) },
                    leadingIcon = {
                        VenusAvatar(username = username, refreshToken = state.avatarVersion)
                    },
                    onClick = {
                        expanded = false
                        onUserSelected(index)
                    },
                )
            }
        }
    }
}

/** 主操作行：主色填充（8dp 圆角，非玻璃）+ 播放图标 + 「开始签到」+ 右侧剩余条数 chip；玻璃只用在次操作行上。 */
@Composable
private fun PrimaryRunRow(hint: String, onClick: () -> Unit) {
    val textStyles = LocalVenusTextStyles.current
    val corner = dimensionResource(R.dimen.shape_corner_xs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = dimensionResource(R.dimen.space_2))
            .heightIn(min = dimensionResource(R.dimen.touch_target_min))
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.space_4)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = VenusIcons.Play,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
        )
        Text(
            text = stringResource(R.string.run_sign_in),
            style = textStyles.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(start = dimensionResource(R.dimen.space_2)),
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_xxs)))
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.20f))
                .padding(
                    horizontal = dimensionResource(R.dimen.space_2),
                    vertical = dimensionResource(R.dimen.space_hairline),
                ),
        ) {
            Text(
                text = hint,
                style = textStyles.monoValue,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 取消按钮：`Widget.Venus.Button.Outlined.Destructive` 的 Compose 复刻。 */
@Composable
private fun CancelRunButton(onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = dimensionResource(R.dimen.space_2))
            .height(dimensionResource(R.dimen.touch_target_min)),
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_xs)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(
            dimensionResource(R.dimen.stroke_width_hairline),
            error,
        ),
    ) {
        Text(text = stringResource(R.string.cancel_task), style = LocalVenusTextStyles.current.labelLarge)
    }
}

/** 次操作行（重试失败 / 后台运行）：液态玻璃 8dp 圆角 + 行内图标 + 标题 + 右侧 mono 提示。 */
@Composable
private fun SurfaceRow(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    trailing: String,
    trailingColor: Color,
    onClick: () -> Unit,
) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val cornerPx = with(density) { dimensionResource(R.dimen.shape_corner_xs).toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = dimensionResource(R.dimen.space_2)),
    ) {
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
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
            )
            Text(
                text = text,
                style = textStyles.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = dimensionResource(R.dimen.space_3)),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = trailing, style = textStyles.monoValue, color = trailingColor)
        }
    }
}

/** 列表头：区块标签 + DONE 计数 + 「查看日志」文本按钮。 */
@Composable
private fun TaskListHeader(doneLabel: String, onViewLog: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimensionResource(R.dimen.list_header_min_height)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.task_list),
            style = LocalVenusTextStyles.current.sectionLabel,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = doneLabel,
            style = LocalVenusTextStyles.current.monoValue,
            modifier = Modifier.padding(end = dimensionResource(R.dimen.space_2)),
        )
        TextButton(
            onClick = onViewLog,
            contentPadding = PaddingValues(horizontal = dimensionResource(R.dimen.space_2)),
        ) {
            Text(
                text = stringResource(R.string.view_log),
                style = LocalVenusTextStyles.current.monoValue,
            )
        }
    }
}

/** 空态：轻量居中提示（原 `task_list_empty_view`，透明卡 + 圆底图标 + 文案）。 */
@Composable
private fun TaskListEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(dimensionResource(R.dimen.space_6)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(dimensionResource(R.dimen.icon_badge_circle))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(dimensionResource(R.dimen.space_2)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = VenusIcons.Pending,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.task_list_empty),
            style = LocalVenusTextStyles.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_3)),
        )
    }
}

/**
 * 单条任务行：发丝分割线 + 状态圈（玻璃底 + 语义描边）+ 状态图形 + 任务名 + 右侧状态。
 * 状态到视觉的派生集中在本函数开头的 `when (task.status)` 里，不要散回调用点：IN_PROGRESS/WARNING 主色环点「运行中」、
 * RETAINED 中性环 + 绿 check「沿用」、COMPLETED 语义绿环 + check「已签」、ERROR 红环 + ×「失败」、CANCELLED 灰点划叉、PENDING 灰点「待执行」。
 */
@Composable
private fun TaskRow(
    task: HomeTaskRow,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val success = colorResource(R.color.status_success)
    val error = MaterialTheme.colorScheme.error
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val stroke: Color
    val icon: ImageVector
    val iconTint: Color
    val strike: Boolean
    val progressText: String?
    val progressColor: Color
    val chipText: String?
    val chipColor: Color?
    val chipTextColor: Color?

    val disabled = task.disabled
    if (disabled) {
        // 功能已开启但凭证未配置：统一走中性灰 + 右侧说明性文案，不占用任何「结果」语义色
        stroke = outlineVariant; icon = VenusIcons.Dot; iconTint = neutral; strike = false
        progressText = stringResource(R.string.task_status_not_configured); progressColor = neutral
        chipText = null; chipColor = null; chipTextColor = null
    } else when (task.status) {
        TASK_IN_PROGRESS, TASK_WARNING -> {
            stroke = primary; icon = VenusIcons.Dot; iconTint = primary; strike = false
            progressText = stringResource(R.string.task_status_running); progressColor = primary
            chipText = null; chipColor = null; chipTextColor = null
        }
        TASK_RETAINED -> {
            stroke = outlineVariant; icon = VenusIcons.Check; iconTint = success; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_retained)
            chipColor = colorResource(R.color.status_success_container)
            chipTextColor = colorResource(R.color.status_on_success_container)
        }
        TASK_COMPLETED -> {
            stroke = success; icon = VenusIcons.Check; iconTint = success; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_done)
            chipColor = colorResource(R.color.status_success_container)
            chipTextColor = colorResource(R.color.status_on_success_container)
        }
        TASK_ERROR -> {
            stroke = error; icon = VenusIcons.TaskError; iconTint = error; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_failed)
            chipColor = MaterialTheme.colorScheme.errorContainer
            chipTextColor = MaterialTheme.colorScheme.onErrorContainer
        }
        TASK_CANCELLED -> {
            stroke = outlineVariant; icon = VenusIcons.Dot; iconTint = neutral; strike = true
            progressText = stringResource(R.string.task_status_cancelled); progressColor = neutral
            chipText = null; chipColor = null; chipTextColor = null
        }
        TASK_WAITING_CAPTCHA -> {
            // 挂起等人：用警告色而非错误色——它不是失败，是「需要你做一步」。
            // 同色系下与「进行中」靠文案区分，因此文案必须写明「待验证」而不是「运行中」。
            val warning = colorResource(R.color.status_warning)
            stroke = warning; icon = VenusIcons.Dot; iconTint = warning; strike = false
            progressText = stringResource(R.string.task_status_waiting_captcha); progressColor = warning
            chipText = null; chipColor = null; chipTextColor = null
        }
        else -> {
            stroke = outlineVariant; icon = VenusIcons.Dot; iconTint = neutral; strike = false
            progressText = stringResource(R.string.task_status_pending); progressColor = neutral
            chipText = null; chipColor = null; chipTextColor = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 项间发丝分割线：首条隐藏（避免顶端生硬分割）
        if (showDivider) {
            VenusDivider(modifier = Modifier.padding(start = dimensionResource(R.dimen.divider_indent_icon)))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.row_min_height))
                .clickable(
                    enabled = task.disabled || task.status == TASK_WAITING_CAPTCHA,
                    onClick = onClick,
                )
                .padding(vertical = dimensionResource(R.dimen.gutter_card)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态圈：玻璃恒为玻璃，状态由语义色描边 + 图标着色承载（不整块换填充色）
            Box(
                modifier = Modifier.size(dimensionResource(R.dimen.status_circle_size)),
                contentAlignment = Alignment.Center,
            ) {
                LensGlass(
                    modifier = Modifier.matchParentSize(),
                    circle = true,
                    semanticStrokeColor = stroke.toArgb(),
                )
                StatusGlyph(icon = icon, tint = iconTint, strike = strike, status = task.status)
            }
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.space_4)))
            Text(
                text = task.name,
                style = textStyles.rowTitle,
                // 仅在不可执行时改色/划线；其余状态保持 style 自带颜色（Color.Unspecified）
                color = if (disabled) neutral else Color.Unspecified,
                textDecoration = if (disabled) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chipText != null && chipColor != null && chipTextColor != null) {
                Text(
                    text = chipText,
                    style = textStyles.monoValue,
                    color = chipTextColor,
                    modifier = Modifier
                        .padding(start = dimensionResource(R.dimen.space_2))
                        .clip(CircleShape)
                        .background(chipColor)
                        .padding(
                            horizontal = dimensionResource(R.dimen.space_2),
                            vertical = dimensionResource(R.dimen.space_hairline),
                        ),
                )
            } else if (progressText != null) {
                Text(
                    text = progressText,
                    style = textStyles.monoValue,
                    color = progressColor,
                    modifier = Modifier.padding(start = dimensionResource(R.dimen.space_2)),
                )
            }
        }
    }
}

/**
 * 状态图形（点 / check / ×，被取消时叠一道错误色划叉）。状态跃迁时做一次轻微 scale-in；
 * 滚动复用不会重放——LazyColumn 的 key 是任务名，状态不变就不触发。
 */
@Composable
private fun StatusGlyph(
    icon: ImageVector,
    tint: Color,
    strike: Boolean,
    status: Int,
) {
    val reducedMotion = rememberReducedMotion()
    val scale = remember { Animatable(1f) }
    var lastStatus by remember { mutableIntStateOf(status) }
    val duration = LocalResources.current.getInteger(R.integer.motion_duration_medium)
    val easing = rememberVenusEasing(R.anim.emphasized_decelerate)

    LaunchedEffect(status) {
        if (status == lastStatus) return@LaunchedEffect
        lastStatus = status
        if (reducedMotion) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        scale.snapTo(0.6f)
        scale.animateTo(1f, tween(duration, easing = easing))
    }

    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.status_icon_size))
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.task_status),
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        if (strike) {
            Icon(
                imageVector = VenusIcons.StrikeCross,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxSize(),
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
 * 主页宿主。极验按钮容器在此急切创建并对外暴露：SDK 的 `GT3GeetestButton` 需要真实 ViewGroup 承载
 * addView/removeView，Compose 表达不了「往某个位置塞第三方 View」，故保留一个同配置的 `LinearLayout`，
 * 仅在其可见时经 `AndroidView` 挂进组合。
 */
class HomeScreenView(context: Context) : AbstractComposeView(context) {

    internal val uiState = HomeUiState()

    /** 极验按钮容器（横向、居中、默认隐藏）。 */
    val geetestContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = GONE
    }

    private var onUserSelectedListener: IndexConsumer? = null
    private var onJumpToUsersListener: Runnable? = null
    private var onStartDailyListener: Runnable? = null
    private var onRunAllListener: Runnable? = null
    private var onCancelDailyListener: Runnable? = null
    private var onRetryFailedListener: Runnable? = null
    private var onBackgroundClickListener: Runnable? = null
    private var onViewLogListener: Runnable? = null
    private var onDisabledTaskClickListener: TaskNameConsumer? = null
    private var onCaptchaClickListener: Runnable? = null

    @Composable
    override fun Content() {
        VenusTheme {
            HomeScreen(
                state = uiState,
                geetestContainer = geetestContainer,
                onUserSelected = { onUserSelectedListener?.accept(it) },
                onJumpToUsers = { onJumpToUsersListener?.run() },
                onStartDaily = { onStartDailyListener?.run() },
                onRunAll = { onRunAllListener?.run() },
                onCancelDaily = { onCancelDailyListener?.run() },
                onRetryFailed = { onRetryFailedListener?.run() },
                onBackgroundClick = { onBackgroundClickListener?.run() },
                onViewLog = { onViewLogListener?.run() },
                onDisabledTaskClick = { onDisabledTaskClickListener?.accept(it) },
                onCaptchaClick = { onCaptchaClickListener?.run() },
            )
        }
    }

    /** 点击「未配置」任务项：跳到设置页对应凭证抽屉。 */
    fun setOnDisabledTaskClickListener(listener: TaskNameConsumer?) {
        onDisabledTaskClickListener = listener
    }

    /** 点击「待验证」任务项：重新拉起人机验证（通知之外的第二入口）。 */
    fun setOnCaptchaClickListener(listener: Runnable?) {
        onCaptchaClickListener = listener
    }

    /** 填充账号下拉：原始用户名列表 + 当前选中下标（-1 = 无选中）。 */
    fun setUsers(users: List<String>, selectedIndex: Int) {
        uiState.users = ArrayList(users)
        uiState.selectedUserIndex = selectedIndex
    }

    /** 账号行展示文案（用户名 + 服务器后缀）。 */
    fun setUserLabel(label: String) {
        uiState.userLabel = label
    }

    /** 账号行头像对应的用户名（null/空 = 无用户空态，显示默认人像）。 */
    fun setAvatarUsername(username: String?) {
        uiState.avatarUsername = username
    }

    /** 强制重载头像：文件按存储 id 定址、换头像时原地覆盖，只调 [setAvatarUsername] 不重组且 Glide 会命中旧缓存。 */
    fun refreshAvatar() {
        uiState.avatarVersion++
    }

    /** 页眉右上角 chip 文案（`MM/dd E`）。 */
    fun setDateChip(text: String) {
        uiState.dateChip = text
    }

    /** 页眉副标题（带「已完成 x/y」进度）。 */
    fun setSubtitle(text: String) {
        uiState.subtitle = text
    }

    /** 任务列表头的 DONE 计数。 */
    fun setDoneLabel(text: String) {
        uiState.doneLabel = text
    }

    /** 主操作行右侧剩余条数提示。 */
    fun setRunHint(text: String) {
        uiState.runHint = text
    }

    /** 重试行的失败计数。 */
    fun setRetryCount(text: String) {
        uiState.retryCount = text
    }

    /** 后台行标题（运行中为「取消任务」）。 */
    fun setBgButtonText(text: String) {
        uiState.bgButtonText = text
    }

    /** 重建任务行列表（顺序即显示顺序，name 作 LazyColumn 的稳定 key）。 */
    fun setTasks(tasks: List<HomeTaskRow>) {
        uiState.tasks = ArrayList(tasks)
    }

    /** 设运行形态（[HOME_RUN_IDLE] / [HOME_RUN_FOREGROUND] / [HOME_RUN_BACKGROUND]）。 */
    fun setRunMode(mode: Int) {
        uiState.runMode = mode
    }

    /** 设「后台任务」设置项是否开启（决定后台行是否出现）。 */
    fun setBgFeatureEnabled(enabled: Boolean) {
        uiState.bgFeatureEnabled = enabled
    }

    /** 极验按钮容器显隐（原 `geetestContainer.setVisibility(...)`）。 */
    fun setGeetestVisible(visible: Boolean) {
        uiState.geetestVisible = visible
        geetestContainer.visibility = if (visible) VISIBLE else GONE
    }

    /** 底栏覆盖高度（px）：列表底部留白，等价于原来的 BottomBarOverlay.attachClearance。 */
    fun setBottomClearance(px: Int) {
        uiState.bottomClearancePx = px
    }

    fun setOnUserSelectedListener(listener: IndexConsumer?) {
        onUserSelectedListener = listener
    }

    fun setOnJumpToUsersListener(listener: Runnable?) {
        onJumpToUsersListener = listener
    }

    fun setOnStartDailyListener(listener: Runnable?) {
        onStartDailyListener = listener
    }

    /** 「全部账号」批量入口。 */
    fun setOnRunAllListener(listener: Runnable?) {
        onRunAllListener = listener
    }

    /** 「全部账号（N）」文案与可见性。 */
    fun setRunAllText(text: String) {
        uiState.runAllText = text
    }

    fun setRunAllVisible(visible: Boolean) {
        uiState.runAllVisible = visible
    }

    /** 多账号聚合条文案。 */
    fun setAggregateText(text: String) {
        uiState.aggregateText = text
    }

    fun setAggregateVisible(visible: Boolean) {
        uiState.aggregateVisible = visible
    }

    fun setOnCancelDailyListener(listener: Runnable?) {
        onCancelDailyListener = listener
    }

    fun setOnRetryFailedListener(listener: Runnable?) {
        onRetryFailedListener = listener
    }

    fun setOnBackgroundClickListener(listener: Runnable?) {
        onBackgroundClickListener = listener
    }

    fun setOnViewLogListener(listener: Runnable?) {
        onViewLogListener = listener
    }
}

@Preview(name = "主页 · 空闲", showBackground = true)
@Composable
private fun HomeScreenPreview() {
    val context = LocalContext.current
    val geetest = remember(context) {
        LinearLayout(context).apply { visibility = android.view.View.GONE }
    }
    VenusTheme {
        HomeScreen(
            state = HomeUiState().apply {
                dateChip = "11/05 周三"
                subtitle = "今日签到 · 已完成 2/5"
                users = listOf("muxiao", "venus")
                selectedUserIndex = 0
                userLabel = "muxiao [国服]"
                runHint = "剩余 3 条"
                doneLabel = "2/5"
                retryCount = "1"
                bgButtonText = "后台运行"
                bgFeatureEnabled = true
                tasks = listOf(
                    HomeTaskRow("bbs_daily", "米游社签到", TASK_COMPLETED),
                    HomeTaskRow("game_signin:原神", "原神", TASK_RETAINED),
                    HomeTaskRow("game_signin:星铁", "崩坏：星穹铁道", TASK_IN_PROGRESS),
                    HomeTaskRow("game_signin:绝区零", "绝区零", TASK_ERROR),
                    HomeTaskRow("game_signin:崩坏3", "崩坏3", TASK_PENDING),
                    HomeTaskRow("cloud_game:云原神", "云原神云游戏签到", TASK_PENDING, disabled = true),
                )
            },
            geetestContainer = geetest,
            onUserSelected = {},
            onJumpToUsers = {},
            onStartDaily = {},
            onRunAll = {},
            onCancelDaily = {},
            onRetryFailed = {},
            onBackgroundClick = {},
            onViewLog = {},
            onDisabledTaskClick = {},
            onCaptchaClick = {},
        )
    }
}
