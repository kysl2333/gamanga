package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.hostOf
import app.gamanga.desktop.data.normalizeBases
import app.gamanga.desktop.data.renderPath
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 拷贝漫画源适配（第八轮 #9，第十轮域名修正）。
 *
 * **域名（用户 2026-10-24 第十二轮后重新指定）**：
 * - 官方地址：`api.mangacopy.com`（**主**，用户要求用这个，不再用 copy4000）
 * - 备用：`api.copy4000.com`（大陆可达；仅在主域名连接失败时兜底）
 * - 旧 `api.copymanga.com`：大陆 DNS 污染不可达，弃用
 * 连接类失败（超时/拒连/DNS）自动切备用域名重试一次；两个都挂才报错。
 *
 * ⚠️ **2026-10-24 实测：接口可达但数据为空**（不是代码问题）：
 * `search/comic` 与 `comics?ordering=-popular` 都返回 `{code:200, results:{total:0, list:[]}}`，
     * 换 Dart/浏览器 UA、加 region/version/platform/Referer 均无效；网页搜索页同样显示
 * 「漫畫（0）」。这些历史响应不能确定IP/账号绑定或解除时长；当前失败回落网页，
 * 明确210/429后逐步减少API再探，不自动换账号或业务端点绕过拒绝。
 *
 * **接口形态**：v3 JSON API（`/api/v3/...`），公开资料与实测一致：
 * - 搜索：`/api/v3/search/comic?limit=N&q=KW`
 * - 详情：`/api/v3/comic/<path_word>`
 * - 章节：`/api/v3/comic/<path_word>/group/default/chapters?limit=N`
 * - 图片：`/api/v3/comic/<path_word>/chapter2/<uuid>`
 *
 * **请求头**：UA 由 Http 读取源配置，默认桌面 Chrome；另带 region 和网页 Referer。
 * 搜索、详情、取图共用失败冷却。网页取数共享 CEF 会话，原生 API 不等同于网页登录。
 *
 * **章节 id 复合编码**：图片接口要 `<path_word>` + `<uuid>` 两个信息，
 * [SourceChapter.id] 编成 `"<path_word>#<uuid>"`（uuid 与 path_word 都不含 #）。
 */
