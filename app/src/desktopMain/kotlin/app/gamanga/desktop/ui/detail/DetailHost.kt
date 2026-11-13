package app.gamanga.desktop.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.ChapterRefresh
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.DownloadStatus
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.ui.ReaderTarget
import app.gamanga.desktop.ui.migrate.SourceMigrateDialog
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情二级页的**宿主**（F-143，2026-10-27 第二轮用户口径）。
 *
 * ## 为什么要有这一层
 *
 * 用户要求：「在书架页点击漫画封面时，在**该书架页内**展示二级详情页；在阅览页点击
 * 漫画封面时，在**该阅览页内**展示二级详情页」。
 *
 * 上一轮的详情页只长在浏览页里——状态（选中的书 / 章节 / 多选下载 / 换源目标）和渲染
 * 全都写在 `BrowseScreen` 内部。书架页要用它，只有两条路：复制一份（必然飘，UI-SPEC 3.6
 * 的教训），或者跳转到浏览页（正是用户这次否掉的行为）。
 *
 * 所以抽成这里：**状态机 + 渲染**都收进来，三个页面各自 `DetailHost(state = ...)`，
 * 谁都不再自己持有详情状态。`open()` 之后面板立刻上屏（见下面的时序），数据到了再补内容。
 *
 * ## 打开时序（用户硬要求：「点击封面后立即弹出详情页，并先显示加载中状态」）
 *
 * ```
 * open(manga) ──① 同步：selected = manga、loading = true ──→ 面板开始上滑（骨架态）
 *             ──② 同步：本地库已有章节就直接填上（毫秒级，离线也有内容）
 *             ──③ 后台：detail + chapters，成功才覆盖，最后 loading = false
 * ```
 *
 * ①② 都是同步的，所以「点下去」和「面板开始动」之间没有任何等待。上一轮的实现在
 * `openDetail` 里先读库再赋值，拷贝漫画那种 6~8 秒的源会让用户点完以为没反应。
 */

/** 面板宽度占可用宽度的比例（用户口径：整体界面的二分之一至三分之二）。 */
private const val PanelWidthFraction = 0.62f

/** 面板顶部留的缝：看得见一级内容，面板才像「浮起来」而不是「整页被换掉」。 */
private val PanelTopGap = 24.dp

/**
 * 详情面板的动画时长（F-144）与曲线（2026-11-08 批次65）。
 *
 * 时长沿用「进入 380 × 1.35 ≈ 513ms、退出 300 × 1.35 ≈ 405ms」的既有口径；
 * 曲线与淡入淡出改为和推荐页详情完全一致（见 [DetailEnterMs] 等共享常量），
 * 用户不再感觉源详情「比推荐页笨」。
 */

/**
 * 详情宿主状态。
 *
 * 生命周期跟页面一致（[rememberDetailHostState]），页面切走时状态自然销毁；
 * 面板里的多选下载、正倒序这些**纯局部**状态也放这里——它们必须随「换一本书」复位，
 * 复位点就在 [open]。
 */
