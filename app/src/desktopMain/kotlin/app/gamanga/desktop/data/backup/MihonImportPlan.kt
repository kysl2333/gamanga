package app.gamanga.desktop.data.backup

import app.gamanga.desktop.model.SourceType

/**
 * mihon 备份导入的**预览计划**（F-275，2026-10-29）。
 *
 * ## 为什么要有这一层
 *
 * 用户原话：「mihon 里面应该有很多没办法使用的源的漫画，需要换源和对齐章数的名字而不是第几章，
 * 但是完全搜索不到的或者只能用几个字部分搜索才能搜索到的要实现解决它，
 * 需要导入 mihon 备份后给用户选择还有改别称让用户自己修改的界面」。
 *
 * 旧实现是「解析完直接开干」：能认的源悄悄导入，认不出的默默跳过，只在最后甩一句
 * 「跳过 N 部」。用户看不到是哪 N 部、也没机会救。现在拆成两步：
 *
 * 1. [buildMihonPreview]——**不联网**，把每条备份整理成一行给用户看：
 *    认得出源且不在书架 = 可直接导入；认不出源 = 需要换源（给个默认别称去搜）；
 *    已经在书架 = 只回填观看历史。
 * 2. 用户在界面里勾选、改别称、点搜索挑到正确的那本，再按选择导入。
 *
 * 纯函数、不碰数据库也不碰网络，`MihonImportPlanTest` 覆盖。
 */

/** 一行备份在导入前的状态。 */
enum class MihonRowStatus {
    /** 已在书架：不重复入架，只按名字回填已读 / 进度。 */
    IN_LIBRARY,

    /** 源站认得（url 里能解析出源 + id）：可直接导入。 */
    READY,

    /** 认不出源站（mihon 里装了我们没有的扩展）：必须换源，用别称去搜。 */
    UNKNOWN_SOURCE,
}

/**
 * 预览里的一行。
 *
 * [alias] 是**搜索用别称**，默认 = 标题去掉装饰符（[defaultSearchAlias]），
 * 用户可以在导入向导里改成自己认得的短词（比如把超长轻小说标题改成「无限扭蛋」）。
 */
data class MihonImportRow(
    val url: String,
    val title: String,
    val alias: String,
    val source: SourceType?,
    val sourceId: String?,
    /** 备份里这一部有多少章（给用户判断「值不值得导」）。 */
    val chapterCount: Int,
    /** 备份里已读的章数。 */
    val readCount: Int,
    val status: MihonRowStatus,
    /** 备份里自带封面图（R-170）：为 true 时导入后直接用这张图，不去源站抓。 */
    val hasCoverImage: Boolean = false,
    /** 这一部在 mihon 里在书架（R-178）：决定它出现在哪一档导入范围里。 */
    val inLibrary: Boolean = true,
    /** 备份里有阅读痕迹（任一章已读 / 有进度）。 */
    val inHistory: Boolean = false,
) {
    /** 给用户看的一句状态说明（界面直接渲染，不再自己拼）。 */
    val statusText: String
        get() = when (status) {
            MihonRowStatus.IN_LIBRARY -> "已在书架 · 只回填进度"
            MihonRowStatus.READY -> "可直接导入"
            MihonRowStatus.UNKNOWN_SOURCE -> "需要换源"
        }
}

/** 预览结果：行 + 统计。 */
data class MihonImportPreview(
    val rows: List<MihonImportRow>,
    /** 解析阶段就坏掉的条目（连 url 都没有）。 */
    val broken: List<String>,
) {
    val total: Int get() = rows.size
    val readyCount: Int get() = rows.count { it.status == MihonRowStatus.READY }
    val inLibraryCount: Int get() = rows.count { it.status == MihonRowStatus.IN_LIBRARY }
    val needSourceCount: Int get() = rows.count { it.status == MihonRowStatus.UNKNOWN_SOURCE }
    val totalChapters: Int get() = rows.sumOf { it.chapterCount }
    val readChapters: Int get() = rows.sumOf { it.readCount }
}