class CopymangaSource(
    override val type: SourceType = SourceType.COPYMANGA,
    override val displayName: String = "拷贝漫画",
    /** 网页站地址（DOM 模式 / 浏览器打开 / Referer 用；F-22 可配）。 */
    // 与 SourceConfig.DEFAULTS 保持一致（正常启动由 Sources 显式传值）
    webBase: String = "https://www.mangacopy.com",
    /** 接口地址候选（主在前，连接失败按序重试；F-22 可配）。 */
    apiBases: List<String> = listOf("https://api.mangacopy.com", "https://api.copy4000.com"),
    /** 路径模板（F-36）：`search` / `detail` / `detailAlt` / `chapter`；空 = 内置默认。 */
    paths: Map<String, String> = emptyMap(),
    /** 可替换的只读取数边界，离线测试覆盖缓存/冷却的真实调用链。 */
    private val apiReader: (suspend (String) -> String)? = null,
    private val detailReader: (suspend (String) -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchReader: (suspend (String) -> String?)? = null,
) : SourceAdapter {

    private val paths: Map<String, String> = COPY_DEFAULT_PATHS + paths

    private fun path(key: String, vararg vars: Pair<String, String>): String =
        renderPath(paths[key] ?: COPY_DEFAULT_PATHS.getValue(key), vars.toMap())

    private val webBase: String = webBase.trim().trimEnd('/').ifBlank { "https://www.mangacopy.com" }

    /** 用热门书名探活：搜「测试」在拷贝漫画基本必然 0 条。 */
    override val probeKeyword: String = "海贼王"

    /** 内嵌浏览器落地校验用的 host（去掉 `www.`，站点会自己跳 www）。 */
    private val webHost: String = hostOf(this.webBase).removePrefix("www.")

    /** API 地址候选（按序尝试，官方域名优先——用户 2026-10-24 指定）。 */
    private val apiBases: List<String> = normalizeBases(apiBases)
        .ifEmpty { listOf("https://api.mangacopy.com") }

    /**
     * 请求头。
     *
     * 历史请求曾收到code 210及「破解版、等待1小時」文案，
     * 尚未隔离证明UA是触发原因；当前沿用普通浏览器UA。
     * 旧响应不能证明本次登录限制按IP绑定或固定一小时解除。
     * UA 本身交给 [Http]（它按源读 `sources.json` 的自定义 UA，默认就是桌面 Chrome）。
     */
    private fun headers() = mapOf(
        "region" to "1",
        "Referer" to "$webBase/",
    )

    /**
     * 带域名 fallback 的 GET：连接类错误（超时/拒连/DNS/代理 CONNECT 失败）
     * 自动换下一个域名重试。业务错误（4xx/5xx JSON）不重试——换了域名也一样。
     */
    private suspend fun getWithFallback(path: String): String {
        currentCoroutineContext().ensureActive()
        val started = clock()
        val stage = when {
            path.contains("/search/") -> "search"
            path.contains("/chapters?") -> "directory"
            path.contains("/chapter2/") -> "pages"
            else -> "detail"
        }
        try {
            val text = apiReader?.invoke(path)
                ?: readApiText(path)
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw CopyApiFailure("format")
            val code = root.int("code")
            if (code != null && code != 200) {
                throw CopyApiFailure(if (code in setOf(210, 429)) "restricted" else "business", code)
            }
            return text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = when (e) {
                is CopyApiFailure -> e
                is HttpException -> CopyApiFailure(if (e.status in setOf(210, 429)) "restricted" else "http", e.status)
                else -> CopyApiFailure(if (isConnectionFailure(e)) "network" else "parse")
            }
            // 仅记录分类和数字码，不带URL/query、响应正文、cookie或令牌。
            AppLog.w("Copy", "API stage=$stage category=${failure.category} code=${failure.code ?: "none"} elapsed=${clock() - started}ms")
            throw failure
        }
    }

    private suspend fun readApiText(path: String): String {
        var last: Exception? = null
        for (base in apiBases) {
            currentCoroutineContext().ensureActive()
            try {
                return Http.getCopyApiText(base + path, headers())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                // 响应已来自服务器时绝不换域名重试，即使正文含connection/timeout等字样。
                if (e is HttpException || !isConnectionFailure(e)) throw e
            }
        }
        throw last ?: CopyApiFailure("network")
    }

    private val apiLock = Mutex()
    private class SearchGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val searchGates = mutableMapOf<String, SearchGate>()
    private val detailLock = Mutex()
    private var restrictionCount = 0
    private val detailAt = mutableMapOf<String, Long>()
    private val completedLoads = java.util.concurrent.atomic.AtomicLong()
    private val detailRevision = mutableMapOf<String, Long>()

    /** API 失败在搜索/详情/取图间共用冷却；锁内复查避免并发重复探测。 */
    private suspend fun <T> tryApi(read: suspend () -> T?): T? = apiLock.withLock {
        currentCoroutineContext().ensureActive()
        if (!apiWorthTrying()) return@withLock null
        var restricted = false
        val result = try {
            read()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            restricted = e is CopyApiFailure && e.category == "restricted"
            if (e is CopyApiFailure && e.category.startsWith("directory-")) {
                AppLog.w("Copy", "API stage=directory category=${e.category}")
            }
            null
        }
        currentCoroutineContext().ensureActive()
        apiUsable = result != null
        if (result == null) {
            if (restricted) restrictionCount = (restrictionCount + 1).coerceAtMost(4)
            val multiplier = if (restricted) 1L shl (restrictionCount - 1) else 1L
            val cooldown = (API_RETRY_MS * multiplier).coerceAtMost(60 * 60_000L)
            apiRetryAt = clock() + cooldown
            AppLog.i("Copy", "API fallback category=${if (restricted) "restricted" else "unavailable"} cooldown=${cooldown / 60_000}min (client retry interval, not server unlock)")
        } else {
            restrictionCount = 0
            apiRetryAt = 0L
        }
        result
    }

    /** 最近一次API可用性；失败在客户端冷却后由下一次真实调用再探，null表示未探测。 */
    @Volatile
    private var apiUsable: Boolean? = null

    /** 客户端再探测截止时间。10 分钟后下一次请求可再探，不是后台定时器或服务端解封时间。 */
    @Volatile
    private var apiRetryAt: Long = 0L

    /** 现在是否值得再试 API。 */
    private fun apiWorthTrying(): Boolean =
        apiUsable != false || clock() >= apiRetryAt

    /**
     * 详情+章节一次拿全的缓存（详情页 DOM 同时含两者，避免起两次隐藏浏览器）。
     *
     * **LRU 40 本**（2026-10-24 加）：原来是无界 ConcurrentHashMap，浏览越多书内存越大
     * （每本几百条章节对象）。40 本足够覆盖一次连续浏览，超出按最近最少使用淘汰。
     */
    private val detailCache: MutableMap<String, Pair<SourceManga, List<SourceChapter>>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Pair<SourceManga, List<SourceChapter>>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Pair<SourceManga, List<SourceChapter>>>?
                ) = size > 40
            }
        )

    /**
     * 搜索缓存（LRU 20，5 分钟）。
     *
     * 拷贝漫画一次搜索要 6~8 秒（必须过隐藏浏览器），而用户经常反复搜同一个词
     * （搜错了改一下、切走再回来）。命中缓存直接返回，体感是「瞬间出结果」。
     */
    private val searchCache: MutableMap<String, Pair<Long, List<SourceManga>>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Pair<Long, List<SourceManga>>>(8, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Pair<Long, List<SourceManga>>>?
                ) = size > 20
            }
        )

    private fun cachedSearch(key: String): List<SourceManga>? {
        val hit = searchCache[key] ?: return null
        val ttl = if (hit.second.isEmpty()) 15_000L else SEARCH_TTL_MS
        if (clock() - hit.first >= ttl) {
            searchCache.remove(key)
            return null
        }
        return hit.second
    }

    private fun putSearch(key: String, list: List<SourceManga>) {
        searchCache[key] = clock() to list
    }

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
        currentCoroutineContext().ensureActive()
        val cacheKey = "$keyword|$page|$pageSize"
        cachedSearch(cacheKey)?.let { return it }
        val gate = synchronized(searchGates) {
            searchGates.getOrPut(cacheKey) { SearchGate() }.also { it.users++ }
        }
        try {
            return gate.mutex.withLock {
                currentCoroutineContext().ensureActive()
                cachedSearch(cacheKey)?.let { return@withLock it }
                val native = tryApi {
                    val path = "/api/v3/search/comic?limit=$pageSize&offset=${page * pageSize}&q=${pathEncode(keyword)}"
                    parseCopymangaSearch(getWithFallback(path)).takeIf { it.isNotEmpty() }
                }
                if (native != null) {
                    putSearch(cacheKey, native)
                    return@withLock native
                }
                searchViaDom(keyword, pageSize).also { putSearch(cacheKey, it) }
            }
        } finally {
            synchronized(searchGates) {
                if (--gate.users == 0) searchGates.remove(cacheKey)
            }
        }
    }

    override suspend fun detail(mangaId: String): SourceManga? = detailWithChapters(mangaId)?.first

    override suspend fun chapters(mangaId: String): List<SourceChapter> =
        detailWithChapters(mangaId)?.second.orEmpty()

    /**
     * 详情 + 章节一次拿全。
     *
     * 详情页 DOM 里同时有书名（`h6[title]`）和章节列表（`a[href*="/chapter/"]`），
     * 一次浏览器加载就够——分开取会起两次隐藏浏览器（各 8 秒，用户实测「搜索速度慢」）。
     */
    override suspend fun loadDetails(mangaId: String, forceRefresh: Boolean): SourceDetails {
        val pair = detailWithChapters(mangaId, forceRefresh)
        return SourceDetails(pair?.first, pair?.second.orEmpty())
    }

    override suspend fun refreshChapters(mangaId: String): List<SourceChapter> {
        val result = loadDetails(mangaId, forceRefresh = true)
        return result.chapters.takeIf { result.chaptersComplete }.orEmpty()
    }

    private suspend fun detailWithChapters(
        mangaId: String,
        forceRefresh: Boolean = false,
    ): Pair<SourceManga, List<SourceChapter>>? {
        val requestedRevision = completedLoads.get()
        return detailLock.withLock {
            currentCoroutineContext().ensureActive()
            val cached = detailCache[mangaId]
            val at = detailAt[mangaId] ?: 0L
            val complete = cached?.second?.let { it.isNotEmpty() && it.none { c -> c.navigationOnly } } == true
            val ttl = if (complete) SEARCH_TTL_MS else 15_000L
            // 同一时刻排队的强刷共享刚完成的结果；其余强刷始终访问远端。
            if (cached != null && ((!forceRefresh && clock() - at < ttl) || (detailRevision[mangaId] ?: 0L) > requestedRevision)) {
                return@withLock cached
            }
            val native = tryApi {
                val detailJson = getWithFallback("/api/v3/comic/${pathEncode(mangaId)}")
                val manga = parseCopymangaDetail(detailJson)
                if (manga == null) null else {
                    val chapters = readDirectory(mangaId, detailJson)
                    chapters.takeIf { it.isNotEmpty() }?.let { manga to it }
                }
            }
            val result = native ?: run {
                val started = clock()
                val json = sourceOrNull {
                    if (detailReader != null) detailReader.invoke(mangaId) else BrowserProbe.evalJson(
                        url = webBase + path("detail", "id" to pathEncode(mangaId)),
                        expression = COPYMANGA_DETAIL_DOM_JS,
                        timeoutMs = 40_000,
                        expectHost = webHost,
                        settleMs = 3_500,
                        stableRoundsRequired = 3,
                    )
                }
                val parsed = json?.let { parseCopymangaDetailDom(it, mangaId) }
                AppLog.i("Copy", "detail single load (${clock() - started}ms) $mangaId chapters=${parsed?.second?.size ?: 0} named=${parsed?.second?.count { !it.navigationOnly } ?: 0}")
                parsed
            }
            currentCoroutineContext().ensureActive()
            result?.also {
                detailCache[mangaId] = it
                detailAt.keys.retainAll(detailCache.keys.toSet())
                detailAt[mangaId] = clock()
                detailRevision.keys.retainAll(detailCache.keys.toSet())
                detailRevision[mangaId] = completedLoads.incrementAndGet()
            }
        }
    }

    /** 仅在API详情可用后补目录；串行分页，无预取/并发风暴，预算耗尽回落现有网页。 */
    private suspend fun readDirectory(mangaId: String, detailJson: String): List<SourceChapter> =
        kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            val results = Json.parseToJsonElement(detailJson).jsonObject.obj("results")
            val groups = results?.obj("groups")?.keys?.toList()?.ifEmpty { listOf("default") }
                ?: listOf("default")
            if (groups.size > 20) throw CopyApiFailure("directory-bounds")
            val collected = mutableListOf<SourceChapter>()
            var requests = 0
            for (group in groups) {
                var offset = 0
                var expectedTotal: Int? = null
                val seen = mutableSetOf<String>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (++requests > 100) throw CopyApiFailure("directory-bounds")
                    val json = getWithFallback("/api/v3/comic/${pathEncode(mangaId)}/group/${pathEncode(group)}/chapters?limit=100&offset=$offset")
                    val page = Json.parseToJsonElement(json).jsonObject.obj("results")
                        ?: throw CopyApiFailure("directory-format")
                    val list = page.arr("list") ?: throw CopyApiFailure("directory-format")
                    val total = page.int("total")?.takeIf { it >= 0 }
                        ?: throw CopyApiFailure("directory-total")
                    if (expectedTotal != null && expectedTotal != total) throw CopyApiFailure("directory-changed")
                    expectedTotal = total
                    if (page.int("offset")?.let { it != offset } == true) throw CopyApiFailure("directory-offset")
                    val parsed = parseCopyChapterRows(page, mangaId)
                    if (parsed.size != list.size || offset + list.size > total ||
                        parsed.any { !seen.add(it.id) }) throw CopyApiFailure("directory-duplicate-or-missing")
                    collected += parsed
                    offset += list.size
                    if (offset == total) break
                    if (list.isEmpty()) throw CopyApiFailure("directory-stalled")
                }
            }
            // 先恢复源序号，再统一套用户的卷优先规则；绝不按单页重编号后拼接。
            orderCopyChapters(collected.distinctBy { it.id }.sortedWith(compareBy({ it.index }, { it.id })))
        } ?: throw CopyApiFailure("directory-budget")

    override suspend fun pages(chapterId: String): List<PageImage> {
        // 安全拆解「path_word#uuid」复合 id：畸形 id（不含 # / 多段）时返回空，
        // 不让解构抛 IndexOutOfBounds 把整章下载拖成 FAILED（mp-diagnosing-bugs
        // 第九轮自查发现）。正常 id 的契约见类注释。
        val parts = chapterId.split("#")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return emptyList()
        val (pathWord, uuid) = parts
        val native = tryApi {
            val path = "/api/v3/comic/${pathEncode(pathWord)}/chapter2/${pathEncode(uuid)}"
            parseCopymangaPages(getWithFallback(path)).takeIf { it.isNotEmpty() }
        }
        if (native != null) return native
        // DOM：打开章节页，让页面自己的 JS 把图片插进 DOM 再抓（连解密都不用管）
        val chapterUrl =
            webBase + path("chapter", "id" to pathEncode(pathWord), "chapter" to pathEncode(uuid))
        val started = System.currentTimeMillis()

        // **先试直接解密**：页面把整章图片列表放在全局 contentKey 里（AES-CBC），
        // 一次就能拿全，不用滚动、不用等逐张解密。
        val decrypted = sourceOrNull {
            BrowserProbe.evalJson(
                url = chapterUrl,
                expression = COPYMANGA_DECRYPT_JS,
                timeoutMs = 40_000,
                evaluateOnce = true,
                expectHost = webHost,
                settleMs = 1_200,
                lane = 1, // 后台通道：取图/下载不占用交互通道（搜索/详情）
            )
        }
        if (decrypted != null) {
            val fromKey = parseCopymangaDecryptedPages(decrypted)
            if (fromKey.isNotEmpty()) {
                AppLog.i("Copy", "pages via contentKey ok (${System.currentTimeMillis() - started}ms) $chapterId pages=${fromKey.size}")
                return fromKey.map { PageImage(url = it, referer = chapterUrl) }
            }
            AppLog.w("Copy", "decrypt returned no urls, shape=${decrypted.take(200)}")
        }
        val json = sourceOrNull {
            BrowserProbe.evalJson(
                url = chapterUrl,
                expression = COPYMANGA_PAGES_DOM_JS,
                timeoutMs = 60_000,
                evaluateOnce = false, // 反复求值：脚本会滚动触发懒加载，图片逐批插入
                expectHost = webHost,
                // 图片是**逐张解密插入**的：只连续两次相同就收工会停在 3 张
                // （2026-10-24 用户实测「阅读器只能加载 3 张漫画页」）。给 5 轮稳定期。
                stableRoundsRequired = 5,
                settleMs = 3_000,
                lane = 1, // 后台通道
                reusePage = true,
            )
        }
        val cost = System.currentTimeMillis() - started
        if (json == null) {
            AppLog.w("Copy", "pages via DOM failed (${cost}ms) $chapterId")
            return emptyList()
        }
        val urls = parseCopymangaPagesDom(json)
        AppLog.i("Copy", "pages via DOM ok (${cost}ms) $chapterId pages=${urls.size}")
        // 短篇可以少于5页；不为诊断再次导航，失败形态由同页解密结果记录。
        // 图片域也要 Referer（拷贝漫画的 CDN 同样防盗链）
        return urls.map { PageImage(url = it, referer = chapterUrl) }
    }

    /**
     * 打开搜索页、等 JS 渲染完，从 DOM 里抓结果卡片。
     *
     * 使用和手动搜索相同的网页入口与CEF会话；仍可能受到站点限制。
     * 选择器用通用的
     * `a[href*="/comic/"]`（路径里带 path_word），不依赖具体 class 名。
     */
    private suspend fun searchViaDom(keyword: String, limit: Int): List<SourceManga> {
        val started = System.currentTimeMillis()
        val json = sourceOrNull {
            if (searchReader != null) searchReader.invoke(keyword) else BrowserProbe.evalJson(
                url = webBase + path("search", "kw" to pathEncode(keyword)),
                expression = COPYMANGA_SEARCH_DOM_JS,
                timeoutMs = 35_000,
                // 反复求值直到结果长度稳定：搜索结果是 JS 异步渲染的
                evaluateOnce = false,
                expectHost = webHost,
                // 2026-10-29 用户报「长标题搜不到」：日志里出现 `search via DOM ok hits=0`
                // 而页面明明已经渲染出结果卡 —— 根因是 600ms 的 settle 太短，
                // 首两次求值都还是空数组（长度一样 → 判「稳定」→ 提前返回 []）。
                // 现在等 1.2s 起评、并且要连续 3 轮一样才收工。
                settleMs = 1_200,
                stableRoundsRequired = 3,
            )
        }
        val cost = System.currentTimeMillis() - started
        if (json == null) {
            AppLog.w("Copy", "search via DOM failed (${cost}ms) kw=$keyword")
            return emptyList()
        }
        val list = parseCopymangaSearchDom(json)
        AppLog.i("Copy", "search via DOM ok (${cost}ms) kw=$keyword hits=${list.size}")
        return list.take(limit)
    }

}

