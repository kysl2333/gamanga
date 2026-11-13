package app.gamanga.desktop.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.ChapterRefresh
import app.gamanga.desktop.data.CustomCoverStore
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.browserUrl
import app.gamanga.desktop.source.domainOf
import app.gamanga.desktop.ui.ReaderTarget
import app.gamanga.desktop.ui.detail.DetailHost
import app.gamanga.desktop.ui.detail.rememberDetailHostState
import app.gamanga.desktop.ui.search.SearchBarTopInset
import app.gamanga.desktop.ui.search.SearchTopBar
import app.gamanga.desktop.ui.stats.StatsButton
import app.gamanga.desktop.ui.search.dismissSearchFocusOnOutsidePress
import app.gamanga.desktop.ui.search.rememberSearchBarBounds
import app.gamanga.desktop.ui.shell.RoundAction
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.MenuItemRow
import app.gamanga.desktop.ui.shell.MenuSeparator
import app.gamanga.desktop.ui.shell.TextAction
import app.gamanga.desktop.ui.shell.WindowMenu
import app.gamanga.desktop.ui.shell.DockMetrics
import app.gamanga.desktop.ui.shell.DockReservedHeight
import app.gamanga.desktop.ui.shell.UndoBar
import app.gamanga.desktop.ui.shell.UndoRequest
import app.gamanga.desktop.ui.shell.WaveIconButton
import app.gamanga.desktop.ui.shell.rememberUndoState
import app.gamanga.desktop.ui.migrate.SourceMigrateDialog
import app.gamanga.desktop.ui.theme.AppFont
import app.gamanga.desktop.ui.theme.BaselineAtBottom
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书架页（正常模式，2026-10-26 Compose 化）。
 *
 * 视觉与交互依据 `docs/ui/prototype/bookshelf-3x2.html`（用户已验收原型），
 * 壳层（无边框窗口 / 底部 dock / 右上关窗键）由 `NormalShell` 提供，本文件只管页面内容。
 *
 * ## 页面结构
 *
 * ```
 * ┌ 顶栏 76dp：居中 [胶囊搜索框] [圆形搜索键] ┐   ← UI-SPEC 1.1「顶栏搜索组」
 * │ 继续阅读  4 部              查看全部     │
 * │ [封面] [封面] [封面] [封面]              │
 * │ 全部漫画  12 部                          │
 * │ [封面] [封面] [封面] ...                 │   ← 列数按宽度自适应（Tokens.Grid）
 * └──────────────────────────────────────────┘
 * ```
 *
 * ## 三个必须保留的行为（都是用户实测后提的）
 *
 * 1. **多选态下不弹右键菜单**（F-61）：菜单项全是单本语义，多选下弹出会被理解成批量执行。
 * 2. **搜索键可点**（F-62）：输入框里的放大镜只是装饰，用户不知道要按回车。
 * 3. **删除可撤销**：走 [LibraryRepository.hideFromLibrary]（只置 inLibrary=0，不硬删），
 *    6 秒内点撤销能把书连同进度一起放回来。
 */
