package app.gamanga.desktop.data.backup

import app.gamanga.desktop.model.SourceType

/**
 * mihon 备份（`.tachibk` = gzip 压缩的 protobuf）里我们关心的字段。
 *
 * 字段号来自 mihon `data/backup/models/`（2026-10 版）：
 * BackupManga{1:source, 2:url, 3:title, 5:author, 9:thumbnailUrl, 13:dateAdded, 16:chapters}、
 * BackupChapter{1:url, 2:name, 4:read, 6:lastPageRead, 9:chapterNumber}。
 * 其余字段（分类/追踪/源偏好等）对「观看历史层面上的备份」没有意义，直接跳过。
 */
data class MihonManga(
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val dateAdded: Long,
    val chapters: List<MihonChapter>,
    /**
     * 备份里自带的封面图字节（R-170，2026-10-30）。
     *
     * mihon 正典 `.tachibk` **不含**图片，但部分分支/魔改版会塞进去。用户口径：
     * 「如果备份里面有图片可以原封不动的使用这张自定义图片」→ 有就用，没有就退回 `thumbnailUrl`。
     * 判据不看字段号，只认文件头（[looksLikeImage]）。
     */
    val coverBytes: ByteArray? = null,
    /**
     * 这一部在 mihon 里**在书架**（`BackupManga.favorite = 100`，R-178）。
     *
     * 用户 2026-10-30 口径：「备份书架是指，只选择书架的书恢复……而选择历史就是把历史的书
     * 给勾选出来」——所以导入范围要按这个标志筛，不能一律全导。
     *
     * 老备份/魔改版可能没有这个字段（`bool(100)` 返回 null）→ **默认 true**：
     * 宁可多列出来让用户自己取消，也不要把整份备份筛成空列表。
     */
    val inLibrary: Boolean = true,
    /** 备份里有阅读痕迹（任一章已读或有进度）。「历史」那一档用它兜底。 */
    val hasHistory: Boolean = false,
)

data class MihonChapter(
    val url: String,
    val name: String,
    val read: Boolean,
    /** 0 起页码；mihon 不记总页数。 */
    val lastPageRead: Long,
    /** 1 起话数（第 1 话 = 1.0；第 1.5 话之类的小数也存在）。 */
    val chapterNumber: Float
)

/**
 * 解析 mihon 备份字节（**gzip 后的**原始文件内容）。
 *
 * 结构不匹配/截断抛 [IllegalArgumentException]；单部漫画解析失败只跳过那一部
 * （坏一部不影响剩下的），收集进 [MihonBackupResult.skipped]。
 */
fun parseMihonBackup(gzipped: ByteArray): MihonBackupResult {
    val raw = gunzip(gzipped)
    val top = parseProtobuf(raw)
    val mangas = ArrayList<MihonManga>()
    val skipped = ArrayList<String>()
    for (m in top.messages(1)) { // Backup.backupManga
        // 2026-10-29（F-275）：字段号用「多候选」读——用户问「很早之前的备份还能用吗」，
        // 历代 mihon / Tachiyomi 把缩略图、章节列表的字段号挪过位，硬读单一字段号
        // 会把老备份读成「有书名没章节」。只要 url + title 还在，这一部就救得回来。
        val url = m.stringAny(2, 1) ?: continue
        val title = m.stringAny(3) ?: url
        val chapters = m.messagesAny(16, 12).mapNotNull { c ->
            val cUrl = c.stringAny(1, 2) ?: return@mapNotNull null
            MihonChapter(
                url = cUrl,
                name = c.stringAny(2, 3) ?: cUrl,
                read = c.bool(4) ?: false,
                lastPageRead = c.longAny(6, 5) ?: 0L,
                chapterNumber = c.float(9) ?: 0f
            )
        }
        mangas += MihonManga(
            url = url,
            title = title,
            author = m.stringAny(5, 4),
            thumbnailUrl = m.stringAny(9, 13, 14),
            dateAdded = m.longAny(13, 11, 16) ?: 0L,
            chapters = chapters,
            // 备份里若带封面图（非正典格式），原样留下；导入时直接当自定义封面写盘
            coverBytes = m.byteBlobs().firstOrNull(::looksLikeImage),
            // 书架标志：mihon 的 `favorite`。字段号 100 是正典格式；老备份没有就按「在书架」处理
            inLibrary = m.bool(100) ?: true,
            hasHistory = chapters.any { it.read || it.lastPageRead > 0L },
        )
    }
    return MihonBackupResult(mangas, skipped)
}

