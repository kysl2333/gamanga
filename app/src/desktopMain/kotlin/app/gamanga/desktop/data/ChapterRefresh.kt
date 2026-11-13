package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

data class ChapterRefreshResult(
    val updated: Int,
    val failed: Int = 0,
    val total: Int = 0,
    val alreadyRunning: Boolean = false,
) {
    val message: String get() = when {
        alreadyRunning -> "正在刷新书架"
        total == 0 -> "书架暂无漫画"
        failed > 0 && updated > 0 -> "有 $updated 部漫画更新，$failed 部未完成刷新"
        failed > 0 -> "$failed 部漫画未完成刷新，请稍后重试"
        updated > 0 -> "有 $updated 部漫画更新了章节"
        else -> "已是最新"
    }
}

/**
 * 书架章节刷新（F-271，2026-10-29 用户要求）。
 *
 * ## 用户口径
 *
 * 「设置页增加刷新书架详情页章节的选项：手动刷新 / 每次开启应用刷新 / 每七天刷新 / 每一天刷新；
 *   默认手动；如果有更新章节，就在书架页『全部漫画 N 部』旁边加个刷新小图标按钮，
 *   刷新到新章节时封面框右上角加一个绿色提示点，进入该漫画详情页后消失。」
 *
 * ## 怎么判断「有新章节」
 *
 * 不用「本次刷新发现多了几话」——那样第二次刷新就归零，绿点会在用户没看过之前自己消失。
 * 改成记 **看过的那一版话数**（`seen[id]`）：
 *
 * - 刷新时只把源站章节列表同步进库（[LibraryRepository.syncChapters]），
 *   顺便对比 `库里的章数 > seen[id]` → 这就是「新章节数」；
 * - 用户**点进详情页**才算看过 → `seen[id] = 当前章数`（[clear]），绿点消失。
 *
 * 于是绿点会一直挂着，直到用户真的去看，符合「进入该漫画详情页后消失」。
 *
 * ## 为什么要节流
 *
 * 每本书都要拉一次源站章节列表（拷贝漫画走隐藏浏览器，单本 6~10 秒）。
 * 所以：① 只对书架内的书；② 并发 3；③ 「每七天 / 每一天」按上次成功刷新的时间判断是否到期；
 * ④ 同一时刻只允许一轮（[running] 为真时直接返回）；⑤ 启动尝试间隔10分钟，失败也计；
 * ⑥ 拷贝目录异常后停止本轮同源队列。
 */
object ChapterRefresh {

    const val MODE_MANUAL = "manual"
    const val MODE_LAUNCH = "launch"
    const val MODE_DAILY = "daily"
    const val MODE_WEEKLY = "weekly"

    const val PREF_MODE = "library.refresh.mode"
    const val PREF_LAST_RUN = "library.refresh.lastRun"
    const val PREF_LAST_ATTEMPT = "library.refresh.lastAttempt"
    private const val AUTO_ATTEMPT_GAP_MS = 10 * 60_000L
    const val PREF_SEEN = "library.refresh.seen"

