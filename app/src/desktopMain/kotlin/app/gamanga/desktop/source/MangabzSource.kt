package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.normalizeBases
import app.gamanga.desktop.data.renderPath
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * MangaBZ（mangabz.com）源适配（第八轮 #9）。
 *
 * **站点形态**（社区爬虫实现一致，见 CSDN 2023 实战文等）：
 * - 搜索：`GET /search?title=KW`（HTML 结果页）
 * - 漫画详情：`GET /<mid>bz/`（HTML，标题/作者/简介 + 章节列表）
 * - 章节翻页器：`GET /m<chapter_code>-p<page>`（HTML，内嵌
 *   `MANGABZ_CID=` / `MANGABZ_MID=` / `MANGABZ_VIEWSIGN_DT="..."` /
 *   `MANGABZ_VIEWSIGN="..."`）
 * - 图片地址接口：`GET /m<chapter_code>/chapterimage.ashx?cid&page&key&_cid&_mid&_dt&_sign`
 *   （带 Referer）→ 响应体形如 `12_abc123|`，取 `|` 前段拼
 *   `https://image.mangabz.com/2/<MID>/<CID>/<key>.jpg`
 *
 * ⚠️ **未实测**：开发机当前网络无法访问 mangabz.com（DNS 异常），
 * 接口路径与字段按公开资料实现、解析逻辑有离线单测，但**没有跑过真实
 * 请求**。图片列表是「一页一次 asHx」形态（页数未知，探测到 999 上限），
 * 比 JSON 型站点慢；首次使用如遇结构不符，把 logs/gamanga.log 反馈即可。
 */
