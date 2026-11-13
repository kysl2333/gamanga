package app.gamanga.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置页重做后的交互契约（2026-10-28）。
 *
 * 只锁定用户明确提出的三条可测事实：
 * 1. 左侧点哪一类立即回调，没有过渡动画（autoAdvance = false 时也要马上生效）；
 * 2. 搜索框能过滤左侧分类；
 * 3. 行式设置项同时渲染标题、说明与右侧控件。
 *
 * 视觉（投影、圆角、留白）不做像素断言，交给用户实机验收。
 */
class SettingsUiTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun sidebarSelectionIsImmediateWithoutAnimation() {
        var picked by mutableStateOf("备份与恢复")
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 720.dp)) {
                    SettingsSidebar(
                        category = picked,
                        query = "",
                        onQuery = {},
                        onSelect = { picked = it },
                        showHealth = false,
                    )
                }
            }
        }
        // 关掉自动推进：如果选中切换依赖动画帧，这里就拿不到新分类
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("网络代理").performMouseInput { click() }
        rule.runOnIdle { assertEquals("网络代理", picked) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("网络代理").assertIsSelected()
    }

    @Test
    fun searchFiltersSidebarCategories() {
        var query by mutableStateOf("")
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 720.dp)) {
                    SettingsSidebar(
                        category = "备份与恢复",
                        query = query,
                        onQuery = { query = it },
                        onSelect = {},
                        showHealth = false,
                    )
                }
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("缓存")
        rule.onNodeWithText("缓存与清理").assertExists()
        rule.onNodeWithText("备份与恢复").assertDoesNotExist()
        rule.onNodeWithText("网络代理").assertDoesNotExist()
    }

    @Test
    fun contentIsCenteredWithFixedMaxWidth() {
        // 用户 2026-10-28 连提两轮：先「拉长界面间距会加大」，改完又说「左边好了右边又开始拉长，
        // 应该居中」。这条测试锁住「固定 760 宽 + 居中」：
        //   左边缘 = 24(padding) + (可用宽 - 760) / 2
        // 靠左会得到 24，吃满会得到 24，两种回归都会立刻红。
        // 注意：期望值**不能写死窗口宽度**——Compose 测试根有自己的默认尺寸（实测 1024dp，
        // 我第一版按 1200 写就挂了），所以按测出来的根宽反推。
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsContentFrame(rememberScrollState()) {
                        Text("内容")
                    }
                }
            }
        }
        val rootWidthDp = with(rule.density) {
            rule.onRoot().fetchSemanticsNode().boundsInRoot.width.toDp().value
        }
        val leftDp = with(rule.density) {
            rule.onNodeWithText("内容").fetchSemanticsNode().boundsInRoot.left.toDp().value
        }
        val expected = 24f + ((rootWidthDp - 48f - 760f) / 2f)
        assertEquals(expected, leftDp, 2f)
    }

    @Test
    fun emptyShelfOffersRecommendShortcut() {
        // 用户 2026-10-28：「书架没书时界面给一个提示可以去推荐页查漫画」。
        // 锁两件事：文案在、点了真的回调（不是只画个不可点的样子）。
        var clicked = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 720.dp)) {
                    app.gamanga.desktop.ui.library.EmptyShelf(onOpenRecommend = { clicked = true })
                }
            }
        }
        rule.onNodeWithText("去推荐页看看").assertExists()
        rule.onNodeWithText("推荐页里挑一本，点开就能加入书架").assertExists()
        rule.onNodeWithText("去推荐页看看").performMouseInput { click() }
        rule.runOnIdle { assertTrue(clicked, "点「去推荐页看看」应触发回调") }
    }

    @Test
    fun rowRendersTitleDescriptionAndTrailingControl() {
        rule.setContent {
            MaterialTheme {
                SettingsRow("备份子目录", "留空表示直接放在上面的地址里。") {
                    Text("控件")
                }
            }
        }
        rule.onNodeWithText("备份子目录").assertExists()
        rule.onNodeWithText("留空表示直接放在上面的地址里。").assertExists()
        rule.onNodeWithText("控件").assertExists()
        val title = rule.onNodeWithText("备份子目录").fetchSemanticsNode().boundsInRoot
        val control = rule.onNodeWithText("控件").fetchSemanticsNode().boundsInRoot
        assert(control.left > title.right) { "控件应在标题右侧" }
    }
}