@Composable
fun BookshelfScreen(
    repository: LibraryRepository,
    onOpenBrowser: (url: String, domain: String) -> Unit,
    /** 「查看全部」→ 历史页。 */
    onSeeAllHistory: () -> Unit,
    /** 空书架的「去推荐页看看」→ 切到推荐页（用户 2026-10-28 要求）。 */
    onOpenRecommend: () -> Unit,
    /** 顶栏搜索键：把词带去浏览页做全网搜索（书架内过滤是输入框实时做的）。 */
    onSearch: (String) -> Unit,
    /**
     * 右键「开始阅读」→ 直接进阅读器（2026-10-26 用户要求：左键进详情、右键进阅读器）。
     * 章节列表由调用方从库里取（本地优先，不要等网络）。
     */
    onResume: (LibraryRepository.ShelfItem) -> Unit,
    /** 详情面板里点「阅读」→ 进阅读器（F-143：书架页内弹出二级详情页）。 */
    onRead: (ReaderTarget) -> Unit,
    /** R-04 下载管理器：详情面板的「已缓存」标记与选章下载都读它。 */
    downloadManager: DownloadManager = AppGraph.downloadManager,
) {
    val all by repository.shelfItems.collectAsState()
    // F-271 章节刷新：每本书「有几话是没看过的」（key = 本地漫画 id），以及是否正在刷新
    val chapterUpdates by ChapterRefresh.pending.collectAsState()
    val refreshing by ChapterRefresh.running.collectAsState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    /** 右键菜单：目标条目 + 按下点的窗口坐标（null = 关）。 */
    var ctx by remember { mutableStateOf<CtxTarget?>(null) }
    /**
     * 回撤条（共用件 `ui/shell/UndoBar.kt`）。「移出书架」「设置 / 取消自定义封面」都往这儿发，
     * 停留计时、新旧提示互相覆盖由 `UndoState` 统一管——页面自己再写一个 `delay(6000)`
     * 会和它抢（连续操作时前一条的计时器会把后一条提前清掉）。
     */
    val undo = rememberUndoState()
    /** 自定义封面的网络下载 / 本地读文件用（两者都是 IO，不能挂在 UI 线程上）。 */
    val scope = rememberCoroutineScope()
    /** 自定义封面失败时的轻提示（不弹回撤条：没有可撤销的动作）。 */
    var coverHint by remember { mutableStateOf<String?>(null) }
    /**
     * 自定义封面弹窗的目标 manga id（null = 关着）。
     *
     * 2026-10-27 第五轮：不再直接弹系统 `JFileChooser`，改成项目自己的选图弹窗
     * （本地文件 / 网络链接两种来源，见 `CoverPickerDialog`）。
     */
    var coverPickerFor by remember { mutableStateOf<String?>(null) }
    /** 网络链接下载中（弹窗里显示「处理中…」并锁住按钮）。 */
    var coverBusy by remember { mutableStateOf(false) }
    /** 弹窗内的错误提示（链接写错 / 下载失败 / 文件不是图片）。 */
    var coverError by remember { mutableStateOf<String?>(null) }
    /** F-45：换源弹窗的目标（null = 关着）。 */
    var migrateTarget by remember { mutableStateOf<MangaEntry?>(null) }
    /**
     * 详情二级页宿主（F-143）。
     *
     * 用户 2026-10-27 第二轮口径：「在书架页点击漫画封面时，在**该书架页内**展示二级详情页」。
     * 上一版是跳到浏览页再弹，等于把用户从书架拽走，回来还要重新找位置。
     */
    val detail = rememberDetailHostState(repository, downloadManager)

    val filtered = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { it.entry.title.contains(query.trim(), ignoreCase = true) }
    }
    // 继续阅读：有阅读记录的前 4 部，按最近阅读时间倒序。搜索时不出这一区（结果就是一区）
    val continueItems = remember(filtered, query) {
        if (query.isNotBlank()) {
            emptyList()
        } else {
            val recent = filtered
                .filter { it.lastReadAt > 0L }
                .sortedByDescending { it.lastReadAt }
                .take(4)
            // 用户 2026-10-26 口径：置顶的书**如果本来就在这 4 本里**，提到第一位；
            // **不在就不硬塞**（继续阅读的语义是「你最近在读的」，不是「你收藏的」）。
            recent.filter { it.pinned } + recent.filterNot { it.pinned }
        }
    }

    // 轻提示 3 秒后自动消失（比回撤条短：它只是「刚才那下没成功」，没有可做的事）
    LaunchedEffect(coverHint) {
        if (coverHint != null) {
            delay(3_000)
            coverHint = null
        }
    }

    fun removeItems(ids: List<String>) {
        if (ids.isEmpty()) return
        repository.hideFromLibrary(ids)
        // 文案保持「已移出 N 项」，回撤仍是 restoreToLibrary（只翻 inLibrary 标志，进度不丢）
        undo.show(UndoRequest("已移出 ${ids.size} 项") { repository.restoreToLibrary(ids) })
        coverHint = null
        picked = emptySet()
        multi = false
    }

    /**
     * 应用一张新封面（本地文件 / 网络图片共用）。
     *
     * **prevPath 必须在写盘之前读**——写完再读拿到的就是新路径，
     * 撤销会「恢复成刚设置的那张」，等于没撤销。
     */
    fun applyCover(id: String, bytes: ByteArray) {
        val prevPath = CustomCoverStore.localPath(id)
        if (!CustomCoverStore.setFromBytes(id, bytes)) {
            // 复制失败（磁盘满 / 文件被独占 / 不是图片）：轻提示，不给回撤（没有生效的动作可撤）
            undo.dismiss()
            coverError = "封面设置失败：这个文件不是可用的图片"
            return
        }
        coverPickerFor = null
        coverError = null
        undo.show(
            UndoRequest("已设置自定义封面") {
                // 之前没有自定义封面 → 撤回到「用源站封面」；有 → 指回原来那张图
                if (prevPath == null) CustomCoverStore.clear(id)
                else CustomCoverStore.setToPath(id, prevPath)
            }
        )
    }

    /**
     * 从网络链接设置封面（2026-10-27 第五轮用户：「一种是网络链接下载图片」）。
     *
     * 下载在 IO 线程、**存的是字节不是链接**：原站删图 / 防盗链 / 改域名都不影响已设好的封面。
     * 失败原因原样带到弹窗里（用户要能判断是链接写错还是站点挡了）。
     */
    fun applyCoverFromUrl(id: String, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            coverError = "链接要以 http:// 或 https:// 开头"
            return
        }
        coverBusy = true
        coverError = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Http.getBytes(url) } }
            coverBusy = false
            result.fold(
                onSuccess = { bytes ->
                    if (bytes.isEmpty()) {
                        coverError = "这个链接没有返回图片内容"
                    } else {
                        applyCover(id, bytes)
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    coverError = "下载失败：${e.message ?: "网络不可用"}"
                },
            )
        }
    }

    /** 取消自定义封面：同样先记 prevPath，回撤时指回原文件（[CustomCoverStore.clear] 不删文件）。 */
    fun clearCustomCover(id: String) {
        val prevPath = CustomCoverStore.localPath(id) ?: return
        CustomCoverStore.clear(id)
        undo.show(UndoRequest("已取消自定义封面") { CustomCoverStore.setToPath(id, prevPath) })
    }

    // 「点搜索框外面就失焦」（用户 2026-10-27）：按下点落在搜索栏矩形外 → clearFocus。
    // 卡片用的是自定义 onPointerEvent 且会 consume，焦点系统收不到「点到别处了」，
    // 所以必须自己判一次几何。
    val searchBounds = rememberSearchBarBounds()
    Box(Modifier.fillMaxSize().dismissSearchFocusOnOutsidePress(searchBounds)) {
        // ---- 沉浸式布局（用户 2026-10-27）----
        // 「菜单按钮是悬浮在主菜单的，现在漫画封面无法穿过底部和顶部；
        //   搜索栏和按钮是虚浮在书架上的，正常情况封面可以从他们下面经过」
        // 所以内容层**铺满整页**，顶栏是浮在上面的遮罩层，滚动时封面从它下面滑过。
        if (all.isEmpty()) {
            EmptyShelf(onOpenRecommend = onOpenRecommend)
        } else if (filtered.isEmpty()) {
            EmptyShelf(text = "书架里没有「${query.trim()}」")
        } else {
                ShelfGrid(
                    continueItems = continueItems,
                    allItems = filtered,
                    multi = multi,
                    picked = picked,
                    onSeeAllHistory = onSeeAllHistory,
                    // F-271：每本书的新章节数（卡片右上角绿点）+ 标题旁刷新按钮的状态
                    chapterUpdates = chapterUpdates,
                    refreshing = refreshing,
                    onRefreshChapters = {
                        scope.launch {
                            val found = ChapterRefresh.run(repository, "书架手动刷新")
                            coverHint = found.message
                        }
                    },
                    onCardClick = { item ->
                        // 搜索框有焦点时，第一下点击会先被输入框拿去「失焦」，
                        // 卡片收不到事件（用户 2026-10-26 实测「优先触发搜索」）。
                        // 这里先主动清焦点，再走原来的逻辑。
                        focus.clearFocus()
                        if (multi) {
                            picked = if (item.entry.id in picked) picked - item.entry.id
                            else picked + item.entry.id
                        } else {
                            // 用户口径「进入该漫画详情页后（绿点）消失」——点开即视为看过
                            ChapterRefresh.clear(repository, item.entry.id)
                            // F-143：**留在书架页**弹出二级详情页，不跳浏览页。
                            // 封面/作者从书架条目带上，面板第一帧就有内容（不必等网络）。
                            detail.open(
                                SourceManga(
                                    sourceId = item.entry.sourceMangaId,
                                    title = item.entry.title,
                                    coverUrl = item.entry.coverUrl,
                                    author = item.entry.author,
                                ),
                                item.entry.source,
                            )
                        }
                    },
                    // F-61（用户 2026-10-26 复验不通过）：多选态下**一律不弹**右键菜单。
                    // 上一版把这个判断漏在了页面层，卡片照样上报，于是菜单还是出来了。
                    onCardContextMenu = { item, pos -> if (!multi) ctx = CtxTarget(item, pos) },
                )
        }

        // 顶栏浮在内容之上（不占布局），底部接一段渐变让封面自然淡出
        TopBarOverlay(
            query = query,
            onQueryChange = { typed ->
                query = typed
                // 用户 2026-10-26 新口径：「输入文字后立刻跳转到浏览页」——
                // 书架页的搜索框只是全局搜索的入口，不再本地过滤书架
                // （书架命中搬到浏览页第一排显示）。
                if (typed.isNotBlank()) onSearch(typed)
            },
            onSearch = { onSearch(query.trim()) },
            bounds = searchBounds,
            // 统计入口（F-38 UI 落地）：贴在搜索栏最左侧，只有图标没有文字。
            // 和 dock 一样浮在书架之上，点开是一个悬浮小框。
            leading = {
                StatsButton(statsProvider = { repository.readingStats() })
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 多选操作组：浮在内容之上、**dock 之上**。
        //
        // 2026-10-27 第五轮用户（严重问题）：「多选开启后 删除和返回按键被菜单栏挡住了，
        // 把多选的这两按键抬高」；第六轮：「多选没对齐菜单栏中心」。
        // 2026-10-29 第三次报同一个问题 → **不再手算常量**：位置改由 [DockMetrics]
        // 里 dock 的实测矩形决定（纵向 = dock 顶边 + 20dp，横向 = dock 中心线）。
        // dock 尺寸以后怎么改，这两个圆钮都不会再被盖住或偏一边。
        MultiActions(
            visible = multi,
            count = picked.size,
            onDelete = { removeItems(picked.toList()) },
            onExit = {
                multi = false
                picked = emptySet()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = DockMetrics.floatingBottom)
                .offset(x = DockMetrics.centerOffsetX),
        )

        // 回撤条：与多选组同位置（互斥：删除后 multi 已退出）
        // 位置（dock 上方 + 一段间距）由 UndoBar 内部统一定义一次，
        // 这里只负责「底部居中」——四处调用点各算一遍必然飘。
        UndoBar(state = undo, modifier = Modifier.align(Alignment.BottomCenter))

        // 封面操作失败的轻提示：同一位置、同一观感，但没有「撤销」（没有生效的动作可撤）
        CoverHint(
            text = coverHint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 与多选组 / 回撤条同一套底部口径（读 dock 实测几何），否则同样会被 dock 盖住
                .padding(bottom = DockMetrics.floatingBottom)
                .offset(x = DockMetrics.centerOffsetX),
        )

        // 自定义封面弹窗（2026-10-27 第五轮）：本地文件 / 网络链接两种来源
        coverPickerFor?.let { id ->
            CoverPickerDialog(
                busy = coverBusy,
                error = coverError,
                onDismiss = {
                    if (!coverBusy) {
                        coverPickerFor = null
                        coverError = null
                    }
                },
                onPickFile = { file ->
                    // 读文件放到 IO 上：几 MB 的图在主线程读会卡一帧
                    scope.launch {
                        coverBusy = true
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching { file.readBytes() }.getOrNull()
                        }
                        coverBusy = false
                        if (bytes == null || bytes.isEmpty()) {
                            coverError = "读不到这个文件（可能被占用或没有权限）"
                        } else {
                            applyCover(id, bytes)
                        }
                    }
                },
                onPickUrl = { url -> applyCoverFromUrl(id, url) },
            )
        }

        // F-45 换源弹窗（整页最前）：迁移成功后书架流会自动刷新，这里只给一条轻提示
        migrateTarget?.let { target ->
            SourceMigrateDialog(
                entry = target,
                repository = repository,
                onDismiss = { migrateTarget = null },
                onMigrated = { newLocalId ->
                    migrateTarget = null
                    coverHint = "已迁移到 ${newLocalId.substringBefore(':')}"
                },
            )
        }

        ctx?.let { target ->
            ShelfContextMenu(
                target = target,
                onDismiss = { ctx = null },
                onRead = {
                    ctx = null
                    onResume(target.item)
                },
                onToggleRead = {
                    ctx = null
                    // 整本已读（unreadCount == 0）时这一项是「标记未读」，否则「标记已读」。
                    // 用户 2026-10-26：「右键标记已读后这个已读漫画的右键标记已读变为标记未读」。
                    val id = target.item.entry.id
                    if (target.item.unreadCount == 0L) repository.markAllUnread(id)
                    else repository.markAllRead(id)
                },
                onEnterMulti = {
                    ctx = null
                    multi = true
                    picked = setOf(target.item.entry.id)
                },
                onTogglePin = {
                    ctx = null
                    // 用户 2026-10-26：「右键置顶选项没有了」——上一版重写菜单时漏了这一项，
                    // 而 `repository.togglePin` 一直在，只是没入口
                    repository.togglePin(target.item.entry.id)
                },
                onToggleCustomCover = {
                    ctx = null
                    // 有自定义封面 → 「取消自定义封面」；没有 → 「自定义封面」。
                    // 判断和菜单文案同源（都读 CustomCoverStore.has），不会出现
                    // 「菜单写自定义、点了却执行取消」的错位。
                    val id = target.item.entry.id
                    if (CustomCoverStore.has(id)) {
                        clearCustomCover(id)
                    } else {
                        coverError = null
                        coverPickerFor = id
                    }
                },
                onMigrate = {
                    ctx = null
                    // 书架条目就是 MangaEntry（含本地 id 与源信息），换源弹窗直接吃它
                    migrateTarget = target.item.entry
                },
                onOpenBrowser = {
                    ctx = null
                    onOpenBrowser(
                        browserUrl(target.item.entry.source, target.item.entry.sourceMangaId),
                        domainOf(target.item.entry.source),
                    )
                },
                onRemove = {
                    ctx = null
                    removeItems(listOf(target.item.entry.id))
                },
            )
        }

        // 详情二级页：宿主自带遮罩 + 底部上滑面板 + 换源弹窗（F-143）。
        // 声明在右键菜单之后 = 画在菜单之上（点封面进来的面板要盖住一切）。
        DetailHost(
            state = detail,
            repository = repository,
            downloadManager = downloadManager,
            onRead = onRead,
            onOpenBrowser = onOpenBrowser,
        )
    }
}

