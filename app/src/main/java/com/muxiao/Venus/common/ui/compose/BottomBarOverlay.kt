package com.muxiao.Venus.common.ui.compose

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import java.lang.ref.WeakReference

/**
 * 底栏悬浮覆盖高度（玻璃面板高 + 下外边距 + 系统导航栏 inset）的发布中心。
 *
 * 原 Java 版平移为 Compose 状态对象：内容直通屏幕底、可滚进玻璃下方，各页面
 * 控制器把本高度作为底部留白下发（Compose 侧 padding），保证最后一个列表项能
 * 完整滚出玻璃区。高度在底栏布局/insets 就绪后由 `VenusBottomBar` 的
 * onGloballyPositioned 发布，变化时自动通知已注册的滚动容器重涂。
 *
 * 监听用弱引用持有：页面控制器在 release 时显式 removeListener，不反向保活。
 */
object BottomBarOverlay {

    /** 覆盖高度（px）。Compose 侧可直接观察（如页面需要驱动重组时）。 */
    /** 覆盖高度状态（Compose 侧观察用；Java 侧走 [get]）。 */
    val heightState: State<Int>
        field = mutableIntStateOf(0)

    /** 发布覆盖高度（UI 线程，底栏布局监听调用）。 */
    @JvmStatic
    fun update(px: Int) {
        if (px == heightState.intValue) return
        heightState.intValue = px
        synchronized(listeners) {
            listeners.removeAll { it.get() == null }
            listeners.forEach { it.get()?.run() }
        }
    }

    /** 当前覆盖高度（px；未发布前为 0）。 */
    @JvmStatic
    fun get(): Int = heightState.intValue

    private val listeners = ArrayList<WeakReference<Runnable>>()

    /** 注册覆盖高度变化监听（弱引用持有；调用方需在销毁时 [removeListener]）。 */
    @JvmStatic
    fun addListener(listener: Runnable) {
        synchronized(listeners) {
            listeners.add(WeakReference(listener))
            listeners.removeAll { it.get() == null }
        }
    }

    /** 解除监听。 */
    @JvmStatic
    fun removeListener(listener: Runnable) {
        synchronized(listeners) { listeners.removeIf { it.get() === listener } }
    }
}
