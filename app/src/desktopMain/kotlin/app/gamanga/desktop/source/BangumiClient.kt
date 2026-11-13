package app.gamanga.desktop.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import app.gamanga.desktop.core.AppVersion
import java.time.Duration

/** Official directory API; deliberately separate from readable manga sources. */
object BangumiClient {
    data class Item(
        val id: Int, val title: String, val coverUrl: String?, val score: Double?, val rank: Int?,
        val original: String = "", val summary: String = "", val tags: List<String> = emptyList(),
        val info: List<Pair<String, String>> = emptyList(), val votes: Map<Int, Int> = emptyMap(),
        val collections: Map<String, Int> = emptyMap(), val platform: String = "", val relation: String = "",
    )
    data class Page(val items: List<Item>, val total: Int)
    data class Person(val id: Int, val name: String, val image: String?, val role: String, val character: Boolean)
    data class Collection(val item: Item, val type: Int, val rate: Int, val episodes: Int, val volumes: Int)
    /**
     * 接口根地址。官方 `api.bgm.tv` 在部分网络下不可达，由 [BangumiMirror] 在启动与切换线路时改写。
     * 图片域名不在这里——见 `RecommendImageRouting`。
     */
    @Volatile
    private var base = BangumiMirrors.Official.api
    private val json = Json { ignoreUnknownKeys = true }

    /** 切线路后旧线路的响应一律作废，否则会拿官方缓存喂给镜像。 */
    fun setApiBase(value: String) {
        val normalized = value.trim().trimEnd('/').ifBlank { BangumiMirrors.Official.api }
        if (normalized == base) return
        base = normalized
        cache.clear()
    }

