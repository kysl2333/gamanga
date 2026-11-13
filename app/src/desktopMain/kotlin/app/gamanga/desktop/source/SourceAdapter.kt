package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.model.SourceType

/**
 * 源站返回的漫画（尚未入书架，故不带本地 id / 封面占位色）。
 */
data class SourceManga(
    /** 源站内部 id（入书架时拼成 `"<source>-<id>"` 作为本地主键）。 */
    val sourceId: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val status: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val tags: List<String> = emptyList()
)

/** 源站章节。[index] 用于排序（0 = 第一话）。 */
data class SourceChapter(
    val id: String,
    val name: String,
    val index: Int,
    val pageCount: Int = 0,
    /** 只有网页导航链接，尚未取得章节目录中的真实名称。 */
    val navigationOnly: Boolean = false,
    /**
     * 这一条是「话」「卷」还是「番外」（见 [ChapterKind]）。
     *
     * 源站能给出确定答案时（Komiic 的 `type`、拷贝漫画的 `groups`）直接填；
     * 给不出时留 [ChapterKind.UNKNOWN]，由 [resolveChapterKinds] 用名字与页数证据补全。
     * **默认 UNKNOWN 而不是 CHAPTER**：不确定时不要假装确定。
     */
    val kind: ChapterKind = ChapterKind.UNKNOWN,
)

/** 一次详情加载的快照；目录不完整时不能用于更新提示或覆盖本地目录。 */
data class SourceDetails(
    val manga: SourceManga?,
    val chapters: List<SourceChapter>,
    val chaptersComplete: Boolean = chapters.isNotEmpty() && chapters.none { it.navigationOnly },
)

/**
 * 一页图片。
 *
 * [ticket] 是部分源站的防盗链票据（Komiic 走 `X-Image-Ticket` 请求头），
 * 下载时必须带上，否则 401/403。票据有时效，过期重新调 [SourceAdapter.pages]。
 *
 * [referer] 是**图片域自己的防盗链**（2026-10-24 实测 MangaBZ）：
 * `image.mangabz.com` 不带 `Referer: https://www.mangabz.com/m<cid>/` 直接 **403**，
 * 浏览器里能看图是因为浏览器自动带了 Referer。加载/下载图片一律用
 * [requestHeaders] 取头，不要各处自己拼。
 */
data class PageImage(
    val url: String,
    val ticket: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val referer: String? = null
)

/** 加载/下载这一页要带的请求头（票据 + 防盗链 Referer）。 */
fun PageImage.requestHeaders(): Map<String, String> = buildMap {
    ticket?.let { put("X-Image-Ticket", it) }
    referer?.let { put("Referer", it) }
}

/**
 * 源适配器（M3/M4）。
 *
 * 只做「取数 + 解析」，不碰 UI / 数据库；源站改版时只改实现类。
 * 所有方法都是 suspend：网络在 [kotlinx.coroutines.Dispatchers.IO] 上跑，
 * 调用方负责切回主线程。
 */
interface SourceAdapter {

    val type: SourceType

    /** 展示名（浏览页卡片 / 阅读器顶栏）。 */
    val displayName: String

    /** 搜索。[page] 从 0 开始。 */
    suspend fun search(keyword: String, page: Int = 0, pageSize: Int = 20): List<SourceManga>

    /** 详情（补齐搜索列表里没有的描述/标签）。 */
    suspend fun detail(mangaId: String): SourceManga?

    /** 章节列表，**按阅读顺序**（第 1 话在前）。 */
    suspend fun chapters(mangaId: String): List<SourceChapter>

    /** 详情与目录快照。强制刷新必须绕过详情缓存；网页源可合并成一次加载。 */
    suspend fun loadDetails(mangaId: String, forceRefresh: Boolean = false): SourceDetails {
        val manga = sourceOrNull { detail(mangaId) }
        val chapters = sourceOrNull {
            if (forceRefresh) refreshChapters(mangaId) else chapters(mangaId)
        }.orEmpty()
        return SourceDetails(manga, chapters)
    }

    /** 查询远端目录，供手动/自动更新检查；带缓存的实现须覆盖此方法。 */
    suspend fun refreshChapters(mangaId: String): List<SourceChapter> = chapters(mangaId)

    /** 某章的全部图片，按页序。 */
    suspend fun pages(chapterId: String): List<PageImage>

    /**
     * 自检探针用的搜索词（F-20/F-36）。
     *
     * **必须选一个「当前站点确实有结果」的词**：自检把「返回 0 条」判成黄灯
     * （路径/选择器配错的唯一信号），拿一个本来就没结果的词去探，会把好源误判成黄灯。
     * 实测（2026-10-25）：Komiic 搜「测试」= 0 条、搜「漫画」= 2 条；G社搜「测试」= 30 条；
     * MangaBZ 搜「的」= 12 条。站点内容变化后这个词可能要跟着换。
     */
    val probeKeyword: String get() = "测试"