data class MihonBackupResult(
    val manga: List<MihonManga>,
    /** 解析失败被跳过的条目（url 或标题，供 UI 告知用户）。 */
    val skipped: List<String>
)

private fun gunzip(bytes: ByteArray): ByteArray {
    val gz = java.util.zip.GZIPInputStream(bytes.inputStream())
    return gz.use { it.readBytes() }
}

/**
 * mihon 漫画 URL → 我们的 (源类型, 源站 id)。
 *
 * mihon 的 `source` 是扩展 id（长整型），跟我们的两个源对不上；但 URL 是公共的：
 * - Komiic：`https://komiic.cc/comic/<id>`（我们的 sourceMangaId 就是这个 id；
 *   旧备份里的 `komiic.com` 域名同样认）
 * - G社系：`https://m.g-mh.org` / `godamh.com` / `baozimh.com` 等域名下的
 *   `/manga/<slug>`（我们的 sourceMangaId 是 slug）
 *
 * 认不出来返回 null（导入时告知用户跳过了哪些）。
 */
fun mihonUrlToSource(url: String): Pair<SourceType, String>? {
    val u = url.trim().removePrefix("http://").removePrefix("https://").lowercase()
    val host = u.substringBefore('/')
    val path = u.substringAfter('/', "")
    return when {
        // komiic.cc / www.komiic.cc（旧备份里的 komiic.com 也认，域名已停用）
        host == "komiic.cc" || host.endsWith(".komiic.cc") ||
            host == "komiic.com" || host.endsWith(".komiic.com") -> {
            val id = path.removePrefix("comic/").substringBefore('/').substringBefore('?')
            if (id.isBlank() || id == "comic") null else SourceType.KOMIIC to id
        }
        // G社一家的换皮域名（同一套 CMS）
        host == "m.g-mh.org" || host.endsWith(".g-mh.org") ||
            host == "godamh.com" || host.endsWith(".godamh.com") ||
            host == "baozimh.com" || host.endsWith(".baozimh.com") ||
            host == "baozimh.org" || host.endsWith(".baozimh.org") -> {
            val rest = path.removePrefix("manga/")
            val slug = rest.substringBefore('/').substringBefore('?')
            if (slug.isBlank() || slug == "manga") null else SourceType.GODA to slug
        }
        else -> null
    }
}

/**
 * mihon 章节 ↔ 本地章节列表的匹配（F-03 导入观看历史用；F-275 改为**按名字优先**）。
 *
 * 用户 2026-10-29 口径：「需要换源和对齐章数的**名字**而不是第几章」。
 * 原因是源站之间话数经常对不上：mihon 里记的是「第 52 话」，换到的源把它拆成
 * 「第 52 话 上 / 下」两章，或者干脆从 1 重新编号 —— 按序号对齐必然错位，
 * 而「第 52 话」这个名字本身在大多数源上是稳定的。
 *
 * 顺序：① 章节名归一化后相等 ② 名字里的数字相等（`第52话` = `52`） ③ 退回按话数取第 n 章（老行为，兜底）。
 * 返回下标，-1 = 没匹配上。
 */
fun matchMihonChapterIndex(
    chapterNumber: Float,
    name: String,
    chapterNames: List<String>
): Int {
    if (chapterNames.isEmpty()) return -1
    matchChapterByName(name, chapterNames).let { if (it >= 0) return it }
    val n = chapterNumber.toInt()
    if (n in 1..chapterNames.size) return n - 1
    return -1
}