class MangabzSource(
    override val type: SourceType = SourceType.MANGABZ,
    override val displayName: String = "MangaBZ",
    /** 站点候选（主站在前，连不上换下一个）。 */
    // 与 SourceConfig.DEFAULTS 保持一致（正常启动由 Sources 显式传值）
    siteBases: List<String> = listOf("https://www.mangabz.com"),
    /** 路径模板（F-36）：`search` / `detail`；空 = 内置默认。 */
    paths: Map<String, String> = emptyMap(),
    private val textReader: (suspend (String) -> String)? = null,
) : SourceAdapter {

    private val bases: List<String> = normalizeBases(siteBases).ifEmpty { listOf("https://www.mangabz.com") }

    private val base: String get() = bases.first()

    /** 「的」在 MangaBZ 有结果（实测 12 条），「测试」不一定。 */
    override val probeKeyword: String = "的"

    private val paths: Map<String, String> = MANGABZ_DEFAULT_PATHS + paths

    /** 渲染一条路径模板（`{kw}`/`{id}`）。 */
    private fun pathOf(key: String, vararg vars: Pair<String, String>): String =
        renderPath(paths[key] ?: MANGABZ_DEFAULT_PATHS.getValue(key), vars.toMap())

    /**
     * 站点 HTML / asHx 请求（带换域名重试）。
     *
     * UA 交给 [Http]（它按源读 `sources.json` 的自定义 UA），这里只补 Referer。
     */
    private suspend fun getText(relative: String, referer: String? = null): String =
        textReader?.invoke(relative) ?: fetchTextWithFallback(bases, relative, type, "MangaBZ") {
            referer?.let { r -> mapOf("Referer" to r) } ?: emptyMap()
        }

    /**
     * 统一失败记日志（第十轮收尾）：用户报「搜索有问题/没有结果」时，
     * logs/gamanga.log 里有失败阶段与原因可查（此前搜索失败无任何记录）。
     */
    private suspend fun <T> logged(op: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.e("MangaBZ", "$op failed", e)
            throw e
        }

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> =
        logged("search '$keyword'") {
            val html = getText(pathOf("search", "kw" to pathEncode(keyword)))
            parseMangabzSearch(html)
        }

    override suspend fun detail(mangaId: String): SourceManga? = detailWithChapters(mangaId)?.first

    override suspend fun chapters(mangaId: String): List<SourceChapter> =
        detailWithChapters(mangaId)?.second ?: emptyList()

    override suspend fun loadDetails(mangaId: String, forceRefresh: Boolean): SourceDetails {
        val result = detailWithChapters(mangaId)
        return SourceDetails(result?.first, result?.second.orEmpty())
    }

    /**
     * 详情页一次拿全（标题信息 + 章节列表）——搜索进详情只发一次请求。
     * mangaId 是站内 mid（详情页 URL `/<mid>bz/` 的数字部分）。
     */
    private suspend fun detailWithChapters(mangaId: String): Pair<SourceManga, List<SourceChapter>>? =
        logged("detail $mangaId") {
            val html = getText(pathOf("detail", "id" to pathEncode(mangaId)))
            parseMangabzDetail(html, mangaId)
        }

    /**
     * 章节图片列表。
     *
     * **2026-10-24 实测推翻旧实现**（用户第十二轮反馈「阅读器一直转圈、只有 1 页」）：
     * asHx 返回的**不是** `12_abc.jpg|` 这种单页 key，而是一段 **JS packer 混淆代码**：
     *
     * ```
     * function dm5imagefun(){
     *   var cid=528110; var key='b70dc2f7...';
     *   var pix="https://image.mangabz.com/1/157/528110";
     *   var pvalue=["/1_1273.jpg","/2_7597.jpg"];      // ← 一次给 2 页
     *   for(var i=0;i<pvalue.length;i++){pvalue[i]=pix+pvalue[i]+'?cid=528110&key=...&uk='}
     *   return pvalue
     * }
     * ```
     *
     * 三条与旧实现的差异（都是「1 页 + 一直转圈」的直接原因）：
     *  1. 要解 packer 才拿得到 `pix` / `key` / `pvalue`（旧代码找 `|`，在压缩文本里
     *     命中的是字典分隔符，拼出的 URL 是 `.../eval(function(...).jpg` 这种垃圾）；
     *  2. 图片域路径是 `pix` 里自带的 `https://image.mangabz.com/1/<mid>/<cid>`，
     *     **不是** 旧代码硬拼的 `/2/<mid>/<cid>/<key>.jpg`；
     *  3. 一次返回 2 页（`page=N` → 第 N、N+1 页），末页只回 1 条，**超界不 404
     *     而是夹取到最后一页**——所以边界判定只能看「最大页号不再增长」，不能靠状态码。
     *
     * 实测（m528110，16P）：page=1 → 1,2；page=15 → 15,16；page=17 → 仍回 16。
     * 每批 2 页 → 16 页只需 8 次请求（旧实现每页 2 次 = 32 次，还全错）。
     */
    override suspend fun pages(chapterId: String): List<PageImage> =
        logged("pages $chapterId") { pagesInner(chapterId) }

    private suspend fun pagesInner(chapterId: String): List<PageImage> = coroutineScope {
        // 翻页器第 1 页拿签名（cid/mid/dt/sign），整章复用；过期了下面会重取一次
        var sign = chapterViewSign(chapterId, 1) ?: return@coroutineScope emptyList()
        val found = sortedMapOf<Int, String>() // 页号 → 图片 URL（sortedMap 天然按页序）
        var next = 1
        var lastMax = 0
        var rounds = 0
        // 每轮并发 6 批（12 页），轮内乱序回来也无所谓——按页号并进 found
        val gate = Semaphore(MANGABZ_BATCH_CONCURRENCY)
        while (next <= MANGABZ_MAX_PAGES && rounds++ < MANGABZ_MAX_ROUNDS) {
            val batchStarts = (0 until MANGABZ_BATCH_CONCURRENCY)
                .map { next + it * 2 }
                .filter { it <= MANGABZ_MAX_PAGES }
            if (batchStarts.isEmpty()) break
            val results = batchStarts.map { start ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            imageBatch(chapterId, start, sign)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e.isNonRetryableSourceError) throw e
                            null
                        }
                    }
                }
            }.awaitAll()
            val ok = results.filterNotNull().flatten()
            if (ok.isEmpty()) {
                // 整轮失败：sign 可能过期（dt 有时效），重取一次再试
                if (rounds == 1) {
                    sign = chapterViewSign(chapterId, 1) ?: return@coroutineScope emptyList()
                    continue
                }
                break
            }
            val maxPage = ok.maxOf { it.page }
            // 超界会夹取到最后一页 → 最大页号不再增长即到末尾
            if (maxPage <= lastMax) break
            ok.filter { it.page > lastMax }.forEach { found[it.page] = it.url }
            lastMax = maxPage
            next = maxPage + 1
        }
        // **必须带 Referer**：image.mangabz.com 是防盗链的，不带
        // `Referer: https://www.mangabz.com/m<cid>/` 直接 403（2026-10-24 实测，
        // 日志铁证「HTTP 403 @ .../1_3345.jpg」）。浏览器里能看图正是因为它自动带了。
        val referer = "$base/m${pathEncode(chapterId)}/"
        found.values.map { PageImage(url = it, referer = referer) }
    }

    /**
     * 取一批（2 页）图片：请求 asHx → 解 packer → 拼真实图片 URL。
     * 返回本批的 (页号, URL)，按页号升序。
     */
    private suspend fun imageBatch(
        chapterCode: String,
        page: Int,
        sign: MangabzViewSign,
    ): List<MangabzImageRef> {
        val path = "/m${pathEncode(chapterCode)}/chapterimage.ashx" +
            "?cid=${sign.cid}&page=$page&key=&_cid=${sign.cid}&_mid=${sign.mid}" +
            "&_dt=${pathEncode(sign.dt)}&_sign=${pathEncode(sign.sign)}"
        val text = getText(path, referer = "$base/m$chapterCode")
        return parseMangabzImageBatch(text)
    }

    /**
     * 翻页器 HTML 里的视图签名（cid/mid/dt/sign）。
     *
     * 404 = 页数边界（该话没有这一页，如 16P 的话探到 p17）——返回 null
     * 让探测自然停止，不当错误抛出（第十二轮 #5：用户实测阅读器
     * 「取图失败 HTTP 404 @ /m528110-p17」，其实就是探测超界）。
     */
    private suspend fun chapterViewSign(chapterCode: String, page: Int): MangabzViewSign? {
        val html = try {
            getText("/m${pathEncode(chapterCode)}-p$page")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNonRetryableSourceError && !e.isGoneError) throw e
            // 404（页数边界）与其他网络失败都视为「这一页没有」——
            // 探测循环靠 null 收尾，不中断整章取图
            return null
        }
        return parseMangabzViewSign(html)
    }

    // 注：旧的 chapterImageUrl（硬拼 image.mangabz.com/2/<mid>/<cid>/<key>.jpg）已删除——
    // 真实图片前缀由 asHx 解包后的 pix 给出（/1/<mid>/<cid>），路径与后缀都不同。
}

