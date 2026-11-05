package com.muxiao.Venus.Setting

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.ImagePost
import com.muxiao.Venus.common.ui.compose.ImageScreen
import com.muxiao.Venus.common.ui.compose.ImageUiState
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：Compose 版图库页（原 `activity_image.xml` + `ImageAdapter`）。
 *
 * 覆盖原实现里由 Adapter/ActionBar 承担、迁移后改为「状态派生」的三件事：
 * 卡片点击分派、长按进入多选、多选态的标题与下载入口。
 *
 * 注：卡片传 `coverUrl = null`，避免测试进程发起真实网络请求。
 */
class ImageScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun post(title: String) = ImagePost(
        title = title,
        author = "@author",
        description = "",
        time = "",
        coverUrl = null,
    )

    private fun stateOf(): ImageUiState = ImageUiState().apply {
        forums = listOf("原神", "星铁")
        categories = listOf("同人图", "COS")
        categoryIndex = 0
        tabs = listOf("日榜", "周榜")
        items = listOf(post("标题甲"), post("标题乙"))
    }

    private fun setScreen(
        state: ImageUiState,
        onItemClick: (Int) -> Unit = {},
        onItemLongClick: (Int) -> Unit = {},
        onTabSelected: (Int) -> Unit = {},
        onDownloadClick: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            VenusTheme {
                ImageScreen(
                    state = state,
                    onBack = onBack,
                    onForumSelected = {},
                    onCategorySelected = {},
                    onTabSelected = onTabSelected,
                    onItemClick = onItemClick,
                    onItemLongClick = onItemLongClick,
                    onDownloadClick = onDownloadClick,
                )
            }
        }
    }

    @Test
    fun gridRendersCardsAndDispatchesClickAndLongClick() {
        val state = stateOf()
        var clicked = -1
        var longClicked = -1
        setScreen(state, onItemClick = { clicked = it }, onItemLongClick = { longClicked = it })

        composeRule.onNodeWithText("标题甲").assertIsDisplayed()
        composeRule.onNodeWithText("标题乙").assertIsDisplayed()

        composeRule.onNodeWithText("标题甲").performClick()
        assertEquals(0, clicked)

        composeRule.onNodeWithText("标题乙").performTouchInput { longClick() }
        assertEquals(1, longClicked)
    }

    @Test
    fun tabClickDispatchesIndex() {
        val state = stateOf()
        var selected = -1
        setScreen(state, onTabSelected = { selected = it })

        composeRule.onNodeWithText("周榜").assertIsDisplayed().performClick()
        assertEquals(1, selected)
    }

    @Test
    fun selectionModeShowsCountTitleAndDownloadEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = stateOf()
        var downloads = 0
        setScreen(state, onDownloadClick = { downloads++ })

        // 普通态：标题是「图片浏览」，没有下载入口
        composeRule.onNodeWithText(context.getString(R.string.toolbar_image_browse)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.download_image)).assertDoesNotExist()

        // 多选态：标题换成「N 项已选择」，下载入口出现
        composeRule.runOnIdle {
            state.selectionMode = true
            state.selected = setOf(1)
        }
        val countTitle = context.resources.getQuantityString(R.plurals.toolbar_items_selected, 1, 1)
        composeRule.onNodeWithText(countTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.download_image))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, downloads)
    }
}
