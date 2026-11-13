package app.gamanga.desktop.data.backup

import app.gamanga.desktop.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * mihon 导入升级（F-275）的纯逻辑测试。
 *
 * 三块：①章节按**名字**对齐（用户明确要求「对齐章数的名字而不是第几章」）；
 * ②预览行的状态判定（可直接导入 / 需换源 / 已在书架）；③旧版备份的宽松解析
 * （字段号挪过位、chapterNumber 写成 double 也要能读）。
 */
class MihonImportPlanTest {

    // ---- ① 章节按名字对齐 ----

    @Test
    fun `chapter name wins over positional number`() {
        // 本地第一话其实是「番外」：按序号会把 mihon 的第 2 话对到番外上，按名字才对到 index 2
        val names = listOf("番外", "第1话", "第2话")
        assertEquals(2, matchMihonChapterIndex(2f, "第2话", names))
        // 老逻辑（话数 - 1）会给出 1，说明这条测试真的在防回归
        assertEquals(1, (2f.toInt()) - 1)
    }

    @Test
    fun `name matching survives split chapters`() {
        // 源站把第 1 话拆成上下：名字键相同 → 取第一个，不越界也不乱序
        val names = listOf("第1话 上", "第1话 下", "第2话")
        assertEquals(0, matchChapterByName("第1话", names))
        assertEquals(2, matchChapterByName("第2话", names))
    }

    @Test
    fun `normalize strips shell decorations and width`() {
        assertEquals("52", normalizeChapterName("第52话"))
        assertEquals("52", normalizeChapterName("Chapter 52"))
        assertEquals("52", normalizeChapterName("（修图）第 52 话"))
        assertEquals("序章", normalizeChapterName("【序章】"))
    }

    @Test
    fun `chapter key treats decorated and plain the same`() {
        assertEquals(chapterKey("第52话"), chapterKey("52话"))
        assertEquals(chapterKey("第52话"), chapterKey("52"))
        assertEquals("n:1.5", chapterKey("第1.5话"))
        assertEquals("s:序章", chapterKey("序章"))
    }

    @Test
    fun `unknown name falls back to positional number`() {
        val names = listOf("A", "B", "C")
        assertEquals(1, matchMihonChapterIndex(2f, "完全对不上", names))
        assertEquals(-1, matchMihonChapterIndex(99f, "完全对不上", names))
    }

    // ---- ② 预览状态 ----

    private fun manga(url: String, title: String, chapters: Int = 3, read: Int = 1) = MihonManga(
        url = url,
        title = title,
        author = null,
        thumbnailUrl = null,
        dateAdded = 0L,
        chapters = (0 until chapters).map {
            MihonChapter(url = "$url#$it", name = "第${it + 1}话", read = it < read, lastPageRead = 0L, chapterNumber = (it + 1).toFloat())
        },
    )

    @Test
    fun `preview classifies ready in-library and unknown source`() {
        val parsed = MihonBackupResult(
            manga = listOf(
                manga("https://komiic.cc/comic/42", "已在书架"),
                manga("https://komiic.cc/comic/99", "可导入"),
                manga("https://weird-site.example/manga/x", "认不出"),
            ),
            skipped = emptyList(),
        )
        val preview = buildMihonPreview(parsed, existingIds = setOf("komiic:42"))
        assertEquals(3, preview.total)
        assertEquals(MihonRowStatus.IN_LIBRARY, preview.rows[0].status)
        assertEquals(MihonRowStatus.READY, preview.rows[1].status)
        assertEquals(MihonRowStatus.UNKNOWN_SOURCE, preview.rows[2].status)
        assertEquals(1, preview.readyCount)
        assertEquals(1, preview.inLibraryCount)
        assertEquals(1, preview.needSourceCount)
        assertEquals(9, preview.totalChapters)
        assertEquals(3, preview.readChapters)
        // 认不出源的行也带着源站 id 的空值，界面靠它决定「要不要去搜」
        assertNull(preview.rows[2].source)
    }