// ---- 解析纯函数（离线可测；样本结构见 CopymangaSourceTest 的 fixture）----

/**
 * 搜索页 DOM 抓取脚本（隐藏浏览器里执行）。
 *
 * 用 `a[href*="/comic/"]` 通用选择器，不依赖站点 class 名。
 *
 * **标题提取顺序很关键**（2026-10-24 用户实测「搜索结果名字是拼音」）：卡片里的
 * `<a>` 只包封面图、书名在 `<h6 title="鬼滅之刃">` 里，`a.textContent` 是空串，
 * 旧实现就退化成 path_word（拼音）。现在按 title 属性 → img.alt → 上级容器里的
 * 标题元素 → 容器文本 依次取，最后才用 path_word。
 */
/** 路径模板默认值（F-36）：改版时可在 `sources.json` 的 `paths` 段覆盖。 */
private val COPY_DEFAULT_PATHS: Map<String, String> = mapOf(
    "search" to "/search?q={kw}",
    "detail" to "/comic/{id}",
    "detailAlt" to "/comicdetail/{id}",
    "chapter" to "/comic/{id}/chapter/{chapter}",
)

private const val COPYMANGA_SEARCH_DOM_JS = """
(function(){
  function textOf(el){ return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function titleOf(a){
    var t = (a.getAttribute('title') || '').trim();
    if (t) return t;
    var img = a.querySelector('img');
    if (img) {
      var alt = (img.getAttribute('alt') || '').trim();
      if (alt) return alt;
    }
    var p = a.parentElement;
    for (var d = 0; d < 3 && p; d++, p = p.parentElement) {
      var h = p.querySelector('h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]');
      var ht = textOf(h);
      if (ht && ht.length <= 60) return ht;
      var pt = textOf(p);
      if (pt && pt.length <= 40) return pt;
    }
    return '';
  }
  var out = [];
  var seen = {};
  var as = document.querySelectorAll('a[href*="/comic/"]');
  for (var i = 0; i < as.length; i++) {
    var href = as[i].getAttribute('href') || '';
    var m = /\/comic\/([^\/?#]+)/.exec(href);
    if (!m) continue;
    var pw = m[1];
    if (seen[pw]) continue;
    var t = titleOf(as[i]);
    var img = as[i].querySelector('img');
    var cover = img ? (img.getAttribute('data-src') || img.getAttribute('src') || '') : '';
    if (!t && !cover) continue;
    seen[pw] = 1;
    out.push({sourceId: pw, title: t, coverUrl: cover || null});
  }
  return out;
})()
"""

