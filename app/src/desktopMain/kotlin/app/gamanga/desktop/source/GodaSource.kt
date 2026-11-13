package app.gamanga.desktop.source

import app.gamanga.desktop.data.normalizeBases
import app.gamanga.desktop.data.renderPath
import app.gamanga.desktop.model.SourceType
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * GoDa 源适配（M4）——包子漫画系。实测 `baozimh.org`(包子漫畫) / `godamh.com`(G站漫畫) /
 * `m.g-mh.org`(G社漫畫) 是**同一套 CMS 的三个换皮域名**，路径结构完全一致（`/s?q=`、
 * `/manga/<slug>`、`data-mid`、`v2.apikk.top` 接口都一样），改 [base] 即可切源。
 *
 * 默认用 `m.g-mh.org`：baozimh.org 会把漫画页重定向到 `/chapterlist/`，且广告脚本会把人
 * 跳到 `tsyndicate.com`；G社这边实测更干净。
 *
 * 搜索/详情/章节都是服务端渲染的 HTML + 站点自己的 JSON 接口，直接 HTTP 解析；
 * **章节图片是例外**：接口把图片列表加密下发（`images.images` 是一串自定义编码的密文，
 * 解码器为混淆过的 JS），硬逆向不划算，于是让站点在隐藏的 JCEF 里自己解，我们只读
 * DOM 的 `data-path`（见 [BrowserProbe]）。
 */
