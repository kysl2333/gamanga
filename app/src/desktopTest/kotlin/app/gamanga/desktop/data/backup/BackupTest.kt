package app.gamanga.desktop.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-03 备份的收尾测试（2026-10-24）。
 *
 * 之前 `BackupManager` / `MihonBackup` / `ProtoReader` 代码写完但**没有一行测试**，
 * 属于「代码完成、未收尾」。这里覆盖三块能离线验证的部分：
 *  - 我们自己的备份格式：导出 → 恢复往返、非法文件拒绝
 *  - mihon 备份的 URL/章节映射（纯函数）
 *  - protobuf 解析（mihon 备份是 gzip + protobuf，解析层必须能吃真实字节）
 */
class BackupTest {

    private fun newDb(): GamangaDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return GamangaDatabase(driver)
    }

    // ---- 我们自己的备份格式 ----

    @Test
    fun `older backup preserves newer local progress and local only records`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        repo.addToLibrary(MangaEntry("k:9", "备份书", SourceType.KOMIIC, "9"))
        db.mangaQueries.insertChapter("k:9#c1", "k:9", "第一话", 0, 0, 18, 0)
        db.mangaQueries.upsertProgress("k:9", "k:9#c1", 1, 18, 1000)
        val manager = BackupManager(db, repo)
        val zip = manager.exportBackup()
        db.mangaQueries.upsertProgress("k:9", "k:9#c1", 8, 18, 2000)
        repo.addToLibrary(MangaEntry("k:10", "本地新增", SourceType.KOMIIC, "10"))
        val report = manager.restoreBackup(zip)
        assertEquals(0, report.progressCount)
        assertEquals(8, repo.progressOf("k:9")?.page)
        assertEquals(2, db.mangaQueries.selectAllManga().executeAsList().size)
    }

    @Test
    fun `export then restore keeps manga chapters progress and prefs`() = runTest {
        val db = newDb()
        val repo = LibraryRepository(db)
        repo.addToLibrary(
            MangaEntry(
                id = "k:9", title = "备份测试", source = SourceType.KOMIIC,
                sourceMangaId = "s9", coverUrl = "https://c/9.jpg"
            )
        )
        db.mangaQueries.insertChapter(
            id = "k:9#c1", mangaId = "k:9", name = "第1话",
            chapterIndex = 0, isRead = 1, pageCount = 18, lastOpenedAt = 0
        )
        db.mangaQueries.upsertProgress("k:9", "k:9#c1", 5, 18, 1_700_000_000_000L)
        repo.setPref("test.key", "test-value")

        val zip = BackupManager(db, repo).exportBackup()
        assertTrue(zip.size > 0, "导出应产出非空 zip")

        // 恢复到另一个空库
        val db2 = newDb()
        val repo2 = LibraryRepository(db2)
        val report = BackupManager(db2, repo2).restoreBackup(zip)
        assertTrue(report.mangaCount >= 1)
        assertTrue(report.chapterCount >= 1)
        assertTrue(report.progressCount >= 1)

        // 关键数据真的回来了
        val restored = repo2.libraryItems
            .first { list -> list.any { it.entry.id == "k:9" } }
            .first { it.entry.id == "k:9" }
        assertEquals("备份测试", restored.entry.title)
        assertEquals("https://c/9.jpg", restored.entry.coverUrl)
        assertEquals("test-value", repo2.getPref("test.key"))
        val progress = assertNotNull(repo2.progressOf("k:9"))
        assertEquals(5, progress.page)
        assertEquals(18, progress.totalPages)
    }

    @Test
    fun `restore rejects bytes that are not our backup`() = runTest {
        val db = newDb()
        val bm = BackupManager(db, LibraryRepository(db))
        assertFailsWith<BackupFormatException> {
            bm.restoreBackup(byteArrayOf(1, 2, 3, 4, 5))
        }
    }

    // ---- mihon 备份的映射（纯函数）----

    @Test
    fun `mihon url maps to source and id`() {
        assertEquals(SourceType.KOMIIC to "abc", mihonUrlToSource("https://komiic.cc/comic/abc"))
        // 旧备份里的旧域名照样认
        assertEquals(SourceType.KOMIIC to "abc", mihonUrlToSource("https://komiic.com/comic/abc"))
        assertEquals(SourceType.KOMIIC to "abc", mihonUrlToSource("https://www.komiic.com/comic/abc/"))
        assertEquals(SourceType.GODA to "xuanfeng", mihonUrlToSource("https://m.g-mh.org/manga/xuanfeng/"))
        assertEquals(SourceType.GODA to "xuanfeng", mihonUrlToSource("https://godamh.com/manga/xuanfeng"))
        assertEquals(SourceType.GODA to "xuanfeng", mihonUrlToSource("https://baozimh.org/manga/xuanfeng"))
        // 不认识的站 / 空路径 → null（导入时跳过，不崩）
        assertNull(mihonUrlToSource("https://example.com/comic/abc"))
        assertNull(mihonUrlToSource("https://komiic.com/comic/"))
        assertNull(mihonUrlToSource(""))
    }

    @Test
    fun `mihon chapter index matches by number then by name`() {
        val names = listOf("第1话", "第2话", "第3话")
        assertEquals(0, matchMihonChapterIndex(1f, "第1话", names))
        assertEquals(2, matchMihonChapterIndex(3f, "任意", names))
        // 话数越界 → 用名字兜底
        assertEquals(1, matchMihonChapterIndex(99f, "第2话", names))
        assertEquals(-1, matchMihonChapterIndex(99f, "不存在", names))
        assertEquals(-1, matchMihonChapterIndex(1f, "第1话", emptyList()))
    }

    // ---- protobuf 解析（mihon 备份是 gzip + protobuf）----

    @Test
    fun `protobuf parses varint string and nested message`() {
        // field1(varint)=42, field2(string)="hi", field3(message){field1=7}
        val bytes = byteArrayOf(
            0x08, 0x2A,
            0x12, 0x02, 0x68, 0x69,
            0x1A, 0x02, 0x08, 0x07,
        )
        val fields = parseProtobuf(bytes)
        assertEquals(3, fields.size)
        assertEquals(42L, fields.long(1))
        assertEquals("hi", fields.string(2))
        val nested = fields.messages(3)
        assertEquals(1, nested.size)
        assertEquals(7L, nested[0].long(1))
    }

    @Test
    fun `protobuf rejects truncated and unknown wire types`() {
        // 声明长度 5 但只给 2 字节
        assertFailsWith<IllegalArgumentException> {
            parseProtobuf(byteArrayOf(0x12, 0x05, 0x68, 0x69))
        }
        // wire type 7 未定义
        assertFailsWith<IllegalArgumentException> {
            parseProtobuf(byteArrayOf(0x0F, 0x00))
        }
        // 空字节 → 空列表（不是错误）
        assertEquals(emptyList(), parseProtobuf(byteArrayOf()))
    }
}