/**
 * 详情页 DOM 抓取脚本：书名 + 封面 + 作者 + 章节列表一次拿全。
 *
 * 真实结构（curl 实测 `/comic/guimiezhiren`，15KB）：书名在
 * `<h6 title="鬼滅之刃">鬼滅之刃</h6>`；章节列表由 JS 异步插入，链接形如
 * `/comic/<path_word>/chapter/<uuid>`（详情页 HTML 里只有一个「开始阅读」按钮，
 * 全量章节必须等渲染）。
 */
private const val COPYMANGA_DETAIL_DOM_JS = """
(function(){
  // **先滚到底**：章节列表在详情页下方且懒加载，不滚动时 DOM 里只有「開始閱讀」
  // 这一个 chapter 链接（2026-10-24 用户实测「详情页章节还是不对，显示的开始阅读」）。
  try { window.scrollTo(0, document.body.scrollHeight); } catch (e) {}
  // 有些模板默认只渲染最近几话，需要点「全部章节 / 展开」才出全量
  try {
    var cand = document.querySelectorAll('a,button,span,div');
    for (var ci = 0; ci < cand.length && ci < 400; ci++) {
      var ct = (cand[ci].textContent || '').trim();
      if (ct.length <= 8 && (ct === '全部章节' || ct === '展开全部' || ct === '查看全部' || ct === '全部話' || ct === '全部话')) {
        cand[ci].click();
        break;
      }
    }
  } catch (e) {}
  function textOf(el){ return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  var title = '';
  var h = document.querySelector('h6[title]');
  if (h) title = (h.getAttribute('title') || textOf(h)).trim();
  if (!title) {
    var og = document.querySelector('meta[property="og:title"]');
    if (og) title = (og.getAttribute('content') || '').trim();
  }
  var cover = '';
  var imgs = document.querySelectorAll('img');
  for (var i = 0; i < imgs.length; i++) {
    var s = imgs[i].getAttribute('data-src') || imgs[i].getAttribute('src') || '';
    if (!s || s.indexOf('/static/') >= 0 || s.indexOf('data:') === 0) continue;
    cover = s;
    break;
  }
  var author = '';
  var ae = document.querySelector('a[href*="/author/"], [class*=author] a');
  if (ae) author = textOf(ae);
  var out = [];
  var seen = {};
  var as = document.querySelectorAll('a[href*="/chapter/"]');
  for (var j = 0; j < as.length; j++) {
    var href = as[j].getAttribute('href') || '';
    var m = /\/chapter\/([0-9a-zA-Z-]+)/.exec(href);
    if (!m) continue;
    var uuid = m[1];
    // 同一uuid可能先有导航按钮、后有真实名称，交给Kotlin统一选名。
    var name = (as[j].getAttribute('title') || textOf(as[j]) || '').replace(/\s+/g, ' ').trim();
    out.push({id: uuid, name: name});
  }
  // **不要因为章节少就 return null**（2026-10-24 第十七轮的教训）：
  // 那样会把「站点只给 1 条」误判成「还没加载完」，一直轮询到 40 秒超时，
  // 结果整页空白（日志：detail via DOM failed (38680ms)）。
  // 现在改成「有多少给多少」：拿不到章节也要让标题/封面显示出来，
  // 章节不足由 UI 提示 + 浏览器兜底，而不是整页失败。
  return {title: title, cover: cover, author: author, chapters: out};
})()
"""