    /**
     * 「搜索返回 0 条」是否算可疑（黄灯）。
     *
     * - `true`（默认）：0 条 = 黄灯。用于**搜索一定该有结果**的源（G社/MangaBZ/拷贝漫画），
     *   这样路径模板或选择器配错时能立刻看出来（那种错不抛异常，只是列表空）。
     * - `false`：站点可达就算绿灯，0 条只是信息。Komiic 用这个——2026-10-25 用户口径
     *   「只要能进登录页或者网站能通就算绿灯」，且它的搜索是按标题精确匹配，空结果很正常。
     *
     * 注意：不管哪种，**抛异常永远是红灯**（连不上/4xx/接口报错）。
     */
    val probeEmptyIsSuspicious: Boolean get() = true

    /**
     * **健康自检探针**（F-20）：跑一次最小搜索。
     *
     * 返回条数交给 `SourceHealth` 判定：>0 = OK，0 = 黄灯（可疑），抛异常 = 红灯。
     * 搜索不烧图片配额，是最便宜的可观测动作。
     *
     * 各源可覆盖：拷贝漫画的搜索要走隐藏浏览器（约 10s），调用方给它更长的超时。
     */
    suspend fun probe(): Int = search(probeKeyword, page = 0, pageSize = 1).size
}

/** 网络回落保留协程取消语义，避免关窗后继续请求。 */
internal suspend fun <T> sourceOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * 已接入的源（新增源只改这里）。
 *
 * [installCookieProvider] 由启动时注入：源适配器靠它拿到内嵌浏览器导出的登录态。
 * 有登录态和没有，差别很大——例如 Komiic 的图片票据有**每日配额**，匿名额度极小，
 * 登录后额度按账号走。所以这里不能把 cookie 写死成 null。
 *
 * **地址来自 `sources.json`（F-22）**：这里只做「取生效值 → 传进构造器」，
 * 适配器内部不再硬编码域名。设置页保存配置后调 [rebuild] 重建全部适配器，
 * **改地址不需要重启应用**（2026-10-25 用户要求）。
 */
object Sources {

    /** 域名 → cookie 串（`a=1; b=2`）；未登录返回 null。 */
    @Volatile
    private var cookieProvider: (String) -> String? = { null }

    fun installCookieProvider(provider: (String) -> String?) {
        cookieProvider = provider
    }

    private val lock = Any()

    /**
     * 当前适配器实例（惰性创建 + 可重建）。
     *
     * 惰性：必须在 [installCookieProvider] 之后才取到登录态（cookie 闭包在请求时才求值，
     * 所以顺序其实不敏感，但保持惰性可以避免启动时白建 4 个对象）。
     */
    @Volatile
    private var adapters: List<SourceAdapter>? = null

    fun all(): List<SourceAdapter> = adapters ?: synchronized(lock) {
        adapters ?: build().also { adapters = it }
    }

    fun of(type: SourceType): SourceAdapter? = all().firstOrNull { it.type == type }

    /**
     * 重建全部适配器（保存 `sources.json` 后调）。
     *
     * **为什么必须重建而不是「改字段」**：适配器在构造时就把 baseUrl / api 地址 /
     * 站点候选列表读进了 `val`，改配置只有重建才能生效。
     *
     * 同时清 [PageCache]：里面缓存的是**旧域名拼出来的图片 URL**，不清的话换域名后
     * 第一次读同一话仍会去打旧地址（图片 404，用户以为「换了域名也没用」）。
     * 拷贝漫画的详情/搜索缓存挂在实例里，重建即自然丢弃。
     */
    fun rebuild() {
        synchronized(lock) {
            PageCache.clear()
            val fresh = build()
            adapters = fresh
            AppLog.i(
                "Sources",
                "已按新配置重建：" + fresh.joinToString { "${it.type.name}=${SourceConfig.baseUrl(it.type)}" },
            )
        }
    }

    // 统一套一层 CachedPages：取图是整条链路里最贵、最容易触发配额的一步。
    private fun build(): List<SourceAdapter> = listOf(
        CachedPages(
            KomiicSource(
                siteBases = SourceConfig.bases(SourceType.KOMIIC),
                cookies = { cookieProvider(SourceConfig.domain(SourceType.KOMIIC)) },
            )
        ),
        CachedPages(
            GodaSource(
                siteBases = SourceConfig.bases(SourceType.GODA),
                apiBases = SourceConfig.apiBaseUrls(SourceType.GODA),
                cookies = { cookieProvider(SourceConfig.domain(SourceType.GODA)) },
            )
        ),
        // 第八轮 #9：MangaBZ + 拷贝漫画。两个站当前开发网络不可达
        // （DNS 异常），接口按公开资料实现、未实测；用户在可访问
        // 环境（或配合设置页 HTTP 代理）下首次使用，异常会进
        // logs/gamanga.log，按日志修字段即可。
        CachedPages(
            MangabzSource(
                siteBases = SourceConfig.bases(SourceType.MANGABZ),
                paths = SourceConfig.paths(SourceType.MANGABZ),
            )
        ),
        CachedPages(
            CopymangaSource(
                webBase = SourceConfig.baseUrl(SourceType.COPYMANGA),
                apiBases = SourceConfig.apiBaseUrls(SourceType.COPYMANGA),
            )
        ),
    )
}
