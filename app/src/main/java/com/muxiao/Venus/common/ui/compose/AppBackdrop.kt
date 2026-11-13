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
 * 折射来自 [LocalGlassBackdrop] 的录制层，而它默认只由 `MainShell` 提供；图库、全屏看图等
 * 独立 Activity 若不接采样源，`LensGlass` 会退化成纯半透明面板（`emptyBackdrop()` 分支）。
 *
 * 结构：外层旁听滚动位移逐帧重录；`glassBackdropSource` 子树（壁纸 + content()）即采样源；
 * overlay() 是顶栏/底栏浮层，**必须在源外**，否则玻璃采到自己产生残影。故 `content` 里不要
 * 放 `LensGlass` 系控件，玻璃只能出现在 `overlay`。
 *
 * 壁纸复用 [AppBackground]（与主界面外壳同一套「解码 → 淡化 → 掺合」管线）：后台线程算出
 * 全不透明位图，直接由 Compose 快照状态发布（快照状态允许任意线程写入，无需切主线程）。
 * 无壁纸或权限不足时回退主题底色，玻璃仍有磨砂但折射极弱。
 */
@Composable
fun VenusBackdropHost(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val backdrops = rememberGlassBackdrops()
    // 主题属性必须在主线程解析（后台读不安全），且要容忍 Preview 缺属性
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