/** 右键菜单的目标（条目 + 窗口坐标）。 */
private data class CtxTarget(val item: LibraryRepository.ShelfItem, val position: Offset)

/**
 * 顶栏：居中「胶囊搜索框 + 圆形搜索键」（UI-SPEC 1.1 硬约束）。
 *
 * 搜索键用 [WaveIconButton]：三态波浪 + 点击注满出彩色，用户 2026-10-26
 * 已验收「搜索键好看就这样」，这里只是换位置复用，**不要重写一套**。
 */
@Suppress("unused") // 已被 SearchTopBar 取代，注释保留供后续重构参考
@Composable
private fun TopBar(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 拖拽条 2026-10-26 提到 45dp（用户「改为 45dp 或规范」），
            // 顶栏只留「42dp 搜索框 + 上下各 13dp」= 68dp。
            // 上一版 84dp 时搜索框下面空太多（用户：「搜索栏和下面的间距有点宽了」）。
            .height(60.dp)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // 用户 2026-10-26：「搜索框不填满，缩短只要中间部分长度，搜索键在搜索框右侧」
            // → 固定 420dp 居中，不再按可用宽度拉伸（1920 下拉伸到 560 仍然显得空）
            Modifier.width(420.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSearch,
                modifier = Modifier.weight(1f),
            )
            WaveIconButton(
                icon = DockIcons.Search,
                contentDescription = "搜索",
                onClick = onSearch,
                size = 42.dp,
            )
        }
    }
}

