package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 章节状态的写入语义（R-159，2026-10-29）。
 *
 * 用户报：「详情页章节右键标记已读和未读一开始好像无法生效，后面点进去再标记又成功了」。
 *
 * 两个根因：
 * 1. `setChapterRead` 是纯 UPDATE —— 该章在库里还没有行（换源迁移 / 备份导入 /
 *    源站重建章节 id）时静默无事发生；
 * 2. `syncChapters` 用「读快照 → INSERT OR REPLACE 写回」，详情页打开时的后台刷新
 *    一旦和点击撞车，就把刚标好的已读冲回未读。
 *
 * 现在：缺行补行 + 状态列不进写语句。这两个不变量必须一直成立。
 */
class ChapterStateSyncTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = GamangaDatabase(driver)
    }

    private fun repo() = LibraryRepository(db)

    private fun importTwoChapters(): String = repo().importFromSource(
        type = SourceType.KOMIIC,
        manga = SourceManga(sourceId = "1315", title = "测试长标题"),
        chapters = listOf(
            SourceChapter(id = "c1", name = "01", index = 0, pageCount = 20),
            SourceChapter(id = "c2", name = "02", index = 1, pageCount = 0),
        ),
    )

    @Test
    fun `marking read creates the row when the chapter was never synced`() {
        val mangaId = importTwoChapters()
        val ghost = "$mangaId#c3"
        assertTrue(repo().setChapterRead(ghost, true, "03", 2), "缺行时要补行并写入")
        val row = repo().chaptersOf(mangaId).first { it.id == ghost }
        assertTrue(row.read, "补出来的行要直接是已读")
        assertEquals("03", row.name)
        assertEquals(3, repo().chapterCountOf(mangaId))
    }

    @Test
    fun `marking read on a missing manga row is refused`() {
        // 书架外试读：chapter.mangaId 外键没有目标行，不能凭空插
        assertFalse(repo().setChapterRead("nope:c1", true, "01", 0))
    }

    @Test
    fun `sync chapters keeps read and opened flags`() {
        val mangaId = importTwoChapters()
        val r = repo()
        r.setChapterRead("$mangaId#c1", true)
        r.setChapterOpened("$mangaId#c2")
        val before = r.chaptersOf(mangaId).associateBy { it.id }

        // 详情页每次打开都会同步一遍（旧的 INSERT OR REPLACE 就在这里冲掉状态）
        repeat(3) {
            r.syncChapters(
                mangaId,
                listOf(
                    SourceChapter(id = "c1", name = "01", index = 0, pageCount = 20),
                    SourceChapter(id = "c2", name = "02", index = 1, pageCount = 0),
                ),
            )
        }

        val after = r.chaptersOf(mangaId).associateBy { it.id }
        assertTrue(after.getValue("$mangaId#c1").read, "已读不能被同步冲掉")
        assertEquals(before.getValue("$mangaId#c2").lastOpenedAt, after.getValue("$mangaId#c2").lastOpenedAt)
    }

    @Test
    fun `sync chapters keeps page count when source returns zero`() {
        val mangaId = importTwoChapters()
        val r = repo()
        // 阅读器回填了页数
        r.updateChapterPageCount("$mangaId#c1", 24)
        // 下次同步源站没给页数（0）——不能把 24 冲成 0，否则进度百分比归零
        r.syncChapters(mangaId, listOf(SourceChapter(id = "c1", name = "01", index = 0, pageCount = 0)))
        assertEquals(24, r.chaptersOf(mangaId).first { it.id == "$mangaId#c1" }.pageCount)
    }

    @Test
    fun `sync chapters adds new chapters without touching old ones`() {
        val mangaId = importTwoChapters()
        val r = repo()
        r.setChapterRead("$mangaId#c1", true)
        r.syncChapters(
            mangaId,
            listOf(
                SourceChapter(id = "c1", name = "01", index = 0, pageCount = 20),
                SourceChapter(id = "c2", name = "02", index = 1, pageCount = 18),
                SourceChapter(id = "c3", name = "03", index = 2, pageCount = 19),
            ),
        )
        assertEquals(3, r.chapterCountOf(mangaId))
        assertTrue(r.chaptersOf(mangaId).first { it.id == "$mangaId#c1" }.read)
        assertEquals(19, r.chaptersOf(mangaId).first { it.id == "$mangaId#c3" }.pageCount)
    }
}
