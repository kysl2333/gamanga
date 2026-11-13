package app.gamanga.desktop.model

/**
 * 首发源标识（ADR-0002 Locked details：Komiic + GoDa 首发，Komga 二期，Mangabz/拷贝漫画三期）。
 */
enum class SourceType { KOMIIC, GODA, KOMGA, MANGABZ, COPYMANGA }

/** 书架条目：一本被收藏的漫画（与源站的对应关系由 [source] + [sourceMangaId] 决定）。 */
data class MangaEntry(
    val id: String,
    val title: String,
    val source: SourceType,
    val sourceMangaId: String,
    val coverUrl: String? = null,
    val author: String? = null
)

/**
 * 章节（阅读进度的最小单位）。
 *
 * [pageCount] 每章页数（#30）：0 = 未知。Komiic 导入时就有；G社 要等阅读器
 * 取到整章图片后回填。详情页章节列表据此显示「N 页」。
 */
data class Chapter(
    val id: String,
    val mangaId: String,
    val name: String,
    val index: Int,
    val read: Boolean = false,
    val pageCount: Int = 0,
    /**
     * 「点过」的时间戳（0 = 没点过）。F-39。
     *
     * **与 [read] 是两件事**：点过 = 打开过这一话（详情页文字变灰），已读 = 翻到了结尾。
     * 「标记未读」会把两者一起清掉。
     */
    val lastOpenedAt: Long = 0
)

/** 阅读进度。 */
data class ReadingProgress(
    val mangaId: String,
    val chapterId: String,
    val page: Int,
    val totalPages: Int
)

/**
 * 历史页的一行（排期第 4 项）：一次阅读记录 + 展示所需的书名/封面/源信息。
 *
 * [page] 是 0-based 页下标，UI 展示时 +1；[percent] 是阅读进度百分比（无页数时为 0）。
 */
data class HistoryEntry(
    val mangaId: String,
    val title: String,
    val coverUrl: String?,
    val source: SourceType,
    val sourceMangaId: String,
    val chapterId: String,
    val chapterName: String?,
    val page: Int,
    val totalPages: Int,
    val updatedAt: Long,
) {
    val percent: Int
        get() = if (totalPages <= 0) 0
        else ((page + 1) * 100 / totalPages).coerceIn(0, 100)
}