/**
 * 顶栏浮层：**完全透明**，只有搜索框和搜索键浮在封面上。
 *
 * ## 为什么最终是透明（用户 2026-10-27 第三轮）
 *
 * 前两版都被否：
 * - 渐变遮罩 → 「不要什么遮罩」；
 * - 实色底 → 截图里那条白带（`padding(top=36)` + 实色背景，视觉上就是窗口顶上一条白条）。
 *
 * 用户的诉求是「下面就只有漫画封面一层，按钮浮空在封面上」——所以顶栏不能再有自己的底色。
 * 现在整条顶栏透明：封面可以一直滚到窗口最顶、从搜索框周围穿过去；
 * 搜索框自己有白底 + 阴影，浮在封面之上，可读性不受影响。
 */
@Composable
private fun TopBarOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    bounds: app.gamanga.desktop.ui.search.SearchBarBounds? = null,
    /** 搜索栏左端外侧的槽位（统计入口）。 */
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            // 内容落在拖拽带（壳层最上层 36dp）下面，否则搜索框点不到。
            // **不加任何背景**：加了就是那条白带。封面从这里穿过去。
            // 2026-10-29 用户第三次报「搜索栏没对齐」：顶距改读三页共用的
            // [SearchBarTopInset]（= 拖拽带 + 20dp），与浏览页 / 推荐页落在同一条线上。
            .padding(top = SearchBarTopInset)
    ) {
        // **与浏览页共用同一个顶栏组件**（用户 2026-10-27：「书架搜索栏和浏览页搜索栏没对齐，
        // 他们本身是同一个组件」）。之前两边各写一份，宽度/圆角/阴影慢慢就飘了。
        // 下面那个 `TopBar` 暂时留着（`@Suppress("unused")`）——它记录的「透明顶栏 + 拖拽带让位」
        // 注释还有价值，等下次重构一起清。
        SearchTopBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            bounds = bounds,
            leading = leading,
        )
    }
}

