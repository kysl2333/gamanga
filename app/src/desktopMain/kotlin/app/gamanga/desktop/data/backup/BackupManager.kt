package app.gamanga.desktop.data.backup

import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant
import java.io.File
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.CustomCoverStore
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.SearchAll
import app.gamanga.desktop.data.WebDavClient
import app.gamanga.desktop.data.backupFilename
import app.gamanga.desktop.data.joinUrl
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * F-03：备份 / 恢复 / mihon 备份导入。
 *
 * 三种能力一张口：
 * 1. [exportBackup] / [restoreBackup]——我们自己的格式：一个 ZIP 里放一份 JSON
 *    （书架 + 章节已读 + 阅读进度 + appPrefs）。JSON 用 kotlinx 的 JsonElement 树
 *    API 手工构建（项目没配 serialization 编译器插件，不写 @Serializable）。
 * 2. [backupToWebDav]——导出并推到 WebDAV，只保留最近 [DEFAULT_KEEP] 份。
 * 3. [importMihonBackup]——解析 mihon 的 `.tachibk`（gzip+protobuf），把书收进
 *    书架并按「话数匹配」回填观看历史（已读/读到第几页）。
 *
 * 恢复是**合并语义**：备份里有的覆盖/按 updatedAt 较新者胜，本地多出来的不动
 * （不删库）。理由：备份可能是旧的，用户恢复后不想丢恢复点之后产生的新数据。
 */