/**
 * **直接解密图片列表**（2026-10-24 逆向所得，用户要求「破解这套系统」）。
 *
 * 从 `comic_content_pass202508141534.js`（base62 packer，已解包）得到的真实逻辑：
 *
 * ```js
 * var iv      = contentKey.substring(0, 16);        // 前 16 字符当 IV
 * var cipher  = contentKey.substring(16);           // 其余是 hex 密文
 * var key     = CryptoJS.enc.Utf8.parse(cct);       // cct = 全局密钥
 * var plain   = CryptoJS.AES.decrypt(
 *                 CryptoJS.enc.Base64.stringify(CryptoJS.enc.Hex.parse(cipher)),
 *                 key, {iv: iv, mode: CBC, padding: Pkcs7}
 *               ).toString(Utf8).trim();
 * JSON.parse(plain);                                // ← 整章图片列表
 * ```
 *
 * `contentKey` / `cct` 都是页面内联脚本定义的**全局变量**，所以在页面上下文里
 * 用 WebCrypto（AES-CBC 自动处理 PKCS7）就能复现，一次拿到**全部**图片，
 * 完全绕开「逐张解密 + 滚动懒加载」（那正是只抓到 3~4 张的原因）。
 */
private const val COPYMANGA_DECRYPT_JS = """
(async function(){
  // **自动发现密文与密钥，不写死变量名**（2026-10-24）：
  // 站点一旦把 contentKey/cct 改名，写死名字的版本就会失效、又得逆向一遍。
  // 这里改成：已知名优先 → 扫描 window 上所有符合「16 字符 + 长 hex」的字符串当密文、
  // 「16/24/32 字符」的字符串当密钥 → 组合试解密，成功即用。
  // 解不出来就把候选名清单返回，日志里能直接看到该改成什么。
  function pushUnique(arr, name, value) {
    if (typeof value !== 'string' || !value) return;
    for (var i = 0; i < arr.length; i++) if (arr[i].value === value) return;
    arr.push({name: name, value: value});
  }
  var cks = [], keys = [];
  try { if (typeof contentKey === 'string') pushUnique(cks, 'contentKey', contentKey); } catch (e) {}
  try { if (typeof cct === 'string') pushUnique(keys, 'cct', cct); } catch (e) {}

  var names = [];
  try { names = Object.getOwnPropertyNames(window); } catch (e) { names = []; }
  var scan = Math.min(names.length, 3000);
  // 密文候选：长度 > 100，前 16 位可见字符 + 后面是 hex
  for (var i = 0; i < scan && cks.length < 6; i++) {
    var v;
    try { v = window[names[i]]; } catch (e) { continue; }
    if (typeof v !== 'string' || v.length < 100) continue;
    if (/^[0-9a-zA-Z]{16}[0-9a-fA-F]{32,}$/.test(v)) pushUnique(cks, names[i], v);
  }
  // 密钥候选：AES 合法长度
  for (var j = 0; j < scan && keys.length < 10; j++) {
    var v2;
    try { v2 = window[names[j]]; } catch (e) { continue; }
    if (typeof v2 !== 'string') continue;
    if (v2.length === 16 || v2.length === 24 || v2.length === 32) pushUnique(keys, names[j], v2);
  }

  var enc = new TextEncoder();
  for (var a = 0; a < cks.length; a++) {
    var ck = cks[a].value;
    var ivPart = ck.substring(0, 16);
    var hexPart = ck.substring(16);
    if (hexPart.length % 2 !== 0 || !/^[0-9a-fA-F]+$/.test(hexPart)) continue;
    var bytes;
    try {
      bytes = new Uint8Array(hexPart.match(/../g).map(function(h){ return parseInt(h, 16); }));
    } catch (e) { continue; }
    for (var b = 0; b < keys.length; b++) {
      try {
        var key = await crypto.subtle.importKey(
          'raw', enc.encode(keys[b].value), {name: 'AES-CBC'}, false, ['decrypt']);
        var plain = await crypto.subtle.decrypt({name: 'AES-CBC', iv: enc.encode(ivPart)}, key, bytes);
        var obj = JSON.parse(new TextDecoder().decode(plain));
        return {ok: true, ckName: cks[a].name, keyName: keys[b].name, data: obj};
      } catch (e) { /* 组合不对，继续试 */ }
    }
  }
  return {
    ok: false,
    ckCandidates: cks.map(function(x){ return x.name + ':' + x.value.length; }),
    keyCandidates: keys.map(function(x){ return x.name + ':' + x.value.length; }),
    globals: names.length
  };
})()
"""