/** 胶囊搜索框：白底 + 1px 细边 + 内嵌装饰放大镜。 */
@Suppress("unused") // 已被 SearchTopBar 内的输入框取代
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .height(42.dp)
            .shadow(2.dp, RoundedCornerShape(Tokens.Radius.Capsule))
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(Tokens.Color.BgElevatedSolid)
            .border(
                width = 1.dp,
                color = if (focused) Tokens.Color.Accent.copy(alpha = 0.45f) else Tokens.Color.BorderHairline,
                shape = RoundedCornerShape(Tokens.Radius.Capsule),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = DockIcons.Search,
            contentDescription = null,
            tint = Tokens.Color.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                MixText(
                    text = "搜索书架",
                    color = Tokens.Color.TextTertiary,
                    fontSize = Tokens.Type.Body.size,
                    style = Tokens.Type.Body.toTextStyle(),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = Tokens.Type.Body.toTextStyle(AppFont.Ui).copy(color = Tokens.Color.TextPrimary),
                cursorBrush = SolidColor(Tokens.Color.Accent),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 空书架 / 搜索无结果。
 *
 * 用户 2026-10-28：「书架没书时界面给一个提示可以去推荐页查漫画」。
 * 所以**真的空**的时候不只丢一行灰字，而是给一句引导 + 一颗「去推荐页看看」按钮。
 * 搜索筛空的时候不给按钮——那不是书架空，是关键词没命中，给按钮反而误导。
 */
@Composable
internal fun EmptyShelf(
    text: String = "书架还是空的",
    onOpenRecommend: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MixText(
                text = text,
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Subtitle.size,
                style = Tokens.Type.Subtitle.toTextStyle(),
            )
            if (onOpenRecommend != null) {
                Spacer(Modifier.height(Tokens.Space.Sm))
                MixText(
                    text = "推荐页里挑一本，点开就能加入书架",
                    color = Tokens.Color.TextTertiary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Tokens.Space.Lg))
                EmptyShelfAction(onClick = onOpenRecommend)
            }
        }
    }
}

