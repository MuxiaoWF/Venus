package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.muxiao.Venus.R

/**
 * 设置页「缓存管理」抽屉内容（原 res/layout/item_setting_cache.xml）。
 *
 * 结构等价于原 XML：竖向排列的「缓存占用文本 + 全宽 tonal 按钮」，
 * 外层间距沿用 `Widget.Venus.DrawerPane`（左右 space_4、上下 gap_row），
 * 按钮沿用 `Widget.Venus.Button.Tonal`（M3 tonal 配色 + 胶囊圆角）。
 *
 * @param cacheSize 已本地化并格式化好的缓存占用文案（由 Java 侧计算后回填）
 * @param onClearClick 点击「清理缓存」回调
 */
@Composable
fun CacheSettingPane(
    cacheSize: String,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimensionResource(R.dimen.space_4),
                end = dimensionResource(R.dimen.space_4),
                top = dimensionResource(R.dimen.gap_row),
                bottom = dimensionResource(R.dimen.gap_row),
            ),
    ) {
        Text(text = cacheSize, style = textStyles.drawerLabel)
        Spacer(Modifier.height(dimensionResource(R.dimen.space_2)))
        Button(
            onClick = onClearClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.touch_target_min)),
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
            Text(text = stringResource(R.string.clear_cache))
        }
    }
}

/**
 * [CacheSettingPane] 的 View 互操作宿主：把 Compose 内容塞进既有 View 体系。
 *
 * 之所以要有这个类：`CollapsibleCardView.setContent(...)` 是 View 侧入口，
 * 而 `ComposeView.setContent { }` 的 lambda 无法从 Java 直接构造，
 * 故用 Kotlin 封装一层 Java 友好的状态与回调 API。
 *
 * 注：Compose 1.12 起 `ComposeView` 为 final，无法继承，故直接实现 [AbstractComposeView]。
 */
class CacheSettingPaneView(context: Context) : AbstractComposeView(context) {

    private val cacheSizeState = mutableStateOf("")
    private var onClearClickListener: Runnable? = null

    /** 缓存占用文案；Java 侧 setCacheSize(...) 直接触发重组。 */
    var cacheSize: String
        get() = cacheSizeState.value
        set(value) {
            cacheSizeState.value = value
        }

    @Composable
    override fun Content() {
        VenusTheme {
            CacheSettingPane(
                cacheSize = cacheSizeState.value,
                onClearClick = { onClearClickListener?.run() },
            )
        }
    }

    /** 清理缓存点击回调；传 null 取消。 */
    fun setOnClearClickListener(listener: Runnable?) {
        onClearClickListener = listener
    }
}

@Preview(name = "设置 · 缓存管理", showBackground = true)
@Composable
private fun CacheSettingPanePreview() {
    VenusTheme {
        CacheSettingPane(
            cacheSize = stringResource(R.string.current_cache_size),
            onClearClick = {},
        )
    }
}
