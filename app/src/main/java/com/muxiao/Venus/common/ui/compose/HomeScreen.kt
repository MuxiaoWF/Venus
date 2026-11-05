package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.view.Gravity
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.muxiao.Venus.R
import com.muxiao.Venus.common.tools
import androidx.compose.ui.platform.LocalResources

/* ===== 运行形态（对齐 HomeViewModel.RunMode） ===== */

const val HOME_RUN_IDLE = 0
const val HOME_RUN_FOREGROUND = 1
const val HOME_RUN_BACKGROUND = 2

/* ===== 任务状态（对齐 TaskItem.TaskStatus 的语义，不复用 ordinal） ===== */

const val TASK_PENDING = 0
const val TASK_IN_PROGRESS = 1
const val TASK_COMPLETED = 2
const val TASK_ERROR = 3
const val TASK_WARNING = 4
const val TASK_CANCELLED = 5

/** 仅展示态：当日已完成但本轮未执行到（绿色沿用上次结果）。 */
const val TASK_RETAINED = 6

/** 一条任务行（名称 + 状态码）。 */
@Immutable
class HomeTaskRow(val name: String, val status: Int)

/**
 * 主页的全部可变状态。
 *
 * 可见性不在 Java 侧逐个 set，而是由 [runMode] / [bgFeatureEnabled] / [tasks] 派生
 * （与原 `HomeViewModel.TaskUiState` 的五个 `xxxVisible()` 规则一一对应）——
 * 这样「同一时刻只有一套可见性真相」的约束在 Compose 里同样成立。
 */
@Stable
class HomeUiState {
    var users by mutableStateOf<List<String>>(emptyList())
    var selectedUserIndex by mutableIntStateOf(-1)
    /** 账号行展示文案（用户名 + 服务器后缀）；无用户时为空。 */
    var userLabel by mutableStateOf("")
    var avatarUsername by mutableStateOf<String?>(null)
    var dateChip by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var doneLabel by mutableStateOf("")
    /** 主操作行右侧的剩余条数提示（`run_remaining` 复数文案）。 */
    var runHint by mutableStateOf("")
    var retryCount by mutableStateOf("")
    var bgButtonText by mutableStateOf("")
    var tasks by mutableStateOf<List<HomeTaskRow>>(emptyList())
    var runMode by mutableIntStateOf(HOME_RUN_IDLE)
    var bgFeatureEnabled by mutableStateOf(false)
    var geetestVisible by mutableStateOf(false)
    var bottomClearancePx by mutableIntStateOf(0)
}

/* 可见性派生（对齐 TaskUiState.userDropdownVisible / cancelButtonVisible / ...） */

private val HomeUiState.userVisible get() = runMode != HOME_RUN_FOREGROUND
private val HomeUiState.cancelVisible get() = runMode == HOME_RUN_FOREGROUND
private val HomeUiState.startVisible get() = runMode == HOME_RUN_IDLE
private val HomeUiState.bgVisible get() = bgFeatureEnabled && runMode != HOME_RUN_FOREGROUND
private val HomeUiState.retryVisible
    get() = runMode == HOME_RUN_IDLE && tasks.any { it.status == TASK_ERROR }

