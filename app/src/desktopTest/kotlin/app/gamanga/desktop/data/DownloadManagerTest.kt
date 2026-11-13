package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-04 下载管理器回归测试（内存 SQLite + 临时目录 + 假取图/假下载）。
 *
 * 覆盖：落盘完整性、进度单调、失败与重试、删除（含在飞取消的竞态）、
 * 重启续传、入队幂等、孤儿清理、路径净化、体积格式化。
 *
 * 用 runBlocking 而不是 runTest：下载跑在管理器自己的 Dispatchers.IO 上
 * （真实线程），虚拟时间会让 withTimeout 立即到期，等不到真实 IO。
 */
class DownloadManagerTest {

    private lateinit var db: GamangaDatabase
    private lateinit var rootDir: Path
    private lateinit var repository: LibraryRepository

    /** 取图桩：返回 [pageCount] 个假页面；可用 [failChapters] 注入失败。 */
    private var pageCount = 3
    private val failChapters = mutableSetOf<String>()
    private var failFirstAttempt = false
    private val attemptOf = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 下载桩：返回 10 字节的假图片；[blockFirst] 让第一页挂住等 [release]。 */
    private var blockFirst = false
    private val release = CompletableDeferred<Unit>()

    /** 每页前 N 次调用抛瞬时错误（测单页重试，第七轮 #3）。 */
    private var failPageAttempts = 0
    private val pageAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 配额错误桩：所有页都抛带 quota 的错误（测不重试 + 错误上下文）。 */
    private var quotaMode = false

    private fun pagesOf(type: SourceType, chapterId: String): List<PageImage> {
        if (chapterId in failChapters) throw IllegalStateException("取图失败：$chapterId")
        val attempt = (attemptOf[chapterId] ?: 0) + 1
        attemptOf[chapterId] = attempt
        if (failFirstAttempt && attempt == 1) throw IllegalStateException("首次必失败")
        return (0 until pageCount).map { PageImage(url = "https://img.example.com/$chapterId/p$it.jpg?x=1") }
    }

    private suspend fun fetchPage(page: PageImage, type: SourceType?): ByteArray {
        if (blockFirst && page.url.endsWith("p0.jpg?x=1")) {
            release.await()
        }
        if (quotaMode) throw IllegalStateException("HTTP 429 quota exceeded")
        val attempt = (pageAttempts[page.url] ?: 0) + 1
        pageAttempts[page.url] = attempt
        if (attempt <= failPageAttempts) throw IllegalStateException("HTTP 502 bad gateway")
        return ByteArray(10) { page.url[it % page.url.length].code.toByte() }
    }

