package app.gamanga.desktop.ui

import app.gamanga.desktop.source.SourceChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 章节编号跳空提示（R-55，用户 2026-10-23 验收3：「如果章节有缺失，
 * 在详情页那个缺失的位置提示这个位置缺几话」）。
 *
 * 用例全部来自真实库数据：装备我最强 292 章里 286 直接跳 290（缺 287-289）；
 * 旋风管家 423 章是「第N卷 + 第N话 + 贺图」混排——卷和贺图不占编号，
 * 不能误报出几十个假空档。
 */
class ChapterRowsTest {

    private fun ch(id: String, name: String) = SourceChapter(id = id, name = name, index = 0)

    private fun gaps(rows: List<ChapterRow>) = rows.filterIsInstance<ChapterRow.Gap>()

    private fun gapTexts(rows: List<ChapterRow>) = gaps(rows).map { it.text }

    // ---- 编号提取（两源真实命名）----

    @Test
    fun `chapter number covers both source naming styles`() {
        assertEquals(286.0, chapterNumberOf("286 密码190000")) // G社：编号 + 标题
        assertEquals(0.0, chapterNumberOf("000 我是谁？"))
        assertEquals(12.0, chapterNumberOf("012")) // Komiic：serial 本体
        assertEquals(2.2, chapterNumberOf("2.2")) // Komiic 小数话
        assertEquals(473.0, chapterNumberOf("第473话 净是些不好预感的建议大全")) // 第N话 形态
        assertEquals(473.0, chapterNumberOf("第 473 話"))
    }

    @Test
    fun `volumes bonus and blanks do not participate`() {
        assertNull(chapterNumberOf("第1卷")) // 卷不是话
        assertNull(chapterNumberOf("贺图 《旋风管家》告白活动贺图"))
        assertNull(chapterNumberOf("番外篇"))
        assertNull(chapterNumberOf(""))
    }

    // ---- 跳空提示 ----

    @Test
    fun `real world gap 286 to 290 reports 287-289`() {
        val rows = chapterRows(
            listOf(
                ch("a", "285 上一话"),
                ch("b", "286 密码190000"),
                ch("c", "290 力量之国"),
                ch("d", "291 尘埃中的王冠"),
            )
        )
        assertEquals(listOf("此处缺失 287-289（共 3 话）"), gapTexts(rows))
        // 提示行必须插在 286 与 290 之间（用户要求的「那个缺失的位置」）
        assertEquals(ChapterRow.Item(ch("b", "286 密码190000")), rows[1])
        assertEquals(ChapterRow.Gap("b", "c", "此处缺失 287-289（共 3 话）"), rows[2])
        assertEquals(ChapterRow.Item(ch("c", "290 力量之国")), rows[3])
    }

    @Test
    fun `single missing chapter says count one`() {
        assertEquals(
            listOf("此处缺失 287（共 1 话）"),
            gapTexts(chapterRows(listOf(ch("a", "286 x"), ch("b", "288 y"))))
        )
    }

    @Test
    fun `consecutive numbers produce no hint`() {
        val rows = chapterRows(listOf(ch("1", "000"), ch("2", "001"), ch("3", "002")))
        assertTrue(gaps(rows).isEmpty())
        assertEquals(3, rows.size)
    }

    @Test
    fun `unnumbered chapters between do not break the chain`() {
        // [286, 贺图, 290]：贺图不占编号，287-289 依然算缺失
        val rows = chapterRows(
            listOf(ch("a", "286 x"), ch("b", "贺图"), ch("c", "290 z"))
        )
        assertEquals(listOf("此处缺失 287-289（共 3 话）"), gapTexts(rows))
        assertEquals(4, rows.size) // 3 章 + 1 提示行
    }

    @Test
    fun `volume and bonus mixed list never false-alarms`() {
        // 旋风管家真实形态：第1卷…第20卷 → 第1话…第476话 → 贺图，编号链上无跳空
        val rows = chapterRows(
            listOf(
                ch("v1", "第1卷"), ch("v2", "第2卷"),
                ch("c1", "第1话 甲"), ch("c2", "第2话 乙"), ch("c3", "第3话 丙"),
                ch("bonus", "贺图 《旋风管家》告白活动贺图"),
            )
        )
        assertTrue(gaps(rows).isEmpty(), "卷/贺图混排不能误报空档")
        assertEquals(6, rows.size) // 6 章原样成 6 行，一行不多
    }

    @Test
    fun `descending display still reports the same ascending range`() {
        val rows = chapterRows(
            listOf(ch("d", "291 w"), ch("c", "290 z"), ch("b", "286 y"), ch("a", "285 x"))
        )
        assertEquals(listOf("此处缺失 287-289（共 3 话）"), gapTexts(rows))
        // 倒序时提示行仍插在两章之间（290 在前、286 在后）
        assertEquals(ChapterRow.Gap("c", "b", "此处缺失 287-289（共 3 话）"), rows[2])
    }

    @Test
    fun `decimal serials only flag integer hops`() {
        assertTrue(
            gaps(chapterRows(listOf(ch("a", "2"), ch("b", "2.2"), ch("c", "3")))).isEmpty(),
            "小数话相邻不是缺失"
        )
        assertEquals(
            listOf("此处缺失 3（共 1 话）"),
            gapTexts(chapterRows(listOf(ch("a", "2.2"), ch("b", "4"))))
        )
    }

    @Test
    fun `duplicate or decreasing numbers never flag`() {
        assertTrue(gaps(chapterRows(listOf(ch("a", "5"), ch("b", "5"), ch("c", "6")))).isEmpty())
        assertTrue(gaps(chapterRows(listOf(ch("a", "9"), ch("b", "8"), ch("c", "7")))).isEmpty())
    }

    @Test
    fun `gap keys are stable and unique`() {
        val rows = chapterRows(
            listOf(ch("a", "1"), ch("b", "3"), ch("c", "5"))
        )
        val keys = rows.map { it.key }
        assertEquals(listOf("a", "gap:a>b", "b", "gap:b>c", "c"), keys)
    }

    // ---- 行下标换算（提示行会让章节下标与行下标错位）----

    @Test
    fun `row index equals chapter index without hints`() {
        val rows = chapterRows(listOf(ch("1", "1"), ch("2", "2"), ch("3", "3")))
        assertEquals(0, rowIndexOfChapter(rows, 0))
        assertEquals(2, rowIndexOfChapter(rows, 2))
        assertEquals(-1, rowIndexOfChapter(rows, -1))
        assertEquals(-1, rowIndexOfChapter(rows, 7)) // 越界
    }

    @Test
    fun `row index shifts after a hint row`() {
        // [1, 2, 4] → 行 [1, 2, 提示缺3, 4]：第 4 话（章节下标 2）落在第 3 行
        val rows = chapterRows(listOf(ch("a", "1"), ch("b", "2"), ch("c", "4")))
        assertEquals(0, rowIndexOfChapter(rows, 0))
        assertEquals(1, rowIndexOfChapter(rows, 1))
        assertEquals(3, rowIndexOfChapter(rows, 2))
        assertEquals(-1, rowIndexOfChapter(rows, -1))
    }
}
