package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.BG_PRESET_INDEX_RICH
import com.muxiao.Venus.common.ui.compose.BackgroundSettingPane
import com.muxiao.Venus.common.ui.compose.SKLAND_INDEX_ENDFIELD
import com.muxiao.Venus.common.ui.compose.SklandSettingPane
import com.muxiao.Venus.common.ui.compose.THEME_INDEX_GREEN
import com.muxiao.Venus.common.ui.compose.THEME_VARIANT_INDEX_DARK
import com.muxiao.Venus.common.ui.compose.ThemeSettingPane
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：主题 / 背景图片 / 森空岛 三个抽屉的 Compose 版行为等价性
 * （圆点与分段按下标派发、滑块按百分比派发、复选框按子项下标派发）。
 */
class ThemeBackgroundSklandPanesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themePaneDispatchesThemeDotAndVariantIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var pickedTheme = -1
        var pickedVariant = -1
        composeRule.setContent {
            VenusTheme {
                ThemeSettingPane(
                    selectedTheme = 0,
                    onThemeSelect = { pickedTheme = it },
                    selectedVariant = 0,
                    onVariantSelect = { pickedVariant = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.theme_green))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.theme_dark)).performClick()

        assertEquals(THEME_INDEX_GREEN, pickedTheme)
        assertEquals(THEME_VARIANT_INDEX_DARK, pickedVariant)
    }

    @Test
    fun backgroundPaneDispatchesPresetAndBothButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var pickedPreset = -1
        var select = 0
        var clear = 0
        composeRule.setContent {
            VenusTheme {
                BackgroundSettingPane(
                    presetIndex = 1,
                    onPresetSelect = { pickedPreset = it },
                    onSelectClick = { select++ },
                    onClearClick = { clear++ },
                    alphaPercent = 45f,
                    onAlphaChange = {},
                    onAlphaChangeFinished = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.select_image)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.clear_background)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.bg_preset_rich)).performClick()

        assertEquals(1, select)
        assertEquals(1, clear)
        assertEquals(BG_PRESET_INDEX_RICH, pickedPreset)
    }

    @Test
    fun sklandPaneDispatchesSwitchAndEndfieldSubItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var enabled: Boolean? = null
        var subIndex = -1
        var subChecked: Boolean? = null
        var save = 0
        composeRule.setContent {
            VenusTheme {
                SklandSettingPane(
                    enabled = true,
                    onEnabledChange = { enabled = it },
                    arknightsChecked = false,
                    endfieldChecked = false,
                    onSubItemChange = { index, checked ->
                        subIndex = index
                        subChecked = checked
                    },
                    onLoginLinkClick = {},
                    onCookieLinkClick = {},
                    onSaveClick = { save++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.endfield)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.save_skland_settings)).performClick()

        assertEquals(SKLAND_INDEX_ENDFIELD, subIndex)
        assertTrue(subChecked == true)
        assertEquals(1, save)
        // 总开关本次未触碰，回调不应被误触发
        assertEquals(null, enabled)
    }
}
