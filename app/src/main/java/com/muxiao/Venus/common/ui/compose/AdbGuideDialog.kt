package com.muxiao.Venus.common.ui.compose

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.muxiao.Venus.R

/**
 * ADB 抓取引导：点击 ADB 行后先展示步骤，确认后再进入配对流程。
 * 用 ComponentDialog 承载 ComposeView，保持与 VenusDialogs 一致的主题和生命周期行为。
 */
object AdbGuideDialog {

    @JvmStatic
    fun show(activity: Activity, onStart: Runnable) {
        val dialog = ComponentDialog(activity)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val compose = ComposeView(activity)
        compose.setContent {
            VenusTheme { Content(onDismiss = { dialog.dismiss() }, onStart = { onStart.run(); dialog.dismiss() }) }
        }
        dialog.setContentView(compose)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    @Composable
    private fun Content(onDismiss: () -> Unit, onStart: () -> Unit) {
        val steps = listOf(
            stringResource(R.string.adb_guide_step1),
            stringResource(R.string.adb_guide_step2),
            stringResource(R.string.adb_guide_step3),
            stringResource(R.string.adb_guide_step4),
            stringResource(R.string.adb_guide_step5),
            stringResource(R.string.adb_guide_step6),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.adb_guide_title), style = LocalVenusTextStyles.current.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_2)),
                ) {
                    Text(
                        stringResource(R.string.adb_guide_intro),
                        style = LocalVenusTextStyles.current.body,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.adb_guide_warning),
                            style = LocalVenusTextStyles.current.bodySmall,
                            modifier = Modifier.padding(dimensionResource(R.dimen.space_2)),
                        )
                    }
                    Text(
                        stringResource(R.string.adb_guide_steps_title),
                        style = LocalVenusTextStyles.current.labelLarge,
                    )
                    steps.forEachIndexed { index, step ->
                        StepItem(number = index + 1, text = step)
                        if (index < steps.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_2)),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = onStart) { Text(stringResource(R.string.adb_guide_start)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    @Composable
    private fun StepItem(number: Int, text: String) {
        val parts = text.split("\n", limit = 2)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "$number. ${parts[0]}",
                style = LocalVenusTextStyles.current.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (parts.size > 1) {
                Text(
                    text = parts[1],
                    style = LocalVenusTextStyles.current.bodySmall,
                )
            }
        }
    }
}