/**
 * 主页（命令台风格）的 Compose 实现，替代 `fragment_home.xml` + `item_home_task_list.xml`
 * + `item_user_dropdown.xml` 三个布局。
 *
 * 结构对应关系：
 * - 页眉固定（原 `home_header`）+ 正文滚动（原 `home_scroll`）→ `Column` + `LazyColumn`
 * - 任务列表 `RecyclerView` + `TaskAdapter` → `LazyColumn` 的 `items`（[TaskRow]）
 * - 账号下拉 `MaterialAutoCompleteTextView` + `UserDropdownAdapter` → `DropdownMenu`
 * - 按钮组的 `TransitionManager` Fade + ChangeBounds → `AnimatedVisibility`
 *   （`expand/shrinkVertically` 取代 ChangeBounds 的塌缩，同样避免「残影 + 跳位」两段脱节）
 * - 三处 `LensGlassDrawable.apply`（日期 chip、重试行、后台行）→ [LensGlass]
 * - 极验按钮容器是 SDK 命令式 `addView` 的真实 View，经 [AndroidView] 桥接，
 *   显隐改由 [HomeUiState.geetestVisible] 驱动（不再直接 setVisibility）
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    geetestContainer: LinearLayout,
    onUserSelected: (Int) -> Unit,
    onJumpToUsers: () -> Unit,
    onStartDaily: () -> Unit,
    onCancelDaily: () -> Unit,
    onRetryFailed: () -> Unit,
    onBackgroundClick: () -> Unit,
    onViewLog: () -> Unit,
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

    Column(
        modifier = modifier
            .glassScrollMotion()
            .fillMaxSize(),
    ) {
        CommandHeader(
            title = stringResource(R.string.daily_tasks_title),
            chipText = state.dateChip,
            subtitle = state.subtitle,
        )

        LazyColumn(
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
            /* ===== 账号上下文（前台运行期间整体隐藏：标签 / 行 / 分割线一起走） ===== */
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

            /* ===== 操作区 ===== */
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
            item(key = "cancel-daily") {
                AnimatedVisibility(visible = state.cancelVisible, enter = enter, exit = exit) {
                    CancelRunButton(onClick = onCancelDaily)
                }
            }
            item(key = "retry-failed") {
                AnimatedVisibility(visible = state.retryVisible, enter = enter, exit = exit) {
                    SurfaceRow(
                        iconRes = R.drawable.ic_task_error,
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
                        iconRes = R.drawable.ic_notification,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = state.bgButtonText,
                        trailing = stringResource(R.string.bg_hint_notify),
                        trailingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onBackgroundClick,
                    )
                }
            }
            // 极验按钮容器：SDK 命令式管理的真实 View，仅在其可见时挂到组合里
            if (state.geetestVisible) {
                item(key = "geetest") {
                    AndroidView(
                        factory = { geetestContainer },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = dimensionResource(R.dimen.space_4)),
                    )
                }
            }
            item(key = "actions-divider") {
                Box(modifier = Modifier.padding(top = dimensionResource(R.dimen.space_4))) {
                    VenusDivider()
                }
            }

            /* ===== 任务列表 ===== */
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
                key = { _, task -> task.name },
            ) { index, task ->
                // 逐项 diff 动画：新增项淡入、位置变化平滑让位（取代原 RecyclerView 的 ItemAnimator）。
                // 状态变更不走 animateItem（它只管出现/消失/位移），改由 [TaskRow] 内部
                // 对状态图标做 scale-in，语义与 View 版一致。
                TaskRow(
                    task = task,
                    showDivider = index > 0,
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

/* ===== 区块与分割线 ===== */

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.sectionLabel,
        modifier = modifier.padding(bottom = dimensionResource(R.dimen.space_1)),
    )
}

/* ===== 账号上下文行 ===== */

/**
 * 账号行：头像 + 用户名（带服务器后缀）+ 上下箭头，点击展开账号下拉。
 *
 * 无用户时整行变成引导：文案换成「尚未添加用户」、点击跳转用户管理页
 * （对应原 `updateUserGuideState` 的两套 OnClickListener）。
 */
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
                )
            }
            Text(
                text = label,
                style = textStyles.bodyLarge,
                // 空态提示必须显眼：主题默认 hint 色是低透明度变体，压背景图上看不清，
                // 故用全强度 onSurface（与原实现 setHintTextColor(colorOnSurface) 同口径）
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = dimensionResource(R.dimen.space_3)),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevrons_up_down),
                contentDescription = stringResource(R.string.select_user),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 下拉展开项 = 头像 + 原始用户名（item_user_dropdown 不带服务器后缀，后缀只在行内展示）
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.users.forEachIndexed { index, username ->
                DropdownMenuItem(
                    text = { Text(text = username, style = textStyles.bodyLarge) },
                    leadingIcon = { VenusAvatar(username = username) },
                    onClick = {
                        expanded = false
                        onUserSelected(index)
                    },
                )
            }
        }
    }
}

/* ===== 操作区三态 ===== */

/**
 * 主操作行：主色填充（8dp 圆角）+ 播放图标 + 「开始签到」+ 右侧剩余条数 kbd chip。
 *
 * 对应 `bg_command_button`（纯主色填充，非玻璃）——玻璃只在次操作行上。
 */
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
            painter = painterResource(R.drawable.ic_play),
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

/**
 * 次操作行（重试失败 / 后台运行）：液态玻璃 8dp 圆角 + 行内图标 + 标题 + 右侧 mono 提示。
 *
 * 对应 `bg_surface_button`（原 XML 里 `tag="glass"` 且被 `LensGlassDrawable.apply` 接管）。
 */