/** 路径模板默认值（F-36）：改版时可在 `sources.json` 的 `paths` 段覆盖。 */
private val MANGABZ_DEFAULT_PATHS: Map<String, String> = mapOf(
    "search" to "/search?title={kw}",
    "detail" to "/{id}bz/",
)

/** 一批图片引用：页号 + 可直接加载的完整 URL。 */
data class MangabzImageRef(val page: Int, val url: String)

/** 每轮并发的 asHx 批次数（每批 2 页 → 一轮 12 页）。 */
private const val MANGABZ_BATCH_CONCURRENCY = 6

/** 轮数上限：12 页/轮 × 100 = 1200 页，足够覆盖任何章节（防死循环）。 */
private const val MANGABZ_MAX_ROUNDS = 100

/** 单页探测上限（社区实现同款 999；正常漫画远到不了）。 */
private const val MANGABZ_MAX_PAGES = 999

/** 翻页器 HTML 里的视图签名四件套。 */
data class MangabzViewSign(val cid: String, val mid: String, val dt: String, val sign: String)

// ---- 解析纯函数（离线可测）----

/**
 * 搜索页 → 漫画列表。
 *
 * 真实结构（2026-10-23 实测 `www.mangabz.com/search?title=黑执事`）：
 * 每个结果一个 `<li>` 包一个 `class="mh-item"` 块——封面 a（内嵌
 * `img.mh-cover`）+ `div.mh-item-detali` 里的 `h2.title`（标题 a，
 * a 内是纯文本）+ 最新章节链接。
 *
 * 历史坑（第十一轮 #1）：旧实现按 `<a>` 粒度解析——封面 a 的尾部空白被
 * 误提成标题（先入列表），真标题 a 反而提取不到被丢弃，用户看到
 * 「有封面没文字」。现在按条目块解析：mid/封面/标题各取一次。
 */
