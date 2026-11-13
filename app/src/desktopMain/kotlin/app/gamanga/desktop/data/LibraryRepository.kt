package app.gamanga.desktop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.core.db.GamangaDatabaseFactory
import app.gamanga.desktop.db.MangaQueries
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.model.Chapter
import app.gamanga.desktop.model.HistoryEntry
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.ReadingProgress
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * M1 本地数据层：书架持久化 Repository。
 *
 * 读路径 = SQLDelight Flow → StateFlow（UI collectAsState）；
 * 写路径 = 同步 query（在调用方自己的 IO 调度里跑）。
 *
 * 种子数据：[seedDemoData] 能造 6 本示例漫画 + 章节 + 1 条阅读进度，
 * **只给测试当夹具用**，生产启动不调用（首启/清空后书架必须是真空的）。
 */
class LibraryRepository(private val db: GamangaDatabase) {

    private val queries: MangaQueries = db.mangaQueries
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 写库专用单线程作用域（prefs/progress 的异步写）。
     * 单线程 = 提交顺序即执行顺序；daemon 线程不阻退出。
     */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "db-write").apply { isDaemon = true }
    }
    private val writeScope = CoroutineScope(writeExecutor.asCoroutineDispatcher() + SupervisorJob())

    /** 书架条目（封面占位色随行返回，M3 有真实封面后改用 coverUrl）。 */
    data class LibraryItem(
        val entry: MangaEntry,
        val unreadCount: Long,
        val coverColorArgb: Long,
        /** R-09：置顶条目排网格最前，封面左上角有角标。 */
        val pinned: Boolean = false
    )

    /** 最近阅读（书架底部条）。 */
    data class RecentReading(
        val mangaTitle: String,
        val chapterName: String,
        val percent: Int
    )

    /**
     * 置顶 id 列表（按置顶先后排序）。
     *
     * 走 appPrefs 而不是给 manga 加列：历史原因是 schema 迁移窗口期
     * [app.gamanga.desktop.core.db] 的完整性校验会把「缺表/缺列」的库判成残缺库
     * 直接清空重建——书架会被吃掉（该风险已由 GamangaDatabaseFactory 的迁移
     * 梯子消除，见 STEPS 的规矩；置顶仍留在 prefs，只是展示顺序，没必要搬）。
     */
    private val pinnedIds = MutableStateFlow(readPinnedIds())

    /**
     * **库里所有 manga 行**的主键集合（不区分 `inLibrary`）。
     *
     * 专门给「下载缓存的孤儿清理」用（R-136，2026-10-28）：
     * 下载行有 `FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE`，
     * 所以「下载该不该留」的判据应该是**这一行 manga 还在不在库里**，
     * 而**不是**「它在不在书架上」。
     *
     * 上一版用的是 [libraryItems]（`inLibrary = 1`）→ 用户「移出书架」（软移出，只翻标志位）
     * 之后，这本书立刻被判成孤儿，下载行 + 磁盘目录 + 内存镜像被一起清掉，
     * 用户看到的就是「移除书架后下载页这本漫画完全消失了」。
     *
     * **初始值是 `null` 而不是 `emptySet()`**：第一次查库回来之前 `emptySet()` 会被下游
     * 当成「库里一本书都没有」，把全部下载缓存当孤儿清掉。`null` = 还没读到，别动手。
     */
    val allMangaIds: StateFlow<Set<String>?> =
        queries.selectAllMangaIds()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** 下载封面与详情元数据，包括已移出书架但仍保留下载的漫画。 */
    val mangaMetadata: StateFlow<Map<String, MangaEntry>> = queries.selectMangaMetadata()
        .asFlow().mapToList(Dispatchers.IO)
        .map { rows -> rows.associate { row -> row.id to MangaEntry(row.id, row.title, SourceType.valueOf(row.source), row.sourceMangaId, row.coverUrl, row.author) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** 书架条目（置顶在前，其次按加入时间倒序——来自 SQL 的原序）。 */
    val libraryItems: StateFlow<List<LibraryItem>> =
        combine(
            queries.selectLibraryWithUnread()
                .asFlow()
                .mapToList(Dispatchers.IO),
            pinnedIds
        ) { rows, pinned -> rows to pinned }
            .map { (rows, pinned) ->
                sortByPinned(rows, pinned) { it.id }.map { row ->
                    LibraryItem(
                        entry = MangaEntry(
                            id = row.id,
                            title = row.title,
                            source = SourceType.valueOf(row.source),
                            sourceMangaId = row.sourceMangaId,
                            coverUrl = row.coverUrl,
                            author = row.author
                        ),
                        unreadCount = row.unreadCount ?: 0L,
                        coverColorArgb = row.coverColorHex?.toLongOrNull(16)
                            ?: DEFAULT_COVER_COLOR,
                        pinned = pinned.contains(row.id)
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentReading: StateFlow<RecentReading?> =
        queries.selectRecentProgress()
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row ->
                row?.let {
                    val percent = if (it.totalPages > 0L) {
                        ((it.page + 1) * 100 / it.totalPages).toInt()
                    } else 0
                    RecentReading(it.mangaTitle, it.chapterName, percent)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 新书架页的条目：在 [LibraryItem] 基础上带**阅读进度**（2026-10-26）。
     *
     * 为什么要新模型而不是给 [LibraryItem] 加字段：老书架页（旧 `LibraryScreen`）
     * 和浏览页都在用 [LibraryItem]，改它会牵动三处。新页用新模型，等旧页退役再合并。
     */
    data class ShelfItem(
        val entry: MangaEntry,
        val unreadCount: Long,
        val coverColorArgb: Long,
        val pinned: Boolean,
        /** 读到的章节序号（0-based）；null = 没读过。 */
        val chapterIndex: Int?,
        /** 读到的章节名（源站原始名，可能很长）。 */
        val chapterName: String?,
        /** 整本进度百分比 0~100；0 = 没读过。 */
        val percent: Int,
        /** 最近阅读时间戳；0 = 没读过。「继续阅读」按它倒序取前 4。 */
        val lastReadAt: Long,
    ) {
        /** 卡片第二行左侧文案：「第 12 话」，没读过给「未读」。 */
        val chapterLabel: String
            get() = chapterIndex?.let { "第 ${it + 1} 话" } ?: "未读"
    }

    /** 书架（置顶在前，其次按加入时间倒序），带阅读进度。 */
    val shelfItems: StateFlow<List<ShelfItem>> =
        combine(
            queries.selectShelfWithProgress()
                .asFlow()
                .mapToList(Dispatchers.IO),
            pinnedIds
        ) { rows, pinned -> rows to pinned }
            .map { (rows, pinned) ->
                sortByPinned(rows, pinned) { it.id }.map { row ->
                    val total = row.progressTotalPages ?: 0L
                    val page = row.progressPage ?: 0L
                    ShelfItem(
                        entry = MangaEntry(
                            id = row.id,
                            title = row.title,
                            source = SourceType.valueOf(row.source),
                            sourceMangaId = row.sourceMangaId,
                            coverUrl = row.coverUrl,
                            author = row.author,
                        ),
                        unreadCount = row.unreadCount ?: 0L,
                        coverColorArgb = row.coverColorHex?.toLongOrNull(16) ?: DEFAULT_COVER_COLOR,
                        pinned = pinned.contains(row.id),
                        chapterIndex = row.progressChapterIndex?.toInt(),
                        chapterName = row.progressChapterName,
                        // totalPages = 0 说明进度行存在但页数未知（老数据），按 0% 显示而不是除零
                        percent = if (total > 0L) ((page + 1) * 100 / total).toInt().coerceIn(0, 100) else 0,
                        lastReadAt = row.progressUpdatedAt ?: 0L,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 历史页（排期第 4 项）：按最近阅读时间倒序的全量记录。
     *
     * R-123（2026-10-26）起**书架外试读也会进历史**：阅读器打开章节时会先调
     * [ensureReadingRows] 补一行 `inLibrary = 0` 的幽灵 manga + 幽灵 chapter，
     * 所以 `selectHistory` 的 INNER JOIN 不再把进度丢掉。
     * 章节名仍可能为 null（chapter 行被级联删），UI 用「第 N 页」兜底。
     */
    val history: StateFlow<List<HistoryEntry>> =
        queries.selectHistory()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.mapNotNull { row ->
                    val type = runCatching { SourceType.valueOf(row.source) }.getOrNull()
                        ?: return@mapNotNull null
                    HistoryEntry(
                        mangaId = row.mangaId,
                        title = row.mangaTitle,
                        coverUrl = row.coverUrl,
                        source = type,
                        sourceMangaId = row.sourceMangaId,
                        chapterId = row.chapterId,
                        chapterName = row.chapterName,
                        page = row.page.toInt(),
                        totalPages = row.totalPages.toInt(),
                        updatedAt = row.updatedAt,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ---- 书架 ----

    fun addToLibrary(entry: MangaEntry, coverColorArgb: Long = DEFAULT_COVER_COLOR) {
        upsertManga(
            id = entry.id,
            title = entry.title,
            source = entry.source.name,
            sourceMangaId = entry.sourceMangaId,
            coverUrl = entry.coverUrl,
            coverColorHex = String.format("FF%06X", coverColorArgb and 0xFFFFFFL),
            author = entry.author,
            addedAt = System.currentTimeMillis()
        )
    }

    /**
     * 入架 / 重新入架的**唯一写法**：`insertManga`（INSERT OR IGNORE）+ `updateMangaMeta`（UPDATE）。
     *
     * 为什么不是一条 UPSERT：SQLDelight 方言是 `sqlite_3_18`，`ON CONFLICT(id) DO UPDATE`
     * 要 SQLite 3.24+，生成阶段会直接报语法错。两条语句的组合语义完全等价，而且都不产生
     * DELETE —— 这是关键：`INSERT OR REPLACE` 的 DELETE 会顺着 `ON DELETE CASCADE` 把
     * 阅读进度 / 已读标记 / 下载记录一起删掉（2026-10-27 第五轮 R-131 的根因）。
     *
     * 两条都在**同一个事务**里：中途失败不会留下「有 manga 没元信息」的半行。
     */
    fun upsertManga(
        id: String,
        title: String,
        source: String,
        sourceMangaId: String,
        coverUrl: String?,
        coverColorHex: String?,
        author: String?,
        addedAt: Long,
    ) {
        db.transaction {
            queries.insertManga(
                id = id,
                title = title,
                source = source,
                sourceMangaId = sourceMangaId,
                coverUrl = coverUrl,
                coverColorHex = coverColorHex,
                author = author,
                addedAt = addedAt
            )
            queries.updateMangaMeta(
                title = title,
                source = source,
                sourceMangaId = sourceMangaId,
                coverUrl = coverUrl,
                coverColorHex = coverColorHex,
                author = author,
                addedAt = addedAt,
                id = id
            )
        }
    }

    fun removeFromLibrary(mangaId: String) {
        // 显式按顺序删，不依赖 PRAGMA foreign_keys —— 它是连接级开关，实测没生效，
        // 只删 manga 会留下孤儿章节/进度（移出后再加回来会"复活"上次的阅读位置）。
        db.transaction {
            queries.deleteProgressByManga(mangaId)
            queries.deleteChaptersByManga(mangaId)
            queries.deleteManga(mangaId)
        }
        // 置顶列表里清掉这条：否则以后有同 id 的书入架会被"借尸还魂"顶到最前。
        if (pinnedIds.value.contains(mangaId)) {
            val next = pinnedIds.value - mangaId
            setPref(PREF_PINNED, next.joinToString(","))
            pinnedIds.value = next
        }
    }

    internal fun migrateAtomically(block: () -> String): String {
        val pinnedBefore = pinnedIds.value
        return try {
            db.transactionWithResult { block() }
        } catch (e: Throwable) {
            pinnedIds.value = pinnedBefore
            throw e
        }
    }

    /**
     * 批量移出书架（新书架页多选删除，2026-10-26）。
     *
     * 与逐条调 [removeFromLibrary] 的区别：**一个事务删完**。
     * 多选 20 本时逐条调用会开 20 个事务、并且每本都写一次 prefs；
     * 中途失败还会留下「删了一半」的中间态。
     */
    fun removeMany(mangaIds: List<String>) {
        if (mangaIds.isEmpty()) return
        db.transaction {
            mangaIds.forEach { id ->
                // 顺序不能变：progress → chapter → manga（PRAGMA foreign_keys 实测不生效，级联是假的）
                queries.deleteProgressByManga(id)
                queries.deleteChaptersByManga(id)
                queries.deleteManga(id)
            }
        }
        val next = pinnedIds.value - mangaIds.toSet()
        if (next.size != pinnedIds.value.size) {
            setPref(PREF_PINNED, next.joinToString(","))
            pinnedIds.value = next
        }
    }

    /**
     * 整本标记已读（书架右键菜单「标记已读」，2026-10-26）。
     *
     * 只改 `isRead`，**不清 `lastOpenedAt`**——读过必然点过，清了章节会重新变黑
     * （与 F-39「灰 ≠ 已读」的口径一致：变灰看 lastOpenedAt，打勾看 isRead）。
     */
    fun markAllRead(mangaId: String) {
        queries.markAllChaptersRead(mangaId)
        // 推进版本号：详情页拿它当 key 重读章节状态，否则标记完颜色不变（F-39/F-40）
        _progressVersion.value += 1
    }

    /**
     * 整本标记未读（2026-10-26 用户要求：右键菜单在整本已读时切换成「标记未读」）。
     *
     * 连 `lastOpenedAt` 一起清——与单章「标记未读」同一口径（F-39）：
     * 只清 isRead 的话章节还是灰的，用户会以为点了没反应。
     */
    fun markAllUnread(mangaId: String) {
        queries.markAllChaptersUnread(mangaId)
        _progressVersion.value += 1
    }

    /**
     * 移出书架但**保留数据**（新书架页删除用，2026-10-26）。
     *
     * 与 [removeFromLibrary] 的区别：那个是**硬删**（manga + chapter + progress 全没），
     * 这个是置 `inLibrary = 0`。新书架页的删除带 6 秒撤销条，硬删之后无法恢复进度，
     * 所以必须走这个。撤销就是 [restoreToLibrary]。
     *
     * 副作用（刻意接受）：书会留在历史页里。R-123 的口径本来就是「看过就进历史」，
     * 书架外试读同样会留痕，语义一致。
     */
    fun hideFromLibrary(mangaIds: List<String>) {
        if (mangaIds.isEmpty()) return
        db.transaction {
            mangaIds.forEach { queries.setLibraryFlag(0L, it) }
        }
        val next = pinnedIds.value - mangaIds.toSet()
        if (next.size != pinnedIds.value.size) {
            setPref(PREF_PINNED, next.joinToString(","))
            pinnedIds.value = next
        }
    }

    /** 撤销移出书架（[hideFromLibrary] 的逆操作）。 */
    fun restoreToLibrary(mangaIds: List<String>) {
        if (mangaIds.isEmpty()) return
        db.transaction {
            mangaIds.forEach { queries.setLibraryFlag(1L, it) }
        }
    }

    // ---- 置顶（R-09）----

    /** 置顶 / 取消置顶。新置顶的排在最前还是末尾？末尾——先置顶的更重要。 */
    fun togglePin(mangaId: String) {
        val next = togglePinnedId(pinnedIds.value, mangaId)
        // 同步写：这是用户点一下菜单的一次性写（不像阅读器那样每帧都写），
        // 同步能保证「返回即落库」——重启后置顶不丢，测试也不用等异步线程。
        setPref(PREF_PINNED, next.joinToString(","))
        pinnedIds.value = next
    }

    private fun readPinnedIds(): List<String> =
        getPref(PREF_PINNED)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * 补空封面：书架条目的 `coverUrl` 为空时用详情页拿到的地址补上。
     *
     * 为什么需要：导入书架时可能拿不到封面（搜索结果没封面、手工补的数据、源站改版），
     * 之后书架就一直显示纯色占位块。详情页本来就会取一次 `detail()`，顺手回填即可自愈。
     * 只填空值，不覆盖已有封面——避免某次详情解析异常把好封面冲掉。
     */
    fun backfillCover(type: SourceType, sourceMangaId: String, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        queries.backfillCover(coverUrl, "${type.name.lowercase()}:$sourceMangaId")
    }

    /**
     * 把源站漫画连同章节一起收进书架（M3）。
     *
     * 本地主键约定：漫画 `"<source>:<sourceMangaId>"`，章节 `"<mangaId>#<sourceChapterId>"`，
     * 都能反解回源站 id（见 [sourceChapterIdOf]），阅读器据此回源取图。
     *
     * [addedAt] 可显式传入（F-03 导入 mihon 备份时用备份里的收藏时间，保持原有排序）；
     * 默认当前时间。
     */
    fun importFromSource(
        type: SourceType,
        manga: app.gamanga.desktop.source.SourceManga,
        chapters: List<app.gamanga.desktop.source.SourceChapter>,
        addedAt: Long = System.currentTimeMillis()
    ): String {
        val mangaId = "${type.name.lowercase()}:${manga.sourceId}"
        // **重新入架必须保留已读 / 点过 / 页数**（2026-10-27 第五轮 R-131）：
        // 这张表走 INSERT OR REPLACE（整行替换），不带上旧值就会把读过 200 话的书
        // 全部重置成「未读 + 没点过」，用户看到的就是「加入书架把记录清了」。
        //
        // 2026-10-29（R-159）进一步改成「先补不存在的行、再原地刷新元信息」：
        // 状态列不再参与写回，所以**不存在「读快照 → 写回」把刚标的已读冲掉**的竞态。
        db.transaction {
            // 入架两步走（INSERT OR IGNORE + UPDATE），**不能**用 INSERT OR REPLACE：
            // 后者的 DELETE 会顺着 ON DELETE CASCADE 把进度/已读/下载记录全删掉。
            queries.insertManga(
                id = mangaId,
                title = manga.title,
                source = type.name,
                sourceMangaId = manga.sourceId,
                coverUrl = manga.coverUrl,
                coverColorHex = String.format("FF%06X", (manga.title.hashCode() and 0x5F5F5F) or 0x909090),
                author = manga.author,
                addedAt = addedAt
            )
            queries.updateMangaMeta(
                title = manga.title,
                source = type.name,
                sourceMangaId = manga.sourceId,
                coverUrl = manga.coverUrl,
                coverColorHex = String.format("FF%06X", (manga.title.hashCode() and 0x5F5F5F) or 0x909090),
                author = manga.author,
                addedAt = addedAt,
                id = mangaId
            )
            chapters.filterNot { it.navigationOnly }.forEach { ch ->
                val chapterId = "$mangaId#${ch.id}"
                // 只补不存在的行（isRead / lastOpenedAt 由 SQL 的默认 0 决定）
                queries.insertGhostChapter(chapterId, mangaId, ch.name, ch.index.toLong())
                // 已存在的行只刷新名字/序号/页数，**状态列一个都不碰**
                queries.updateChapterMeta(
                    name = ch.name,
                    chapterIndex = ch.index.toLong(),
                    // Komiic 源接口直给每章页数；包子漫画给 0，等阅读时回填。
                    // 传 0 时 SQL 里保留旧值，进度百分比不会突然变成 0%。
                    pageCount = ch.pageCount.toLong(),
                    id = chapterId,
                )
            }
        }
        return mangaId
    }

    /**
     * 把**详情页刚拉到的章节列表**同步进库（2026-10-25 用户实测：拷贝漫画联网看过 206 话，
     * 断网后只剩 1 条——因为章节只在「加入书架」那一刻落库，之后列表更新不会回写）。
     *
     * 只在**已在书架**的书上写（没入架不落库，避免脏数据）。
     *
     * **2026-10-29 R-159：不再「读快照 → INSERT OR REPLACE 写回」**。
     * 旧写法在详情页打开时的后台刷新里跑，与用户点「标记已读」撞车时会把刚标好的
     * 已读冲成未读（用户看到的就是「一开始标记不生效，过一会儿再标又好了」）。
     * 现在：不存在的行用 `insertGhostChapter` 补，存在的行用 `updateChapterMeta`
     * 只刷名字/序号/页数 —— 状态列（isRead / lastOpenedAt）完全不出现在写语句里。
     */
    fun syncChapters(localMangaId: String, chapters: List<SourceChapter>) {
        if (chapters.isEmpty()) return
        val existing = queries.selectChaptersByManga(localMangaId).executeAsList()
        // 不在书架：不落库。
        //
        // **2026-10-30（T-06 缓存体检）改判据**：原来只看「章节表空不空」——
        // 章节表空但书**在书架**时（导入中途失败 / 手动清过章节），章节就永远进不了库，
        // 于是每次打开详情都得重新联网拉一遍，断网时章节区永远是空的（本地缓存等于没有）。
        // 现在按 manga 行的 `inLibrary` 判断，书架里的书一定会被缓存下来。
        if (existing.isEmpty() && queries.selectMangaById(localMangaId).executeAsOneOrNull()?.inLibrary != 1L) {
            return
        }
        db.transaction {
            chapters.filterNot { it.navigationOnly }.forEach { ch ->
                val chapterId = "$localMangaId#${ch.id}"
                queries.insertGhostChapter(chapterId, localMangaId, ch.name, ch.index.toLong())
                queries.updateChapterMeta(
                    name = ch.name,
                    chapterIndex = ch.index.toLong(),
                    pageCount = ch.pageCount.toLong(),
                    id = chapterId,
                )
            }
        }
    }

    /**
     * 一次性清掉历史版本遗留的示例数据（[seedDemoData] 造的 6 本）。
     *
     * 背景：老版本在 `main()` 里首启自动回填示例数据，已经装过/跑过老版本的机器上，
     * 这 6 本还留在库里。用户 2026-10-28 要求「打包应该把这些假的漫画封面直接移除」，
     * 光删掉回填调用不够——已有数据得主动清一次，否则他还得手动右键移出 6 次。
     *
     * **判据刻意保守**：id 必须是种子 id，且 `coverUrl` 仍为 null（种子数据从不写封面）。
     * 用户如果给这几本补过自定义封面/真封面，`coverUrl` 非空就跳过，当作用户数据不动。
     *
     * @return 实际删掉的本数（0 表示本来就没有，可以据此不写日志噪音）
     */
    fun purgeDemoData(): Int {
        val targets = queries.selectMangaMetadata().executeAsList()
            .filter { it.id in DEMO_MANGA_IDS && it.coverUrl == null }
            .map { it.id }
        if (targets.isEmpty()) return 0
        db.transaction {
            targets.forEach { id ->
                queries.deleteProgressByManga(id)
                queries.deleteChaptersByManga(id)
                queries.deleteManga(id)
            }
        }
        return targets.size
    }

    /**
     * 造一批示例数据（6 本漫画 + 章节 + 1 条阅读进度）。
     *
     * **只给测试用，生产启动路径不调用。** 用户 2026-10-28 明确要求：
     * 「清空所有数据或者新安装的页面是我测试时的界面，打包应该把这些假的漫画封面直接移除」。
     * 之前是 `main()` 里首启空库自动回填，结果「清空所有数据」重启后又长出 6 本假漫画，
     * 看上去像没清干净。
     *
     * 为什么保留这个函数而不是删掉：`LibraryRepositoryTest` 有 10 个用例拿它当夹具，
     * 删了要重写一堆造数代码，收益不大。名字改成 `seedDemoData` 是为了让调用方一眼看出
     * 这不是生产逻辑。
     */
    fun seedDemoData() {
        db.transaction {
            if (queries.selectLibraryWithUnread().executeAsList().isNotEmpty()) return@transaction
            val now = System.currentTimeMillis()
            seedManga.forEachIndexed { i, seed ->
                queries.insertManga(
                    id = seed.id,
                    title = seed.title,
                    source = seed.source.name,
                    sourceMangaId = seed.sourceMangaId,
                    coverUrl = null,
                    coverColorHex = seed.colorHex,
                    author = seed.author,
                    addedAt = now - i * 60_000L
                )
                for (n in 1..seed.chapters) {
                    queries.insertChapter(
                        id = "${seed.id}-c$n",
                        mangaId = seed.id,
                        name = "第 $n 话",
                        chapterIndex = n.toLong(),
                        isRead = if (n <= seed.firstRead) 1L else 0L,
                        lastOpenedAt = 0L,
                        pageCount = 0L // 种子数据没有源站页数；读的时候会回填
                    )
                }
            }
            // 剑来：第 11 话读到第 6 页 / 共 9 页
            queries.upsertProgress(
                mangaId = "local-1",
                chapterId = "local-1-c11",
                page = 5,
                totalPages = 9,
                updatedAt = now
            )
        }
    }

    // ---- 章节 ----

    fun chaptersOf(mangaId: String): List<Chapter> =
        queries.selectChaptersByManga(mangaId).executeAsList().map { row ->
            Chapter(
                id = row.id,
                mangaId = row.mangaId,
                name = row.name,
                index = row.chapterIndex.toInt(),
                read = row.isRead == 1L,
                pageCount = row.pageCount.toInt(),
                lastOpenedAt = row.lastOpenedAt
            )
        }

    /** 某本已落库的章节数（F-271 章节刷新用它判断「比看过的那一版多了几话」）。 */
    fun chapterCountOf(mangaId: String): Int =
        queries.selectChapterCountByManga(mangaId).executeAsOne().toInt()

    /**
     * 标记已读 / 未读。
     *
     * **标记未读要连「点过」一起清**（F-39，用户明确）：否则章节还是灰的，
     * 用户会以为「点了没反应」。已读只改 isRead，不动 lastOpenedAt（读过必然点过）。
     *
     * **2026-10-29 R-159：行不存在时补一行**。旧实现是纯 UPDATE，碰上「书架里有这本、
     * 但该章的行还没落库」（换源迁移 / 备份导入 / 源站重建了章节 id）就静默无事发生，
     * 用户看到的就是「右键标记已读完全没反应」。现在先查一次，缺行就补幽灵行再写状态。
     *
     * @param name 章节名（补行时要用；调用方有就传）
     * @param chapterIndex 章节序号（补行时要用，默认 0）
     * @return true = 真的写进去了（false = 章节所属的漫画行不存在，试读场景，无法补行）
     */
    fun setChapterRead(
        chapterId: String,
        read: Boolean,
        name: String? = null,
        chapterIndex: Int = 0,
    ): Boolean {
        val row = queries.selectChapterById(chapterId).executeAsOneOrNull()
        if (row == null) {
            // chapter.mangaId 是外键：漫画行不存在（书架外试读）时插不进去，只能放弃
            val mangaId = chapterId.substringBeforeLast('#', chapterId)
            if (name == null || mangaId == chapterId) return false
            if (queries.selectMangaById(mangaId).executeAsOneOrNull() == null) return false
            queries.insertGhostChapter(chapterId, mangaId, name, chapterIndex.toLong())
            AppLog.i("Library", "标记已读补行 $chapterId（$name）")
        }
        if (read) queries.markChapterRead(1L, chapterId) else queries.markChapterUnread(chapterId)
        // 推进版本号：详情页拿它当 key 重读章节状态，否则标记完颜色不变（F-39/F-40）
        _progressVersion.value += 1
        return true
    }

    /**
     * 累加观看时长（F-38）。[day] 用本地日期 `yyyy-MM-dd`，默认今天。
     *
     * 计时规则在调用方（阅读器）：每页最多计 60 秒、翻页/切话重置——这里只负责落库。
     */
    fun addReadingSeconds(seconds: Int, day: String = java.time.LocalDate.now().toString()) {
        if (seconds <= 0) return
        queries.ensureReadingDay(day)
        queries.addReadingSecondsOfDay(seconds.toLong(), day)
    }

    /** 统计快照（F-38 数据层；UI 在阶段 4 做）。 */
    fun readingStats(today: String = java.time.LocalDate.now().toString()): ReadingStats =
        ReadingStats(
            libraryCount = queries.selectLibraryCount().executeAsOne(),
            readChapterCount = queries.selectReadChapterCount().executeAsOne(),
            downloadedChapterCount = queries.selectDownloadedChapterCount().executeAsOne(),
            downloadedMangaCount = queries.selectDownloadedMangaCount().executeAsOne(),
            todaySeconds = queries.selectReadingSecondsOfDay(today).executeAsOneOrNull() ?: 0L,
            totalSeconds = queries.selectTotalReadingSeconds().executeAsOne(),
        )

    /**
     * 清空观看时长记录（统计清理入口，设置页调用）。
     *
     * **只删 `readingTime` 一张表的行**：书架、章节、阅读进度是另外的表，天然不受影响——
     * 用户点「清空统计数据」的预期是「把计时归零」，不是「把收藏和读到的位置也抹掉」。
     * 清空后 [readingStats] 的 `todaySeconds` / `totalSeconds` 自然回到 0。
     */
    fun clearReadingTime() {
        queries.deleteReadingDays()
    }

    /**
     * **清除所有数据**（设置页「恢复初始安装状态」，2026-10-27 第五轮用户要求，
     * 第六轮按「保留下载缓存」重做）。
     *
     * ## 用户口径（2026-10-27 第六轮原话）
     *
     * 「清除所有数据应该清除所有除了下载缓存以外的，让其恢复到刚开始安装的初始状态，
     *   注意数据安全不要把其他软件的数据给清理掉」。
     *
     * ## 清什么
     *
     * | 类别 | 具体 |
     * |---|---|
     * | 阅读进度 | `readingProgress` 全部行（历史页的数据源） |
     * | 阅读统计 | `readingTime` 全部行 |
     * | 书架 | `manga` 里**没有下载记录**的全部行（含 R-123 幽灵行） |
     * | 章节 | 同上那些书的 `chapter` 行（已读标记在这里） |
     * | 配置 | `appPrefs` 除 `download.root` 外**全部**（窗口尺寸 / 代理 / WebDAV / 阅读设置 / cookie / 置顶 / 浏览历史 / 自定义封面映射） |
     *
     * ## 保留下载缓存（关键）
     *
     * 「下载缓存」= `downloadChapter` 行 + 磁盘上的图片 + **能读到它们的书与章节**。
     * 三者缺一，下载页就是一堆点不开的孤儿：
     * - `downloadChapter` 有 `FOREIGN KEY (mangaId) REFERENCES manga(id) ON DELETE CASCADE`，
     *   直接 `DELETE FROM manga` 会把下载记录一起级联删掉；
     * - 下载页 `selectDownloadsWithTitle` 要 `JOIN manga` 拿书名；
     * - 「阅读」要走 `repository.chaptersOf(mangaId)` 重建章节数组。
     *
     * 所以：**有下载记录的书一行都不删**，只把 `inLibrary` 置 0（退回幽灵行）——
     * 书架因此是空的（初始状态），下载页照常能看能读。
     *
     * ## 为什么不删磁盘文件
     *
     * ①那些是用户看得见的文件，误删几十 GB 不可恢复；
     * ②**数据安全**：只允许在 `%LOCALAPPDATA%\\Gamanga` 自己的目录里动手，
     * 绝不按「路径前缀 / 通配符」去删别的目录（用户明确提醒「不要把其他软件的数据给清理掉」）。
     * 自定义封面的图片文件由 `CustomCoverStore.purgeFiles()` 在**它自己的目录内**逐文件删。
     *
     * ## 为什么一个事务
     *
     * 中途失败会留下「书架空了但历史还在」的中间态，用户看到的是一半清干净一半没清。
     */
    fun clearAllUserData(): ClearResult {
        // 有下载记录的书 = 要保留的书（见上面「保留下载缓存」）
        val keepIds = db.downloadQueries.selectAllDownloads()
            .executeAsList()
            .map { it.mangaId }
            .toSet()
        var removed = 0
        db.transaction {
            queries.deleteReadingDays()
            queries.clearAllProgress()
            // 不能 clearAllChapters / clearAllManga：会把要保留的书一起删掉
            queries.selectAllMangaIds().executeAsList()
                .filterNot { it in keepIds }
                .forEach { id ->
                    queries.deleteChaptersByManga(id)
                    queries.deleteManga(id)
                    removed++
                }
            // 保留下来的书退回幽灵行：书架清空，下载页仍能 join 到书名
            queries.clearLibraryFlags()
            queries.clearContentPrefs()
        }
        // 内存里的派生状态也要跟着归零，否则界面要等重启才干净
        pinnedIds.value = emptyList()
        _progressVersion.value += 1
        return ClearResult(keptForDownloads = keepIds.size, removedBooks = removed)
    }

    /** [clearAllUserData] 的结果（设置页用它给用户一句准确的提示）。 */
    data class ClearResult(
        /** 因为有下载记录而被保留的书数。 */
        val keptForDownloads: Int,
        /** 被真正删掉的书数。 */
        val removedBooks: Int,
    )

    /** 记「点过」（F-39）：点开章节时调，只影响详情页灰显，与已读无关。 */
    fun setChapterOpened(chapterId: String) {
        queries.markChapterOpened(System.currentTimeMillis(), chapterId)
    }

    /**
     * 异步版（阅读器打开章节时调，别堵主线程）。
     *
     * 写完推进 [progressVersion]：详情页拿它当 key 重读章节状态，否则「刚点过的章节
     * 返回详情页还是正常色」——用户会以为灰显坏了。
     */
    fun setChapterOpenedAsync(chapterId: String) {
        val now = System.currentTimeMillis()
        writeScope.launch {
            queries.markChapterOpened(now, chapterId)
            _progressVersion.value += 1
        }
    }

    /**
     * 回填某章页数（#30）。阅读器取到整章图片后调——这是 G社 漫画唯一的
     * 页数来源（它的章节接口不给页数，图片列表又是加密的）。
     *
     * 行不存在时静默跳过：没入书架就试读的漫画没有 chapter 行，而 chapter 表
     * 有 mangaId 外键，那种场景插不进去（所以是 UPDATE 而不是 upsert）。
     */
    fun updateChapterPageCount(chapterId: String, pageCount: Int) {
        if (pageCount <= 0) return
        queries.updateChapterPageCount(pageCount.toLong(), chapterId)
    }

    /** 异步版（阅读器取页完成时调，理由同 [setPrefAsync]）。 */
    fun updateChapterPageCountAsync(chapterId: String, pageCount: Int) {
        if (pageCount <= 0) return
        writeScope.launch { queries.updateChapterPageCount(pageCount.toLong(), chapterId) }
    }

    /**
     * 本地已缓存的章节页数：源站章节 id → 页数（#30）。
     *
     * 详情页章节列表的兜底数据源：源站不给页数时（G社），读过的章在这里有数。
     * 章节本地 id 是 `"<mangaId>#<sourceChapterId>"`，反解出源站 id 才能和
     * 详情页的 [app.gamanga.desktop.source.SourceChapter] 对上；反解不了的
     * （种子数据的 `local-1-c11` 形态）丢弃。
     */
    fun cachedPageCounts(mangaId: String): Map<String, Int> =
        chaptersOf(mangaId).mapNotNull { ch ->
            if (ch.pageCount <= 0) return@mapNotNull null
            val sourceChapterId = ch.id.substringAfter('#', "")
            if (sourceChapterId.isEmpty()) null else sourceChapterId to ch.pageCount
        }.toMap()

    /**
     * R-123：补齐「这本书 + 这一话」的行，让书架外试读也能进历史。
     *
     * **用户实测**：「我刚刚看了个书架里没有的动漫，但是历史上并没有记录下来」。
     * 根因不在写入端（阅读器一直在写 `readingProgress`），而在读取端：
     * 历史页的 `selectHistory` 是 `readingProgress JOIN manga`，
     * 书架外的书根本没有 `manga` 行 → 进度行被 JOIN 丢掉 → 历史里什么都不显示。
     *
     * 做法：写一行 `inLibrary = 0` 的**幽灵 manga** + 一行**幽灵 chapter**。
     * - 两条都是 `INSERT OR IGNORE`：书架里已有的书原样不动（不会被降级成幽灵）；
     * - 幽灵行只服务历史/进度，所有书架语义查询都带 `inLibrary = 1` 把它们过滤掉；
     * - 幽灵章节的 `isRead` 恒为 0 且不影响统计——`selectTotalUnread` /
     *   `selectReadChapterCount` 也都带了 `inLibrary = 1`。
     *
     * 同步执行（调用方在阅读器里一话只调一次）：必须保证它先于任何进度写入落库。
     */
    fun ensureReadingRows(
        mangaId: String,
        title: String,
        source: SourceType,
        sourceMangaId: String,
        coverUrl: String?,
        chapterId: String,
        chapterName: String,
        chapterIndex: Int,
    ) {
        queries.insertGhostManga(
            id = mangaId,
            title = title,
            source = source.name,
            sourceMangaId = sourceMangaId,
            coverUrl = coverUrl,
            coverColorHex = String.format("FF%06X", DEFAULT_COVER_COLOR and 0xFFFFFFL),
            author = null,
        )
        queries.insertGhostChapter(
            id = chapterId,
            mangaId = mangaId,
            name = chapterName,
            chapterIndex = chapterIndex.toLong(),
        )
    }

    fun saveProgress(progress: ReadingProgress) {
        queries.upsertProgress(
            mangaId = progress.mangaId,
            chapterId = progress.chapterId,
            page = progress.page.toLong(),
            totalPages = progress.totalPages.toLong(),
            updatedAt = System.currentTimeMillis()
        )
        _progressVersion.value += 1
    }

    /**
     * 阅读进度变更计数（2026-10-24 用户反馈「从阅读器返回详情页没有实时同步记录」）。
     *
     * 详情页的「继续阅读 · 第 N 话」是 `remember(...) { progressOf(localId) }` 算出来的，
     * 只在 key 变化时重读——从阅读器返回时 key 没变，于是永远显示进阅读器之前的旧进度。
     * 把这个计数放进 remember 的 key 里，阅读器每写一次进度详情页就重读一次。
     */
    private val _progressVersion = MutableStateFlow(0)
    val progressVersion: StateFlow<Int> = _progressVersion

    /**
     * 单本漫画的封面地址（2026-10-30：阅读器顶栏那张 26×36 的小封面要用）。
     *
     * 阅读器的入口有多种（详情页 / 历史 / 下载页 / 书架），不是每条都带着封面地址；
     * 有 `localMangaId` 时回库查一次是最省事、也最不会漏的做法——DB 里的 `coverUrl`
     * 是入架/试读时写下的原值，拿到后 `CoverCache.localUri()` 再换成本地 `file:`。
     *
     * 同步读一行（主键查询，微秒级），只在阅读器打开时调用一次，不会卡界面。
     */
    fun coverUrlOf(mangaId: String): String? =
        runCatching { queries.selectMangaById(mangaId).executeAsOneOrNull()?.coverUrl }.getOrNull()

    /** 某本书的阅读进度（打开阅读器时定位到上次那一话）。 */
    fun progressOf(mangaId: String): ReadingProgress? =
        queries.selectProgressByManga(mangaId).executeAsOneOrNull()?.let {
            ReadingProgress(it.mangaId, it.chapterId, it.page.toInt(), it.totalPages.toInt())
        }

    /**
     * 删掉一本书的阅读进度 = 从「历史」里移除这条记录（2026-10-24 用户要求）。
     *
     * 只删 progress 行，不动书架/章节/已下载文件——用户要的是「别再出现在历史里」，
     * 不是把书移出书架。
     */
    fun deleteProgress(mangaId: String) {
        queries.deleteProgressByManga(mangaId)
        _progressVersion.value += 1
    }

    /**
     * 把一条被删掉的历史记录写回去（历史页「从历史移除」的 6 秒回撤，2026-10-27 第五轮）。
     *
     * **和 [saveProgress] 的唯一区别是 `updatedAt` 用原始值。**
     * 用 saveProgress 回撤会把时间改成「现在」，那条记录立刻跳到历史列表最前面——
     * 用户撤的是「误点删错了」，不是「刚读完」，位置必须还原。
     */
    fun restoreHistory(entry: HistoryEntry) {
        queries.upsertProgress(
            mangaId = entry.mangaId,
            chapterId = entry.chapterId,
            page = entry.page.toLong(),
            totalPages = entry.totalPages.toLong(),
            updatedAt = entry.updatedAt,
        )
        _progressVersion.value += 1
    }

    // ---- 键值 ----

    fun getPref(key: String): String? = queries.selectPref(key).executeAsOneOrNull()

    fun setPref(key: String, value: String) {
        queries.upsertPref(key, value)
    }

    /**
     * 异步写 prefs（fire-and-forget）。
     *
     * **为什么必须异步**：阅读器每切一次阅读方式/缩放就写 3 个 key，同步写库跑在
     * Compose UI 线程上——实测表现为「缩放要隔 1 秒多才有反应、滑动卡、滚轮偶尔
     * 无响应」（主线程被 SQLite 写堵住，帧派发和指针事件都跟着堵）。
     * 单线程调度器保证同一个 key 的写入按提交顺序生效（后写覆盖先写，不会乱序）。
     */
    fun setPrefAsync(key: String, value: String) {
        writeScope.launch { queries.upsertPref(key, value) }
    }

    /**
     * 异步落阅读进度（阅读器滚动停稳时每页都调，理由同 [setPrefAsync]）。
     * [markChapterRead] 顺带把该话标记已读——和进度写同一个写线程，不会半套。
     */
    fun saveProgressAsync(progress: ReadingProgress, markChapterRead: Boolean = false) {
        writeScope.launch {
            queries.upsertProgress(
                mangaId = progress.mangaId,
                chapterId = progress.chapterId,
                page = progress.page.toLong(),
                totalPages = progress.totalPages.toLong(),
                updatedAt = System.currentTimeMillis()
            )
            if (markChapterRead) queries.markChapterRead(1L, progress.chapterId)
            // 在写线程里自增：详情页 collect 到这个值就重读进度（见 progressVersion 注释）
            _progressVersion.value += 1
        }
    }

    companion object {
        const val PREF_WINDOW_SIZE = "window.size"

        /** 置顶 id 列表（逗号分隔，按置顶先后）。R-09。 */
        const val PREF_PINNED = "library.pinned"

        /**
         * 浏览历史（JSON 数组，见 [BrowseHistoryStore]）。F-128。
         *
         * 与阅读进度历史**完全独立**：这里只记「在浏览页点开过」，没读过也算。
         */
        const val PREF_BROWSE_HISTORY = "browse.history"

        // ---- HTTP 代理（第八轮 #8）----

        /** 全局代理开关（"1" 开 / 其他关）。 */
        const val PREF_PROXY_ENABLED = "proxy.enabled"

        /** 代理地址（host，可空）。 */
        const val PREF_PROXY_HOST = "proxy.host"

        /** 代理端口（1~65535）。 */
        const val PREF_PROXY_PORT = "proxy.port"

        /** 代理协议：HTTP / SOCKS5（缺省 HTTP）。 */
        const val PREF_PROXY_PROTOCOL = "proxy.protocol"

        /** 单源代理开关（"0" = 该源直连，不跟全局；缺省/"1" = 跟随全局）。 */
        fun proxySourcePrefKey(type: app.gamanga.desktop.model.SourceType) = "proxy.source.${type.name}"

        /** 下载缓存根目录（空 = 默认 `%LOCALAPPDATA%\Gamanga\downloads`）。第七轮 #7。 */
        const val PREF_DOWNLOAD_ROOT = "download.root"

        /** 下载章节间并发数（1~8，默认 2）。第七轮 #2。 */
        const val PREF_DOWNLOAD_CONCURRENCY = "download.concurrency"

        /** 源站导出的 cookie 持久化 key（M3/M4 原生适配器读取）。 */
        fun cookiePrefKey(domain: String) = "cookies.$domain"

        /** 本地漫画 id → 源站 id（`komiic:228` → `228`）。 */
        fun sourceMangaIdOf(localId: String) = localId.substringAfter(':', localId)

        /**
         * 本地章节 id → 源站章节 id（`komiic:228#3276` → `3276`）。
         *
         * **必须按第一个 `#` 切**（2026-10-25 线上 BUG）：拷贝漫画的**源站章节 id 自己就带 `#`**
         * （`guimiezhiren#<uuid>`），本地 id 于是长这样：
         * `copymanga:guimiezhiren#guimiezhiren#<uuid>`。
         * 用 `substringAfterLast('#')` 会把 `guimiezhiren#` 前缀一起切掉，
         * 还原出的源站 id 就是错的 → 下载键对不上 → 断网时「该话无图片」。
         * 而 `localMangaId`（`<source>:<sourceMangaId>`）本身不含 `#`，所以第一个 `#` 之后就是源站 id。
         */
        fun sourceChapterIdOf(localChapterId: String) =
            localChapterId.substringAfter('#', localChapterId)

        /** 无封面占位色（mihon 风格淡色 ARGB）。 */
        const val DEFAULT_COVER_COLOR: Long = 0xFFB5D4F4L

        /**
         * 示例数据的固定 id。真实漫画的 id 由源站数据生成，不会长这样；
         * [purgeDemoData] 用它做「这条是不是示例数据」的第一道判据。
         */
        private val DEMO_MANGA_IDS = setOf("local-1", "local-2", "local-3", "local-4", "local-5", "local-6")

        private data class SeedManga(
            val id: String,
            val title: String,
            val source: SourceType,
            val sourceMangaId: String,
            val colorHex: String,
            val author: String?,
            val chapters: Int,
            val firstRead: Int
        )

        private val seedManga = listOf(
            SeedManga("local-1", "剑来", SourceType.KOMIIC, "m1001", "FFB5D4F4", "烽火戏诸侯", 12, 10),
            SeedManga("local-2", "镖人", SourceType.KOMIIC, "m1002", "FFCECBF6", "许先哲", 10, 7),
            SeedManga("local-3", "尚善", SourceType.GODA, "m2001", "FF9FE1CB", null, 8, 5),
            SeedManga("local-4", "端脑", SourceType.GODA, "m2002", "FFFAC775", "壁水羽", 6, 6),
            SeedManga("local-5", "人间武圣", SourceType.KOMIIC, "m1005", "FFF4C0D1", null, 9, 5),
            SeedManga("local-6", "玄皓战记", SourceType.GODA, "m2003", "FFC0DD97", "郑多强", 5, 5)
        )
    }
}

/**
 * 书架排序（R-09）：置顶的排最前（按置顶先后），其余保持原序（SQL 的 addedAt DESC）。
 *
 * 稳定排序——同一档位内相对顺序不变，否则每次重排书架都会"洗牌"。
 * 纯函数，由 desktopTest 的 [LibraryOrderTest] 锁住。
 */
internal fun <T> sortByPinned(items: List<T>, pinnedIds: List<String>, idOf: (T) -> String): List<T> =
    items.sortedBy { item ->
        val i = pinnedIds.indexOf(idOf(item))
        if (i >= 0) i else Int.MAX_VALUE
    }

/** 置顶切换：没有则追加到末尾，已有则移除（纯函数，便于测试）。 */
internal fun togglePinnedId(ids: List<String>, id: String): List<String> =
    if (ids.contains(id)) ids - id else ids + id

/** 全局服务定位器（自用规模，不引 DI 框架）。 */
object AppGraph {
    val database: GamangaDatabase by lazy { GamangaDatabaseFactory.create() }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database) }

    /** R-04 下载管理器：落盘根目录默认 `%LOCALAPPDATA%\Gamanga\downloads`，
     * 可在设置页改（pref 记住位置，[DownloadManager.relocate] 负责搬文件）。 */
    val downloadManager: DownloadManager by lazy {
        val repo = libraryRepository
        val defaultRoot = java.nio.file.Paths.get(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "Gamanga",
            "downloads"
        )
        val savedRoot = repo.getPref(LibraryRepository.PREF_DOWNLOAD_ROOT)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.nio.file.Paths.get(it) }.getOrNull() }
        val manager = DownloadManager(
            db = database,
            rootDir = savedRoot ?: defaultRoot,
            libraryRepository = repo,
        )
        // 并发数也走 pref：默认 2（Komiic 取图烧配额、G社 拉隐藏浏览器，
        // 章节级并发再高只是让取图阶段互相排队）
        val savedConcurrency = repo.getPref(LibraryRepository.PREF_DOWNLOAD_CONCURRENCY)
            ?.toIntOrNull()
        if (savedConcurrency != null) manager.setMaxParallelChapters(savedConcurrency)
        manager
    }
    val backupManager: app.gamanga.desktop.data.backup.BackupManager by lazy {
        app.gamanga.desktop.data.backup.BackupManager(database, libraryRepository)
    }

    /** F-128：浏览页的「浏览历史」（与阅读进度历史独立，存 appPrefs）。 */
    val browseHistory: BrowseHistoryStore by lazy {
        BrowseHistoryStore(libraryRepository)
    }
}

/**
 * 统计页要的全部数字（F-38）。
 *
 * 「下载漫画数」按**漫画**计数：同一本下了 3 话也只算 1 本（用户 2026-10-25 口径）。
 */
data class ReadingStats(
    val libraryCount: Long,
    val readChapterCount: Long,
    val downloadedChapterCount: Long,
    val downloadedMangaCount: Long,
    /** 今天的观看秒数。 */
    val todaySeconds: Long,
    val totalSeconds: Long,
)
