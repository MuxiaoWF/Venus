package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.DailySettingPane
import com.muxiao.Venus.common.ui.compose.GameDailySettingPane
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：米游币签到 / 游戏签到两个抽屉的 Compose 版行为等价性
 * （Chip 按下标派发勾选、国际服隐藏位生效）。
 */
class SignInPanesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dailyPaneDispatchesChipToggleAtCorrectIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var toggledIndex = -1
        var toggledValue = false

        composeRule.setContent {
            VenusTheme {
                DailySettingPane(
                    switchChecked = true,
                    onSwitchChange = {},
                    chipChecked = List(8) { false },
                    onChipToggle = { index, checked ->
                        toggledIndex = index
                        toggledValue = checked
                    },
                )
            }
        }

        // 「原神」是下标 0 的 Chip
        composeRule.onNodeWithText(context.getString(R.string.genshin_impact)).performClick()

        assertEquals(0, toggledIndex)
        assertEquals(true, toggledValue)
    }

    @Test
    fun gameDailyPaneHidesOverseaUnsupportedChips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            VenusTheme {
                GameDailySettingPane(
                    switchChecked = true,
                    onSwitchChange = {},
                    chipChecked = List(6) { false },
                    onChipToggle = { _, _ -> },
                    // 国际服：末两位（崩坏2 / 未定事件簿）隐藏
                    chipVisible = List(6) { it < 4 },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.honkai_impact_3)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.honkai_impact_2)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.tears_of_themis)).assertDoesNotExist()
    }
}
