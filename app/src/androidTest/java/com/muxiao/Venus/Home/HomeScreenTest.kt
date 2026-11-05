package com.muxiao.Venus.Home

import android.widget.LinearLayout
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.HOME_RUN_FOREGROUND
import com.muxiao.Venus.common.ui.compose.HOME_RUN_IDLE
import com.muxiao.Venus.common.ui.compose.HomeScreen
import com.muxiao.Venus.common.ui.compose.HomeTaskRow
import com.muxiao.Venus.common.ui.compose.HomeUiState
import com.muxiao.Venus.common.ui.compose.TASK_COMPLETED
import com.muxiao.Venus.common.ui.compose.TASK_ERROR
import com.muxiao.Venus.common.ui.compose.TASK_PENDING
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：确保 Compose 版主页与原 XML 行为等价。
 *
 * 重点覆盖「可见性由状态派生」这条新约定——原来这五个可见性散在 `render()` 的
 * 十几次 `setVisibility` 里，现在只由 runMode / bgFeatureEnabled / 任务状态决定：
 * 1. 空闲：开始按钮 + 后台行（开关开启时）+ 失败时出现重试行
 * 2. 前台运行：账号行与开始按钮收起，取消按钮出现，后台行连带收起
 * 3. 任务状态文案与计数器
 */
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun geetestContainer() = LinearLayout(context).apply {
        visibility = android.view.View.GONE
    }

    private fun baseState() = HomeUiState().apply {
        dateChip = "11/05 周三"
        subtitle = "今日签到 · 已完成 1/3"
        users = listOf("muxiao")
        selectedUserIndex = 0
        userLabel = "muxiao [国服]"
        runHint = "剩余 3 条"
        doneLabel = "1/3"
        retryCount = "1"
        bgButtonText = "后台运行"
        bgFeatureEnabled = true
        tasks = listOf(
            HomeTaskRow("米游社签到", TASK_COMPLETED),
            HomeTaskRow("原神", TASK_ERROR),
            HomeTaskRow("崩坏3", TASK_PENDING),
        )
    }

    private fun setScreen(
        state: HomeUiState,
        onStartDaily: () -> Unit = {},
        onJumpToUsers: () -> Unit = {},
    ) {
        composeRule.setContent {
            VenusTheme {
                HomeScreen(
                    state = state,
                    geetestContainer = geetestContainer(),
                    onUserSelected = {},
                    onJumpToUsers = onJumpToUsers,
                    onStartDaily = onStartDaily,
                    onCancelDaily = {},
                    onRetryFailed = {},
                    onBackgroundClick = {},
                    onViewLog = {},
                )
            }
        }
    }

    @Test
    fun idleShowsStartAndRetryButNotCancel() {
        setScreen(baseState())

        composeRule.onNodeWithText(context.getString(R.string.run_sign_in)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.retry_failed_tasks)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.background_running)).assertIsDisplayed()
        // 空闲态不出现取消按钮
        composeRule.onAllNodesWithText(context.getString(R.string.cancel_task)).assertCountEquals(0)
    }

    @Test
    fun foregroundHidesAccountSectionAndStartButton() {
        setScreen(baseState().apply { runMode = HOME_RUN_FOREGROUND })

        composeRule.onAllNodesWithText(context.getString(R.string.run_sign_in)).assertCountEquals(0)
        // 前台运行期间账号上下文整体收起（标签 / 行 / 分割线一起走，不留孤儿标题）
        composeRule.onAllNodesWithText(context.getString(R.string.section_account)).assertCountEquals(0)
        // 后台行在前台运行期间也收起；取消按钮出现
        composeRule.onAllNodesWithText(context.getString(R.string.background_running)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.cancel_task)).assertIsDisplayed()
    }

    @Test
    fun retryRowOnlyAppearsWhenIdleAndFailed() {
        setScreen(
            baseState().apply {
                runMode = HOME_RUN_IDLE
                tasks = listOf(
                    HomeTaskRow("米游社签到", TASK_COMPLETED),
                    HomeTaskRow("崩坏3", TASK_PENDING),
                )
            },
        )

        composeRule.onAllNodesWithText(context.getString(R.string.retry_failed_tasks))
            .assertCountEquals(0)
    }

    @Test
    fun startButtonReportsClick() {
        var started = false
        setScreen(baseState(), onStartDaily = { started = true })

        composeRule.onNodeWithText(context.getString(R.string.run_sign_in)).performClick()
        assertTrue(started)
    }

    @Test
    fun noUserStateJumpsToUserManagement() {
        var jumped = false
        setScreen(
            baseState().apply {
                users = emptyList()
                selectedUserIndex = -1
                userLabel = ""
            },
            onJumpToUsers = { jumped = true },
        )

        composeRule.onNodeWithText(context.getString(R.string.home_no_user_hint)).performClick()
        assertTrue(jumped)
    }
}
