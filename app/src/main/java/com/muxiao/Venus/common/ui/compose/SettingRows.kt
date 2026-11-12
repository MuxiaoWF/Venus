package com.muxiao.Venus.common.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R

/**
 * 设置抽屉的**紧凑行组件族**（替代原先 13 个窗格里各自手写的 Row + Spacer 组合）。
 *
 * <h3>为什么要有这一层</h3>
 * 迁移前的抽屉内容是「控件 + 手写 Spacer(space_1/2/3/4)」堆出来的：同一个开关行在 5 处有 3 种写法，
 * 说明文本、纯文本、导航行各写一遍，结果是**纵向 40% 的高度花在空白上**，且行距毫无规律。
 * 这里把「一行设置项」的骨架固定下来，间距由组件内部决定，调用方只描述语义。
 *
 * <h3>密度口径（改动前请先读）</h3>
 * - 行高下限 [R.dimen.setting_row_min_height] = 44dp：M3 与无障碍的最小可点高度，**不得再压**；
 * - 行内上下各 space_1(4dp)，标题与副标题之间只隔一条发丝（2dp）——副标题属于该行，不是独立段落；
 * - 行与行之间由调用方给 `Spacer(space_2)`，段与段之间给 `Spacer(space_3)`，**不再出现 space_4 做行距**；
 * - 说明文本一律走 [SettingHint] 或 [SettingSwitchRow] 的 `subtitle`，不再"独立 Text + 上下空白"。
 *
 * <h3>复用约定</h3>
 * - 开关行一律 [SettingSwitchRow]（整行可点、`onCheckedChange = null` 的 Switch，语义挂在行上）；
 * - 可点导航行一律 [SettingNavRow]（图标 + 标题 + 副标题 + 等宽值 + 右向箭头）；
 * - 多选行一律 [SettingCheckRow]；外链行一律 [SettingLinkRow]；动作行一律 [SettingActionRow] / [GlassActionRow]。
 * 新增抽屉不要自己拼 Row，先看这里有没有现成的。
 */

/** 提示文本的语气（决定颜色，不决定字号）。 */
internal enum class SettingTone { Info, Warning, Success }

/** 动作行的主按钮材质：液态玻璃（默认）或 M3 描边。 */
internal enum class SettingActionVariant { Glass, Outlined }

/** 语气 → 主题色；[SettingTone.Info] 用次要文字色。 */
@Composable
private fun settingToneColor(tone: SettingTone): Color = when (tone) {
    SettingTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    SettingTone.Warning -> colorResource(R.color.status_warning)
    SettingTone.Success -> colorResource(R.color.status_success)
}

/**
 * 所有行共用的骨架：左可选项（图标 / 复选框）+ 标题列（标题 / 副标题 / 附注）+ 右可选项。
 *
 * 交互修饰符由调用方以 [interaction] 传入（`clickable` / `toggleable` / 空），
 * 这样开关行与复选框行能把语义 role 挂在整行上，而不是只有控件本体可点。
 */
@Composable
private fun SettingRowLayout(
    title: String,
    subtitle: String?,
    note: String?,
    noteColor: Color,
    leadingIconRes: Int?,
    iconTint: Color,
    leadingContent: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    interaction: Modifier,
    enabled: Boolean,
    fillWidth: Boolean = true,
    subtitleMaxLines: Int = 3,
    noteMaxLines: Int = 3,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            // 放进 FlowRow 的复选行不能撑满宽度，否则会各占一行、并排失效
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = dimensionResource(R.dimen.setting_row_min_height))
            .then(interaction)
            .padding(vertical = dimensionResource(R.dimen.space_1)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leadingContent != null -> {
                leadingContent()
                Spacer(Modifier.width(dimensionResource(R.dimen.space_3)))
            }

            leadingIconRes != null -> {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    tint = if (iconTint.isSpecified) iconTint else secondary,
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_3)))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = textStyles.drawerLabel,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(dimensionResource(R.dimen.space_hairline)))
                Text(
                    text = subtitle,
                    style = textStyles.bodySmall,
                    color = secondary.copy(alpha = alpha),
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note != null) {
                Spacer(Modifier.height(dimensionResource(R.dimen.space_hairline)))
                Text(
                    text = note,
                    style = textStyles.bodySmall,
                    // 0 表示未指定：回退到次要文字色
                    color = if (noteColor.isSpecified) noteColor.copy(alpha = alpha) else secondary.copy(alpha = alpha),
                    maxLines = noteMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            trailing()
        }
    }
}