@Composable
private fun SurfaceRow(
    iconRes: Int,
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
                painter = painterResource(iconRes),
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

/* ===== 任务列表 ===== */

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
                painter = painterResource(R.drawable.ic_pending),
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
 *
 * 状态到视觉的派生与原 `TaskAdapter.onBindViewHolder` 的 switch 一一对应：
 * - IN_PROGRESS / WARNING（旧数据残留）：主色环 + 主色点，右侧主色 mono「运行中」
 * - RETAINED：中性环 + 绿色 check，右侧「沿用」chip（成功容器色）
 * - COMPLETED：语义绿环 + 绿色 check，右侧「已签」chip
 * - ERROR：错误红环 + 大号 ×，右侧「失败」chip（错误容器色）
 * - CANCELLED：中性环 + 灰点 + 错误色划叉，右侧 mono「已取消」
 * - PENDING：中性环 + 灰点，右侧 mono「待执行」
 */
@Composable
private fun TaskRow(
    task: HomeTaskRow,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val success = colorResource(R.color.status_success)
    val error = MaterialTheme.colorScheme.error
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val stroke: Color
    val iconRes: Int
    val iconTint: Color
    val strike: Boolean
    val progressText: String?
    val progressColor: Color
    val chipText: String?
    val chipColor: Color?
    val chipTextColor: Color?

    when (task.status) {
        TASK_IN_PROGRESS, TASK_WARNING -> {
            stroke = primary; iconRes = R.drawable.ic_dot; iconTint = primary; strike = false
            progressText = stringResource(R.string.task_status_running); progressColor = primary
            chipText = null; chipColor = null; chipTextColor = null
        }
        TASK_RETAINED -> {
            stroke = outlineVariant; iconRes = R.drawable.ic_check; iconTint = success; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_retained)
            chipColor = colorResource(R.color.status_success_container)
            chipTextColor = colorResource(R.color.status_on_success_container)
        }
        TASK_COMPLETED -> {
            stroke = success; iconRes = R.drawable.ic_check; iconTint = success; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_done)
            chipColor = colorResource(R.color.status_success_container)
            chipTextColor = colorResource(R.color.status_on_success_container)
        }
        TASK_ERROR -> {
            stroke = error; iconRes = R.drawable.ic_task_error; iconTint = error; strike = false
            progressText = null; progressColor = neutral
            chipText = stringResource(R.string.task_chip_failed)
            chipColor = MaterialTheme.colorScheme.errorContainer
            chipTextColor = MaterialTheme.colorScheme.onErrorContainer
        }
        TASK_CANCELLED -> {
            stroke = outlineVariant; iconRes = R.drawable.ic_dot; iconTint = neutral; strike = true
            progressText = stringResource(R.string.task_status_cancelled); progressColor = neutral
            chipText = null; chipColor = null; chipTextColor = null
        }
        else -> {
            stroke = outlineVariant; iconRes = R.drawable.ic_dot; iconTint = neutral; strike = false
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
                StatusGlyph(iconRes = iconRes, tint = iconTint, strike = strike, status = task.status)
            }
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.space_4)))
            Text(
                text = task.name,
                style = textStyles.rowTitle,
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
 * 状态图形（点 / check / ×，被取消时叠一道错误色划叉）。
 *
 * 状态跃迁时做一次轻微 scale-in（对应原 `TaskAdapter` 的 statusChanged 分支，
 * 用同一套 emphasized-decelerate 与 medium 时长）；滚动复用不会重放，
 * 因为 LazyColumn 的 key 是任务名、状态不变则不触发。
 */
@Composable
private fun StatusGlyph(
    iconRes: Int,
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
            painter = painterResource(iconRes),
            contentDescription = stringResource(R.string.task_status),
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
        if (strike) {
            Icon(
                painter = painterResource(R.drawable.ic_strike_cross),
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
 * 主页的 Compose 宿主。
 *
 * 极验按钮容器在此**急切创建并对外暴露**：SDK 的 `GT3GeetestButton` 需要真实的
 * ViewGroup 承载 `addView/removeView`，而 Compose 无法表达「往某个位置塞一个第三方 View」，
 * 故保留一个 `LinearLayout`（与 `fragment_home.xml` 的 geetest_container 同配置），
 * 仅在可见时经 `AndroidView` 挂进组合。
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
    private var onCancelDailyListener: Runnable? = null
    private var onRetryFailedListener: Runnable? = null
    private var onBackgroundClickListener: Runnable? = null
    private var onViewLogListener: Runnable? = null

    @Composable
    override fun Content() {
        VenusTheme {
            HomeScreen(
                state = uiState,
                geetestContainer = geetestContainer,
                onUserSelected = { onUserSelectedListener?.accept(it) },
                onJumpToUsers = { onJumpToUsersListener?.run() },
                onStartDaily = { onStartDailyListener?.run() },
                onCancelDaily = { onCancelDailyListener?.run() },
                onRetryFailed = { onRetryFailedListener?.run() },
                onBackgroundClick = { onBackgroundClickListener?.run() },
                onViewLog = { onViewLogListener?.run() },
            )
        }
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
                    HomeTaskRow("米游社签到", TASK_COMPLETED),
                    HomeTaskRow("原神", TASK_RETAINED),
                    HomeTaskRow("崩坏：星穹铁道", TASK_IN_PROGRESS),
                    HomeTaskRow("绝区零", TASK_ERROR),
                    HomeTaskRow("崩坏3", TASK_PENDING),
                )
            },
            geetestContainer = geetest,
            onUserSelected = {},
            onJumpToUsers = {},
            onStartDaily = {},
            onCancelDaily = {},
            onRetryFailed = {},
            onBackgroundClick = {},
            onViewLog = {},
        )
    }
}
