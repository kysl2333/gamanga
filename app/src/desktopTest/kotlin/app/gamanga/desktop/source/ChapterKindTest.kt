package app.gamanga.desktop.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 章节「话 / 卷」分离（用户 2026-11-11：黑色五葉草话卷混排）。
 *
 * 真实现场数据来自本机库：Komiic 圣诞 `komiic:1882` 共 432 条，
 * 其中 216 条是话（18~52 页）、216 条是卷（184~200 页），
 * 两边 serial 都只有 `01/02/03…`，所以旧的「按名字排序」会把它们交错排成
 * 01(52页) / 01(192页) / 02(26页) / 02(192页)…
 */
class ChapterKindTest {

    @Test
    fun `名字带卷标记的判卷，带话标记的判话`() {
        assertEquals(ChapterKind.VOLUME, chapterKindOf("第3卷"))
        assertEquals(ChapterKind.VOLUME, chapterKindOf("3卷"))
        assertEquals(ChapterKind.VOLUME, chapterKindOf("卷3"))
        assertEquals(ChapterKind.VOLUME, chapterKindOf("Vol.3"))
        assertEquals(ChapterKind.VOLUME, chapterKindOf("Book 12"))
        assertEquals(ChapterKind.VOLUME, chapterKindOf("第十二冊"))

        assertEquals(ChapterKind.CHAPTER, chapterKindOf("第12話"))
        assertEquals(ChapterKind.CHAPTER, chapterKindOf("12话"))
        assertEquals(ChapterKind.CHAPTER, chapterKindOf("012"))
        assertEquals(ChapterKind.CHAPTER, chapterKindOf("2.2"))
    }

    @Test
    fun `番外优先于卷话，且不把全一篇当卷`() {
        assertEquals(ChapterKind.EXTRA, chapterKindOf("番外篇"))
        assertEquals(ChapterKind.EXTRA, chapterKindOf("第1卷 特別篇"))
        assertEquals(ChapterKind.EXTRA, chapterKindOf("贺图"))
        assertEquals(ChapterKind.EXTRA, chapterKindOf("後記"))
        // 站点常用的「全一篇」既不是卷也不是话：没有编号，交给结构证据
        assertEquals(ChapterKind.UNKNOWN, chapterKindOf("全一篇"))
    }

    @Test
    fun `Komiic 真实数据 话卷成对同号 靠页数证据分离`() {
        // 复刻 komiic:1882 的前 6 条真实数据
        val raw = listOf(
            "01" to 52, "01" to 192,
            "02" to 26, "02" to 192,
            "03" to 24, "03" to 192,
        ).mapIndexed { i, (name, pages) -> SourceChapter("c$i", name, i, pages) }

        val resolved = resolveChapterKinds(raw)

        // 192 页那份是卷、几十页那份是话
        assertEquals(
            listOf(
                ChapterKind.CHAPTER, ChapterKind.VOLUME,
                ChapterKind.CHAPTER, ChapterKind.VOLUME,
                ChapterKind.CHAPTER, ChapterKind.VOLUME,
            ),
            resolved.map { it.kind },
        )
    }

    @Test
    fun `页数差距不够大时不猜，避免把厚话误判成卷`() {
        // 60 页 vs 100 页只有 1.67 倍，不足以证明是两套体系
        val raw = listOf(
            SourceChapter("a", "01", 0, 60),
            SourceChapter("b", "01", 1, 100),
        )
        val resolved = resolveChapterKinds(raw)
        assertTrue(resolved.none { it.kind == ChapterKind.VOLUME }, "证据不足时不许判卷")
    }

    @Test
    fun `页数差距够但大的一方不足 80 页也不判卷`() {
        val raw = listOf(
            SourceChapter("a", "01", 0, 20),
            SourceChapter("b", "01", 1, 70),
        )
        val resolved = resolveChapterKinds(raw)
        assertTrue(resolved.none { it.kind == ChapterKind.VOLUME })
    }

    @Test
    fun `源自带类型优先于名字与结构推断`() {
        // Komiic 的 type=book 已在源里写成 VOLUME；名字只是 "01"，页数也只是 52，
        // 结构推断会认为它是话——但源说了算。
        val raw = listOf(
            SourceChapter("a", "01", 0, 52, kind = ChapterKind.VOLUME),
            SourceChapter("b", "02", 1, 26),
        )
        val resolved = resolveChapterKinds(raw)
        assertEquals(ChapterKind.VOLUME, resolved[0].kind)
        assertEquals(ChapterKind.CHAPTER, resolved[1].kind)
    }

    @Test
    fun `显式卷名不会被重复编号推断改掉`() {
        val raw = listOf(
            SourceChapter("a", "第1卷", 0, 190),
            SourceChapter("b", "第1话", 1, 19),
        )
        val resolved = resolveChapterKinds(raw)
        assertEquals(ChapterKind.VOLUME, resolved[0].kind)
        assertEquals(ChapterKind.CHAPTER, resolved[1].kind)
    }

    @Test
    fun `中文卷号能取到数字`() {
        assertEquals(11.0, chapterNumberOrNull("第十一卷"))
        assertEquals(1.0, chapterNumberOrNull("第一卷"))
        assertEquals(123.0, chapterNumberOrNull("第一百二十三话"))
    }
}
