package app.gamanga.desktop.data

import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 浏览历史的一条：**在浏览页点开过的一本漫画**。
 *
 * ## 为什么单独建一个模型，不复用 `HistoryEntry`
 *
 * 用户 2026-10-27 明确：「浏览页历史只记录在浏览页点过的漫画封面，仅用于浏览页封面展示，
 * 与记录阅读进度的历史页相互独立」。
 *
 * 两者的数据源根本不同：
 * - `HistoryEntry` 来自 `readingProgress`（读过、有页码、有章节），是**阅读进度**；
 * - 本模型来自「在浏览页点了一下封面」，**没读过也算**（点开看完简介就退出的书也该留痕）。
 *
 * 硬塞进 `readingProgress` 会污染历史页和「继续阅读」——那是另一套语义。
 */
data class BrowseHistoryEntry(
    val source: SourceType,
    val sourceMangaId: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    /** 最近一次点开的时间戳（毫秒）。列表按它倒序。 */
    val visitedAt: Long = 0L,
) {
    /**
     * 去重键：同一本（同源 + 同源站 id）只留最近一次。
     *
     * 不用本地 id（`<source>:<sourceMangaId>`）而自己拼，是为了**不依赖库里的 manga 行**——
     * 书架外的书在库里可能还没有行，但浏览历史照样要能记。
     */
    val key: String get() = "${source.name.lowercase()}:$sourceMangaId"
}

/**
 * 浏览历史仓库（持久化在 `appPrefs` 的 `browse.history` 键里，JSON 数组）。
 *
 * ## 为什么不建表
 *
 * 只是一串「封面 + 标题」，没有外键、没有查询、没有联表，建表要动 schema +
 * 迁移梯子（见 [app.gamanga.desktop.core.db.GamangaDatabaseFactory.STEPS] 的规矩），
 * 收益为零。`appPrefs` 本来就是干这个的（置顶列表也在这儿）。
 *
 * ## 读写策略
 *
 * 写用**同步** `setPref`：一次点击只写一次（不是每帧），同步能保证「点完就落库」，
 * 与 [LibraryRepository.togglePin] 同一口径。上限 [MAX_ENTRIES] 条，超出丢最旧的——
 * 这只是个「最近浏览」的展示区，无限增长没意义。
 */
class BrowseHistoryStore(private val repo: LibraryRepository) {

    private val _items = MutableStateFlow(decode(repo.getPref(LibraryRepository.PREF_BROWSE_HISTORY)))

    /** 浏览历史（最近点开的在前）。 */
    val items: StateFlow<List<BrowseHistoryEntry>> = _items.asStateFlow()

    /**
     * 记一条：同一本已存在时**只更新时间并提到最前**（不产生重复封面）。
     *
     * @param entry 只需要 `source` / `sourceMangaId` / `title` / `coverUrl` / `author`，
     *              `visitedAt` 由这里盖当前时间。
     */
    fun record(entry: BrowseHistoryEntry) {
        val now = System.currentTimeMillis()
        val next = (listOf(entry.copy(visitedAt = now)) + _items.value.filterNot { it.key == entry.key })
            .take(MAX_ENTRIES)
        commit(next)
    }

    /** 删一条（右键「删除记录」）。 */
    fun remove(key: String) {
        removeAll(listOf(key))
    }

    /** 批量删（浏览历史多选删除）。一次写盘，不做 N 次 pref 写。 */
    fun removeAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val drop = keys.toSet()
        val next = _items.value.filterNot { it.key in drop }
        if (next.size != _items.value.size) commit(next)
    }

    /** 清空。 */
    fun clear() {
        if (_items.value.isNotEmpty()) commit(emptyList())
    }

    /**
     * 回撤：把刚删掉的条目放回去（`删除记录` / 多选删除 / `清除全部` 共用）。
     *
     * ## 为什么按 `visitedAt` 重排，而不是插回原来的下标
     *
     * 这份列表本来就**只有一条排序规则**——`visitedAt` 倒序（[record] 靠它把刚点的提到最前，
     * UI 直接按仓库给的顺序渲染，不看别的字段）。所以「插回原位置」与「重新排一遍」
     * 得到的是同一个列表；而重排不用记原下标，也不用担心删除到回撤这几秒里
     * 又点开了别的书导致下标失效。等价且简单，就选简单的。
     *
     * ## 为什么超出 [MAX_ENTRIES] 丢的是最旧的
     *
     * 回撤的对象是「刚刚被删掉的」，它们的 `visitedAt` 比现存的大多数都新，
     * 重排后自然落在前面；被 `.take` 挤出去的只会是更早的条目——
     * 与 [record] 的「超出丢最旧」同一口径，不会出现「回撤反而把新记录挤掉」。
     *
     * ## 幂等
     *
     * 已经在列表里的 key 直接跳过：回撤前用户可能又点开了同一本（[record] 已把它记回来），
     * 这时再补一条就是重复封面。所以重复点「撤销」不会让列表变长。
     */
    fun restore(entries: List<BrowseHistoryEntry>) {
        if (entries.isEmpty()) return
        val existing = _items.value
        val back = entries.filterNot { e -> existing.any { it.key == e.key } }
        if (back.isEmpty()) return
        commit((existing + back).sortedByDescending { it.visitedAt }.take(MAX_ENTRIES))
    }

    private fun commit(next: List<BrowseHistoryEntry>) {
        _items.value = next
        repo.setPref(LibraryRepository.PREF_BROWSE_HISTORY, encode(next))
    }

    companion object {
        /** 最多留多少条。60 条 ≈ 4K 屏 8 列的 8 排，够翻的了。 */
        const val MAX_ENTRIES = 60

        /**
         * 序列化成 JSON 数组。
         *
         * 手写而不是 `@Serializable`：项目只引了 kotlinx-serialization-json 的树 API
         * （见 `gradle/libs.versions.toml` 的注释，没上编译器插件），和
         * [app.gamanga.desktop.data.backup.BackupManager] 保持同一种写法。
         */
        fun encode(items: List<BrowseHistoryEntry>): String = buildJsonArray {
            items.forEach { e ->
                add(
                    buildJsonObject {
                        put("source", e.source.name)
                        put("sourceMangaId", e.sourceMangaId)
                        put("title", e.title)
                        put("coverUrl", e.coverUrl)
                        put("author", e.author)
                        put("visitedAt", e.visitedAt)
                    }
                )
            }
        }.toString()

        /**
         * 反序列化。**任何异常都退化成空列表**——这条 pref 坏掉不能影响启动，
         * 更不能像 schema 校验那样把整个库判成损坏。
         */
        fun decode(json: String?): List<BrowseHistoryEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val type = o["source"]?.jsonPrimitive?.contentOrNull
                        ?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
                        ?: return@mapNotNull null
                    val sid = o["sourceMangaId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    BrowseHistoryEntry(
                        source = type,
                        sourceMangaId = sid,
                        title = title,
                        coverUrl = o["coverUrl"]?.jsonPrimitive?.contentOrNull,
                        author = o["author"]?.jsonPrimitive?.contentOrNull,
                        visitedAt = o["visitedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}