@Stable
class DetailHostState internal constructor(
    private val repository: LibraryRepository,
    private val downloadManager: DownloadManager,
    private val scope: CoroutineScope,
) {
    /** 当前详情目标（null = 面板收起）。 */
    var selected by mutableStateOf<SourceManga?>(null)
        private set

    /** 当前目标所属源（与 [selected] 同时设置，不会出现「有书没源」的中间态）。 */
    var sourceType by mutableStateOf<SourceType?>(null)
        private set

    var chapters by mutableStateOf<List<SourceChapter>>(emptyList())
        private set

    var cachedPageCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 章节/详情还在拉取中。面板已经弹出、内容还没到 → 章节区显示骨架。 */
    var loading by mutableStateOf(false)
        private set

    var chapterNotice by mutableStateOf<String?>(null)
        private set

    var chapterDesc by mutableStateOf(false)
        private set

    var downloadMode by mutableStateOf(false)
        private set

    var downloadSelected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 面板内的内联提示（入队 / 已加入书架 / 换源完成），4 秒自动清。 */
    var hint by mutableStateOf<String?>(null)
        private set

    /** F-45 换源弹窗的目标（null = 关着）。 */
    var migrateTarget by mutableStateOf<MangaEntry?>(null)
        private set

    /** 面板是否展开（调用方用它判断要不要吃掉「点一级菜单收回」这类手势）。 */
    val isOpen: Boolean get() = selected != null

    /** 当前目标的本地 id（`<源>#<源站 id>`）；面板关着时为 null。 */
    val localId: String?
        get() = selected?.let { "${sourceType?.name?.lowercase()}:${it.sourceId}" }

    private var openJob: Job? = null
    private var hintJob: Job? = null

    /**
     * 打开一本漫画的详情。
     *
     * **同步完成**「上屏 + 本地章节填充」，网络部分丢到后台——这是「点击即弹」的关键。
     */
    fun open(manga: SourceManga, type: SourceType) {
        val adapter = Sources.of(type)
        if (adapter == null) {
            AppLog.w("Detail", "no adapter for ${type.name}, ignore open", null)
            return
        }
        openJob?.cancel()
        // 换书 = 复位所有局部状态（上一本的多选态不能带到这一本）
        downloadMode = false
        downloadSelected = emptySet()
        chapterDesc = false
        hint = null
        chapterNotice = null
        hintJob?.cancel()
        sourceType = type

        val id = "${type.name.lowercase()}:${manga.sourceId}"
        // 封面用库里那份兜底：从书架进来时 manga.coverUrl 常是 null，
        // 只有 detail 成功才拿得到 og:image，而离线 detail 必然失败 → 封面永远空白。
        val dbCover = repository.libraryItems.value
            .firstOrNull { it.entry.id == id }?.entry?.coverUrl
        val initialCover = manga.coverUrl ?: dbCover
        selected = if (initialCover != null && initialCover != manga.coverUrl) {
            manga.copy(coverUrl = initialCover)
        } else {
            manga
        }

        // ---- ① 本地优先：同步读库，先让界面有东西 ----
        val local = localChaptersOf(id)
        chapters = local
        cachedPageCounts = repository.cachedPageCounts(id)
        // 本地一话都没有 → 面板会停在骨架态；有的话骨架一闪而过（比白屏好）
        loading = true

        // 封面缓存不能等 detail（拷贝漫画离线时 detail 要 16 秒且必然失败）
        scope.launch { withContext(Dispatchers.IO) { CoverCache.resolve(initialCover) } }

        // ---- ② 后台刷新：网络成功才覆盖 ----
        openJob = scope.launch {
            try {
                refresh(manga, type, adapter, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                chapterNotice = "刷新未完成，请重试或在浏览器查看"
                AppLog.w("Detail", "refresh failed sid=${manga.sourceId}", e)
            } finally {
                // 只有「还是这本书」时才收骨架——否则会把用户刚打开的另一本一起收掉
                if (currentCoroutineContext().isActive && selected?.sourceId == manga.sourceId && sourceType == type) loading = false
            }
        }
    }

    /** 显式刷新绕过缓存，保留已有目录直到完整结果返回。 */
    fun reload() {
        if (loading) return
        val manga = selected ?: return
        val type = sourceType ?: return
        val adapter = Sources.of(type) ?: return
        val id = localId ?: return
        loading = true
        chapterNotice = null
        openJob = scope.launch {
            try {
                refresh(manga, type, adapter, id, forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chapterNotice = "刷新未完成，请重试或在浏览器查看"
                AppLog.w("Detail", "manual refresh failed sid=${manga.sourceId}", e)
            } finally {
                if (currentCoroutineContext().isActive && selected?.sourceId == manga.sourceId && sourceType == type) loading = false
            }
        }
    }

    /** 收起面板（点返回 / 点遮罩 / 点一级菜单 / 迁移完成）。 */
    fun close() {
        openJob?.cancel()
        openJob = null
        selected = null
        sourceType = null
        loading = false
        downloadMode = false
        downloadSelected = emptySet()
        chapters = emptyList()
        cachedPageCounts = emptyMap()
        migrateTarget = null
    }

    /** 点 dock 上同一个一级菜单项时由页面转发进来（等价于「点一级菜单收回二级页」）。 */
    fun closeIfOpen() {
        if (isOpen) close()
    }

    // ---- 以下都是面板内部的局部操作，页面不再关心 ----

    fun toggleOrder() {
        chapterDesc = !chapterDesc
    }

    fun enterDownloadMode() {
        val id = localId ?: return
        val manga = selected ?: return
        if (repository.libraryItems.value.none { it.entry.id == id }) {
            // 下载有 manga 外键，没入书架先自动导入（移出书架会连带清掉下载）
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.importFromSource(sourceType!!, manga, chapters)
                    }
                }
                showHint("已加入书架，进入多选下载：点章节格勾选，『取消』退出")
            }
        } else {
            showHint("已进入多选下载：点章节格勾选，『取消』退出")
        }
        downloadMode = true
        downloadSelected = emptySet()
    }

    /**
     * 从章节右键菜单进入**多选模式**（2026-10-30 用户要求 3）。
     *
     * 和「下载」按钮进的是同一套勾选界面（用户原话「复用现有下载勾选界面」），
     * 区别只在于进来之后工具条多了「标记已读 / 标记未读」两个批量动作。
     * [seedChapterId] = 右键点中的那一章，进来就顺手勾上——用户已经指着它了，
     * 不勾等于让他再点一次。
     */
    fun enterMultiSelect(seedChapterId: String? = null) {
        val id = localId ?: return
        if (repository.libraryItems.value.none { it.entry.id == id }) {
            // 标记已读 / 下载都写库，需要 manga 行存在；没入书架就先入架
            val manga = selected ?: return
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.importFromSource(sourceType!!, manga, chapters)
                    }
                }
            }
        }
        downloadMode = true
        downloadSelected = seedChapterId?.let { setOf(it) } ?: emptySet()
        showHint("已进入多选：勾选章节后可下载或标记已读/未读，『取消』退出")
    }

    fun exitDownloadMode() {
        downloadMode = false
        downloadSelected = emptySet()
    }

    fun toggleSelected(chapterId: String) {
        downloadSelected = if (chapterId in downloadSelected) {
            downloadSelected - chapterId
        } else {
            downloadSelected + chapterId
        }
    }

    /** 全选**未缓存**的章节（已缓存再下一次是纯浪费，enqueue 也会跳过）。 */
    fun selectAllUncached() {
        val id = localId ?: return
        val tasks = downloadManager.tasks.value
        downloadSelected = chapters
            .filter { tasks["$id#${it.id}"]?.status != DownloadStatus.COMPLETED }
            .map { it.id }
            .toSet()
    }

    /** 全选**全部**章节（2026-10-30 用户要求 2）。 */
    fun selectAll() {
        downloadSelected = chapters.map { it.id }.toSet()
    }

    /** 取消全选：清空勾选（用户要求 2）。 */
    fun clearSelection() {
        downloadSelected = emptySet()
    }

    /**
     * 批量标记已读 / 未读（2026-10-30 用户要求 3）。
     *
     * 逐章调 [LibraryRepository.setChapterRead]：它自己会补幽灵行、推进
     * `progressVersion`，详情页的章节状态随之重读（颜色立刻变），不需要额外刷新。
     */
    fun markSelectedRead(read: Boolean) {
        val id = localId ?: return
        if (downloadSelected.isEmpty()) return
        val chosen = chapters.filter { it.id in downloadSelected }
        var ok = 0
        chosen.forEach { ch ->
            if (repository.setChapterRead("$id#${ch.id}", read, ch.name, ch.index)) ok++
        }
        downloadSelected = emptySet()
        showHint(
            if (read) "已标记已读：$ok 话" else "已标记未读：$ok 话"
        )
    }

    fun startDownload() {
        val id = localId ?: return
        val manga = selected ?: return
        if (downloadSelected.isEmpty()) return
        val chosen = chapters.filter { it.id in downloadSelected }
        downloadManager.enqueue(id, chosen, manga.title)
        downloadMode = false
        downloadSelected = emptySet()
        showHint("已加入下载队列（${chosen.size} 话）——左侧「下载」页查看进度")
    }

    fun enqueueChapter(chapter: SourceChapter) {
        val id = localId ?: return
        val manga = selected ?: return
        downloadManager.enqueue(id, listOf(chapter), manga.title)
    }

    fun retryDownload(chapterLocalId: String) {
        downloadManager.retry(chapterLocalId)
    }

    fun addToShelf() {
        val id = localId ?: return
        val manga = selected ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.importFromSource(sourceType!!, manga, chapters)
                }
            }.onFailure { showHint("加入书架失败：${it.message ?: "未知原因"}") }
            showHint("已加入书架：${manga.title}")
        }
    }

    /**
     * 移出书架 = **软移出**（只把 `inLibrary` 置 0）。
     *
     * 2026-10-27 第五轮用户：「移除书架后会把记录都消除掉，要求保留记录防止误点被删」。
     * 根因就在这里：上一版调的是 `removeFromLibrary` —— 那是**硬删**，
     * 一个事务里把 `readingProgress` → `chapter` → `manga` 三张表按顺序删光，
     * 阅读位置、已读标记、历史记录全部消失，误点一下不可恢复。
     *
     * 书架页的多选删除早就改成了 `hideFromLibrary`（只翻标志位，可撤销），
     * 详情页这颗按钮漏改，于是出现「同一件事两套语义」。
     * 现在两边一致：**行还在库里，只是不在书架上**，重新加入书架时阅读位置原样回来。
     * 代价只有一行 `manga` + 若干 `chapter` + 一条 `progress`，一本书几十 KB 量级。
     */
    fun removeFromShelf() {
        val id = localId ?: return
        val title = selected?.title.orEmpty()
        repository.hideFromLibrary(listOf(id))
        showHint("已移出书架：$title（阅读记录已保留）")
    }

    /** 打开换源弹窗（F-45）。 */
    fun requestMigrate() {
        val id = localId ?: return
        val manga = selected ?: return
        migrateTarget = MangaEntry(
            id = id,
            title = manga.title,
            source = sourceType!!,
            sourceMangaId = manga.sourceId,
            coverUrl = manga.coverUrl,
            author = manga.author,
        )
    }

    fun dismissMigrate() {
        migrateTarget = null
    }

    /**
     * 迁移完成：本地 id 变了，当前面板还挂在旧源上——直接收回，
     * 否则用户会对着一份已经被删掉的旧条目操作。
     */
    fun onMigrated(newLocalId: String) {
        migrateTarget = null
        close()
        showHint("已迁移到 ${newLocalId.substringBefore(':')}")
    }

    private fun showHint(text: String) {
        hintJob?.cancel()
        hint = text
        hintJob = scope.launch {
            delay(4_000)
            hint = null
        }
    }

    private fun localChaptersOf(localId: String): List<SourceChapter> =
        repository.chaptersOf(localId).map { c ->
            SourceChapter(
                // 本地 id 是 "<localMangaId>#<sourceChapterId>"，**按第一个 # 切**
                // （源站 id 可能自带 #，用 substringAfterLast 会切错）
                id = c.id.substringAfter('#', c.id),
                name = c.name,
                index = c.index,
                pageCount = c.pageCount,
            )
        }

    /** 后台刷新：详情 + 章节，成功才覆盖本地那份。 */
    private suspend fun refresh(
        manga: SourceManga,
        type: SourceType,
        adapter: SourceAdapter,
        localId: String,
        forceRefresh: Boolean = false,
    ) {
        val snapshot = withContext(Dispatchers.IO) { adapter.loadDetails(manga.sourceId, forceRefresh) }
        currentCoroutineContext().ensureActive()
        val detail = snapshot.manga
        detail?.let { d ->
            // 封面选「能落盘的那张」：列表项 URL 可能已 404，详情页 og:image 往往才是活的。
            // 不能盲选某一方，按「谁先能缓存成功」定。
            val candidates = listOfNotNull(manga.coverUrl, d.coverUrl).distinct()
            var chosen: String? = candidates.firstOrNull { CoverCache.localUri(it) != null }
            if (chosen == null) {
                for (u in candidates) {
                    withContext(Dispatchers.IO) { CoverCache.resolve(u) }
                    if (CoverCache.localUri(u) != null) {
                        chosen = u
                        break
                    }
                }
            }
            if (selected?.sourceId == manga.sourceId && sourceType == type) {
                selected = d.copy(coverUrl = chosen ?: manga.coverUrl ?: d.coverUrl)
            }
            chosen?.let { c ->
                withContext(Dispatchers.IO) { repository.backfillCover(type, manga.sourceId, c) }
            }
        }
        currentCoroutineContext().ensureActive()
        val remote = snapshot.chapters
        val current = selected?.sourceId == manga.sourceId && sourceType == type
        if (snapshot.chaptersComplete) {
            if (current) chapters = remote
            withContext(Dispatchers.IO) {
                repository.syncChapters(localId, remote)
                currentCoroutineContext().ensureActive()
                ChapterRefresh.markLoaded(repository, localId)
            }
        } else {
            if (current) {
                chapterNotice = if (chapters.isNotEmpty()) "刷新未完成，保留已有章节" else "章节列表未取全"
                if (chapters.isEmpty()) chapters = remote
            }
            // 网络拿不到（离线 / 源站改版 / 返回空）——保留本地那份，不要清空
            AppLog.w(
                "Detail",
                "chapters empty, keep local (${chapters.size}) sid=${manga.sourceId}",
                null,
            )
        }
    }
}

