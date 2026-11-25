package com.muxiao.Venus.common.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R
import com.muxiao.Venus.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap

/**
 * 背景图裁剪页状态宿主：Java 侧（[com.muxiao.Venus.Setting.CropActivity]）下发已解码源图与
 * 裁剪框宽高比，Compose 侧完成手势（拖动 / 双指缩放）与结果回传。
 */
@Stable
class CropScreenUiState {
    /** 已解码（含 EXIF 方向修正）的源图；null 时显示加载态。 */
    var source: Bitmap? by mutableStateOf(null)

    /** 裁剪框宽高比（宽/高）：背景页=屏幕比例、头像页=1:1；<=0 时按源图自身比例兜底。 */
    var frameAspect by mutableFloatStateOf(0f)

    /** 圆形裁剪框（头像）：遮罩挖圆、描边为圆；输出仍为框内正方形位图（展示侧 circleCrop 同口径）。 */
    var circular by mutableStateOf(false)
}

/** Java 侧确认回调：SAM 接口（void 返回），Java 侧可直接用方法引用。 */
fun interface BitmapConsumer {
    fun accept(bitmap: Bitmap)
}

/** Java 侧回调宿主：取消 / 确认（回传裁剪结果 Bitmap）。 */
class CropScreenView(context: Context) : AbstractComposeView(context) {

    val state = CropScreenUiState()

    private var onCancelClickListener: Runnable? = null
    private var onConfirmListener: BitmapConsumer? = null

    /** 下发源图与裁剪框比例（setContentView 之前或之后均可，状态驱动重组合）。 */
    fun setSource(bitmap: Bitmap?, frameAspect: Float, circular: Boolean = false) {
        state.source = bitmap
        state.frameAspect = frameAspect
        state.circular = circular
    }

    fun setOnCancelClickListener(listener: Runnable?) {
        onCancelClickListener = listener
    }

    fun setOnConfirmListener(listener: BitmapConsumer?) {
        onConfirmListener = listener
    }

    @Composable
    override fun Content() {
        VenusTheme {
            CropScreen(
                state = state,
                onCancel = { onCancelClickListener?.run() },
                onConfirm = { bitmap -> onConfirmListener?.accept(bitmap) },
            )
        }
    }
}

/**
 * 背景图裁剪页：纯黑看图底 + 居中固定比例裁剪框 + 手势调整，替代原 UCrop 三方 Activity。
 *
 * 交互约束（对齐原 UCrop 配置）：比例锁定为屏幕比例（不可旋转/改比例）、输出不超过屏幕尺寸、
 * 图片必须始终覆盖裁剪框（不留黑边）。
 *
 * 输出：确认时按「裁剪框在源图上的映射区域」生成 Bitmap（尺寸 = 裁剪框像素尺寸，≤ 屏幕像素；
 * Java 侧落盘前会等比放大到整屏，见 CropActivity.saveAndFinish），
 * 交回 Java 侧统一压缩保存——与原「UCrop 写临时文件 → saveBackgroundImage 复制落盘」链路等价。
 */