/**
 * 空书架的引导按钮：白卡 + 细描边 + hover 浮起，和设置页按钮同一套手感。
 *
 * 点击必须用 [onPointerEvent] 判主键（`clickable` 会把右键也算点击，R-09 教训）；
 * 这里没有复用设置页的 `primaryPress`——它是各文件里的 private 扩展，不跨文件可见。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EmptyShelfAction(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(999.dp)
    val lift by animateDpAsState(
        targetValue = if (hovered) 4.dp else 0.dp,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "emptyShelfLift",
    )
    Row(
        Modifier
            .height(36.dp)
            .shadow(
                elevation = lift,
                shape = shape,
                clip = false,
                ambientColor = Color(0x0F000000),
                spotColor = Color(0x1A000000),
            )
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Tokens.Color.BorderHairline, shape)
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isPrimaryPressed && event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }
                    onClick()
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MixText("去推荐页看看", fontSize = 13.sp, color = Tokens.Color.TextPrimary)
    }
}

/**
 * 封面墙：继续阅读（最多 4 部）+ 全部漫画。
 *
 * 同一本书会在两个区各出现一次（原型已知取舍：语义上选的是书，不是卡片），
 * 所以 grid key 必须带区块前缀，否则 Compose 会因重复 key 崩溃。
 */
@Composable
private fun ShelfGrid(
    continueItems: List<LibraryRepository.ShelfItem>,
    allItems: List<LibraryRepository.ShelfItem>,
    multi: Boolean,
    picked: Set<String>,
    onSeeAllHistory: () -> Unit,
    /** F-271：本地漫画 id → 新章节数（0/缺省 = 没有更新）。 */
    chapterUpdates: Map<String, Int>,
    refreshing: Boolean,
    onRefreshChapters: () -> Unit,
    onCardClick: (LibraryRepository.ShelfItem) -> Unit,
    onCardContextMenu: (LibraryRepository.ShelfItem, Offset) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxWidth - Tokens.Grid.ContentPaddingH * 2
        val columns = Tokens.Grid.columnsFor(available)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Tokens.Grid.ContentPaddingH,
                end = Tokens.Grid.ContentPaddingH,
                // 顶部留出「拖拽带 + 顶栏」的高度（用户 2026-10-27：「书架页的设计太高了，
                // 应该在搜索栏下方才对」——上一版写 0，滚到顶时「继续阅读」标题跑到搜索框上面去了）。
                // 留 96 = 36 拖拽带 + 60 顶栏：滚到顶时第一行正好在搜索框下方；
                // 继续滚动时封面会向上滑过搜索框（顶栏是透明的，能看见）——沉浸感不丢。
                top = 96.dp,
                // 底部留 dock 的高度：**这是滚动内容的一部分，不是固定白带**——
                // 滚动过程中封面照样会滑到 dock 下面（浮空效果），
                // 但滚到底时最后一行能完整停在 dock 上方，书名不会被永久遮住。
                bottom = 88.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapH),
            verticalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapV),
        ) {
            if (continueItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "sec-continue") {
                    SectionHeader(
                        title = "继续阅读",
                        // 用户 2026-10-26：「继续阅读 把【4 部】给去掉」——这一区本来就只有 4 本，
                        // 计数是废话；「全部漫画」那区保留（用户要看到总数）
                        count = null,
                        action = "查看全部",
                        onAction = onSeeAllHistory,
                    )
                }
                items(continueItems, key = { "cont-${it.entry.id}" }) { item ->
                    ShelfCard(
                        item = item,
                        multi = multi,
                        picked = item.entry.id in picked,
                        newChapters = chapterUpdates[item.entry.id] ?: 0,
                        onClick = { onCardClick(item) },
                        onContextMenu = { pos -> onCardContextMenu(item, pos) },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "sec-all") {
                SectionHeader(
                    title = "全部漫画",
                    count = allItems.size,
                    action = null,
                    onAction = null,
                    // 用户 2026-10-29：「如果有更新章节，就在书架页全部漫画 N 部灰字旁边加个
                    // 章节刷新小图标按钮」——按钮常驻（手动档也要能点），有更新时带绿点数字。
                    refreshing = refreshing,
                    updates = chapterUpdates.size,
                    onRefresh = onRefreshChapters,
                )
            }
            items(allItems, key = { "all-${it.entry.id}" }) { item ->
                ShelfCard(
                    item = item,
                    multi = multi,
                    picked = item.entry.id in picked,
                    newChapters = chapterUpdates[item.entry.id] ?: 0,
                    onClick = { onCardClick(item) },
                    onContextMenu = { pos -> onCardContextMenu(item, pos) },
                )
            }
        }
    }
}

