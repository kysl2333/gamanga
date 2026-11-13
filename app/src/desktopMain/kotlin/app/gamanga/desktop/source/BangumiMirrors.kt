package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.prefs.Preferences

/**
 * 一条可用的 Bangumi 线路：API 与图片域名成对出现。
 *
 * 官方线路在部分网络下完全不可达（本机实测 `api.bgm.tv` / `lain.bgm.tv` 全部超时），
 * 镜像线路同时代理 V0 接口与封面 CDN，图片 URL 结构与官方一致（`/r/{宽}/pic/...`），
 * 因此只需替换域名，路径可以原样复用。
 */
data class BangumiMirror(
    val id: String,
    val name: String,
    val api: String,
    val image: String,
    val note: String = "",
)

/** 线路探测结果。三种状态必须区分：被反爬拦截 ≠ 网络不可达，前者换机器也没用。 */
sealed interface MirrorProbe {
    /** 接口通了；[imageOk] 是图片域名是否也能取到图——接口通而图裂等于没法用。 */
    data class Ok(val latencyMs: Long, val imageOk: Boolean = true) : MirrorProbe
    /** 站点可达但返回 HTML 验证页（Anubis / Cloudflare 一类），程序内 HTTP 无法通过。 */
    data object Blocked : MirrorProbe
    data object Down : MirrorProbe
}

/** 真正能用的线路：接口与图片都通。 */
val MirrorProbe.isUsable: Boolean get() = this is MirrorProbe.Ok && imageOk

/**
 * 线路清单与当前选择。
 *
 * 只做三件事：内置候选清单、并发探测、把选择写进 [BangumiClient] 与图片路由。
 * 探测不缓存到磁盘——用户点「检测」就是要看此刻的真实可达性。
 */
object BangumiMirrors {

    const val OFFICIAL_ID = "official"

    val Official = BangumiMirror(
        id = OFFICIAL_ID,
        name = "官方源",
        api = "https://api.bgm.tv",
        image = "https://lain.bgm.tv",
        note = "Bangumi 官方，部分网络环境不可达",
    )

    /** 内置兜底清单。**不是「最新镜像源」**——真正的线路清单来自镜像目录（见 [DIRECTORY_URL]）。 */
    val catalog: List<BangumiMirror> = listOf(
        Official,
        BangumiMirror(
            id = "anibt",
            name = "ANIBT 镜像",
            api = "https://bgmapi.anibt.net",
            image = "https://bgmimg.anibt.net",
            note = "社区维护，接口与图片路径与官方一致",
        ),
    )

    /**
     * 镜像目录（bangumi.wiki）的公开接口：`GET /api/v1/mirrors`。
     *
     * 2026-10-28 用户指出：bangumi.wiki **不是镜像站**，它是「哪些镜像现在能用」的目录站，
     * 目录本身用 JSON 发布线路清单（含每条线路的 api / lain 主机名），只读、无需鉴权。
     * 所以「获取最新镜像源」= 拉这个接口，而不是程序里写死的三条。
     */
    const val DIRECTORY_URL = "https://bangumi.wiki/api/v1/mirrors"
    private const val KEY_CATALOG = "mirror.catalog"

    private const val KEY_ID = "mirror.id"
    private const val KEY_API = "mirror.api"
    private const val KEY_IMAGE = "mirror.image"

    /** 当前生效线路。切换时同步给 [BangumiClient] 并清掉旧线路的响应缓存。 */
    @Volatile
    var current: BangumiMirror = Official
        private set

    /**
     * 已知的图片域名。图片路由本身**不靠白名单**（按 `/pic/cover/…` 路径判定，见
     * `RecommendImageRouting`），这里只用于展示与排查。
     */
    val knownImageHosts: Set<String>
        get() = buildSet {
            addAll(catalog.mapNotNull { hostOf(it.image) })
            add("lain.bangumi.tv")
            add("lain.bgm.tv")
        }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.lowercase()?.ifBlank { null }

    fun apply(mirror: BangumiMirror) {
        current = mirror
        BangumiClient.setApiBase(mirror.api)
    }

