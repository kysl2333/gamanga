package app.gamanga.desktop.data

/**
 * 搜索词的「退让字典」（2026-10-29 用户报「长标题搜不到，只搜 9999 才搜到」）。
 *
 * ## 问题
 *
 * 轻小说式的超长标题常带装饰括号与感叹号，例如
 * `被信赖的伙伴在地下城深处追杀依靠恩惠『无限扭蛋』召唤等级9999的同伴们…『活该！』`。
 * 源站自己的搜索是按**整串子串**匹配的，把 `『』！` 这类装饰符也当普通字符算进去，
 * 于是「标题里其实有这一段」也命中不了；短词（`9999`）反而能搜到。
 *
 * ## 做法
 *
 * 不改用户输入、也不改简繁字典（`ZhSearch` 只管本地书架匹配，与源站无关）——
 * 而是**原词搜不到时再退一步**：先去掉装饰符与标点，再退到最长的连续片段。
 * 只在「看起来会被装饰符坑到」时才退（见 [needsFallback]），
 * 否则每个空结果都要多跑两三次搜索，拷贝漫画一次 6 秒，用户会以为卡死。
 *
 * 纯函数、无网络，`SearchKeywordsTest` 覆盖。
 */
object SearchKeywords {

    /** 装饰性成对符号：标题里常见，搜索时基本是噪音。 */
    private val DECORATIVE = setOf(
        '『', '』', '【', '】', '「', '」', '《', '》', '〈', '〉',
        '“', '”', '‘', '’', '（', '）', '(', ')', '［', '］', '[', ']', '〔', '〕',
    )

    /** 句读标点：同上，但用来切片段。 */
    private val PUNCT = setOf(
        '！', '!', '？', '?', '。', '，', ',', '、', '；', ';', '：', ':',
        '·', '…', '—', '－', '-', '～', '~', '　', '\n', '\t',
    )

    /** 超过这个长度就认为「站点多半是按整串匹配、命中率会掉」。 */
    private const val LONG_KEYWORD = 20

    /**
     * 这个词要不要准备退让方案。
     *
     * 两种情况：① 含装饰符/标点（会被站点当普通字符）；② 特别长（整串子串匹配几乎必然落空）。
     * 普通短词（「海贼王」「测试」）返回 false —— 它们空就是真的没有。
     */
    fun needsFallback(keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return false
        if (k.length > LONG_KEYWORD) return true
        return k.any { it in DECORATIVE || it in PUNCT }
    }

    /** 去掉装饰符与标点，空白压成一个空格。 */
    fun cleaned(keyword: String): String {
        val out = StringBuilder(keyword.length)
        var space = false
        for (c in keyword) {
            when {
                c in DECORATIVE || c in PUNCT -> {
                    if (!space && out.isNotEmpty()) { out.append(' '); space = true }
                }
                c.isWhitespace() -> {
                    if (!space && out.isNotEmpty()) { out.append(' '); space = true }
                }
                else -> {
                    out.append(c)
                    space = false
                }
            }
        }
        return out.toString().trim()
    }

    /**
     * 按装饰符/标点/空白切出的连续片段，按长度降序。
     *
     * 只保留 ≥2 字的片段：单字片段（如「的」）搜出来全是噪音。
     */
    fun chunks(keyword: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= 2) parts += cur.toString()
            cur.clear()
        }
        for (c in keyword) {
            if (c in DECORATIVE || c in PUNCT || c.isWhitespace()) flush() else cur.append(c)
        }
        flush()
        return parts.sortedByDescending { it.length }
    }

    /**
     * 退让候选（最多两条，去重、且不等于原词）：
     * ① 去掉装饰符与标点后的整串；② 最长的连续片段。
     */
    fun fallbacks(keyword: String): List<String> {
        val raw = keyword.trim()
        if (raw.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        cleaned(raw).takeIf { it.isNotEmpty() && it != raw }?.let { out += it }
        chunks(raw).firstOrNull()?.takeIf { it != raw && it !in out }?.let { out += it }
        return out.take(2).toList()
    }
}