/**
 * 把解析结果整理成预览行。
 *
 * [existingIds] 是本地库现有的 manga id 集合（`komiic:123` 这种），
 * 用来判「已在书架」——判断逻辑与导入时**同一套 id 拼法**，避免两处漂移。
 */
fun buildMihonPreview(
    parsed: MihonBackupResult,
    existingIds: Set<String>,
): MihonImportPreview {
    val rows = parsed.manga.map { m ->
        val mapping = mihonUrlToSource(m.url)
        val type = mapping?.first
        val sourceId = mapping?.second
        val mangaId = if (type != null && sourceId != null) mangaIdOf(type, sourceId) else null
        val status = when {
            mangaId != null && mangaId in existingIds -> MihonRowStatus.IN_LIBRARY
            mapping != null -> MihonRowStatus.READY
            else -> MihonRowStatus.UNKNOWN_SOURCE
        }
        MihonImportRow(
            url = m.url,
            title = m.title,
            alias = defaultSearchAlias(m.title),
            source = type,
            sourceId = sourceId,
            chapterCount = m.chapters.size,
            readCount = m.chapters.count { it.read || it.lastPageRead > 0L },
            status = status,
            hasCoverImage = m.coverBytes != null,
            inLibrary = m.inLibrary,
            inHistory = m.hasHistory,
        )
    }
    return MihonImportPreview(rows = rows, broken = parsed.skipped)
}

/** 本地库 manga id 的拼法（**唯一一处**：导入与预览都必须用它）。 */
fun mangaIdOf(type: SourceType, sourceId: String): String = "${type.name.lowercase()}:$sourceId"

/**
 * 一条导入选择（用户在界面里定下来的）。
 *
 * [source] / [sourceId] 为空 = 「用别称去搜」，由导入器在可用源里挑最像的一本；
 * 用户手动点过某个搜索结果时，这两项就是那一本。
 */
data class MihonImportChoice(
    val url: String,
    val alias: String,
    val source: SourceType? = null,
    val sourceId: String? = null,
    /**
     * 换源后**新源上的标题 / 封面**（R-169，2026-10-30）。
     *
     * 用户：「导入后的名字应该为后面替换源的名字……可能就是换源后名字是原来的名字导致
     * 书架漫画封面出不来」。原来的实现把备份里的标题写进书架，但封面 URL 用的是备份里的
     * mihon CDN 缩略图（多数已失效）→ 名字是旧的、图也出不来。现在以新源为准。
     */
    val pickedTitle: String? = null,
    val pickedCover: String? = null,
)

/**
 * 导入范围（R-171 / R-178，2026-10-30 用户最终口径）。
 *
 * ## 用户原话（R-178 更正了 R-171 的理解）
 *
 * 「我说的备份书架是指，只选择**书架的书**恢复，同时恢复阅读记录；
 *   而选择历史就是**把历史的书给勾选出来**恢复历史的书」。
 *
 * 所以两档的差别是**列哪些书**，不是「写不写阅读进度」：
 * - [SHELF_ONLY]（默认，用户说「大多数人一般都是恢复书架的」）→ 只列 mihon 书架里的书；
 * - [SHELF_AND_HISTORY] → 连只有阅读记录（不在书架）的书也列出来。
 *
 * ⚠️ 两档**都写阅读进度**：书架那一档用户明确要「同时恢复阅读记录」。
 * （R-171 时理解成「只恢复书架就不写进度」，导致书架里的书读到哪里全丢了。）
 */
enum class MihonImportMode {
    /** 只恢复书架里的书（含它们的阅读记录）。 */
    SHELF_ONLY,

    /** 书架 + 观看历史：把只有阅读记录的书也列出来一起恢复。 */
    SHELF_AND_HISTORY,
}

