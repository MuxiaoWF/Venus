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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import com.bumptech.glide.signature.ObjectKey
import com.muxiao.Venus.R
import com.muxiao.Venus.User.AvatarStore

/**
 * 设置抽屉内容的外壳（取代原 XML 的 `Widget.Venus.DrawerPane` 样式，该样式已随迁移删除）。
 *
 * 各 `item_setting_*` 迁移后的 Composable 一律用它做根，保证抽屉外壳口径一致。
 *
 * <h3>内边距为什么是 12 / 8 / 4 / 16</h3>
 * 迁移初版是「上下 gap_row(12) + 左右 space_4(16)」，与上游三层叠加后，内容被推到
 * 左 50dp / 右 32dp，而抽屉标题行是 24/24dp——内容右端比标题右端多缩 8dp，
 * 可点区域白窄 16dp，视觉上"内容悬在标题下方、右边收不齐"。
 * 现在按「左悬挂、右对齐」重定：
 * - 左 space_3(12)：引导线在 24dp（与标题文字左端齐），内容从 36dp 起（24 + 12），
 *   竖线到内容留 10dp（12 - 2dp 线宽），保留从属层级的悬挂缩进；
 * - 右 space_2(8)：内容右端与标题行右端同为 24dp，按钮/chip 的右边缘终于对得上；
 * - 上 space_1(4)：标题行自带 16dp 下边距，这里再垫 12 会形成 28dp 空档，
 *   展开后第一行与标题"脱节"。4dp 让内容紧贴标题行，密度由此开始；
 * - 下 [R.dimen.drawer_content_bottom](16)：**上下不对称是刻意的**。标题行下方有 16dp 垫着，
 *   内容底部却紧跟分隔线与下一条标题行的 16dp；底部若也只有 4dp，内容会紧贴分隔线，
 *   形成「上 20 / 下 4」的失衡（抽屉看起来被切掉了下半段）。取 16dp 后分隔线正好居中。
 *
 * 调用方不要在外层再加左右/底部 padding（那正是错位与底部失衡的来源）。
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
                start = dimensionResource(R.dimen.space_3),
                end = dimensionResource(R.dimen.space_2),
                top = dimensionResource(R.dimen.space_1),
                bottom = dimensionResource(R.dimen.drawer_content_bottom),
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
        // 纵向 gap 收紧到 4dp：chip 组折行时（游戏签到最多 3 行）每行省 4dp，横向仍留 8dp 防误触
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_1)),
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
 * @param collapse 传 `rememberHeaderCollapseState()` 的实例即启用「随滚动收缩 / 展开」；
 *        null = 固定页眉。收缩顺序为「顶部留白 → 眉标 → 副标题」，标题行（含 chip）常驻，
 *        因此收起后列表上方仍有一个可读的页面标题
 */
@Composable
internal fun CommandHeader(
    title: String,
    chipText: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    collapse: HeaderCollapseState? = null,
) {
    val textStyles = LocalVenusTextStyles.current
    val density = LocalDensity.current
    val gutter = dimensionResource(R.dimen.gutter_screen)
    val expandedTop = dimensionResource(R.dimen.space_5)
    val compactTop = dimensionResource(R.dimen.space_2)
    val topDeltaPx = with(density) { (expandedTop - compactTop).toPx() }

    // 眉标 / 副标题的自然高度：收缩时逐块裁掉，标题行常驻（列表永远有个可读的归属标题）
    var eyebrowPx by remember { mutableFloatStateOf(0f) }
    var subtitlePx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(topDeltaPx, eyebrowPx, subtitlePx) {
        collapse?.updateRange(topDeltaPx + eyebrowPx + subtitlePx)
    }

    // 收缩预算按「顶部留白 → 眉标 → 副标题」的顺序分配：前一项裁完才轮到下一项，
    // 所以拖动过程中先收紧留白、再吃掉眉标、最后收副标题，不会出现半行文字被切断
    var budgetPx = collapse?.let { (-it.offsetPx).coerceAtLeast(0f) } ?: 0f
    val topCutPx = budgetPx.coerceIn(0f, topDeltaPx).also { budgetPx -= it }
    val eyebrowCutPx = budgetPx.coerceIn(0f, eyebrowPx).also { budgetPx -= it }
    val subtitleCutPx = budgetPx.coerceIn(0f, subtitlePx)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = gutter,
                end = gutter,
                top = expandedTop - with(density) { topCutPx.toDp() },
                bottom = dimensionResource(R.dimen.space_3),
            ),
    ) {
        CollapsibleBlock(
            naturalPx = eyebrowPx,
            visiblePx = eyebrowPx - eyebrowCutPx,
            alpha = collapseAlpha(eyebrowPx, eyebrowCutPx),
        ) {
            Text(
                text = stringResource(R.string.venus_eyebrow),
                style = textStyles.eyebrow,
                modifier = Modifier.onSizeChanged { eyebrowPx = it.height.toFloat() },
            )
        }
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
            CollapsibleBlock(
                naturalPx = subtitlePx,
                visiblePx = subtitlePx - subtitleCutPx,
                alpha = collapseAlpha(subtitlePx, subtitleCutPx),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { subtitlePx = it.height.toFloat() },
                ) {
                    Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
                    Text(text = subtitle, style = textStyles.headerSubtitle)
                }
            }
        }
    }
}

