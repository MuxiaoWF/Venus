package com.muxiao.Venus.common.ui.compose

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.animation.AnimationUtils
import androidx.annotation.AnimRes
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.muxiao.Venus.R
import com.muxiao.Venus.User.AvatarStore

/**
 * 设置抽屉内容的外壳（取代原 XML 的 `Widget.Venus.DrawerPane` 样式，该样式已随迁移删除）：
 * 左右 space_4、上下 gap_row 内边距 + 竖向排列。
 * 各 `item_setting_*` 迁移后的 Composable 一律用它做根，保证抽屉外壳口径一致。
 */
@Composable
internal fun DrawerPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimensionResource(R.dimen.space_4),
                end = dimensionResource(R.dimen.space_4),
                top = dimensionResource(R.dimen.gap_row),
                bottom = dimensionResource(R.dimen.gap_row),
            ),
        content = content,
    )
}

/** Java 友好的“按下标回调”（避免 java.util.function 在 minSdk 23 下的脱糖依赖）。 */
fun interface IndexConsumer {
    fun accept(index: Int)
}

/** Java 友好的布尔回调。 */
fun interface BooleanConsumer {
    fun accept(value: Boolean)
}

/** Java 友好的「下标 + 勾选态」回调，用于 Chip 组。 */
fun interface IndexedCheckedConsumer {
    fun accept(index: Int, checked: Boolean)
}

/** Java 友好的浮点回调（滑块等连续值）。 */
fun interface FloatConsumer {
    fun accept(value: Float)
}

/**
 * 左文案 + 右开关的一行，等价于 `SwitchMaterial(text=..., textAppearance=DrawerLabel)`。
 */
@Composable
internal fun LabeledSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = LocalVenusTextStyles.current.drawerLabel,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 多选题 Chip 流式布局，等价于 XML 的 `ChipGroup` + `Widget.Material3.Chip.Filter`：
 * 自动换行、多选、可整组禁用（国际服隐藏/禁用场景）。
 *
 * @param labels 各 Chip 文案（顺序即 Java 侧的下标契约）
 * @param checked 各 Chip 勾选态，长度需与 [labels] 一致
 * @param enabled 整组是否可点
 * @param visible 各 Chip 是否可见（国际服隐藏不支持的游戏）
 */
@Composable
internal fun FilterChipFlow(
    labels: List<String>,
    checked: List<Boolean>,
    enabled: Boolean,
    visible: List<Boolean>,
    onToggle: (index: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
    ) {
        labels.forEachIndexed { index, label ->
            if (visible.getOrElse(index) { true }) {
                FilterChip(
                    selected = checked.getOrElse(index) { false },
                    onClick = { onToggle(index, !checked.getOrElse(index) { false }) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * 与 `Widget.Venus.Card` 等价的分组卡：surfaceContainerLow 底 + 中圆角 + level1 阴影。
 * 对应 XML 里 `style="@style/Widget.Venus.Card"` 的 MaterialCardView。
 */
@Composable
internal fun VenusCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_md)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(
            defaultElevation = dimensionResource(R.dimen.elevation_level1),
        ),
    ) {
        Column(content = content)
    }
}

/**
 * 把项目的 M3 缓动插值器（`R.anim.emphasized_decelerate` 等）桥接成 Compose 的 [Easing]。
 *
 * 直接搬 XML 插值器而不是用 Compose 内置曲线，是为了让动画手感与 View 侧严格一致
 * （这些曲线同时被 Fragment 转场、chip 淡入、结果区滑入使用）。
 */
@Composable
internal fun rememberVenusEasing(@AnimRes animRes: Int): Easing {
    val context = LocalContext.current
    return remember(context, animRes) {
        val interpolator = AnimationUtils.loadInterpolator(context, animRes)
        Easing { fraction -> interpolator.getInterpolation(fraction) }
    }
}

/**
 * 水平分隔线：outlineVariant 引导色 + 标准细线厚度（divider_height）。
 * 各页面与卡片统一复用；需要缩进或上下间距时经 [modifier] 传入，不再各自私有封装。
 */
@Composable
internal fun VenusDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = dimensionResource(R.dimen.divider_height),
    )
}

/**
 * 命令台页眉：眉标 + 大标题 + 右上角 mono chip + 副标题。
 *
 * 对应 XML 里三个主页面（home / settings / user_management）完全一致的那段
 * `Eyebrow + HeaderTitle + date/version chip + HeaderSubtitle` 结构——
 * 原先三份 XML 逐字复制，迁移后收敛为一处，改版式只改这里。
 *
 * @param chipText 右上角 chip 文案（日期 `MM/dd E` 或版本号）；空串则不显示
 * @param subtitle 副标题；null 表示无（settings 页有固定副标题，其余两页由 Java 动态回填）
 */
@Composable
internal fun CommandHeader(
    title: String,
    chipText: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val gutter = dimensionResource(R.dimen.gutter_screen)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = gutter,
                end = gutter,
                top = dimensionResource(R.dimen.space_5),
                bottom = dimensionResource(R.dimen.space_3),
            ),
    ) {
        Text(text = stringResource(R.string.venus_eyebrow), style = textStyles.eyebrow)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = dimensionResource(R.dimen.space_1)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = textStyles.headerTitle,
                modifier = Modifier.weight(1f),
            )
            if (chipText.isNotEmpty()) HeaderChip(chipText)
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = textStyles.headerSubtitle,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_1)),
            )
        }
    }
}