/**
 * 章节页 DOM 抓取脚本：等页面自己的 JS 把图片插进 DOM，直接抓 img 的地址。
 *
 * 章节页 HTML 只有 7.4KB、没有任何漫画 img——图片全部由前端脚本
 * （`comic_content_pass*.js`）解密后插入，所以「让页面自己跑」比逆向接口省事。
 * 每次求值先滚到底：懒加载只渲染视口内的图。
 */
private const val COPYMANGA_PAGES_DOM_JS = """
(function(){
  function ok(s){
    if (!s) return false;
    if (s.indexOf('data:') === 0) return false;
    if (s.indexOf('/static/') >= 0) return false;
    if (s.indexOf('/ads/') >= 0) return false;
    if (s.indexOf('mangafunb.fun/static') >= 0) return false;
    return true;
  }
  function add(out, s){
    if (!ok(s)) return;
    if (s.indexOf('//') === 0) s = location.protocol + s;
    if (out.indexOf(s) < 0) out.push(s);
  }
  var out = [];
  var imgs = document.querySelectorAll('img');
  for (var i = 0; i < imgs.length; i++) {
    add(out, imgs[i].getAttribute('data-src'));
    add(out, imgs[i].getAttribute('data-original'));
    add(out, imgs[i].getAttribute('src'));
  }
  var ss = document.querySelectorAll('source[srcset], img[srcset]');
  for (var j = 0; j < ss.length; j++) {
    var v = (ss[j].getAttribute('srcset') || '').split(',')[0];
    if (v) add(out, v.trim().split(' ')[0]);
  }
  var bg = document.querySelectorAll('[style*="background-image"]');
  for (var k = 0; k < bg.length && k < 60; k++) {
    var m = /url\(['"]?([^'")]+)['"]?\)/.exec(bg[k].getAttribute('style') || '');
    if (m) add(out, m[1]);
  }
  // **滚动位置必须「按当前 scrollY 推进」，不能按已抓图片数算**（2026-10-24 修）：
  // 上一版写的是 `y = out.length * vh`——图片数卡在 3 时每次都滚到同一位置（3 屏），
  // 页面再也不动，图片自然永远是 3 张（用户实测「还是只能抓 3 张，但在内嵌浏览器里
  // 随便拖动就能看全」）。站点脚本监听 window 的 scroll（混淆脚本里有 `onscroll`/
  // `scrollY`），所以每轮真往前推一点，它才会继续解密下一页。
  try {
    var vh = window.innerHeight || 800;
    var cur = window.scrollY || window.pageYOffset || 0;
    var max = Math.max(
      document.body ? document.body.scrollHeight : 0,
      document.documentElement ? document.documentElement.scrollHeight : 0
    ) - vh;
    // 每轮推进两屏：16 页的章节约 6~8 轮到底，配合 5 轮稳定期约 8~10 秒
    var y = cur + vh * 2;
    if (y > max) y = max;
    window.scrollTo(0, y);
    // 有些模板用内部容器滚动，一并推一下（无害）
    var boxes = document.querySelectorAll('div,section,main,article');
    for (var bi = 0; bi < boxes.length && bi < 200; bi++) {
      var el = boxes[bi];
      if (el.scrollHeight > el.clientHeight + 100 && el.scrollTop + el.clientHeight < el.scrollHeight - 4) {
        el.scrollTop = el.scrollTop + vh * 2;
      }
    }
  } catch (e) {}
  // 同理：0 张也照实返回空数组，让上层显示「没有图片 / 去浏览器看」，
  // 不要靠 null 死等（40 秒超时对用户毫无意义）。
  return out;
})()
"""