    val apiBase: String get() = base
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val gate = Mutex()
    private var lastRequest = 0L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private suspend fun request(path: String, body: String? = null, token: String = "", method: String = if (body == null) "GET" else "POST", forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val cacheable = token.isBlank() && (method == "GET" || path.startsWith("/v0/search/subjects"))
        val cacheKey = path + (body ?: "")
        val lifetime = if (path.matches(Regex("/v0/subjects/\\d+"))) 86_400_000 else 600_000
        if (cacheable && !forceRefresh) cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < lifetime }?.let { return@withContext it.second }
        gate.withLock {
            delay((1000 - (System.nanoTime() - lastRequest) / 1_000_000).coerceAtLeast(0))
            lastRequest = System.nanoTime()
            val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "${AppVersion.UA_PRODUCT} (personal desktop comic reader)")
                .header("Accept", "application/json")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.header("Content-Type", "application/json")
            builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Bangumi HTTP ${response.statusCode()}" }
            response.body().also { if (cacheable) { if (cache.size >= 48) cache.clear(); cache[cacheKey] = System.currentTimeMillis() to it } }
        }
    }
    suspend fun search(keyword: String = "", category: String = "全部", sort: String = "rank", offset: Int = 0, forceRefresh: Boolean = false): Page {
        val body = buildJsonObject {
            put("keyword", keyword); put("sort", sort)
            putJsonObject("filter") {
                putJsonArray("type") { add(1) }; putJsonArray("tag") { add("漫画"); if (category != "全部") add(category) }
                put("nsfw", false)
                if (keyword.isBlank() && sort == "rank") putJsonArray("rank") { add(">0") }
            }
        }
        return parsePage(request("/v0/search/subjects?limit=30&offset=$offset", body.toString(), forceRefresh = forceRefresh))
    }
    suspend fun ranking(limit: Int = 24) = search().items.take(limit)
    suspend fun detail(id: Int) = parseItem(json.parseToJsonElement(request("/v0/subjects/$id")).jsonObject)!!
    suspend fun related(id: Int): List<Item> = json.parseToJsonElement(request("/v0/subjects/$id/subjects")).jsonArray.mapNotNull { parseItem(it.jsonObject) }
    suspend fun people(id: Int, characters: Boolean): List<Person> = json.parseToJsonElement(request("/v0/subjects/$id/${if (characters) "characters" else "persons"}")).jsonArray.mapNotNull {
        val o = it.jsonObject
        val key = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        Person(key, o.text("name"), o["images"]?.let { v -> (v as? JsonObject)?.text("medium") }?.ifBlank { null }, o.text("relation"), characters)
    }
    data class Account(val username: String, val avatar: String?)
    suspend fun account(token: String): Account {
        val o = json.parseToJsonElement(request("/v0/me", token = token)).jsonObject
        return Account(o.text("username"), (o["avatar"] as? JsonObject)?.text("medium")?.ifBlank { null })
    }
    suspend fun me(token: String): String = account(token).username
    suspend fun saveStatus(id: Int, type: Int, rate: Int, token: String) {
        val body = buildJsonObject { put("type", type); put("rate", rate) }
        request("/v0/users/-/collections/$id", body.toString(), token, "POST")
    }
    suspend fun collections(username: String, token: String, offset: Int, known: Map<Int, Item> = emptyMap(), excluded: MutableSet<Int> = mutableSetOf()): Pair<List<Collection>, Int> {
        val o = json.parseToJsonElement(request("/v0/users/${URLEncoder.encode(username, "UTF-8")}/collections?subject_type=1&limit=50&offset=$offset", token = token)).jsonObject
        val items = mutableListOf<Collection>()
        for (element in (o["data"] as? JsonArray).orEmpty()) {
            val c = element.jsonObject
            val subject = c["subject"] as? JsonObject ?: continue
            val slim = parseItem(subject) ?: continue
            if (slim.id in excluded) continue
            // Reuse persistent classification; only unseen subjects need a detail request.
            val item = known[slim.id] ?: run {
                val full = json.parseToJsonElement(request("/v0/subjects/${slim.id}")).jsonObject
                if (!isComic(full)) { excluded.add(slim.id); null } else parseItem(full)
            } ?: continue
            items.add(Collection(item, c.number("type"), c.number("rate"), c.number("ep_status"), c.number("vol_status")))
        }
        return items to o.number("total")
    }
    suspend fun save(id: Int, type: Int, rate: Int, episodes: Int, volumes: Int, token: String) {
        val body = buildJsonObject { put("type", type); put("rate", rate); put("ep_status", episodes); put("vol_status", volumes) }
        request("/v0/users/-/collections/$id", body.toString(), token, "POST")
    }
    fun parseRanking(body: String): List<Item> {
        val root = json.parseToJsonElement(body)
        val array = if (root is JsonArray) root else (root as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::parseItem) }
    }
    fun parsePage(body: String): Page {
        val root = json.parseToJsonElement(body).jsonObject
        return Page((root["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf(::isComic)?.let(::parseItem) }, root.number("total"))
    }
    fun isComic(o: JsonObject): Boolean {
        if (o.number("type") !in listOf(0, 1)) return false
        val platform = o.text("platform")
        val category = o["book_category"]?.jsonPrimitive?.intOrNull
        if (category != null) return category == 1001
        if (platform.contains("小说") || platform.contains("小說") || platform.contains("画集")) return false
        return platform.contains("漫画") || platform.contains("漫畫")
    }
    fun parseItem(o: JsonObject): Item? {
        val id = o["id"]?.jsonPrimitive?.intOrNull ?: return null
        val title = o.text("name_cn").ifBlank { o.text("name") }.ifBlank { return null }
        val rating = o["rating"] as? JsonObject
        val counts = rating?.get("count") as? JsonObject
        val collection = o["collection"] as? JsonObject
        return Item(id, title, (o["images"] as? JsonObject)?.let { it.text("large").ifBlank { it.text("medium") } }?.ifBlank { null },
            rating?.get("score")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
            (rating?.get("rank") ?: o["rank"])?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            o.text("name"), o.text("summary"), (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
            (o["infobox"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { info -> info.text("key") to valueText(info["value"]) } },
            (1..10).associateWith { counts?.number(it.toString()) ?: 0 }, collection?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }.orEmpty(), o.text("platform"), o.text("relation"))
    }
    private fun valueText(v: JsonElement?): String = when(v) { is JsonPrimitive -> v.content; is JsonArray -> v.joinToString("、") { if (it is JsonObject) it.text("v") else valueText(it) }; else -> "" }
    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    fun searchTitle(title: String) = title.replace(Regex("第\\s*\\d+\\s*[卷话話集册]|[（(]\\d+[）)]|Vol\\.?\\s*\\d+", RegexOption.IGNORE_CASE), "").trim()
}