class BackupManager(
    private val db: GamangaDatabase,
    private val repository: LibraryRepository,
    /** 源适配器解析器（测试注入 fake；线上走 [Sources]）。 */
    private val adapterResolver: (SourceType) -> SourceAdapter? = Sources::of
) {

    private val queries get() = db.mangaQueries

    // ---- 我们自己的备份格式 ----

    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) {
        val mangaRows = queries.selectAllManga().executeAsList()
        val progressRows = queries.selectAllProgress().executeAsList()
        val prefRows = queries.selectAllPrefs().executeAsList()
        // **只带「进度用得到」的章节**（每本漫画当前在读的那一话）。
        // 旧实现把每本漫画的全部章节都塞进备份：一本 200+ 话、几十本 → 文件里绝大多数
        // 是永远不会用到的章节行（2026-10-25 用户反馈「备份里包含了每一部漫画的章节，感觉不需要」）。
        // 进度行只需要 chapterId + 章节名能显示，所以保留这一话就够恢复「读到第几话第几页」。
        val neededChapterIds = progressRows.map { it.chapterId }.toSet()
        val chapterRows = if (neededChapterIds.isEmpty()) emptyList()
        else mangaRows.flatMap { queries.selectChaptersByManga(it.id).executeAsList() }
            .filter { it.id in neededChapterIds }

        val root = buildJsonObject {
            put("format", FORMAT_NAME)
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            putJsonArray("manga") {
                for (m in mangaRows) add(buildJsonObject {
                    put("id", m.id)
                    put("title", m.title)
                    put("source", m.source)
                    put("sourceMangaId", m.sourceMangaId)
                    put("coverUrl", m.coverUrl)
                    put("coverColorHex", m.coverColorHex)
                    put("author", m.author)
                    put("addedAt", m.addedAt)
                })
            }
            putJsonArray("chapters") {
                for (c in chapterRows) add(buildJsonObject {
                    put("id", c.id)
                    put("mangaId", c.mangaId)
                    put("name", c.name)
                    put("chapterIndex", c.chapterIndex)
                    put("isRead", c.isRead != 0L)
                    put("pageCount", c.pageCount)
                })
            }
            putJsonArray("progress") {
                for (p in progressRows) add(buildJsonObject {
                    put("mangaId", p.mangaId)
                    put("chapterId", p.chapterId)
                    put("page", p.page)
                    put("totalPages", p.totalPages)
                    put("updatedAt", p.updatedAt)
                })
            }
            putJsonArray("prefs") {
                for (kv in prefRows) add(buildJsonObject {
                    put("key", kv.key)
                    // SQLDelight 把 value 列生成成 value_（避开保留字冲突）
                    put("value", kv.value_)
                })
            }
        }
        val json = Json.encodeToString(JsonElement.serializer(), root).toByteArray()
        zipSingleEntry(ENTRY_NAME, json)
    }

    /**
     * 从我们自己的备份恢复（合并语义，见类注释）。
     *
     * 文件不合法抛 [BackupFormatException]；单条记录坏了跳过该条，不影响整体。
     */
    suspend fun restoreBackup(zip: ByteArray): RestoreReport = withContext(Dispatchers.IO) {
        val jsonText = readSingleJson(zip)
            ?: throw BackupFormatException("备份文件里找不到 $ENTRY_NAME（不是 Gaaa漫画 的备份？）")
        val root = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw BackupFormatException("备份内容不是合法 JSON：${e.message?.take(80)}")
        }
        if (root.str("format") != FORMAT_NAME) {
            throw BackupFormatException("备份格式不是 $FORMAT_NAME")
        }
        val version = root.long("version") ?: 0L
        if (version > FORMAT_VERSION) {
            throw BackupFormatException("备份版本过新（v$version），请先升级应用")
        }

        var mangaCount = 0
        var chapterCount = 0
        var progressCount = 0
        var prefsCount = 0
        db.transaction {
            // 顺序即外键依赖：manga → chapter / progress；prefs 无依赖放最后
            for (el in root.arr("manga")) {
                val o = el.jsonObject
                val id = o.str("id") ?: continue
                val title = o.str("title") ?: "(无标题)"
                val source = o.str("source") ?: SourceType.KOMIIC.name
                val sourceMangaId = o.str("sourceMangaId") ?: id
                val coverUrl = o.str("coverUrl")
                val coverColorHex = o.str("coverColorHex")
                val author = o.str("author")
                val addedAt = o.long("addedAt") ?: 0L
                // 入架两步走（INSERT OR IGNORE + UPDATE），原因见 Manga.sq 里 insertManga 的注释。
                // 恢复备份时这本很可能**已经在书架里**：用 INSERT OR REPLACE 的话，那条
                // DELETE 会顺着 ON DELETE CASCADE 把本机已有的进度 / 已读 / 下载记录一起删掉。
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
                mangaCount++
            }
            for (el in root.arr("chapters")) {
                val o = el.jsonObject
                val id = o.str("id") ?: continue
                val mangaId = o.str("mangaId") ?: continue
                queries.insertChapter(
                    id = id,
                    mangaId = mangaId,
                    name = o.str("name") ?: "(未命名章节)",
                    chapterIndex = o.long("chapterIndex") ?: 0L,
                    isRead = if (o.bool("isRead") == true) 1L else 0L,
                    // 旧备份（#30 之前）没有这个字段，缺省 0 = 未知，读的时候再回填
                    pageCount = o.long("pageCount") ?: 0L,
                    // F-39「点过」：旧备份没有这个字段 → 0（不灰显）。已读的章节
                    // 下次打开阅读器会重新记点过，不用从备份里推。
                    lastOpenedAt = 0L
                )
                chapterCount++
            }
            for (el in root.arr("progress")) {
                val o = el.jsonObject
                val mangaId = o.str("mangaId") ?: continue
                val chapterId = o.str("chapterId") ?: continue
                val updatedAt = o.long("updatedAt") ?: 0L
                // 合并语义：本地进度更新就不被旧备份覆盖
                val existing = queries.selectProgressByManga(mangaId).executeAsOneOrNull()
                if (existing != null && existing.updatedAt >= updatedAt) continue
                queries.upsertProgress(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    page = o.long("page") ?: 0L,
                    totalPages = o.long("totalPages") ?: 0L,
                    updatedAt = updatedAt
                )
                progressCount++
            }
            for (el in root.arr("prefs")) {
                val o = el.jsonObject
                val key = o.str("key") ?: continue
                queries.upsertPref(key, o.str("value") ?: "")
                prefsCount++
            }
        }
        RestoreReport(mangaCount, chapterCount, progressCount, prefsCount)
    }

    // ---- WebDAV ----

    /**
     * 导出 → 上传到**固定文件名** [LATEST_BACKUP_NAME]（F-44 覆盖式备份）。
     *
     * 与旧实现的区别（用户 2026-10-25 要求）：
     * 1. **不再每次新建时间戳文件**——单槽位覆盖，备份目录不会越堆越多；
     * 2. **防反向备份**：上传前比较「本地数据最后修改时间」与「云端文件时间」，
     *    云端更新就**拒绝上传**（抛 [BackupRemoteNewerException]），避免用旧数据盖掉新数据；
     * 3. **覆盖前留一份上一版**：先把 `latest` 改名成 `prev` 再上传（服务器不支持 MOVE 就跳过，
     *    不让整个备份失败）；
     * 4. 老版本留下的 `gamanga_<时间戳>.zip` 仍按 [keep] 份清理。
     *
     * @param force 用户确认「就要用本地覆盖云端」时传 true，跳过第 2 条判断
     * @param localModifiedAt 本地数据最后修改时间（毫秒）；默认取 DB 文件与 WAL 的较大者，
     *   测试可注入固定值
     */
    suspend fun backupToWebDav(
        client: WebDavClient,
        keep: Int = DEFAULT_KEEP,
        force: Boolean = false,
        localModifiedAt: Long = defaultLocalModifiedAt(),
    ): WebDavBackupReport {
        val bytes = exportBackup()
        client.ensureDir(client.dir)
        val entries = runCatching { client.list() }.getOrDefault(emptyList())
        val remoteLatest = entries.firstOrNull { it.name == LATEST_BACKUP_NAME }
        val remoteAt = parseHttpDate(remoteLatest?.lastModified)

        if (backupDecision(localModifiedAt, remoteAt, force) == BackupDecision.RefuseRemoteNewer) {
            throw BackupRemoteNewerException(remoteAt ?: 0L, localModifiedAt)
        }

        // 覆盖前把上一版留成 prev：MOVE 不支持就跳过（宁可少一份历史，也别让备份失败）
        if (remoteLatest != null) {
            val moved = runCatching {
                client.move(
                    joinUrl(client.dir, LATEST_BACKUP_NAME),
                    joinUrl(client.dir, PREV_BACKUP_NAME),
                )
            }.getOrDefault(false)
            if (!moved) AppLog.w("WebDav", "服务器不支持 MOVE，本次不保留 prev 版本")
        }

        val ok = client.upload(joinUrl(client.dir, LATEST_BACKUP_NAME), bytes)
        if (!ok) throw BackupUploadException("上传被服务器拒绝（HTTP 非 2xx）")

        // 老版本的时间戳文件：仍按 keep 份保留，其余删掉（不碰 latest/prev）
        val legacy = entries.map { it.name }
            .filter { it.startsWith("gamanga_") && it.endsWith(".zip") }
        val deleted = ArrayList<String>()
        if (legacy.size > keep) {
            legacy.sortedDescending().drop(keep).forEach { old ->
                val removed = runCatching { client.delete(joinUrl(client.dir, old)) }.getOrDefault(false)
                if (removed) deleted += old
            }
        }
        AppLog.i(
            "WebDav",
            "覆盖式备份完成：${bytes.size} 字节 · 本地 ${localModifiedAt} · 云端旧 ${remoteAt ?: "无"}" +
                if (deleted.isEmpty()) "" else " · 清理旧备份 ${deleted.size} 份",
        )
        return WebDavBackupReport(LATEST_BACKUP_NAME, bytes.size.toLong(), deleted)
    }

    // ---- mihon 备份导入（F-03 起步，F-275 改成「预览 → 用户选择 → 导入」）----

    /**
     * 第一步：**只解析、不联网**，把备份整理成给用户看的预览行。
     *
     * 用户 2026-10-29 口径：「需要导入 mihon 备份后给用户选择还有改别称让用户自己修改的界面」。
     * 所以这里只回答三个问题：认不认得出源站、在不在书架、备份里有多少章 / 已读多少。
     */
    suspend fun previewMihonImport(bytes: ByteArray): MihonImportPreview = withContext(Dispatchers.IO) {
        val parsed = parseMihonBackup(bytes)
        val existingIds = queries.selectAllManga().executeAsList().map { it.id }.toSet()
        buildMihonPreview(parsed, existingIds)
    }

    /**
     * 按别称在可用源里搜候选（换源用）。
     *
     * 走 [SearchAll.searchWithFallback]：原词 0 条时按 `SearchKeywords` 退让（去装饰符 → 切段），
     * 正好覆盖用户说的「完全搜索不到的、或者只能用几个字部分搜索才能搜索到的」。
     * 结果按 [titleMatchScore] 降序，标题完全一致的最前面。
     */
    suspend fun searchMihonCandidates(
        alias: String,
        originalTitle: String = alias,
        types: List<SourceType> = Sources.all().map { it.type },
        limit: Int = 12,
    ): List<MihonCandidate> = withContext(Dispatchers.IO) {
        val kw = alias.trim()
        if (kw.isEmpty()) return@withContext emptyList()
        val out = ArrayList<MihonCandidate>()
        for (type in types) {
            val adapter = adapterResolver(type) ?: continue
            val hits = runCatching { SearchAll.searchWithFallback(adapter, kw) }.getOrDefault(emptyList())
            for (h in hits) {
                out += MihonCandidate(
                    source = type,
                    sourceId = h.sourceId,
                    title = h.title,
                    coverUrl = h.coverUrl,
                    author = h.author,
                    // 用**原始标题**打分（别称可能被用户改短了，短词对长标题永远只是「包含」）
                    score = titleMatchScore(originalTitle, h.title),
                )
            }
        }
        // R-179：按**位置加权**排序（开头命中 > 中间命中），同档再比原始标题分与长度
        out.sortedWith(
            compareByDescending<MihonCandidate> { titleMatchRank(kw, it.title) }
                .thenByDescending { it.score }
                .thenBy { it.title.length }
        ).take(limit)
    }

    /**
     * 流式版换源搜索（R-166，2026-10-29 用户：「搜索速度太慢，搜索到的优先发出来」）。
     *
     * 与 [searchMihonCandidates] 的差别只有两点，但决定体感：
     * 1. **并发**：五个源同时打，谁先回来先发（原来是 for 循环串行，总耗时 = 最慢那个源）；
     * 2. **流式**：每搜到一条就 `emit`，界面边收边显示，不用等全部结束。
     *
     * 单源失败/超时只丢这个源（[withTimeoutOrNull] 15s 兜底：拷贝漫画 DOM 兜底能跑到 20s，
     * 不让它把整次搜索拖住）。
     */
    fun searchMihonCandidatesStreaming(
        alias: String,
        originalTitle: String = alias,
        types: List<SourceType> = Sources.all().map { it.type },
        perSourceTimeoutMs: Long = 15_000,
    ): Flow<MihonCandidate> = channelFlow {
        val kw = alias.trim()
        if (kw.isEmpty()) return@channelFlow
        val targets = types.mapNotNull { t -> adapterResolver(t)?.let { t to it } }
        coroutineScope {
            targets.forEach { (type, adapter) ->
                launch(Dispatchers.IO) {
                    val hits = withTimeoutOrNull(perSourceTimeoutMs) {
                        runCatching { SearchAll.searchWithFallback(adapter, kw) }.getOrDefault(emptyList())
                    } ?: emptyList()
                    for (h in hits) {
                        // 用**原始标题**打分（别称可能被改短，短词对长标题永远只是「包含」）
                        send(
                            MihonCandidate(
                                source = type,
                                sourceId = h.sourceId,
                                title = h.title,
                                coverUrl = h.coverUrl,
                                author = h.author,
                                score = titleMatchScore(originalTitle, h.title),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 第二步：按用户的选择导入。
     *
     * 每条选择：[MihonImportChoice.source] 有值就直接用那一本；为空则拿别称去搜，
     * 取打分最高的候选（用户在界面里没手动挑时才走这条）。
     *
     * 2026-10-30 三条返工（R-169 / R-171 / R-172）：
     * - **名字与封面以新源为准**（[MihonImportChoice.pickedTitle] / [pickedCover]）——
     *   用户报「换源后名字还是原来的名字，书架封面出不来」；
     * - **导入范围可选**（[mode]）：只恢复书架 / 书架 + 观看历史。范围只决定**列哪些书**，
     *   两档都回填已读与阅读进度（R-178 用户更正）；
     * - **逐步回报进度**（[onProgress]）：导入一部可能要拉一次章节列表，十几部就是十几秒，
     *   界面必须知道「正在导第几本」。
     */
    suspend fun importMihonSelection(
        bytes: ByteArray,
        choices: List<MihonImportChoice>,
        mode: MihonImportMode = MihonImportMode.SHELF_ONLY,
        onProgress: (MihonImportProgress) -> Unit = {},
    ): MihonImportReport = withContext(Dispatchers.IO) {
        val parsed = parseMihonBackup(bytes)
        val byUrl = parsed.manga.associateBy { it.url }
        val existingIds = queries.selectAllManga().executeAsList().map { it.id }.toSet()
        var mangaImported = 0
        var chaptersImported = 0
        var historyRestored = 0
        var coversRestored = 0
        var done = 0
        val skipped = ArrayList<SkippedManga>()

        for (choice in choices) {
            // 用户点「取消导入」时立刻停手（R-193）：每部开头检查一次，已导入的保留。
            currentCoroutineContext().ensureActive()
            val m = byUrl[choice.url]
            done++
            // **进度回报绝不能打断导入**（R-193，2026-10-30）。
            // 原来这里直接调 `onProgress(...)`：界面侧用 `scope.launch(Dispatchers.Main)`
            // 切线程，而桌面端没有 Main dispatcher 时 `launch` **同步抛异常**——
            // 异常从回调冒到本循环，整批导入在第一本之前就中断（用户看到「恢复后书架
            // 还是空的、按钮点了没反应」）。进度只是 UI 附属品，出错就该静默跳过。
            if (m != null) {
                runCatching {
                    onProgress(MihonImportProgress(done = done, total = choices.size, title = m.title))
                }
            }
            if (m == null) {
                skipped += SkippedManga(choice.alias, "备份里找不到这一部（可能已被移除）")
                continue
            }

            var type = choice.source
            var sourceId = choice.sourceId
            if (type == null || sourceId == null) {
                mihonUrlToSource(m.url)?.let { mapping ->
                    type = mapping.first
                    sourceId = mapping.second
                }
            }
            var effective = choice
            if (type == null || sourceId == null) {
                val best = runCatching { searchMihonCandidates(choice.alias, m.title) }
                    .getOrDefault(emptyList())
                    .firstOrNull()
                if (best == null) {
                    skipped += SkippedManga(m.title, "按别称「${choice.alias}」没搜到可用的源")
                    continue
                }
                type = best.source
                sourceId = best.sourceId
                // 自动挑的那本也要用它的标题/封面（否则还是备份里的旧名字 + 失效封面）
                effective = choice.copy(
                    source = type,
                    sourceId = sourceId,
                    pickedTitle = choice.pickedTitle ?: best.title,
                    pickedCover = choice.pickedCover ?: best.coverUrl,
                )
            }
            val result = importOneMihon(m, type, sourceId, existingIds, effective)
            when (result) {
                is OneResult.Ok -> {
                    mangaImported += result.newManga
                    chaptersImported += result.newChapters
                    historyRestored += result.history
                    if (result.coverRestored) coversRestored++
                }
                is OneResult.Skip -> skipped += SkippedManga(m.title, result.reason)
            }
        }
        MihonImportReport(
            mangaImported = mangaImported,
            chaptersImported = chaptersImported,
            historyRestored = historyRestored,
            skipped = skipped,
            coversRestored = coversRestored,
            mode = mode,
        )
    }

    /**
     * 老入口（一次性全导，认得出源的全部导入）。保留给旧调用方与测试；
     * 新界面走 [previewMihonImport] + [importMihonSelection]。
     */
    suspend fun importMihonBackup(bytes: ByteArray): MihonImportReport = withContext(Dispatchers.IO) {
        val parsed = parseMihonBackup(bytes)
        val choices = parsed.manga.mapNotNull { m ->
            val mapping = mihonUrlToSource(m.url) ?: return@mapNotNull null
            MihonImportChoice(m.url, defaultSearchAlias(m.title), mapping.first, mapping.second)
        }
        importMihonSelection(bytes, choices, MihonImportMode.SHELF_AND_HISTORY)
    }

    private sealed interface OneResult {
        data class Ok(
            val newManga: Int,
            val newChapters: Int,
            val history: Int,
            val coverRestored: Boolean = false,
        ) : OneResult

        data class Skip(val reason: String) : OneResult
    }

    /**
     * 导入单部：书不在书架才入架（拉源站章节），然后按**章节名**回填已读 / 进度。
     *
     * 单部失败（网络/解析）只跳过这一部，不拖垮整批。
     *
     * 名字/封面取值顺序（R-169）：**用户挑中的新源那本** → 备份里的标题/缩略图。
     * 备份里若自带封面图字节（R-170），入架后直接写成自定义封面，连源站封面都不用等。
     */
    private suspend fun importOneMihon(
        m: MihonManga,
        type: SourceType,
        sourceId: String,
        existingIds: Set<String>,
        choice: MihonImportChoice,
    ): OneResult {
        val adapter = adapterResolver(type) ?: return OneResult.Skip("本地未接入该源")
        val mangaId = mangaIdOf(type, sourceId)
        return try {
            var newManga = 0
            var newChapters = 0
            var coverRestored = false
            val localChapters = if (existingIds.contains(mangaId)) repository.chaptersOf(mangaId) else emptyList()
            if (localChapters.isEmpty()) {
                val fetched = adapter.chapters(sourceId)
                if (fetched.isEmpty()) return OneResult.Skip("源站返回空章节列表")
                repository.importFromSource(
                    type = type,
                    manga = SourceManga(
                        sourceId = sourceId,
                        // 名字用**新源**的（用户：导入后的名字应该为替换源的名字）
                        title = choice.pickedTitle ?: m.title,
                        // 封面同理：优先新源封面，其次备份缩略图
                        coverUrl = choice.pickedCover ?: m.thumbnailUrl,
                        author = m.author,
                    ),
                    chapters = fetched,
                    addedAt = if (m.dateAdded > 0) m.dateAdded else System.currentTimeMillis(),
                )
                newManga = 1
                newChapters = fetched.size
            }
            // 备份自带封面图 → 原封不动用这张（覆盖源站封面）
            m.coverBytes?.let { bytes ->
                if (CustomCoverStore.setFromBytes(mangaId, bytes)) coverRestored = true
            }

            val chapters = repository.chaptersOf(mangaId)
            val names = chapters.map { it.name }
            var history = 0
            for (mc in m.chapters) {
                val idx = matchMihonChapterIndex(mc.chapterNumber, mc.name, names)
                if (idx < 0) continue
                val ch = chapters[idx]
                var wrote = false
                if (mc.read && !ch.read) {
                    queries.markChapterRead(1L, ch.id)
                    wrote = true
                }
                // R-178（2026-10-30 用户最终口径）：「只选择书架的书恢复**同时恢复阅读记录**」。
                // 两档范围都写进度——上一版「只恢复书架就不写进度」把书架里的书读到哪里全丢了。
                // 范围只决定**列哪些书**（见 `MihonImportMode.includes`），不再影响写什么。
                if (mc.lastPageRead > 0L) {
                    // totalPages=0：mihon 不记总页数；阅读器只用 chapterId 定位，无副作用
                    queries.upsertProgress(mangaId, ch.id, mc.lastPageRead, 0L, System.currentTimeMillis())
                    wrote = true
                }
                if (wrote) history++
            }
            OneResult.Ok(newManga, newChapters, history, coverRestored)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户点「取消导入」→ 必须原样往上抛，不能被下面的通用 catch 吞成
            // 「章节获取失败：StandaloneCoroutine was cancelled」（R-193）
            throw e
        } catch (e: Exception) {
            OneResult.Skip("章节获取失败：${e.message?.take(60) ?: e::class.simpleName}")
        }
    }

    // ---- 内部工具 ----

    private fun zipSingleEntry(name: String, content: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /** 取 ZIP 里第一个 `.json` 条目；没有返回 null（不抛——由调用方判"不是我们的备份"）。 */
    private fun readSingleJson(zip: ByteArray): String? {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json")) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.arr(key: String): List<JsonElement> =
        this[key]?.jsonArray ?: emptyList()

    companion object {
        const val FORMAT_NAME = "gamanga-backup"
        const val FORMAT_VERSION = 1L

        /** ZIP 内的 JSON 条目名。 */
        const val ENTRY_NAME = "backup.json"

        /** 云端最多保留几份（对齐 mihon 的 4 份自动备份）。 */
        const val DEFAULT_KEEP = 4

        /** 覆盖式备份的固定文件名（F-44）。 */
        const val LATEST_BACKUP_NAME = "gamanga-latest.zip"

        /** 覆盖前保留的上一版。 */
        const val PREV_BACKUP_NAME = "gamanga-prev.zip"
    }
}

data class RestoreReport(
    val mangaCount: Int,
    val chapterCount: Int,
    val progressCount: Int,
    val prefsCount: Int
)

data class WebDavBackupReport(
    val fileName: String,
    val sizeBytes: Long,
    /** 因超出保留份数被删掉的旧备份名。 */
    val deleted: List<String>
)

data class MihonImportReport(
    val mangaImported: Int,
    val chaptersImported: Int,
    /** 回填了观看历史的章节数（已读/进度）。 */
    val historyRestored: Int,
    val skipped: List<SkippedManga>,
    /** 用备份里自带图片还原的自定义封面数（R-170）。 */
    val coversRestored: Int = 0,
    /** 本次的导入范围（报告里要写清「只导了书架」还是「书架 + 历史」）。 */
    val mode: MihonImportMode = MihonImportMode.SHELF_ONLY,
)

data class SkippedManga(val title: String, val reason: String)

class BackupFormatException(message: String) : RuntimeException(message)

class BackupUploadException(message: String) : RuntimeException(message)

/** 覆盖式备份的判定结果（F-44）。 */
enum class BackupDecision {
    /** 可以上传（覆盖云端）。 */
    Overwrite,

    /** 云端比本地新：拒绝上传，避免「反向备份」。 */
    RefuseRemoteNewer,
}

/**
 * 该不该用本地覆盖云端（F-44 纯函数，方便单测）。
 *
 * 规则：
 * - `force` → 一律覆盖（用户已确认）；
 * - 云端没有备份 → 直接上传；
 * - 云端时间 **晚于** 本地最后修改时间 + [toleranceMs] → 拒绝（云端更新）。
 *
 * 为什么要容差：WebDAV 的 Last-Modified 只有秒级精度，且本机与服务器时钟有偏差；
 * 不留容差会在「刚备份完马上再备份」时误判成「云端更新」。
 */
internal fun backupDecision(
    localModifiedAt: Long,
    remoteModifiedAt: Long?,
    force: Boolean,
    toleranceMs: Long = 60_000L,
): BackupDecision = when {
    force -> BackupDecision.Overwrite
    remoteModifiedAt == null -> BackupDecision.Overwrite
    remoteModifiedAt > localModifiedAt + toleranceMs -> BackupDecision.RefuseRemoteNewer
    else -> BackupDecision.Overwrite
}

/**
 * 解析 WebDAV 返回的时间。标准是 RFC 1123（`Wed, 21 Oct 2015 07:28:00 GMT`），
 * 少数服务器给 ISO-8601；两种都试，解析不了返回 null（当作「没有云端时间」，不阻断备份）。
 */
internal fun parseHttpDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.recoverCatching {
        Instant.parse(raw.trim()).toEpochMilli()
    }.getOrNull()
}

/**
 * 本地数据「最后修改时间」：取数据库文件与 `-wal` 的较大者。
 *
 * 为什么看 WAL：SQLDelight 走 WAL 模式，写操作先进 `gamanga.db-wal`，主库文件的 mtime
 * 可能滞后很久——只看主库会把「刚读完一章」判成「本地很旧」，从而误拒上传。
 */
internal fun defaultLocalModifiedAt(): Long {
    val dir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Gamanga")
    val db = File(dir, "gamanga.db")
    val wal = File(dir, "gamanga.db-wal")
    return maxOf(db.lastModified(), wal.lastModified())
}

/** 云端比本地新，拒绝覆盖（F-44「反向备份」防护）。 */
class BackupRemoteNewerException(
    val remoteModifiedAt: Long,
    val localModifiedAt: Long,
) : RuntimeException(
    "云端备份比本地新（云端 ${fmtBackupTime(remoteModifiedAt)} · 本地 ${fmtBackupTime(localModifiedAt)}），" +
        "已跳过上传以免覆盖更新的数据；确需覆盖请点「强制覆盖」"
)

private fun fmtBackupTime(millis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
