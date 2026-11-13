package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.ReadingProgress
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 一个可迁移的候选（另一个源上的同一本书）。 */
data class MigrateCandidate(
    val source: SourceType,
    val manga: SourceManga,
    /** 拉失败时为空列表（UI 标「章节未知」）。 */
    val chapters: List<SourceChapter>,
    /** 3 完全相同 / 2 包含 / 1 仅作者。 */
    val score: Int,
    /** 「标题完全相同」「标题包含」「仅作者匹配」。 */
    val matchLabel: String,
)

/** 迁移计划（预览用，纯数据，不写库）。 */
data class MigrationPlan(
    val from: MangaEntry,
    val fromChapterCount: Int,
    val to: MigrateCandidate,
    /** 目标源里的章节下标（0-based），-1 = 没进度。 */
    val alignedChapterIndex: Int,
    val alignedPage: Int,
    /** 「章节数一致，按序号对齐」/「章节数不一致，按比例对齐」。 */
    val note: String,
)

/**
 * 手动换源（roadmap 第 5 节，F-46）。
 *
 * ## 为什么是「手动确认」而不是自动
 *
 * 用户 2026-10-25 明确：检测到源失效**只提示，不自动改数据**。
 * 换源要删旧 manga 行（连带章节/进度），误判一次就是不可逆的数据损失，
 * 所以必须由用户看过候选与迁移预览后点「确认迁移」。
 *
 * ## 为什么候选只搜「其他源」
 *
 * 当前源已经失效（404 / 空列表），再搜它只会把用户带回坏源。
 * 同时按 [SourceType] 去重，避免同一本书在两个源上被误当成两条候选。
 *
 * ## 匹配度的三级口径（用户 2026-10-25 第 5 节原文）
 *
 * `标题完全相同 > 标题包含 > 仅作者匹配`。比较前两边都过 [cleanTitle] + [ZhSearch.normalize]：
 * 源站标题普遍带 `[连载中]`、`（全 120 话）`、`漫画` 这类噪声，直接比会全部判成「不匹配」。
 * 作者只作为**最后一级**兜底——同名不同作者的书很多，作者相同但标题完全不同的也不少，
 * 所以仅作者匹配（score=1）在 UI 上要显式标出来，让用户自己判断。
 */
object SourceMigration {

    private const val TAG = "SourceMigration"

    /** 每个源只看搜索结果前 5 条：再往后基本是不同作品，拉章节会白烧配额。 */
    private const val TAKE_PER_SOURCE = 5

    /**
     * 候选排序：匹配度高的在前，同分按章节数多的在前（章节多通常更完整）。
     *
     * 章节数未知（拉取失败）按 0 排——它至少还能被用户选中，只是排在已知话数的后面。
     */
    private val candidateOrder: Comparator<MigrateCandidate> =
        compareByDescending<MigrateCandidate> { it.score }.thenByDescending { it.chapters.size }

    /**
     * 并发搜其他源，等全部回来后排序返回。
     *
     * **每个源独立协程**（照 `SearchAll` 的做法）：一个源超时/报错不影响其它源，
     * 也不会因为拷贝漫画要 6~8 秒就把整体拖到「看起来卡死」。
     * 需要「谁先回来先出谁」的 UI 用 [findCandidatesFlow]。
     */
    suspend fun findCandidates(repository: LibraryRepository, entry: MangaEntry): List<MigrateCandidate> {
        val all = findCandidatesFlow(repository, entry).toList().flatten()
        return all.distinctBy { it.source to it.manga.sourceId }.sortedWith(candidateOrder)
    }

    /**
     * 增量版：**每个源回来就发一批**，不等齐。
     *
     * 为什么要有两个入口：`findCandidates` 返回 List，必须等全部源结束才能排序；
     * 弹窗里用户在等的时候，先到的源应该立刻出现在列表里（拷贝漫画常常最后到）。
     * 每批发出的列表内部已按 [candidateOrder] 排好，UI 只需合并后再排一次。
     *
     * [repository] 目前只用于签名一致（搜索不需要读库），保留是为了调用方不用记两套参数。
     */
    @Suppress("UNUSED_PARAMETER")
    fun findCandidatesFlow(repository: LibraryRepository, entry: MangaEntry): Flow<List<MigrateCandidate>> =
        channelFlow {
            val keyword = cleanTitle(entry.title)
            if (keyword.isBlank()) return@channelFlow
            Sources.all()
                .filter { it.type != entry.source }
                .forEach { adapter ->
                    launch(Dispatchers.IO) {
                        val batch = try {
                            candidatesFrom(adapter, entry, keyword)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 单源失败 = 这个源没有候选，不影响其它源（也不该让弹窗报错）
                            AppLog.w(TAG, "换源搜索失败 ${adapter.type}：${e.message}")
                            emptyList()
                        }
                        if (batch.isNotEmpty()) send(batch.sortedWith(candidateOrder))
                    }
                }
        }