/**
 * 通用行：左图标 + 标题（可选副标题）+ 右内容，行本身可点。
 *
 * 供无现成派生组件的场景使用；开关/导航/复选/外链请优先用对应的派生组件。
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    iconTint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingRowLayout(
        title = title,
        subtitle = subtitle,
        note = null,
        noteColor = Color.Unspecified,
        leadingIconRes = leadingIconRes,
        iconTint = iconTint,
        leadingContent = null,
        trailing = trailing,
        interaction = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
        enabled = enabled,
    )
}

/**
 * 开关行：整行可点（`toggleable` + `Role.Switch`），Switch 本体只负责显示状态。
 *
 * 原 [LabeledSwitchRow] 只有 Switch 本体可点，行高仅 32dp，与 `touch_target_min` 的意图不符；
 * 这里把语义与可点区收回到整行，行高按 44dp 起算。
 *
 * @param subtitle 该开关的说明文本。说明属于这一行，不要再在外面单独加 Text。
 *        **超过 3 行请改用 [SettingHint]**——说明太长时整行都成了开关的点击区，容易误触
 * @param note 附加提醒（如验证码风险），与 [subtitle] 同样式、可单独着色
 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    subtitleMaxLines: Int = 3,
    noteMaxLines: Int = 3,
) {
    SettingRowLayout(
        title = title,
        subtitle = subtitle,
        note = note,
        noteColor = noteColor,
        leadingIconRes = null,
        iconTint = Color.Unspecified,
        leadingContent = null,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        interaction = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        enabled = enabled,
        subtitleMaxLines = subtitleMaxLines,
        noteMaxLines = noteMaxLines,
    )
}

/**
 * 导航行：图标 + 标题（可选副标题）+ 右侧等宽值 + 右向箭头，整行可点。
 *
 * 取代原实现里手写的「Row + Icon + 两个 Text + 旋转箭头」——通知窗格有 2 处、其余窗格散落多处。
 *
 * @param value 右侧等宽值（时刻 / 状态文案）；null 则不显示
 * @param valueColor 值颜色；Unspecified 走次要文字色
 */
@Composable
internal fun SettingNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIconRes: Int? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    SettingRowLayout(
        title = title,
        subtitle = subtitle,
        note = null,
        noteColor = Color.Unspecified,
        leadingIconRes = leadingIconRes,
        iconTint = Color.Unspecified,
        leadingContent = null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        text = value,
                        style = textStyles.monoValue,
                        color = if (valueColor.isSpecified) valueColor else secondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
                }
                // ic_chevron 默认朝下，顺时针 270° 后朝右
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(270f),
                )
            }
        },
        interaction = Modifier.clickable(enabled = enabled, onClick = onClick),
        enabled = enabled,
    )
}

/**
 * 复选行：左侧 Checkbox + 标题，整行可点（语义挂在行上，Checkbox 只显示状态）。
 *
 * @param fillWidth false 表示按内容宽度收缩，用于放进 [FlowRow] 与其它复选项并排
 */
@Composable
internal fun SettingCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    SettingRowLayout(
        title = title,
        subtitle = null,
        note = null,
        noteColor = Color.Unspecified,
        leadingIconRes = null,
        iconTint = Color.Unspecified,
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        trailing = null,
        interaction = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        enabled = enabled,
        fillWidth = fillWidth,
    )
}

/**
 * 外链行：下划线文案 + 尾随链接图标（等价原 XML 的 `drawableEnd + drawablePadding`）。
 *
 * 原先「关于」与「森空岛」各写了一份（`AboutLinkLine` / `SklandLink`），此处合并为一处。
 */
@Composable
internal fun SettingLinkRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = dimensionResource(R.dimen.setting_row_min_height))
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_1)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = LocalVenusTextStyles.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // fill = false：图标紧贴文字尾部，文字不被推开
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
        Icon(
            painter = painterResource(R.drawable.ic_link_url),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
        )
    }
}

