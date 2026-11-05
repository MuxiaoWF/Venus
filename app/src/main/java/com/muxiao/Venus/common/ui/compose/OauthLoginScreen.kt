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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.muxiao.Venus.R

/**
 * 国际服登录页（原 res/layout/activity_oauth_login.xml）。
 *
 * 结构：工具栏（返回 + 标题）+ 可滚动内容区，内含「Cookie 登录」卡与
 * 「OAuth WebView」卡（点击开始抓取后才显示，固定 800dp 高 + 顶部线性进度）。
 *
 * WebView 通过 `AndroidView` 桥接：Compose 侧只负责容器与进度，配置/加载仍由 Activity 完成
 * （见 [OauthLoginScreenView.setOnWebViewCreatedListener]）。这样既拿到 Compose 的排版，
 * 又不把 WebView 的生命周期从 Activity 手里夺走。
 *
 * @param webViewVisible 是否显示 WebView 卡（点「Cookie 登录」后才为 true）
 * @param buttonEnabled Cookie 登录按钮是否可点（抓取期间禁用，防重复触发）
 * @param loading 是否显示顶部进度条（页面加载完成后隐藏）
 * @param onBackClick 返回
 * @param onStartCapture 点击「Cookie 登录」（宿主据此禁用按钮 / 展开卡 / 开进度）
 * @param onWebViewCreated WebView 实例就绪（Activity 在此配置 UA/Cookie/Client 并 loadUrl）
 */
@Composable
fun OauthLoginScreen(
    webViewVisible: Boolean,
    buttonEnabled: Boolean,
    loading: Boolean,
    onBackClick: () -> Unit,
    onStartCapture: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 工具栏：替代 MaterialToolbar（原样式 Widget.Venus.Toolbar：透明底 + ToolbarTitle）
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
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.close_webpage),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(90f),
                )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.oauth_login_title),
                style = textStyles.toolbarTitle,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = dimensionResource(R.dimen.space_5),
                    end = dimensionResource(R.dimen.space_5),
                    top = dimensionResource(R.dimen.space_2),
                    bottom = dimensionResource(R.dimen.space_4),
                ),
        ) {
            // ---- Cookie 登录卡 ----
            VenusCard {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_4))) {
                    Row(
                        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_3)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dimensionResource(R.dimen.icon_badge_circle))
                                .clip(CircleShape)
                                .background(colorResource(R.color.primary_alpha_10))
                                .padding(dimensionResource(R.dimen.space_2)),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_link),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(dimensionResource(R.dimen.gap_row)))
                        Text(
                            text = stringResource(R.string.cookie_login),
                            style = textStyles.title,
                        )
                    }
                    Text(
                        text = stringResource(R.string.cookie_login_hint),
                        style = textStyles.rowSubtitle,
                        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.space_3)),
                    )
                    Button(
                        // 点击后由宿主禁用按钮 / 展开卡片 / 打开进度条，
                        // 真正的 WebView 配置与加载在 onWebViewCreated 回调里由 Activity 完成
                        onClick = onStartCapture,
                        enabled = buttonEnabled,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.cookie_login_start),
                            style = textStyles.labelMedium,
                        )
                    }
                }
            }

            // ---- OAuth WebView 卡（点击后才展开）----
            if (webViewVisible) {
                Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
                VenusCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimensionResource(R.dimen.oauth_webview_height))
                            .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_md))),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx -> WebView(ctx).also(onWebViewCreated) },
                            // 卡片收起（重试/成功/失败重来）时旧 WebView 会离开组合树：
                            // 必须显式销毁，否则每次抓取都泄漏一个 WebView 实例
                            onRelease = { view -> view.destroy() },
                        )
                        if (loading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [OauthLoginScreen] 的 View 互操作宿主。 */
class OauthLoginScreenView(context: Context) : AbstractComposeView(context) {

    private val webViewVisibleState = mutableStateOf(false)
    private val buttonEnabledState = mutableStateOf(true)
    private val loadingState = mutableStateOf(false)

    private var onBackClickListener: Runnable? = null
    private var onWebViewCreatedListener: WebViewConsumer? = null

    /** WebView 卡是否展开。 */
    var webViewVisible: Boolean
        get() = webViewVisibleState.value
        set(value) {
            webViewVisibleState.value = value
        }

    /** Cookie 登录按钮是否可点。 */
    var cookieButtonEnabled: Boolean
        get() = buttonEnabledState.value
        set(value) {
            buttonEnabledState.value = value
        }

    /** 顶部进度条是否显示；Activity 在 onPageFinished 里置 false。 */
    var loading: Boolean
        get() = loadingState.value
        set(value) {
            loadingState.value = value
        }

    @Composable
    override fun Content() {
        VenusTheme {
            VenusPageRoot {
                OauthLoginScreen(
                    webViewVisible = webViewVisibleState.value,
                    buttonEnabled = buttonEnabledState.value,
                    loading = loadingState.value,
                    onBackClick = { onBackClickListener?.run() },
                    onStartCapture = {
                        // 抓取开始即禁用按钮 + 展开 WebView 卡 + 打开进度条；
                        // WebView 实例就绪后经 onWebViewCreated 交给 Activity 配置并加载
                        buttonEnabledState.value = false
                        webViewVisibleState.value = true
                        loadingState.value = true
                    },
                    onWebViewCreated = { view -> onWebViewCreatedListener?.accept(view) },
                )
            }
        }
    }

    fun setOnBackClickListener(listener: Runnable?) {
        onBackClickListener = listener
    }

    fun setOnWebViewCreatedListener(listener: WebViewConsumer?) {
        onWebViewCreatedListener = listener
    }

    /** 收起 WebView 卡并恢复按钮（抓取成功/失败后的收尾）。 */
    fun resetAfterCapture() {
        webViewVisibleState.value = false
        buttonEnabledState.value = true
        loadingState.value = false
    }
}

/** Java 友好的 WebView 回调（避免在 Java 侧构造 Kotlin 函数类型）。 */
fun interface WebViewConsumer {
    fun accept(view: WebView)
}

@Preview(name = "国际服登录", showBackground = true)
@Composable
private fun OauthLoginScreenPreview() {
    VenusTheme {
        OauthLoginScreen(
            webViewVisible = false,
            buttonEnabled = true,
            loading = false,
            onBackClick = {},
            onStartCapture = {},
            onWebViewCreated = {},
        )
    }
}