    fun applyAndSave(mirror: BangumiMirror, prefs: Preferences) {
        apply(mirror)
        runCatching {
            prefs.put(KEY_ID, mirror.id)
            prefs.put(KEY_API, mirror.api)
            prefs.put(KEY_IMAGE, mirror.image)
            prefs.flush()
        }
    }

    /** 启动时恢复上次线路；配置缺失或损坏一律回落官方。 */
    fun restore(prefs: Preferences) {
        val id = runCatching { prefs.get(KEY_ID, OFFICIAL_ID) }.getOrDefault(OFFICIAL_ID)
        val saved = catalog.firstOrNull { it.id == id } ?: run {
            val api = runCatching { prefs.get(KEY_API, "") }.getOrDefault("")
            val image = runCatching { prefs.get(KEY_IMAGE, "") }.getOrDefault("")
            if (id == "custom" && isHttps(api) && isHttps(image)) {
                BangumiMirror("custom", "自定义线路", api.trimEnd('/'), image.trimEnd('/'), "手动填写")
            } else Official
        }
        apply(saved)
    }

    fun custom(api: String, image: String): BangumiMirror? {
        val a = api.trim().trimEnd('/')
        val i = image.trim().trimEnd('/')
        if (!isHttps(a) || !isHttps(i)) return null
        return BangumiMirror("custom", "自定义线路", a, i, "手动填写")
    }

