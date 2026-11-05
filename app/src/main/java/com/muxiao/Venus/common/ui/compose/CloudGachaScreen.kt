package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.muxiao.Venus.R

/** 内容区三态：加载中 / 页面已就绪 / 主帧加载失败。 */
const val CLOUD_STAGE_LOADING = 0
const val CLOUD_STAGE_CONTENT = 1
const val CLOUD_STAGE_ERROR = 2

/**
 * 云游戏抽卡链接页（原 res/layout/activity_cloud_gacha.xml）。
 *
 * 结构：顶栏（返回 + 标题）→ 分割线 → 提示行（登录后自动复制）→ 内容区三态。
 *
 * 内容区保留原实现的两个关键行为：
 * 1. **WebView 延迟到首帧之后再创建**（原为 ViewStub + `post`）——首次初始化是主线程重活，
 *    同步创建会拖慢转场；这里用 `withFrameNanos` 等过首帧再挂载。
 * 2. **加载/失败时不是移除 WebView 而是遮住**（原为 `INVISIBLE`），
 *    避免重新创建导致页面状态与会话丢失。
 *
 * @param stage 内容区状态，见 `CLOUD_STAGE_*`
 * @param onCloseClick 关闭页面
 * @param onRetryClick 加载失败后重试
 * @param onWebViewCreated WebView 首次创建完成（Activity 在此配置并 loadUrl）
 */
@Composable
fun CloudGachaScreen(
    stage: Int,
    onCloseClick: () -> Unit,
    onRetryClick: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    // 首帧之后再挂载 WebView（等价于原 root.post { setupWebView() }）
    var attachWebView by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        attachWebView = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 顶栏：返回 + 标题（原 insets 监听改为 statusBarsPadding）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    start = dimensionResource(R.dimen.space_2),
                    end = dimensionResource(R.dimen.gutter_screen),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_2),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCloseClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.close_webpage),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(90f),
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.cloud_gaming),
                style = textStyles.title,
            )
        }

        VenusDivider()

        // 提示行：图标 + 文案
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.gutter_screen),
                    vertical = dimensionResource(R.dimen.space_3),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
            Text(
                text = stringResource(R.string.gacha_login_prompt),
                style = textStyles.body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }

        // 内容区：WebView + 覆盖层（加载中 / 失败重试）
        Box(modifier = Modifier.fillMaxSize()) {
            if (attachWebView) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> WebView(ctx).also(onWebViewCreated) },
                )
            }
            if (stage != CLOUD_STAGE_CONTENT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (stage == CLOUD_STAGE_LOADING) {
                        CircularProgressIndicator()
                    } else {
                        CloudErrorContent(onRetryClick)
                    }
                }
            }
        }
    }
}

/** 失败态：圆底警示图标 + 说明 + 重试按钮（对应原 cloud_error 容器）。 */
@Composable
private fun CloudErrorContent(onRetryClick: () -> Unit) {
    val textStyles = LocalVenusTextStyles.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(dimensionResource(R.dimen.icon_badge_circle))
                .clip(CircleShape)
                .background(colorResource(R.color.primary_alpha_10))
                .padding(dimensionResource(R.dimen.space_2)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_task_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        Text(
            text = stringResource(R.string.cloud_load_failed),
            style = textStyles.body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_4)),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        Button(onClick = onRetryClick, shape = CircleShape) {
            Text(
                text = stringResource(R.string.cloud_retry),
                style = textStyles.labelMedium,
            )
        }
    }
}

/** [CloudGachaScreen] 的 View 互操作宿主。 */
class CloudGachaScreenView(context: Context) : AbstractComposeView(context) {

    private val stageState = mutableIntStateOf(CLOUD_STAGE_LOADING)
    private var onCloseClickListener: Runnable? = null
    private var onRetryClickListener: Runnable? = null
    private var onWebViewCreatedListener: WebViewConsumer? = null

    /** 内容区状态；Java 侧 showLoading/showContent/showError 都改这一个值。 */
    var stage: Int
        get() = stageState.intValue
        set(value) {
            stageState.intValue = value
        }

    @Composable
    override fun Content() {
        VenusTheme {
            VenusPageRoot {
                CloudGachaScreen(
                    stage = stageState.intValue,
                    onCloseClick = { onCloseClickListener?.run() },
                    onRetryClick = { onRetryClickListener?.run() },
                    onWebViewCreated = { view -> onWebViewCreatedListener?.accept(view) },
                )
            }
        }
    }

    fun setOnCloseClickListener(listener: Runnable?) {
        onCloseClickListener = listener
    }

    fun setOnRetryClickListener(listener: Runnable?) {
        onRetryClickListener = listener
    }

    fun setOnWebViewCreatedListener(listener: WebViewConsumer?) {
        onWebViewCreatedListener = listener
    }
}

@Preview(name = "云游戏抽卡", showBackground = true)
@Composable
private fun CloudGachaScreenPreview() {
    VenusTheme {
        CloudGachaScreen(
            stage = CLOUD_STAGE_ERROR,
            onCloseClick = {},
            onRetryClick = {},
            onWebViewCreated = {},
        )
    }
}
