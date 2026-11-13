package app.gamanga.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.ui.reader.*
import app.gamanga.desktop.source.SourceChapter
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class ReaderChromeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `toolbar animation does not resize reading surface every frame`() {
        val visible = mutableStateOf(true)
        val sizes = mutableListOf<Int>()
        rule.setContent { ReaderViewportMotion(visible.value, false, Modifier.requiredSize(800.dp, 600.dp),
            toolbar = { Box(Modifier.fillMaxWidth().height(48.dp)) },
            progress = { Box(Modifier.fillMaxWidth().height(2.dp).testTag("loading-line")) }) {
            Box(Modifier.fillMaxSize().onSizeChanged { sizes += it.height }.testTag("reading-body"))
        } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { sizes.clear(); visible.value = false }
        repeat(16) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        println("reader toolbar content height changes=${sizes.distinct().size} sizes=$sizes")
        assertTrue(sizes.distinct().size <= 2, "工具栏位移不能让漫画每帧重新Fit：$sizes")
    }

    @Test fun `directory keeps its content throughout exit instead of flashing settings`() {
        val open = mutableStateOf(true)
        rule.setContent { ReaderPanelMotion(false, open.value, false,
            settings = { androidx.compose.material3.Text("阅读方式") },
            chapters = { androidx.compose.material3.Text("目录内容") }) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { open.value = false }
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithText("阅读方式").assertDoesNotExist()
        rule.onNodeWithText("目录内容").assertExists()
        rule.mainClock.advanceTimeBy(240)
        rule.onNodeWithText("目录内容").assertDoesNotExist()
    }

    @Test fun `loading line and reading surface move together through intermediate frames`() {
        val visible = mutableStateOf(true)
        rule.setContent { ReaderViewportMotion(visible.value, false, Modifier.requiredSize(800.dp, 600.dp),
            toolbar = { Box(Modifier.fillMaxWidth().height(48.dp)) },
            progress = { Box(Modifier.fillMaxWidth().height(2.dp).testTag("loading-line")) }) {
            Box(Modifier.fillMaxSize().testTag("reading-body"))
        } }
        val before = rule.onNodeWithTag("loading-line").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { visible.value = false }
        rule.mainClock.advanceTimeBy(80)
        val line = rule.onNodeWithTag("loading-line").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("reading-body").fetchSemanticsNode().boundsInRoot
        assertTrue(line.top > 0f && line.top < before, "加载条应处于中间位置：$line")
        assertTrue(kotlin.math.abs(line.bottom - body.top) < 2f, "加载条与漫画顶部应同步：$line / $body")
    }

    @Test fun `close control is rightmost and directory selects current chapter`() {
        var selected = -1
        rule.setContent { MaterialTheme { Column(Modifier.width(800.dp)) {
            ReaderBar("书名", "第一话", 0, 1000, 0, 10, {}, {}, {})
            ReaderChapterPanel((0..999).map { SourceChapter("$it", "第${it + 1}话", it) }, 700, { selected = it })
        } } }
        val close = rule.onNodeWithContentDescription("关闭阅读器").fetchSemanticsNode().boundsInRoot
        val browser = rule.onNodeWithText("浏览器查看").fetchSemanticsNode().boundsInRoot
        assertTrue(close.left > browser.right)
        rule.onNodeWithTag("reader-chapter-700").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(700, selected)
        rule.onAllNodesWithText("第1话").assertCountEquals(0)
    }
}