/** 随裁切比例淡出：裁掉多少就淡多少，避免高度归零前还留一行实心文字。 */
private fun collapseAlpha(naturalPx: Float, cutPx: Float): Float =
    if (naturalPx <= 0f) 1f else (1f - cutPx / naturalPx).coerceIn(0f, 1f)

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
internal fun VenusAvatar(username: String?, size: Dp = 32.dp, refreshToken: Int = 0) {
    val context = LocalContext.current
    // refreshToken 参与 key/remember/LaunchedEffect：头像文件会被原地覆盖（路径不变），
    // 只靠 username 做 key 的话，换完头像这里不会重载，界面仍是旧图。
    key(username, refreshToken) {
        val hasCustom = remember(username, refreshToken) {
            !username.isNullOrEmpty() && AvatarStore.hasAvatar(context, username)
        }
        var bitmap by remember(username, refreshToken) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(username, hasCustom, refreshToken) {
            if (hasCustom && username != null) {
                val file = AvatarStore.avatarFile(context, username)
                Glide.with(context)
                    .asBitmap()
                    .load(file)
                    // 路径不变但内容会变：用修改时间做签名，否则 Glide 命中内存缓存直接返回旧图
                    .signature(ObjectKey(file.lastModified()))
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
        AvatarFrame(size) {
            val loaded = bitmap
            if (hasCustom && loaded != null) {
                AvatarImage(loaded)
            } else {
                AvatarPlaceholderIcon()
            }
        }
    }
}

/**
 * 尚未落盘的头像预览（相册 Uri / File）。
 *
 * 登录页「选择头像」在登录成功前不写盘（避免给一个没登成功的账号留孤儿文件），
 * 所以不能走 [VenusAvatar]（它按用户名读文件），这里直接按任意 Glide model 加载预览。
 * 外框、默认图标、加载失败回退都与 [VenusAvatar] 同一套，两处观感不会分叉。
 */
@Composable
internal fun AvatarPreview(model: Any, size: Dp = 32.dp) {
    val context = LocalContext.current
    var bitmap by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        Glide.with(context)
            .asBitmap()
            .load(model)
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
                    bitmap = null
                }
            })
    }
    AvatarFrame(size) {
        val loaded = bitmap
        if (loaded != null) AvatarImage(loaded) else AvatarPlaceholderIcon()
    }
}

/** 头像外框：圆形主色淡底 + 发丝描边（同 `bg_icon_circle` 的 oval solid/stroke）。 */
@Composable
private fun AvatarFrame(size: Dp, content: @Composable BoxScope.() -> Unit) {
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
        content = content,
    )
}

@Composable
private fun AvatarImage(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** 默认态：ic_user 主色着色 + space_2 内边距。 */
@Composable
private fun AvatarPlaceholderIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_user),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxSize()
            .padding(dimensionResource(R.dimen.space_2)),
    )
}