/**
 * 说明 / 提示文本：统一的次级字号 + 语气色。
 *
 * 取代原先散落各处的 `textStyles.bodySmall.copy(color = onSurfaceVariant)` 与警告色变体。
 *
 * @param tone 语气色（Info / Warning / Success）
 * @param maxLines 上限行数；超出省略。超长说明请改写文案或移到帮助对话框，不要靠加行数解决
 */
@Composable
internal fun SettingHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: SettingTone = SettingTone.Info,
    maxLines: Int = 4,
) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.bodySmall,
        color = settingToneColor(tone),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 动作行：主按钮（可带等宽徽标）+ 可选的次要文字按钮，**同一行**。
 *
 * 原「云游戏签到」把「获取令牌 / 进度 / 手动填写」拆成三行共 88dp；
 * 进度是按钮的结果、手动填写是同一动作的兜底，二者都应贴着主按钮，而不是各占一行。
 *
 * @param primaryBadge 主按钮内的等宽徽标（如 `1/2`），与主按钮共处一行
 * @param secondaryText 次要动作文案（同一行右侧的文字按钮）；与 [onSecondaryClick] 同时给才显示
 */
@Composable
internal fun SettingActionRow(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryBadge: String? = null,
    variant: SettingActionVariant = SettingActionVariant.Glass,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val actionHeight = dimensionResource(R.dimen.setting_action_height)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (variant) {
            SettingActionVariant.Outlined -> OutlinedButton(
                onClick = onPrimaryClick,
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = actionHeight),
            ) {
                Text(text = primaryText, style = textStyles.labelMedium)
                if (primaryBadge != null) {
                    Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                    Text(text = primaryBadge, style = textStyles.bodySmall, color = secondary)
                }
            }

            SettingActionVariant.Glass -> Box(modifier = Modifier.weight(1f)) {
                // 胶囊形（与原 OutlinedButton(shape = CircleShape) 口径一致），故只给 pill
                LensGlass(
                    modifier = Modifier.matchParentSize(),
                    spec = GlassSpecs.Inset,
                    pill = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = actionHeight)
                        .clickable(onClick = onPrimaryClick)
                        .padding(horizontal = dimensionResource(R.dimen.space_3)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = primaryText,
                        style = textStyles.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (primaryBadge != null) {
                        Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                        Text(text = primaryBadge, style = textStyles.bodySmall, color = secondary)
                    }
                }
            }
        }
        if (secondaryText != null && onSecondaryClick != null) {
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            TextButton(onClick = onSecondaryClick) {
                Text(text = secondaryText, style = textStyles.labelMedium)
            }
        }
    }
}

/**
 * 玻璃动作行：整行是一块液态玻璃表面（`GlassSpecs.Inset`）+ 可选的左图标与右侧等宽值。
 *
 * 居中规则：无 `trailing` 时「图标 + 文字」作为一组居中（单动作行 = 按钮语义）；
 * 传了 `trailing` 才切成左对齐的「图标 + 文字 + 右值」列表式。
 *
 * 取代 `bg_glass_row_button` 九宫格 drawable（View 时代遗留，靠 `drawBehind` 画，
 * 与页内其它玻璃不是同一套材质）。「查看榜单图」「更新配置」等抽屉底部动作行统一走它。
 */
@Composable
internal fun GlassActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    trailing: String? = null,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 判定口径：只有「带右侧值」才是列表式的左对齐（左图标 + 文字 + 右值），
    // 其余一律居中——单动作行是按钮语义，不是列表行。
    val centered = trailing == null
    // 图标宽度 + 图标与文字的间距：居中时右侧要补一块等宽空白，
    // 否则「图标 + 文字」整组居中会让文字中心右偏 (图标 + 间距)/2（约 12dp），
    // 看上去像没对齐。补空白后文字本身压在行中线上，图标紧贴其左侧。
    val iconSize = dimensionResource(R.dimen.icon_inline)
    val iconGap = dimensionResource(R.dimen.space_2)
    Box(modifier = modifier.fillMaxWidth()) {
        LensGlass(
            modifier = Modifier.matchParentSize(),
            spec = GlassSpecs.Inset,
            cornerRadiusDp = dimensionResource(R.dimen.shape_corner_xs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.setting_row_min_height))
                .clickable(onClick = onClick)
                .padding(horizontal = dimensionResource(R.dimen.space_3)),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.width(iconGap))
            }
            Text(
                text = text,
                style = textStyles.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (centered) Modifier else Modifier.weight(1f),
            )
            // 居中的光学配平：右侧补一块与「图标 + 间距」等宽的空白
            if (centered && iconRes != null) {
                Spacer(Modifier.width(iconSize + iconGap))
            }
            if (trailing != null) {
                Text(text = trailing, style = textStyles.monoValue, color = secondary)
            }
        }
    }
}