/**
 * 按章节名匹配（纯函数，`MihonImportPlanTest` 覆盖）。
 *
 * ① 归一化后整串相等（最可靠）
 * ② 归一化后的「数字键」相等（`第52话` / `52话` / `52` / `第52话 完` 都算同一话）
 * ③ 名字里含同一数字且**唯一**时也算（`第52话 上` 对 `52话`）
 */
fun matchChapterByName(name: String, chapterNames: List<String>): Int {
    val target = normalizeChapterName(name)
    if (target.isNotBlank()) {
        chapterNames.indexOfFirst { normalizeChapterName(it) == target }.let { if (it >= 0) return it }
    }
    val key = chapterKey(name)
    if (key.isNotEmpty()) {
        val hits = chapterNames.indices.filter { chapterKey(chapterNames[it]) == key }
        if (hits.size == 1) return hits.first()
        if (hits.size > 1) return hits.first() // 重复话名（分上下册时常见）：取第一个
    }
    return -1
}

/**
 * 章节名的归一化：全角→半角、小写、去空白、去括号注释、剥掉「第 / 话 / 章 / 集 / 回 / 卷 /
 * chapter / ch / ep」这些**壳字符**，只留内容本身。
 *
 * `第52话 完结` → `52完结`；`Chapter 52` → `52`；`（修图）第52话` → `52`。
 */
fun normalizeChapterName(raw: String): String {
    val half = buildString(raw.length) {
        for (c in raw) {
            append(
                when {
                    c.code in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                    c == '\u3000' -> ' '
                    else -> c
                }
            )
        }
    }
    var s = half.lowercase()
    // 圆括号 / 方括号里的补充说明（已转半角）：作者碎碎念、页数、"完"这类注释 —— 连内容一起去掉。
    s = s.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
    // 中文装饰括号**只去括号本身、保留里面的字**：`【序章】` → `序章`、`『无限扭蛋』` → `无限扭蛋`
    // （上一版连内容一起去掉，结果 `【序章】` 变成空串，章节名直接对不上）
    s = s.replace(Regex("""[【】『』《》「」〔〕〈〉]"""), "")
    // 序号外壳：「第 N 话」的「第」、英文的 chapter/ch/ep
    s = s.replace(Regex("""第"""), " ")
    s = s.replace(Regex("""(chapter|chap|ch|episode|ep)\.?\s*"""), " ")
    // 量词只剥「紧跟数字」的那一个：`52话` → `52`，但 `序章` / `番外` 原样保留
    s = s.replace(Regex("""(?<=\d)\s*[话話章集回卷]"""), "")
    return s.replace(Regex("""\s+"""), "")
}

/**
 * 章节的「数字键」：`第52话` → `n:52`、`1.5话` → `n:1.5`、`序章` → `s:序章`。
 *
 * 小数保留（`第 1.5 话` 这种补章在源站很常见），去掉末尾多余的 0（`52.0` = `52`）。
 */
fun chapterKey(raw: String): String {
    val s = normalizeChapterName(raw)
    val num = Regex("""\d+(?:\.\d+)?""").find(s)?.value
    if (num != null) {
        val trimmed = num.trimEnd('0').trimEnd('.')
        return "n:" + trimmed.ifEmpty { "0" }
    }
    return if (s.isBlank()) "" else "s:$s"
}

/**
 * 默认「别称」：拿标题去掉装饰符后的样子当搜索词（用户可在导入向导里改）。
 *
 * 源站搜索按子串匹配，`『』【】！` 这类符号会让整串命中率掉到 0（F-272 同一个根因），
 * 所以默认就给一个干净的词，用户不用自己删。
 */
fun defaultSearchAlias(title: String): String {
    val cleaned = app.gamanga.desktop.data.SearchKeywords.cleaned(title).trim()
    return cleaned.ifBlank { title.trim() }
}
