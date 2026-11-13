package app.gamanga.desktop.ui

import app.gamanga.desktop.source.SourceChapter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 章节列表的一行：要么是一章，要么是「编号跳空」提示行（R-55）。
 *
 * 用户实测：G社「装备我最强」章节列表 286 直接跳 290，缺的 287-289 在列表里
 * 毫无提示。源站缺话时（下架/审核）就在这里插一行说明缺了几话。
 */
sealed interface ChapterRow {

    /** LazyColumn 的稳定 key：章节用章节 id，提示行用前后两章的 id 拼（同一空档只此一个）。 */
    val key: String

    data class Item(val chapter: SourceChapter) : ChapterRow {
        override val key: String get() = chapter.id
    }

    /**
     * 编号跳空提示行。[prevId] / [nextId] 是**列表顺序**上的前后章（正倒序都可能是大的在前），
     * 仅用于拼 key；[text] 恒按从小到大描述区间。
     */
    data class Gap(
        val prevId: String,
        val nextId: String,
        val text: String,
    ) : ChapterRow {
        override val key: String get() = "gap:$prevId>$nextId"
    }
}

/** 编号差判定阈值：差 >1 才算跳空（差 1 = 连续）。留 ε 防二进制小数误差。 */
private const val GAP_EPS = 1e-9

/**
 * 从章节名提取「话数」；取不到返回 null。
 *
 * 两源的真实命名（实测）：
 *  - Komiic：name 就是 serial 本体——"000" / "012" / "2.2"（小数话）；
 *  - G社：编号开头——"286 与史莱姆..." / "000 我是谁？-装备我最强"。
 * 另兼容 "第286话" 形态（编号不在开头）。
 *
 * "第1卷" / "贺图" / "番外篇" / 空串一律 null——卷和番外不是"缺的话"，
 * 硬凑进编号链只会误报。
 */
internal fun chapterNumberOf(name: String): Double? {
    val s = name.trim()
    Regex("""^(\d+(?:\.\d+)?)""").find(s)?.let { m ->
        m.groupValues[1].toDoubleOrNull()?.let { return it }
    }
    return Regex("""^第\s*(\d+(?:\.\d+)?)\s*[话話]""").find(s)
        ?.groupValues?.get(1)?.toDoubleOrNull()
}

/**
 * 给章节列表插入缺失提示行。传入**显示顺序**的列表即可——正序倒序都正确
 * （倒序时同一对空档章节是「大的在前」，用绝对值判定，文案恒按升序描述）。
 *
 * 判定规则：
 *  1. 只比较**相邻的两个能提取到编号的章节**；中间夹的非编号章节（卷/贺图/番外）
 *     跳过——它们不占编号，不该打断编号链（[286, 贺图, 290] 依然提示缺 287-289）。
 *  2. 编号差 >1 即有缺失。缺失的整数区间 = [floor(较小)+1, ceil(较大)-1]：
 *     整数场景精确（286→290 得 287-289，共 3 话）；小数话场景只报区间内的整数
 *     （2.2→4 得 3）。
 *  3. 区间为空（纯小数跳变、中间没有整数）不提示——说不出缺了哪几话就不编。
 */
internal fun chapterRows(chapters: List<SourceChapter>): List<ChapterRow> {
    val rows = ArrayList<ChapterRow>(chapters.size)
    var prevNumbered: SourceChapter? = null
    var prevNumber = 0.0
    for (ch in chapters) {
        val number = chapterNumberOf(ch.name)
        if (number == null) {
            rows += ChapterRow.Item(ch) // 非编号章节照常显示，只是不参与判定
            continue
        }
        val prev = prevNumbered
        if (prev != null && abs(number - prevNumber) > 1.0 + GAP_EPS) {
            val lo = minOf(prevNumber, number)
            val hi = maxOf(prevNumber, number)
            val from = floor(lo).toInt() + 1
            val to = ceil(hi).toInt() - 1
            if (from <= to) {
                rows += ChapterRow.Gap(
                    prevId = prev.id,
                    nextId = ch.id,
                    text = if (from == to) "此处缺失 $from（共 1 话）"
                    else "此处缺失 $from-$to（共 ${to - from + 1} 话）",
                )
            }
        }
        rows += ChapterRow.Item(ch)
        prevNumbered = ch
        prevNumber = number
    }
    return rows
}

/**
 * 章节（显示序下标）→ 行下标。R-55 插了提示行后两者不再相等：
 * 提示行前的章节行下标不变，之后的整体后移。
 * [chapterDisplayIndex] < 0（无进度）或越界返回 -1，调用方跳过定位。
 */
internal fun rowIndexOfChapter(rows: List<ChapterRow>, chapterDisplayIndex: Int): Int {
    if (chapterDisplayIndex < 0) return -1
    var seen = -1
    rows.forEachIndexed { i, row ->
        if (row is ChapterRow.Item && ++seen == chapterDisplayIndex) return i
    }
    return -1
}
