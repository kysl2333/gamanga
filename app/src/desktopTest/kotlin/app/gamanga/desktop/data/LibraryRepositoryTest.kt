package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.ReadingProgress
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 数据层不变量回归测试（内存 SQLite seam）。
 *
 * 锁定 M1 验收中人工验证过的行为：种子幂等、级联删除、未读计数、
 * 百分比取整、prefs 往返、封面色往返、locale 无关的十六进制格式化。
 */
class LibraryRepositoryTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = GamangaDatabase(driver)
    }

    private fun repo() = LibraryRepository(db)

    @Test
    fun `download metadata retains cover after hiding from shelf`() = runTest {
        val r = repo()
        r.addToLibrary(MangaEntry("komiic:77", "下载漫画", SourceType.KOMIIC, "77", "https://example.test/cover.jpg", "作者"))
        r.libraryItems.first { it.any { item -> item.entry.id == "komiic:77" } }
        r.hideFromLibrary(listOf("komiic:77"))
        r.libraryItems.first { it.none { item -> item.entry.id == "komiic:77" } }
        val metadata = r.mangaMetadata.first { it.containsKey("komiic:77") }
        assertEquals("https://example.test/cover.jpg", metadata["komiic:77"]?.coverUrl)
        assertEquals("作者", metadata["komiic:77"]?.author)
    }

    @Test
    fun `seed is idempotent`() = runTest {
        val r = repo()
        r.seedDemoData()
        r.seedDemoData()
        // StateFlow.first() 返回当前值（初始空表），需等上游发射收敛
        val items = r.libraryItems.first { it.isNotEmpty() }
        assertEquals(6, items.size)
        // 未读合计 12（M1 验收值）
        assertEquals(12L, items.sumOf { it.unreadCount })
        // 章节总数 50
        assertEquals(50, items.sumOf { r.chaptersOf(it.entry.id).size })
    }

    @Test
    fun `purge removes legacy demo data but keeps real books`() = runTest {
        val r = repo()
        r.seedDemoData()
        r.libraryItems.first { it.isNotEmpty() }
        r.addToLibrary(
            MangaEntry("komiic:88", "真漫画", SourceType.KOMIIC, "88", "https://example.test/real.jpg", "作者")
        )
        r.libraryItems.first { list -> list.any { it.entry.id == "komiic:88" } }

        // 6 本示例数据全清掉
        assertEquals(6, r.purgeDemoData())

        val items = r.libraryItems.first { list -> list.size == 1 }
        assertEquals(listOf("komiic:88"), items.map { it.entry.id })
        // 章节与进度跟着一起走，不留孤儿
        assertEquals(0, r.chaptersOf("local-1").size)
        assertNull(r.progressOf("local-1"))
        // 幂等：再跑一次没有东西可删
        assertEquals(0, r.purgeDemoData())
    }

    @Test
    fun `cascade delete removes chapters and progress`() = runTest {
        val r = repo()
        r.seedDemoData()
        assertTrue(r.recentReading.first { it != null } != null)
        r.removeFromLibrary("local-1")
        val items = r.libraryItems.first { it.isNotEmpty() && it.none { i -> i.entry.id == "local-1" } }
        assertEquals(5, items.size)
        assertEquals(0, r.chaptersOf("local-1").size)
        // 进度必须一起清掉：只删 manga 会留孤儿进度，重新加回书架会"复活"上次阅读位置
        assertNull(r.progressOf("local-1"), "移出书架后进度没删干净")
        assertNull(r.recentReading.first { it == null })
    }

    @Test
    fun `backfill cover fills empty and never overwrites`() = runTest {
        val r = repo()
        // 导入一本没有封面的（搜索结果没封面 / 手工补的数据都会这样）
        r.importFromSource(
            SourceType.KOMIIC,
            app.gamanga.desktop.source.SourceManga(sourceId = "42", title = "无封面", coverUrl = null),
            emptyList()
        )
        val id = "komiic:42"
        fun entryOf(list: List<LibraryRepository.LibraryItem>) =
            list.firstOrNull { it.entry.id == id }?.entry

        // 注意：不能直接 first{} 读当前值——StateFlow 会立刻返回**上一次**的列表（老坑），
        // 必须等到"封面等于期望值"的那次发射。
        suspend fun awaitCover(expected: String?) =
            r.libraryItems.first { entryOf(it)?.coverUrl == expected }

        awaitCover(null) // 导入时没封面就该是空

        r.backfillCover(SourceType.KOMIIC, "42", "https://cdn/a.jpg")
        awaitCover("https://cdn/a.jpg")

        // 已有封面不能被后续某次详情解析冲掉
        r.backfillCover(SourceType.KOMIIC, "42", "https://cdn/b.jpg")
        r.backfillCover(SourceType.KOMIIC, "42", "   ")
        assertEquals("https://cdn/a.jpg", entryOf(r.libraryItems.first())?.coverUrl, "回填只该填空值")
    }

    @Test
    fun `unread filter semantics`() = runTest {
        val r = repo()
        r.seedDemoData()
        val unreadOnly = r.libraryItems.first { it.isNotEmpty() }.filter { it.unreadCount > 0 }
        assertEquals(4, unreadOnly.size) // 端脑/玄皓 已读完，其余 4 本有未读
        assertEquals(12L, unreadOnly.sumOf { it.unreadCount })
    }

    @Test
    fun `recent reading percent floors`() = runTest {
        val r = repo()
        r.seedDemoData()
        // 种子进度：page=5 / totalPages=9 → (5+1)*100/9 = 66.67 → 66
        val recent = r.recentReading.first { it != null }!!
        assertEquals(66, recent.percent)
        assertEquals("剑来", recent.mangaTitle)
        assertEquals("第 11 话", recent.chapterName)
    }

    @Test
    fun `prefs roundtrip`() {
        val r = repo()
        assertNull(r.getPref(LibraryRepository.PREF_WINDOW_SIZE))
        r.setPref(LibraryRepository.PREF_WINDOW_SIZE, "1280,800")
        assertEquals("1280,800", r.getPref(LibraryRepository.PREF_WINDOW_SIZE))
    }

    /**
     * 历史页（排期第 4 项）：按最近阅读时间倒序、带上书名/源/章节名。
     *
     * 时间戳直接写库（不用 saveProgress）——同一毫秒内两次调用排序不稳定，
     * 断言「倒序」必须用可控的 updatedAt。
     */
    @Test
    fun `history lists newest first with manga info`() = runTest {
        val r = repo()
        r.addToLibrary(
            MangaEntry(
                id = "k:1", title = "甲", source = SourceType.KOMIIC,
                sourceMangaId = "s1", coverUrl = "https://c/1.jpg"
            )
        )
        r.addToLibrary(
            MangaEntry(id = "g:2", title = "乙", source = SourceType.GODA, sourceMangaId = "s2")
        )
        // 甲有章节行 → 历史页能显示章节名；乙没有 → 章节名 null（LEFT JOIN）
        db.mangaQueries.insertChapter(
            id = "k:1#c1", mangaId = "k:1", name = "第1话",
            chapterIndex = 0, isRead = 0, pageCount = 20, lastOpenedAt = 0
        )
        db.mangaQueries.upsertProgress("k:1", "k:1#c1", 4, 20, 1_000L)
        db.mangaQueries.upsertProgress("g:2", "g:2#c9", 0, 0, 2_000L)

        val list = r.history.first { it.isNotEmpty() }
        assertEquals(2, list.size)
        assertEquals("乙", list[0].title, "2000 > 1000，新的在前")
        assertEquals("甲", list[1].title)
        assertEquals(SourceType.KOMIIC, list[1].source)
        assertEquals("s1", list[1].sourceMangaId)
        assertEquals("https://c/1.jpg", list[1].coverUrl)
        assertEquals("第1话", list[1].chapterName)
        assertEquals(4, list[1].page)
        assertEquals(20, list[1].totalPages)
        assertEquals(25, list[1].percent, "(4+1)/20 = 25%")
        // 乙没有章节行：章节名 null、无页数 → 百分比 0（不崩）
        assertNull(list[0].chapterName)
        assertEquals(0, list[0].percent)
    }

    @Test
    fun `delete progress clears history but keeps the book`() = runTest {
        val r = repo()
        r.addToLibrary(
            MangaEntry(id = "k:9", title = "删历史", source = SourceType.KOMIIC, sourceMangaId = "s9")
        )
        db.mangaQueries.upsertProgress("k:9", "k:9#c1", 3, 10, 5_000L)
        // 先等它出现在历史里（StateFlow 会先发初始空列表）
        r.history.first { list -> list.any { it.mangaId == "k:9" } }

        r.deleteProgress("k:9")

        val after = r.history.first { list -> list.none { it.mangaId == "k:9" } }
        assertTrue(after.none { it.mangaId == "k:9" }, "历史里应已移除")
        // 书架条目不受影响（只删 progress 行）
        val shelf = r.libraryItems.first { list -> list.any { it.entry.id == "k:9" } }
        assertTrue(shelf.any { it.entry.id == "k:9" }, "书应还在书架")
    }

    @Test
    fun `relative time buckets`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", app.gamanga.desktop.ui.relativeTime(now - 30_000L, now))
        assertEquals("5 分钟前", app.gamanga.desktop.ui.relativeTime(now - 5 * 60_000L, now))
        assertEquals("3 小时前", app.gamanga.desktop.ui.relativeTime(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", app.gamanga.desktop.ui.relativeTime(now - 2 * 86_400_000L, now))
        assertEquals("时间未知", app.gamanga.desktop.ui.relativeTime(0L, now))
        // 超过 7 天走日期格式（不依赖具体时区，只断言形如 yyyy-MM-dd）
        val old = app.gamanga.desktop.ui.relativeTime(now - 30 * 86_400_000L, now)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(old), "实际：$old")
    }

    @Test
    fun `cover color hex roundtrip`() = runTest {        val r = repo()
        r.addToLibrary(
            MangaEntry(id = "x1", title = "颜色", source = SourceType.KOMIIC, sourceMangaId = "s1"),
            coverColorArgb = 0xFFB5D4F4L
        )
        val item = r.libraryItems.first { list -> list.any { it.entry.id == "x1" } }
            .first { it.entry.id == "x1" }
        assertEquals(0xFFB5D4F4L, item.coverColorArgb)
    }

    @Test
    fun `color hex formatting is locale independent`() = runTest {
        val original = Locale.getDefault()
        try {
            // hi-IN 在 JVM 上会本地化十进制数字；%06X 的补零与十六进制不得被本地化
            Locale.setDefault(Locale("hi", "IN"))
            val r = repo()
            r.addToLibrary(
                MangaEntry(id = "x2", title = "本地化", source = SourceType.GODA, sourceMangaId = "s2"),
                coverColorArgb = 0xFF123456L
            )
            // 必须等含 x2 的那次发射：StateFlow 的 first() 会立刻返回初始空列表（老坑）
            val item = r.libraryItems.first { list -> list.any { it.entry.id == "x2" } }
                .first { it.entry.id == "x2" }
            assertEquals(0xFF123456L, item.coverColorArgb)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `chapters ordered ascending by index`() {
        val r = repo()
        r.seedDemoData()
        val chapters = r.chaptersOf("local-1")
        assertEquals(12, chapters.size)
        assertEquals((1..12).toList(), chapters.map { it.index })
        assertEquals(10, chapters.count { it.read })
    }

    // ---- 每章页数（#30）----

    @Test
    fun `chapter pageCount roundtrips import, read backfill and cache lookup`() {
        val r = repo()
        r.importFromSource(
            SourceType.KOMIIC,
            SourceManga(sourceId = "77", title = "页数"),
            listOf(
                // Komiic 源接口直给每章 size
                SourceChapter(id = "c1", name = "第 1 话", index = 0, pageCount = 18),
                // G社 形态：源站不给，导入时 0 = 未知
                SourceChapter(id = "c2", name = "第 2 话", index = 1, pageCount = 0)
            )
        )
        val chapters = r.chaptersOf("komiic:77")
        assertEquals(18, chapters[0].pageCount)
        assertEquals(0, chapters[1].pageCount)
        // 兜底查询只收有数的，键反解回源站章节 id
        assertEquals(mapOf("c1" to 18), r.cachedPageCounts("komiic:77"))

        // 阅读回填：第 2 话读到 24 页（阅读器取到整章图片后写回）
        r.updateChapterPageCount("komiic:77#c2", 24)
        assertEquals(24, r.chaptersOf("komiic:77")[1].pageCount)
        assertEquals(mapOf("c1" to 18, "c2" to 24), r.cachedPageCounts("komiic:77"))

        // 0/负数不回填：防止把已知页数冲成「未知」
        r.updateChapterPageCount("komiic:77#c2", 0)
        assertEquals(24, r.chaptersOf("komiic:77")[1].pageCount)
        // 行不存在（没入书架就试读）静默跳过，不抛
        r.updateChapterPageCount("komiic:77#no-such-chapter", 5)
        // 非库内漫画：空表，不抛
        assertEquals(emptyMap(), r.cachedPageCounts("komiic:404"))
    }

    @Test
    fun `seed chapters carry no pageCount`() {
        val r = repo()
        r.seedDemoData()
        assertTrue(r.chaptersOf("local-1").all { it.pageCount == 0 })
    }

    // ---- 置顶（R-09）----

    @Test
    fun `pinned items sort first in pin order`() {
        // 稳定排序：置顶组内按置顶先后，其余保持原序（addedAt DESC）
        assertEquals(
            listOf("c", "a", "b", "d"),
            sortByPinned(listOf("a", "b", "c", "d"), listOf("c", "a")) { it }
        )
    }

    @Test
    fun `empty or unknown pin list keeps original order`() {
        val rows = listOf("a", "b", "c")
        assertEquals(rows, sortByPinned(rows, emptyList()) { it })
        assertEquals(rows, sortByPinned(rows, listOf("zzz")) { it })
    }

    @Test
    fun `toggle pin appends then removes`() {
        assertEquals(listOf("a"), togglePinnedId(emptyList(), "a"))
        assertEquals(listOf("a", "b"), togglePinnedId(listOf("a"), "b"))
        assertEquals(listOf("b"), togglePinnedId(listOf("a", "b"), "a"))
    }

    @Test
    fun `pin survives repository restart`() = runTest {
        val r1 = repo()
        r1.seedDemoData()
        r1.togglePin("local-3")
        // 新实例 = 重启：置顶必须从 appPrefs 读回（schema 不加列的理由见 LibraryRepository）
        val r2 = repo()
        val items = r2.libraryItems.first { list -> list.any { it.pinned } }
        assertEquals("local-3", items.first().entry.id, "置顶项应排网格最前")
        assertTrue(items.first().pinned)
        assertTrue(items.drop(1).none { it.pinned }, "其余条目不该被带上置顶标")
    }

    @Test
    fun `remove from library prunes stale pin`() = runTest {
        val r = repo()
        r.seedDemoData()
        r.togglePin("local-3")
        r.removeFromLibrary("local-3")
        // 置顶列表里不留孤儿 id：否则同 id 的书重新入架会被"借尸还魂"顶到最前
        assertEquals("", r.getPref(LibraryRepository.PREF_PINNED))
        val r2 = repo()
        val items = r2.libraryItems.first { list -> list.isNotEmpty() && list.none { it.pinned } }
        assertTrue(items.none { it.pinned })
    }

    // ---- R-123：书架外试读也要进历史 ----

    @Test
    fun `external reading shows up in history but not on the shelf`() = runTest {
        val r = repo()
        r.ensureReadingRows(
            mangaId = "komiic:999",
            title = "书架外的书",
            source = SourceType.KOMIIC,
            sourceMangaId = "999",
            coverUrl = "https://img.example.com/999.jpg",
            chapterId = "komiic:999#c1",
            chapterName = "第 1 话",
            chapterIndex = 0,
        )
        r.saveProgress(ReadingProgress("komiic:999", "komiic:999#c1", 3, 20))

        // 历史里有：书名、章节名、页码都拿得到（JOIN 不再丢记录）
        val h = r.history.first { it.isNotEmpty() }.single()
        assertEquals("书架外的书", h.title)
        assertEquals("第 1 话", h.chapterName)
        assertEquals(3, h.page)
        assertEquals(20, h.totalPages)

        // 书架上没有它，书架「继续阅读」也不该被书架外的书占位
        assertTrue(r.libraryItems.value.isEmpty(), "幽灵行不能出现在书架里")
        assertNull(r.recentReading.value, "书架「继续阅读」只认书架内的书")
    }

    @Test
    fun `ensureReadingRows never downgrades a manga already in the library`() = runTest {
        val r = repo()
        r.seedDemoData()
        val before = r.libraryItems.first { it.isNotEmpty() }
        val original = before.first { it.entry.id == "local-1" }.entry.title
        val chapter = r.chaptersOf("local-1").first()
        db.mangaQueries.markChapterRead(1L, chapter.id)

        // 用**同一个 manga id + 同一个章节 id** 调「补历史行」：
        // 两条 INSERT OR IGNORE 都必须什么都不改（书名不被覆盖、已读状态不被重置）。
        // 这是幽灵行最危险的地方——一旦写成 INSERT OR REPLACE，用户书架里的书
        // 会被降级成 inLibrary = 0（从书架消失），章节已读也会被清掉。
        r.ensureReadingRows(
            mangaId = "local-1",
            title = "被幽灵行改掉的名字",
            source = SourceType.KOMIIC,
            sourceMangaId = "1",
            coverUrl = null,
            chapterId = chapter.id,
            chapterName = "被幽灵行改掉的章节名",
            chapterIndex = 99,
        )

        val after = r.libraryItems.first { it.isNotEmpty() }
        assertEquals(before.size, after.size, "书架本数不变（不能被降级成幽灵行）")
        assertEquals(original, after.first { it.entry.id == "local-1" }.entry.title, "书名不能被覆盖")
        val chapterAfter = r.chaptersOf("local-1").first { it.id == chapter.id }
        assertEquals(chapter.name, chapterAfter.name, "章节名不能被覆盖")
        assertTrue(chapterAfter.read, "已读状态不能被重置")
    }
}
