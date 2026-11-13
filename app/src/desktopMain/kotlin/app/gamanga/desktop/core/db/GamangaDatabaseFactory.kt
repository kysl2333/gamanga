package app.gamanga.desktop.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * SQLDelight 驱动工厂（desktop）。
 *
 * 库文件落在 `%LOCALAPPDATA%\Gamanga\gamanga.db`（Windows 惯例，
 * 与 Suwayomi 的 `AppData\Local\Tachidesk` 一致——各自的本地数据互不干扰）。
 * 老版本（Mihon Desktop/mihon.db）首次启动时自动搬到新位置，不丢数据。
 *
 * 启动引导 = **迁移优先，清库兜底**：
 * 1. 按 `PRAGMA user_version` 跑迁移梯子（[STEPS]）：缺表补表、缺列补列，
 *    全程保留数据。老库（version 0）和崩在半途的库（表不齐）都能被修好。
 * 2. 迁移后仍残缺才是未知损坏，此时才清场重建——最后手段，正常升级不会走到。
 *
 * 历史教训（为什么不能「残缺即清库」）：schema 一改（加列），按旧 schema 建的库
 * 在新代码眼里就是「残缺」，整体 DROP 会**吃掉用户整个书架**。于是所有 schema
 * 变更都必须走 [STEPS] 补迁移，见那里的规矩。
 */
object GamangaDatabaseFactory {

    fun create(): GamangaDatabase {
        val appData = Paths.get(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
        val dbFile = appData.resolve("Gamanga").resolve("gamanga.db")
        // 改名迁移（Mihon Desktop → GA漫画）：新库不存在且老库在，就搬过来
        val legacy = appData.resolve("Mihon Desktop").resolve("mihon.db")
        if (Files.notExists(dbFile) && Files.exists(legacy)) {
            Files.createDirectories(dbFile.parent)
            runCatching { Files.move(legacy, dbFile) } // 搬不动就算了，重建空库也不影响启动
        }
        return create(dbFile)
    }

    fun create(dbFile: Path): GamangaDatabase {
        Files.createDirectories(dbFile.parent)

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.toAbsolutePath()}")
        ensureSchema(driver)
        // JdbcSqliteDriver 复用同一连接，建连时打开外键级联（deleteManga → chapter/progress 级联删）
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return GamangaDatabase(driver)
    }

    /** 迁移 + 校验。见类文档：先补迁移，补完还残才清场。 */
    private fun ensureSchema(driver: JdbcSqliteDriver) {
        val current = userVersion(driver)
        for (step in STEPS) {
            if (step.version <= current) continue
            step.apply(driver)
            driver.execute(null, "PRAGMA user_version = ${step.version}", 0)
        }
        if (!schemaComplete(driver)) {
            // 兜底：梯子走完还缺表——未知损坏 / 人工改坏了库文件。数据本就不可用，
            // 弃之重建，至少应用能启动（旧行为只在这种情况下保留）。
            GamangaDatabase.allTableNames().forEach { table ->
                driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
            }
            GamangaDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${STEPS.last().version}", 0)
        }
    }

    /** 单步迁移。[version] 为迁移后的 schema 版本号（= user_version 目标值）。 */
    private class Step(val version: Long, val apply: (JdbcSqliteDriver) -> Unit)

