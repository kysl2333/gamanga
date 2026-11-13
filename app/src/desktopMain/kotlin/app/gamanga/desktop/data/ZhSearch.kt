package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog

/**
 * 中文搜索归一化（简繁互转）—— 2026-10-26 用户要求。
 *
 * 用户原话：「搜索书架内漫画的时候，自动简体繁体相互转换，只命中一个字也能搜索到」。
 *
 * ## 做法
 *
 * 把「书名」和「查询词」都过一遍 [normalize]（繁体 → 简体），再做包含匹配。
 * 这样两个方向都通：简体输入命中繁体书名、繁体输入命中简体书名。
 *
 * ## 另外支持「拼音首字母」（2026-10-27 用户要求）
 *
 * 用户原话：「搜索栏搜索不光简繁转换，还要首拼音查找，比如 g 就能找到鬼灭之刃，
 * zbwzq 就能找到装备我最强，这些服务于查找书架漫画」。
 *
 * 规则：查询词**全是字母**时，额外把书名转成拼音首字母串再 contains
 * （「鬼灭之刃」→ `gmzr`，所以敲 `g` 或 `gmz` 都能命中）。
 * 字典是 `pypinyin` 生成的 `resources/zh/pinyin-initials.txt`（20892 字 / 82KB），
 * 多音字取最常用读音 —— 对首字母检索这是正确取舍。
 *
 * 字典是 OpenCC 的 `TSCharacters`（Apache-2.0）压成的单字表
 * （`resources/zh/ts-characters.txt`，3221 对，20KB；生成脚本 `tools/gen-zh-map.py`）。
 *
 * ## 为什么只做单字级
 *
 * 书名匹配只需要「这个字是不是同一个字」，不需要「台湾→臺灣」这类词级转换
 * （那要额外两张词表，收益极小）。单字表已经覆盖「海賊→海贼」「進擊→进击」这类实际场景。
 *
 * ## 为什么不用 `String.contains` 直接比
 *
 * 直接比只对**完全相同的字**成立。繁体书名里只要有一个字不同（`賊` vs `贼`），
 * 整条就匹配不上——这就是用户说的「搜不到」。
 *
 * ## 失败降级
 *
 * 资源读不到（IDE 直接跑、资源没进 classpath）时映射表为空，
 * [normalize] 原样返回，退化成普通 `contains`——**功能变弱但不崩**。
 */
object ZhSearch {

    /** 繁体字 → 简体字。空表 = 资源缺失，退化成普通匹配。 */
    private val tradToSimp: Map<Char, Char> by lazy { load() }

    /** 汉字 → 拼音首字母（小写）。空表 = 资源缺失，拼音匹配自动关闭。 */
    private val hanziInitial: Map<Char, Char> by lazy { loadPinyin() }

    /** 是否成功加载了字典（设置页/测试可据此判断简繁搜索是否生效）。 */
    val hasDictionary: Boolean get() = tradToSimp.isNotEmpty()

    /**
     * 繁体 → 简体。
     *
     * 只替换字典里有的字，其余（简体、标点、字母、数字）原样保留，
     * 所以对已经是简体的文本是**幂等**的。
     */
    fun normalize(text: String): String {
        if (tradToSimp.isEmpty()) return text
        var changed = false
        val out = CharArray(text.length) { i ->
            val c = text[i]
            val mapped = tradToSimp[c]
            if (mapped != null) {
                changed = true
                mapped
            } else {
                c
            }
        }
        return if (changed) String(out) else text
    }

    /**
     * 书架搜索匹配。
     *
     * 规则（按用户口径「只命中一个字也能搜到」）：
     * 1. 两边都归一化成简体；
     * 2. 忽略大小写；
     * 3. 查询词按空格拆成多个词时，**每个词都要命中**（`AND`）——
     *    这样「海贼 王」能搜到「海贼王」，但「海贼 火影」不会误命中。
     * 4. 单个字也算命中（`contains` 天然支持）。
     */
    fun matches(title: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val haystack = normalize(title).lowercase()

        // ① 简繁归一化后的普通包含匹配（原有逻辑）
        val words = q.split(' ', '\u3000').filter { it.isNotBlank() }
        if (words.all { haystack.contains(normalize(it).lowercase()) }) return true

        // ② 拼音首字母匹配：查询词全是字母时才试（避免「第1话」这种混排走错分支）
        if (hanziInitial.isNotEmpty() && q.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            // 用**原始标题**算首字母，不用 normalize 后的：拼音与简繁无关，
            // 而且归一化过程本身可能有损（2026-10-27 的字典错位事故就是教训）
            val initials = initialsOf(title.lowercase())
            if (initials.contains(q.lowercase())) return true
        }
        return false
    }

    /**
     * 把一串文字转成「拼音首字母串」：汉字取首字母，字母数字原样保留，其余（标点/空格）跳过。
     *
     * 「鬼灭之刃 第1卷」→ `gmzr`（数字也保留的话是 `gmzr1`，这里数字保留更利于「第1卷」检索）。
     */
    fun initialsOf(text: String): String {
        if (hanziInitial.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> out.append(c.lowercaseChar())
                else -> hanziInitial[c]?.let { out.append(it) }
            }
        }
        return out.toString()
    }

    private fun loadPinyin(): Map<Char, Char> = runCatching {
        val stream = ZhSearchAnchor::class.java.classLoader
            ?.getResourceAsStream("zh/pinyin-initials.txt")
            ?: return@runCatching emptyMap()
        val body = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        buildMap {
            var i = 0
            while (i + 1 < body.length) {
                val ch = body[i]
                val letter = body[i + 1]
                if (ch != '\n' && letter != '\n') put(ch, letter)
                i += 2
            }
        }
    }.onFailure {
        AppLog.w("UI", "拼音首字母表加载失败，拼音搜索关闭：${it.message}")
    }.getOrDefault(emptyMap())

    private fun load(): Map<Char, Char> = runCatching {
        val stream = ZhSearchAnchor::class.java.classLoader
            ?.getResourceAsStream("zh/ts-characters.txt")
            ?: return@runCatching emptyMap()
        val body = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        // **按行解析，每行 `<繁体><简体>`**（2026-10-27 修）。
        // 旧写法是「每 2 个 char 一对」的等距解析 —— 字典里只要混进一个非 BMP 字符
        // （扩展 B 区生僻字，Python 算 1 字符、Kotlin 的 String 占 2 个 char），
        // 后面全部错位，实测把「装」映射成了「裡」，简体搜索直接失效。
        // 现在按行切、只认长度正好 2 的行，非 BMP 行自然被跳过。
        buildMap {
            body.lineSequence().forEach { line ->
                if (line.length == 2 && line[0] != line[1]) put(line[0], line[1])
            }
        }
    }.onFailure {
        AppLog.w("UI", "简繁字典加载失败，书架搜索退化为普通匹配：${it.message}")
    }.getOrDefault(emptyMap())

    /** 只用来取 classloader 的锚点，不实例化。 */
    private object ZhSearchAnchor
}
