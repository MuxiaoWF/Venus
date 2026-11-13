package com.muxiao.Venus.common.ui.compose

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.muxiao.Venus.R

/**
 * 图库缩略图卡：缩略图（`image_card_height` 固定高 + centerCrop）+ 标题 / 作者 / 描述 /
 * 时间四行文本（字号走 `LocalVenusTextStyles` 同源刻度）；选中态用主色描边。
 *
 * @param imageUrl 首图地址（列表接口只取 `images[0]`）
 * @param description 描述（最多两行）
 * @param selected 是否处于多选选中态
 */
@Composable
fun ImageCard(
    imageUrl: String?,
    title: String,
    author: String,
    description: String,
    time: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    val stroke = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(dimensionResource(R.dimen.space_2)),
        shape = RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        // 抬升卡口径：elevation_level2
        elevation = CardDefaults.cardElevation(
            defaultElevation = dimensionResource(R.dimen.elevation_level2),
        ),
        border = if (selected) {
            BorderStroke(
                width = dimensionResource(R.dimen.stroke_width_focused),
                color = stroke,
            )
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_3))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.image_card_height))
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.shape_corner_sm)))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                GlideThumbnail(
                    imageUrl = imageUrl,
                    modifier = Modifier.fillMaxSize(),
                )
                // 选中徽标：仅靠描边，缩略图占满时几乎看不出是否已选
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(dimensionResource(R.dimen.space_1))
                            .size(dimensionResource(R.dimen.icon_badge_sm))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(dimensionResource(R.dimen.icon_inline)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
            Text(
                text = title,
                style = textStyles.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            Text(
                text = author,
                style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 空文案不渲染 Text，但保留外边距，使缺描述/时间时卡片高度保持一致
            Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = textStyles.rowSubtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = textStyles.rowSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 用 Glide 加载缩略图：项目只有 Glide（没有 Coil），故用 `CustomTarget<Bitmap>` 转成
 * [androidx.compose.ui.graphics.ImageBitmap]；加载中显示 `ic_loading`，失败显示 `ic_error`。
 *
 * 不用 `AndroidView(ImageView)`：图库是两列网格，每项一个 View 会让滚动成本回到 View 体系。
 */
@Composable
private fun GlideThumbnail(imageUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(imageUrl) { mutableStateOf(false) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrEmpty()) {
            failed = true
            return@LaunchedEffect
        }
        Glide.with(context)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    bitmap = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    bitmap = null
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    failed = true
                }
            })
    }

    val loaded = bitmap
    when {
        loaded != null -> Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )

        else -> Image(
            painter = painterResource(if (failed) R.drawable.ic_error else R.drawable.ic_loading),
            contentDescription = null,
            modifier = modifier.padding(dimensionResource(R.dimen.space_6)),
        )
    }
}

@Preview(name = "图库卡片", showBackground = true, widthDp = 180)
@Composable
private fun ImageCardPreview() {
    VenusTheme {
        ImageCard(
            imageUrl = null,
            title = "标题示例",
            author = "@作者",
            description = "这是一段最多两行的描述文本，用来验证换行与省略号。",
            time = "2026-11-05",
            selected = false,
        )
    }
}
