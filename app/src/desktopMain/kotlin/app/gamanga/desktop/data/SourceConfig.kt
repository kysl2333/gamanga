package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.Sources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 单个源的地址配置（F-22）。
 *
 * 四个字段都是「**空 = 用内置默认**」的语义，所以用户只在需要改的时候写一项即可：
 * - [baseUrl] 站点根地址（浏览器打开 / 搜索兜底页 / Referer 都跟着它走）
 * - [backupBaseUrls] 备用站点地址，主站连接失败时按序重试（同 CMS 换皮域名，如包子系）
 * - [apiBaseUrls] 接口域名候选（第一个优先，连接失败换下一个）。Komiic / MangaBZ
 *   的接口与站点同域，留空即可（[SourceConfig.apiBaseUrls] 会回落到 [baseUrl]）
 * - [userAgent] 自定义 UA（空 = [app.gamanga.desktop.source.Http] 内置的桌面 Chrome UA）
 *
 * **字段留空 ≠ 写空字符串**：写空字符串与不写等价，都表示用默认。
 */
data class SourceEndpoint(
    val baseUrl: String = "",
    val backupBaseUrls: List<String> = emptyList(),
    val apiBaseUrls: List<String> = emptyList(),
    val userAgent: String = "",
    /**
     * 路径模板（F-36）：`search` / `detail` / `chapter` / `api` 等 key → 相对路径模板。
     * 占位符只允许 `{kw}` `{id}` `{manga}` `{chapter}` `{page}`（空 = 内置默认）。
     */
    val paths: Map<String, String> = emptyMap(),
) {
    /** 全部字段都没设置（UI 用来判断「这一项是默认值」）。 */
    val isEmpty: Boolean
        get() = baseUrl.isBlank() && userAgent.isBlank() && paths.isEmpty() &&
            backupBaseUrls.none { it.isNotBlank() } && apiBaseUrls.none { it.isNotBlank() }
}

/**
 * 源站地址配置化（F-22）。
 *
 * **要解决的问题**：源站换域名时**不改代码**。改代码意味着重新编译打包，
 * 而这类站点换域名是常态（G社/包子系一年换好几次，拷贝漫画换过 3 次）。
 *
 * **文件**：`%LOCALAPPDATA%\Gamanga\sources.json`（人可读、可手改，缩进 2 空格）：
 *
 * ```json
 * {
 *   "GODA": {
 *     "baseUrl": "https://godamh.com",
 *     "backupBaseUrls": ["https://m.g-mh.org"],
 *     "userAgent": ""
 *   },
 *   "COPYMANGA": {
 *     "apiBaseUrls": ["https://api.copy4000.com", "https://api.mangacopy.com"]
 *   }
 * }
 * ```
 *
 * 键是 [SourceType] 的枚举名（大写）；未知键忽略（不报错，方便先写注释性配置）。
 * `backupBaseUrls` / `apiBaseUrls` 既接受数组也接受逗号分隔字符串（手改更省事）。
 *
 * **读取时机**：启动时 [load] 一次。适配器是惰性单例，**改完必须重启才生效**——
 * 设置页会明确提示这一点，别指望保存后立刻切。
 *
 * **为什么不放 appPrefs**：这份配置要能脱离应用手改（用户/NAS/另一台机器上复制），
 * 也要能一眼看清「现在到底指向哪个域名」。appPrefs 在 SQLite 里，手改不了。
 */
object SourceConfig {

    const val FILE_NAME = "sources.json"