    fun isHttps(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 并发探测整份清单，返回结果与候选一一对应。单条最长 8 秒。 */
    suspend fun probeAll(list: List<BangumiMirror> = catalog): List<Pair<BangumiMirror, MirrorProbe>> = coroutineScope {
        list.map { mirror -> async { mirror to probe(mirror) } }.awaitAll()
    }

    // ── 镜像目录（bangumi.wiki/api/v1/mirrors）────────────────────────────

    /**
     * 拉一次实时线路清单。目录拉不到时退回 [fallback]（上一次的结果或内置兜底），
     * **绝不返回空**——空清单会让换源窗口一条线路都没有。
     */
    suspend fun discover(prefs: Preferences? = null, fallback: List<BangumiMirror> = loadCatalog(prefs)): List<BangumiMirror> =
        withContext(Dispatchers.IO) {
            val remote = runCatching { fetchDirectory() }.getOrNull()
            val merged = merge(remote.orEmpty(), fallback)
            if (!remote.isNullOrEmpty()) saveCatalog(prefs, merged)
            merged
        }

    /** 官方永远排第一；目录线路按返回顺序；内置兜底只补目录里没有的。 */
    private fun merge(remote: List<BangumiMirror>, fallback: List<BangumiMirror>): List<BangumiMirror> {
        val seen = mutableSetOf(Official.id)
        val out = mutableListOf(Official)
        (remote + fallback).forEach { mirror ->
            val key = hostOf(mirror.api) ?: mirror.id
            if (mirror.id == OFFICIAL_ID || !seen.add(key)) return@forEach
            out += mirror
        }
        return out
    }

    private suspend fun fetchDirectory(): List<BangumiMirror> = withTimeoutOrNull(10_000) {
        val request = HttpRequest.newBuilder(URI.create(DIRECTORY_URL))
            .timeout(Duration.ofSeconds(9))
            .header("User-Agent", "${AppVersion.UA_PRODUCT} (personal desktop comic reader)")
            .header("Accept", "application/json")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return@withTimeoutOrNull emptyList()
        if (!response.headers().firstValue("content-type").orElse("").contains("json", true)) return@withTimeoutOrNull emptyList()
        parseDirectory(response.body())
    } ?: emptyList()

    /**
     * 解析目录 JSON。每条线路的 `services` 里挑 `api` 与 `lain`（图床）两个主机名，
     * 缺哪个就按 `api.<base>` / `lain.<base>` 拼——两条路径结构跟官方完全一致。
     */
    internal fun parseDirectory(body: String): List<BangumiMirror> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        (root["mirrors"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { parseEntry(element.jsonObject) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseEntry(mirror: JsonObject): BangumiMirror? {
        val base = mirror["base"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null } ?: return null
        // `base` 是主机名，不是 URL：带 scheme / 路径 / 端口的一律视为脏数据，
        // 否则拼出来的 `https://api.http://…` 也能通过 isHttps。
        if (base.any { it == '/' || it == ':' || it == ' ' }) return null
        val services = (mirror["services"] as? JsonArray).orEmpty().map { it.jsonObject }
        fun serviceHost(id: String): String? = services
            .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }
            ?.let { it["hosts"] as? JsonArray }
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val api = "https://${serviceHost("api") ?: "api.$base"}"
        val image = "https://${serviceHost("lain") ?: "lain.$base"}"
        if (!isHttps(api) || !isHttps(image)) return null
        return BangumiMirror(
            id = "dir:" + (mirror["id"]?.jsonPrimitive?.contentOrNull ?: base),
            name = mirror["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: base,
            api = api,
            image = image,
            note = mirror["note"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "来自镜像目录",
        )
    }

    /** 上次拉到的清单落盘，目录站暂时打不开时换源窗口仍然有内容。 */
    fun saveCatalog(prefs: Preferences?, list: List<BangumiMirror>) {
        if (prefs == null) return
        runCatching {
            val data = buildJsonArray {
                list.filter { it.id != OFFICIAL_ID }.forEach { mirror ->
                    add(buildJsonObject {
                        put("id", mirror.id); put("name", mirror.name)
                        put("api", mirror.api); put("image", mirror.image); put("note", mirror.note)
                    })
                }
            }
            prefs.put(KEY_CATALOG, data.toString()); prefs.flush()
        }
    }

    fun loadCatalog(prefs: Preferences?): List<BangumiMirror> {
        if (prefs == null) return catalog
        val raw = runCatching { prefs.get(KEY_CATALOG, "") }.getOrDefault("")
        val saved = runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val api = o["api"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val image = o["image"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!isHttps(api) || !isHttps(image)) return@mapNotNull null
                BangumiMirror(
                    id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = o["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: hostOf(api).orEmpty(),
                    api = api, image = image,
                    note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
        return merge(saved, catalog)
    }

    /**
     * 探一条线路：接口拿一个稳定存在的条目（subject 2）当探针，再用它返回的封面验图片域名。
     *
     * 接口判据三条，缺一不可：HTTP 2xx、`Content-Type` 是 JSON、body 能解析出 `id`。
     * 只看状态码会把 Anubis 的挑战页（200 + text/html）当成可用。
     * 图片单独再探一次：官方 `api.bgm.tv` 与 `lain.bgm.tv` 是两个域名，可能一个通一个不通。
     */
    suspend fun probe(mirror: BangumiMirror): MirrorProbe = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = withTimeoutOrNull(10_000) {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create(mirror.api.trimEnd('/') + "/v0/subjects/2"))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "${AppVersion.UA_PRODUCT} (personal desktop comic reader)")
                    .header("Accept", "application/json")
                    .GET().build()
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }.getOrNull()
        }
        val response = result ?: return@withContext MirrorProbe.Down
        if (response.statusCode() !in 200..299) {
            // 401/403/429 说明线路本身通（接口要求鉴权或限流），仍算可达。
            return@withContext if (response.statusCode() in setOf(401, 403, 429)) MirrorProbe.Ok(elapsed(started)) else MirrorProbe.Down
        }
        val type = response.headers().firstValue("content-type").orElse("")
        if (!type.contains("json", true)) return@withContext MirrorProbe.Blocked
        val subject = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (subject?.get("id") == null) return@withContext MirrorProbe.Blocked
        val imageUrl = (subject["images"] as? JsonObject)?.let { images ->
            listOf("small", "common", "grid", "medium", "large")
                .firstNotNullOfOrNull { key -> images[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null } }
        }
        MirrorProbe.Ok(elapsed(started), imageOk = imageUrl == null || imageReachable(imageUrl))
    }

    private suspend fun imageReachable(url: String): Boolean = withTimeoutOrNull(8_000) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "${AppVersion.UA_PRODUCT} (personal desktop comic reader)")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..299
        }.getOrDefault(false)
    } ?: false

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    fun label(probe: MirrorProbe): String = when (probe) {
        is MirrorProbe.Ok -> if (probe.imageOk) "可用 · ${probe.latencyMs}ms" else "接口通 · 图片不可达"
        MirrorProbe.Blocked -> "被人机验证拦截"
        MirrorProbe.Down -> "不可达"
    }
}
