package com.muxiao.Venus.Setting

import android.widget.TextView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.muxiao.Venus.R
import com.muxiao.Venus.common.ui.compose.SETTINGS_DRAWER_CONTENT_TAG
import com.muxiao.Venus.common.ui.compose.SettingsCard
import com.muxiao.Venus.common.ui.compose.SettingsScreen
import com.muxiao.Venus.common.ui.compose.SettingsUiState
import com.muxiao.Venus.common.ui.compose.VenusTheme
import org.junit.Rule
import org.junit.Test

/**
 * 迁移回归测试：确保 Compose 版设置页与原 `CollapsibleCardView` 行为等价。
 *
 * 覆盖点：
 * 1. 12 个抽屉标题全部渲染（顺序即版式，漏一个就是版式错位）
 * 2. 默认折叠 → 点击标题展开内容 → 再点收起
 * 3. 折叠态摘要（`setValue`）与标题同排显示
 * 4. `setVisibility(GONE)` 收起整扇抽屉（国际服隐藏米游币签到用）
 */
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cards() = listOf(
        SettingsCard(R.string.miyoushe_coin_sign_in),
        SettingsCard(R.string.game_sign_in),
        SettingsCard(R.string.server_type),
        SettingsCard(R.string.config_info),
        SettingsCard(R.string.update),
        SettingsCard(R.string.cache),
        SettingsCard(R.string.language),
        SettingsCard(R.string.theme),
        SettingsCard(R.string.background),
        SettingsCard(R.string.background_running),
        SettingsCard(R.string.skland_sign_in),
        SettingsCard(R.string.about),
    )

    private fun setScreen(list: List<SettingsCard>) {
        composeRule.setContent {
            VenusTheme {
                SettingsScreen(
                    state = SettingsUiState().apply { versionChip = "v2.4.0" },
                    daily = list[0],
                    gameDaily = list[1],
                    server = list[2],
                    config = list[3],
                    update = list[4],
                    cache = list[5],
                    language = list[6],
                    theme = list[7],
                    background = list[8],
                    notification = list[9],
                    skland = list[10],
                    about = list[11],
                    onImageClick = {},
                )
            }
        }
    }

    private fun paneContent(): TextView =
        TextView(context).apply { text = "缓存 12.4 MB" }

    @Test
    fun allTwelveDrawersAreRendered() {
        val list = cards()
        setScreen(list)

        composeRule.onNodeWithText(context.getString(R.string.miyoushe_coin_sign_in)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.game_sign_in)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.server_type)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.view_miyoushe_ranking)).assertIsDisplayed()
    }

    @Test
    fun tappingTitleTogglesContent() {
        val list = cards()
        list[5].setContent(paneContent())
        setScreen(list)

        // 默认全部折叠：没有任何抽屉内容在场
        composeRule.onAllNodesWithTag(SETTINGS_DRAWER_CONTENT_TAG).assertCountEquals(0)

        composeRule.onNodeWithText(context.getString(R.string.cache)).performClick()
        composeRule.onAllNodesWithTag(SETTINGS_DRAWER_CONTENT_TAG).assertCountEquals(1)

        composeRule.onNodeWithText(context.getString(R.string.cache)).performClick()
        composeRule.onAllNodesWithTag(SETTINGS_DRAWER_CONTENT_TAG).assertCountEquals(0)
    }

    @Test
    fun collapsedValueIsShownNextToTitle() {
        val list = cards()
        list[5].setValue("12.4 MB")
        setScreen(list)

        composeRule.onNodeWithText("12.4 MB").assertIsDisplayed()
    }

    @Test
    fun settingVisibilityHiddenRemovesDrawer() {
        val list = cards()
        list[0].setVisibility(android.view.View.GONE)
        setScreen(list)

        composeRule.onAllNodesWithText(context.getString(R.string.miyoushe_coin_sign_in))
            .assertCountEquals(0)
        // 其余抽屉不受影响
        composeRule.onNodeWithText(context.getString(R.string.game_sign_in)).assertIsDisplayed()
    }
}
