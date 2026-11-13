package app.gamanga.desktop.source

import app.gamanga.desktop.data.normalizeBases
import app.gamanga.desktop.model.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Komiic 源适配（M3）。
 *
 * 走站点自己的 GraphQL 接口 `POST https://komiic.cc/api/query`（前端 SPA 用的同一个），
 * 免登录即可取公开内容；登录态只在成人内容 / 收藏夹时才需要，届时把 M2 导出的
 * cookie 串挂到 [cookies] 上即可。
 *
 * 图片是**防盗链**的：`imagesByChapterId` 只给 kid，必须换 [PageImage.ticket]，
 * 下载时放进 `X-Image-Ticket` 请求头（票据 10 分钟过期，403 即需重取）。
 * 一次 `imageTicketsByChapterId` 就能拿到整章的 url + ticket，避免 N+1。
 */
class KomiicSource(
    /** 站点候选（主站在前，连不上换下一个）；接口固定在 `<base>/api/query`。 */
    // 与 SourceConfig.DEFAULTS 保持一致（正常启动由 Sources 显式传值）
    siteBases: List<String> = listOf("https://komiic.cc"),
    /** 借会话（M2 的 cookie 导出通道）；默认无登录态。 */
    private val cookies: () -> String? = { null },
    /** 路径模板（F-36）：`api` = GraphQL 端点路径；空 = 内置默认。 */
    paths: Map<String, String> = emptyMap(),
) : SourceAdapter {

    private val apiPath: String = paths["api"]?.trim()?.ifBlank { null } ?: "/api/query"

    private val bases: List<String> = normalizeBases(siteBases).ifEmpty { listOf("https://komiic.cc") }

    private val base: String get() = bases.first()

    override val type: SourceType = SourceType.KOMIIC
    override val displayName: String = "Komiic"

    /** 「测试」在 Komiic 搜不到东西（实测 0 条），用「漫画」探活。 */
    override val probeKeyword: String = "漫画"

    /**
     * 用户口径（2026-10-25）：「能进登录页或网站能通就算绿灯」。
     * Komiic 搜索是按标题匹配，0 条属正常结果，不该判黄灯；抛异常仍是红灯。
     */
    override val probeEmptyIsSuspicious: Boolean = false

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
        val data = gql(
            SEARCH,
            buildJsonObject {
                put("kw", keyword)
                put("limit", pageSize)
                put("offset", page * pageSize)
            }
        )
        return data.arr("searchComics")?.mapNotNull { it as? JsonObject }?.map(::toManga).orEmpty()
    }

    override suspend fun detail(mangaId: String): SourceManga? {
        val data = gql(DETAIL, buildJsonObject { put("id", mangaId) })
        return data.obj("comicById")?.let(::toManga)
    }

    override suspend fun chapters(mangaId: String): List<SourceChapter> {
        val data = gql(CHAPTERS, buildJsonObject { put("id", mangaId) })
        // **服务端返回的不是阅读顺序**（实测 000,001,239,268,274,002,003 混在一起），
        // 必须自己按 serial 的数值排：serial 形如 "000"/"012"/"2.2"，用 Double 兼容小数话。
        // 另外同一本书会同时有「话」和「卷（单行本）」两套编号，serial 都只有数字
        // （01/01、02/02…），**必须用 `type` 区分**，否则话卷交错（用户 2026-11-11 截图）。
        val list = data.arr("chaptersByComicId")?.mapNotNull { el ->
            (el as? JsonObject)?.let {
                SourceChapter(
                    id = it.str("id") ?: return@mapNotNull null,
                    name = it.str("serial") ?: "第 ? 话",
                    index = 0, // 排完再回填
                    pageCount = it.int("size") ?: 0,
                    kind = when (it.str("type")?.lowercase()) {
                        "chapter" -> ChapterKind.CHAPTER
                        "book", "volume" -> ChapterKind.VOLUME
                        "special", "extra", "omake", "oneshot" -> ChapterKind.EXTRA
                        else -> ChapterKind.UNKNOWN
                    },
                )
            }
        }.orEmpty()
        // 卷在前、话在后（与拷贝漫画同一口径）；同组内按 serial 数值升序。
        return list.sortedWith(
            compareBy(
                { it.kind.sortRank() },
                { serialValue(it.name) },
                { it.name },
            )
        ).mapIndexed { i, ch -> ch.copy(index = i) }
    }

    override suspend fun pages(chapterId: String): List<PageImage> {
        val data = gql(TICKETS, buildJsonObject { put("c", chapterId) })
        return data.arr("imageTicketsByChapterId")?.mapNotNull { el ->
            (el as? JsonObject)?.let {
                val url = it.str("url") ?: return@mapNotNull null
                PageImage(url, it.str("ticket"), it.int("width") ?: 0, it.int("height") ?: 0)
            }
        }.orEmpty()
    }

    // ---- GraphQL ----

    private suspend fun gql(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val headers: (String) -> Map<String, String> = { site ->
            buildMap {
                put("Origin", site)
                put("Referer", "$site/")
                cookies()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
        }
        val root = Json.parseToJsonElement(
            postJsonWithFallback(bases, apiPath, body, SourceType.KOMIIC, "Komiic", headers)
        ).jsonObject
        (root["errors"] as? JsonArray)?.firstOrNull()?.let {
            throw IllegalStateException("Komiic 接口报错：${(it as? JsonObject)?.str("message")}")
        }
        return root["data"]?.let { it as? JsonObject }
            ?: throw IllegalStateException("Komiic 响应无 data：$root".take(200))
    }

    private fun toManga(o: JsonObject) = SourceManga(
        sourceId = o.str("id") ?: "",
        title = o.str("title") ?: "(无标题)",
        coverUrl = o.str("imageUrl"),
        author = o.arr("authors")?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")
            ?.takeIf { it.isNotBlank() },
        status = o.str("status"),
        year = o.int("year"),
        description = o.str("description"),
        tags = o.arr("categories")?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty()
    )

    private companion object {
        /**
         * 章节排序键：取 serial 里的数字部分。
         * "000"→0、"012"→12、"2.2"→2.2；拿不到数字的排到最后（不丢章节）。
         */
        fun serialValue(serial: String): Double =
            serial.toDoubleOrNull()
                ?: Regex("""\d+(\.\d+)?""").find(serial)?.value?.toDoubleOrNull()
                ?: Double.MAX_VALUE

        const val FIELDS = "id title status year imageUrl authors{name} categories{name} description"

        const val SEARCH = """
            query(${'$'}kw:String!,${'$'}limit:Int!,${'$'}offset:Int!){
              searchComics(
                pagination:{limit:${'$'}limit,offset:${'$'}offset,orderBy:DATE_UPDATED,asc:false},
                keyword:${'$'}kw, adult:false
              ){ $FIELDS }
            }"""

        const val DETAIL = "query(${'$'}id:ID!){ comicById(comicId:${'$'}id){ $FIELDS } }"

        const val CHAPTERS =
            "query(${'$'}id:ID!){ chaptersByComicId(comicId:${'$'}id){ id serial type size } }"

        const val TICKETS =
            "query(${'$'}c:ID!){ imageTicketsByChapterId(chapterId:${'$'}c){ kid url ticket width height } }"
    }
}

// ---- JsonObject 取值小工具（源适配层内部用，避免到处写类型转换） ----

internal fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

/** order 之类的数值字段：G社 API 偶尔下发小数（实测 240.1/270.1/275.1），
 *  intOrNull 对小数直接返回 null，需要按 double 收。 */
internal fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        ?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
