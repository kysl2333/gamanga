package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.db.DownloadChapter
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.db.SelectDownloadsWithTitle
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.source.isGoneError
import app.gamanga.desktop.source.isQuotaError
import app.gamanga.desktop.source.requestHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/**
 * 下载任务状态。
 *
 * QUEUED 排队中 · DOWNLOADING 下载中 · COMPLETED 已完成（图片字节齐了）
 * FAILED 失败（可重试）。取消 = 直接删行（[DownloadManager.delete]），不设 CANCELLED。
 */
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

/**
 * 下载失败的上下文化异常：消息里带阶段/页码，下载管理页直接展示。
 *
 * 旧实现把裸异常消息写进 error 列——「下载接近完成时却提示失败」的用户只看到
 * 一句 `HTTP 502`，既不知道是哪一页也不知道还能不能重试。现在失败信息形如
 * 「第 12/30 页下载失败（重试 3 次仍失败）：HTTP 502」。
 */
class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 单页下载失败后的重试次数（配额类错误不重试，见 [isQuotaError]）。 */
private const val PAGE_FETCH_ATTEMPTS = 3

/** 单页重试的退避基数（ms）：第 n 次重试前等 BASE*n——网络抖动大概率秒恢复。 */
private const val PAGE_RETRY_BACKOFF_MS = 400L

/** 下载速率采样窗（ms）：[DownloadManager.speedBps] 按这个窗口平均。 */
private const val SPEED_WINDOW_MS = 3_000L

/**
 * 下载缓存目录的「归属标记」文件名（第八轮 #7）。
 *
 * 批量清理（[DownloadManager.clearAllCache] / 孤儿清理）动手前先查这个文件：
 * 有 = 这个目录确实是 Gamanga 的下载缓存；没有 = 用户 pref 指错了地方，
 * 拒绝删除。杜绝「把系统或其他不相关文件删掉」的最坏情况。
 */
private const val MARKER_NAME = ".gamanga-downloads"

/**
 * 一个章节的下载任务（UI 直接消费）。
 *
 * [mangaTitle] 不进库（join manga 拿），重启后由 [selectDownloadsWithTitle] 补回。
 * [createdAt] 入队时间，永不变——下载列表按它稳定排序（[updatedAt] 每页进度
 * 都改，按它排任务会在列表里来回跳）。
 */
data class DownloadTask(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val chapterName: String,
    val mangaTitle: String = "",
    val pageCount: Int = 0,
    val downloadedPages: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val totalBytes: Long = 0,
    val updatedAt: Long = 0,
    val createdAt: Long = 0,
) {
    /** 0f~1f；总页数未知（取图列表还没回来）时返回 0f。 */
    val progress: Float
        get() = if (pageCount > 0) (downloadedPages.toFloat() / pageCount).coerceIn(0f, 1f) else 0f
}

/** 一本漫画的下载缓存占用（设置页缓存管理用）。 */
data class CacheUsage(val mangaId: String, val mangaTitle: String, val bytes: Long)

/**
 * 下载管理器（R-04）。
 *
 * **落盘布局**：`<rootDir>/<sanitize(mangaId)>/<sanitize(chapterId)>/<页序>.<ext>`，
 * 页序四位零填充（`0000.jpg`）——按文件名排序即阅读顺序，扩展名不影响解码
 * （Skia 按文件头魔术数字识别格式）。
 *
 * **并发模型**：章节间最多 [maxParallelChapters] 个并行（运行时可调，
 * 见 [setMaxParallelChapters]），章节内每页最多 [maxParallelPages] 个并行。
 * 取图片列表（`adapter.pages`）无法并行加速——Komiic 一次调用烧一份配额、
 * G社 一次拉起隐藏浏览器，能并发的只有图片字节下载。
 *
 * **DB 是真相**：状态/进度/字节数都写库；内存 [tasks] 只是 UI 镜像。
 * 进程被杀时进行中的任务停在 DOWNLOADING，下次启动 [resumeInterrupted]
 * 把它们退回队列重跑（章节目录会先清空再下，不混入上一次的残页）。
 *
 * **取消语义**：所有 suspend 调用必须让 CancellationException 穿透——
 * 吞掉它会重演 R-45「僵尸协程把 error 写回已不属于它的任务」。
 */
