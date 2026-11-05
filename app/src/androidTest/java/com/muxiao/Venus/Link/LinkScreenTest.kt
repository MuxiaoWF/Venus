package com.muxiao.Venus.Link

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.LINK_CHIP_FETCHED
import com.muxiao.Venus.common.ui.compose.LINK_CHIP_PENDING
import com.muxiao.Venus.common.ui.compose.LinkRow
import com.muxiao.Venus.common.ui.compose.LinkScreen
import com.muxiao.Venus.common.ui.compose.LinkUiState
import com.muxiao.Venus.common.ui.compose.VPN_STATUS_SUCCESS
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：确保 Compose 版抽卡链接页与原 XML 行为等价。
 *
 * 重点覆盖三件在原实现里散落在 View 操作中的事：
 * 1. 来源行状态切换后 chip / 状态文案同步（原来靠 setChipState 逐控件改）
 * 2. 结果行渲染 + 复制按钮回调带出对应 URL
 * 3. 无用户空态引导可点击（原来靠 updateUserGuideState 换点击行为）
 */
class LinkScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun state() = LinkUiState()

    @Test
    fun sourceRowReflectsChipStateTransition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiState = state()
        composeRule.setContent {
            VenusTheme {
                LinkScreen(
                    state = uiState,
                    onUserSelected = {},
                    onEmptyUserClick = {},
                    onSourceClick = {},
                    onRefreshClick = {},
                    onCloudClick = {},
                    onVpnClick = {},
                    onCopy = {},
                )
            }
        }

        // 初始 PENDING：两行都显示「待获取」chip + 「尚未获取」状态
        composeRule.onAllNodesWithText(context.getString(R.string.gacha_not_fetched))
            .assertCountEquals(2)
        composeRule.onAllNodesWithText(context.getString(R.string.gacha_chip_pending))
            .assertCountEquals(2)

        // 第一行切到 FETCHED：chip 与状态文案一起变成「已获取」（该行 2 个节点）
        composeRule.runOnIdle { uiState.sources[0].state = LINK_CHIP_FETCHED }
        composeRule.onAllNodesWithText(context.getString(R.string.gacha_fetched))
            .assertCountEquals(2)

        // 回到 PENDING 恢复原文案（对应「重新获取失败」路径）
        composeRule.runOnIdle { uiState.sources[0].state = LINK_CHIP_PENDING }
        composeRule.onAllNodesWithText(context.getString(R.string.gacha_chip_pending))
            .assertCountEquals(2)
    }

    @Test
    fun resultRowRendersAndCopyDispatchesUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiState = state()
        val url = "https://example.invalid/gacha?authkey=abc"
        var copied: String? = null

        composeRule.setContent {
            VenusTheme {
                LinkScreen(
                    state = uiState,
                    onUserSelected = {},
                    onEmptyUserClick = {},
                    onSourceClick = {},
                    onRefreshClick = {},
                    onCloudClick = {},
                    onVpnClick = {},
                    onCopy = { copied = it },
                )
            }
        }

        composeRule.runOnIdle {
            uiState.sources[0].state = LINK_CHIP_FETCHED
            uiState.sources[0].result = listOf(LinkRow("UID 100000001", url))
        }

        composeRule.onNodeWithText("UID 100000001").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy))
            .assertIsDisplayed()
            .performClick()

        assertEquals(url, copied)
    }

    @Test
    fun emptyUserRowShowsGuideAndDispatchesClick() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiState = state()
        var emptyClicks = 0

        composeRule.setContent {
            VenusTheme {
                LinkScreen(
                    state = uiState,
                    onUserSelected = {},
                    onEmptyUserClick = { emptyClicks++ },
                    onSourceClick = {},
                    onRefreshClick = {},
                    onCloudClick = {},
                    onVpnClick = {},
                    onCopy = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.home_no_user_hint))
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, emptyClicks)
    }

    @Test
    fun vpnRowShowsSemanticStatusText() {
        val uiState = state()
        val captured = "已捕获 · 国服"
        composeRule.setContent {
            VenusTheme {
                LinkScreen(
                    state = uiState,
                    onUserSelected = {},
                    onEmptyUserClick = {},
                    onSourceClick = {},
                    onRefreshClick = {},
                    onCloudClick = {},
                    onVpnClick = {},
                    onCopy = {},
                )
            }
        }

        composeRule.runOnIdle {
            uiState.vpnStatusText = captured
            uiState.vpnStatusState = VPN_STATUS_SUCCESS
        }

        composeRule.onNodeWithText(captured).assertIsDisplayed()
    }
}
