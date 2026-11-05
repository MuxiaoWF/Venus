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
 * 液态玻璃采样链：**直接采用 Kyant0/AndroidLiquidGlass 库**（`io.github.kyant0:backdrop`，
 * 见 libs.versions.toml）的 LayerBackdrop 管线，替代旧自研的 GraphicsLayer 快照管线。
 *
 * <h3>与旧自研管线的本质差异（为什么画质上限更高）</h3>
 * <ul>
 *   <li><b>全分辨率录制</b>：源子树每帧按原始尺寸 record 进 [LayerBackdrop.graphicsLayer]
 *       ——旧管线 0.4× 快照放大 2.5× 采样是「栅格感/像素块感」的根源，此处结构上不存在；</li>
 *   <li><b>零回读、零快照协程</b>：玻璃透镜在 draw 阶段把源层经
 *       `Modifier.drawBackdrop` 录进自己的层并挂 RenderEffect（blur + AGSL lens），
 *       GPU 直接消费层内容——旧管线的 `toImageBitmap()` 回读栅栏（每次 ~10-25ms）与
 *       32ms 快照循环整个消失；</li>
 *   <li><b>同帧跟随</b>：源重录与玻璃重采样发生在同一帧的绘制遍历内（兄弟节点按组合
 *       顺序先后绘制），无旧管线「录制 → 异步快照 → 下一帧发布 → 玻璃重绘」的 1-2 帧
 *       滞后，切页/滚动的折射延迟感消失。</li>
 * </ul>
 *
 * <h3>双采样源架构（防自引用的结构解，沿用）</h3>
 *
 * 「玻璃透镜是采样源的子孙节点」时，透镜渲染会进入源层、下一帧被自己再次折射
 * （自引用反馈 → 雾斑累积）。把采样源拆成两棵互不包含的子树：
 *
 * 1. **基础源**（[GlassBackdrops.base]）：主题底色 + 壁纸图，**不含任何透镜**。
 *    页内玻璃（Home 操作行、chips 等，全在 Pager 子树内）一律采样它——结构上
 *    不可能采到自己的输出，零反馈。
 * 2. **内容源**（[GlassBackdrops.content]）：主题底色 + 壁纸图 + Pager 全部页面。
 *    只有**底栏**玻璃采样它（列表内容滚进玻璃下方）。底栏在内容源子树之外，
 *    其输出不会进入源层——同样无反馈；页内透镜的输出会进入内容源层，底栏折射
 *    「滚到玻璃下的玻璃」语义正确且稳定（页内透镜的输入是基础源，不构成环）。
 *
 * <h3>内容变化信号（旧 motionTick 平移，仍是必需）</h3>
 *
 * Compose 的滚动/翻页是「子树仅 layer 平移」——源节点 draw 不会自动重跑，录制会停在
 * 旧位置。沿用旧管线的两路信号：
 * <ol>
 *   <li><b>滚动旁听</b>：[glassScrollMotion]（nestedScroll onPostScroll）与 Pager 位移
 *       旁听逐帧 bump [GlassBackdropState.contentVersion]；</li>
 *   <li><b>draw-phase 读取</b>：源节点链（[glassBackdropSource] 内）与透镜链
 *       （[LensGlass] 内）各挂一个 [GlassMotionNode] 读 contentVersion——bump 即
 *       失效重画，同布局节点整链重画连带 layerBackdrop 重录 / drawBackdrop 重采样。</li>
 * </ol>
 * 无滚动信号的内容变化（背景位图发布）经 [GlassBackdropState.requestCapture] 显式失效。
 */

/**
 * 双采样源集合（主界面外壳持有）。见文件头「双采样源架构」。
 *
 * @param base 基础源：底色 + 壁纸，页内玻璃的采样源（无透镜子孙，无自引用）
 * @param content 内容源：底色 + 壁纸 + 页面，底栏玻璃的采样源
 */
class GlassBackdrops internal constructor(
    val base: GlassBackdropState,
    val content: GlassBackdropState,
)

/**
 * 一个采样源的链路状态：库的 [LayerBackdrop]（全分辨率录制层）+ 内容变化信号。
 * 透镜经 [LocalGlassBackdrop]（基础源）或显式参数（底栏 → 内容源）取用。
 */
class GlassBackdropState internal constructor(val backdrop: LayerBackdrop) {

    /**
     * 内容变化信号：滚动（[glassScrollMotion] / Pager 位移旁听）或显式失效
     * （[requestCapture]）时 bump。读取方：
     * - 源节点链上的 [GlassMotionNode]（[glassBackdropSource] 内）：失效 → 同布局节点
     *   整链重画 → layerBackdrop 重录；
     * - 透镜链上的 [GlassMotionNode]（LensGlass 内）：失效 → 玻璃重画 → 同帧重采样源层。
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

/**
 * 创建双采样源（主界面外壳组合期调用一次）。每源一个 [LayerBackdrop] 录制层，
 * 无快照协程、无回读——源层随源节点 draw 自动推进（见文件头）。
 */
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
 * 玻璃采样源标记。挂在与采样范围对应的子树根上（见文件头双源架构）：
 * 链上先挂库的 [layerBackdrop]（每帧 draw 把子树全分辨率 record 进源层），再挂
 * [GlassMotionElement] 读 [GlassBackdropState.contentVersion]——滚动信号 bump 即
 * 失效重画（同一布局节点的 draw modifier 整链重跑），保证纯平移滚动期间录制跟随。
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState): Modifier =
    this
        .layerBackdrop(state.backdrop)
        .then(GlassMotionElement(state.contentVersion))

/**
 * 把 Compose 的滚动告诉液态玻璃。**带玻璃的滚动页面必须在根节点挂上它。**
 *
 * Compose 的 LazyColumn / verticalScroll / HorizontalPager 走自己的滚动（子树仅 layer
 * 平移，父级 draw 不会自动重跑），内容源节点收不到任何通知——录制会停在旧位置，
 * 底栏玻璃内容冻结。这里用 nestedScroll 的 onPostScroll 旁听每一帧被内层滚容器消费掉
 * 的位移，bump 内容源的 [GlassBackdropState.contentVersion]：源节点整链重画（重录），
 * 玻璃链同步重画（重采样）。不改动任何滚动行为，只旁听。
 */
@Composable
fun Modifier.glassScrollMotion(): Modifier {
    val backdrop = LocalGlassContentBackdrop.current
    val connection = remember(backdrop) {
        object : NestedScrollConnection {
            // available/source 为接口契约参数，本旁听逻辑只需要 consumed，故抑制未使用告警
            @Suppress("UNUSED_PARAMETER")
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
 * 内容变化信号的 draw-phase 读取节点：draw 中读 [tick]，bump 即失效重画（Compose 对
 * draw 内的状态读自动注册观察）。挂点决定作用域：
 * - 源节点链（[glassBackdropSource]）：重画连带 [layerBackdrop] 重录；
 * - 透镜链（[LensGlass]）：重画连带 drawBackdrop 重采样源层。
 * 同一布局节点上的 draw modifier 共享一次 draw 遍历——任一节点失效即整链重跑。
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