@Composable
fun CropScreen(
    state: CropScreenUiState,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val bitmap = state.source
    val scope = rememberCoroutineScope()
    var cropping by remember { mutableStateOf(false) }

    // 变换状态（CropScreen 单作用域持有，确认裁剪时直接读取）
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 用户缩放（相对 baseScale，1 = 恰好覆盖）与图片中心偏移
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    val frame: Rect = remember(bitmap, boxSize, state.frameAspect) {
        frameRect(
            area = Size(boxSize.width.toFloat(), boxSize.height.toFloat()),
            bitmap = bitmap,
            aspect = state.frameAspect,
        )
    }
    val baseScale: Float = remember(frame, bitmap) {
        if (bitmap == null || frame.width <= 0f) 1f
        else max(frame.width / bitmap.width, frame.height / bitmap.height)
    }
    val s = baseScale * scale
    val borderWidth = dimensionResource(R.dimen.space_hairline)

    Column(
        modifier = modifier
            .fillMaxSize()
            // 看图口径用纯黑底（与全屏图片页一致），而非主题 surface
            .background(Color.Black),
    ) {
        // 顶栏：返回 + 标题
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
            GlassIconButton(
                icon = VenusIcons.ArrowBack,
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onCancel,
                tint = Color.White,
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.space_1)))
            Text(
                text = stringResource(R.string.toolbar_crop_image),
                style = textStyles.title,
                color = Color.White,
            )
        }

        // 裁剪区：手势 + 图片 + 遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { boxSize = it }
                .clipToBounds()
                .pointerInput(bitmap, frame) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (bitmap == null || frame.width <= 0f) return@detectTransformGestures
                        // 以手势质心为不动点缩放：offset' = c − (c − offset)·zoom（c 为质心相对区中心偏移）
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val c = centroid - center
                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                        val newOffset = Offset(
                            c.x - (c.x - offset.x) * zoom,
                            c.y - (c.y - offset.y) * zoom,
                        ) + pan
                        scale = newScale
                        offset = clampOffset(newOffset, baseScale * newScale, bitmap, frame)
                    }
                },
        ) {
            if (bitmap == null || frame.width <= 0f) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                // 位图按变换矩阵直接绘制（不用 Image 节点：节点会被 Box 约束钳制，
                // painter 又按 ContentScale.Fit 在节点内信箱摆放，导致渲染与钳制数学不一致、
                // 框内露出图外区域）。变换模型与 clampOffset/cropBitmap 完全同源：
                // 屏幕点 sp = center + offset + (p − bitmapCenter)·S
                Canvas(modifier = Modifier.matchParentSize()) {
                    val image = bitmap.asImageBitmap()
                    val bx = bitmap.width.toFloat()
                    val by = bitmap.height.toFloat()
                    val cx = size.width / 2f + offset.x
                    val cy = size.height / 2f + offset.y
                    withTransform({
                        translate(cx - bx * s / 2f, cy - by * s / 2f)
                        scale(s, s, pivot = Offset.Zero)
                    }) {
                        drawImage(image)
                    }
                    val dim = Color.Black.copy(alpha = 0.55f)
                    if (state.circular) {
                        // 圆形裁剪框（头像）：整区矩形 XOR 内切圆（even-odd）遮罩——圆外（含框角）全部变暗
                        val mask = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addOval(frame)
                        }
                        drawPath(mask, dim)
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = frame.width / 2f,
                            center = frame.center,
                            style = Stroke(width = borderWidth.toPx(), cap = StrokeCap.Butt),
                        )
                    } else {
                        // 方形裁剪框：上、下、左、右四块遮罩（不引入 saveLayer/混合模式）
                        drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, frame.top))
                        drawRect(dim, topLeft = Offset(0f, frame.bottom), size = Size(size.width, size.height - frame.bottom))
                        drawRect(dim, topLeft = Offset(0f, frame.top), size = Size(frame.left, frame.height))
                        drawRect(dim, topLeft = Offset(frame.right, frame.top), size = Size(size.width - frame.right, frame.height))
                        drawRect(
                            color = Color.White.copy(alpha = 0.85f),
                            topLeft = frame.topLeft,
                            size = frame.size,
                            style = Stroke(width = borderWidth.toPx(), cap = StrokeCap.Butt),
                        )
                        val hairline = 1.dp.toPx()
                        for (i in 1..2) {
                            val fx = frame.left + frame.width * i / 3f
                            val fy = frame.top + frame.height * i / 3f
                            drawLine(Color.White.copy(alpha = 0.3f), Offset(fx, frame.top), Offset(fx, frame.bottom), hairline)
                            drawLine(Color.White.copy(alpha = 0.3f), Offset(frame.left, fy), Offset(frame.right, fy), hairline)
                        }
                    }
                }
            }
        }

        // 底栏：取消 / 确认
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = dimensionResource(R.dimen.gutter_screen),
                    vertical = dimensionResource(R.dimen.space_3),
                ),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2), Alignment.End),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !cropping,
            ) {
                Text(
                    text = stringResource(R.string.btn_cancel),
                    style = textStyles.body,
                    color = Color.White,
                )
            }
            Button(
                onClick = {
                    if (bitmap == null) return@Button
                    cropping = true
                    // 涉及整屏大位图绘制，放 Default 线程；结果回主线程交回 Java 侧
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { cropBitmap(bitmap, frame, baseScale * scale, offset, boxSize) }
                        }
                        cropping = false
                        result.onSuccess(onConfirm).onFailure {
                            Logger.w("背景图裁剪失败: " + it.message)
                        }
                    }
                },
                enabled = bitmap != null && !cropping,
            ) {
                Text(text = stringResource(R.string.btn_save))
            }
        }
    }
}

