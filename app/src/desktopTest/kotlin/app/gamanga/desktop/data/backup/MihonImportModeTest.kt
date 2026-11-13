package app.gamanga.desktop.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * mihon 导入的行为回归（2026-10-30 用户实机反馈）。
 *
 * - **R-169**：换源后书架里的名字/封面要用**新源**的（原来用备份里的旧名字 + 失效的 mihon CDN 图）；
 * - **R-170**：备份里自带图片时原封不动用这张（解析层识别 `coverBytes`）；
 * - **R-178**：导入范围 = 「列哪些书」而不是「写不写进度」。
 *   只恢复书架 = 只列 mihon 书架里的书，**连阅读记录一起恢复**；
 *   书架 + 观看历史 = 连只有阅读记录的书也列出来。
 *
 * 全部用注入的 fake adapter + 内存库，不碰网络也不碰真实用户目录。
 */
class MihonImportModeTest {

    private fun newDb(): GamangaDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return GamangaDatabase(driver)
    }

    private class FakeAdapter(
        override val type: SourceType = SourceType.KOMIIC,
        override val displayName: String = "假源",
    ) : SourceAdapter {
        override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> = emptyList()
        override suspend fun detail(mangaId: String): SourceManga? = null
        override suspend fun chapters(mangaId: String): List<SourceChapter> = listOf(
            SourceChapter(id = "$mangaId-c1", name = "第1话", index = 0),
            SourceChapter(id = "$mangaId-c2", name = "第2话", index = 1),
        )

        override suspend fun pages(chapterId: String): List<PageImage> = emptyList()
    }

    /** 造一份最小备份：一本书、两章（第 1 章已读且读到第 5 页）。 */
    private fun backupBytes(
        title: String = "备份里的旧名字",
        coverBlob: ByteArray? = null,
        /** 备份里这一部在不在 mihon 书架（字段 100 = favorite）。null = 老备份没这个字段。 */
        favorite: Boolean? = null,
    ): ByteArray {
        val c1 = Proto()
            .string(1, "https://komiic.cc/comic/42/ch/1")
            .string(2, "第1话")
            .bool(4, true)
            .long(6, 5L)
            .string(9, "1")
            .build()
        val c2 = Proto()
            .string(1, "https://komiic.cc/comic/42/ch/2")
            .string(2, "第2话")
            .bool(4, false)
            .string(9, "2")
            .build()
        val manga = Proto()
            .string(2, "https://komiic.cc/comic/42")
            .string(3, title)
            .string(9, "https://old-cdn.invalid/cover.jpg")
            .bytes(16, c1)
            .bytes(16, c2)
            .apply {
                coverBlob?.let { bytes(20, it) }
                favorite?.let { bool(100, it) }
            }
            .build()
        return gzip(Proto().bytes(1, manga).build())
    }

    private fun manager(db: GamangaDatabase, repo: LibraryRepository) =
        BackupManager(db, repo) { FakeAdapter() }

    private fun choice() = MihonImportChoice(
        url = "https://komiic.cc/comic/42",
        alias = "新源名字",
        source = SourceType.KOMIIC,
        sourceId = "42",
        pickedTitle = "新源名字",
        pickedCover = "https://new-source.example/cover.jpg",
    )

    @Test
    fun `shelf only import keeps title and cover from the new source`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        val mgr = manager(db, repo)

        val report = mgr.importMihonSelection(
            backupBytes(),
            listOf(choice()),
            MihonImportMode.SHELF_ONLY,
        )

        assertEquals(1, report.mangaImported)
        val manga = db.mangaQueries.selectAllManga().executeAsList().single()
        assertEquals("新源名字", manga.title, "书架里的名字必须用换源后那本的名字")
        assertEquals("https://new-source.example/cover.jpg", manga.coverUrl, "封面也要用新源的")
    }

    @Test
    fun `shelf only import restores reading progress too`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        val mgr = manager(db, repo)

        mgr.importMihonSelection(backupBytes(), listOf(choice()), MihonImportMode.SHELF_ONLY)

        // 章节已读标记要保留（书架上的未读数才对）
        val chapters = db.mangaQueries.selectChaptersByManga("komiic:42").executeAsList()
        assertEquals(1, chapters.count { it.isRead == 1L })
        // R-178：用户明确「只选择书架的书恢复**同时恢复阅读记录**」→ 必须有进度行
        val progress = db.mangaQueries.selectAllProgress().executeAsList()
        assertEquals(1, progress.size, "只恢复书架也要写阅读进度（用户 2026-10-30 更正）")
        assertEquals(5L, progress.single().page)
    }

    @Test
    fun `shelf and history import writes progress`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        val mgr = manager(db, repo)

        val report = mgr.importMihonSelection(
            backupBytes(),
            listOf(choice()),
            MihonImportMode.SHELF_AND_HISTORY,
        )

        val progress = db.mangaQueries.selectAllProgress().executeAsList()
        assertEquals(1, progress.size)
        assertEquals(5L, progress.single().page)
        assertEquals(MihonImportMode.SHELF_AND_HISTORY, report.mode)
    }

    @Test
    fun `cover image inside backup is detected and marked in preview`() {
        // 造一张「PNG」：只认文件头 + 长度 > 512，内容不重要
        val png = ByteArray(600).also {
            it[0] = 0x89.toByte()
            it[1] = 0x50
            it[2] = 0x4E
            it[3] = 0x47
        }
        val parsed = parseMihonBackup(backupBytes(coverBlob = png))
        val m = parsed.manga.single()
        assertNotNull(m.coverBytes, "备份里的图片字节要能被认出来")
        assertEquals(600, m.coverBytes.size)

        val preview = buildMihonPreview(parsed, emptySet())
        assertTrue(preview.rows.single().hasCoverImage, "预览行要标出「含封面图」")
    }

    @Test
    fun `backup without image has no cover bytes`() {
        val parsed = parseMihonBackup(backupBytes())
        assertNull(parsed.manga.single().coverBytes)
        assertTrue(!buildMihonPreview(parsed, emptySet()).rows.single().hasCoverImage)
    }

    // ---- R-178：导入范围只决定「列哪些书」----

    /** 两本书：A 在 mihon 书架（favorite=1，没读过）；B 不在书架但读过（历史里）。 */
    private fun twoBooksBackup(): ByteArray {
        fun chapter(name: String, read: Boolean, pages: Long) = Proto()
            .string(1, "https://komiic.cc/comic/1/ch/$name")
            .string(2, name)
            .bool(4, read)
            .apply { if (pages > 0) long(6, pages) }
            .string(9, "1")
            .build()

        val shelfBook = Proto()
            .string(2, "https://komiic.cc/comic/1")
            .string(3, "书架里的书")
            .bytes(16, chapter("第1话", read = false, pages = 0))
            .bool(100, true)
            .build()
        val historyBook = Proto()
            .string(2, "https://komiic.cc/comic/2")
            .string(3, "只在历史里的书")
            .bytes(16, chapter("第1话", read = true, pages = 3))
            .bool(100, false)
            .build()
        return gzip(Proto().bytes(1, shelfBook).bytes(1, historyBook).build())
    }

    @Test
    fun `shelf only mode lists only books that are in the mihon library`() {
        val preview = buildMihonPreview(parseMihonBackup(twoBooksBackup()), emptySet())
        assertEquals(2, preview.rows.size, "解析层不能自己筛，范围交给界面")

        val shelf = preview.rows.filter { MihonImportMode.SHELF_ONLY.includes(it) }
        assertEquals(listOf("书架里的书"), shelf.map { it.title })

        val all = preview.rows.filter { MihonImportMode.SHELF_AND_HISTORY.includes(it) }
        assertEquals(2, all.size, "「书架 + 观看历史」要把只在历史里的书也列出来")
    }

    @Test
    fun `old backup without favorite flag still lists every book in shelf mode`() {
        // 老备份没有字段 100：不能因为读不到标志位就把整份备份筛成空列表
        val preview = buildMihonPreview(parseMihonBackup(backupBytes()), emptySet())
        assertEquals(1, preview.rows.count { MihonImportMode.SHELF_ONLY.includes(it) })
        assertTrue(preview.rows.single().inLibrary)
    }

    // ---- R-193（2026-10-30 实机）：进度回调抛异常**不能**打断导入 ----

    /**
     * 真实故障复现：桌面端没有 `Dispatchers.Main` 时，界面侧在进度回调里
     * `scope.launch(Dispatchers.Main)` 会**同步抛** `IllegalStateException`。
     *
     * 上一版 `importMihonSelection` 直接调 `onProgress(...)`，异常顺着回调冒进循环，
     * 整批导入在**第一本之前**就中断——用户看到「恢复备份之后书架/历史还是空的」。
     * 现在进度回报被 `runCatching` 兜住：UI 怎么炸都不影响数据落地。
     */
    @Test
    fun `cancelling while fetching second book preserves first and never writes second`() = kotlinx.coroutines.runBlocking {
        val db = newDb()
        val repo = LibraryRepository(db)
        val fetchingSecond = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : SourceAdapter by FakeAdapter() {
            override suspend fun chapters(mangaId: String): List<SourceChapter> {
                if (mangaId == "2") {
                    fetchingSecond.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                return FakeAdapter().chapters(mangaId)
            }
        }
        val mgr = BackupManager(db, repo) { adapter }
        val choices = listOf(
            MihonImportChoice("https://komiic.cc/comic/1", "第一本", SourceType.KOMIIC, "1"),
            MihonImportChoice("https://komiic.cc/comic/2", "第二本", SourceType.KOMIIC, "2"),
        )
        val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            mgr.importMihonSelection(twoBooksBackup(), choices)
        }
        try {
            kotlinx.coroutines.withTimeout(5000) { fetchingSecond.await() }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf("komiic:1"), db.mangaQueries.selectAllManga().executeAsList().map { it.id })
            assertEquals(2, repo.chaptersOf("komiic:1").size)
            assertTrue(repo.chaptersOf("komiic:2").isEmpty())
            assertNull(repo.progressOf("komiic:2"))
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `progress callback failure never aborts the import`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        val mgr = manager(db, repo)
        val choices = listOf(
            MihonImportChoice("https://komiic.cc/comic/1", "书架里的书", SourceType.KOMIIC, "1"),
            MihonImportChoice("https://komiic.cc/comic/2", "只在历史里的书", SourceType.KOMIIC, "2"),
        )

        val report = mgr.importMihonSelection(twoBooksBackup(), choices) {
            throw IllegalStateException("Module with the Main dispatcher is missing")
        }

        assertEquals(2, report.mangaImported, "进度回调抛异常不能让整批导入中断")
        assertEquals(2, db.mangaQueries.selectAllManga().executeAsList().size, "两本书都要真的落库")
    }
}