/** 这一档要不要列这一行（界面筛选用，导入器也用它兜底）。 */
fun MihonImportMode.includes(row: MihonImportRow): Boolean = when (this) {
    MihonImportMode.SHELF_ONLY -> row.inLibrary
    MihonImportMode.SHELF_AND_HISTORY -> row.inLibrary || row.inHistory
}

/** 导入过程中的进度（R-172）：`done/total` + 当前在导哪本。 */
data class MihonImportProgress(
    val done: Int,
    val total: Int,
    val title: String,
)

/** 搜索候选：换源时给用户挑的那几行。 */
data class MihonCandidate(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val coverUrl: String?,
    val author: String?,
    /** 3 = 标题完全一致；2 = 互相包含；1 = 只是搜到了。 */
    val score: Int,
) {
    /** 「包子漫画 · 被信赖的伙伴…」这种一行展示。 */
    fun label(sourceName: String): String = "$sourceName · $title"
}

/**
 * 标题相似度打分（纯函数，界面与导入器共用同一套判据）。
 *
 * 归一化后：完全相等 3；互相包含 2；否则 1（搜到了但不像）。
 *
 * 注意**不复用** [normalizeChapterName]：那套会剥掉「话/章/集/回/卷」这些字，
 * 对标题是破坏性的（有些书名里就有「卷」）。标题只要去装饰符、压空白、转小写即可。
 */
fun titleMatchScore(a: String, b: String): Int {
    val x = normalizeTitle(a)
    val y = normalizeTitle(b)
    if (x.isBlank() || y.isBlank()) return 1
    if (x == y) return 3
    if (x.contains(y) || y.contains(x)) return 2
    return 1
}

/** 标题归一化：去装饰符/标点 → 去空白 → 小写。 */
fun normalizeTitle(raw: String): String =
    app.gamanga.desktop.data.SearchKeywords.cleaned(raw)
        .replace(Regex("""\s+"""), "")
        .lowercase()

/**
 * 候选排序用的**细粒度**匹配分（R-179，2026-10-30 用户）。
 *
 * ## 用户原话
 *
 * 「并没有优先放第一位，**越靠前的文字优先级越高**」。
 *
 * 上一版用 [titleMatchScore]（3 完全一致 / 2 互相包含 / 1 只是搜到）排序——
 * 「包含」这一档内部全是 2，谁先回来谁靠前；于是搜「重骑士」时
 * 《被放逐的重骑士…》（第 4 个字才出现）可能排在《重骑士与魔剑》（开头就是）前面。
 *
 * ## 分档（越大越靠前）
 *
 * | 情况 | 分 | 说明 |
 * |---|---|---|
 * | 完全一致 | 1000 | |
 * | 候选以搜索词**开头** | 900 | 「越靠前」的最高形态 |
 * | 搜索词出现在候选中间 | 800 − 位置 | 出现得越早分越高（最多扣 60） |
 * | 候选是搜索词的**开头** | 500 | 候选比搜索词短：`重骑士` 搜到 `重骑士` 的截断名 |
 * | 候选出现在搜索词中间 | 400 − 位置 | 更差一档 |
 * | 只是被源站搜到 | 0 | 完全不沾边 |
 *
 * 位置扣分封顶 60：保证「中间出现」永远压不过「开头出现」，但同一档内部仍按位置排。
 */
fun titleMatchRank(query: String, candidate: String): Int {
    val q = normalizeTitle(query)
    val c = normalizeTitle(candidate)
    if (q.isBlank() || c.isBlank()) return 0
    if (q == c) return 1000
    if (c.startsWith(q)) return 900
    val at = c.indexOf(q)
    if (at >= 0) return 800 - at.coerceAtMost(60)
    if (q.startsWith(c)) return 500
    val at2 = q.indexOf(c)
    if (at2 >= 0) return 400 - at2.coerceAtMost(60)
    return 0
}
