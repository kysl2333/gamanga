package app.gamanga.desktop.ui.recommend

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import app.gamanga.desktop.source.BangumiClient
import app.gamanga.desktop.source.BangumiMirror
import app.gamanga.desktop.source.BangumiMirrors
import app.gamanga.desktop.source.MirrorProbe
import app.gamanga.desktop.source.isUsable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.prefs.Preferences

class RecommendState(
    private val scope: CoroutineScope,
    private val prefs: Preferences = Preferences.userRoot().node("Gamanga/recommend"),
    private val searchPage: suspend (String, String, String, Int, Boolean) -> BangumiClient.Page = { keyword, category, sort, offset, refresh ->
        BangumiClient.search(keyword, category, sort, offset, refresh)
    },
    private val randomizer: kotlin.random.Random = kotlin.random.Random.Default,
) {
    data class Record(val type: Int = 0, val rate: Int = 0)
    class Detail(val item: BangumiClient.Item) {
        var content by mutableStateOf(item)
        val scroll = ScrollState(0)
        var related by mutableStateOf<List<BangumiClient.Item>>(emptyList())
        var similar by mutableStateOf<List<BangumiClient.Item>>(emptyList())
        var people by mutableStateOf<List<BangumiClient.Person>>(emptyList())
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var job: Job? = null
    }
    private val grids = mutableMapOf<String, LazyGridState>()
    val grid: LazyGridState get() = grids.getOrPut(tab) { LazyGridState() }
    var reduceMotion by mutableStateOf(prefs.getBoolean("reduceMotion", false))
    fun setReducedMotion(value: Boolean) { reduceMotion = value; prefs.putBoolean("reduceMotion", value) }
    var keyword by mutableStateOf("")
    var category by mutableStateOf("全部")
    var collectionType by mutableIntStateOf(0)
    var sort by mutableStateOf("rank")
    var tab by mutableStateOf("发现")
    var random by mutableStateOf(prefs.getBoolean("randomMode", false))
        private set
    fun setRandomMode(value: Boolean) {
        random = value; prefs.putBoolean("randomMode", value)
        if (value) { keyword = ""; category = "全部" }
        search(newRandomBatch = value)
    }
    val randomBrowse get() = random && keyword.isBlank() && category == "全部"
    fun nextRandomBatch() {
        if (loading && searchJob?.isActive == true) return
        keyword = ""; category = "全部"
        search(newRandomBatch = true)
    }
    fun refresh() { search(forceRefresh = true) }
    fun browseTag(tag: String) {
        val value = tag.trim()
        if (value.isEmpty()) return
        clearDetails()
        tab = "发现"
        keyword = ""
        category = value
        random = false
        prefs.putBoolean("randomMode", false)
        search()
    }
    fun consumeSyncNotice(version: Int) { if (syncNoticeVersion == version) syncNotice = null }
    var items by mutableStateOf<List<BangumiClient.Item>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var offset by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var username by mutableStateOf("")
    var avatar by mutableStateOf<String?>(null)
    var token by mutableStateOf("")
    var syncing by mutableStateOf(false)
    var silentSync by mutableStateOf(false)
        private set
    val showSyncProgress get() = syncing && !silentSync
    var syncProgress by mutableIntStateOf(0)
    var saving by mutableStateOf(false)
    var savingId by mutableStateOf<Int?>(null)
    var syncNotice by mutableStateOf<String?>(null)
    var syncNoticeVersion by mutableIntStateOf(0)
    fun notifySynced(text: String) { syncNotice = text; syncNoticeVersion++ }
    val stack = mutableStateListOf<Detail>()
    val records = mutableStateMapOf<Int, Record>() // Persistent remote account snapshot; no reading progress.
    private val excludedSubjects = mutableSetOf<Int>()
    private val accountPersistGate = Mutex()
    private val accountSnapshotGeneration = java.util.concurrent.atomic.AtomicInteger()
    var mirror by mutableStateOf(BangumiMirrors.Official)
        private set
    /** 换源窗口展示的线路清单：内置兜底 + 上次从镜像目录拉到的（打开窗口时再刷新一次）。 */
    var mirrors by mutableStateOf(BangumiMirrors.loadCatalog(prefs))
        private set
    /** 官方线路已确认不可达。用户要求：只有不通时才在界面上露出换源入口。 */
    var officialUnreachable by mutableStateOf(false)
        private set
    /** 官方不通时探到的可用镜像。**不直接生效**，等用户二次确认（2026-10-28 用户要求）。 */
    var suggestedMirror by mutableStateOf<BangumiMirror?>(null)
        private set
    var probing by mutableStateOf(false)
        private set
    var probes by mutableStateOf<Map<String, MirrorProbe>>(emptyMap())
        private set
    /** 切换线路：写偏好、作废旧线路的图与响应缓存，然后重拉一页确认真的通了。 */
    fun selectMirror(value: BangumiMirror) {
        BangumiMirrors.applyAndSave(value, prefs)
        mirror = value
        suggestedMirror = null
        autoFallbackTried = false
        RecommendImageCache.clear()
        message = null
        if (value.id != BangumiMirrors.OFFICIAL_ID) officialUnreachable = true
        search()
    }
    /** 用户否掉推荐镜像：不再自动重试，把换源入口留给用户自己点。 */
    fun dismissSuggestedMirror() {
        suggestedMirror = null
        message = null
        error = "官方线路不可达，可用右上角换源选择镜像线路"
    }
    fun confirmSuggestedMirror() {
        val target = suggestedMirror ?: return
        suggestedMirror = null
        selectMirror(target)
    }
    /** 并发探测整份候选清单。打开窗口时顺手从镜像目录刷一次线路清单。 */
    fun probeMirrors(refreshDirectory: Boolean = true) {
        if (probing) return
        probing = true
        scope.launch {
            try {
                if (refreshDirectory) mirrors = BangumiMirrors.discover(prefs, mirrors)
                probes = BangumiMirrors.probeAll(mirrors).associate { (m, result) -> m.id to result }
                if (mirror.id !in probes) probes = probes + (mirror.id to MirrorProbe.Down)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "镜像检测失败，请稍后重试" }
            finally { probing = false }
        }
    }
    val accountItems = mutableStateMapOf<Int, BangumiClient.Item>()
    val known = mutableStateMapOf<Int, BangumiClient.Item>() // At most 50 history entries.
    val history = mutableStateListOf<Int>()
    private var searchJob: Job? = null
    private var accountJob: Job? = null
    private val persistGate = Mutex()
    private val snapshotGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val saveGate = Mutex()
    private var initialized = false
    private var searchGeneration = 0
    private val credentialGate = Mutex()
    private var accountGeneration = 0
    private var randomTotal = 0
    private val randomOffsets = linkedSetOf<Int>()
    private var currentBatchOffsets = emptyList<Int>()
    private var currentRandomItems = emptyList<BangumiClient.Item>()
    private var retryRandomBatch = false
    private val recentRandomIds = linkedSetOf<Int>()
    private var autoFallbackTried = false
    fun initialize() {
        if (initialized) return
        initialized = true
        restoreSnapshot(); restoreAccountSnapshot()
        BangumiMirrors.restore(prefs); mirror = BangumiMirrors.current
        if (mirror.id != BangumiMirrors.OFFICIAL_ID && mirrors.none { it.id == mirror.id }) mirrors = mirrors + mirror
        search()
        val generation = accountGeneration
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { credentialGate.withLock { BangumiCredential.read(prefs) } }
                if (generation == accountGeneration && !value.isNullOrBlank()) connect(value, persistCredential = false, silent = true)
            } catch (_: Exception) { message = "已保存的账号无法解锁，请重新连接" }
        }
    }
    private fun persist() {
        val data = buildJsonObject {
            putJsonArray("history") { history.take(50).forEach { add(it) } }
            putJsonArray("items") { history.mapNotNull { known[it] }.forEach { item ->
                add(buildJsonObject { put("id", item.id); put("name_cn", item.title); put("name", item.original); putJsonObject("images") { put("large", item.coverUrl.orEmpty()) } })
            } }
        }.toString()
        val generation = snapshotGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            persistGate.withLock {
                if (generation != snapshotGeneration.get()) return@withLock
                try {
                    val chunks = data.chunked(6000)
                    val old = prefs.getInt("snapshot.count", 0)
                    chunks.forEachIndexed { i, s -> prefs.put("snapshot.$i", s) }
                    for (i in chunks.size until old) prefs.remove("snapshot.$i")
                    prefs.putInt("snapshot.count", chunks.size); prefs.remove("local"); prefs.flush()
                } catch (_: Exception) { withContext(Dispatchers.Main) { message = "最近浏览保存失败" } }
            }
        }
    }
    fun restoreSnapshot() {
        runCatching {
            val n = prefs.getInt("snapshot.count", 0)
            if (n <= 0) return
            val o = Json.parseToJsonElement((0 until n).joinToString("") { prefs.get("snapshot.$it", "") }).jsonObject
            val loaded = (o["items"] as? JsonArray).orEmpty().mapNotNull { BangumiClient.parseItem(it.jsonObject) }.associateBy { it.id }
            history.clear(); history.addAll((o["history"] as? JsonArray).orEmpty().map { it.jsonPrimitive.int }.distinct().take(50))
            known.clear(); history.forEach { id -> loaded[id]?.let { known[id] = it } }
            persist() // Migrate old snapshots: deliberately discard all local collection/progress fields.
        }
    }
    internal fun restoreAccountSnapshot() {
        runCatching {
            val count = prefs.getInt("account.count", 0)
            if (count <= 0) return
            val root = Json.parseToJsonElement((0 until count).joinToString("") { prefs.get("account.$it", "") }).jsonObject
            username = root["username"]?.jsonPrimitive?.content.orEmpty()
            avatar = root["avatar"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            (root["items"] as? JsonArray).orEmpty().forEach { value ->
                val o = value.jsonObject
                val item = BangumiClient.parseItem(o) ?: return@forEach
                accountItems[item.id] = item
                records[item.id] = Record(o["status"]?.jsonPrimitive?.intOrNull ?: 0, o["rate"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            excludedSubjects.addAll((root["excluded"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull })
        }.onFailure { message = "账号缓存无法读取，将重新同步" }
    }
    internal fun persistAccountSnapshot() {
        val data = buildJsonObject {
            put("username", username); put("avatar", avatar.orEmpty())
            putJsonArray("excluded") { excludedSubjects.forEach { add(it) } }
            putJsonArray("items") { accountItems.values.forEach { item -> add(buildJsonObject {
                put("id", item.id); put("name_cn", item.title); put("name", item.original); put("platform", item.platform)
                putJsonObject("images") { put("large", item.coverUrl.orEmpty()) }
                putJsonObject("rating") { put("score", item.score ?: 0.0); put("rank", item.rank ?: 0) }
                put("status", records[item.id]?.type ?: 0); put("rate", records[item.id]?.rate ?: 0)
            }) } }
        }.toString()
        val generation = accountSnapshotGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) { accountPersistGate.withLock {
            if (generation != accountSnapshotGeneration.get()) return@withLock
            try {
                val chunks = data.chunked(6000); val old = prefs.getInt("account.count", 0)
                chunks.forEachIndexed { i, chunk -> prefs.put("account.$i", chunk) }
                for (i in chunks.size until old) prefs.remove("account.$i")
                prefs.putInt("account.count", chunks.size); prefs.flush()
            } catch (_: Exception) { withContext(Dispatchers.Main) { message = "账号缓存保存失败" } }
        } }
    }
    fun removeHistory(id: Int) { history.remove(id); known.remove(id); persist() }
    fun clearHistory() { history.clear(); known.clear(); persist() }
    fun switchTab(value: String) { tab = value }
    fun search(more: Boolean = false, forceRefresh: Boolean = false, newRandomBatch: Boolean = false) {
        if (more && randomBrowse) return
        if (more && (loading || offset >= total || error != null)) return
        searchJob?.cancel()
        val requestKeyword = keyword; val requestCategory = category; val requestSort = sort
        val randomBatch = randomBrowse
        val generation = ++searchGeneration
        val requestGrid = grids.getOrPut("发现") { LazyGridState() }
        loading = true; error = null
        searchJob = scope.launch {
            try {
                if (randomBatch) {
                    retryRandomBatch = newRandomBatch || currentBatchOffsets.isEmpty()
                    loadRandomBatch(newRandomBatch || currentBatchOffsets.isEmpty(), forceRefresh, generation, requestGrid)
                    return@launch
                }
                if (!more) { offset = 0; requestGrid.requestScrollToItem(0); items = emptyList() }
                var page = searchPage(requestKeyword, requestCategory, requestSort, offset, forceRefresh)
                // A filtered API page may contain only novels. Keep advancing until comics or the end.
                while (page.items.isEmpty() && offset + 30 < page.total) {
                    offset += 30
                    page = searchPage(requestKeyword, requestCategory, requestSort, offset, forceRefresh)
                }
                ensureActive()
                if (generation != searchGeneration) return@launch
                if (mirror.id == BangumiMirrors.OFFICIAL_ID) officialUnreachable = false
                val incoming = page.items
                // Only slim list metadata is retained; images remain in the shared budgeted LRU.
                val slim = incoming.map { it.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap(), tags = it.tags.take(8)) }
                items = (if (more) items + slim else slim).distinctBy { it.id }
                total = page.total; offset += 30
                if (items.size > 120) {
                    val remove = items.size - 90
                    val newIndex = (requestGrid.firstVisibleItemIndex - remove).coerceAtLeast(0)
                    val pixel = requestGrid.firstVisibleItemScrollOffset
                    items = items.drop(remove); requestGrid.scrollToItem(newIndex, pixel)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 官方线路在部分网络下整条不可达（本机实测全部超时）。首次失败时自动探一遍候选，
                // 但**不直接切过去**：换线路会让账号令牌经过第三方服务器，必须用户二次确认。
                if (generation != searchGeneration) return@launch
                if (randomBatch && items.isNotEmpty()) error = "加载失败，当前随机漫画已保留，请重试"
                else if (!more && mirror.id == BangumiMirrors.OFFICIAL_ID && !autoFallbackTried) {
                    autoFallbackTried = true
                    autoFallback()
                } else error = "加载失败，请检查网络后重试"
            }
            finally { if (generation == searchGeneration) loading = false }
        }
    }

    private suspend fun loadRandomBatch(newBatch: Boolean, refresh: Boolean, generation: Int, grid: LazyGridState) {
        val incoming = linkedMapOf<Int, BangumiClient.Item>()
        val requested = mutableListOf<Int>()
        if (!newBatch) {
            for (start in currentBatchOffsets) {
                val page = searchPage("", "全部", sort, start, refresh)
                currentCoroutineContext().ensureActive()
                page.items.forEach { incoming[it.id] = it }
            }
            if (generation != searchGeneration) return
            items = currentRandomItems.mapNotNull { incoming[it.id] }
            currentRandomItems = items
            return
        }
        var first: BangumiClient.Page? = null
        val count = if (randomTotal > 0) randomTotal else {
            val initial = searchPage("", "全部", sort, 0, refresh)
            first = initial
            currentCoroutineContext().ensureActive()
            initial.total
        }
        val pageCount = ((count + RANDOM_BATCH_SIZE - 1) / RANDOM_BATCH_SIZE).coerceAtLeast(1)
        val candidates = (0 until pageCount).map { it * RANDOM_BATCH_SIZE }.shuffled(randomizer)
        val fresh = candidates.filter { it !in randomOffsets }
        val starts = (fresh + candidates.filter { it in randomOffsets }).take(RANDOM_MAX_PAGES)
        for (start in starts) {
            val page = if (start == 0 && first != null) first else searchPage("", "全部", sort, start, refresh)
            currentCoroutineContext().ensureActive()
            requested += start
            page.items.filter { it.id !in recentRandomIds }.forEach { incoming.putIfAbsent(it.id, it) }
            if (incoming.size >= RANDOM_BATCH_SIZE) break
        }
        if (generation != searchGeneration) return
        randomTotal = count
        randomOffsets.addAll(requested)
        if (randomOffsets.size >= pageCount) randomOffsets.clear()
        if (incoming.isEmpty()) {
            message = "这一轮暂时没有新的漫画，请稍后再试"
            return
        }
        items = incoming.values.shuffled(randomizer).take(RANDOM_BATCH_SIZE).map {
            it.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap(), tags = it.tags.take(8))
        }
        currentBatchOffsets = requested
        currentRandomItems = items
        recentRandomIds.addAll(items.map { it.id })
        while (recentRandomIds.size > RANDOM_RECENT_LIMIT) recentRandomIds.remove(recentRandomIds.first())
        message = null
        grid.requestScrollToItem(0)
        total = count
        offset = count // 随机批次只由明确的底部动作推进，不参与无限续页。
    }

    private companion object {
        const val RANDOM_BATCH_SIZE = 30
        const val RANDOM_MAX_PAGES = 4
        const val RANDOM_RECENT_LIMIT = 300
    }

    /**
     * 官方线路失败后的自愈：从镜像目录拉清单 → 并发探测 → 把最快可用线路**推荐**给用户。
     *
     * 只推荐不生效（2026-10-28 用户明确要求）：界面弹二次确认，用户点「使用该线路」才真正
     * 换源；拒绝则保持官方，把换源入口留给用户自己点。
     */
    private fun autoFallback() {
        officialUnreachable = true
        message = "官方线路不可达，正在寻找可用镜像…"
        scope.launch {
            try {
                mirrors = BangumiMirrors.discover(prefs, mirrors)
                val results = BangumiMirrors.probeAll(mirrors)
                probes = results.associate { (m, result) -> m.id to result }
                val best = results
                    .filter { it.first.id != BangumiMirrors.OFFICIAL_ID && it.second.isUsable }
                    .minByOrNull { (it.second as MirrorProbe.Ok).latencyMs }?.first
                message = null
                if (best == null) { error = "官方线路不可达，镜像目录里也没有可用线路"; return@launch }
                suggestedMirror = best
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "线路检测失败，请检查网络后重试"; message = null }
        }
    }
    internal fun similarTag(tags: List<String>): String? = tags.firstOrNull {
        val tag = it.trim()
        tag.isNotBlank() && tag !in setOf("漫画", "漫畫", "漫画系列", "漫畫系列", "日本", "中国", "欧美", "书籍", "書籍", "单行本", "コミック", "コミックス", "连载", "连载中", "已完结", "全部", "小说改", "轻小说", "日本漫画") && !tag.matches(Regex("\\d{4}(年)?"))
    }
    fun retry() { error = null; if (randomBrowse) search(forceRefresh = true, newRandomBatch = retryRandomBatch) else search(items.isNotEmpty()) }
    fun closeDetail() { stack.removeLastOrNull()?.job?.cancel() }
    fun clearDetails() { stack.forEach { it.job?.cancel() }; stack.clear() }
    fun open(item: BangumiClient.Item) {
        known[item.id] = item.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap())
        history.remove(item.id); history.add(0, item.id)
        while (history.size > 50) known.remove(history.removeAt(history.lastIndex))
        persist()
        if (stack.size >= 6) stack.removeAt(0).job?.cancel()
        val entry = Detail(item); stack.add(entry); load(entry)
    }
    fun load(entry: Detail) {
        entry.job?.cancel()
        entry.job = scope.launch {
            entry.loading = true; entry.error = null; entry.people = emptyList()
            try {
                entry.content = BangumiClient.detail(entry.item.id); entry.loading = false
                suspend fun optional(block: suspend () -> Unit) { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { entry.error = "部分关联资料未加载，点击重试" } }
                optional { entry.related = BangumiClient.related(entry.item.id) }
                optional { entry.similar = similarTag(entry.content.tags)?.let { tag -> BangumiClient.search(category = tag).items.filter { it.id != entry.item.id }.take(6) }.orEmpty() }
                for (characters in listOf(false, true)) optional { entry.people = entry.people + BangumiClient.people(entry.item.id, characters) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { entry.error = "详情加载失败，请重试" }
            finally { entry.loading = false }
        }
    }
    fun record(item: BangumiClient.Item, record: Record) {
        if (token.isBlank()) { message = "请先连接 Bangumi 账号"; return }
        if (syncing || saving) return
        val credential = token
        saving = true; savingId = item.id; syncNotice = null
        scope.launch {
            saveGate.withLock {
                try {
                    BangumiClient.saveStatus(item.id, record.type.takeIf { it > 0 } ?: 3, record.rate, credential)
                    if (token == credential) { records[item.id] = record; accountItems[item.id] = item.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap()); persistAccountSnapshot(); notifySynced("已同步到 Bangumi") }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "同步失败，账号状态未更改，请重试" }
                finally { saving = false; savingId = null }
            }
        }
    }
    fun disconnect() {
        accountGeneration++
        accountJob?.cancel(); token = ""; username = ""; avatar = null; records.clear(); accountItems.clear(); syncing = false
        excludedSubjects.clear(); persistAccountSnapshot()
        scope.launch(Dispatchers.IO) {
            credentialGate.withLock {
                try { BangumiCredential.clear(prefs) }
                catch (_: Exception) { withContext(Dispatchers.Main) { message = "凭据清除失败，请重试断开连接" } }
            }
        }
    }
    fun connect(value: String, persistCredential: Boolean = true, silent: Boolean = false) {
        if (syncing || saving) return
        silentSync = silent
        syncing = true; syncProgress = 0; syncNotice = null; message = null
        val generation = ++accountGeneration
        accountJob = scope.launch {
            try {
                val credential = value.trim()
                val me = BangumiClient.account(credential)
                if (persistCredential) withContext(Dispatchers.IO) { credentialGate.withLock { BangumiCredential.save(credential, prefs) } }
                ensureActive()
                if (username != me.username) { records.clear(); accountItems.clear(); excludedSubjects.clear() }
                token = credential; username = me.username; avatar = me.avatar
                persistAccountSnapshot()
                val incoming = mutableListOf<BangumiClient.Collection>()
                var start = 0
                do {
                    val (list, count) = BangumiClient.collections(me.username, credential, start, accountItems.toMap(), excludedSubjects)
                    ensureActive()
                    list.forEach { accountItems[it.item.id] = it.item.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap()); records[it.item.id] = Record(it.type, it.rate) }
                    incoming.addAll(list); syncProgress = incoming.size; start += 50
                    persistAccountSnapshot()
                } while (start < count)
                records.clear(); accountItems.clear()
                incoming.forEach { accountItems[it.item.id] = it.item.copy(summary = "", info = emptyList(), votes = emptyMap(), collections = emptyMap()); records[it.item.id] = Record(it.type, it.rate) }
                persistAccountSnapshot()
                if (!silent) notifySynced("同步完成，共 ${incoming.size} 本漫画")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!silent) message = "账号同步失败，请检查令牌与网络后重试" }
            finally { if (generation == accountGeneration) syncing = false }
        }
    }
}
