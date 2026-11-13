package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.AboutSettingPane
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 「关于」抽屉：版本文案可显示 + 两个外链各自派发点击。 */
class AboutSettingPaneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsVersionAndDispatchesBothLinkClicks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var githubClicks = 0
        var blogClicks = 0

        composeRule.setContent {
            VenusTheme {
                AboutSettingPane(
                    appVersion = "0.0.8",
                    onGithubClick = { githubClicks++ },
                    onBlogClick = { blogClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("0.0.8").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.github_source)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.my_blog)).performClick()

        assertEquals(1, githubClicks)
        assertEquals(1, blogClicks)
    }
}