    /**
     * 内置默认（**运行期唯一的来源**：`Sources` 装配适配器、`SourceLinks`、设置页占位符都取这里）。
     *
     * 适配器构造器上那几个字面量只是「不带参直接 new」时的兜底（测试用），
     * 正常启动路径**一定**由 `Sources` 显式传值——改默认地址只改这里。
     *
     * - GODA 默认 `m.g-mh.org`：baozimh.org 会把漫画页重定向到 `/chapterlist/`
     *   且广告会跳 `tsyndicate.com`；G社这边实测最干净（见 GodaSource 注释）。
     *   备用域名只列同 CMS 的换皮站，不默认启用（自动切换只在连接失败时发生）。
     * - COPYMANGA 主接口 `api.mangacopy.com`，备用 `api.copy4000.com`（大陆可达）。
     * - Komiic / MangaBZ 的接口与站点同域，`apiBaseUrls` 留空。
     */
    val DEFAULTS: Map<SourceType, SourceEndpoint> = mapOf(
        SourceType.KOMIIC to SourceEndpoint(
            baseUrl = "https://komiic.cc",
            paths = mapOf("api" to "/api/query"),
        ),
        SourceType.GODA to SourceEndpoint(
            baseUrl = "https://m.g-mh.org",
            apiBaseUrls = listOf("https://v2.apikk.top"),
            paths = mapOf(
                "search" to "/s/{kw}",
                "detail" to "/manga/{id}",
                "chapter" to "/manga/{manga}/{chapter}",
            ),
        ),
        SourceType.MANGABZ to SourceEndpoint(
            baseUrl = "https://www.mangabz.com",
            // 翻页器（/m{cid}-p{n}）与 asHx 取图路径没模板化：改那两条时解析代码也要改，
            // 只改路径救不回来（见 ADR-0004 档 1 的范围）
            paths = mapOf(
                "search" to "/search?title={kw}",
                "detail" to "/{id}bz/",
            ),
        ),
        SourceType.COPYMANGA to SourceEndpoint(
            baseUrl = "https://www.mangacopy.com",
            apiBaseUrls = listOf("https://api.mangacopy.com", "https://api.copy4000.com"),
            paths = mapOf(
                "search" to "/search?q={kw}",
                "detail" to "/comic/{id}",
                "chapter" to "/comic/{id}/chapter/{chapter}",
            ),
        ),
    )