    /**
     * 算出「迁到 [candidate] 之后进度落在哪一话哪一页」。
     *
     * 对齐规则（roadmap 第 5 节）：
     * 1. 两边话数相同 → 按序号一一对应（最可靠，不引入比例误差）；
     * 2. 话数不同 → 按 `oldPos / (oldCount - 1)` 的比例映射到目标区间；
     * 3. 目标源章节拉取失败 / 旧书没有进度 → `-1`（UI 显示「从头开始」）。
     *
     * 页码不按比例缩放：用户读到「第 5 页」，换源后仍是「第 5 页」更符合直觉；
     * 目标章节页数更少时才夹到最后一页。
     *
     * 同步读库（两次查询），调用方负责放到 IO 线程。
     */
    fun plan(repository: LibraryRepository, entry: MangaEntry, candidate: MigrateCandidate): MigrationPlan {
        val oldChapters = repository.chaptersOf(entry.id).sortedBy { it.index }
        val progress = repository.progressOf(entry.id)
        val fromCount = oldChapters.size
        val toCount = candidate.chapters.size

        if (progress == null) {
            return MigrationPlan(entry, fromCount, candidate, -1, 0, "旧书没有阅读进度，迁移后从第一话开始")
        }
        if (toCount == 0) {
            return MigrationPlan(entry, fromCount, candidate, -1, 0, "新源章节列表为空，迁移后从第一话开始")
        }

        val oldPos = oldChapters.indexOfFirst { it.id == progress.chapterId }
        if (oldPos < 0) {
            // 进度指向的章节行已被删（历史遗留脏数据）：退到第一话，别按 -1 去算比例
            return MigrationPlan(entry, fromCount, candidate, 0, 0, "上次读的章节已不在列表，迁移后从第一话开始")
        }

        val targetIndex = alignIndex(oldPos, fromCount, toCount)
        val targetChapter = candidate.chapters[targetIndex]
        val page = if (targetChapter.pageCount > 0) {
            progress.page.coerceIn(0, targetChapter.pageCount - 1)
        } else {
            progress.page.coerceAtLeast(0)
        }
        val note = if (fromCount == toCount) "章节数一致，按序号对齐" else "章节数不一致，进度按比例对齐"
        return MigrationPlan(entry, fromCount, candidate, targetIndex, page, note)
    }

    /**
     * 执行迁移，返回新的本地 manga id。失败抛异常（调用方展示）。
     *
     * ## 顺序为什么必须显式
     *
     * 表上写了 `ON DELETE CASCADE`，但 `PRAGMA foreign_keys` 是**连接级开关，实测没生效**
     * （`removeFromLibrary` 的注释记着这条坑）。所以这里不依赖级联：
     * `removeFromLibrary` 自己按 `progress → chapter → manga` 的顺序硬删。
     *
     * 步骤：
     * 1. 读旧状态快照（已读 / 点过 / 进度 / 置顶）；
     * 2. 删旧书（硬删，含章节与进度）；
     * 3. 从新源导入（`importFromSource` 一次事务写 manga + chapter）；
     * 4. 按对齐关系把 `isRead` / `lastOpenedAt` 写回新章节；
     * 5. 写进度；
     * 6. 恢复置顶。
     *
     * ## 失败会怎样（必须知道）
     *
     * 删除、导入、状态与进度写回在Repository同一事务中完成。
     * 任一步失败回滚旧书与相关记录，并恢复内存置顶状态。
     *
     * ## 不搬的东西
     *
     * - **下载缓存**：`downloadChapter` 行不在 `removeFromLibrary` 里删（同样没有真实级联），
     *   但 `DownloadManager` 监听 `libraryItems` 会自动收尾——旧 mangaId 不在书架后，
     *   `cleanupOrphans` 删掉 DB 行与磁盘目录。迁移期间下载任务会被一并取消。
     * - **自定义封面**：按 mangaId 存（`coverCache` 目录），迁移后 id 变了就失效——
     *   新书用新源的封面；以后要做「跨源保留自定义封面」再单独处理。
     * - **加入书架时间**：新行取当前时间，书架顺序会按新加入的排（用户能感知，但这是
     *   `importFromSource` 的默认行为，硬改要动 Repository）。
     */
    fun execute(repository: LibraryRepository, plan: MigrationPlan): String {
        val candidate = plan.to
        // 第 0 步：先挡住「新源没有章节」——此时旧数据还完好，可以安全放弃
        check(candidate.chapters.isNotEmpty()) { "新源没有返回章节列表，已取消迁移（原书未改动）" }

        return repository.migrateAtomically { executeValidated(repository, plan) }.also { newId ->
            AppLog.i(TAG, "换源完成：${plan.from.id} → $newId（${candidate.source}）")
        }
    }