/** 分区标题：标题 + 计数 +（可选）章节刷新按钮 + 右侧文字动作（「查看全部」）。 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int?,
    action: String?,
    onAction: (() -> Unit)?,
    /** F-271：为 true 时刷新图标转圈（不用转圈 loading，UI-SPEC 第 5 节）。 */
    refreshing: Boolean = false,
    /** 有更新的漫画本数（0 = 没有，按钮上不挂绿点）。 */
    updates: Int = 0,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
        // 兜底：右侧「查看全部」这类没有基线的元素按底对齐。
        // 左边三件（标题 / N 部 / 刷新图标）**不走底对齐**，走基线——见 R-182 的注释。
        verticalAlignment = Alignment.Bottom,
    ) {
        // R-182（2026-10-30 用户）：「这三个都要底部对齐，对齐全部漫画」。
        // 原来三个都用 `Alignment.Bottom`（对齐布局盒子底边），但文字盒子的底边是
        // 「基线 + 降部」，16sp 的降部约 3~4dp、12sp 的更小，图标又是 24dp 方盒 →
        // 三条底边各差几 dp，看上去就是没对齐。
        // 现在统一按**基线**对齐：文字用 `alignByBaseline()`，图标用 [BaselineAtBottom]
        // 把底边当基线，三者的视觉底边落在同一条线上。
        MixText(
            text = title,
            modifier = Modifier.alignByBaseline(),
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.copy(weight = FontWeight.SemiBold).toTextStyle(),
        )
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            MixText(
                text = "$count 部",
                modifier = Modifier.alignByBaseline(),
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
            )
        }
        if (onRefresh != null) {
            // R-167（2026-10-29 用户）：「刷新图标对齐 2 部文字的底对齐并间距靠近」。
            // R-182 起改成按基线对齐：图标的底边 = 「2 部」的字形底边，不用再补 padding 微调。
            Spacer(Modifier.width(4.dp))
            BaselineAtBottom(Modifier.alignByBaseline()) {
                ChapterRefreshButton(refreshing = refreshing, updates = updates, onClick = onRefresh)
            }
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.weight(1f))
            // 2026-10-27 用户：「查看全部文字按键添加触碰和点击的自然动效」——
            // 与浏览历史「清除全部」共用同一个 TextAction（触碰变强调色 + 浮浅底，按下缩 0.94）
            TextAction(text = action, onClick = onAction, idleColor = Tokens.Color.Accent)
        }
    }
}

/**
 * 章节刷新小图标按钮（F-271，2026-10-29）。
 *
 * 尺寸跟分区标题的字高对齐（24dp 圆 + 15dp 图标），hover 出浅底——
 * 与「源分区标题里的登录小地球」同一套小图标语言，整页只有一种。
 * 刷新中：图标按 900ms 一圈匀速转；有更新：右上角一颗绿色小点（用户要求「绿色提示点」）。
 */
@Composable
private fun ChapterRefreshButton(refreshing: Boolean, updates: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val transition = rememberInfiniteTransition(label = "refreshSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "refreshAngle",
    )
    // R-175（2026-10-30 用户）：「刷新图标多了半点绿色的点」——绿点画在 24dp 方框的右上角，
    // 而那个方框自己 `clip(CircleShape)` → 角上的点被圆裁掉一半，看着像半个绿点。
    // 拆成两层：外层不裁（只做定位），内层才是圆形按钮本体，绿点挂在外层上。
    Box(Modifier.size(24.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
                .hoverable(interaction)
                .clickable(enabled = !refreshing, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Icon(
                imageVector = DockIcons.Refresh,
                contentDescription = if (refreshing) "正在刷新章节" else "刷新章节",
                tint = when {
                    refreshing -> Tokens.Color.Accent
                    hovered -> Tokens.Color.Accent
                    else -> Tokens.Color.TextTertiary
                },
                // R-182 起不再自己补 1dp 找平：外层 [BaselineAtBottom] 已经把「按钮底边」
                // 当成基线报给这一行，图标贴住按钮底边就等于贴住文字基线。
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { rotationZ = if (refreshing) angle else 0f },
            )
        }
        if (updates > 0 && !refreshing) {
            // 落在圆的 45° 边上（不是方框角），并且不再被裁
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Tokens.Color.Success),
            )
        }
    }
}