/**
 * 键值行（左标签 / 右等宽值），用于「配置信息」这类只读 KV 列表。
 *
 * 纯文本、无交互，所以按 [R.dimen.setting_compact_row_height](32dp) 排布——7 行 KV 原先每行
 * 40dp + 手写 padding，共占 280dp，收紧后 224dp。
 */
@Composable
internal fun SettingValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    val textStyles = LocalVenusTextStyles.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimensionResource(R.dimen.setting_compact_row_height)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = textStyles.drawerLabel,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_3)))
        Text(
            text = value,
            style = textStyles.monoValue,
            color = if (valueColor.isSpecified) valueColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 等宽双按钮行（如「选择图片 / 清除背景」「从 Github 检查 / 从网盘下载」）。
 *
 * 原先这两个窗格各写一遍 `Row + OutlinedButton(weight 1f) + Spacer(space_2)`，
 * 连注释都是复制的；此处收口。
 */
@Composable
internal fun SettingButtonPairRow(
    leftText: String,
    onLeftClick: () -> Unit,
    rightText: String,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val actionHeight = dimensionResource(R.dimen.setting_action_height)
    // 文字被截断的根因是 M3 按钮默认水平 contentPadding 24dp×2：等宽双按钮在 360dp 屏上
    // 各约 150dp，留给文字只剩 102dp，"检查更新 - Github"（≈124dp）、
    // "Cloud Update Password: mxwf"（≈190dp）都放不下。
    // 两点处理：① 水平内边距收到 space_2(8)，让出 32dp；② 允许最多两行居中。
    // 两行会让两个按钮高度不一致，因此行高取 IntrinsicSize.Min、按钮 fillMaxHeight 强制等高。
    val contentPadding = PaddingValues(
        horizontal = dimensionResource(R.dimen.space_2),
        vertical = 0.dp,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PairButton(
            text = leftText,
            onClick = onLeftClick,
            style = textStyles.labelMedium,
            minHeight = actionHeight,
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
        PairButton(
            text = rightText,
            onClick = onRightClick,
            style = textStyles.labelMedium,
            minHeight = actionHeight,
            contentPadding = contentPadding,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 等宽双按钮中的单个：文字可换到两行、居中，且在 [IntrinsicSize.Min] 的父行里被拉平高度。
 */
@Composable
private fun PairButton(
    text: String,
    onClick: () -> Unit,
    style: TextStyle,
    minHeight: Dp,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = contentPadding,
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = minHeight),
    ) {
        Text(
            text = text,
            style = style,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 单选分段控件（服务器类型 / 深浅模式 / 背景淡化风格）。
 *
 * 原先三个窗格各自复制了同一段 20 行样板（`SingleChoiceSegmentedButtonRow` + `SegmentedButton`
 * + `itemShape` + 同款 label Text），此处收口。
 *
 * @param glass true = 液态玻璃轨道 + 实心 `primaryContainer` 选中段（与页内玻璃同一套材质）；
 *        false = M3 原生描边分段（需要严格 M3 观感时的退路）
 *
 * 选中段用**实心**而非第二层玻璃：轨道已经是玻璃表面，再叠一层透镜会发灰且两头采样同一底图。
 */
@Composable
internal fun VenusSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = true,
) {
    val textStyles = LocalVenusTextStyles.current
    if (!glass) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            options.forEachIndexed { index, text ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    // 按位置给出形状：首尾圆角、中间段直角，相邻段共用边框
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = text,
                            style = textStyles.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        return
    }

    val innerHeight = dimensionResource(R.dimen.setting_compact_row_height)
    Box(modifier = modifier.fillMaxWidth()) {
        LensGlass(
            modifier = Modifier.matchParentSize(),
            spec = GlassSpecs.Inset,
            pill = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.space_1))
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, text ->
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = innerHeight)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = textStyles.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
