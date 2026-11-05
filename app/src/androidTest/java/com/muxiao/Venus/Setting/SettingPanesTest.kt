package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.LanguageSettingPane
import com.muxiao.Venus.common.ui.compose.ServerSettingPane
import com.muxiao.Venus.common.ui.compose.UpdateSettingPane
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：语言 / 服务器类型 / 更新 三个抽屉的 Compose 版行为等价性
 * （文案可显示 + 回调按下标/布尔值正确派发）。
 */
class SettingPanesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun languagePaneDispatchesSelectedIndex() {
        var picked = -1
        val options = listOf("跟随系统", "简体中文", "繁體中文", "English")
        composeRule.setContent {
            VenusTheme {
                LanguageSettingPane(options = options, selectedIndex = 0, onSelect = { picked = it })
            }
        }

        // 展开下拉后点第二项
        composeRule.onNodeWithText(options[0]).performClick()
        composeRule.onNodeWithText(options[2]).assertIsDisplayed().performClick()

        assertEquals(2, picked)
    }

    @Test
    fun serverPaneDispatchesSelectedIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var picked = -1
        composeRule.setContent {
            VenusTheme {
                ServerSettingPane(selectedIndex = 0, onSelect = { picked = it })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.server_os)).performClick()

        assertEquals(1, picked)
    }

    @Test
    fun updatePaneDispatchesSwitchAndBothButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var switchValue: Boolean? = null
        var github = 0
        var pan = 0
        composeRule.setContent {
            VenusTheme {
                UpdateSettingPane(
                    autoUpdateChecked = true,
                    onAutoUpdateChange = { switchValue = it },
                    onCheckGithubClick = { github++ },
                    onCheckPanClick = { pan++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.check_update_github)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.check_update_pan)).performClick()

        // 总开关未被点击：回调不应被误触发
        assertEquals(null, switchValue)
        assertEquals(1, github)
        assertEquals(1, pan)
    }
}
