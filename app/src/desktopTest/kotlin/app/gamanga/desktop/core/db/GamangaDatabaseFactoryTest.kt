package app.gamanga.desktop.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 启动引导的反馈回路：覆盖「库文件存在但 schema 不完整」等崩溃重启场景。
 *
 * 对应真实症状：用户某次启动崩在写库中途 → 之后每次启动都抛
 * "no such table" 崩溃循环。
 */
class GamangaDatabaseFactoryTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("mihon-db-test")
    }

    @AfterTest
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun dbFile(): Path = tempDir.resolve("mihon.db")

    @Test
    fun `fresh path creates full schema`() {
        val db = GamangaDatabaseFactory.create(dbFile())
        assertTablesComplete(db)
        // 空库查询不应抛
        assertEquals(0, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
    }

    @Test
    fun `zero byte file is bootstrapped`() {
        Files.createFile(dbFile()) // 0 字节：崩溃在 Schema.create 之前的最简形态
        val db = GamangaDatabaseFactory.create(dbFile())
        assertTablesComplete(db)
        assertEquals(0, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
    }

    @Test
    fun `partial schema file is repaired not crash looped`() {
        // 手工造一个「只建了 manga 表」的半成品库（崩在 create 中途的形态）
        val f = dbFile()
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        d.execute(
            null,
            "CREATE TABLE manga (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, " +
                "sourceMangaId TEXT NOT NULL, coverUrl TEXT, coverColorHex TEXT, author TEXT, " +
                "addedAt INTEGER NOT NULL DEFAULT 0)",
            0
        )
        // 崩溃前写进去的书架条目：修复必须保留，不能清库
        d.execute(
            null,
            "INSERT INTO manga (id, title, source, sourceMangaId, addedAt) VALUES " +
                "('keep-1', '半途写入', 'KOMIIC', 'm1', 1)",
            0
        )
        d.close()

        val db = GamangaDatabaseFactory.create(f)
        // 半成品库必须被修复成可用状态，而不是把 "no such table" 抛给用户
        assertTablesComplete(db)
        // 迁移优先于清库：缺的表补上，已有的数据原样保留
        assertEquals(1, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
    }

    @Test
    fun `v1 database is migrated in place, not wiped`() {
        // v1 形状（#30 迁移前）：四张表齐，但 chapter 没有 pageCount 列——
        // 正是「schema 加了列、用户库里还是旧形状」的线上形态。
        val f = dbFile()
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        d.execute(
            null,
            "CREATE TABLE manga (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, " +
                "sourceMangaId TEXT NOT NULL, coverUrl TEXT, coverColorHex TEXT, author TEXT, " +
                "addedAt INTEGER NOT NULL DEFAULT 0)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE chapter (id TEXT NOT NULL PRIMARY KEY, mangaId TEXT NOT NULL, name TEXT NOT NULL, " +
                "chapterIndex INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE readingProgress (mangaId TEXT NOT NULL PRIMARY KEY, chapterId TEXT NOT NULL, " +
                "page INTEGER NOT NULL, totalPages INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE appPrefs (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0
        )
        d.execute(null, "INSERT INTO manga (id, title, source, sourceMangaId, addedAt) VALUES ('mig-1', '老库', 'KOMIIC', 'm1', 1)", 0)
        d.execute(null, "INSERT INTO chapter (id, mangaId, name, chapterIndex, isRead) VALUES ('mig-1#c1', 'mig-1', '第 1 话', 0, 0)", 0)
        d.close()

        val db = GamangaDatabaseFactory.create(f)
        // 老数据必须在（清库重建会吃掉书架——这个测试就是防它）
        assertEquals(1, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
        val chapters = db.mangaQueries.selectChaptersByManga("mig-1").executeAsList()
        assertEquals(1, chapters.size)
        // 缺列已补齐，默认 0
        assertEquals(0L, chapters.single().pageCount)
        // 新 schema 的查询直接可用（带 pageCount 的 insertChapter）
        db.mangaQueries.insertChapter("mig-1#c2", "mig-1", "第 2 话", 1L, 0L, 24L, 0L)
        val after = db.mangaQueries.selectChaptersByManga("mig-1").executeAsList()
        assertEquals(2, after.size)
        assertEquals(24L, after.last().pageCount)
        // 版本号记到最新（v1→v5 全梯子走完），重启不再重跑迁移
        assertEquals(GamangaDatabaseFactory.latestMigrationVersion(), userVersionOf(f))
    }

    @Test
    fun `v2 database is migrated in place, not wiped`() {
        // v2 形状（R-04 下载迁移前）：四张表齐 + chapter.pageCount 有，
        // 但没有 downloadChapter——正是「schema 加了表、用户库里还是旧形状」的线上形态。
        val f = dbFile()
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        createV2Schema(d)
        d.execute(null, "INSERT INTO manga (id, title, source, sourceMangaId, addedAt) VALUES ('dl-1', '老库', 'KOMIIC', 'm1', 1)", 0)
        d.execute(null, "INSERT INTO chapter (id, mangaId, name, chapterIndex, isRead, pageCount) VALUES ('dl-1#c1', 'dl-1', '第 1 话', 0, 0, 20)", 0)
        d.close()

        val db = GamangaDatabaseFactory.create(f)
        // 老数据必须在（清库重建会吃掉书架）
        assertEquals(1, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
        assertEquals(20L, db.mangaQueries.selectChaptersByManga("dl-1").executeAsList().single().pageCount)
        // 新表已补上且可直接用（带外键的 insert：manga 行还在，FK 通）
        db.downloadQueries.upsertDownload(
            id = "dl-1#c1", mangaId = "dl-1", chapterId = "c1", name = "第 1 话",
            pageCount = 20L, downloadedPages = 20L, status = "COMPLETED", error = null,
            totalBytes = 1024L, updatedAt = 1L, createdAt = 1L,
        )
        assertEquals(
            "COMPLETED",
            db.downloadQueries.selectDownloadById("dl-1#c1").executeAsOneOrNull()?.status
        )
        // 版本号记到最新
        assertEquals(GamangaDatabaseFactory.latestMigrationVersion(), userVersionOf(f))
    }

    @Test
    fun `v3 download table gains createdAt column without losing rows`() {
        // v3 形状（第七轮 #2 迁移前）：downloadChapter 在，但没有 createdAt 列。
        // 老行必须被回填（= updatedAt，保序不洗牌），不能丢。
        val f = dbFile()
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        createV2Schema(d)
        d.execute(
            null,
            "CREATE TABLE downloadChapter (" +
                "id TEXT NOT NULL PRIMARY KEY, mangaId TEXT NOT NULL, chapterId TEXT NOT NULL, " +
                "name TEXT NOT NULL, pageCount INTEGER NOT NULL DEFAULT 0, " +
                "downloadedPages INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, error TEXT, " +
                "totalBytes INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
            0
        )
        d.execute(null, "INSERT INTO manga (id, title, source, sourceMangaId, addedAt) VALUES ('dlv3-1', '老库', 'KOMIIC', 'm1', 1)", 0)
        d.execute(
            null,
            "INSERT INTO downloadChapter (id, mangaId, chapterId, name, pageCount, downloadedPages, status, totalBytes, updatedAt) " +
                "VALUES ('dlv3-1#c1', 'dlv3-1', 'c1', '第 1 话', 20, 20, 'COMPLETED', 1024, 5000)",
            0
        )
        d.close()

        val db = GamangaDatabaseFactory.create(f)
        // 老行还在，且 createdAt 已回填为 updatedAt
        val row = db.downloadQueries.selectDownloadById("dlv3-1#c1").executeAsOneOrNull()!!
        assertEquals(5000L, row.createdAt, "回填规则：createdAt = updatedAt")
        // 新 schema 的 upsert（带 createdAt）直接可用
        db.downloadQueries.upsertDownload(
            id = "dlv3-1#c2", mangaId = "dlv3-1", chapterId = "c2", name = "第 2 话",
            pageCount = 10L, downloadedPages = 0L, status = "QUEUED", error = null,
            totalBytes = 0L, updatedAt = 6000L, createdAt = 6000L,
        )
        assertEquals(2, db.downloadQueries.selectAllDownloads().executeAsList().size)
        // 版本号到 v4
        assertEquals(GamangaDatabaseFactory.latestMigrationVersion(), userVersionOf(f))
    }

    /** v2 形状的四张基础表（chapter 已带 pageCount）。 */
    @Test
    fun `v7 adds inLibrary column and keeps existing shelf rows on the shelf`() {
        // R-123：manga 加 inLibrary 列。这是**会碰用户真实库**的迁移，
        // 必须保证：①老行一条不少；②老行全部被当成「书架内」（DEFAULT 1）；
        // ③幽灵行（inLibrary = 0）插得进去，且不出现在书架查询里。
        val f = dbFile()
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        createV2Schema(d) // 老形状：manga 没有 inLibrary 列
        d.execute(
            null,
            "INSERT INTO manga (id, title, source, sourceMangaId, addedAt) " +
                "VALUES ('v7-1', '老书架里的书', 'KOMIIC', 'm1', 123)",
            0
        )
        d.close()

        val db = GamangaDatabaseFactory.create(f)

        // ① + ②：老行还在，且默认算书架内
        val shelf = db.mangaQueries.selectLibraryWithUnread().executeAsList()
        assertEquals(1, shelf.size, "老书架不能丢")
        assertEquals("v7-1", shelf.single().id)
        assertEquals(1L, shelf.single().inLibrary, "老数据必须被当成书架内")

        // ③：幽灵行能插，且被书架查询过滤掉
        db.mangaQueries.insertGhostManga("v7-2", "书架外试读过的", "KOMIIC", "m2", null, null, null)
        assertEquals(1, db.mangaQueries.selectLibraryWithUnread().executeAsList().size)
        assertEquals(1L, db.mangaQueries.selectLibraryCount().executeAsOne(), "书架本数不含幽灵行")

        // 对**已在架**的书调 insertGhostManga 必须无效（INSERT OR IGNORE）：
        // 否则一次试读就会把书架里的书降级成幽灵行，等于把书弄丢
        db.mangaQueries.insertGhostManga("v7-1", "想改掉的名字", "KOMIIC", "m1", null, null, null)
        val stillShelf = db.mangaQueries.selectLibraryWithUnread().executeAsList().single()
        assertEquals("老书架里的书", stillShelf.title, "书名不能被幽灵行覆盖")
        assertEquals(1L, stillShelf.inLibrary, "已在架的书不能被降级")

        assertEquals(GamangaDatabaseFactory.latestMigrationVersion(), userVersionOf(f))
    }

    private fun createV2Schema(d: JdbcSqliteDriver) {
        d.execute(
            null,
            "CREATE TABLE manga (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, " +
                "sourceMangaId TEXT NOT NULL, coverUrl TEXT, coverColorHex TEXT, author TEXT, " +
                "addedAt INTEGER NOT NULL DEFAULT 0)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE chapter (id TEXT NOT NULL PRIMARY KEY, mangaId TEXT NOT NULL, name TEXT NOT NULL, " +
                "chapterIndex INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0, pageCount INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE readingProgress (mangaId TEXT NOT NULL PRIMARY KEY, chapterId TEXT NOT NULL, " +
                "page INTEGER NOT NULL, totalPages INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
            0
        )
        d.execute(
            null,
            "CREATE TABLE appPrefs (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            0
        )
    }

    @Test
    fun `valid existing database is preserved on reopen`() {
        val db1 = GamangaDatabaseFactory.create(dbFile())
        db1.mangaQueries.insertManga(
            id = "keep-1", title = "保留", source = "KOMIIC", sourceMangaId = "m1",
            coverUrl = null, coverColorHex = "FFB5D4F4", author = null, addedAt = 1L
        )
        // 重新打开同一文件：数据必须在
        val db2 = GamangaDatabaseFactory.create(dbFile())
        assertEquals(1, db2.mangaQueries.selectLibraryWithUnread().executeAsList().size)
    }

    /** 直接读库文件头的 user_version（与工厂内部同一来源）。 */
    private fun userVersionOf(f: Path): Long {
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.toAbsolutePath()}")
        try {
            return d.getConnection().createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else -1L
                }
            }
        } finally {
            d.close()
        }
    }

    private fun assertTablesComplete(db: GamangaDatabase) {
        // 查询能跑通即证明四张表都在：selectLibraryWithUnread 带 chapter 子查询，
        // selectPref 读 appPrefs，下面再写 readingProgress。**不断言库里为空**——
        // 迁移测试会故意留数据来验证「补表不清库」。
        db.mangaQueries.selectLibraryWithUnread().executeAsList()
        // appPrefs / readingProgress 也要在（selectPref 直接读 appPrefs）
        assertEquals(null, db.mangaQueries.selectPref("probe").executeAsOneOrNull())
        db.mangaQueries.upsertProgress("m", "c", 0, 1, 0L) // 写 readingProgress
        val rows = db.mangaQueries.selectRecentProgress().executeAsList()
        assertEquals(0, rows.size) // 进度行的 mangaId "m" 不存在，join 无结果但不抛错
    }
}