/** 搜索缓存有效期（毫秒）。 */
private const val SEARCH_TTL_MS = 5 * 60_000L

/** API 失败后的客户端再探测间隔，不代表站点解除限制所需时间。 */
private const val API_RETRY_MS = 10 * 60_000L

/** 是不是「開始閱讀 / 继续阅读」这类导航按钮（不是真章节名）。 */
private fun isNavButtonName(name: String): Boolean {
    val t = name.trim()
    if (t.contains("開始閱讀") || t.contains("开始阅读")) return true
    return Regex("""^(開始|开始)?\s*(閱讀|阅读|继续阅读|繼續閱讀)$""").containsMatchIn(t)
}

/** 同时识别阿拉伯数字和站点使用的中文卷/话编号。 */
private fun copyNumberOf(name: String): Double? {
    Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toDoubleOrNull()?.let { return it }
    val token = Regex("""第\s*([零〇一二三四五六七八九十百千万两兩]+)\s*[卷冊册话話]""")
        .find(name)?.groupValues?.get(1) ?: return null
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    var total = 0
    var section = 0
    var number = 0
    for (c in token) {
        when {
            c in digits -> number = number * 10 + digits.getValue(c)
            c in units -> { section += (if (number == 0) 1 else number) * units.getValue(c); number = 0 }
            c == '万' -> { total += (section + number) * 10_000; section = 0; number = 0 }
        }
    }
    return (total + section + number).toDouble()
}

/** 章节名是不是「卷」。交给通用分类器，避免源内源外两套正则各说各话。 */
internal fun isCopyVolume(name: String): Boolean = chapterKindOf(name) == ChapterKind.VOLUME

/** 卷、话、无编号附录；同组升序，编号相同时保留站点顺序。 */
private fun orderCopyChapters(chapters: List<SourceChapter>): List<SourceChapter> =
    chapters.sortedWith(compareBy<SourceChapter>(
        { it.kind.sortRank() },
        { copyNumberOf(it.name) ?: 0.0 },
    )).mapIndexed { i, ch -> ch.copy(index = i) }

/**
 * 详情页 DOM 结果 → (漫画, 章节)。
 *
 * 结构：`{title, cover, author, chapters:[{id(uuid), name}]}`。
 * 章节 id 在这里拼成 `"<path_word>#<uuid>"`（与 API 路径同一契约，pages 靠它拆解）。
 * 站点可能「新话在前」——首项编号大于末项就整体反转，并重算 index。
 */
internal fun parseCopymangaDetailDom(
    json: String,
    mangaId: String,
): Pair<SourceManga, List<SourceChapter>>? = runCatching {
    val o = Json.parseToJsonElement(json).jsonObject
    val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return null
    val raw = o.arr("chapters") ?: return SourceManga(sourceId = mangaId, title = title) to emptyList()
    // **「開始閱讀」是导航按钮，不是章节**（2026-10-24 用户截图实测）：
    // 它和「第1話」指向同一个 uuid，旧实现按 DOM 顺序 distinctBy 后保留了按钮那条，
    // 于是列表底部多出一个「開始閱讀」、而「第1話」不见了（点按钮确实进第1话）。
    // 按 uuid 分组优先保留真实目录名；只有按钮时标记阅读入口，不猜卷/话编号。
    val grouped = raw.mapNotNull { el ->
        val c = el.jsonObject
        val uuid = c.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = c.str("name").orEmpty().trim()
        uuid to name
    }.groupBy({ it.first }, { it.second })
    val chapters = grouped.map { (uuid, names) ->
        val real = names.firstOrNull { it.isNotBlank() && !isNavButtonName(it) }
        SourceChapter(
            id = "$mangaId#$uuid", name = real ?: "阅读入口", index = 0,
            navigationOnly = real == null,
            kind = real?.let { chapterKindOf(it) } ?: ChapterKind.UNKNOWN,
        )
    }
    // **按章节号排序，而不是靠 DOM 顺序/整体反转**（2026-10-24 用户截图实测）：
    // 站点返回的顺序是「最新话置顶 + 特别短篇置顶 + 其余正序」——
    // 例如 [第206話, 第206話特別短篇, 第2話, 第3話, …]，首末编号比较根本判不出来，
    // 结果详情页显示成 206 → 短篇 → 2 → 3…，中间还插一条「缺失 3-205」的假提示。
    // 卷优先，再按话号升序，最后保留无编号附录。
    val indexed = orderCopyChapters(chapters)
    SourceManga(
        sourceId = mangaId,
        title = title,
        coverUrl = o.str("cover")?.takeIf { it.isNotBlank() },
        author = o.str("author")?.takeIf { it.isNotBlank() },
    ) to indexed
}.getOrNull()

/**
 * 解密后的 JSON → 图片 URL 列表。
 *
 * 结构没有公开文档，所以**递归收集所有 http(s) 字符串**：不管包成
 * `[...]` / `{contents:[...]}` / `{path, url}` 哪种形状，图片地址一定是 http(s) 开头的
 * 字符串。按出现顺序去重（顺序即页序）。
 */