/** 在页面里创建一份详情宿主状态（随组合生命周期）。 */
@Composable
fun rememberDetailHostState(
    repository: LibraryRepository,
    downloadManager: DownloadManager,
): DetailHostState {
    val scope = rememberCoroutineScope()
    return remember(scope, repository, downloadManager) {
        DetailHostState(repository, downloadManager, scope)
    }
}

/**
 * 详情二级页宿主：遮罩 + 底部上滑面板 + 换源弹窗。
 *
 * 调用方只要把它放在**铺满一级内容的容器**里（`Box { 一级内容; DetailHost(...) }`），
 * 面板就会盖在一级内容之上、左右两侧露出一级内容。
 */
@Composable
fun DetailHost(
    state: DetailHostState,
    repository: LibraryRepository,
    downloadManager: DownloadManager,
    modifier: Modifier = Modifier,
    onRead: (ReaderTarget) -> Unit,
    onOpenBrowser: (url: String, domain: String) -> Unit,
) {
    val visible = state.selected != null
    // 退出动画期间 selected 已是 null，直接读它会让面板滑到一半变成空白——
    // 留一份「最后一次打开的目标」，退出过程仍然画着原来的内容。
    var lastManga by remember { mutableStateOf<SourceManga?>(null) }
    var lastType by remember { mutableStateOf<SourceType?>(null) }
    LaunchedEffect(state.selected, state.sourceType) {
        if (state.selected != null) {
            lastManga = state.selected
            lastType = state.sourceType
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 宽度 62%，再夹到 [1/2, 2/3]（用户要求「二分之一至三分之二」）
        val panelWidth = (maxWidth * PanelWidthFraction)
            .coerceIn(maxWidth / 2f, maxWidth * 2f / 3f)
        val panelHeight = maxHeight - PanelTopGap

        // 遮罩压在面板**下面**：把一级内容暗 8%，点它 = 关详情
        DetailScrim(visible = visible, onClick = { state.close() })

        DetailPanelSlide(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            width = panelWidth,
            height = panelHeight,
        ) {
            val manga = state.selected ?: lastManga
            val type = state.sourceType ?: lastType
            if (manga != null && type != null) {
                DetailPanel(
                    manga = manga,
                    sourceType = type,
                    repository = repository,
                    chapters = state.chapters,
                    cachedPageCounts = state.cachedPageCounts,
                    downloadTasks = downloadManager.tasks.collectAsState().value,
                    chapterDesc = state.chapterDesc,
                    onToggleOrder = { state.toggleOrder() },
                    downloadMode = state.downloadMode,
                    downloadSelected = state.downloadSelected,
                    hint = state.hint,
                    chapterNotice = state.chapterNotice,
                    onRefresh = { state.reload() },
                    loading = state.loading,
                    onClose = { state.close() },
                    onRead = onRead,
                    onAddToShelf = { state.addToShelf() },
                    onRemoveFromShelf = { state.removeFromShelf() },
                    onEnterDownloadMode = { state.enterDownloadMode() },
                    onExitDownloadMode = { state.exitDownloadMode() },
                    onToggleSelected = { state.toggleSelected(it) },
                    onSelectAllUncached = { state.selectAllUncached() },
                    // 2026-10-30 用户要求 2：全选 / 取消全选
                    onSelectAll = { state.selectAll() },
                    onClearSelection = { state.clearSelection() },
                    // 2026-10-30 用户要求 3：右键进多选 + 批量标记
                    onEnterMultiSelect = { state.enterMultiSelect(it) },
                    onMarkSelectedRead = { state.markSelectedRead(it) },
                    onStartDownload = { state.startDownload() },
                    onEnqueueChapter = { state.enqueueChapter(it) },
                    onRetryDownload = { state.retryDownload(it) },
                    onOpenBrowser = onOpenBrowser,
                    onMigrate = { state.requestMigrate() },
                    panelWidth = panelWidth,
                )
            }
        }

        // F-45 换源弹窗：整页最前（盖住详情面板）
        state.migrateTarget?.let { target ->
            SourceMigrateDialog(
                entry = target,
                repository = repository,
                onDismiss = { state.dismissMigrate() },
                onMigrated = { newLocalId -> state.onMigrated(newLocalId) },
            )
        }
    }
}

/**
 * 面板圆角：**只圆顶部两角**。底部贴窗口底边，底部若也圆就会露出底下一级页的一小块白，
 * 看起来像面板「浮在半空」，所以底部必须是直角。
 */
private val DetailPanelShape = RoundedCornerShape(
    topStart = Tokens.Radius.Drawer,
    topEnd = Tokens.Radius.Drawer,
)

/**
 * 二级页从**底部中间**上滑展开。
 *
 * - **进入** 513ms（原 380ms + 35%）ease-out-quint + 淡入；
 * - **退出** 405ms（原 300ms + 35%）同一曲线，比进入略快 —— 退出比进入「急」一点，
 *   手感才不会拖；
 * - 叠一层淡入淡出：纯位移在白底上会有一条硬边，淡一层就融了。
 *
 * ## 为什么包成独立函数而不是直接写 `AnimatedVisibility`
 *
 * 调用点在 `BoxScope` 里，`AnimatedVisibility` 会匹配到 `BoxScope.AnimatedVisibility`；
 * 挪进这个不带 LayoutScope 接收者的函数里，顶层那个重载就能正常解析（上一轮踩过）。
 */
@Composable
private fun DetailPanelSlide(
    visible: Boolean,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.width(width).height(height),
        enter = slideInVertically(
            animationSpec = tween(DetailEnterMs, easing = DetailEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(DetailFadeInMs, delayMillis = DetailFadeInDelayMs)),
        exit = slideOutVertically(
            animationSpec = tween(DetailExitMs, easing = DetailEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(DetailFadeOutMs)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 28.dp,
                    shape = DetailPanelShape,
                    clip = false,
                    // 中性黑：彩色阴影会让白面板发脏（同 ShelfCard 的口径）
                    ambientColor = Color(0x14000000),
                    spotColor = Color(0x26000000),
                )
                .clip(DetailPanelShape)
                .background(Tokens.Color.BgElevatedSolid),
        ) { content() }
    }
}

/**
 * 详情面板外面的**透明点击层**（没有底色）。
 *
 * 2026-10-27 第四轮用户：「在浏览页打开的二级详情页不要再有遮盖一级菜单的灰色阴影背景」。
 * 上一版铺了一层 8% 黑，目的是「提示这里浮了一层」——但面板自己已经有 28dp 阴影，
 * 再压一层灰只会让一级菜单（dock、封面墙）发闷，看起来像整页被停用了。
 *
 * 所以底色去掉，**只保留点击语义**：点面板外的任何地方仍然收回详情（用户早前的口径
 * 「点一级菜单就把二级菜单收回去」不能丢）。淡入淡出保留，纯为了和面板同步出现。
 */
@Composable
private fun DetailScrim(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
        )
    }
}