/**
 * 多选操作组：圆形删除（带数量角标）+ 圆形退出。
 *
 * 原型里它在 dock 正上方，两个 52dp 圆钮浮入（透明度 + 8dp 上移）。
 * `visible=false` 时**保留布局但不可点**，避免进出多选时整页跳动。
 */
@Composable
private fun MultiActions(
    visible: Boolean,
    count: Int,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(180),
        label = "multiAppear",
    )
    Row(
        modifier
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 8.dp.toPx()
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val enabled = visible && count > 0
        RoundAction(
            icon = DockIcons.Trash,
            description = "删除所选",
            danger = enabled,
            enabled = enabled,
            badge = if (count > 0) count.toString() else null,
            onClick = onDelete,
        )
        RoundAction(
            icon = DockIcons.Close,
            description = "退出多选",
            danger = false,
            enabled = visible,
            badge = null,
            onClick = onExit,
        )
    }
}

/**
 * 轻提示：只有文字，没有「撤销」。
 *
 * 为什么需要它：设封面失败（复制不了文件）时**不能弹回撤条**——没有生效的动作可撤，
 * 给一个点不动的「撤销」比不给更糟。但也不能静默（用户点了菜单什么都没发生）。
 * 所以沿用回撤条的观感（白卡 + 细边 + 大阴影 + 圆角），只去掉那个键，
 * 并且出现时先 `undo.dismiss()`——UI-SPEC 第 7 节「同一屏最多一条状态提示」。
 */
@Composable
private fun CoverHint(text: String?, modifier: Modifier = Modifier) {
    val appear by animateFloatAsState(
        targetValue = if (text != null) 1f else 0f,
        animationSpec = tween(180),
        label = "coverHintAppear",
    )
    if (appear <= 0.01f) return
    val shape = RoundedCornerShape(Tokens.Radius.Card)
    Box(
        modifier
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 10.dp.toPx() }
            .shadow(
                elevation = Tokens.Elevation.Overlay,
                shape = shape,
                clip = false,
                ambientColor = Color(0x14000000),
                spotColor = Color(0x26000000),
            )
            .clip(shape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, Tokens.Color.BorderHairline, shape)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        MixText(
            text = text.orEmpty(),
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.toTextStyle(),
        )
    }
}


/**
 * 右键菜单（页面级唯一实例）。
 *
 * 定位沿用旧书架页的教训（R-09）：`DropdownMenu` 的 `offset` 是相对锚点的另一套坐标空间，
 * 喂格子内坐标会弹到窗口别处。这里用 [Popup] + [PopupPositionProvider]，
 * 直接把**窗口绝对坐标**当菜单左上角，再按窗口尺寸夹取。
 */
@Composable
private fun ShelfContextMenu(
    target: CtxTarget,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onToggleRead: () -> Unit,
    onEnterMulti: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleCustomCover: () -> Unit,
    onMigrate: () -> Unit,
    onOpenBrowser: () -> Unit,
    onRemove: () -> Unit,
) {
    WindowMenu(position = target.position, onDismiss = onDismiss) {
        CtxItem("开始阅读", DockIcons.Play, danger = false, onClick = onRead)
        CtxItem(
            label = if (target.item.unreadCount == 0L) "标记未读" else "标记已读",
            icon = DockIcons.Check,
            danger = false,
            onClick = onToggleRead,
        )
        CtxItem(
            label = if (target.item.pinned) "取消置顶" else "置顶",
            icon = DockIcons.Pin,
            danger = false,
            onClick = onTogglePin,
        )
        // 「自定义封面 / 取消自定义封面」共用一颗图标：语义是「这本的封面图」，
        // 换文案已经说清是设置还是取消，换图标反而要用户重新认一遍。
        CtxItem(
            label = if (CustomCoverStore.has(target.item.entry.id)) "取消自定义封面" else "自定义封面",
            icon = DockIcons.Picture,
            danger = false,
            onClick = onToggleCustomCover,
        )
        // F-45 换源：源站 404 / 改版 / 被风控时，把这本书整本搬到别的源（章节与进度按对齐关系迁移）
        CtxItem("换源…", DockIcons.Globe, danger = false, onClick = onMigrate)
        CtxItem("在浏览器打开", DockIcons.External, danger = false, onClick = onOpenBrowser)
        CtxItem("多选", DockIcons.Pick, danger = false, onClick = onEnterMulti)
        MenuSeparator()
        CtxItem("移出书架", DockIcons.Trash, danger = true, onClick = onRemove)
    }
}

/** 菜单项：与浏览历史右键菜单共用（唯一实现在 `ui/shell/ContextMenu.kt`）。 */
@Composable
private fun CtxItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean,
    onClick: () -> Unit,
) = MenuItemRow(label = label, icon = icon, danger = danger, onClick = onClick)
