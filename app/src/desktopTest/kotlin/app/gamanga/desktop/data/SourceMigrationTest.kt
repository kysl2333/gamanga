package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.ReadingProgress
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceMigrationTest {
    private fun fixture(): Triple<JdbcSqliteDriver, GamangaDatabase, LibraryRepository> {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        val db = GamangaDatabase(driver)
        val repo = LibraryRepository(db)
        repo.importFromSource(SourceType.KOMIIC, SourceManga("old", "原书"), listOf(SourceChapter("c1", "第一话", 0, pageCount = 20)))
        repo.saveProgress(ReadingProgress("komiic:old", "komiic:old#c1", 5, 20))
        repo.setChapterRead("komiic:old#c1", true)
        repo.setChapterOpened("komiic:old#c1")
        repo.togglePin("komiic:old")
        return Triple(driver, db, repo)
    }

    private fun plan(repo: LibraryRepository) = SourceMigration.plan(
        repo,
        MangaEntry("komiic:old", "原书", SourceType.KOMIIC, "old"),
        MigrateCandidate(SourceType.GODA, SourceManga("new", "新书"), listOf(SourceChapter("c2", "新第一话", 0, pageCount = 3)), 3, "标题完全相同"),
    )

    @Test
    fun `failed destination insert preserves original book chapters progress and pin`() {
        val (driver, db, repo) = fixture()
        val before = repo.chaptersOf("komiic:old")
        val progress = repo.progressOf("komiic:old")
        driver.execute(null, """
            CREATE TRIGGER fail_migration BEFORE INSERT ON manga WHEN NEW.id = 'goda:new'
            BEGIN SELECT RAISE(ABORT, 'migration fault'); END
        """.trimIndent(), 0)
        assertFails { SourceMigration.execute(repo, plan(repo)) }
        assertEquals(before, repo.chaptersOf("komiic:old"))
        assertEquals(progress, repo.progressOf("komiic:old"))
        assertEquals("komiic:old", repo.getPref(LibraryRepository.PREF_PINNED))
        assertNull(db.mangaQueries.selectMangaById("goda:new").executeAsOneOrNull())
    }

    @Test
    fun `failed progress write rolls back destination and restores original pin state`() {
        val (driver, db, repo) = fixture()
        val before = repo.chaptersOf("komiic:old")
        val progress = repo.progressOf("komiic:old")
        driver.execute(null, """
            CREATE TRIGGER fail_progress BEFORE INSERT ON readingProgress WHEN NEW.mangaId = 'goda:new'
            BEGIN SELECT RAISE(ABORT, 'progress fault'); END
        """.trimIndent(), 0)
        assertFails { SourceMigration.execute(repo, plan(repo)) }
        assertEquals(before, repo.chaptersOf("komiic:old"))
        assertEquals(progress, repo.progressOf("komiic:old"))
        assertNull(db.mangaQueries.selectMangaById("goda:new").executeAsOneOrNull())
        assertTrue(repo.chaptersOf("goda:new").isEmpty())
        assertNull(repo.progressOf("goda:new"))
        assertEquals("komiic:old", repo.getPref(LibraryRepository.PREF_PINNED))
        repo.togglePin("komiic:old")
        assertEquals("", repo.getPref(LibraryRepository.PREF_PINNED), "回滚后内存仍应识别原书已置顶")
    }

    @Test
    fun `successful migration maps read state progress and pin`() {
        val (_, db, repo) = fixture()
        assertEquals("goda:new", SourceMigration.execute(repo, plan(repo)))
        assertNull(db.mangaQueries.selectMangaById("komiic:old").executeAsOneOrNull())
        assertTrue(repo.chaptersOf("goda:new").single().read)
        assertTrue(repo.chaptersOf("goda:new").single().lastOpenedAt > 0)
        assertEquals(2, repo.progressOf("goda:new")?.page)
        assertEquals(3, repo.progressOf("goda:new")?.totalPages)
        assertEquals("goda:new", repo.getPref(LibraryRepository.PREF_PINNED))
    }
}
