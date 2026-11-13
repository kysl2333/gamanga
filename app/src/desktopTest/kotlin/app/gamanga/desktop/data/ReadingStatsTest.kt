package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-38 统计数据层：5 个数字都要对，尤其「下载漫画数按漫画计数」。
 */
class ReadingStatsTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = GamangaDatabase(driver)
    }

    private fun manga(id: String) = db.mangaQueries.insertManga(
        id = id, title = "书 $id", source = "GODA", sourceMangaId = "s$id",
        coverUrl = null, coverColorHex = null, author = null, addedAt = 0L,
    )

    private fun chapter(id: String, mangaId: String, read: Boolean) = db.mangaQueries.insertChapter(
        id = id, mangaId = mangaId, name = "第 1 话", chapterIndex = 0,
        isRead = if (read) 1 else 0, pageCount = 10, lastOpenedAt = 0,
    )

    private fun download(id: String, mangaId: String, status: String) = db.downloadQueries.upsertDownload(
        id = id, mangaId = mangaId, chapterId = id, name = "第 1 话",
        pageCount = 10, downloadedPages = 10, status = status, error = null,
        totalBytes = 100, updatedAt = 0, createdAt = 0,
    )

    @Test
    fun `counts library chapters downloads and reading time`() {
        val r = LibraryRepository(db)
        manga("m1"); manga("m2")
        chapter("m1#c1", "m1", read = true)
        chapter("m1#c2", "m1", read = false)
        chapter("m2#c1", "m2", read = true)
        // m1 下了 2 话、m2 下了 1 话；再加一条失败任务（不计入）
        download("m1#c1", "m1", "COMPLETED")
        download("m1#c2", "m1", "COMPLETED")
        download("m2#c1", "m2", "COMPLETED")
        download("m2#c2", "m2", "FAILED")

        r.addReadingSeconds(90, "2026-10-24")
        r.addReadingSeconds(30, "2026-10-25")
        r.addReadingSeconds(15, "2026-10-25")

        val s = r.readingStats(today = "2026-10-25")
        assertEquals(2, s.libraryCount)
        assertEquals(2, s.readChapterCount)
        assertEquals(3, s.downloadedChapterCount, "只算 COMPLETED")
        assertEquals(2, s.downloadedMangaCount, "按漫画计数，m1 下 2 话也只算 1 本")
        assertEquals(45, s.todaySeconds)
        assertEquals(135, s.totalSeconds)
    }

    @Test
    fun `reading time is zero when never read`() {
        val s = LibraryRepository(db).readingStats(today = "2026-10-25")
        assertEquals(0, s.todaySeconds)
        assertEquals(0, s.totalSeconds)
        assertEquals(0, s.libraryCount)
    }
}
