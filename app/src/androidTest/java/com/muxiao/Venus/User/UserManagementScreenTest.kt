package com.muxiao.Venus.User

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.UserCardRow
import com.muxiao.Venus.common.ui.compose.UserManagementScreen
import com.muxiao.Venus.common.ui.compose.UserManagementUiState
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：确保 Compose 版用户管理页与原 XML 行为等价。
 *
 * 覆盖原实现里散在命令式 View 操作中的三件事：
 * 1. 空态与区块标签互斥（原来靠 `noUserPrompt` / `users_section_label` 的 setVisibility）
 * 2. 「默认」chip 只出现在当前账号上
 * 3. 卡片内三个入口（重命名 / 重新登录 / 删除）与右下角 FAB 各自回调正确
 */
class UserManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun state(
        users: List<UserCardRow> = emptyList(),
        emptyText: String? = null,
    ) = UserManagementUiState().apply {
        this.users = users
        this.emptyText = emptyText
        subtitle = "管理登录账号"
    }

    @Test
    fun emptyStateShowsPromptAndHidesSectionLabel() {
        val prompt = "当前服务器还没有登录账号"
        composeRule.setContent {
            VenusTheme {
                UserManagementScreen(
                    state = state(emptyText = prompt),
                    onAddUserClick = {},
                    onAvatarClick = {},
                    onRenameClick = {},
                    onReloginClick = {},
                    onDeleteClick = {},
                    onCloudGameClick = {},
                    onSklandClick = {},
                )
            }
        }

        composeRule.onNodeWithText(prompt).assertIsDisplayed()
        // 空态时区块标签收起（否则出现「孤儿标题」）
        composeRule.onAllNodesWithText(context.getString(R.string.section_accounts))
            .assertCountEquals(0)
    }

    @Test
    fun onlyCurrentUserCarriesDefaultChip() {
        composeRule.setContent {
            VenusTheme {
                UserManagementScreen(
                    state = state(
                        users = listOf(
                            UserCardRow("muxiao", "muxiao · 国服", true),
                            UserCardRow("venus", "venus · 国服", false),
                        ),
                    ),
                    onAddUserClick = {},
                    onAvatarClick = {},
                    onRenameClick = {},
                    onReloginClick = {},
                    onDeleteClick = {},
                    onCloudGameClick = {},
                    onSklandClick = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.section_accounts)).assertIsDisplayed()
        // 两个用户 → 仅一枚「默认」chip
        composeRule.onAllNodesWithText(context.getString(R.string.user_chip_default))
            .assertCountEquals(1)
    }

    @Test
    fun cardActionsReportTargetUsername() {
        var deleted: String? = null
        var relogin: String? = null
        var renamed: String? = null
        var added = false

        composeRule.setContent {
            VenusTheme {
                UserManagementScreen(
                    state = state(users = listOf(UserCardRow(
                        "muxiao", "muxiao · 国服", true,
                        cloudGameLabel = "云游戏 未配置",
                        sklandLabel = "森空岛 未开启",
                    ))),
                    onAddUserClick = { added = true },
                    onAvatarClick = {},
                    onRenameClick = { renamed = it },
                    onReloginClick = { relogin = it },
                    onDeleteClick = { deleted = it },
                    onCloudGameClick = {},
                    onSklandClick = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.delete_user)).performClick()
        assertEquals("muxiao", deleted)

        composeRule.onNodeWithText(context.getString(R.string.re_login)).performClick()
        assertEquals("muxiao", relogin)

        // 信息行整行可点 = 重命名（原 XML 里没有独立重命名按钮）
        composeRule.onNodeWithText("muxiao · 国服").performClick()
        assertEquals("muxiao", renamed)

        composeRule.onNodeWithText(context.getString(R.string.add_or_login_user)).performClick()
        assertTrue(added)
    }
}