    /**
     * schema 迁移梯子（只增不减）。
     *
     * 不用 SQLDelight 的 migrate：生成的 `Schema.migrate` 是空实现（没有 .sqm
     * 迁移文件），driver 也不会自动调它；版本号自己管更直接。
     *
     * **规矩：`Manga.sq` 每次加表/加列，这里必须同步补一步幂等迁移。**
     * 否则老库缺列，带新列的查询（如 insertChapter）会直接 "no such column"
     * 崩给用户。只做「加表 / 加列」这类 SQLite 支持的迁移；改列类型、删列
     * SQLite 办不了，只能清场重建（丢该库数据，慎用）。
     */
    private val STEPS: List<Step> = listOf(
        // v1：首发 schema（M1 形态，与 Manga.sq 保持一致）。
        // IF NOT EXISTS：老库表已齐时全是 no-op；崩在 Schema.create 中途的库
        // 缺哪张补哪张，已有的表和数据原样保留。
        Step(1) { driver ->
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS manga (" +
                    "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, " +
                    "sourceMangaId TEXT NOT NULL, coverUrl TEXT, coverColorHex TEXT, author TEXT, " +
                    "addedAt INTEGER NOT NULL DEFAULT 0)",
                0
            )
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS chapter (" +
                    "id TEXT NOT NULL PRIMARY KEY, mangaId TEXT NOT NULL, name TEXT NOT NULL, " +
                    "chapterIndex INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
                0
            )
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS readingProgress (" +
                    "mangaId TEXT NOT NULL PRIMARY KEY, chapterId TEXT NOT NULL, page INTEGER NOT NULL, " +
                    "totalPages INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
                0
            )
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS appPrefs (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                0
            )
            driver.execute(null, "CREATE INDEX IF NOT EXISTS chapter_mangaId ON chapter(mangaId)", 0)
        },
        // v2（#30）：chapter 加页数列。Komiic 源接口直接给每章 size；
        // G社接口不给页数，退而求其次——阅读时按 pages.size 回填，详情页
        // 章节列表因此能显示「N 页」（读过的章立即有，没读过的等读过）。
        Step(2) { driver ->
            addColumnIfMissing(driver, "chapter", "pageCount INTEGER NOT NULL DEFAULT 0")
        },
        // v3（R-04 下载）：downloadChapter 表（章节图片落盘 + 任务状态）。
        // 老库缺表时补建；已有下载行原样保留（IF NOT EXISTS，不清库）。
        // 文件级数据在 DownloadManager 的 rootDir，不在库里。
        Step(3) { driver ->
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS downloadChapter (" +
                    "id TEXT NOT NULL PRIMARY KEY, mangaId TEXT NOT NULL, chapterId TEXT NOT NULL, " +
                    "name TEXT NOT NULL, pageCount INTEGER NOT NULL DEFAULT 0, " +
                    "downloadedPages INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, error TEXT, " +
                    "totalBytes INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE)",
                0
            )
            driver.execute(
                null,
                "CREATE INDEX IF NOT EXISTS downloadChapter_mangaId ON downloadChapter(mangaId)",
                0
            )
        },
        // v4（第七轮 #2）：downloadChapter 加入队时间列。下载列表改按 createdAt
        // 稳定排序——旧排序用 updatedAt，每页进度都改它，任务行在列表里来回跳
        // （用户实测「正在下载的两个任务在列表中来回跳动」）。老行回填
        // updatedAt（入队先后 ≈ 最后更新先后，保序不洗牌）。
        Step(4) { driver ->
            addColumnIfMissing(driver, "downloadChapter", "createdAt INTEGER NOT NULL DEFAULT 0")
            driver.execute(
                null,
                "UPDATE downloadChapter SET createdAt = updatedAt WHERE createdAt = 0",
                0
            )
        },
        // v5（F-39）：「点过」时间列。详情页灰显的判据是「点过」而不是「已读」——
        // 用户 2026-10-25 明确「变灰不代表已读，只表示点过这一章」。
        //
        // ⚠️ 梯子**必须按版本升序**：ensureSchema 只读一次 user_version，然后逐条
        // 「version > current 才执行」并推进版本号。把 v5 写在 v4 前面，v3 的老库
        // 会先执行 v5（版本跳到 5）→ v4 被判定「已应用」而跳过 → createdAt 列永远补不上。
        Step(5) { driver ->
            addColumnIfMissing(driver, "chapter", "lastOpenedAt INTEGER NOT NULL DEFAULT 0")
        },
        // v6（F-38）：观看时长表。只存「天 → 秒」，不存明细——统计页只要总数与今日。
        Step(6) { driver ->
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS readingTime (" +
                    "day TEXT NOT NULL PRIMARY KEY, seconds INTEGER NOT NULL DEFAULT 0)",
                0
            )
        },
        // v7（R-123）：「是否在书架」列。用户实测「看了个书架里没有的动漫，历史里没记录」——
        // 历史页的 selectHistory 要 JOIN manga 拿书名/封面，而书架外的书根本没有 manga 行，
        // 于是 INNER JOIN 直接把进度丢了。现在允许写入 inLibrary = 0 的「幽灵行」：
        // 只服务历史/进度，书架查询一律过滤掉。
        //
        // DEFAULT 1 = **老库里的行全部视为书架内**（这正是它们原本的含义），零数据变更。
        Step(7) { driver ->
            addColumnIfMissing(driver, "manga", "inLibrary INTEGER NOT NULL DEFAULT 1")
        }
    )

    /**
     * 迁移梯子的版本序列（测试用）。
     *
     * 存在的理由：`ensureSchema` 只读一次 user_version，然后逐条「version > current 才执行」
     * 并推进版本号——**梯子必须严格升序**。曾经把 v5 写在 v4 前面，v3 老库会先跑 v5
     * （版本跳到 5）→ v4 被当成「已应用」跳过 → 那一列永远补不上。这条断言就是防这个。
     */
    internal fun migrationVersions(): List<Long> = STEPS.map { it.version }

    /** 梯子最末版本号（= 全新库写进 user_version 的值）。测试用它，别再写死数字。 */
    internal fun latestMigrationVersion(): Long = STEPS.last().version

    /** 经原始 JDBC 查 sqlite_master（SQLDelight 的 .sq 查询过不了 schema 校验，不能用它）。 */
    private fun schemaComplete(driver: JdbcSqliteDriver): Boolean {
        val present = mutableSetOf<String>()
        driver.getConnection().createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                while (rs.next()) present += rs.getString(1)
            }
        }
        return present.containsAll(GamangaDatabase.allTableNames().toSet())
    }

    /** `PRAGMA user_version`：存在库文件头里的 schema 版本。0 = 从未记过（含所有老库）。 */
    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.getConnection().createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }

    /**
     * 缺列才 ALTER（幂等）。user_version=0 的老库形状未知，每一步迁移都要
     * 先自查再动手——直接 ALTER 在列已存在时会抛 "duplicate column name"。
     */
    private fun addColumnIfMissing(driver: JdbcSqliteDriver, table: String, def: String) {
        val column = def.substringBefore(' ').trim()
        if (column.isBlank()) return
        driver.getConnection().createStatement().use { st ->
            // PRAGMA table_info 的列序：cid, name, type, notnull, dflt_value, pk
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString(2) == column) return
                }
            }
        }
        driver.execute(null, "ALTER TABLE $table ADD COLUMN $def", 0)
    }
}
