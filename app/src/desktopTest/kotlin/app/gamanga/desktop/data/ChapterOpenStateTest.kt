package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.core.db.GamangaDatabaseFactory
import app.gamanga.desktop.db.GamangaDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-39：「点过」与「已读」是两个独立状态。
 *
 * 用户 2026-10-25 明确：详情页变灰只表示「点过这一章」，**不代表已读**；
 * 「标记未读」要把两个状态一起清掉，章节才恢复原色。
 */
class ChapterOpenStateTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = GamangaDatabase(driver)
    }

    private fun repo() = LibraryRepository(db)

    /** 直接插一行章节（不走 addToLibrary，避免依赖它的签名）。 */
    private fun insertChapter(id: String = "m1#c1") {
        // chapter.mangaId 有外键，先补 manga 行
        db.mangaQueries.insertManga(
            id = "m1", title = "测试漫画", source = "GODA", sourceMangaId = "s1",
            coverUrl = null, coverColorHex = null, author = null, addedAt = 0L,
        )
        db.mangaQueries.insertChapter(
            id = id, mangaId = "m1", name = "第 1 话", chapterIndex = 0,
            isRead = 0, pageCount = 20, lastOpenedAt = 0,
        )
    }

    private fun chapter(id: String = "m1#c1") =
        repo().chaptersOf("m1").first { it.id == id }

    @Test
    fun `opening a chapter marks opened but not read`() {
        insertChapter()
        repo().setChapterOpened("m1#c1")
        val ch = chapter()
        assertTrue(ch.lastOpenedAt > 0, "点过要记时间戳")
        assertFalse(ch.read, "点过 ≠ 已读")
    }

    @Test
    fun `marking read keeps opened`() {
        insertChapter()
        repo().setChapterOpened("m1#c1")
        repo().setChapterRead("m1#c1", true)
        val ch = chapter()
        assertTrue(ch.read)
        assertTrue(ch.lastOpenedAt > 0, "已读的章节必然点过，不能把点过清掉")
    }

    @Test
    fun `marking unread clears both states`() {
        insertChapter()
        repo().setChapterOpened("m1#c1")
        repo().setChapterRead("m1#c1", true)
        repo().setChapterRead("m1#c1", false)
        val ch = chapter()
        assertFalse(ch.read)
        assertEquals(0L, ch.lastOpenedAt, "标记未读要连点过一起清，否则章节还是灰的")
    }

    @Test
    fun `migration ladder is strictly ascending`() {
        val versions = GamangaDatabaseFactory.migrationVersions()
        assertEquals(versions.sorted(), versions, "迁移梯子必须升序，否则中间版本会被跳过")
        assertEquals(versions.distinct(), versions, "版本号不能重复")
    }
}
