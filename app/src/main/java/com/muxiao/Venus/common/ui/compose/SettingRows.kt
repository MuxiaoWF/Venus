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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R

/**
 * 设置抽屉的紧凑行组件族：骨架固定、间距由组件内部决定，调用方只描述语义。新增抽屉不要自己拼 Row——开关行用
 * [SettingSwitchRow]、导航行 [SettingNavRow]、多选 [SettingCheckRow]、外链 [SettingLinkRow]、动作 [SettingActionRow]/[GlassActionRow]。
 * 密度口径：[R.dimen.setting_row_min_height] 44dp 是 M3/无障碍最小可点高度，不得再压；行内上下各 space_1，标题与副标题间只隔 2dp 发丝；
 * 行距由调用方给 `Spacer(space_2)`、段距 `space_3`（不再用 space_4 做行距），说明文本一律走 `subtitle` 或 [SettingHint]。
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
 * [interaction] 只承载交互语义（`clickable` / `toggleable` / 空），使 role 能挂在整行而不只是控件本体；[modifier] 挂最外层管尺寸/权重/边距。
 */
@Composable
private fun SettingRowLayout(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    note: String?,
    noteColor: Color,
    leadingIcon: ImageVector?,
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
        modifier = modifier
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

            leadingIcon != null -> {
                Icon(
                    imageVector = leadingIcon,
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

/** 通用行：左图标 + 标题（可选副标题）+ 右内容，行本身可点；开关/导航/复选/外链请优先用对应派生组件。 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingRowLayout(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        note = null,
        noteColor = Color.Unspecified,
        leadingIcon = leadingIcon,
        iconTint = iconTint,
        leadingContent = null,
        trailing = trailing,
        interaction = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
        enabled = enabled,
    )
}

/**
 * 开关行：整行可点（`toggleable` + `Role.Switch`），Switch 本体只负责显示状态，行高按 44dp 起算。
 * @param subtitle 说明属于这一行，不要再在外面单独加 Text；超过 3 行请改用 [SettingHint]——说明太长会让整行都成点击区，容易误触
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
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        note = note,
        noteColor = noteColor,
        leadingIcon = null,
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
 * @param value 右侧等宽值（时刻 / 状态文案），null 则不显示；[valueColor] 为 Unspecified 时走次要文字色
 */
@Composable
internal fun SettingNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    SettingRowLayout(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        note = null,
        noteColor = Color.Unspecified,
        leadingIcon = leadingIcon,
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
                // VenusIcons.ChevronDown 默认朝下，顺时针 270° 后朝右
                Icon(
                    imageVector = VenusIcons.ChevronDown,
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
 * @param fillWidth false = 按内容宽度收缩，用于放进 FlowRow 与其它复选项并排
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
        modifier = modifier,
        title = title,
        subtitle = null,
        note = null,
        noteColor = Color.Unspecified,
        leadingIcon = null,
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

/** 外链行：下划线文案 + 尾随链接图标；「关于」与「森空岛」共用。 */
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
            imageVector = VenusIcons.LinkUrl,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
        )
    }
}

/**
 * 说明 / 提示文本：统一的次级字号 + 语气色。
 * @param tone 语气色（Info / Warning / Success）
 * @param maxLines 超出省略；超长说明请改写文案或移到帮助对话框，不要靠加行数解决
 */
@Composable
internal fun SettingHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: SettingTone = SettingTone.Info,
    maxLines: Int = 4,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = LocalVenusTextStyles.current.bodySmall,
        color = settingToneColor(tone),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 动作行：主按钮（可带等宽徽标）+ 可选的次要文字按钮，同一行——进度是按钮的结果、手动填写是同一动作的兜底，
 * 都应贴着主按钮，不要各占一行（拆成三行会多出几十 dp 空白）。
 * @param primaryBadge 主按钮内的等宽徽标（如 `1/2`）
 * @param secondaryText 与 [onSecondaryClick] 同时给才显示
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
 * 玻璃动作行：整行一块液态玻璃表面（`GlassSpecs.Inset`）+ 可选左图标与右侧等宽值。
 * 无 `trailing` 时「图标 + 文字」作为一组居中（按钮语义），传了 `trailing` 才切成左对齐的列表式。
 * 抽屉底部动作行（「查看榜单图」「更新配置」等）统一走它，不要再画九宫格 drawable 凑玻璃。
 */
@Composable
internal fun GlassActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
) {
    val textStyles = LocalVenusTextStyles.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 只有带右侧值才是列表式左对齐，其余一律居中——单动作行是按钮语义，不是列表行
    val centered = trailing == null
    // 居中时右侧要补一块「图标 + 间距」等宽空白，否则整组居中会让文字中心右偏约 12dp，看着像没对齐
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
            if (icon != null) {
                Icon(
                    imageVector = icon,
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
            if (centered && icon != null) {
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
 * 纯文本无交互，按 [R.dimen.setting_compact_row_height](32dp) 排布而非 44dp 行高，以压缩多行 KV 的总高度。
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

/** 等宽双按钮行（「选择图片 / 清除背景」「从 Github 检查 / 从网盘下载」等），两个窗格共用一套。 */
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
    // 文字被截断的根因是 M3 按钮默认水平 contentPadding 24dp×2：360dp 屏上等宽双按钮各约 150dp，
    // 文字只剩约 102dp，长文案（「检查更新 - Github」≈124dp）放不下。故内边距收到 space_2 让出空间并允许两行；
    // 两行会让两个按钮不等高，因此行高取 IntrinsicSize.Min、按钮 fillMaxHeight 强制等高。
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

/** 等宽双按钮中的单个：文字可换两行居中，并在 [IntrinsicSize.Min] 的父行里被拉平高度。 */
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
 * 单选分段控件（服务器类型 / 深浅模式 / 背景淡化风格），三个窗格共用。
 * @param glass true = 液态玻璃轨道 + 实心 `primaryContainer` 选中段（与页内玻璃同一材质）；
 *        false = M3 原生描边分段（需要严格 M3 观感时的退路）
 * 选中段用实心而非第二层玻璃：轨道已是玻璃表面，再叠透镜会发灰且两头采样同一底图。
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