    @Test
    fun `alias drops decorative brackets`() {
        val alias = defaultSearchAlias("『无限扭蛋』召唤等级9999的同伴！")
        assertTrue(alias.isNotBlank())
        assertTrue(!alias.contains("『") && !alias.contains("』"), "别称里不该留装饰符：$alias")
        assertTrue(alias.contains("9999"), "数字段要留着，那是搜得到的关键：$alias")
    }

    @Test
    fun `title score prefers exact then contains`() {
        assertEquals(3, titleMatchScore("重骑士", "重骑士"))
        assertEquals(2, titleMatchScore("重骑士", "被放逐的重骑士用游荡技能"))
        assertEquals(1, titleMatchScore("重骑士", "完全无关的书"))
    }

    /**
     * R-179（2026-10-30 用户）：「越靠前的文字优先级越高」。
     *
     * 上一版「包含」全是 2 分，谁先回来谁靠前 → 搜「重骑士」时
     * 《被放逐的重骑士…》可能排在《重骑士与魔剑》前面。现在按出现位置分档。
     */
    @Test
    fun `rank puts earlier matches first`() {
        val exact = titleMatchRank("重骑士", "重骑士")
        val prefix = titleMatchRank("重骑士", "重骑士与魔剑")
        val middleNear = titleMatchRank("重骑士", "被放逐的重骑士")
        val middleFar = titleMatchRank("重骑士", "异世界转生之后我成了传说中最强的重骑士")
        val unrelated = titleMatchRank("重骑士", "完全无关的书")

        assertTrue(exact > prefix, "完全一致要排最前：$exact vs $prefix")
        assertTrue(prefix > middleNear, "开头命中要排在中间命中之前：$prefix vs $middleNear")
        assertTrue(middleNear > middleFar, "出现得越早越靠前：$middleNear vs $middleFar")
        assertTrue(middleFar > unrelated, "沾边总比不沾边强：$middleFar vs $unrelated")
        assertEquals(0, unrelated)
    }

    @Test
    fun `rank treats a shortened candidate as a weaker match`() {
        // 候选比搜索词短（用户把别称写长了）：只算「候选是搜索词的开头」
        assertEquals(500, titleMatchRank("重骑士与魔剑", "重骑士"))
        // 而同一档下，完整出现在候选里的（哪怕在中间）一定更高
        assertTrue(titleMatchRank("重骑士", "被放逐的重骑士") > 500)
    }

    @Test
    fun `manga id is shared between preview and import`() {
        assertEquals("komiic:42", mangaIdOf(SourceType.KOMIIC, "42"))
        assertEquals("goda:abc", mangaIdOf(SourceType.GODA, "abc"))
    }

    // ---- ③ 旧版备份的宽松解析 ----

    @Test
    fun `old backup with shifted fields and double chapter number still parses`() {
        // 旧版本：章节列表在 field 12（不是 16），chapterNumber 写成 double（wireType 1）
        val chapter = Proto()
            .string(1, "https://komiic.cc/comic/7/ch/1")
            .string(2, "第1话")
            .double(9, 1.0)
            .build()
        val manga = Proto()
            .string(2, "https://komiic.cc/comic/7")
            .string(3, "旧备份里的书")
            .bytes(12, chapter)
            .build()
        val backup = Proto().bytes(1, manga).build()

        val parsed = parseMihonBackup(gzip(backup))
        assertEquals(1, parsed.manga.size)
        val m = parsed.manga.first()
        assertEquals("旧备份里的书", m.title)
        assertEquals(1, m.chapters.size)
        assertEquals("第1话", m.chapters.first().name)
        assertEquals(1f, m.chapters.first().chapterNumber)
        assertNotNull(mihonUrlToSource(m.url))
    }

    @Test
    fun `backup without url is skipped not crashed`() {
        val manga = Proto().string(3, "没有 url 的坏条目").build()
        val backup = Proto().bytes(1, manga).build()
        val parsed = parseMihonBackup(gzip(backup))
        assertEquals(0, parsed.manga.size)
    }
}
