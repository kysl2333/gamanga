package app.gamanga.desktop.source

/**
 * 章节「属于哪一套编号体系」。
 *
 * 真实反馈：「有些源会分话和卷，结果话和卷都在一起了」——
 * 同一本书里「话」和「卷（单行本）」是两套独立的编号，旧实现只取章节名排序，
 * 于是 `01(52页) / 01(192页) / 02(26页) / 02(192页)…` 交错排成一条链，
 * 详情页看上去就是一堆重复编号，用户分不清点哪个。
 *
 * 判定分三层，**能用上一层的绝不看下一层**（越靠前越准）：
 *  1. 源结构自带类型：Komiic 的 `type`（`chapter` / `book`）；拷贝漫画的 `groups` 分组名。
 *  2. 章节名里的显式标记：`第3卷` / `Vol.3` / `第12話` / `番外篇`。
 *  3. 数据自证据（见 [resolveChapterKinds]）：同一编号出现多次且页数量级差一倍以上。
 */
enum class ChapterKind {
    /** 一话（连载话）。 */
    CHAPTER,

    /** 一卷 / 单行本。 */
    VOLUME,

    /** 番外 / 特别篇 / 贺图 / 附录 —— 不占话号，也不属于卷。 */
    EXTRA,

    /** 名字和结构都给不出证据。**不猜**，显示时按「话」处理。 */
    UNKNOWN,
}

/**
 * 显示顺序权重：卷 → 话 → 其它（番外 / 无编号附录 / 未判定）。
 *
 * 番外不占编号，必须和「无编号附录」同档，否则会插到有编号的话前面
 * （CopymangaSourceTest 里 `第1卷/序幕/开幕/终幕/番外` 的顺序就靠这条）。
 */
fun ChapterKind.sortRank(): Int = when (this) {
    ChapterKind.VOLUME -> 0
    ChapterKind.CHAPTER -> 1
    ChapterKind.EXTRA, ChapterKind.UNKNOWN -> 2
}

/** 番外类标记：先于卷/话判定，避免「特别篇」被算进卷里。 */
private val ExtraMark = Regex(
    "番外|特別篇|特别篇|番外篇|外传|外傳|贺图|賀圖|附录|附錄|后记|後記|短篇|休刊|休載|公告|omake",
    RegexOption.IGNORE_CASE,
)

/** 卷标记：`第3卷` / `3卷` / `卷3` / `Vol.3` / `Book 3` / 中文数字卷。 */
private val VolumeMark = Regex(
    "(?:第\\s*[0-9一二三四五六七八九十百零〇两兩]+\\s*[卷冊册])" +
        "|(?:[0-9]+\\s*[卷冊册])" +
        "|(?:[卷冊册]\\s*[0-9]+)" +
        "|(?:vol\\.?\\s*[0-9]+)" +
        "|(?:book\\.?\\s*[0-9]+)",
    RegexOption.IGNORE_CASE,
)

/** 话标记：`第12話` / `12话` / 纯数字（Komiic 的 serial `"012"`）。 */
private val ChapterMark = Regex(
    "(?:[0-9]+\\s*[话話])" +
        "|(?:第\\s*[0-9一二三四五六七八九十百零〇两兩]+\\s*[话話])" +
        "|(?:^[0-9]+(?:\\.[0-9]+)?$)",
)

private val NumberOnly = Regex("""^[0-9]+(?:\.[0-9]+)?$""")

/**
 * 只看章节名判类型。**纯函数**，不做跨章节推断。
 *
 * 纯数字名字（`"01"` / `"012"`）默认按「话」——Komiic 的卷靠 `type` 字段纠正，
 * 没有类型的源交给 [resolveChapterKinds] 的结构证据纠正。
 */
fun chapterKindOf(name: String): ChapterKind {
    val s = name.trim()
    if (s.isEmpty()) return ChapterKind.UNKNOWN
    if (ExtraMark.containsMatchIn(s)) return ChapterKind.EXTRA
    if (VolumeMark.containsMatchIn(s)) return ChapterKind.VOLUME
    if (ChapterMark.containsMatchIn(s)) return ChapterKind.CHAPTER
    return ChapterKind.UNKNOWN
}

/** 名字里除了数字什么都没有（Komiic 的 `"01"` / `"012"`）——这种最需要结构证据。 */
private fun isNumberOnly(name: String): Boolean = NumberOnly.matches(name.trim())