    private fun executeValidated(repository: LibraryRepository, plan: MigrationPlan): String {
        val candidate = plan.to
        val oldId = plan.from.id

        // 第 1 步：快照。必须在删之前全部读完，删完就查不到了。
        val oldChapters = repository.chaptersOf(oldId).sortedBy { it.index }
        val oldProgress = repository.progressOf(oldId)
        val pinnedBefore = repository.getPref(LibraryRepository.PREF_PINNED)
            ?.split(',')
            ?.map { it.trim() }
            ?.contains(oldId) == true

        // 第 2 步：硬删旧书（progress → chapter → manga，Repository 内部按序）
        repository.removeFromLibrary(oldId)

        // 第 3 步：导入新源（新 source + sourceMangaId → 新本地 id）
        val newId = repository.importFromSource(candidate.source, candidate.manga, candidate.chapters)

        // 第 4 步：已读 / 点过按对齐关系搬过去。
        // 只在旧状态里为 true / >0 时写——批量写 200 话会产生 200 次状态推送，
        // 没读过的章节本来默认就是 0，不需要写。
        val toCount = candidate.chapters.size
        oldChapters.forEachIndexed { oldPos, old ->
            if (!old.read && old.lastOpenedAt <= 0) return@forEachIndexed
            val target = candidate.chapters[alignIndex(oldPos, oldChapters.size, toCount)]
            val newChapterId = "$newId#${target.id}"
            if (old.read) repository.setChapterRead(newChapterId, true)
            if (old.lastOpenedAt > 0) repository.setChapterOpened(newChapterId)
        }

        // 第 5 步：进度。页码取预览算好的值；总页数优先用新章节页数（未知时沿用旧值）
        if (plan.alignedChapterIndex in candidate.chapters.indices) {
            val target = candidate.chapters[plan.alignedChapterIndex]
            val totalPages = when {
                target.pageCount > 0 -> target.pageCount
                oldProgress != null && oldProgress.totalPages > 0 -> oldProgress.totalPages
                else -> plan.alignedPage + 1
            }
            repository.saveProgress(
                ReadingProgress(
                    mangaId = newId,
                    chapterId = "$newId#${target.id}",
                    page = plan.alignedPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)),
                    totalPages = totalPages,
                )
            )
        }

        // 第 6 步：置顶。removeFromLibrary 已经把旧 id 从 prefs 里清掉了，这里重新加回。
        if (pinnedBefore) repository.togglePin(newId)

        return newId
    }

    // ---- 内部实现 ----

    /** 单个源的候选：搜索 → 打分 → 对 score≥2 的补章节。 */
    private suspend fun candidatesFrom(
        adapter: SourceAdapter,
        entry: MangaEntry,
        keyword: String,
    ): List<MigrateCandidate> = withContext(Dispatchers.IO) {
        val found = adapter.search(keyword, page = 0, pageSize = 20).take(TAKE_PER_SOURCE)
        val scored = found.mapNotNull { manga ->
            val score = scoreOf(entry.title, entry.author, manga)
            if (score <= 0) null else MigrateCandidate(adapter.type, manga, emptyList(), score, labelOf(score))
        }
        // 仅作者匹配（1）不拉章节：多半是不同作品，拉章节只是白烧配额（Komiic 取图有配额）
        coroutineScope {
            scored.map { cand ->
                async {
                    if (cand.score < 2) return@async cand
                    val chapters = try {
                        adapter.chapters(cand.manga.sourceId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.w(TAG, "拉章节失败 ${adapter.type}/${cand.manga.sourceId}：${e.message}")
                        emptyList()
                    }
                    cand.copy(chapters = chapters.sortedBy { it.index })
                }
            }.awaitAll()
        }
    }

    /**
     * 匹配度打分：3 标题完全相同 / 2 标题包含 / 1 仅作者 / 0 不相关。
     *
     * 标题比较前先清洗再归一化（简繁 + 去标点空白），否则源站标题里的
     * `[连载中]`、`漫画` 会让「完全相同」永远判不出来。
     */
    private fun scoreOf(title: String, author: String?, manga: SourceManga): Int {
        val query = norm(cleanTitle(title))
        val target = norm(cleanTitle(manga.title))
        if (query.isNotEmpty() && query == target) return 3
        // 至少两个字才做包含：单字「火」会把「火焰纹章」之类全部捞进来
        if (query.length >= 2 && target.isNotEmpty() && (target.contains(query) || query.contains(target))) {
            return 2
        }
        val a = norm(author.orEmpty())
        val b = norm(manga.author.orEmpty())
        if (a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))) return 1
        return 0
    }

    private fun labelOf(score: Int): String = when (score) {
        3 -> "标题完全相同"
        2 -> "标题包含"
        else -> "仅作者匹配"
    }

    /**
     * 旧章节位置 → 新章节位置。
     *
     * 话数相同直接同下标（避免 `3/11` 这类比例算出 ±1 的漂移）；
     * 话数不同按两端对齐的比例映射（第 1 话→第 1 话、最后一话→最后一话）。
     * `oldCount <= 1` 时只能落到第一话。
     */
    private fun alignIndex(oldPos: Int, oldCount: Int, newCount: Int): Int {
        if (newCount <= 0) return -1
        if (oldCount <= 1 || oldCount == newCount) return oldPos.coerceIn(0, newCount - 1)
        val ratio = oldPos.toDouble() / (oldCount - 1)
        return (ratio * (newCount - 1)).roundToInt().coerceIn(0, newCount - 1)
    }

    /** 归一化：简繁统一 → 只留字母数字（去空格、标点、装饰符号）→ 小写。 */
    private fun norm(text: String): String =
        ZhSearch.normalize(text).filter { it.isLetterOrDigit() }.lowercase()

    /** 括号类噪声（`[连载中]`、`（全 120 话）`、`(中文版)`）。 */
    private val bracketNoise = listOf(
        Regex("\\[[^\\]]*]"),
        Regex("【[^】]*】"),
        Regex("（[^）]*）"),
        Regex("\\([^)]*\\)"),
    )

    /** 结尾噪声词：只在**末尾**且不是整个标题时才去掉（「漫画」开头的作品名不能被砍）。 */
    private val tailNoise = listOf("连载中", "已完结", "全集", "漫画", "下拉式", "在线看")

    /** 清洗标题两侧的装饰符号。 */
    private val edgeJunk = charArrayOf(' ', '\t', '·', '-', '—', '：', ':', '_', '/', '|', '【', '】')

    /**
     * 清洗标题（只留作品名）：去括号段 + 去结尾状态词 + 收拢空白。
     *
     * 为什么要清洗：换源搜索拿原样标题去搜，源站会按「火影忍者 漫画 连载中」这种串
     * 去做全文匹配，命中率明显低于只搜「火影忍者」。推荐页跳搜索用的是同一套思路。
     */
    fun cleanTitle(raw: String): String {
        var s = raw
        bracketNoise.forEach { s = s.replace(it, " ") }
        // 反复剥：`火影忍者 漫画 全集` 要连剥两次
        var stripping = true
        while (stripping) {
            stripping = false
            val t = s.trim().trim(*edgeJunk)
            for (word in tailNoise) {
                if (t.length > word.length && t.endsWith(word)) {
                    s = t.dropLast(word.length)
                    stripping = true
                    break
                }
            }
            if (!stripping) s = t
        }
        return s.trim().trim(*edgeJunk).replace(Regex("\\s+"), " ")
    }
}