internal fun parseMangabzSearch(html: String): List<SourceManga> {
    // 条目块：class="mh-item" 到 </li>（站点每条结果一个 li）
    val item = Regex("""class="mh-item"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    return item.findAll(html).mapNotNull { m ->
        val block = m.groupValues[1]
        val mid = Regex("""href="/(\d+)bz/"""").find(block)?.groupValues?.get(1)
            ?: return@mapNotNull null
        val title = Regex("""class="title"[^>]*>\s*<a[^>]*>([^<]+)</a>""").find(block)
                ?.groupValues?.get(1)?.trim()
            ?: Regex("""<h2[^>]*>\s*<a[^>]*>([^<]+)</a>""").find(block)
                ?.groupValues?.get(1)?.trim()
            ?: return@mapNotNull null
        val cover = Regex("""class="mh-cover"[^>]*src="([^"]+)"""").find(block)
                ?.groupValues?.get(1)
            ?: Regex("""src="([^"]+)"""").find(block)?.groupValues?.get(1)
        SourceManga(sourceId = mid, title = title, coverUrl = cover)
    }.distinctBy { it.sourceId }.toList()
}

/**
 * 详情页 → 漫画信息 + 章节列表。
 *
 * 真实结构（2026-10-23 实测 `/157bz/`）：
 * - 标题：`class="detail-info-title"` 里是干净书名（`<title>` 是 SEO 串
 *   「黑執事漫畫_230已更新_黑執事漫畫在線閱讀」，不能直接用——第十二轮 #4
 *   用户实测「名字叫黑执事，详情页显示一堆无关文字」）
 * - 封面：`img.detail-info-cover`（页面里第一个 img 是网站 logo，不能取首个）
 * - 章节：每话两个链接（「开始阅读」按钮区 `detail-info-btn-1` + 章节
 *   列表区 `detail-list-form-item`，232 话全量在 HTML）——限定列表区匹配
 *   并去重，否则重复 cid 会让浏览页 LazyColumn 的 items(key) 崩（R-72）。
 */
internal fun parseMangabzDetail(html: String, mangaId: String): Pair<SourceManga, List<SourceChapter>>? {
    val title = Regex("""class="detail-info-title"[^>]*>\s*([^<]+?)\s*<""").find(html)
            ?.groupValues?.get(1)?.trim()
        ?: Regex("""<h1[^>]*>([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.trim()
        ?: Regex("""<title>([^<]+)""").find(html)?.groupValues?.get(1)?.trim()
            ?.substringBefore('_')?.removeSuffix("漫画")?.removeSuffix("漫畫")?.trim()
        ?: return null
    // 封面：img.detail-info-cover（src/class 属性顺序不定，先定位 img 标签再取 src）
    val cover = Regex("""<img[^>]*class="[^"]*detail-info-cover[^"]*"[^>]*>""").find(html)
            ?.let { Regex("""src="([^"]+)"""").find(it.value)?.groupValues?.get(1) }
        ?: Regex("""<img[^>]+(?:src|data-src)="([^"]+)"[^>]*>""").find(html)?.groupValues?.get(1)
    val author = Regex("""作者[：:]\s*</[^>]+>\s*<[^>]+>([^<]+)""").find(html)?.groupValues?.get(1)?.trim()
    val chapters = run {
        // 章节列表本体（class="detail-list-form-item"）优先：详情页里每话还会在
        // 「开始阅读」按钮区（detail-info-btn-1）出现一次——同一 cid 两个链接，
        // 不去重会让浏览页 LazyColumn 的 items(key) 直接崩
        // （Key "122414" was already used → 闪退，第十一轮 #2 实测）。
        val listItems = Regex("""href="/m(\d+)/"[^>]*detail-list-form-item[^>]*>([^<]+)""")
            .findAll(html).toList()
            .ifEmpty { Regex("""href="/m(\d+)/"[^>]*>([^<]+)""").findAll(html).toList() }
        listItems.mapIndexed { i, m ->
            val name = m.groupValues[2].trim()
            SourceChapter(id = m.groupValues[1], name = name, index = i, kind = chapterKindOf(name))
        }.distinctBy { it.id } // 双保险：同 cid 只留第一个（列表区新话在前）
            .reversed()          // 站点新话在前 → 翻转为阅读顺序（第 1 话在前）
            // **reversed 之后必须重算 index**（2026-10-24 用户实测「第 1 卷打开是第 230 话」）：
            // 上面的 index 是「倒序前」的序号，倒序后数组位置全变了而 index 没变，
            // 于是 chapters[0].index == 231 → 点第 1 项跳到最后一话。
            // index 的契约是「在本列表中的下标」（ReaderScreen 拿它当数组下标用）。
            .mapIndexed { i, ch -> ch.copy(index = i) }
    }
    val manga = SourceManga(
        sourceId = mangaId,
        title = title,
        coverUrl = cover,
        author = author,
    )
    return manga to chapters
}

/**
 * 翻页器 HTML → 视图签名四件套（MANGABZ_CID / MANGABZ_MID / VIEWSIGN_DT / VIEWSIGN）。
 *
 * 形态：`MANGABZ_CID=12345;` / `MANGABZ_VIEWSIGN_DT="2024-01-01 00:00:00"` /
 * `MANGABZ_VIEWSIGN="abcdef"`。缺任一 → null（页面没加载出来/结构变了）。
 */
internal fun parseMangabzViewSign(html: String): MangabzViewSign? {
    fun pick(prefix: String, quoted: Boolean): String? {
        val re = if (quoted) {
            Regex(Regex.escape(prefix) + """="([^"]*)"""")
        } else {
            Regex(Regex.escape(prefix) + """=(\d+)""")
        }
        return re.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }
    val cid = pick("MANGABZ_CID", quoted = false) ?: return null
    val mid = pick("MANGABZ_MID", quoted = false) ?: return null
    val dt = pick("MANGABZ_VIEWSIGN_DT", quoted = true) ?: return null
    val sign = pick("MANGABZ_VIEWSIGN", quoted = true) ?: return null
    return MangabzViewSign(cid = cid, mid = mid, dt = dt, sign = sign)
}

/**
 * asHx 响应 → 一批图片引用（页号 + 完整 URL）。
 *
 * 真实响应是 JS packer 混淆（见 [pagesInner] 注释）。解包后形如：
 * ```
 * function dm5imagefun(){var cid=528110;var key='b70dc2f7...';
 *   var pix="https://image.mangabz.com/1/157/528110";
 *   var pvalue=["/1_1273.jpg","/2_7597.jpg"]; ...}
 * ```
 * 页号取文件名前缀（`1_1273.jpg` → 1），URL = `pix + 文件名 + ?cid=..&key=..&uk=`。
 *
 * 解不出 packer（站点改版 / 直接返回纯文本 key）时退化为旧式 `12_abc|` 解析——
 * 那种形态只给 1 页，页号取 `|` 前的数字。
 */
internal fun parseMangabzImageBatch(text: String): List<MangabzImageRef> {
    val js = unpackJsPacker(text)
    if (js != null) {
        val pix = Regex("""pix\s*=\s*"([^"]+)"""").find(js)?.groupValues?.get(1)
        val cid = Regex("""cid\s*=\s*(\d+)""").find(js)?.groupValues?.get(1)
        val key = Regex("""key\s*=\s*'([^']*)'""").find(js)?.groupValues?.get(1)
            ?: Regex("""key=([0-9a-fA-F]{8,})""").find(js)?.groupValues?.get(1)
        val arr = Regex("""pvalue\s*=\s*\[([^\]]*)]""").find(js)?.groupValues?.get(1)
        if (pix != null && cid != null && key != null && arr != null) {
            val files = Regex(""""([^"]+)"""").findAll(arr).map { it.groupValues[1] }.toList()
            val query = "?cid=$cid&key=$key&uk="
            return files.mapNotNull { file ->
                val page = Regex("""/(\d+)_""").find(file)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                MangabzImageRef(page = page, url = pix + file + query)
            }.sortedBy { it.page }
        }
    }
    // 老式 `12_abc.jpg|` 单页响应：只有文件名、拼不出真实图片前缀（pix 在
    // packer 里），硬拼出来的 URL 一定是错的——宁可返回空让上层显示「没有图片」，
    // 也不要给一批 404 的假 URL（那正是「一直转圈」的观感）。
    return emptyList()
}

/**
 * 解 Dean Edwards 的 JS packer（`eval(function(p,a,c,k,e,d){...}('...',a,c,'..|..'.split('|'),0,{}))`）。
 *
 * 站点把整章图片清单塞进这个壳里（`pvalue` 数组）。解包算法是固定的：
 * 把 payload 里的每个词按 36 进制解析成索引，替换成字典 k 里的原词。
 * 返回 null = 不是 packer 格式。
 */
internal fun unpackJsPacker(src: String): String? {
    val m = PACKER.find(src) ?: return null
    val payload = m.groupValues[1]
    val radix = m.groupValues[2].toIntOrNull() ?: return null
    if (radix < 2) return null
    val dict = m.groupValues[4].split('|')
    return WORD.replace(payload) { token ->
        val idx = token.value.toIntOrNull(radix)
        val rep = idx?.let { dict.getOrNull(it) }.orEmpty()
        if (rep.isEmpty()) token.value else rep
    }
}

private val PACKER = Regex(
    """eval\(function\(p,a,c,k,e,d\)\{.*?}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)\)""",
    RegexOption.DOT_MATCHES_ALL
)

private val WORD = Regex("""\b\w+\b""")

/**
 * asHx 响应体 → 图片文件名 key（**旧式单页形态** `12_abc123|`，现在基本见不到）。
 *
 * 保留给 [parseMangabzImageBatch] 的退化路径与老测试：取 `|` 前整段；
 * 没有 `|` → null。
 */
internal fun parseMangabzImageKey(text: String): String? {
    val bar = text.indexOf('|')
    if (bar <= 0) return null
    return text.substring(0, bar).trim().takeIf { it.isNotEmpty() }
}
