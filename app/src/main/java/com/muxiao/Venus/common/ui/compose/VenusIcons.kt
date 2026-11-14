package com.muxiao.Venus.common.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Venus 图标集：由 res/drawable 下的 VectorDrawable 机械迁移而来（scripts/vector_to_compose.py
 * 直接读 XML 转换，路径/线宽/端帽与原文件一一对应，视觉零变化）。
 *
 * 颜色约定：builder 内一律白色占位——`Icon(imageVector, tint = ...)` 以 SrcIn 整体染色，
 * 调用方显式给 tint（一般取 MaterialTheme.colorScheme.*），attr 链（?attr / @color selector）就此消失。
 *
 * 注意：原 ic_arrow_back 带 autoMirrored，ImageVector 无此机制，RTL 下不再自动镜像
 * （本应用以中文为主，可忽略）。
 *
 * 仍留在 res/drawable 的（系统/RemoteViews 消费，Compose 无关）：
 * ic_brand（48dp 主题色）、ic_splash（启动屏动画）、bg_widget / bg_widget_button（小组件）、
 * ic_error / ic_link / ic_notification（通知图标，颜色已固化白色；Compose 侧用本文件同名图标）。
 */
object VenusIcons {


    /** ← ic_arrow_back.xml */
    val ArrowBack: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.ArrowBack",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineTo(13.42f, 5.41f)
                lineTo(12f, 4f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                lineTo(13.41f, 18.59f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineTo(11f)
                close()
            }
        }.build()
    }

    /** ← ic_check.xml */
    val Check: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Check",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 1024f, viewportHeight = 1024f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(468.7f, 676.4f)
                arcToRelative(29f, 29f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -20.5f,
                    dy1 = -8.5f
                )
                lineTo(264.1f, 485f)
                arcToRelative(29.1f, 29.1f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 41f,
                    dy1 = -41.3f
                )
                lineTo(468.7f, 606.6f)
                lineTo(730.3f, 346.2f)
                arcToRelative(29.1f, 29.1f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 41f,
                    dy1 = 41.3f
                )
                lineTo(488.7f, 667.9f)
                arcToRelative(29f, 29f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -20f,
                    dy1 = 8.5f
                )
                close()
            }
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(512f, 1000.7f)
                arcToRelative(488.7f, 488.7f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 488.7f,
                    dy1 = -488.7f
                )
                arcToRelative(488.7f, 488.7f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -488.7f,
                    dy1 = 488.7f
                )
                close()
                moveTo(512f, 81.5f)
                arcToRelative(430.5f, 430.5f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = false,
                    dx1 = 430.5f,
                    dy1 = 430.5f
                )
                arcTo(430.5f, 430.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 512f,
                    y1 = 81.5f
                )
                close()
            }
        }.build()
    }

    /** ← ic_chevron.xml */
    val ChevronDown: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.ChevronDown",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(12f, 15.75f)
                lineTo(7.4f, 11.15f)
                curveTo(7.01f, 10.76f, 7.01f, 10.14f, 7.4f, 9.75f)
                curveTo(7.79f, 9.36f, 8.41f, 9.36f, 8.8f, 9.75f)
                lineTo(12f, 13f)
                lineTo(15.2f, 9.75f)
                curveTo(15.59f, 9.36f, 16.21f, 9.36f, 16.6f, 9.75f)
                curveTo(16.99f, 10.14f, 16.99f, 10.76f, 16.6f, 11.15f)
                lineTo(12f, 15.75f)
                close()
            }
        }.build()
    }

    /** ← ic_chevrons_up_down.xml */
    val ChevronsUpDown: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.ChevronsUpDown",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 15f)
                lineTo(12f, 20f)
                lineTo(17f, 15f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 9f)
                lineTo(12f, 4f)
                lineTo(17f, 9f)
            }
        }.build()
    }

    /** ← ic_cloud.xml */
    val Cloud: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Cloud",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
                horizontalLineTo(19f)
                curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
                curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
                close()
                moveTo(19f, 18f)
                lineTo(6f, 18f)
                curveTo(3.79f, 18f, 2f, 16.21f, 2f, 14f)
                curveTo(2f, 11.79f, 3.79f, 10f, 6f, 10f)
                horizontalLineTo(6.71f)
                curveTo(7.37f, 7.69f, 9.48f, 6f, 12f, 6f)
                curveTo(15.04f, 6f, 17.5f, 8.46f, 17.5f, 11.5f)
                verticalLineTo(12f)
                lineTo(19f, 12f)
                curveTo(20.66f, 12f, 22f, 13.34f, 22f, 15f)
                curveTo(22f, 16.66f, 20.66f, 18f, 19f, 18f)
                close()
            }
        }.build()
    }

    /** ← ic_copy.xml */
    val Copy: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Copy",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(16f, 1f)
                horizontalLineTo(4f)
                curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
                verticalLineTo(17f)
                horizontalLineTo(4f)
                verticalLineTo(3f)
                horizontalLineTo(16f)
                verticalLineTo(1f)
                close()
                moveTo(19f, 5f)
                horizontalLineTo(8f)
                curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
                verticalLineTo(21f)
                curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
                horizontalLineTo(19f)
                curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
                verticalLineTo(7f)
                curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
                close()
                moveTo(19f, 21f)
                horizontalLineTo(8f)
                verticalLineTo(7f)
                horizontalLineTo(19f)
                verticalLineTo(21f)
                close()
            }
        }.build()
    }

    /** ← ic_dot.xml */
    val Dot: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Dot",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(12f, 12f)
                moveTo(7f, 12f)
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 10f,
                    dy1 = 0f
                )
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = -10f,
                    dy1 = 0f
                )
            }
        }.build()
    }

    /** ← ic_download.xml */
    val Download: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Download",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 15f)
                verticalLineTo(3f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 15f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = 2f
                )
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = -2f
                )
                verticalLineTo(15f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 10f)
                lineTo(12f, 15f)
                lineTo(17f, 10f)
            }
        }.build()
    }

    /** ← ic_error.xml */
    val Error: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Error",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 20f,
                    dy1 = 0f
                )
                arcToRelative(10f, 10f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = -20f,
                    dy1 = 0f
                )
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 9f)
                lineTo(9f, 15f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 9f)
                lineTo(15f, 15f)
            }
        }.build()
    }

    /** ← ic_home.xml */
    val Home: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Home",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 21f)
                verticalLineTo(13f)
                arcToRelative(1f, 1f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -1f,
                    dy1 = -1f
                )
                horizontalLineTo(10f)
                arcToRelative(1f, 1f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -1f,
                    dy1 = 1f
                )
                verticalLineTo(21f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 10f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = 0.709f,
                    dy1 = -1.528f
                )
                lineTo(10.709f, 2.473f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = 2.582f,
                    dy1 = 0f
                )
                lineTo(20.291f, 8.472f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 10f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = 2f
                )
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = -2f
                )
                close()
            }
        }.build()
    }

    /** ← ic_image.xml */
    val Image: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Image",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(21f, 19f)
                verticalLineTo(5f)
                curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
                horizontalLineTo(5f)
                curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
                verticalLineTo(19f)
                curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                horizontalLineTo(19f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                close()
                moveTo(8.5f, 13.5f)
                lineTo(11f, 16.51f)
                lineTo(14.5f, 12f)
                lineTo(19f, 18f)
                horizontalLineTo(5f)
                lineTo(8.5f, 13.5f)
                close()
            }
        }.build()
    }

    /** ← ic_link.xml */
    val Link: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Link",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 13f)
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 7.54f,
                    dy1 = 0.54f
                )
                lineTo(20.54f, 10.54f)
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -7.07f,
                    dy1 = -7.07f
                )
                lineTo(11.75f, 5.18f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14f, 11f)
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -7.54f,
                    dy1 = -0.54f
                )
                lineTo(3.46f, 13.46f)
                arcToRelative(5f, 5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 7.07f,
                    dy1 = 7.07f
                )
                lineTo(12.24f, 18.82f)
            }
        }.build()
    }

    /** ← ic_link_url.xml */
    val LinkUrl: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.LinkUrl",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 13f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = 2f
                )
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2f,
                    dy1 = -2f
                )
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = 2f,
                    dy1 = -2f
                )
                horizontalLineTo(11f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 3f)
                lineTo(12f, 12f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(9f)
            }
        }.build()
    }

    /** ← ic_loading.xml */
    val Loading: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Loading",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 2f)
                verticalLineTo(6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16.2f, 7.8f)
                lineTo(19.1f, 4.9f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(18f, 12f)
                horizontalLineTo(22f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16.2f, 16.2f)
                lineTo(19.1f, 19.1f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 18f)
                verticalLineTo(22f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.9f, 19.1f)
                lineTo(7.8f, 16.2f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2f, 12f)
                horizontalLineTo(6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.9f, 4.9f)
                lineTo(7.8f, 7.8f)
            }
        }.build()
    }

    /** ← ic_notification.xml */
    val NotificationBell: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.NotificationBell",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 22f)
                curveTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
                horizontalLineTo(10f)
                curveTo(10f, 21.1f, 10.9f, 22f, 12f, 22f)
                close()
                moveTo(18f, 16f)
                verticalLineTo(11f)
                curveTo(18f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f)
                lineTo(13.5f, 4f)
                curveTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f)
                curveTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f)
                verticalLineTo(4.68f)
                curveTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f)
                verticalLineTo(16f)
                lineTo(4f, 18f)
                verticalLineTo(19f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                lineTo(18f, 16f)
                close()
            }
        }.build()
    }

    /** ← ic_pending.xml */
    val Pending: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Pending",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 1024f, viewportHeight = 1024f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(114.6f, 951f)
                horizontalLineTo(412.8f)
                verticalLineTo(879.3f)
                horizontalLineTo(186.3f)
                verticalLineTo(131.8f)
                horizontalLineTo(780.2f)
                verticalLineTo(403.2f)
                horizontalLineTo(851.9f)
                verticalLineTo(60.2f)
                horizontalLineTo(114.6f)
                verticalLineTo(951.1f)
                close()
            }
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(662.4f, 311f)
                horizontalLineTo(304f)
                verticalLineTo(239.3f)
                horizontalLineTo(662.4f)
                verticalLineTo(311f)
                close()
                moveTo(508.8f, 490.2f)
                horizontalLineTo(304f)
                verticalLineTo(418.5f)
                horizontalLineTo(508.8f)
                verticalLineTo(490.2f)
                close()
                moveTo(668.8f, 554.2f)
                arcToRelative(169f, 169f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = false,
                    dx1 = 0f,
                    dy1 = 337.9f
                )
                arcToRelative(169f, 169f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 0f,
                    dy1 = -337.9f
                )
                close()
                moveTo(428.2f, 723.2f)
                arcToRelative(240.6f, 240.6f, 0f, isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 481.3f,
                    dy1 = 0f
                )
                arcToRelative(240.6f, 240.6f, 0f, isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -481.3f,
                    dy1 = 0f
                )
                close()
            }
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(629.8f, 588.8f)
                horizontalLineTo(701.5f)
                verticalLineTo(720.2f)
                lineTo(784.1f, 761.5f)
                lineTo(752f, 825.6f)
                lineTo(629.8f, 764.5f)
                verticalLineTo(588.8f)
                close()
            }
        }.build()
    }

    /** ← ic_play.xml */
    val Play: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(8f, 5f)
                verticalLineTo(19f)
                lineTo(19f, 12f)
                close()
            }
        }.build()
    }

    /** ← ic_refresh.xml */
    val Refresh: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Refresh",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 3f)
                curveTo(14.39f, 3f, 16.68f, 3.94f, 18.4f, 5.6f)
                lineTo(21f, 8f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 3f)
                lineTo(21f, 8f)
                lineTo(16f, 8f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 21f)
                curveTo(9.61f, 21f, 7.32f, 20.06f, 5.6f, 18.4f)
                lineTo(3f, 16f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 21f)
                lineTo(3f, 16f)
                lineTo(8f, 16f)
            }
        }.build()
    }

    /** ← ic_schedule.xml */
    val Schedule: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Schedule",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
            ) {
                moveTo(11.99f, 2f)
                curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.47f, 22f, 11.99f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 11.99f, 2f)
                close()
                moveTo(12f, 20f)
                curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
                curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
                curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
                curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
                close()
                moveTo(12.5f, 7f)
                horizontalLineTo(11f)
                verticalLineTo(13f)
                lineTo(16.25f, 16.15f)
                lineTo(17f, 14.92f)
                lineTo(12.5f, 12.25f)
                close()
            }
        }.build()
    }

    /** ← ic_settings.xml */
    val Settings: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Settings",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.671f, 4.136f)
                arcToRelative(2.34f, 2.34f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = 4.659f,
                    dy1 = 0f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 3.319f,
                    dy1 = 1.915f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = 2.33f,
                    dy1 = 4.033f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 0f,
                    dy1 = 3.831f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2.33f,
                    dy1 = 4.033f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -3.319f,
                    dy1 = 1.915f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -4.659f,
                    dy1 = 0f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -3.32f,
                    dy1 = -1.915f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = true,
                    dx1 = -2.33f,
                    dy1 = -4.033f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 0f,
                    dy1 = -3.831f
                )
                arcTo(2.34f, 2.34f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 6.35f,
                    y1 = 6.051f
                )
                arcToRelative(2.34f, 2.34f, 0f, isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = 3.319f,
                    dy1 = -1.915f
                )
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                moveTo(9f, 12f)
                arcToRelative(3f, 3f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 6f,
                    dy1 = 0f
                )
                arcToRelative(3f, 3f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = -6f,
                    dy1 = 0f
                )
            }
        }.build()
    }

    /** ← ic_sparkles.xml */
    val Sparkles: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Sparkles",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.937f, 15.5f)
                arcTo(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 8.5f,
                    y1 = 14.063f
                )
                lineTo(2.365f, 12.481f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 2.365f,
                    y1 = 11.519f
                )
                lineTo(8.5f, 9.936f)
                arcTo(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 9.937f,
                    y1 = 8.5f
                )
                lineTo(11.519f, 2.365f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 12.481f,
                    y1 = 2.365f
                )
                lineTo(14.063f, 8.5f)
                arcTo(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 15.5f,
                    y1 = 9.937f
                )
                lineTo(21.635f, 11.518f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 21.635f,
                    y1 = 12.482f
                )
                lineTo(15.5f, 14.063f)
                arcTo(2f, 2f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 14.063f,
                    y1 = 15.5f
                )
                lineTo(12.481f, 21.635f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 11.519f,
                    y1 = 21.635f
                )
                close()
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20f, 3f)
                lineTo(20f, 7f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(22f, 5f)
                lineTo(18f, 5f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 17f)
                lineTo(4f, 19f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 18f)
                lineTo(3f, 18f)
            }
        }.build()
    }

    /** ← ic_strike_cross.xml */
    val StrikeCross: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.StrikeCross",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 3f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 5f)
                lineTo(19f, 19f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 3f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 5f)
                lineTo(5f, 19f)
            }
        }.build()
    }

    /** ← ic_task_error.xml */
    val TaskError: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.TaskError",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }.build()
    }

    /** ← ic_trash.xml */
    val Trash: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Trash",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, 6f)
                lineTo(21f, 6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 6f)
                lineTo(19f, 20f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 22f)
                lineTo(7f, 22f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 20f)
                lineTo(5f, 6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8f, 6f)
                lineTo(8f, 4f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 10f, y1 = 2f)
                lineTo(14f, 2f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 4f)
                lineTo(16f, 6f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 11f)
                lineTo(10f, 17f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14f, 11f)
                lineTo(14f, 17f)
            }
        }.build()
    }

    /** ← ic_user.xml */
    val User: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.User",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 15f)
                horizontalLineTo(6f)
                arcToRelative(4f, 4f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    dx1 = -4f,
                    dy1 = 4f
                )
                verticalLineTo(21f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14.305f, 16.53f)
                lineTo(15.228f, 16.148f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15.228f, 13.852f)
                lineTo(14.305f, 13.469f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16.852f, 12.228f)
                lineTo(16.469f, 11.305f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16.852f, 17.772f)
                lineTo(16.469f, 18.696f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19.148f, 12.228f)
                lineTo(19.531f, 11.305f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19.53f, 18.696f)
                lineTo(19.148f, 17.772f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20.772f, 13.852f)
                lineTo(21.696f, 13.469f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20.772f, 16.148f)
                lineTo(21.696f, 16.531f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(18f, 15f)
                moveTo(15f, 15f)
                arcToRelative(3f, 3f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 6f,
                    dy1 = 0f
                )
                arcToRelative(3f, 3f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = -6f,
                    dy1 = 0f
                )
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 7f)
                moveTo(5f, 7f)
                arcToRelative(4f, 4f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = 8f,
                    dy1 = 0f
                )
                arcToRelative(4f, 4f, 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    dx1 = -8f,
                    dy1 = 0f
                )
            }
        }.build()
    }

    /** ← ic_user_plus.xml */
    val UserPlus: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.UserPlus",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2f, 21f)
                arcTo(8f, 8f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 15.292f,
                    y1 = 15f
                )
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 13f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 10f, y1 = 3f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 10f, y1 = 13f)
                close()
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 16f)
                lineTo(19f, 22f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(22f, 19f)
                lineTo(16f, 19f)
            }
        }.build()
    }

    /** ← ic_zap.xml */
    val Zap: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Venus.Zap",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 14f)
                arcTo(1f, 1f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 3.22f,
                    y1 = 12.37f
                )
                lineTo(13.12f, 2.17f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 13.98f,
                    y1 = 2.63f
                )
                lineTo(12.06f, 8.65f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 13f, y1 = 10f)
                lineTo(20f, 10f)
                arcTo(1f, 1f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 20.78f,
                    y1 = 11.63f
                )
                lineTo(10.88f, 21.83f)
                arcTo(0.5f, 0.5f, 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 10.02f,
                    y1 = 21.37f
                )
                lineTo(11.94f, 15.35f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 11f, y1 = 14f)
                close()
            }
        }.build()
    }

}
