package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.CacheSettingPane
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：确保 Compose 版「缓存管理」抽屉与原 XML 行为等价
 * （文案可显示 + 「清理缓存」点击回调能触发）。
 */
class CacheSettingPaneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsCacheSizeTextAndDispatchesClearClick() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clearText = context.getString(R.string.clear_cache)
        var clicks = 0

        composeRule.setContent {
            VenusTheme {
                CacheSettingPane(cacheSize = "128 MB", onClearClick = { clicks++ })
            }
        }

        composeRule.onNodeWithText("128 MB").assertIsDisplayed()
        composeRule.onNodeWithText(clearText).assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }
}