class DownloadManager(
    private val db: GamangaDatabase,
    private var rootDir: Path,
    private val libraryRepository: LibraryRepository? = null,
    private var maxParallelChapters: Int = 2,
    private val maxParallelPages: Int = 4,
    private val pagesOf: suspend (SourceType, String) -> List<PageImage> = { type, chapterId ->
        Sources.of(type)?.pages(chapterId) ?: emptyList()
    },
    private val fetchPage: suspend (PageImage, SourceType?) -> ByteArray = { page, type ->
        Http.getBytes(
            page.url,
            page.requestHeaders(),
            sourceType = type
        )
    },
    private val copyDirectory: (Path, Path) -> Unit = { source, target ->
        check(source.toFile().copyRecursively(target.toFile(), overwrite = false)) { "复制失败：$source" }
    },
) {

    private val queries get() = db.downloadQueries
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 库访问锁（T-04，2026-10-26）。
     *
     * 章节内是 [maxParallelPages] 个协程并发下载，每下完一页都会 `update()` 一次；
     * 而 `JdbcSqliteDriver` **复用同一个 JDBC 连接**。多线程同时往一个 SQLite
     * 连接写/读，轻则 `SQLITE_BUSY`，重则互相等待卡住——用户侧表现是「下载进度
     * 卡住不动」，测试侧就是偶发干等 20s 超时（`progress is monotonic` 那条）。
     * 单次读写只有几十微秒，串行化代价远小于风险。
     */
    private val dbLock = Any()

    /** 任务镜像：chapterLocalId → task。UI collectAsState 它。 */
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    /** 在飞的章节协程（取消/删除用）。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 待下载章节 id 队列（[pump] 消费）。 */
    private val queue = ArrayDeque<String>()

    /**
     * 全局暂停标志（第八轮 #5「全部暂停」）。
     *
     * 只停「启动新章节」：在飞任务照常跑完（取消在飞任务会丢半章进度，
     * 与暂停语义不符——暂停是为了限流/让网速，不是放弃进度）。
     * [pump] 见此标志直接返回；[resumeAll] 清零并补位。
     */
    @Volatile
    private var paused = false

    /**
     * 下载速率采样窗（第八轮 #5「每秒下载速度」）。
     * 每写完一页记一条 (时间戳 ms, 字节)；speedBps 按最近 3 秒平均。
     */
    private val rateWindow = ArrayDeque<Pair<Long, Long>>()

    /** 最近 [SPEED_WINDOW_MS] 的平均下载速度（字节/秒）；0 = 没有活跃下载。 */
    fun speedBps(): Long = synchronized(rateWindow) {
        val now = System.currentTimeMillis()
        rateWindow.removeAll { now - it.first > SPEED_WINDOW_MS }
        if (rateWindow.isEmpty()) 0L
        else rateWindow.sumOf { it.second } * 1000L / SPEED_WINDOW_MS
    }

    private fun recordRate(bytes: Long) = synchronized(rateWindow) {
        rateWindow.addLast(System.currentTimeMillis() to bytes)
        val cutoff = System.currentTimeMillis() - SPEED_WINDOW_MS
        while (rateWindow.isNotEmpty() && rateWindow.first().first < cutoff) {
            rateWindow.removeFirst()
        }
    }

    init {
        reloadFromDb()
        ensureMarker()
        // 孤儿清理的判据是「**库里还有没有这一行 manga**」，不是「它在不在书架上」（R-136）。
        //
        // 上一版订阅的是 `libraryItems`（只含 `inLibrary = 1`）→ 用户把漫画「移出书架」
        // （软移出，只翻标志位，行还在）之后，这本书立刻被判成孤儿：
        // 下载行删掉、磁盘目录 `deleteRecursively()`、内存镜像清空 —— 用户看到的就是
        // 「移除书架后下载页这本漫画完全消失了」。而下载缓存按用户口径是**该留的**
        // （「清除所有数据」都要保留下载缓存，更别说只是移出书架）。
        //
        // 换成 `allMangaIds` 后语义正确：
        //  - 软移出（`hideFromLibrary`）：manga 行还在 → 下载保留 ✓
        //  - 真删（`removeFromLibrary` / 多选删除）：manga 行没了，`downloadChapter` 已被
        //    外键级联删掉，磁盘目录按孤儿清 ✓（这条路径本来就是对的）
        libraryRepository?.let { repo ->
            scope.launch {
                // null = 还没读到库，别动手（否则第一次 collect 的空集合会把缓存全清）
                repo.allMangaIds.collect { ids -> if (ids != null) cleanupOrphans(ids) }
            }
        }
    }

    /**
     * 确保缓存根目录有归属标记（第八轮 #7）。
     *
     * 批量清理（全部清理 / 孤儿清理）动手前先查这个文件——没有标记的目录
     * 「不是我们的缓存目录」，拒绝删除。默认位置和 relocate 的新位置都会建。
     */
    private fun ensureMarker() {
        runCatching {
            Files.createDirectories(rootDir)
            val marker = rootDir.resolve(MARKER_NAME).toFile()
            if (!marker.exists()) marker.writeText("Gamanga downloads cache root\n")
        }.onFailure { AppLog.w("Download", "ensureMarker failed at $rootDir: ${it.message}") }
    }

    // ---- 对外 API ----

    /** 某章是否已完整落盘（阅读器据此跳过 adapter.pages，不烧配额/不拉浏览器）。 */
    fun isCompleted(chapterLocalId: String): Boolean = localPages(chapterLocalId).isNotEmpty()

    /**
     * 已下载章节的本地页面列表（阅读器用）。
     *
     * 未完成、缺页或空文件返回空列表，阅读器沿既有分支回源，避免压缩页序。
     */
    fun localPages(chapterLocalId: String): List<PageImage> {
        val row = queries.selectDownloadById(chapterLocalId).executeAsOneOrNull() ?: return emptyList()
        if (row.status != DownloadStatus.COMPLETED.name) return emptyList()
        if (row.pageCount <= 0 || row.pageCount > Int.MAX_VALUE) return emptyList()
        val files = chapterDir(row.mangaId, row.chapterId).toFile().listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.nameWithoutExtension.toIntOrNull() != null }
            ?.sortedBy { it.nameWithoutExtension.toInt() }
            ?: return emptyList()
        if (files.size != row.pageCount.toInt() || files.withIndex().any { (index, file) ->
                file.nameWithoutExtension.toInt() != index
            }) return emptyList()
        // 必须走 Path.toUri()：File.toURI() 在 Windows 上产出 `file:/D:/...`
        // （单斜杠），ImageCache 的 file:// 前缀匹配会漏。
        return files.map { PageImage(url = it.toPath().toUri().toString()) }
    }

    /**
     * 加入下载队列（幂等）。
     *
     * 已完成 / 在队列中 / 正在下载的章节跳过；FAILED 的重新入队（重试语义）。
     * 调用方需保证 manga 已在书架（FK）——详情页「下载」对未入架漫画会先导入。
     */
    fun enqueue(mangaId: String, chapters: List<SourceChapter>, mangaTitle: String = "") {
        // 入队序号：取「当前时间」与「库里最大值」的较大者再递增——保证严格
        // 递增。同一次 enqueue 的多章若共用同一毫秒时间戳，ORDER BY createdAt
        // 会并列，并列行顺序不定，列表又会抖（第七轮 #2 要的是"永不稳定"）。
        var seq = maxOf(
            System.currentTimeMillis(),
            queries.selectAllDownloads().executeAsList().maxOfOrNull { it.createdAt } ?: 0L
        )
        val added = mutableListOf<String>()
        chapters.forEach { ch ->
            val id = chapterLocalId(mangaId, ch.id)
            val existing = queries.selectDownloadById(id).executeAsOneOrNull()
            if (existing != null && existing.status != DownloadStatus.FAILED.name) return@forEach
            val task = DownloadTask(
                id = id,
                mangaId = mangaId,
                chapterId = ch.id,
                chapterName = ch.name,
                mangaTitle = mangaTitle,
                pageCount = existing?.pageCount?.toInt() ?: ch.pageCount,
                downloadedPages = 0,
                status = DownloadStatus.QUEUED,
                error = null,
                // 入队时间：新任务取递增序号；FAILED 重试保留原值——任务在列表里
                // 停在原地，不因为重试就跳到队尾（用户要求顺序稳定）。
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: (++seq),
            )
            val now = System.currentTimeMillis()
            persist(task, now)
            _tasks.update { it + (id to task.copy(updatedAt = now)) }
            added += id
        }
        if (added.isEmpty()) return
        synchronized(queue) { added.forEach { queue.addLast(it) } }
        AppLog.i("Download", "enqueue ${added.size} chapter(s) for $mangaId (${mangaTitle.ifBlank { "?" }})")
        pump()
    }

    /**
     * 取消并删除一个下载（含磁盘文件与 DB 行）。
     *
     * **顺序有讲究**：先删行再取消协程。协程每写一页、每次落进度前都查行——
     * 行没了就停手自清（见 [downloadChapter]），不会把状态/文件写回一个
     * 用户刚删掉的任务（僵尸复活）。
     */
    fun delete(chapterLocalId: String) {
        val row = queries.selectDownloadById(chapterLocalId).executeAsOneOrNull()
        jobs.remove(chapterLocalId)?.cancel()
        synchronized(queue) { queue.remove(chapterLocalId) }
        queries.deleteDownload(chapterLocalId)
        row?.let { chapterDir(it.mangaId, it.chapterId).toFile().deleteRecursively() }
        _tasks.update { it - chapterLocalId }
        AppLog.i("Download", "delete $chapterLocalId")
    }

    /** 失败任务重试：清错误与进度，重新入队。 */
    fun retry(chapterLocalId: String) {
        val row = queries.selectDownloadById(chapterLocalId).executeAsOneOrNull() ?: return
        val task = row.toTask().copy(
            status = DownloadStatus.QUEUED,
            downloadedPages = 0,
            error = null,
        )
        update(task)
        synchronized(queue) { if (chapterLocalId !in queue) queue.addLast(chapterLocalId) }
        AppLog.i("Download", "retry $chapterLocalId ('${task.chapterName}')")
        pump()
    }

    /** 整本书的下载一起删（移出书架时由孤儿清理间接走到，也可直接调）。 */
    fun deleteMangaDownloads(mangaId: String) {
        val ids = queries.selectAllDownloads().executeAsList()
            .filter { it.mangaId == mangaId }
            .map { it.id }
        ids.forEach { delete(it) }
    }

    /**
     * 启动恢复：上次进程被杀时停在 DOWNLOADING 的任务退回队列重跑。
     *
     * 必须在 `Sources.installCookieProvider` 之后调（取图要借登录态）。
     * 只恢复未完成的任务；已完成的图片字节本来就在磁盘上，无需处理。
     */
    fun resumeInterrupted() {
        val unfinished = queries.selectUnfinishedDownloads().executeAsList()
        unfinished.forEach { row ->
            if (row.status == DownloadStatus.DOWNLOADING.name) {
                update(row.toTask().copy(status = DownloadStatus.QUEUED, downloadedPages = 0, error = null))
            }
        }
        if (unfinished.isEmpty()) return
        synchronized(queue) { unfinished.forEach { queue.addLast(it.id) } }
        pump()
    }

    // ---- 并发数设置（下载管理页，第七轮 #2）----

    /**
     * 调整章节间并发数（1~8）。其余章节继续排队，不打断在飞任务。
     *
     * 推荐值 2：Komiic 每章取图烧一份每日配额、G社 每章拉一次隐藏浏览器，
     * 章节级并发再高也只是让取图阶段互相排队；真正能并行的只有章内图片字节
     * 下载（固定 4 路）。调大只在「多本书同时下、源站不限流」时有意义。
     */
    fun setMaxParallelChapters(value: Int) {
        val next = value.coerceIn(1, 8)
        synchronized(queue) { maxParallelChapters = next }
        if (next > 0) pump() // 调大立刻补位启动排队中的章节
    }

    fun maxParallelChapters(): Int = synchronized(queue) { maxParallelChapters }

    // ---- 下载缓存管理（设置页，第七轮 #7）----

    /**
     * 每本漫画占用的缓存字节（按磁盘实际大小算，不等 DB 的 totalBytes——
     * 失败/中断留下的残页也在盘上，清理要以实际占用为准）。
     */
    fun cacheUsage(): List<CacheUsage> =
        queries.selectDownloadsWithTitle().executeAsList()
            .groupBy { it.mangaId }
            .map { (mangaId, list) ->
                val bytes = list.sumOf { row -> dirSize(chapterDir(row.mangaId, row.chapterId)) }
                CacheUsage(mangaId = mangaId, mangaTitle = list.first().mangaTitle, bytes = bytes)
            }
            .sortedByDescending { it.bytes }

    /** 全部下载缓存的总字节。 */
    fun totalCacheBytes(): Long = cacheUsage().sumOf { it.bytes }

    /**
     * 清空全部下载缓存（行 + 磁盘文件 + 在飞任务）。
     *
     * **安全边界（第八轮 #7，用户明确要求「不要把系统或其他不相关文件删掉」）**：
     * 旧实现 `rootDir.listFiles().forEach { deleteRecursively() }` 会删掉缓存根目录下的
     * **一切**——用户把缓存位置指到自己的某个目录（里面还有文档/照片）时就遭殃。
     * 现在双保险：①根目录必须带归属标记（[MARKER_NAME]），否则拒绝执行；
     * ②只删「DB 里登记过的漫画目录」白名单，根目录下的其他文件/目录一律不碰。
     *
     * @return false = 该目录没有归属标记（不是 Gamanga 缓存目录），未删任何东西。
     */
    fun clearAllCache(): Boolean {
        if (!rootDir.resolve(MARKER_NAME).toFile().exists()) {
            AppLog.w("Download", "clearAllCache rejected: no marker in ${rootDir.toAbsolutePath()}")
            return false
        }
        // 先停在飞任务：协程每写一页前都查行，行没了它们会自停自清
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        synchronized(queue) { queue.clear() }
        val rows = queries.selectAllDownloads().executeAsList()
        rows.forEach { queries.deleteDownload(it.id) }
        // 白名单删除：只删 DB 登记过的漫画目录（sanitize 规则与建目录时一致）。
        // 并发删（一部一协程）：几千个小文件在 Windows 上串行删很慢——
        // 用户实测「全部清理转圈挺久」（第九轮 #7）。
        val names = rows.map { sanitizePathSegment(it.mangaId) }.toSet()
        runBlocking {
            names.map { name ->
                async(Dispatchers.IO) { rootDir.resolve(name).toFile().deleteRecursively() }
            }.awaitAll()
        }
        _tasks.value = emptyMap()
        AppLog.i("Download", "clearAllCache: ${rows.size} row(s), dirs=${rows.map { it.mangaId }.distinct()}")
        return true
    }

    /**
     * 更换下载缓存位置：把现有文件搬到新目录再切换 [rootDir]。
     *
     * **安全边界（第八轮 #7）**：
     * 1. 拒绝危险目标（系统盘根 / Windows / Program Files / 用户主目录及其
     *    Desktop·Documents·Downloads 等 shell 文件夹 / Gamanga 数据根本身）——
     *    选这些位置等于让下载缓存和系统/个人文件混住，拒绝并提示。
     * 2. 只复制 DB 登记过的漫画目录（白名单），不把目标目录里的其他文件卷进来；
     *    旧目录同样只删白名单目录。
     * 3. 搬运期间在飞任务可能往旧目录写文件——先取消等它们停（协程自清旧目录），
     *    搬完再重新入队未完成任务（[resumeInterrupted]）。
     *
     * @return null = 成功；非 null = 失败原因（调用方直接展示，位置不变）。
     */
    @Synchronized
    fun relocate(newRoot: Path): String? {
        val oldPath = rootDir.toFile().canonicalFile.toPath()
        val newPath = newRoot.toFile().canonicalFile.toPath()
        if (newPath == oldPath) return null
        if (newPath.startsWith(oldPath) || oldPath.startsWith(newPath)) {
            return "更改失败：新旧缓存目录不能互相包含"
        }
        if (newRoot.isDangerousDownloadRoot()) {
            val msg = "已拒绝：${newRoot.toAbsolutePath()} 是系统/个人目录，不能作为下载缓存位置"
            AppLog.w("Download", "relocate rejected: ${newRoot.toAbsolutePath()}")
            return msg
        }
        val whitelist = queries.selectAllDownloads().executeAsList()
            .map { sanitizePathSegment(it.mangaId) }.toSet()
        if (whitelist.any { Files.exists(newRoot.resolve(it)) }) {
            return "更改失败：目标存在同名文件或漫画目录，请选择空目录"
        }
        val wasPaused = paused
        paused = true
        val activeJobs = synchronized(queue) {
            queue.clear()
            jobs.values.toList().also { list -> list.forEach { it.cancel() } }
        }
        runBlocking { activeJobs.forEach { it.join() } }
        jobs.clear()
        var staging: Path? = null
        val published = mutableListOf<Path>()
        var copied = false
        try {
            Files.createDirectories(newRoot)
            val stage = Files.createTempDirectory(newRoot, ".gamanga-move-")
            staging = stage
            whitelist.forEach { name ->
                val src = rootDir.resolve(name).toFile()
                if (src.isDirectory) {
                    copyDirectory(src.toPath(), stage.resolve(name))
                }
            }
            whitelist.forEach { name ->
                val source = stage.resolve(name)
                if (Files.exists(source)) {
                    val target = newRoot.resolve(name)
                    Files.move(source, target)
                    published.add(target)
                }
            }
            val marker = newRoot.resolve(MARKER_NAME)
            if (!Files.exists(marker)) Files.writeString(marker, "Gamanga downloads cache root\n")
            copied = true
        } catch (e: Exception) {
            published.forEach { it.toFile().deleteRecursively() }
            AppLog.e("Download", "relocate copy failed to ${newRoot.toAbsolutePath()}", e)
            return "更改失败：目标磁盘空间不足或没有写入权限"
        } finally {
            staging?.toFile()?.deleteRecursively()
            // 切根前仍保持暂停，避免恢复的协程在旧目录抢写。
            if (!copied) {
                paused = wasPaused
                if (!wasPaused) resumeInterrupted()
            }
        }
        // 搬运成功：清空旧目录白名单部分、切换根目录、恢复被中断的下载
        whitelist.forEach { name -> rootDir.resolve(name).toFile().deleteRecursively() }
        rootDir = newRoot
        ensureMarker() // 新目录同样打上归属标记，后续清理才敢动手
        paused = wasPaused
        if (!wasPaused) resumeInterrupted()
        AppLog.i("Download", "relocated root to ${newRoot.toAbsolutePath()} (${whitelist.size} manga dir(s))")
        return null
    }

    /**
     * 是否「不适合作为下载缓存根目录」。
     *
     * 判据：系统盘根 / Windows / Program Files / 用户主目录本身 / 常见 shell
     * 文件夹 / Gamanga 自己的数据根（DB 与下载缓存是父子关系，混住后
     * 「清理缓存」的白名单逻辑离数据库只差一层，拒绝）。
     */
    private fun Path.isDangerousDownloadRoot(): Boolean {
        val p = toAbsolutePath().normalize()
        val home = runCatching { Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize() }
            .getOrNull() ?: return false
        val shell = listOf("Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music", "OneDrive")
        val bad = buildList {
            add(Paths.get("C:\\"))
            add(Paths.get("C:\\Windows"))
            add(Paths.get("C:\\Program Files"))
            add(Paths.get("C:\\Program Files (x86)"))
            add(home)
            shell.forEach { add(home.resolve(it)) }
            System.getenv("LOCALAPPDATA")?.let { add(Paths.get(it, "Gamanga")) }
            System.getenv("APPDATA")?.let { add(Paths.get(it, "Gamanga")) }
        }
        return bad.any { p == it.toAbsolutePath().normalize() }
    }

    /** 当前下载缓存根目录。 */
    fun rootDir(): Path = rootDir

    /** 目录占用字节（不存在/读不了返回 0）。 */
    private fun dirSize(dir: Path): Long =
        dir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- 队列泵 ----

    /**
     * 补位启动章节下载协程。空槽位数 = maxParallelChapters - 在飞任务数。
     * 整个「取队首 + 占 job 槽」在同一把锁里完成，防止两个 pump 重复启动同一章。
     */
    private fun pump() {
        if (paused) return // 全部暂停：不启动新章节，排队任务原地等待
        synchronized(queue) {
            while (jobs.values.count { it.isActive } < maxParallelChapters) {
                val next = queue.firstOrNull() ?: break
                queue.removeFirst()
                if (jobs.containsKey(next)) continue // 已在飞（尚未进 finally 清理）
                jobs[next] = scope.launch { downloadChapter(next) }
            }
        }
    }

    /**
     * 全部暂停（第八轮 #5，第九轮 #11 改为「真停」）。
     *
     * **旧实现只停「启动新章节」**：在飞任务跑完当前章——用户实测「点全部暂停
     * 还在继续下载」（日志铁证：19:41:40 pauseAll → 19:41:48 completed）。
     *
     * 现在：取消在飞协程（取消不删行不删文件——CancellationException 原样抛出，
     * finally 只清 jobs/queue，半章文件留给下次续跑重下）+ 把 DOWNLOADING 状态的
     * 任务置回 QUEUED（DB/UI 一致）+ 清空排队（[paused] 期间不启动）。
     */
    fun pauseAll() {
        if (paused) return
        paused = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        synchronized(queue) { queue.clear() }
        queries.selectUnfinishedDownloads().executeAsList().forEach { row ->
            if (row.status == DownloadStatus.DOWNLOADING.name) {
                // 保留已下载进度（第十二轮 #9）：磁盘页数写回 DB——下载页显示
                // 「已暂停 N/M 页」+ 停住的进度条，而不是归零显示「等待中」。
                val onDisk = chapterDir(row.mangaId, row.chapterId).toFile()
                    .listFiles()?.count { it.isFile && it.length() > 0 } ?: 0
                update(
                    row.toTask().copy(
                        status = DownloadStatus.QUEUED,
                        downloadedPages = onDisk,
                        error = null,
                    )
                )
            }
        }
        AppLog.i("Download", "pauseAll: in-flight cancelled, unfinished reverted to QUEUED (disk pages kept)")
    }

    /** 全部下载（第八轮 #5）：解除暂停，所有未完成任务重新排队并补位启动。 */
    fun resumeAll() {
        paused = false
        val unfinished = queries.selectUnfinishedDownloads().executeAsList().map { it.id }
        synchronized(queue) {
            unfinished.forEach { id -> if (id !in queue) queue.addLast(id) }
        }
        AppLog.i("Download", "resumeAll: ${unfinished.size} unfinished task(s) re-queued")
        pump()
    }

    fun isPaused(): Boolean = paused

    private suspend fun downloadChapter(id: String) {
        val task = taskOf(id) ?: return
        try {
            update(task.copy(status = DownloadStatus.DOWNLOADING, error = null))
            val type = sourceTypeOf(task.mangaId)
                ?: throw IllegalStateException("无法识别来源：${task.mangaId}")
            // 1) 取图片列表：这一步决定 pageCount，也是配额/浏览器开销所在。
            // 失败信息带上阶段——用户看到「取图片列表失败：HTTP 502」知道卡在
            // 哪一步，而不是一句没头没尾的裸异常（第七轮 #3）。
            val pages = try {
                withContext(Dispatchers.IO) { pagesOf(type, task.chapterId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw DownloadException("取图片列表失败：${e.message ?: e::class.simpleName}", e)
            }
            if (pages.isEmpty()) throw DownloadException("该话没有图片（源站返回空列表）")
            update(task.copy(status = DownloadStatus.DOWNLOADING, pageCount = pages.size))
            // 2) 断点续传（第十轮 #6，用户实测「暂停后继续从 0 重新下载」）：
            // **不清空目录**——扫已有完整页文件（页序零填充名），跳过已下的页，
            // 只补缺失的。暂停/失败/重试/重启后续跑都只补剩余页。
            // 旧实现 deleteRecursively 清空重下，半章进度全废。
            val dir = chapterDir(task.mangaId, task.chapterId)
            Files.createDirectories(dir)
            val existing = dir.toFile().listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.map { it.name }
                ?.toSet() ?: emptySet()
            // 3) 页间并发下载（章节间并发由队列控制，这里只管页）
            val gate = Semaphore(maxParallelPages)
            // 单调计数器：并发页完成顺序不固定，直接写 i+1 会让进度条回退；
            // 按「写完一页文件才 +1」计数既真实（= 磁盘页数）又只增不减。
            // 续传时从已有页数起算——进度条起点就是已下的部分。
            val done = java.util.concurrent.atomic.AtomicInteger(existing.size)
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            coroutineScope {
                pages.mapIndexed { i, page ->
                    async {
                        gate.withPermit {
                            // 任务可能已被 delete() 删掉：行没了就停手，
                            // 不再写文件/写状态（finally 会负责自清目录）。
                            if (!taskExists(id)) {
                                return@withPermit
                            }
                            val fileName = pageFileName(i, page.url)
                            // 断点续传：已完整下过的页直接跳过（源站页序稳定，
                            // 文件名即页序；0 字节的半页文件不算，会重下）
                            if (fileName in existing) return@withPermit
                            val data = fetchPageWithRetry(page, i + 1, pages.size, type)
                            Files.write(
                                dir.resolve(fileName),
                                data,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                            bytes.addAndGet(data.size.toLong())
                            recordRate(data.size.toLong())
                            val n = done.incrementAndGet()
                            // 每页都落库：进程随时可能被杀，重启后续传从 downloadedPages 接着算。
                            //
                            // **并发写入必须保证单调（R-124，2026-10-26 实测抓到的真 bug）**：
                            // `update()` 是「整行覆盖」，而这里是 maxParallelPages 个协程并发调用——
                            // n=3 先落库、n=2 后落库，进度条就会**往回退**。
                            // 实测证据：任务停在 downloadedPages=2 而 totalBytes=30（3 页 × 10 字节），
                            // 也就是三页都下完了，进度却显示 2。测试 `progress is monotonic`
                            // 本来就是为这个行为写的，只是一直偶发失败、被当成"测试不可靠"。
                            // 现在把「读当前值 → 只有更大才写」放进同一把锁，原子且单调。
                            synchronized(dbLock) {
                                if (taskExists(id)) {
                                    val cur = _tasks.value[id]
                                    if (cur != null && n > cur.downloadedPages) {
                                        update(
                                            cur.copy(
                                                status = DownloadStatus.DOWNLOADING,
                                                pageCount = pages.size,
                                                downloadedPages = n,
                                                totalBytes = bytes.get(),
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            // 4) 完成前最后确认任务还在（delete 可能刚删了行）
            if (!taskExists(id)) return
            // 清理页序超界的残留文件：源站页数变少时，上一轮多下的页会混进
            // localPages（按文件名排序）——删掉页序 >= 总数的
            dir.toFile().listFiles()?.forEach { f ->
                if (f.isFile && (f.nameWithoutExtension.toIntOrNull() ?: 0) >= pages.size) f.delete()
            }
            // 顺带回填章节页数（与阅读器同源，G社 唯一的页数来源）
            db.mangaQueries.updateChapterPageCount(pages.size.toLong(), id)
            update(
                task.copy(
                    status = DownloadStatus.COMPLETED,
                    pageCount = pages.size,
                    downloadedPages = pages.size,
                    totalBytes = bytes.get(),
                    error = null,
                )
            )
            AppLog.i(
                "Download",
                "completed ${task.mangaId}#${task.chapterId} '${task.chapterName}' " +
                    "${pages.size} pages / ${bytes.get()} bytes"
            )
        } catch (e: CancellationException) {
            // 取消（删除任务 / 应用退出）不是失败：原样抛出，不写任何状态。
            // 吞掉它会重演 R-45 僵尸协程——把 error 盖回已经不属于它的任务。
            throw e
        } catch (e: Throwable) {
            // 删除竞态：delete() 删行后协程才抛（如目录已删导致写文件失败），
            // 此时行已经没了——绝不能把 FAILED 写回去"复活"用户刚删掉的任务。
            if (queries.selectDownloadById(id).executeAsOneOrNull() == null) return
            update(
                task.copy(
                    status = DownloadStatus.FAILED,
                    error = e.displayMessage(task.chapterName),
                )
            )
            AppLog.e("Download", "failed ${task.mangaId}#${task.chapterId} '${task.chapterName}'", e)
        } finally {
            synchronized(queue) { queue.remove(id) }
            jobs.remove(id)
            // 自清：任务行没了（被 delete 删掉）就把自己写的目录删掉。
            // delete() 也删，但协程可能比它晚一步刚建好目录——两边都删才收敛。
            if (queries.selectDownloadById(id).executeAsOneOrNull() == null) {
                chapterDir(task.mangaId, task.chapterId).toFile().deleteRecursively()
            }
            pump() // 这一章让出槽位，队列里下一章补上
        }
    }

    /**
     * 单页下载 + 有限重试。配额/条目失效类错误不重试（重试没意义还白烧请求），
     * 其余（网络抖动、5xx）退避重试 [PAGE_FETCH_ATTEMPTS] 次。
     *
     * 为什么值得重试：旧实现任一页 fetch 抛错就取消全章——用户下了 90% 也
     * 整体失败、进度全废。第七轮 #3 实测「下载接近完成时却提示失败」的真因：
     * 单页瞬时失败被放大成整章失败，且错误信息是裸异常消息，不带页码/阶段。
     */
    private suspend fun fetchPageWithRetry(
        page: PageImage,
        pageNo: Int,
        total: Int,
        type: SourceType?,
    ): ByteArray {
        var last: Throwable? = null
        repeat(PAGE_FETCH_ATTEMPTS) { attempt ->
            try {
                return fetchPage(page, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e.isQuotaError || e.isGoneError) {
                    throw DownloadException(
                        "第 $pageNo/$total 页下载失败：${e.message ?: "图片获取失败"}", e
                    )
                }
                last = e
                if (attempt < PAGE_FETCH_ATTEMPTS - 1) delay(PAGE_RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        val reason = last?.message ?: last?.let { it::class.simpleName } ?: "未知错误"
        throw DownloadException("第 $pageNo/$total 页下载失败（重试 ${PAGE_FETCH_ATTEMPTS} 次仍失败）：$reason", last)
    }

    /** 失败展示信息：已带上下文的（[DownloadException]）原样用，裸异常补上章节名。 */
    private fun Throwable.displayMessage(chapterName: String): String =
        if (this is DownloadException) message ?: "下载失败"
        else "《$chapterName》下载失败：${message ?: this::class.simpleName ?: "未知错误"}"

    // ---- 孤儿清理（移出书架后的磁盘文件 + DB 行）----

    /**
     * 收尾「库里已经没有这本书」的下载缓存。
     *
     * [liveMangaIds] 传的是**库里所有 manga 行**（[LibraryRepository.allMangaIds]），
     * **不是**书架条目 —— 移出书架是软移出（只翻 `inLibrary`），下载缓存必须留着（R-136）。
     */
    private fun cleanupOrphans(liveMangaIds: Set<String>) {
        // 归属标记检查（第八轮 #7）：没有标记的目录不是我们的缓存目录
        // （用户 pref 指错地方时），只清 DB 行/镜像，绝不动磁盘上的其他文件。
        val marked = rootDir.resolve(MARKER_NAME).toFile().exists()
        // 目录名是 sanitize 过的，比对时用同一套规则（防止"komiic:228"与
        // "komiic_228"这类理论撞名误删——撞名时保留，偏安全）
        val liveDirs = liveMangaIds.map { sanitizePathSegment(it) }.toSet()
        if (marked) {
            rootDir.toFile().listFiles()?.forEach { child ->
                if (child.isDirectory && child.name !in liveDirs) child.deleteRecursively()
            }
        } else {
            AppLog.w("Download", "cleanupOrphans skipped disk sweep: no marker in $rootDir")
        }
        // DB 行：**真删**（`removeFromLibrary` / 多选删除）时 manga 行没了，
        // ON DELETE CASCADE 已经把下载行带走；这里兜底「行还在」的形态
        // （如手动改库、FK 未生效的连接）。软移出（`hideFromLibrary`）行还在 → 不会被清。
        val orphanIds = queries.selectAllDownloads().executeAsList()
            .filter { it.mangaId !in liveMangaIds }
            .map { it.id }
        orphanIds.forEach { queries.deleteDownload(it) }
        // 镜像：不能靠查库行来定孤儿——外键级联先删行的路径下，
        // selectAllDownloads 早已查不到它们，但内存镜像不会自己消失，
        // UI 会挂着幽灵任务。镜像按 mangaId 直接收。
        val staleMirrorIds = _tasks.value.values
            .filter { it.mangaId !in liveMangaIds }
            .map { it.id }
        if (staleMirrorIds.isNotEmpty()) {
            _tasks.update { map -> map - staleMirrorIds.toSet() }
        }
    }

    // ---- 状态读写 ----

    private fun taskOf(id: String): DownloadTask? =
        queries.selectDownloadById(id).executeAsOneOrNull()?.toTask()

    private fun update(task: DownloadTask) {
        // 内存镜像与库行用同一个时间戳：下载管理页组内按 createdAt（入队序）
        // 排序，updatedAt 只反映"最近一次进度变化"，不参与排序——两边不一致
        // 会出现「刚操作完的任务不动」。
        val now = System.currentTimeMillis()
        val stamped = task.copy(updatedAt = now)
        persist(stamped, now)
        _tasks.update { it + (stamped.id to stamped) }
    }

    private fun persist(task: DownloadTask, now: Long) {
        // 章节内是 maxParallelPages 个协程并发下载，每下完一页都会走到这里；
        // 而 JdbcSqliteDriver **复用同一个 JDBC 连接**。多线程同时往一个 SQLite
        // 连接写，轻则 SQLITE_BUSY，重则互相等待——表现是「进度卡住不动」，
        // 测试里就是偶发干等超时（T-04 追到的现象）。写只有几十微秒，串行化很划算。
        synchronized(dbLock) {
            queries.upsertDownload(
                id = task.id,
                mangaId = task.mangaId,
                chapterId = task.chapterId,
                name = task.chapterName,
                pageCount = task.pageCount.toLong(),
                downloadedPages = task.downloadedPages.toLong(),
                status = task.status.name,
                error = task.error,
                totalBytes = task.totalBytes,
                updatedAt = now,
                createdAt = task.createdAt,
            )
        }
    }

    /** 任务行是否还在（被 delete() 删掉时页协程要停手）。读也走同一把锁，理由见 [persist]。 */
    private fun taskExists(id: String): Boolean = synchronized(dbLock) {
        queries.selectDownloadById(id).executeAsOneOrNull() != null
    }

    private fun reloadFromDb() {
        _tasks.value = queries.selectDownloadsWithTitle().executeAsList()
            .associate { it.id to it.toTask() }
    }

    // ---- 路径与命名 ----

    private fun chapterDir(mangaId: String, chapterId: String): Path =
        rootDir.resolve(sanitizePathSegment(mangaId)).resolve(sanitizePathSegment(chapterId))

    private fun pageFileName(index: Int, url: String): String =
        "%04d.%s".format(index, extensionOf(url))

    companion object {

        /** 本地章节 id：与 chapter 表同构（"<mangaId>#<sourceChapterId>"）。 */
        fun chapterLocalId(mangaId: String, sourceChapterId: String) = "$mangaId#$sourceChapterId"

        /**
         * 路径段净化：只保留 ASCII 字母数字与 `._-`，其余折叠成 `_`。
         *
         * 本地 id 含 `:`（`komiic:228`）和 `#`（章节 id）——Windows 文件名
         * 都不允许，必须替换。零填充页序 + 净化后的 id 组合保证排序稳定。
         */
        internal fun sanitizePathSegment(segment: String): String =
            segment.map { c ->
                val ok = c.code in 48..57 || c.code in 65..90 || c.code in 97..122 || c in "._-"
                if (ok) c else '_'
            }.joinToString("")

        /** 从 URL 猜图片扩展名（Skia 按魔术数字解码，猜错也只是文件名难看）。 */
        internal fun extensionOf(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
            val ext = path.substringAfterLast('.', "").lowercase()
            return if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) ext else "jpg"
        }

        /** mangaId → SourceType（"komiic:228" → KOMIIC）。解析不了返回 null。 */
        internal fun sourceTypeOf(mangaId: String): SourceType? =
            runCatching { SourceType.valueOf(mangaId.substringBefore(':').uppercase()) }.getOrNull()

        /** 人类可读的体积（下载管理页总占用）。 */
        internal fun formatBytes(bytes: Long): String {
            val abs = bytes.absoluteValue
            return when {
                abs < 1024 -> "$bytes B"
                abs < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                abs < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }

        private fun DownloadChapter.toTask() = DownloadTask(
            id = id,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterName = name,
            pageCount = pageCount.toInt(),
            downloadedPages = downloadedPages.toInt(),
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
            error = error,
            totalBytes = totalBytes,
            updatedAt = updatedAt,
            createdAt = createdAt,
        )

        private fun SelectDownloadsWithTitle.toTask() = DownloadTask(
            id = id,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterName = name,
            mangaTitle = mangaTitle,
            pageCount = pageCount.toInt(),
            downloadedPages = downloadedPages.toInt(),
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
            error = error,
            totalBytes = totalBytes,
            updatedAt = updatedAt,
            createdAt = createdAt,
        )
    }
}
