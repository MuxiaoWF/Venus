package com.muxiao.Venus.common.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.MaterialColors
import com.muxiao.Venus.Setting.SettingsFragment
import com.muxiao.Venus.common.AppBackground
import com.muxiao.Venus.common.AppExecutors

/**
 * 独立页面（非 `MainShell` 承载的 Activity）的「壁纸 + 液态玻璃」宿主。
 *
 * <h3>为什么需要它</h3>
 * 玻璃透镜的折射来自 [LocalGlassBackdrop] 的录制层，而它此前只由 `MainShell` 提供。
 * 图库页、全屏看图都是独立 Activity：不接采样源的话，`LensGlass` 会退化成
 * 纯半透明面板（[LensGlass] 文档里的 `emptyBackdrop()` 分支），写了玻璃却没有折射。
 *
 * <h3>结构：内容进采样源，浮层在源外</h3>
 * ```
 * Box(glassScrollMotion)          ← 旁听每个被滚容器消费的位移，逐帧重录
 * ├── Box(glassBackdropSource)    ← 采样源子树：壁纸 + content()
 * │   ├── 壁纸（或纯色基底）
 * │   └── content()               ← 会被玻璃折射的滚动内容
 * └── overlay()                   ← 顶栏 / 底栏等浮层，必须在源外，否则玻璃采到自己（残影）
 * ```
 * 因此 `content` 里**不要**放 `LensGlass` 系控件，玻璃只能出现在 `overlay`。
 *
 * <h3>壁纸来源</h3>
 * 复用 [AppBackground]（与主界面外壳同一套「解码 → 淡化 → 掺合」管线）：
 * 后台线程算出全不透明位图，直接由 Compose 快照状态发布（快照状态允许任意线程写入，
 * 不需要回主线程）。无壁纸或权限不足时回退主题底色，玻璃仍有磨砂但折射极弱。
 */
@Composable
fun VenusBackdropHost(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val backdrops = rememberGlassBackdrops()
    // 底色/主题色必须在主线程解析（后台读主题属性不安全），且要能容忍 Preview 缺主题属性
    val baseColor = remember(context) {
        Color(
            runCatching {
                MaterialColors.getColor(context, android.R.attr.colorBackground, android.graphics.Color.BLACK)
            }.getOrDefault(android.graphics.Color.BLACK)
        )
    }
    val surfaceColor = remember(context) {
        runCatching {
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurface,
                android.graphics.Color.BLACK,
            )
        }.getOrDefault(android.graphics.Color.BLACK)
    }

    var wallpaper by remember { mutableStateOf<Bitmap?>(null) }

    // 首帧后读设置并异步合成；设置页改了壁纸重进本页即生效（无需监听 prefs）
    LaunchedEffect(context) {
        val uri = runCatching { SettingsFragment.getBackgroundImageUri(context) }.getOrNull()
            ?: return@LaunchedEffect
        val preset = runCatching { SettingsFragment.getBackgroundPreset(context) }.getOrDefault(1)
        val alpha = runCatching { SettingsFragment.getBackgroundAlpha(context) }.getOrDefault(1f)
        AppExecutors.get().execute {
            // 失败（权限/解码）时保持 null → 纯色基底，不打断页面
            wallpaper = runCatching {
                AppBackground.loadComposited(context, uri, preset, alpha, surfaceColor)
            }.getOrNull()
        }
    }

    // 壁纸发布不经滚动信号，必须显式失效采样源，否则玻璃仍按旧底图折射
    LaunchedEffect(wallpaper) {
        if (wallpaper != null) backdrops.content.requestCapture()
    }

    CompositionLocalProvider(
        // 页内透镜默认取内容源（本宿主只有一个采样源：壁纸 + 页面内容）
        LocalGlassBackdrop provides backdrops.content,
        // glassScrollMotion 的旁听目标
        LocalGlassContentBackdrop provides backdrops.content,
    ) {
        Box(modifier = modifier.fillMaxSize().glassScrollMotion()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .glassBackdropSource(backdrops.content)
                    .background(baseColor),
            ) {
                wallpaper?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                content()
            }
            overlay()
        }
    }
}
