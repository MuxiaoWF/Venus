package com.muxiao.Venus.common.ui.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.res.ResourcesCompat
import com.muxiao.Venus.R

/**
 * 配置信息 KV 行的标签顺序（与 Java 侧 `displayCurrentConfigValues` 的下标契约一致）。
 */
private val CONFIG_KEY_LABEL_RES = intArrayOf(
    R.string.config_key_local_time,
    R.string.config_key_cloud_time,
    R.string.config_key_bbs_version,
    R.string.config_key_salt_6x,
    R.string.config_key_salt_4x,
    R.string.config_key_lk2,
    R.string.config_key_k2,
)

/**
 * 设置页「配置信息」抽屉内容（原 res/layout/item_setting_bbs_utils.xml）。
 *
 * 结构：说明副文本 + 7 行「标签左 / 等宽值右对齐」KV + 全宽表面行按钮（更新配置）。
 *
 * @param values 7 个配置值（顺序见 [CONFIG_KEY_LABEL_RES]）
 * @param onUpdateClick 点击「更新配置」
 */
@Composable
fun BbsUtilsSettingPane(
    values: List<String>,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyles = LocalVenusTextStyles.current
    // 行按钮底是液态玻璃 layer-list（非 vector），painterResource 只认 vector / 位图，
    // 这里直接取 Drawable 在 drawBehind 里绘制，观感与原 MaterialButton 的 background 一致。
    // LocalResources 配置感知：语言/主题切换时重组并重取，避免 stale resources
    val resources = LocalResources.current
    val context = LocalContext.current
    val rowButtonBackground = remember(resources) {
        ResourcesCompat.getDrawable(resources, R.drawable.bg_glass_row_button, context.theme)
    }
    DrawerPane(modifier) {
        Text(
            text = stringResource(R.string.refresh_tip),
            style = textStyles.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        Text(
            text = stringResource(R.string.current_config_value),
            style = textStyles.drawerLabel,
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.space_1)))
        CONFIG_KEY_LABEL_RES.forEachIndexed { index, labelRes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = dimensionResource(R.dimen.space_2)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(labelRes), style = textStyles.drawerLabel)
                Spacer(Modifier.width(dimensionResource(R.dimen.space_3)))
                Text(
                    text = values.getOrElse(index) { "" },
                    style = textStyles.monoValue.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.space_3)))
        // 表面行按钮：沿用原 bg_glass_row_button 九宫底，保证与 XML 版观感一致
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.touch_target_min))
                .drawBehind {
                    val bg = rowButtonBackground ?: return@drawBehind
                    bg.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    bg.draw(drawContext.canvas.nativeCanvas)
                }
                .clickable(onClick = onUpdateClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(dimensionResource(R.dimen.space_2)))
                Text(
                    text = stringResource(R.string.update_config),
                    style = textStyles.body.copy(color = MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}

/** [BbsUtilsSettingPane] 的 View 互操作宿主。 */
class BbsUtilsSettingPaneView(context: Context) : AbstractComposeView(context) {

    private val valuesState = mutableStateListOf<String>().apply {
        repeat(CONFIG_KEY_LABEL_RES.size) { add("") }
    }
    private var onUpdateClickListener: Runnable? = null

    /** 按下标回填配置值（顺序见 Compose 侧 CONFIG_KEY_LABEL_RES）；写入 SnapshotStateList 即触发重组。 */
    fun setValue(index: Int, value: String) {
        if (index in valuesState.indices) valuesState[index] = value
    }

    @Composable
    override fun Content() {
        VenusTheme {
            BbsUtilsSettingPane(
                values = valuesState.toList(),
                onUpdateClick = { onUpdateClickListener?.run() },
            )
        }
    }

    fun setOnUpdateClickListener(listener: Runnable?) {
        onUpdateClickListener = listener
    }
}

@Preview(name = "设置 · 配置信息", showBackground = true)
@Composable
private fun BbsUtilsSettingPanePreview() {
    VenusTheme {
        BbsUtilsSettingPane(
            values = listOf("2026-11-05 08:00", "2026-11-04 22:10", "2.98.0", "abc123", "def456", "ghi789", "jkl012"),
            onUpdateClick = {},
        )
    }
}