/** 页眉右上角的 mono 值 chip（日期 / 版本号）：液态玻璃 + 4dp 圆角 + 半透渐变底。 */
@Composable
private fun HeaderChip(text: String) {
    val density = LocalDensity.current
    val cornerPx = with(density) { dimensionResource(R.dimen.shape_corner_xxs).toPx() }
    Box {
        LensGlass(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
        )
        Text(
            text = text,
            style = LocalVenusTextStyles.current.monoValue,
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.space_3),
                vertical = dimensionResource(R.dimen.space_2),
            ),
        )
    }
}

/**
 * 头像徽标（纯 Compose 实现，原 AndroidView(ImageView) 已移除）。
 *
 * 对应 XML 里 `bg_icon_circle` + `ic_user` + `app:tint="?attr/colorPrimary"` 的头像位
 * （首页账号行、抽卡链接页账号行、用户管理页用户卡共用）。
 *
 * 视觉语义：
 * - 圆底：主色 10% 填充 + 主色 20% 发丝描边（同 bg_icon_circle 的 oval solid/stroke）
 * - 默认态：ic_user 主色着色 + space_2 内边距
 * - 自定义头像：Glide 圆形裁剪结果铺满；加载期间保持默认态，出图才切换（防闪烁），
 *   加载失败或文件竞态被删时回默认态
 *
 * `key(username)` 是必需的：列表复用同一节点时换人必须重新加载，
 * 否则会残留上一用户的头像状态。
 */
@Composable
internal fun VenusAvatar(username: String?, size: Dp = 32.dp) {
    val context = LocalContext.current
    key(username) {
        val hasCustom = remember(username, context) {
            !username.isNullOrEmpty() && AvatarStore.hasAvatar(context, username)
        }
        var bitmap by remember(username) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(username, hasCustom) {
            if (hasCustom && username != null) {
                Glide.with(context)
                    .asBitmap()
                    .load(AvatarStore.avatarFile(context, username))
                    .circleCrop()
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?,
                        ) {
                            bitmap = resource
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            bitmap = null
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            // 文件在 hasAvatar 判断后被删等竞态：保持 null → 渲染默认态
                            bitmap = null
                        }
                    })
            }
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape)
                .border(
                    width = dimensionResource(R.dimen.stroke_width_hairline),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val loaded = bitmap
            if (hasCustom && loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_user),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(dimensionResource(R.dimen.space_2)),
                )
            }
        }
    }
}