    private lateinit var logDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        // 日志隔离：下载全链路会写 AppLog，别污染真实日志位置（第十轮收尾发现）
        logDir = Files.createTempDirectory("dlmgr-test")
        app.gamanga.desktop.core.AppLog.dirOverride = logDir
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = GamangaDatabase(driver)
        rootDir = Files.createTempDirectory("gamanga-dl-test")
        repository = LibraryRepository(db)
    }

    @AfterTest
    fun teardown() {
        release.complete(Unit) // 放掉还挂着的假下载，别把协程留在半空
        rootDir.toFile().deleteRecursively()
        app.gamanga.desktop.core.AppLog.dirOverride = null
        logDir.toFile().deleteRecursively()
    }

    private fun manager(
        maxParallelChapters: Int = 2,
        maxParallelPages: Int = 4,
        withRepository: Boolean = false,
    ) = DownloadManager(
        db = db,
        rootDir = rootDir,
        libraryRepository = if (withRepository) repository else null,
        maxParallelChapters = maxParallelChapters,
        maxParallelPages = maxParallelPages,
        pagesOf = ::pagesOf,
        fetchPage = ::fetchPage,
    )

    private fun importManga(mangaId: String = "komiic:228", title: String = "装备我最强") {
        repository.importFromSource(
            SourceType.KOMIIC,
            SourceManga(sourceId = "228", title = title),
            listOf(SourceChapter(id = "286", name = "286 密码", index = 0))
        )
        check(repository.chaptersOf(mangaId).isNotEmpty()) { "导入失败" }
    }

    /** 等所有任务离开 QUEUED/DOWNLOADING（真实时间轮询）。 */
    private suspend fun awaitIdle(m: DownloadManager, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (m.tasks.value.values.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }) {
                delay(30)
            }
        }
    }

    private fun chapterDirOf(mangaId: String, chapterId: String): Path =
        rootDir.resolve(DownloadManager.sanitizePathSegment(mangaId))
            .resolve(DownloadManager.sanitizePathSegment(chapterId))

    // ---- 落盘 ----

    @Test
    fun `missing middle page rejects partial local chapter without changing saved page count`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        awaitIdle(m)
        Files.delete(chapterDirOf("komiic:228", "286").resolve("0001.jpg"))
        assertTrue(m.localPages("komiic:228#286").isEmpty(), "缺图必须回到阅读器既有远端分支，不能压缩页序")
        assertEquals(3, repository.chaptersOf("komiic:228").single().pageCount)
        assertFalse(m.isCompleted("komiic:228#286"), "完整状态应核实实际文件")
    }

    @Test
    fun `zero byte page rejects local chapter and foreign files never become pages`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        awaitIdle(m)
        val dir = chapterDirOf("komiic:228", "286")
        Files.writeString(dir.resolve("notes.txt"), "not a page")
        assertEquals(3, m.localPages("komiic:228#286").size)
        Files.write(dir.resolve("0001.jpg"), byteArrayOf())
        assertTrue(m.localPages("komiic:228#286").isEmpty())
    }

    @Test
    fun `reimport and chapter sync preserve progress read state and completed download`() = runBlocking {
        importManga()
        val m = manager()
        try {
            m.enqueue("komiic:228", listOf(SourceChapter("286", "旧章节名", 0)))
            awaitIdle(m)
            repository.saveProgress(app.gamanga.desktop.model.ReadingProgress("komiic:228", "komiic:228#286", 1, 3))
            repository.setChapterRead("komiic:228#286", true)
            repository.setChapterOpened("komiic:228#286")
            val progress = repository.progressOf("komiic:228")
            val before = repository.chaptersOf("komiic:228").single()
            val download = db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOne()
            val pages = m.localPages("komiic:228#286")
            repository.hideFromLibrary(listOf("komiic:228"))
            repository.importFromSource(SourceType.KOMIIC, SourceManga("228", "新书名"), listOf(SourceChapter("286", "新章节名", 0)))
            repository.syncChapters("komiic:228", listOf(SourceChapter("286", "同步章节名", 0)))
            assertEquals(progress, repository.progressOf("komiic:228"))
            val after = repository.chaptersOf("komiic:228").single()
            assertTrue(after.read)
            assertEquals(before.lastOpenedAt, after.lastOpenedAt)
            assertEquals(3, after.pageCount)
            assertEquals("同步章节名", after.name)
            assertEquals(download, db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOne())
            assertEquals(pages, m.localPages("komiic:228#286"))
            assertEquals(1L, db.mangaQueries.selectMangaById("komiic:228").executeAsOne().inLibrary)
            val backup = app.gamanga.desktop.data.backup.BackupManager(db, repository)
            val zip = backup.exportBackup()
            backup.restoreBackup(zip)
            assertEquals(progress, repository.progressOf("komiic:228"))
            assertEquals(download, db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOne())
            assertEquals(pages, m.localPages("komiic:228#286"))
        } finally {
            m.pauseAll()
        }
    }

    @Test
    fun `download writes every page to disk and completes`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286 密码", index = 0)))
        awaitIdle(m)

        val task = m.tasks.value["komiic:228#286"]
        assertNotNull(task)
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(3, task.pageCount)
        assertEquals(3, task.downloadedPages)
        assertEquals(30L, task.totalBytes) // 3 页 × 10 字节
        assertTrue(m.isCompleted("komiic:228#286"))

        // 磁盘：一章一个目录，页序零填充，按文件名排序即阅读顺序
        val dir = chapterDirOf("komiic:228", "286")
        val files = dir.toFile().listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("0000.jpg", "0001.jpg", "0002.jpg"), files)

        // localPages：file:// 链接按页序排好，阅读器直接喂给 RemoteImage
        val pages = m.localPages("komiic:228#286")
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.url.startsWith("file://") })
        assertTrue(pages[0].url.endsWith("0000.jpg"))

        // 页数回填章节表（G社 唯一页数来源，与阅读器同一条路）
        assertEquals(3, repository.chaptersOf("komiic:228").single().pageCount)
    }

    @Test
    fun `progress is monotonic while downloading`() = runBlocking {
        importManga()
        blockFirst = true // 最后一页（p0）挂住，前面 3 页先跑完
        pageCount = 4
        val m = manager(maxParallelPages = 4)
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))

        // 等挂起页真的进了 fetchPage：此时前 3 页已完成（done=3），第 4 页在飞。
        // done 按「写完一页 +1」计数，p0 不落地前恒为 3，轮询必然等到。
        //
        // 20s 超时的来历（2026-10-24）：原来 5s，机器负载高时（并行跑全量测试 + 编译）
        // 4 页模拟下载跑不完，偶发 TimeoutCancellationException。
        //
        // 2026-10-26 加固：原来写的是 `while (downloadedPages != 3)`——一旦 done 因为
        // 任何原因跳过 3（比如挂起没生效、p0 被放行后直接冲到 4），循环就**永远等不到**，
        // 只能干耗满 20s 抛一句 TimeoutCancellationException，看不出真因。
        // 现在改成「等 >= 3」+ 每轮检查任务还在跑：任务提前结束（COMPLETED/FAILED）
        // 立刻断言失败并带上状态与错误，把 20s 的哑超时变成一秒内的有效报错。
        // 语义不变：p0 挂住时 done 不可能超过 3，循环退出时必然是 3。
        withTimeout(20_000) {
            while ((m.tasks.value["komiic:228#286"]?.downloadedPages ?: 0) < 3) {
                val snap = m.tasks.value["komiic:228#286"]
                assertTrue(
                    snap != null &&
                        (snap.status == DownloadStatus.QUEUED || snap.status == DownloadStatus.DOWNLOADING),
                    "任务提前结束，挂起页没有生效：status=${snap?.status} done=${snap?.downloadedPages} err=${snap?.error}"
                )
                delay(10)
            }
        }
        val mid = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.DOWNLOADING, mid.status)
        assertEquals(3, mid.downloadedPages) // 严格等于 3：p0 还挂在 release 上

        release.complete(Unit)
        awaitIdle(m)
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#286"]!!.status)
        assertEquals(4, m.tasks.value["komiic:228#286"]!!.downloadedPages)
    }

    // ---- 失败与重试 ----

    @Test
    fun `failed chapter marks FAILED and queue continues to next chapter`() = runBlocking {
        importManga()
        failChapters += "bad"
        val m = manager(maxParallelChapters = 1)
        m.enqueue(
            "komiic:228",
            listOf(
                SourceChapter(id = "bad", name = "坏话", index = 0),
                SourceChapter(id = "good", name = "好话", index = 1),
            )
        )
        awaitIdle(m)

        val bad = m.tasks.value["komiic:228#bad"]!!
        assertEquals(DownloadStatus.FAILED, bad.status)
        assertNotNull(bad.error)
        assertTrue(bad.error.contains("取图失败"))
        // 队列不能因为一章失败就停：后面的好话必须下完
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#good"]!!.status)
    }

    @Test
    fun `retry after failure completes`() = runBlocking {
        importManga()
        failFirstAttempt = true
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        assertEquals(DownloadStatus.FAILED, m.tasks.value["komiic:228#286"]!!.status)

        failFirstAttempt = false
        m.retry("komiic:228#286")
        awaitIdle(m)
        val task = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(3, task.downloadedPages)
    }

    // ---- 删除（含在飞取消的竞态）----

    @Test
    fun `delete removes files, row and mirror`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        assertTrue(chapterDirOf("komiic:228", "286").toFile().exists())

        m.delete("komiic:228#286")
        assertFalse(m.isCompleted("komiic:228#286"))
        assertFalse(chapterDirOf("komiic:228", "286").toFile().exists())
        assertFalse(m.tasks.value.containsKey("komiic:228#286"))
        assertNull(db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOneOrNull())
    }

    @Test
    fun `delete during download cancels job without resurrecting row`() = runBlocking {
        importManga()
        blockFirst = true
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        // 等第一页挂进 fetchPage（任务在飞）
        withTimeout(5_000) {
            while (m.tasks.value["komiic:228#286"]?.status != DownloadStatus.DOWNLOADING) delay(10)
        }

        m.delete("komiic:228#286") // 取消在飞协程 + 删行删文件
        release.complete(Unit)    // 放掉挂起页：协程随后被取消/报错都不该写回状态
        delay(300)                // 给取消落地的时间

        assertNull(db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOneOrNull())
        assertFalse(m.tasks.value.containsKey("komiic:228#286"))
        assertFalse(chapterDirOf("komiic:228", "286").toFile().exists())
    }

    // ---- 重启续传 ----

    @Test
    fun `interrupted DOWNLOADING task is resumed by a fresh manager`() = runBlocking {
        importManga()
        // 模拟上次进程被杀：手工留一个 DOWNLOADING 行（文件可有可无）
        db.downloadQueries.upsertDownload(
            id = "komiic:228#286",
            mangaId = "komiic:228",
            chapterId = "286",
            name = "286 密码",
            pageCount = 3,
            downloadedPages = 1,
            status = DownloadStatus.DOWNLOADING.name,
            error = null,
            totalBytes = 10,
            updatedAt = 1L,
            createdAt = 1L,
        )

        val m = manager()
        m.resumeInterrupted()
        awaitIdle(m)

        val task = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(3, task.downloadedPages)
        // 重跑前会清空旧目录：不会混入上次残页
        assertEquals(listOf("0000.jpg", "0001.jpg", "0002.jpg"), chapterDirOf("komiic:228", "286").toFile().listFiles()!!.map { it.name }.sorted())
    }

    // ---- 入队幂等 ----

    @Test
    fun `enqueue skips completed and queued but retries failed`() = runBlocking {
        importManga()
        val m = manager()
        val ch286 = SourceChapter(id = "286", name = "286", index = 0)
        val ch290 = SourceChapter(id = "290", name = "290", index = 1)

        m.enqueue("komiic:228", listOf(ch286))
        awaitIdle(m)
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#286"]!!.status)

        // 已完成 → 跳过（不重复入队、不重置进度）
        m.enqueue("komiic:228", listOf(ch286))
        awaitIdle(m)
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#286"]!!.status)
        assertEquals(3, m.tasks.value["komiic:228#286"]!!.downloadedPages)
        assertEquals(1, m.tasks.value.size)

        // 失败 → 再次 enqueue 同一章就是重试（区别于 retry() 的显式入口）
        failChapters += "290"
        m.enqueue("komiic:228", listOf(ch290))
        awaitIdle(m)
        assertEquals(DownloadStatus.FAILED, m.tasks.value["komiic:228#290"]!!.status)

        failChapters -= "290"
        m.enqueue("komiic:228", listOf(ch290))
        awaitIdle(m)
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#290"]!!.status)
        assertEquals(2, m.tasks.value.size)
    }

    // ---- 孤儿清理 ----

    @Test
    fun `removing manga from library cleans download files and rows`() = runBlocking {
        importManga()
        val m = manager(withRepository = true)
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        assertTrue(chapterDirOf("komiic:228", "286").toFile().exists())

        repository.removeFromLibrary("komiic:228")

        // 行由外键级联删；文件与镜像由管理器监听 libraryItems 收尾（异步，轮询等它）。
        // 注意要等镜像也收干净：cleanupOrphans 里删目录→删行→收镜像是顺序执行的，
        // 只等目录会在机器繁忙时抢跑（全套跑比孤立跑更容易复现）。
        withTimeout(5_000) {
            while (chapterDirOf("komiic:228", "286").toFile().exists()
                || m.tasks.value.containsKey("komiic:228#286")
            ) delay(20)
        }
        assertNull(db.downloadQueries.selectDownloadById("komiic:228#286").executeAsOneOrNull())
        assertFalse(m.tasks.value.containsKey("komiic:228#286"))
    }

    // ---- 并发上限 ----

    @Test
    fun `chapter parallelism is capped by maxParallelChapters`() = runBlocking {
        importManga()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val countingPagesOf: suspend (SourceType, String) -> List<PageImage> = { type, chapterId ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(80) // 让两章有机会叠在一起（若上限失效）
            active.decrementAndGet()
            pagesOf(type, chapterId)
        }
        val m = DownloadManager(
            db = db, rootDir = rootDir, maxParallelChapters = 1,
            pagesOf = countingPagesOf, fetchPage = ::fetchPage,
        )
        m.enqueue(
            "komiic:228",
            listOf(
                SourceChapter(id = "a", name = "A", index = 0),
                SourceChapter(id = "b", name = "B", index = 1),
                SourceChapter(id = "c", name = "C", index = 2),
            )
        )
        awaitIdle(m)
        assertEquals(1, peak.get(), "maxParallelChapters=1 时取图不能并发")
        assertEquals(3, m.tasks.value.values.count { it.status == DownloadStatus.COMPLETED })
    }

    // ---- 第七轮 #2：列表顺序稳定 / 并发可调 ----

    @Test
    fun `download list keeps enqueue order regardless of progress updates`() = runBlocking {
        importManga()
        // 并发 1：三章顺序下完，每下完一章 updatedAt 都会变——排序按 createdAt
        // （入队序）绝不能跟着抖。旧实现按 updatedAt 排，用户实测「正在下载的
        // 两个任务在列表中来回跳动」。
        val m = manager(maxParallelChapters = 1)
        m.enqueue(
            "komiic:228",
            listOf(
                SourceChapter(id = "a", name = "A", index = 0),
                SourceChapter(id = "b", name = "B", index = 1),
                SourceChapter(id = "c", name = "C", index = 2),
            )
        )
        awaitIdle(m)

        val rows = db.downloadQueries.selectAllDownloads().executeAsList()
        assertEquals(
            listOf("komiic:228#a", "komiic:228#b", "komiic:228#c"),
            rows.map { it.id },
            "下载列表必须按入队序稳定排列"
        )
        // createdAt 严格递增（同一次 enqueue 的多章也不能并列）
        assertTrue(rows[0].createdAt < rows[1].createdAt)
        assertTrue(rows[1].createdAt < rows[2].createdAt)
        // updatedAt 同样递增（并发 1 顺序下完）——若按它倒序排（旧实现），
        // 行序会变成 c,b,a 并随每页进度抖动；现在入队序钉死不变。
        assertTrue(rows[0].updatedAt <= rows[1].updatedAt)
        assertTrue(rows[1].updatedAt <= rows[2].updatedAt)
    }

    @Test
    fun `setMaxParallelChapters widens the gate at runtime`() = runBlocking {
        importManga()
        val active = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val countingPagesOf: suspend (SourceType, String) -> List<PageImage> = { type, chapterId ->
            active.incrementAndGet()
            gate.await() // 挂住，等测试把并发调大
            pagesOf(type, chapterId)
        }
        val m = DownloadManager(
            db = db, rootDir = rootDir, maxParallelChapters = 1,
            pagesOf = countingPagesOf, fetchPage = ::fetchPage,
        )
        m.enqueue(
            "komiic:228",
            listOf(
                SourceChapter(id = "a", name = "A", index = 0),
                SourceChapter(id = "b", name = "B", index = 1),
                SourceChapter(id = "c", name = "C", index = 2),
            )
        )
        withTimeout(5_000) { while (active.get() < 1) delay(10) }
        assertEquals(1, m.maxParallelChapters())

        m.setMaxParallelChapters(2)
        assertEquals(2, m.maxParallelChapters())
        // 调大后 pump 立刻补位：第二章挂进来（不用等第一章完成）
        withTimeout(5_000) { while (active.get() < 2) delay(10) }

        gate.complete(Unit)
        awaitIdle(m)
        assertEquals(3, m.tasks.value.values.count { it.status == DownloadStatus.COMPLETED })
    }

    // ---- 第七轮 #3：单页重试 + 错误上下文 ----

    @Test
    fun `transient page failure is retried instead of failing the chapter`() = runBlocking {
        importManga()
        failPageAttempts = 2 // 每页前两次抛瞬时错误，第三次成功
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)

        val task = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.COMPLETED, task.status, "单页瞬时失败重试后应成功：${task.error}")
        assertEquals(3, task.downloadedPages)
        assertTrue(pageAttempts.values.all { it == 3 }, "每页应恰好试 3 次（2 次失败 + 1 次成功）")
    }

    @Test
    fun `quota page failure fails fast with page context`() = runBlocking {
        importManga()
        quotaMode = true
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)

        val task = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.FAILED, task.status)
        assertNotNull(task.error)
        assertTrue(task.error.contains("页下载失败"), "错误要带页码上下文：${task.error}")
        assertTrue(task.error.contains("quota"), "配额原因要透传给用户：${task.error}")
        assertTrue(pageAttempts.values.all { it == 1 }, "配额错误不重试（重试只是白烧）")
    }

    @Test
    fun `page list failure reports the stage`() = runBlocking {
        importManga()
        failChapters += "286"
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)

        val task = m.tasks.value["komiic:228#286"]!!
        assertEquals(DownloadStatus.FAILED, task.status)
        assertTrue(task.error!!.contains("取图片列表失败"), "错误要带阶段：${task.error}")
    }

    // ---- 第七轮 #7：缓存用量 / 清理 / 换位置 ----

    @Test
    fun `relocate waits for cancelled download cleanup before copying`() = runBlocking {
        importManga()
        val started = CompletableDeferred<Unit>()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val copiedAfterStop = java.util.concurrent.atomic.AtomicBoolean(false)
        val m = DownloadManager(db, rootDir, pagesOf = ::pagesOf, fetchPage = { _, _ ->
            started.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(100) }
                stopped.set(true)
            }
        }, copyDirectory = { source, target ->
            copiedAfterStop.set(stopped.get())
            source.toFile().copyRecursively(target.toFile())
        })
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        withTimeout(5000) { started.await() }
        val target = Files.createTempDirectory("gamanga-relocate-active")
        try {
            assertNull(m.relocate(target))
            assertTrue(copiedAfterStop.get(), "复制必须在取消收尾完成后")
        } finally {
            m.pauseAll()
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relocate copy failure removes staging and leaves original readable`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        awaitIdle(m)
        val failing = DownloadManager(db, rootDir, pagesOf = ::pagesOf, fetchPage = ::fetchPage, copyDirectory = { _, target ->
            Files.createDirectories(target)
            Files.writeString(target.resolve("partial.jpg"), "partial")
            throw java.io.IOException("injected copy fault")
        })
        val target = Files.createTempDirectory("gamanga-relocate-fault")
        try {
            Files.writeString(target.resolve("keep.txt"), "user data")
            assertNotNull(failing.relocate(target))
            assertEquals(rootDir, failing.rootDir())
            assertEquals(3, failing.localPages("komiic:228#286").size)
            assertEquals(listOf("keep.txt"), target.toFile().listFiles()!!.map { it.name })
            assertEquals("user data", Files.readString(target.resolve("keep.txt")))
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relocate rejects nested root and preserves original files`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        awaitIdle(m)
        assertNotNull(m.relocate(rootDir.resolve("nested")), "目标在旧目录内部会被随后清理一起删除，必须拒绝")
        assertEquals(rootDir, m.rootDir())
        assertEquals(3, m.localPages("komiic:228#286").size)
    }

    @Test
    fun `relocate collision preserves destination file and original download`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter("286", "第一话", 0)))
        awaitIdle(m)
        val target = Files.createTempDirectory("gamanga-relocate-conflict")
        try {
            val collision = target.resolve(DownloadManager.sanitizePathSegment("komiic:228"))
            Files.writeString(collision, "user data")
            assertNotNull(m.relocate(target))
            assertEquals("user data", Files.readString(collision))
            assertEquals(rootDir, m.rootDir())
            assertEquals(3, m.localPages("komiic:228#286").size)
        } finally {
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cache usage counts disk bytes and clear removes them`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)

        val usage = m.cacheUsage()
        assertEquals(1, usage.size)
        assertEquals("装备我最强", usage[0].mangaTitle)
        assertEquals(30L, usage[0].bytes, "按磁盘实际字节（3 页 × 10）")
        assertEquals(30L, m.totalCacheBytes())

        m.clearAllCache()
        assertTrue(m.cacheUsage().isEmpty())
        assertEquals(0L, m.totalCacheBytes())
        assertTrue(m.tasks.value.isEmpty())
        assertFalse(chapterDirOf("komiic:228", "286").toFile().exists())
    }

    @Test
    fun `relocate moves files and keeps downloads readable`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        assertTrue(chapterDirOf("komiic:228", "286").toFile().exists())

        val newRoot = Files.createTempDirectory("gamanga-dl-test-new")
        try {
            assertNull(m.relocate(newRoot)) // null = 成功（第八轮 #7 改为返回失败原因）
            assertEquals(newRoot.toAbsolutePath().normalize(), m.rootDir().toAbsolutePath().normalize())
            // 文件搬到新位置
            val moved = newRoot.resolve(DownloadManager.sanitizePathSegment("komiic:228"))
                .resolve(DownloadManager.sanitizePathSegment("286"))
            assertTrue(moved.toFile().exists())
            assertEquals(3, moved.toFile().listFiles()!!.size)
            // 旧位置清空
            assertFalse(chapterDirOf("komiic:228", "286").toFile().exists())
            // DB 不动，localPages 从新根继续读
            assertTrue(m.isCompleted("komiic:228#286"))
            assertEquals(3, m.localPages("komiic:228#286").size)
            // 同一目录再 relocate = no-op 成功
            assertNull(m.relocate(newRoot))
        } finally {
            newRoot.toFile().deleteRecursively()
        }
    }

    // ---- 缓存清理安全（第八轮 #7：用户明确要求「不要把系统或其他不相关文件删掉」）----

    @Test
    fun `clear all cache removes only whitelisted manga dirs`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        // 缓存根目录里的「外来物」：用户自置的目录和文件（位置是用户自选目录时会有）
        val foreignDir = rootDir.resolve("我的照片").toFile().apply { mkdirs() }
        val foreignFile = rootDir.resolve("重要文档.txt").toFile().apply { writeText("do not touch") }
        val mangaDir = rootDir.resolve(DownloadManager.sanitizePathSegment("komiic:228")).toFile()
        assertTrue(mangaDir.isDirectory)

        val ok = m.clearAllCache()

        assertTrue(ok, "带归属标记的缓存目录应放行")
        assertFalse(mangaDir.exists(), "白名单漫画目录要删")
        assertTrue(foreignDir.exists(), "外来目录绝不能动")
        assertTrue(foreignFile.exists() && foreignFile.readText() == "do not touch", "外来文件绝不能动")
        assertTrue(m.tasks.value.isEmpty(), "任务镜像清空")
    }

    @Test
    fun `clear all cache refuses when ownership marker is missing`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        // 模拟「pref 指错地方」：目录不是 Gamanga 缓存目录（标记被删/从未建）
        rootDir.resolve(".gamanga-downloads").toFile().delete()
        val mangaDir = rootDir.resolve(DownloadManager.sanitizePathSegment("komiic:228")).toFile()

        val ok = m.clearAllCache()

        assertFalse(ok, "没有归属标记 = 不是我们的目录，拒绝执行")
        assertTrue(mangaDir.exists(), "拒绝时一个字节都不删")
        assertTrue(m.tasks.value.isNotEmpty(), "任务行也不清（拒绝是整体的）")
    }

    @Test
    fun `relocate rejects system and personal directories`() {
        val m = manager()
        val home = java.nio.file.Paths.get(System.getProperty("user.home"))
        assertNotNull(m.relocate(home), "用户主目录要拒")
        assertNotNull(m.relocate(home.resolve("Desktop")), "桌面要拒")
        assertNotNull(m.relocate(home.resolve("Documents")), "文档要拒")
        assertNotNull(m.relocate(home.resolve("Downloads")), "下载文件夹要拒")
        assertNotNull(m.relocate(java.nio.file.Paths.get("C:\\")), "盘符根要拒")
        // 被拒后位置不变
        assertEquals(rootDir, m.rootDir())
    }

    @Test
    fun `relocate moves only whitelist dirs and leaves foreign files`() = runBlocking {
        importManga()
        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = "286", name = "286", index = 0)))
        awaitIdle(m)
        val foreignFile = rootDir.resolve("我的照片").toFile().apply {
            mkdirs(); resolve("a.jpg").writeText("photo bytes")
        }
        val newRoot = Files.createTempDirectory("gamanga-dl-test-whitelist")
        try {
            assertNull(m.relocate(newRoot))
            // 新位置：白名单漫画目录 + 归属标记；外来目录没跟过来
            assertTrue(
                newRoot.resolve(DownloadManager.sanitizePathSegment("komiic:228")).toFile().isDirectory
            )
            assertTrue(newRoot.resolve(".gamanga-downloads").toFile().exists(), "新目录要打归属标记")
            assertFalse(newRoot.resolve("我的照片").toFile().exists(), "外来目录不复制")
            // 旧位置：白名单目录删了，外来文件原样
            assertFalse(rootDir.resolve(DownloadManager.sanitizePathSegment("komiic:228")).toFile().exists())
            assertTrue(foreignFile.exists() && foreignFile.resolve("a.jpg").readText() == "photo bytes")
        } finally {
            newRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pause all cancels in-flight and resume requeues`() = runBlocking {
        importManga()
        val m = manager(maxParallelChapters = 1)
        blockFirst = true // 第一章第一页挂住，制造「一个在飞 + 若干排队」
        m.enqueue(
            "komiic:228",
            listOf(
                SourceChapter(id = "a", name = "a", index = 0),
                SourceChapter(id = "b", name = "b", index = 1),
            )
        )
        delay(300) // 让第一章开始（挂第一页）
        m.pauseAll()
        assertTrue(m.isPaused())
        // 真停（第九轮 #11）：在飞协程被取消、状态回退，不能有 DOWNLOADING 残留
        delay(300)
        var statuses = m.tasks.value.values.map { it.status }
        assertEquals(0, statuses.count { it == DownloadStatus.DOWNLOADING }, "暂停后不应有 DOWNLOADING（实际=$statuses）")
        assertTrue(statuses.all { it == DownloadStatus.QUEUED }, "未完成任务应全部回 QUEUED（实际=$statuses）")
        // 半章文件保留（取消不删目录，续跑时重下该章）
        assertTrue(chapterDirOf("komiic:228", "a").toFile().exists(), "半章目录应保留")
        // 暂停中再入队：不启动
        m.enqueue("komiic:228", listOf(SourceChapter(id = "c", name = "c", index = 2)))
        delay(300)
        statuses = m.tasks.value.values.map { it.status }
        assertEquals(0, statuses.count { it == DownloadStatus.DOWNLOADING }, "暂停中入队也不启动")
        // 恢复：放掉挂起页 + resumeAll → 队列继续跑完
        release.complete(Unit)
        m.resumeAll()
        assertFalse(m.isPaused())
        awaitIdle(m)
        assertTrue(m.tasks.value.values.all { it.status == DownloadStatus.COMPLETED })
    }

    // ---- 断点续传（第十轮 #6：暂停/失败/重启后只补剩余页，不再从 0 重下）----

    @Test
    fun `resumed chapter only downloads missing pages`() = runBlocking {
        importManga()
        val chapterId = "286"
        // 预置「上一轮下了一半」的现场：章目录里已有 p0/p1（内容是 OLD 标记），
        // DB 行停在 DOWNLOADING、downloadedPages=2（模拟暂停/进程被杀）
        val dir = chapterDirOf("komiic:228", chapterId).toFile().apply { mkdirs() }
        dir.resolve("0000.jpg").writeText("OLD_PAGE_0")
        dir.resolve("0001.jpg").writeText("OLD_PAGE_1")
        db.downloadQueries.upsertDownload(
            id = "komiic:228#$chapterId",
            mangaId = "komiic:228",
            chapterId = chapterId,
            name = "286 密码",
            pageCount = 3,
            downloadedPages = 2,
            status = DownloadStatus.DOWNLOADING.name,
            error = null,
            totalBytes = 20,
            updatedAt = 1L,
            createdAt = 1L,
        )

        // 新 manager（重启语义）：resumeInterrupted 把任务退回队列
        val m = manager()
        m.resumeInterrupted()
        awaitIdle(m)

        // 断点续传断言：已下的两页原封不动，只补了第 3 页
        assertEquals("OLD_PAGE_0", dir.resolve("0000.jpg").readText(), "p0 不应重下")
        assertEquals("OLD_PAGE_1", dir.resolve("0001.jpg").readText(), "p1 不应重下")
        assertEquals(3, dir.listFiles()!!.size, "应补齐到 3 页")
        assertTrue(
            dir.resolve("0002.jpg").readText() != "OLD_PAGE_2" && dir.resolve("0002.jpg").length() > 0,
            "p2 应是新下载的内容"
        )
        val task = m.tasks.value["komiic:228#$chapterId"]!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(3, task.downloadedPages)
    }

    @Test
    fun `retry after failure continues from disk`() = runBlocking {
        importManga()
        val chapterId = "286"
        // 失败现场：p0 已下、p1/p2 缺
        val dir = chapterDirOf("komiic:228", chapterId).toFile().apply { mkdirs() }
        dir.resolve("0000.jpg").writeText("OLD_PAGE_0")
        db.downloadQueries.upsertDownload(
            id = "komiic:228#$chapterId",
            mangaId = "komiic:228",
            chapterId = chapterId,
            name = "286 密码",
            pageCount = 3,
            downloadedPages = 1,
            status = DownloadStatus.FAILED.name,
            error = "第 2/3 页下载失败：HTTP 502",
            totalBytes = 10,
            updatedAt = 1L,
            createdAt = 1L,
        )

        val m = manager()
        m.enqueue("komiic:228", listOf(SourceChapter(id = chapterId, name = "286", index = 0)))
        awaitIdle(m)

        assertEquals("OLD_PAGE_0", dir.resolve("0000.jpg").readText(), "retry 也不重下已有页")
        assertEquals(3, dir.listFiles()!!.size)
        assertEquals(DownloadStatus.COMPLETED, m.tasks.value["komiic:228#$chapterId"]!!.status)
    }

    // ---- 纯函数 ----

    @Test
    fun `sanitize path segment keeps ascii word chars`() {
        assertEquals("komiic_228", DownloadManager.sanitizePathSegment("komiic:228"))
        assertEquals("komiic_228_3276", DownloadManager.sanitizePathSegment("komiic:228#3276"))
        assertEquals("a_b_c", DownloadManager.sanitizePathSegment("a/b\\c"))
        assertEquals("keep.as-is_1", DownloadManager.sanitizePathSegment("keep.as-is 1"))
        // 空段映射为空串（真实 id 不会为空；目录拼接时由调用方保证前缀非空）
        assertEquals("", DownloadManager.sanitizePathSegment(""))
    }

    @Test
    fun `extension of url ignores query and falls back to jpg`() {
        assertEquals("jpg", DownloadManager.extensionOf("https://a.com/x.jpg?sig=1"))
        assertEquals("png", DownloadManager.extensionOf("https://a.com/x.PNG"))
        assertEquals("webp", DownloadManager.extensionOf("https://a.com/x.webp#frag"))
        assertEquals("jpg", DownloadManager.extensionOf("https://a.com/no-ext"))
        assertEquals("jpg", DownloadManager.extensionOf("https://a.com/x.php?id=1"))
    }

    @Test
    fun `format bytes scales units`() {
        assertEquals("512 B", DownloadManager.formatBytes(512))
        assertEquals("1.5 KB", DownloadManager.formatBytes(1536))
        assertEquals("1.5 MB", DownloadManager.formatBytes(1536L * 1024))
        assertEquals("1.50 GB", DownloadManager.formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun `source type of manga id`() {
        assertEquals(SourceType.KOMIIC, DownloadManager.sourceTypeOf("komiic:228"))
        assertEquals(SourceType.GODA, DownloadManager.sourceTypeOf("goda:x"))
        assertNull(DownloadManager.sourceTypeOf("local-1"))
        assertNull(DownloadManager.sourceTypeOf(""))
    }

    @Test
    fun `progress clamps to zero one range`() {
        val base = DownloadTask(id = "i", mangaId = "m", chapterId = "c", chapterName = "n")
        assertEquals(0f, base.progress)
        assertEquals(0.5f, base.copy(pageCount = 4, downloadedPages = 2).progress)
        assertEquals(1f, base.copy(pageCount = 3, downloadedPages = 9).progress)
    }
}