/** 图片必须覆盖裁剪框：offset 钳制在「图片超出裁剪框部分的一半」内。 */
private fun clampOffset(off: Offset, s: Float, bitmap: Bitmap?, frame: Rect): Offset {
    if (bitmap == null) return off
    val maxX = max(0f, (bitmap.width * s - frame.width) / 2f)
    val maxY = max(0f, (bitmap.height * s - frame.height) / 2f)
    return Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
}

/** 居中裁剪框：宽高比 aspect（宽/高），装不进时先限制高度再回推宽度。 */
private fun frameRect(area: Size, bitmap: Bitmap?, aspect: Float): Rect {
    if (area.width <= 0f || area.height <= 0f) return Rect.Zero
    val a = when {
        aspect > 0f -> aspect
        bitmap != null && bitmap.height > 0 -> bitmap.width / bitmap.height.toFloat()
        else -> return Rect.Zero
    }
    var w = min(area.width, area.height * a)
    var h = w / a
    if (h > area.height) {
        h = area.height
        w = h * a
    }
    val left = (area.width - w) / 2f
    val top = (area.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * 按当前变换把裁剪框内容裁成输出 Bitmap（尺寸 = 裁剪框像素尺寸，≤ 屏幕像素）。
 *
 * 映射推导：屏幕点 sp = center + offset + (p − bitmapCenter)·S（p 为源图像素坐标，S = baseScale·scale），
 * 输出像素 o = sp − frame.topLeft = (p − bt)·S ⇒ `matrix = setScale(S) + postTranslate(−bt)`，
 * 其中 bt 为裁剪框左上角在源图坐标系中的位置（采样区尺寸 = frame/S）。
 */
private fun cropBitmap(
    bitmap: Bitmap,
    frame: Rect,
    s: Float,
    offset: Offset,
    boxSize: IntSize,
): Bitmap {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val bt = RectF(
        bw / 2f + (frame.left - boxSize.width / 2f - offset.x) / s,
        bh / 2f + (frame.top - boxSize.height / 2f - offset.y) / s,
        0f, 0f,
    )
    val out = createBitmap(
        frame.width.roundToInt().coerceAtLeast(1),
        frame.height.roundToInt().coerceAtLeast(1),
    )
    // 映射 o = (p − bt)·s：平移必须经 preTranslate 作用于源坐标（先平移后缩放 ⇒ s·(p − bt)）。
    // 若用 postTranslate，平移落在输出空间（o = s·p − bt），仅当 bt≈0（源图与框同比例、未缩放未平移，
    // 如背景截图）时两者重合；源图远大于框（头像）且随手势缩放平移后 bt≠0，采样区会整体偏移，
    // 裁出框外区域（表现为「只有框的一角成为成品」）。
    // 注意缩放系数必须是 s 而非 1/s——写成 1/s 会把「框内 1/s 区域」拉伸成整幅输出，
    // 表现为输出相对预览整体放大约 1/s² 倍、手势后采样区越界（JPEG 下呈黑带）。
    val matrix = Matrix()
    matrix.setScale(s, s)
    matrix.preTranslate(-bt.left, -bt.top)
    // android.graphics.Canvas 全限定：与上方 androidx.compose.foundation.Canvas 组合式同名，须消歧
    android.graphics.Canvas(out).drawBitmap(bitmap, matrix, null)
    return out
}