/** 章节名里的第一个编号（话号或卷号）；取不到返回 null。 */
internal fun chapterNumberOrNull(name: String): Double? {
    Regex("""[0-9]+(?:\.[0-9]+)?""").find(name)?.value?.toDoubleOrNull()?.let { return it }
    val token = Regex("""第\s*([零〇一二三四五六七八九十百千万两兩]+)\s*[卷冊册话話前]""")
        .find(name)?.groupValues?.get(1) ?: return null
    return chineseNumber(token)?.toDouble()
}

/** 中文数字 → 数值（"一百二十三" → 123）。取不到返回 null。 */
internal fun chineseNumber(token: String): Int? {
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    var total = 0
    var section = 0
    var number = 0
    var seen = false
    for (c in token) {
        when {
            c in digits -> { number = number * 10 + digits.getValue(c); seen = true }
            c in units -> {
                section += (if (number == 0) 1 else number) * units.getValue(c)
                number = 0
                seen = true
            }
            c == '万' -> {
                total += (section + number) * 10_000
                section = 0
                number = 0
                seen = true
            }
        }
    }
    return if (seen) total + section + number else null
}

/** 判定卷时要求的页数比：大的是小的两倍以上，才敢说「这是卷不是话」。 */
private const val VOLUME_PAGE_RATIO = 2.0

/** 卷的页数下限：一话通常几十页，短于这个不可能是整卷。 */
private const val VOLUME_MIN_PAGES = 80

/**
 * 补全 [SourceChapter.kind]：源里已经给出确定类型的原样保留，剩下的按
 * 「名字标记 → 数据自证据」依次推断。
 *
 * 数据自证据（最通用的一层，不依赖任何源的特有字段）：
 * 同一本书里**同一个编号出现多次**、且其中一条的页数是另一条的 **2 倍以上**
 * （多的一方还 ≥ 80 页），那么页数大的那条是**卷**、小的是**话**。
 * 典型就是 Komiic 的卷与话同号：`01(52页)` 与 `01(192页)`。
 *
 * 为什么不用「页数中位数二分」这类更强的手段：会把页数偏厚的连载话误判成卷，
 * 误判的代价（读者点进「卷」发现只有一话）比漏判更大，所以只认**成对重复编号**这种硬证据。
 */
fun resolveChapterKinds(chapters: List<SourceChapter>): List<SourceChapter> {
    if (chapters.isEmpty()) return chapters
    // 第一层：名字显式标记（EXTRA / VOLUME 一锤定音）
    var list = chapters.map {
        if (it.kind == ChapterKind.UNKNOWN) it.copy(kind = chapterKindOf(it.name)) else it
    }

    // 第二层：成对重复编号 + 页数量级差。只改「名字看不出是卷」的那部分，
    // 已经明确写了「第3卷」的不再动。
    val ambiguous = list.indices.filter {
        val k = list[it].kind
        k == ChapterKind.UNKNOWN || (k == ChapterKind.CHAPTER && isNumberOnly(list[it].name))
    }
    if (ambiguous.size < 2) return list

    val byNumber = LinkedHashMap<Double, MutableList<Int>>()
    for (i in ambiguous) {
        val n = chapterNumberOrNull(list[i].name) ?: continue
        byNumber.getOrPut(n) { mutableListOf() } += i
    }
    val promote = mutableSetOf<Int>()
    for ((_, idxs) in byNumber) {
        if (idxs.size < 2) continue
        val maxPages = idxs.maxOf { list[it].pageCount }
        val minPages = idxs.map { list[it].pageCount }.filter { it > 0 }.minOrNull() ?: continue
        if (maxPages < VOLUME_MIN_PAGES) continue
        if (maxPages < minPages * VOLUME_PAGE_RATIO) continue
        // 页数最大的一条判卷
        promote += idxs.maxBy { list[it].pageCount }
    }
    if (promote.isEmpty()) return list
    list = list.mapIndexed { i, ch ->
        when {
            i in promote -> ch.copy(kind = ChapterKind.VOLUME)
            ch.kind == ChapterKind.UNKNOWN -> ch.copy(kind = ChapterKind.CHAPTER)
            else -> ch
        }
    }
    return list
}