    /** 配置文件默认位置（与 DB / 日志 / 封面缓存同根）。 */
    fun defaultFile(): File = File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "Gamanga/$FILE_NAME",
    )

    /** 文件里写的原始值（未设置 = 空字段）；UI 编辑用，别拿它算实际地址。 */
    @Volatile
    private var raw: Map<SourceType, SourceEndpoint> = emptyMap()

    /** 是否已从磁盘读过（没读过时 [raw] 为空 = 全部默认）。 */
    @Volatile
    var loaded: Boolean = false
        private set

    /**
     * 只在**第一次**读磁盘（启动时调；重复调用直接返回内存里的值）。
     *
     * 为什么要这个：`application { }` 的内容是 composable，重组时会再执行一遍
     * 直接语句。若每次重组都重新读文件，用户在别处手改了文件会出现「悄悄变了但没人
     * 重建适配器」的半生效状态。启动读一次 + 保存走 [save]（会重建适配器），路径才唯一。
     */
    fun loadOnce(from: File = defaultFile()): Map<SourceType, SourceEndpoint> {
        if (loaded) return raw
        return load(from)
    }

    /**
     * 从磁盘读一次（启动时调）。
     *
     * 文件不存在 / JSON 坏了 / 字段类型不对 —— 一律**保持默认并记日志**，
     * 绝不因为一份手改坏的配置让应用起不来。
     */
    fun load(from: File = defaultFile()): Map<SourceType, SourceEndpoint> {
        val parsed = if (!from.isFile) {
            AppLog.i("SourceConfig", "没有 $FILE_NAME，全部用内置默认")
            emptyMap()
        } else {
            runCatching { decode(from.readText(Charsets.UTF_8)) }
                .onFailure { AppLog.e("SourceConfig", "解析 $FILE_NAME 失败，保持内置默认：${from.absolutePath}", it) }
                .getOrDefault(emptyMap())
        }
        raw = parsed
        loaded = true
        AppLog.i("SourceConfig", "已加载 ${from.absolutePath}：" + describe())
        return parsed
    }

    /** 丢弃内存里的配置（测试用；运行期不用调）。 */
    fun reset() {
        raw = emptyMap()
        loaded = false
    }

    /**
     * 文件里写的原始值（没设置过的源不在 map 里）。
     *
     * 设置页的输入框绑它——输入框要显示「用户写过的内容」，没写过就该是空、
     * 让占位符显示默认值；拿 [effective] 去绑会把默认值当成用户填的，
     * 一保存就全量落地成配置，以后改默认值也影响不到这些用户。
     */
    fun rawSnapshot(): Map<SourceType, SourceEndpoint> = raw

    /** UI 预览：把输入框里的文本解析成实际会用的地址（空 = 默认）。 */
    fun previewBase(type: SourceType, text: String): String =
        normalizeBase(text).ifBlank { baseUrl(type) }

    /** UI 预览：输入框里的 UA（空 = 用内置）。 */
    fun previewUserAgent(type: SourceType, text: String): String? =
        text.trim().ifBlank { userAgent(type) }

    /**
     * 已停用的旧官方域名 → 现默认域名。
     *
     * Komiic 官方从 `komiic.com` 迁到 `komiic.cc`（2026-10-28 实测旧域名 DNS 已不可达、
     * `komiic.cc` 的 GraphQL 正常）。老用户的 `sources.json` 里存的还是旧值，
     * 读时自动纠正，不要求用户手改文件。
     */
    private val RETIRED_BASES: Map<SourceType, Map<String, String>> = mapOf(
        SourceType.KOMIIC to mapOf(
            "https://komiic.com" to "https://komiic.cc",
            "https://www.komiic.com" to "https://komiic.cc",
        ),
    )

    private fun migrateBase(type: SourceType, url: String): String =
        RETIRED_BASES[type]?.get(url.trim().trimEnd('/').lowercase()) ?: url

    /** 当前生效值（文件 > 内置默认）。 */
    fun effective(type: SourceType): SourceEndpoint {
        val def = DEFAULTS[type] ?: SourceEndpoint()
        val cur = raw[type] ?: return def
        return SourceEndpoint(
            baseUrl = migrateBase(type, cur.baseUrl.ifBlank { def.baseUrl }),
            backupBaseUrls = cur.backupBaseUrls.ifEmpty { def.backupBaseUrls },
            apiBaseUrls = cur.apiBaseUrls.ifEmpty { def.apiBaseUrls },
            userAgent = cur.userAgent.ifBlank { def.userAgent },
            // 逐 key 覆盖：只改 search 时，detail/chapter 仍用默认
            paths = def.paths + cur.paths,
        )
    }

    /** 主站地址（永远非空、无结尾斜杠）。 */
    fun baseUrl(type: SourceType): String = effective(type).baseUrl

    /** 站点候选：主站 + 备用（去重，主站在前）。 */
    fun bases(type: SourceType): List<String> {
        val e = effective(type)
        return (listOf(e.baseUrl) + e.backupBaseUrls).filter { it.isNotBlank() }.distinct()
    }

    /**
     * 接口候选：`apiBaseUrls` 有值就用它，否则回落到站点地址
     * （Komiic / MangaBZ 这类「接口与站点同域」的源）。
     */
    fun apiBaseUrls(type: SourceType): List<String> {
        val e = effective(type)
        return e.apiBaseUrls.ifEmpty { listOf(e.baseUrl) }.filter { it.isNotBlank() }
    }

    /** 主接口地址（Goda 的 v2.apikk.top / 拷贝漫画的 api.mangacopy.com）。 */
    fun apiBaseUrl(type: SourceType): String = apiBaseUrls(type).first()

    /** 路径模板（生效值）：key = search/detail/chapter/api…，空 = 内置默认。 */
    fun paths(type: SourceType): Map<String, String> = effective(type).paths

    /** 自定义 UA；null = 用 Http 内置 UA。 */
    fun userAgent(type: SourceType): String? = effective(type).userAgent.ifBlank { null }

    /**
     * 源站域名（cookie 持久化 key / 内嵌浏览器 expectHost 用）。
     *
     * 从**生效的** baseUrl 里取 host：换了域名，cookie 也跟着按新域名存，
     * 不会把旧站点的登录态发给新站点。
     */
    fun domain(type: SourceType): String = hostOf(baseUrl(type))

    // ---- 读写 ----

    /**
     * 写回磁盘并**立即生效**（设置页「保存」）。
     *
     * 三件事必须一起做，少一件就是「半生效」：
     * 1. 归一化 + 落盘（用户手改的格式问题在这里吃掉）；
     * 2. 采用为当前生效值（`raw = normalized`）；
     * 3. [Sources.rebuild] 重建全部适配器。
     *
     * 只落盘不重建 → 界面显示新地址、请求还打旧地址；只重建不采用 → 适配器从旧值
     * 装配，等于没改。重建还会清图片列表缓存（旧域名拼出的 URL 不能再用）。
     *
     * @return 归一化后真正写进文件、并已生效的内容（UI 拿它刷新编辑框与脏检查基线）
     * @throws Exception 磁盘写失败（上层展示原因，不吞）
     */
    fun save(map: Map<SourceType, SourceEndpoint>, to: File = defaultFile()): Map<SourceType, SourceEndpoint> {
        val normalized = map.mapValues { (_, e) ->
            SourceEndpoint(
                baseUrl = normalizeBase(e.baseUrl),
                backupBaseUrls = normalizeBases(e.backupBaseUrls),
                apiBaseUrls = normalizeBases(e.apiBaseUrls),
                userAgent = e.userAgent.trim(),
                paths = normalizePaths(e.paths),
            )
        }.filterValues { !it.isEmpty }
        to.parentFile?.mkdirs()
        to.writeText(encode(normalized), Charsets.UTF_8)
        raw = normalized
        loaded = true
        AppLog.i("SourceConfig", "已保存并生效 ${to.absolutePath}：" + describe(normalized))
        Sources.rebuild()
        return normalized
    }

    /** 序列化成人可读 JSON（只写设置过的字段，空字段不落地）。 */
    fun encode(map: Map<SourceType, SourceEndpoint>): String {
        val root = buildJsonObject {
            map.toSortedMap(compareBy { it.name }).forEach { (type, e) ->
                if (e.isEmpty) return@forEach
                put(type.name, buildJsonObject {
                    if (e.baseUrl.isNotBlank()) put("baseUrl", e.baseUrl)
                    if (e.backupBaseUrls.isNotEmpty()) put("backupBaseUrls", JsonArray(e.backupBaseUrls.map(::JsonPrimitive)))
                    if (e.apiBaseUrls.isNotEmpty()) put("apiBaseUrls", JsonArray(e.apiBaseUrls.map(::JsonPrimitive)))
                    if (e.userAgent.isNotBlank()) put("userAgent", e.userAgent)
                    if (e.paths.isNotEmpty()) put("paths", buildJsonObject {
                        e.paths.toSortedMap().forEach { (k, v) -> put(k, v) }
                    })
                })
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    /** 解析 JSON（坏字段跳过，不影响其它源）。 */
    fun decode(text: String): Map<SourceType, SourceEndpoint> {
        val root = Json.parseToJsonElement(text).jsonObject
        val out = mutableMapOf<SourceType, SourceEndpoint>()
        for ((key, value) in root) {
            val type = SourceType.entries.firstOrNull { it.name.equals(key.trim(), ignoreCase = true) }
            if (type == null) {
                AppLog.w("SourceConfig", "忽略未知源键：$key")
                continue
            }
            val obj = value as? JsonObject ?: continue
            out[type] = SourceEndpoint(
                baseUrl = normalizeBase(obj["baseUrl"].asString()),
                backupBaseUrls = normalizeBases(obj["backupBaseUrls"].asStringList()),
                apiBaseUrls = normalizeBases(obj["apiBaseUrls"].asStringList()),
                userAgent = obj["userAgent"].asString().trim(),
                paths = normalizePaths((obj["paths"] as? JsonObject)?.mapValues { it.value.asString() }.orEmpty()),
            )
        }
        return out
    }

    /**
     * 一句话描述「配置里到底写了什么」。
     *
     * 必须看 [raw] 而不是 [effective]：后者永远带默认值，用它判断「有没有自定义」
     * 会把四个源全列出来，日志里看着像「用户配了一堆」，排查时误导人。
     */
    private fun describe(map: Map<SourceType, SourceEndpoint> = raw): String =
        map.entries.filter { !it.value.isEmpty }
            .joinToString(", ") { (type, e) -> "${type.name}=${e.baseUrl.ifBlank { e.apiBaseUrls.firstOrNull() ?: "默认" }}" }
            .ifBlank { "全部内置默认" }

    private val prettyJson = Json { prettyPrint = true }

    /** 只认 JSON 字符串：数字/布尔/对象一律当「没设置」（手改写错类型不该被拼成地址）。 */
    private fun JsonElement?.asString(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    /** 数组或逗号分隔字符串都接受（手改 `"a.com,b.com"` 更省事）。 */
    private fun JsonElement?.asStringList(): List<String> = when (this) {
        null -> emptyList()
        is JsonArray -> mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
        is JsonPrimitive -> takeIf { it.isString }?.contentOrNull
            ?.split(',', '\n', ' ')?.filter { it.isNotBlank() }.orEmpty()
        else -> emptyList()
    }
}

/**
 * 归一化单个地址：去空白 → 补协议 → 去结尾斜杠。
 *
 * 手改配置最常见的两种写法错误都能吃下：`m.g-mh.org`（漏协议）、
 * `https://m.g-mh.org/`（多斜杠，拼出 `//manga/...` 会被部分站点判成 404）。
 */
internal fun normalizeBase(raw: String): String {
    var s = raw.trim()
    if (s.isBlank()) return ""
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "https://$s"
    }
    return s.trimEnd('/')
}

/** 归一化一组地址：逐个归一、丢空、去重（保序）。 */
internal fun normalizeBases(raw: List<String>): List<String> =
    raw.map(::normalizeBase).filter { it.isNotBlank() }.distinct()

/**
 * 从地址取 host（`https://www.mangacopy.com/comic/x` → `www.mangacopy.com`）。
 * 端口去掉——cookie 域和 expectHost 都不带端口。
 */
internal fun hostOf(url: String): String {
    val noScheme = url.trim().substringAfter("://", url.trim())
    return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
}

/**
 * 归一化路径模板（F-36）：去空白、丢空值、补前导斜杠。
 *
 * 补斜杠是因为模板要和 baseUrl 直接拼（`base + path`）：写成 `s/{kw}` 会拼出
 * `https://hosts/...` 这种把主机名吃掉的地址，症状是「换了路径反而整站 404」。
 */
internal fun normalizePaths(raw: Map<String, String>): Map<String, String> =
    raw.mapValues { (_, v) -> v.trim() }
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, v) -> if (v.startsWith("/")) v else "/$v" }

/**
 * 渲染路径模板：`{kw}` / `{id}` / `{manga}` / `{chapter}` / `{page}` 按 [vars] 替换。
 *
 * **不做 URL 编码**：调用方传入的必须是已编码的值（关键词走 `pathEncode`，
 * id/slug 原样）——模板本身也可能把值放进查询串，统一编码会双重转义。
 * 未知占位符**原样保留**：写错 key 时能在 URL 里一眼看见 `{xxx}`，比悄悄变成空串好查。
 */
internal fun renderPath(template: String, vars: Map<String, String>): String {
    var out = template
    for ((key, value) in vars) out = out.replace("{$key}", value)
    return out
}
