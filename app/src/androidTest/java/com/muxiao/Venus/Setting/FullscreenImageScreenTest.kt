package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.FullscreenImageScreen
import com.muxiao.Venus.common.ui.compose.FullscreenPage
import com.muxiao.Venus.common.ui.compose.FullscreenUiState
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：Compose 版全屏图片页（原 `activity_fullscreen_image.xml` + `ImagePagerAdapter`）。
 *
 * 页面数据传 `imageUrl = null`，避免测试进程发起真实网络请求；
 * 此时每页走「无图」分支显示错误图标，但标题 / 作者 / 计数 / 下载入口的渲染逻辑与真实数据一致。
 */
class FullscreenImageScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pages() = listOf(
        FullscreenPage(null, "作品甲", "作者甲", "2026-11-05 12:00:00", "描述甲"),
        FullscreenPage(null, "作品甲", "作者甲", "2026-11-05 12:00:00", "描述甲"),
    )

    @Test
    fun showsTitleAuthorAndCounter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = FullscreenUiState()
        state.pages = pages()

        composeRule.setContent {
            VenusTheme {
                FullscreenImageScreen(
                    state = state,
                    initialPage = 0,
                    onBack = {},
                    onDownload = {},
                    onPageChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("作品甲").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.image_author_prefix) + "作者甲")
            .assertIsDisplayed()
        // 两页时计数为 1/2
        composeRule.onNodeWithText("1/2").assertIsDisplayed()
    }

    @Test
    fun downloadButtonDispatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = FullscreenUiState()
        state.pages = pages()
        var downloads = 0

        composeRule.setContent {
            VenusTheme {
                FullscreenImageScreen(
                    state = state,
                    initialPage = 0,
                    onBack = {},
                    onDownload = { downloads++ },
                    onPageChanged = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.download_image))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun reportsInitialPage() {
        val state = FullscreenUiState()
        state.pages = pages()
        var reported = -1

        composeRule.setContent {
            VenusTheme {
                FullscreenImageScreen(
                    state = state,
                    initialPage = 1,
                    onBack = {},
                    onDownload = {},
                    onPageChanged = { reported = it },
                )
            }
        }

        // 初始页 1 → 计数 2/2，且首帧即回调给宿主（下载要按当前页取图）
        composeRule.onNodeWithText("2/2").assertIsDisplayed()
        assertEquals(1, reported)
    }
}
