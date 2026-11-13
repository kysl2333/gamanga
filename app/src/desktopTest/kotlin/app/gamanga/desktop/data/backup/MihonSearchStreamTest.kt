package app.gamanga.desktop.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 换源搜索的**流式 + 并发**回归（R-166，2026-10-29）。
 *
 * 用户原话：「搜索速度太慢，搜索到的优先发出来」。原来 `searchMihonCandidates` 是
 * `for (type in types)` 串行 + 全部结束才返回，总耗时 = 最慢那个源（拷贝漫画 DOM 兜底能到 20s），
 * 期间界面只有一个「搜索中…」文字，什么也看不到。
 *
 * 这个测试锁两件事，任何一条挂了就说明又退回串行/一次性返回：
 * 1. **先到先出**：快源的结果必须在慢源完成**之前**就拿到（不是等全部）；
 * 2. **单源超时只丢这个源**：挂住的源不会把整次搜索拖死。
 *
 * 用注入的 fake adapter（`BackupManager` 的第三个参数），不碰网络。
 */
class MihonSearchStreamTest {

    private fun newDb(): GamangaDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return GamangaDatabase(driver)
    }

    private fun manager(vararg adapters: SourceAdapter): BackupManager {
        val db = newDb()
        val byType = adapters.associateBy { it.type }
        return BackupManager(db, LibraryRepository(db)) { byType[it] }
    }

    /** 只实现 search 的假源；[delayMs] 模拟网络耗时。 */
    private class FakeAdapter(
        override val type: SourceType,
        override val displayName: String,
        private val delayMs: Long,
        private val results: List<SourceManga>,
    ) : SourceAdapter {
        override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
            if (delayMs > 0) delay(delayMs)
            return results
        }

        override suspend fun detail(mangaId: String): SourceManga? = null
        override suspend fun chapters(mangaId: String): List<SourceChapter> = emptyList()
        override suspend fun pages(chapterId: String): List<PageImage> = emptyList()
    }

    @Test
    fun `fast source is emitted before slow one finishes`() = runTest {
        val fast = FakeAdapter(SourceType.KOMIIC, "快源", 0, listOf(SourceManga("s1", "标题 A")))
        val slow = FakeAdapter(SourceType.MANGABZ, "慢源", 2_000, listOf(SourceManga("s2", "标题 B")))
        val mgr = manager(fast, slow)

        val started = System.currentTimeMillis()
        val first = mgr.searchMihonCandidatesStreaming(
            alias = "标题",
            originalTitle = "标题",
            types = listOf(SourceType.KOMIIC, SourceType.MANGABZ),
        ).first()
        val elapsed = System.currentTimeMillis() - started

        assertEquals(SourceType.KOMIIC, first.source, "先到的是快源那条")
        // 慢源 2000ms：如果又退回「等全部」，这里必然 >2000
        assertTrue(elapsed < 1_000, "第一条应在慢源完成前到达，实测 ${elapsed}ms")
    }

    @Test
    fun `hanging source is dropped by per source timeout`() = runTest {
        val fast = FakeAdapter(SourceType.KOMIIC, "快源", 0, listOf(SourceManga("s1", "标题 A")))
        val hanging = FakeAdapter(SourceType.MANGABZ, "挂住的源", 3_000, listOf(SourceManga("s2", "标题 B")))
        val mgr = manager(fast, hanging)

        val all = mgr.searchMihonCandidatesStreaming(
            alias = "标题",
            originalTitle = "标题",
            types = listOf(SourceType.KOMIIC, SourceType.MANGABZ),
            perSourceTimeoutMs = 200,
        ).toList()

        assertEquals(1, all.size, "超时的源被丢掉，只留快源那条")
        assertEquals("s1", all.first().sourceId)
    }

    @Test
    fun `blank alias emits nothing`() = runTest {
        val fast = FakeAdapter(SourceType.KOMIIC, "快源", 0, listOf(SourceManga("s1", "标题 A")))
        val mgr = manager(fast)
        assertEquals(0, mgr.searchMihonCandidatesStreaming("   ", "标题").toList().size)
    }
}
