package app.gamanga.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 详情页章节列表的「显示下标」映射（R-08 定位 + 高亮共用）。
 *
 * 倒序显示时列表是升序列表的镜像：直接用阅读进度下标定位会滚到对称的另一头，
 * 高亮也会标错行。UI 层（BrowseScreen）只负责调用，这里把规则锁住。
 */
class BrowseChapterIndexTest {

    @Test
    fun `ascending list maps identity`() {
        assertEquals(0, chapterDisplayIndex(0, 10, desc = false))
        assertEquals(9, chapterDisplayIndex(9, 10, desc = false))
    }

    @Test
    fun `descending list mirrors around the end`() {
        assertEquals(9, chapterDisplayIndex(0, 10, desc = true))
        assertEquals(0, chapterDisplayIndex(9, 10, desc = true))
        assertEquals(4, chapterDisplayIndex(5, 10, desc = true))
    }

    @Test
    fun `missing progress returns minus one so caller skips`() {
        assertEquals(-1, chapterDisplayIndex(-1, 10, desc = false))
        assertEquals(-1, chapterDisplayIndex(-1, 10, desc = true))
    }

    @Test
    fun `empty list or stale index is clamped`() {
        assertEquals(-1, chapterDisplayIndex(0, 0, desc = false))
        // 进度指向的章节已被源站删掉（越界）：夹到最后一格而不是负下标
        assertEquals(9, chapterDisplayIndex(15, 10, desc = false))
        assertEquals(0, chapterDisplayIndex(15, 10, desc = true))
    }
}
