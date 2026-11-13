package com.muxiao.Venus.common.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 液态玻璃采样链：直接用 Kyant0/AndroidLiquidGlass 库的 LayerBackdrop 管线（libs.versions.toml），
 * 全分辨率录制 + 零回读 + 同帧跟随，不再有快照管线的栅格感与 1-2 帧滞后。
 *
 * 双采样源（防自引用）：玻璃透镜若是采样源的子孙，其输出会进入源层被自己再次折射（雾斑累积）。
 * 故拆成两棵互不包含的子树——基础源 [GlassBackdrops.base]（底色 + 壁纸，不含透镜）供页内玻璃采样；
 * 内容源 [GlassBackdrops.content]（底色 + 壁纸 + Pager 全部页面）只供底栏采样（列表内容滚进玻璃下方）。
 *
 * 内容变化信号不可省：Compose 滚动/翻页只是子树 layer 平移，源节点 draw 不会重跑，录制会停在旧位置。
 * 滚动由 [glassScrollMotion] 与 Pager 位移旁听逐帧 bump [GlassBackdropState.contentVersion]；
 * 无滚动信号的变化（背景位图发布）须显式调 [GlassBackdropState.requestCapture]。
 */

/** 双采样源集合（主界面外壳持有）：base 供页内玻璃、content 供底栏。见文件头「双采样源」。 */
class GlassBackdrops internal constructor(
    val base: GlassBackdropState,
    val content: GlassBackdropState,
)

/** 一个采样源的链路状态：库的 [LayerBackdrop]（全分辨率录制层）+ 内容变化信号；透镜经 [LocalGlassBackdrop]（基础源）或显式参数（底栏 → 内容源）取用。 */
class GlassBackdropState internal constructor(val backdrop: LayerBackdrop) {

    /**
     * 内容变化信号：滚动（[glassScrollMotion] / Pager 旁听）或 [requestCapture] 时 bump。源节点链与透镜链上的
     * [GlassMotionNode] 在 draw 中读它，失效即整链重画 → layerBackdrop 重录 / drawBackdrop 重采样。
     */
    val contentVersion = mutableIntStateOf(0)

    /** 滚动旁听入口：Compose 滚动/翻页不触发源节点重画，每帧位移须显式失效。 */
    fun onContentMoved() {
        contentVersion.intValue++
    }

    /** 显式请求失效重录（内容在无滚动信号的情况下变化时，如背景位图发布）。 */
    fun requestCapture() {
        contentVersion.intValue++
    }
}

/** 创建双采样源（主界面外壳组合期调用一次）：每源一个 [LayerBackdrop] 录制层，无快照协程、无回读。 */
@Composable
fun rememberGlassBackdrops(): GlassBackdrops {
    val base = rememberLayerBackdrop()
    val content = rememberLayerBackdrop()
    val baseState = remember(base) { GlassBackdropState(base) }
    val contentState = remember(content) { GlassBackdropState(content) }
    return GlassBackdrops(baseState, contentState)
}

/** 页内玻璃透镜取用的采样源（基础源）；主界面外壳提供，无外壳的窗口为 null → 回退渲染。 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/** 滚动旁听目标（内容源）：[glassScrollMotion] 读取；玻璃滚动页面须在提供范围内使用。 */
val LocalGlassContentBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/**
 * 玻璃采样源标记，挂在与采样范围对应的子树根上：先挂库的 [layerBackdrop]（每帧 draw 把子树全分辨率 record 进源层），
 * 再挂 [GlassMotionElement] 读 [GlassBackdropState.contentVersion]——滚动 bump 即失效重画（整链重跑），保证纯平移滚动期间录制跟随。
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState): Modifier =
    this
        .layerBackdrop(state.backdrop)
        .then(GlassMotionElement(state.contentVersion))

/**
 * 把 Compose 的滚动告诉液态玻璃。带玻璃的滚动页面必须在根节点挂上它：LazyColumn / verticalScroll /
 * HorizontalPager 的滚动只是子树 layer 平移，内容源节点收不到通知，录制会停在旧位置（底栏玻璃内容冻结）。
 * 这里只旁听 onPostScroll 的已消费位移并 bump contentVersion，不消费位移，滚动/惯性/回弹行为不变。
 */
@Composable
fun Modifier.glassScrollMotion(): Modifier {
    val backdrop = LocalGlassContentBackdrop.current
    val connection = remember(backdrop) {
        object : NestedScrollConnection {
            // available/source 为接口契约参数，本处只要 consumed；IDE key 是 unused、编译器是 UNUSED_PARAMETER，两者都写
            @Suppress("unused", "UNUSED_PARAMETER")
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (consumed.x != 0f || consumed.y != 0f) {
                    backdrop?.onContentMoved()
                }
                // 只旁听：不消费任何剩余位移，滚动/惯性/回弹行为完全不变
                return Offset.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}

/**
 * 内容变化信号的 draw-phase 读取节点：draw 中读 [tick] 即自动注册观察，bump 即失效重画；
 * 挂在源节点链上连带 [layerBackdrop] 重录，挂在透镜链上连带 drawBackdrop 重采样。
 */
internal class GlassMotionNode(var tick: IntState) : Modifier.Node(), DrawModifierNode {

    override fun ContentDrawScope.draw() {
        tick.intValue // draw-phase 读：bump → 本节点失效 → 同布局节点整链重画
        drawContent()
    }
}

internal data class GlassMotionElement(val tick: IntState) :
    ModifierNodeElement<GlassMotionNode>() {
    override fun create() = GlassMotionNode(tick)
    override fun update(node: GlassMotionNode) {
        node.tick = tick
    }

    // 显式声明布局检查器中该 modifier 的展示信息，避免默认反射读取（依赖 kotlin-reflect）。
    override fun InspectorInfo.inspectableProperties() {
        name = "glassMotion"
        properties["tick"] = tick
    }
}
