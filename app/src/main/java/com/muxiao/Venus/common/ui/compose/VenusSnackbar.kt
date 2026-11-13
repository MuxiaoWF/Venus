package com.muxiao.Venus.common.ui.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R
import kotlinx.coroutines.launch

/**
 * 应用级 Snackbar 门面（Java 友好）：业务侧只调 [show]，落在哪个宿主由当前活跃页面决定。
 *
 * <p>多宿主：主界面与二级页面各持独立宿主（页面组合树才是 UI 真实边界）。[bind] 按注册
 * 顺序成栈，[show] 只投给最后注册且仍存活的宿主（当前前台页面）；[unbind] 后自动回退下层。</p>
 *
 * <p>线程：任意线程可调，内部 marshal 到主线程（Snackbar 本身要求主线程）。</p>
 */
object VenusSnackbar {

    /** 宿主登记（token 用于精确注销，避免 lambda 实例比较）。 */
    private val sinks = LinkedHashMap<Int, (String) -> Unit>()
    private var nextToken = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 弹一条短时长提示：无活跃宿主时静默丢弃，不抛异常。 */
    @JvmStatic
    fun show(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) deliver(message)
        else mainHandler.post { deliver(message) }
    }

    private fun deliver(message: String) {
        val sink = synchronized(sinks) { sinks.values.lastOrNull() } ?: return
        sink(message)
    }

    /** 注册宿主，返回注销令牌（Compose 侧在 DisposableEffect 内成对调用）。 */
    internal fun bind(sink: (String) -> Unit): Int = synchronized(sinks) {
        val token = ++nextToken
        sinks[token] = sink
        token
    }

    internal fun unbind(token: Int) {
        synchronized(sinks) { sinks.remove(token) }
    }
}

/**
 * 页面级 Snackbar 宿主：需放在页面根 [androidx.compose.foundation.layout.Box] 内。
 *
 * 不占位：无消息时宿主高度为 0，不遮挡下层内容；有消息时仅底部条带接收触摸。
 */
@Composable
fun VenusSnackbarHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(hostState) {
        val token = VenusSnackbar.bind { message ->
            scope.launch {
                // 连续提示不排队，直接替换当前那条
                hostState.currentSnackbarData?.dismiss()
                hostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }
        onDispose { VenusSnackbar.unbind(token) }
    }

    // 距屏幕底 64dp 以避让底栏玻璃条。SnackbarHost 自身把内容放在 host 左上角，
    // 故外包一层 Box 承担居中；上移必须用 offset——padding 会让空宿主也占住底部条带挡住触摸。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = (-64).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SnackbarHost(
            hostState = hostState,
            snackbar = { data -> VenusSnackbarSurface(data.visuals.message) },
        )
    }
}

/**
 * 页面根容器：内容 + 底部提示条宿主。
 *
 * 二级页面各自是独立 Activity，各需一份宿主（一页 = 一个 UI 边界）；主界面四页共用
 * 一份，直接在 [MainShellView] 里装配，不走这里。
 */
@Composable
fun VenusPageRoot(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        VenusSnackbarHost(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Snackbar 表面，独立以便复用到对话框等其他宿主。 */
@Composable
private fun VenusSnackbarSurface(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        shadowElevation = 4.dp,
        modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_4)),
    ) {
        Text(
            text = message,
            style = LocalVenusTextStyles.current.body,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