class GodaSource(
    /** 站点候选（主站在前）。连不上就按序换下一个——同 CMS 换皮域名见类注释。 */
    siteBases: List<String> = listOf(DEFAULT_BASE),
    /** 接口候选（Goda 系各换皮站共用 `v2.apikk.top`，与站点域名无关）。 */
    apiBases: List<String> = listOf(DEFAULT_API),
    /** 借会话：内嵌浏览器导出的登录态（`a=1; b=2`），未登录为 null。 */
    private val cookies: () -> String? = { null },
    /** 路径模板（F-36，`sources.json` 的 `paths` 段）；空 = 内置默认。 */
    paths: Map<String, String> = emptyMap(),
    private val htmlReader: (suspend (String) -> String)? = null,
    private val jsonReader: (suspend (String) -> String)? = null,
) : SourceAdapter {

    private val paths: Map<String, String> = DEFAULT_PATHS + paths

    /** 渲染一条路径模板（`{kw}`/`{id}`/`{manga}`/`{chapter}`）。 */
    private fun path(key: String, vararg vars: Pair<String, String>): String =
        renderPath(paths[key] ?: DEFAULT_PATHS.getValue(key), vars.toMap())

    private val bases: List<String> = normalizeBases(siteBases).ifEmpty { listOf(DEFAULT_BASE) }

    private val apiRoots: List<String> = normalizeBases(apiBases).ifEmpty { listOf(DEFAULT_API) }

    /** 主站地址（Referer / 内嵌浏览器页面地址都用它）。 */
    private val base: String get() = bases.first()

    /**
     * 统一的站点请求头：带 Referer（部分接口校验）+ 登录态。
     *
     * [baseForReferer] 由换域名重试传入——Referer 必须是**当前请求那个站**的，
     * 拿 A 站 Referer 打 B 站会被当外链拒掉。
     */
    private fun headers(baseForReferer: String = base): Map<String, String> = buildMap {
        put("Referer", "$baseForReferer/")
        cookies()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    /** 站点 HTML 页（搜索/详情）——带换域名重试。 */
    private suspend fun getHtml(path: String): String =
        htmlReader?.invoke(path) ?: fetchTextWithFallback(bases, path, SourceType.GODA, "Goda") { headers(it) }

    /** 站点 JSON 接口——接口域名同样可配、可备用。 */
    private suspend fun getApi(path: String): String =
        jsonReader?.invoke(path) ?: fetchTextWithFallback(apiRoots, path, SourceType.GODA, "Goda") { headers() }

    override val type: SourceType = SourceType.GODA
    override val displayName: String = "包子漫画"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
        // 站点改版（Nuxt→Astro）后搜索从「/s?q=查询串」变成「/s/<关键词>」路径式：
        // 旧写法服务端直接忽略 q，对任意关键词都返回同一份默认列表（R-41，
        // 用户感知「第二次搜索还是第一次的结果」）。卡片结构没变，CARD 正则仍适用。
        val html = getHtml(path("search", "kw" to pathEncode(keyword)))
        // 卡片：<a href="/manga/slug">…<img src="cover">…<h3 class="cardtitle">标题</h3>
        return CARD.findAll(html).map { m ->
            val slug = m.groupValues[1]
            SourceManga(
                sourceId = slug,
                title = m.groupValues[3].stripTags(),
                coverUrl = m.groupValues[2].ifBlank { null }
            )
        }.drop(page * pageSize).take(pageSize).toList()
    }

    override suspend fun detail(mangaId: String): SourceManga? =
        parseDetail(getHtml(path("detail", "id" to mangaId)), mangaId)

    override suspend fun loadDetails(mangaId: String, forceRefresh: Boolean): SourceDetails {
        val html = getHtml(path("detail", "id" to mangaId))
        val manga = parseDetail(html, mangaId)
        val chapters = sourceOrNull { chaptersFromHtml(html) }.orEmpty()
        return SourceDetails(manga, chapters)
    }

    private fun parseDetail(html: String, mangaId: String): SourceManga? {
        val rawH1 = H1.find(html)?.groupValues?.get(1) ?: return null
        // H1 里嵌着状态徽章（`<h1> 标题 <span>連載中</span></h1>`），截到第一个 span 前才是标题
        val title = rawH1.substringBefore("<span").stripTags().ifBlank { rawH1.stripTags() }
        return SourceManga(
            sourceId = mangaId,
            title = title,
            coverUrl = OG_IMAGE.find(html)?.groupValues?.get(1),
            author = AUTHOR.find(html)?.groupValues?.get(1)?.stripTags(),
            status = STATUS.find(html)?.groupValues?.get(1),
            description = (DESC.find(html) ?: META_DESC.find(html))?.groupValues?.get(1)?.stripTags(),
            tags = TAG.findAll(html).map { it.groupValues[1].stripTags() }.toList()
        )
    }

    override suspend fun chapters(mangaId: String): List<SourceChapter> =
        chaptersFromHtml(getHtml(path("detail", "id" to mangaId)))

    private suspend fun chaptersFromHtml(html: String): List<SourceChapter> {
        val mid = MID.find(html)?.groupValues?.get(1)
            ?: CHAPTER_MID.find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val json = Json.parseToJsonElement(
            getApi("/api/manga/get?mid=$mid&mode=all")
        ).jsonObject
        val list = json["data"]?.let { it as? JsonObject }?.get("chapters") as? JsonArray ?: return emptyList()
        return mapChapters(list, mid)
    }

    /**
     * 纯函数：API chapters 数组 → 排好序、index 回填的章节列表。
     *
     * id 编码成 "mid|cid|章节slug"：取图接口要 (m=mid, c=cid)，读 DOM 要章节 slug。
     * order 偶尔是小数（实测 240.1/270.1/275.1，站方给补章/换页让位）：
     * intOrNull 解析小数直接返回 null，旧兜底 `?: 1` 把 239/268/274 全压成
     * index 0——用户实测「章序混乱，239/268/274 夹在第 1 话后面」
     * （2026-10-23，装备我最强 292 章实录）。按 double 收。
     *
     * 排序策略：order 齐全 → 按 order 排（站方偶发乱序数组时唯一可靠的键，
     * Komiic 实测服务端就返回乱序）；只要有**任何一个**缺 order → 整表信任
     * 数组顺序（数组本身就是站点展示顺序，旋风管家 423 章、装备我最强 292 章
     * 实测均成立）。不搞「有的按 order、有的按数组位置」的混排——两种刻度
     * 混用会排错（缺 order 的位置 2 会插到 order=5 的前面）。
     *
     * 排完统一 mapIndexed 回填 index（与 Komiic 同套路），杜绝并列——
     * chapterIndex 并列时 ORDER BY 的顺序不确定。
     */
    internal fun mapChapters(list: JsonArray, mid: String): List<SourceChapter> {
        data class Parsed(val chapter: SourceChapter, val order: Double?)
        val parsed = list.mapIndexedNotNull { position, el ->
            val ch = el as? JsonObject ?: return@mapIndexedNotNull null
            val attrs = ch["attributes"] as? JsonObject ?: return@mapIndexedNotNull null
            val cid = ch.str("id") ?: return@mapIndexedNotNull null
            val slug = attrs.str("slug") ?: return@mapIndexedNotNull null
            Parsed(
                chapter = SourceChapter(
                    id = "$mid|$cid|$slug",
                    name = attrs.str("title") ?: "第 ${position + 1} 话",
                    index = position, // 占位：排完序统一回填
                    pageCount = 0,
                    kind = chapterKindOf(attrs.str("title").orEmpty()),
                ),
                order = attrs.double("order")?.takeIf { it.isFinite() }
            )
        }
        val ordered = if (parsed.all { it.order != null }) {
            parsed.sortedBy { it.order as Double }
        } else {
            parsed // 保持数组顺序
        }
        return ordered.mapIndexed { i, p -> p.chapter.copy(index = i) }
    }

    override suspend fun pages(chapterId: String): List<PageImage> {
        val parts = chapterId.split('|')
        val mid = parts.getOrElse(0) { "" }
        val cid = parts.getOrElse(1) { "" }
        val chapterSlug = parts.getOrElse(2) { "" }
        if (mid.isBlank() || cid.isBlank() || chapterSlug.isBlank()) return emptyList()

        // 1) 拿漫画 slug 与线路（线路决定图片域名：line 2 走 c-nd2，其余 c-nd3）
        val info = Json.parseToJsonElement(getApi("/api/v2/chapter/getinfo?m=$mid&c=$cid")).jsonObject
        val chapter = (info["data"] as? JsonObject)?.get("info") as? JsonObject ?: return emptyList()
        val mangaSlug = chapter.str("slug") ?: return emptyList()
        val line = (chapter["images"] as? JsonObject)?.int("line") ?: 1
        val host = if (line == 2) "https://c-nd2-1.6wm.top" else "https://c-nd3-1.6wm.top"

        // 2) 让站点自己解密并插图，然后直接读 DOM。
        //    优先 `data-path`（相对路径，拼 [host] 即可）；站点某些线路只写 `data-src`（绝对地址），
        //    所以 `data-src` 作为回退——两者的值是同一张图。
        //    没解出来时返回 null 让 BrowserProbe 继续等，绝不把空数组当结果。
        val js = """
            (function(){
              var im = Array.from(document.querySelectorAll('#chapcontent img[data-path],#chapcontent img[data-src]'));
              var out = im.map(function(i){ return i.dataset.path || i.dataset.src || ''; })
                          .filter(function(v){ return v; });
              return out.length ? out : null;
            })()
        """.trimIndent()
        val pageUrl = base + path("chapter", "manga" to mangaSlug, "chapter" to chapterSlug)
        val json = BrowserProbe.evalJson(pageUrl, js, timeoutMs = 45_000)
        return parsePaths(json).map { path ->
            // data-path 是 "/scomic/..." 这种相对路径；data-src 已是完整 URL
            PageImage(if (path.startsWith("http")) path else host + path)
        }
    }

    private fun parsePaths(json: String?): List<String> {
        if (json == null) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
    }

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        // 与 SourceConfig.DEFAULTS 保持一致（正常启动由 Sources 显式传值）
        const val DEFAULT_BASE = "https://m.g-mh.org"
        const val DEFAULT_API = "https://v2.apikk.top"

        val CARD = Regex(
            """<a href="/manga/([^"]+)"[^>]*>\s*<div[^>]*>\s*<div[^>]*>\s*<img[^>]*src="([^"]*)".*?<h3[^>]*>(.*?)</h3>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val H1 = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
        val OG_IMAGE = Regex("""property="og:image" content="([^"]+)"""")
        val META_DESC = Regex("""<meta name="description" content="([^"]*)"""")
        // 详情字段都挂在链接上：/manga-author/<slug>、/manga-genre/<slug>、/manga-tag/<slug>
        val AUTHOR = Regex("""/manga-author/[^"]*"[^>]*>\s*<span>\s*([^<]+?)\s*</span>""")
        val TAG = Regex("""/manga-tag/[^"]*"[^>]*>\s*<span[^>]*>\s*#?\s*([^<]+?)\s*</span>""")
        // 简介是带 line-clamp-4 的那个 <p>（内容常常只有"标题 -作者"，但比 meta 描述优先）
        val DESC = Regex("""<p class="[^"]*line-clamp-4[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val STATUS = Regex("""(連載中|已完結|連載|完結)""")
        /** 路径模板默认值（F-36）：改版时可在 `sources.json` 的 `paths` 段覆盖。 */
        val DEFAULT_PATHS: Map<String, String> = mapOf(
            "search" to "/s/{kw}",
            "detail" to "/manga/{id}",
            "chapter" to "/manga/{manga}/{chapter}",
        )

        val MID = Regex("""id="allchapters"[^>]*data-mid="(\d+)"""")
        val CHAPTER_MID = Regex("""data-mid="(\d+)"""")
    }
}

/**
 * 路径段编码：URLEncoder 把空格编成 `+`（查询串语义），路径里必须是 `%20`。
 *
 * 从 GodaSource 的私有成员提为包内共享（第八轮 #9）：MangaBZ / 拷贝漫画
 * 的 URL 拼装同样需要（关键词进路径的站都吃这一套）。
 */
internal fun pathEncode(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20")
