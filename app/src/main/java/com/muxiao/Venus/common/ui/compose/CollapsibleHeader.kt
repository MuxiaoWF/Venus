package com.muxiao.Venus.common.ui.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 页眉滚动收缩状态：手指拖动**按像素跟随**，松手过半对齐。
 *
 * 两处接入方共用这一套手感：四个 fragment 的页眉（`CommandHeader`）与图库页浮层顶栏
 * （`ImageScreen`）。差别只在「高度去哪了」——
 * fragment 页眉在正常布局流里，高度一缩下面的列表自然上移，不需要 padding 补偿；
 * 图库顶栏是浮层，得自己把同一份位移折算成列表的 `contentTopInset`。
 *
 * 只保存一个位移 [offsetPx]（范围 `[-rangePx, 0]`），可收缩高度 [rangePx] 由页眉实测回填，
 * 因此页眉里增减一行（如副标题为空）不需要另外调参；范围已知的场合（图库的 chip 行）
 * 也可以直接 [updateRange] 回填。
 */
@Stable
internal class HeaderCollapseState {

    /** 可收缩高度（px）：0 表示当前页眉没有可收缩内容（例如无眉标、无副标题）。 */
    private var rangePx by mutableFloatStateOf(0f)

    /** 当前位移（px）：0 = 完全展开，-rangePx = 完全收起。 */
    internal var offsetPx by mutableFloatStateOf(0f)

    /** 松手对齐动画的句柄：新手势按下时取消，避免动画和手指抢同一个值。 */
    internal var settleJob: Job? = null

    /** 0 = 完全展开，1 = 完全收起。 */
    val fraction: Float
        get() = if (rangePx <= 0f) 0f else (-offsetPx / rangePx).coerceIn(0f, 1f)

    /** 页眉测量完成后回填可收缩高度，并把位移夹回合法区间。 */
    internal fun updateRange(px: Float) {
        val next = px.coerceAtLeast(0f)
        if (next == rangePx) return
        rangePx = next
        offsetPx = offsetPx.coerceIn(-next, 0f)
    }

    /** 消费一段竖直位移，返回真正被消费的量（剩余部分交给列表继续滚）。 */
    internal fun consume(deltaY: Float): Float {
        if (rangePx <= 0f) return 0f
        val before = offsetPx
        offsetPx = (before + deltaY).coerceIn(-rangePx, 0f)
        return offsetPx - before
    }

    /** 松手对齐：过半收起，未过半弹回展开。 */
    internal suspend fun settle() {
        if (rangePx <= 0f) return
        animateTo(if (fraction >= 0.5f) -rangePx else 0f)
    }

    /** 展开（列表回到顶部时调用，避免顶部残留收起态）。 */
    internal suspend fun expand() {
        if (offsetPx == 0f) return
        animateTo(0f)
    }

    private suspend fun animateTo(target: Float) {
        if (offsetPx == target) return
        animate(
            initialValue = offsetPx,
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) { value, _ -> offsetPx = value }
    }
}

@Composable
internal fun rememberHeaderCollapseState(): HeaderCollapseState = remember { HeaderCollapseState() }

/**
 * 把页眉收缩接到滚动链上。
 *
 * 挂载位置：页面根容器（滚动容器的**父级**），这样列表的位移会先经过这里：
 * 上滑（`available.y < 0`）先收页眉，下滑先展开，页眉走完自己的行程再把剩余位移交给列表。
 * 惯性滚动（`SideEffect`）不驱动收缩，松手后由 `onPostFling` 一次性对齐，避免边飞边抖。
 */
@Composable
internal fun rememberHeaderCollapseConnection(state: HeaderCollapseState): NestedScrollConnection {
    val scope = rememberCoroutineScope()
    return remember(state, scope) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.SideEffect) return Offset.Zero
                if (available.y == 0f) return Offset.Zero
                // 上一轮对齐动画还没跑完就再次拖动：立刻让出控制权
                state.settleJob?.cancel()
                val consumed = state.consume(available.y)
                return Offset(0f, consumed)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                state.settleJob?.cancel()
                state.settleJob = scope.launch { state.settle() }
                return Velocity.Zero
            }
        }
    }
}

/**
 * 列表回到顶部时自动展开页眉。
 *
 * 必要性：惯性滚动不驱动收缩（见 [rememberHeaderCollapseConnection]），所以「一路甩回顶部」
 * 之后页眉会停在收起态；这里按 atTop 兜底展开，让顶部永远对应完整页眉。
 */
@Composable
internal fun HeaderCollapseState.expandWhenAtTop(atTop: Boolean) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(atTop) {
        if (!atTop) return@LaunchedEffect
        // 走 settleJob：这样下一次拖动可以在 onPreScroll 里直接把它取消，不会和手指抢位移
        settleJob?.cancel()
        settleJob = scope.launch { expand() }
    }
}

/**
 * 可收缩的一段页眉内容：高度随收缩量裁切，同时按剩余比例淡出。
 *
 * 内容以 `wrapContentHeight(unbounded = true)` 测量——父级高度在收缩时小于内容高度，
 * 若不解除高度约束，`onSizeChanged` 报的会是"被裁后的高度"，收缩量就会被越算越小。
 *
 * @param naturalPx 内容自然高度（px）；0 = 还没测到，此时先按自然高度排版，避免首帧闪烁
 * @param visiblePx 当前应占高度（px），收缩到 0 时不再占位
 */
@Composable
internal fun CollapsibleBlock(
    naturalPx: Float,
    visiblePx: Float,
    alpha: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val measured = naturalPx > 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (measured) {
                    Modifier.height(with(density) { visiblePx.coerceAtLeast(0f).toDp() })
                } else {
                    Modifier
                }
            )
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true, align = Alignment.Top)
                .alpha(alpha.coerceIn(0f, 1f)),
        ) {
            content()
        }
    }
}