internal fun parseCopymangaDecryptedPages(json: String): List<String> = runCatching {
    val out = mutableListOf<String>()
    fun walk(el: kotlinx.serialization.json.JsonElement) {
        when (el) {
            is kotlinx.serialization.json.JsonArray -> el.forEach { walk(it) }
            is kotlinx.serialization.json.JsonObject -> el.values.forEach { walk(it) }
            is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { if (it !in out) out += it }
            else -> Unit
        }
    }
    walk(Json.parseToJsonElement(json))
    out
}.getOrDefault(emptyList())

/** 章节页 DOM 结果 → 图片地址数组（保持 DOM 顺序 = 页序）。 */
internal fun parseCopymangaPagesDom(json: String): List<String> = runCatching {
    Json.parseToJsonElement(json).jsonArray
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .filter { it.isNotBlank() }
        .distinct()
}.getOrDefault(emptyList())

/** DOM 抓取结果 → [SourceManga]（结构：`[{sourceId,title,coverUrl}]`）。 */
internal fun parseCopymangaSearchDom(json: String): List<SourceManga> = runCatching {
    Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
        val o = el.jsonObject
        val id = o.str("sourceId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SourceManga(
            sourceId = id,
            title = o.str("title")?.takeIf { it.isNotBlank() } ?: id,
            coverUrl = o.str("coverUrl")?.takeIf { it.isNotBlank() },
        )
    }
}.getOrDefault(emptyList())

/**
 * 搜索响应 → 漫画列表。
 *
 * 结构：`{code, results:{list:[{name, path_word, cover, author:[{name}], popular, ...}]}}`。
 */
internal fun parseCopymangaSearch(json: String): List<SourceManga> = runCatching {
    val list = Json.parseToJsonElement(json).jsonObject.obj("results")?.arr("list")
        ?: return emptyList()
    return list.mapNotNull { el ->
        val o = el.jsonObject
        val name = o.str("name") ?: return@mapNotNull null
        val pathWord = o.str("path_word") ?: return@mapNotNull null
        SourceManga(
            sourceId = pathWord,
            title = name,
            coverUrl = o.str("cover"),
            author = o.arr("author")?.mapNotNull { it.jsonObject.str("name") }
                ?.joinToString("、")?.ifBlank { null },
        )
    }
}.getOrDefault(emptyList())

/**
 * 详情响应 → 漫画（补齐简介/状态/标签）。
 *
 * 结构：`{code, results:{comic:{name, path_word, cover, author, description, ...}}}`。
 * 字段名按 v3 惯例；站点改版时这里最先不同（未实测，见类注释）。
 */
internal fun parseCopymangaDetail(json: String): SourceManga? = runCatching {
    val comic = Json.parseToJsonElement(json).jsonObject.obj("results")?.obj("comic")
        ?: return null
    val name = comic.str("name") ?: return null
    val pathWord = comic.str("path_word") ?: return null
    return SourceManga(
        sourceId = pathWord,
        title = name,
        coverUrl = comic.str("cover"),
        author = comic.arr("author")?.mapNotNull { it.jsonObject.str("name") }
            ?.joinToString("、")?.ifBlank { null },
        description = comic.str("description") ?: comic.str("brief"),
        status = comic.obj("restrict")?.str("display"),
    )
}.getOrNull()

/**
 * 章节列表响应 → [SourceChapter]（按 index 升序 = 第 1 话在前）。
 *
 * 结构：`{code, results:{list:[{index, uuid, name, ordered, ...}]}}`。
 * id 编成 `"<path_word>#<uuid>"`（图片接口要两个信息）。
 */
internal fun parseCopymangaChapters(json: String): List<SourceChapter> = runCatching {
    val results = Json.parseToJsonElement(json).jsonObject.obj("results") ?: return emptyList()
    orderCopyChapters(parseCopyChapterRows(results).sortedWith(compareBy({ it.index }, { it.id })))
}.getOrDefault(emptyList())

private fun parseCopyChapterRows(results: JsonObject, requestedId: String? = null): List<SourceChapter> =
    results.arr("list").orEmpty().mapNotNull { el ->
        val o = el.jsonObject
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val uuid = o.str("uuid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val pathWord = requestedId ?: results.str("comic_path_word") ?: o.str("comic_path_word")
            ?: return@mapNotNull null
        SourceChapter(
            id = "$pathWord#$uuid", name = name, index = o.int("ordered") ?: o.int("index") ?: 0,
            pageCount = o.int("size") ?: 0,
            kind = chapterKindOf(name),
        )
    }

/**
 * 章节图片响应 → 页列表（contents[].url，顺序即页序）。
 *
 * 结构：`{code, results:{chapter:{contents:[{url}, ...]}}}`。
 */
internal fun parseCopymangaPages(json: String): List<PageImage> = runCatching {
    val chapter = Json.parseToJsonElement(json).jsonObject.obj("results")?.obj("chapter")
        ?: return emptyList()
    val contents = chapter.arr("contents") ?: return emptyList()
    val pages = contents.map { el ->
        val url = el.jsonObject.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return emptyList()
        PageImage(url = url)
    }
    // 旧格式不带words时保持原顺序；带了就必须是完整的0-based排列，异常交给网页回落。
    if ("words" !in chapter) return pages
    val words = chapter.arr("words") ?: return emptyList()
    val order = words.map { it.jsonPrimitive.contentOrNull?.toIntOrNull() ?: return emptyList() }
    if (order.size != pages.size || order.toSet() != pages.indices.toSet()) return emptyList()
    pages.indices.sortedBy { order[it] }.map { pages[it] }
}.getOrDefault(emptyList())

private class CopyApiFailure(val category: String, val code: Int? = null) : RuntimeException("Copy API $category")