    /** 各档的展示文案（设置页与文档共用一份，别在两处各写一遍）。 */
    val MODE_LABELS: List<Pair<String, String>> = listOf(
        MODE_MANUAL to "手动刷新",
        MODE_LAUNCH to "每次开启应用刷新",
        MODE_DAILY to "每一天刷新",
        MODE_WEEKLY to "每七天刷新",
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CONCURRENCY = 3
    private val stateLock = Any()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 每本书的新章节数（key = 本地漫画 id）。空 = 没有更新。 */
    private val _pending = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pending: StateFlow<Map<String, Int>> = _pending.asStateFlow()

    fun mode(repository: LibraryRepository): String =
        repository.getPref(PREF_MODE)?.takeIf { it in MODE_LABELS.map { l -> l.first } } ?: MODE_MANUAL

    fun setMode(repository: LibraryRepository, mode: String) {
        repository.setPref(PREF_MODE, mode)
    }

    fun labelOf(mode: String): String =
        MODE_LABELS.firstOrNull { it.first == mode }?.second ?: "手动刷新"

    /** 上次成功刷新的时间戳（0 = 从没刷过）。 */
    fun lastRun(repository: LibraryRepository): Long =
        repository.getPref(PREF_LAST_RUN)?.toLongOrNull() ?: 0L

    /** 启动时该不该自动刷一次。 */
    fun dueOnLaunch(repository: LibraryRepository, now: Long = System.currentTimeMillis()): Boolean {
        val lastAttempt = repository.getPref(PREF_LAST_ATTEMPT)?.toLongOrNull()
        if (lastAttempt != null && now - lastAttempt < AUTO_ATTEMPT_GAP_MS) return false
        return when (mode(repository)) {
            MODE_LAUNCH -> true
            MODE_DAILY -> now - lastRun(repository) >= DAY_MS
            MODE_WEEKLY -> now - lastRun(repository) >= 7 * DAY_MS
            else -> false
        }
    }

    /** 启动时调用：把已存的「看过的话数」读进内存，并算一次绿点。 */
    fun initialize(repository: LibraryRepository) {
        readSeen(repository)
        recompute(repository)
    }

    /**
     * 刷新整个书架。
     *
     * @return 更新数及未完成数；失败不能显示为「已是最新」。
     */
    suspend fun run(
        repository: LibraryRepository,
        reason: String,
        adapterOf: (SourceType) -> SourceAdapter? = Sources::of,
    ): ChapterRefreshResult {
        if (!_running.compareAndSet(false, true)) return ChapterRefreshResult(_pending.value.size, alreadyRunning = true)
        val started = System.currentTimeMillis()
        try {
            val items = repository.shelfItems.value
            if (items.isEmpty()) return ChapterRefreshResult(0)
            // 在网络前落库：失败或中途退出后重启也不立即重刷整库；不冒充成功时间。
            repository.setPref(PREF_LAST_ATTEMPT, started.toString())
            val semaphore = Semaphore(CONCURRENCY)
            suspend fun refresh(item: app.gamanga.desktop.model.MangaEntry): Boolean {
                val adapter = adapterOf(item.source) ?: return false
                return try {
                    val remote = adapter.refreshChapters(item.sourceMangaId)
                    if (remote.isEmpty() || remote.any { it.navigationOnly }) return false
                    synchronized(stateLock) {
                        val seen = readSeen(repository)
                        val local = repository.chapterCountOf(item.id)
                        val baseline = seenAfterRefresh(seen[item.id], local, remote.size)
                        repository.syncChapters(item.id, remote)
                        seen[item.id] = baseline
                        writeSeen(repository, seen)
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Refresh", "chapters failed source=${item.source} category=${e.javaClass.simpleName}")
                    false
                }
            }
            val results = coroutineScope {
                // 拷贝按源单队列：首个不完整/失败后停止同源剩余项，其他源照常。
                // 不把导航缺失直接判成风控，也不删除既有目录或绿点。
                items.groupBy { it.entry.source }.map { (source, group) ->
                    async(Dispatchers.IO) {
                        if (source == SourceType.COPYMANGA) {
                            var stopped = false
                            group.mapIndexed { index, item ->
                                if (stopped) false else semaphore.withPermit { refresh(item.entry) }.also { ok ->
                                    if (!ok) {
                                        stopped = true
                                        AppLog.w("Refresh", "source batch stopped source=$source category=directory-unavailable remaining=${group.size - index - 1}")
                                    }
                                }
                            }
                        } else coroutineScope {
                            group.map { item -> async { semaphore.withPermit { refresh(item.entry) } } }.awaitAll()
                        }
                    }
                }.awaitAll().flatten()
            }
            // 失败时保留库内已知更新，不用本次成功子集覆盖绿点。
            recompute(repository)
            if (results.all { it }) repository.setPref(PREF_LAST_RUN, System.currentTimeMillis().toString())
            val found = _pending.value
            AppLog.i(
                "Refresh",
                "$reason 完成：${items.size} 本，有更新 ${found.size} 本，失败 ${results.count { !it }} 本，" +
                    "${System.currentTimeMillis() - started}ms",
            )
            return ChapterRefreshResult(found.size, results.count { !it }, items.size)
        } finally {
            _running.value = false
        }
    }

    /**
     * 用户看过了（点进详情页）→ 绿点消失。
     *
     * ⚠️ **R-180（2026-10-30 用户）**：「详情页还没加载完全好像就识别以为有新漫画」。
     * 点开时本地章数可能还是 **0**（章节从来没落过库）或旧值：详情页是「先上本地那份、
     * 后台再拉源站并 `syncChapters` 落库」，落库之后章数就涨了。
     * 旧实现无条件把当前章数记成基线 → 记成 0（或旧值）→ 下一轮刷新把整本书都算成新章节，
     * 绿点又冒出来。
     *
     * 现在：**本地 0 章时不写基线**（宁可不记，也不记 0），详情页加载完成后再由
     * [markLoaded] 把基线推到真实章数。
     */
    fun clear(repository: LibraryRepository, mangaId: String) = synchronized(stateLock) {
        val seen = readSeen(repository)
        val next = seenAfterView(seen[mangaId], repository.chapterCountOf(mangaId))
        if (next != null && next != seen[mangaId]) {
            seen[mangaId] = next
            writeSeen(repository, seen)
        }
        _pending.value = _pending.value - mangaId
    }

    /**
     * 详情页的章节**已经加载并落库**（R-180）。
     *
     * [clear] 发生在「点开的那一刻」，那时章数还是旧的；网络回来后 `syncChapters`
     * 会把新章节写进库，章数变大。如果基线停在旧值，下一轮刷新就会把用户刚刚看过的
     * 那几话报成「新章节」。所以在详情页刷新结束时再推一次基线。
     *
     * 只对**已经有基线**的书生效（= 在书架里且刷过）——没基线的书交给 [clear]，
     * 免得给书架外的书往 prefs 里塞垃圾。
     */
    fun markLoaded(repository: LibraryRepository, mangaId: String) = synchronized(stateLock) {
        val seen = readSeen(repository)
        if (!seen.containsKey(mangaId)) {
            _pending.value = _pending.value - mangaId
            return@synchronized
        }
        val next = seenAfterView(seen[mangaId], repository.chapterCountOf(mangaId))
        if (next != null && next != seen[mangaId]) {
            seen[mangaId] = next
            writeSeen(repository, seen)
        }
        _pending.value = _pending.value - mangaId
    }

    /** 重新算一遍绿点（刷新后 / 看过之后 / 启动时）。 */
    fun recompute(repository: LibraryRepository) = synchronized(stateLock) {
        val seen = readSeen(repository)
        _pending.value = repository.shelfItems.value.mapNotNull { item ->
            val id = item.entry.id
            val baseline = seen[id] ?: return@mapNotNull null
            val now = repository.chapterCountOf(id)
            if (now > baseline) id to (now - baseline) else null
        }.toMap()
    }

    // ---- seen 的存取（JSON 存在 appPrefs 里，不动 schema）----

    private fun readSeen(repository: LibraryRepository): MutableMap<String, Int> {
        val raw = repository.getPref(PREF_SEEN) ?: return mutableMapOf()
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as JsonObject
            obj.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.intOrNull?.let { k to it }
            }.toMap().toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeSeen(repository: LibraryRepository, seen: Map<String, Int>) {
        val json = buildJsonObject {
            seen.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        repository.setPref(PREF_SEEN, json)
    }

    /** 测试用：清掉内存态。 */
    internal fun resetForTest() {
        _pending.value = emptyMap()
        _running.value = false
    }
}

/**
 * 「看过之后」的基线（纯函数，`ChapterRefreshPolicyTest` 覆盖）。
 *
 * [loaded] 是**当前库里**的章数：
 * - `> 0` → 就记它（用户看到的就是这些章节）；
 * - `== 0` → **保留原基线**（章节还没加载出来，记 0 会让下一轮刷新把整本书算成新章节）。
 *
 * 返回 null 表示「本来就没有基线、这次也定不出来」→ 调用方不写 prefs。
 */
internal fun seenAfterView(current: Int?, loaded: Int): Int? =
    if (loaded > 0) loaded else current

/**
 * 刷新时给「第一次见到的书」定基线（纯函数）。
 *
 * 优先用本地章数；本地一话都没有（章节从没落过库）时用**远端章数**——
 * 旧实现记的是 `local`，也就是 0，于是下一轮刷新把全部章节都当成新增。
 */
internal fun seenAfterRefresh(current: Int?, local: Int, remoteSize: Int): Int =
    current ?: if (local > 0) local else remoteSize
