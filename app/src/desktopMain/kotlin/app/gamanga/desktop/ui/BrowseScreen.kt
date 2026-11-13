package app.gamanga.desktop.ui

import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.BrowseHistoryEntry
import app.gamanga.desktop.data.BrowseHistoryStore
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.ZhSearch
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.ui.detail.DetailHost
import app.gamanga.desktop.ui.detail.rememberDetailHostState
import app.gamanga.desktop.ui.search.BrowseHistoryPane
import app.gamanga.desktop.ui.search.GlobalSearchPane
import app.gamanga.desktop.ui.search.SearchTopBar
import app.gamanga.desktop.ui.search.dismissSearchFocusOnOutsidePress
import app.gamanga.desktop.ui.search.rememberSearchBarBounds
import app.gamanga.desktop.ui.theme.Tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 浏览页（M3）：**浏览历史 → 搜索 → 详情/章节 → 加入书架 / 开读**。
 *
 * ## 三段式信息架构（F-128，2026-10-27 用户重新定义）
 *
 * | 状态 | 主区显示什么 |
 * |---|---|
 * | 搜索框**空** | 只显示「浏览历史」（[BrowseHistoryPane]）——在浏览页点开过的封面 |
 * | 有字但**没提交** | 只显示「书架」那一行（纯内存过滤，边打边筛） |
 * | **点过**搜索键/回车 | 书架 + 各源分区，**常驻**，直到用户把文字删空 |
 *
 * 之前是「一进来就是一片空白等输入」，用户嫌脏。现在首屏是历史，干净且有用。
 *
 * 浏览历史与阅读进度历史**完全独立**（[app.gamanga.desktop.data.BrowseHistoryStore]）：
 * 只看过简介没读过的书也在这里，历史页（`NavItem.HISTORY`）不受影响。
 *
 * 每条源都有「浏览器兜底」：源站改版或需要登录态时，直接在内嵌浏览器里看。
 */
@Composable
fun BrowseScreen(
    repository: LibraryRepository,
    onOpenBrowser: (url: String, domain: String) -> Unit,
    onRead: (ReaderTarget) -> Unit,
    /** 从书架点进来时直接落到这本漫画的详细页（源类型 + 源站条目）。 */
    initialDetail: Pair<SourceType, SourceManga>? = null,
    /** 从书架顶栏搜索键带过来的词：进页面后自动搜一次（2026-10-26）。 */
    initialKeyword: String? = null,
    autoSubmitInitialKeyword: Boolean = false,
    /** R-04 下载管理器（详情页选章下载 / 「已缓存」标记的数据源）。 */
    downloadManager: DownloadManager = AppGraph.downloadManager,
    /** F-128：浏览历史（首屏内容 + 点击记录）。 */
    historyStore: BrowseHistoryStore = AppGraph.browseHistory,
    /**
     * F-129：dock 里「浏览」按钮被点了几次（自增计数）。
     *
     * 用途：详情是二级页，用户要能「点一级菜单把二级页收回去」。但那时 `NavItem.BROWSE`
     * 已经是选中态，再点一次状态不变、页面收不到任何信号。壳层因此把「重复点同一个菜单项」
     * 计成一次自增，页面据此关闭详情。
     */
    navClickSeq: Int = 0,
    /**
     * `initialDetail` / `initialKeyword` 已被消费。
     *
     * 上游必须据此把它们置空：否则切到别的页再切回来，BrowseScreen 是新建实例，
     * 会拿同一个旧值再开一次详情 —— 就是「点浏览按钮冒出个漫画二级页」那个 bug。
     * （关键词同理：不置空的话每次回浏览页都会把上次搜的词重新填回输入框。）
     */
    onInitialConsumed: () -> Unit = {},
) {
    var keyword by remember { mutableStateOf(initialKeyword.orEmpty()) }
    var results by remember { mutableStateOf<List<SourceManga>>(emptyList()) }

    // ---- 全局搜索状态（2026-10-26 用户重定义）----
    /** 书架条目（简繁归一化后过滤，命中显示在第一排）。 */
    val shelfItems by repository.shelfItems.collectAsState()
    /** 浏览历史（F-128）：只在浏览页点过的封面，与阅读进度历史无关。 */
    val browseHistory by historyStore.items.collectAsState()
    val shelfHits = remember(shelfItems, keyword) {
        if (keyword.isBlank()) emptyList()
        else shelfItems.filter { ZhSearch.matches(it.entry.title, keyword) }
    }
    /** 各源搜索状态（key 缺省 = 还没搜）。 */
    var sourceStates by remember {
        mutableStateOf<Map<SourceType, app.gamanga.desktop.data.SourceSearchState>>(emptyMap())
    }
    /** 是否已经点过搜索键/回车——没点过时源分区只显示「按回车搜索」。 */
    var sourceSearchStarted by remember { mutableStateOf(false) }
    /** 正在跑的搜索（重复点搜索键要先取消上一次，否则两批结果混在一起）。 */
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /**
     * 详情二级页宿主（F-143）。状态机与渲染都在 `ui/detail/DetailHost.kt`。
     *
     * 用户这次要求「在书架页点封面就在书架页内弹出、在阅览页点封面就在阅览页内弹出」——
     * 详情页不能再长在某一个页面里。抽成宿主之后，三个页面各自持有一份 `DetailHostState`，
     * 谁都不再自己维护「选中的书 / 章节 / 多选下载 / 换源目标」这套状态。
     */
    val detail = rememberDetailHostState(repository, downloadManager)

    val scope = rememberCoroutineScope()

    /**
     * 搜索（2026-10-26 改为**全局多源并发**）。
     *
     * 用户口径：「只有在用户点击了搜索键或者回车键后开始进行漫画源的搜索」——
     * 所以这个方法只由「点搜索键 / 回车」触发，输入文字本身不搜源
     * （书架命中是纯内存过滤，随输入实时更新，见 [shelfHits]）。
     *
     * 用 [app.gamanga.desktop.data.SearchAll]：各源独立协程，谁先回来先显示谁
     * （拷贝漫画 6~8 秒，等全部回来再渲染会让用户以为卡死）。
     */
    fun search() {
        if (keyword.isBlank()) return
        sourceSearchStarted = true
        sourceStates = emptyMap()
        // 重复搜索先取消上一批，否则两批结果会混进同一个 map
        searchJob?.cancel()
        searchJob = scope.launch {
            // 通知壳层：dock 的浏览按钮要播搜索动效（用户 2026-10-27 要求）
            app.gamanga.desktop.ui.search.SearchStatus.searching()
            try {
                app.gamanga.desktop.data.SearchAll.search(keyword).collect { (type, state) ->
                    sourceStates = sourceStates + (type to state)
                }
                // flow 正常收完 = 各源都回来了（或失败）→ 播一次「完成」动效
                app.gamanga.desktop.ui.search.SearchStatus.done()
            } finally {
                // 被新搜索取消时不播「完成」——但也不能永远停在 Searching
                if (!currentCoroutineContext().isActive) {
                    app.gamanga.desktop.ui.search.SearchStatus.idle()
                }
            }
        }
        // 关键词变了，原来的选中书已经不属于这批结果 → 收回详情面板
        detail.close()
    }

    LaunchedEffect(initialKeyword, autoSubmitInitialKeyword) {
        if (autoSubmitInitialKeyword && !initialKeyword.isNullOrBlank()) {
            keyword = initialKeyword
            search()
            onInitialConsumed()
        }
    }

    // 从书架输入框跳进来：**只填词，不自动搜源**。
    // 用户 2026-10-26 口径：「输入文字后立刻跳转到浏览页，浏览页立即搜索书架是否存在……
    // 只有在用户点击了搜索键或者回车键后开始进行漫画源的搜索」。
    // 所以这里刻意不调 search()——书架命中是内存过滤，随 keyword 自动更新。

    /**
     * 搜索词被**删空** → 退回浏览历史（F-128）。
     *
     * 用户口径：「搜索后搜索页常驻，直到用户删除全部搜索文字才回到浏览历史」。
     * 所以这里只在 `keyword` 变空时清场，**不**在每次改字时清——
     * 改了词但没重新提交时，上一次的源结果仍然留在屏幕上（常驻语义）。
     *
     * 同时把 dock 的搜索动效复位（不然被取消的那次搜索会让浏览按钮永远在「搜索中」）。
     */
    LaunchedEffect(keyword.isBlank()) {
        if (keyword.isBlank()) {
            searchJob?.cancel()
            sourceSearchStarted = false
            sourceStates = emptyMap()
            app.gamanga.desktop.ui.search.SearchStatus.idle()
        }
    }

    /**
     * 书架命中被点开（F-128：顺手记进浏览历史）。
     *
     * 用户口径：「浏览历史只记录在浏览页点过的漫画封面，包括在浏览页点过的书架漫画封面」——
     * 所以记录点就在**这里**，而不是在书架页的点击回调里。
     */
    fun openShelfHit(item: LibraryRepository.ShelfItem) {
        historyStore.record(
            BrowseHistoryEntry(
                source = item.entry.source,
                sourceMangaId = item.entry.sourceMangaId,
                title = item.entry.title,
                coverUrl = item.entry.coverUrl,
                author = item.entry.author,
            )
        )
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

    /** 源站搜索结果被点开（同样记进浏览历史）。 */
    fun openSourceHit(type: SourceType, manga: SourceManga) {
        historyStore.record(
            BrowseHistoryEntry(
                source = type,
                sourceMangaId = manga.sourceId,
                title = manga.title,
                coverUrl = manga.coverUrl,
                author = manga.author,
            )
        )
        detail.open(manga, type)
    }

    /**
     * 浏览历史里的封面被点开。
     *
     * 再点一次会把它**提到最前**（[BrowseHistoryStore.record] 的去重语义），
     * 这正是「最近浏览」该有的行为。
     */
    fun openHistoryEntry(entry: BrowseHistoryEntry) {
        historyStore.record(entry)
        detail.open(
            SourceManga(
                sourceId = entry.sourceMangaId,
                title = entry.title,
                coverUrl = entry.coverUrl,
                author = entry.author,
            ),
            entry.source,
        )
    }

    // 书架 / 历史页点进来：直接打开详细页（而不是像以前那样直接进阅读器）。
    //
    // 2026-10-27 第五轮修 bug（用户：「菜单栏点浏览按钮进入的是个漫画的二级菜单，
    // 书柜页和浏览页来回切换复现 1 次」）：
    // 根因是 `initialDetail` 由 `GamangaApp` 持有、**消费之后从不清空**。
    // 切页时 BrowseScreen 离开组合，再切回来是**新建实例**，`LaunchedEffect(initialDetail)`
    // 用同一个旧值又跑一遍 → 一进浏览页就自动弹出上次那本书的详情。
    // 现在打开完立刻 `onInitialConsumed()` 让上游置空，第二次进来就不会再弹。
    LaunchedEffect(initialDetail) {
        val (type, manga) = initialDetail ?: return@LaunchedEffect
        detail.open(manga, type)
        onInitialConsumed()
    }

    // 「点搜索框外面就失焦」：页面根记下自己在窗口里的位置，搜索栏记下自己的矩形，
    // 按下点落在栏外就 clearFocus（用户 2026-10-27：「点击其他任意地方无法关闭输入文本的状态」）
    val searchBounds = rememberSearchBarBounds()
    Column(
        Modifier
            .fillMaxSize()
            .dismissSearchFocusOnOutsidePress(searchBounds)
            // 顶部不再由根 padding 出一份：顶距统一交给搜索栏的 [SearchBarTopInset]
            // （2026-10-29 三页对齐，见 GlobalSearchPane 里那个常量的注释）
            //
            // ⚠️ **底部不要给根 padding**（R-176 / R-177，2026-10-30 用户）：
            // 「搜索页多选的两图标比书架页的两图标高度高了」「撤销窗口也要对齐菜单栏」。
            // 根上那 20dp 会把整块内容区抬高 → 挂在 `Alignment.BottomCenter` 上的
            // 多选条 / 回撤条都跟着高 20dp，而它们的位置是按 **窗口底部** 算的
            // （`DockMetrics.floatingBottom` 来自 dock 在壳里的实测矩形）。
            // 于是同一套组件在两页差了 20dp。底部留白现在只加在需要它的子节点上
            // （详情二级面板），浮层因此和书架页落在同一条线上。
            .padding(start = 20.dp, end = 20.dp)
    ) {
        // 2026-10-26 用户重定义：这里是**全局搜索**，不再是「选一个源再搜」。
        // 输入即显示书架命中（纯内存），点搜索键/回车才真正去各源搜（拷贝漫画要 8~10 秒）。
        // **和书架页一样：页面从窗口顶开始，顶栏自己让出拖拽带**
        // （用户 2026-10-27 截图对比：两边搜索框差约 11dp，根因是两套让位机制）
        SearchTopBar(
            query = keyword,
            onQueryChange = { keyword = it },
            onSearch = { search() },
            // 从书架跳过来要能直接接着打字（用户 2026-10-26：「跳转后搜索框就没激活」）
            autoFocus = true,
            // 有内容、还没提交 → 搜索栏最右端浮出回车小图标（F-128 第四版：
            // 用户把「气泡提示」整个否掉了，要的是框内的一个图标）
            showEnterAction = keyword.isNotBlank() && !sourceSearchStarted,
            bounds = searchBounds,
            // 三页共用一个顶距常量：浏览页 / 书架页 / 推荐页的搜索栏从此落在同一条水平线上
            modifier = Modifier.padding(top = app.gamanga.desktop.ui.search.SearchBarTopInset),
        )

        Spacer(Modifier.height(10.dp))
        // ---- 主区（一级） + 详情（二级，从底部中间上滑）----
        //
        // F-133（2026-10-27 用户口径）：「二级详情页由一级界面中间底部向上展开，并带有现代审美的
        // 过渡动效；展开时左右两侧仍可见一级菜单，二级菜单宽度占整体界面的二分之一至三分之二」。
        // 所以一级**宽度、布局、滚动位置一律不变**（覆盖层，不是分栏）——分栏会让一级页在
        // 300ms 内连续两次重排，封面列数跟着跳，看起来是「整页在抖」。
        // 点 dock 的「浏览」按钮 = 点一级菜单 → 收起二级页（用户要求）
        LaunchedEffect(navClickSeq) { if (navClickSeq > 0) detail.closeIfOpen() }

        // 面板宽度/高度由 DetailHost 自己按可用尺寸算（62%，夹在 1/2~2/3）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize()) {
                when {
                    // ① 搜索框空 → 浏览历史（首屏）
                    keyword.isBlank() -> BrowseHistoryPane(
                        items = browseHistory,
                        onOpen = { entry -> openHistoryEntry(entry) },
                        onDelete = { keys -> historyStore.removeAll(keys) },
                        onClearAll = { historyStore.clear() },
                        // F-138/F-139：删 / 清空都能回撤。回撤条由历史面板自己显示，
                        // 这里只负责把条目塞回 store（`restore` 按 visitedAt 重排）。
                        onRestore = { entries -> historyStore.restore(entries) },
                    )
                    // ② 有字但没提交 → 只有书架那一行（传空源列表即可，不另加开关参数）
                    !sourceSearchStarted -> GlobalSearchPane(
                        query = keyword,
                        shelfHits = shelfHits,
                        sourceTypes = emptyList(),
                        sourceStates = emptyMap(),
                        onOpenShelf = { item -> openShelfHit(item) },
                        onOpenSource = { _, _ -> },
                        onOpenBrowser = onOpenBrowser,
                    )
                    // ③ 提交过 → 书架 + 各源，常驻到文字被删空
                    else -> GlobalSearchPane(
                        query = keyword,
                        shelfHits = shelfHits,
                        // **实际有适配器的源**，不是 SourceType.entries ——
                        // 用户 2026-10-27：「我没有 komga 这个网站源可以去掉」。
                        // Komga 只是枚举里的死项（没有适配器），用 entries 会白列一个永远空的分区。
                        sourceTypes = remember { Sources.all().map { it.type } },
                        sourceStates = sourceStates,
                        onOpenShelf = { item -> openShelfHit(item) },
                        onOpenSource = { type, manga -> openSourceHit(type, manga) },
                        onOpenBrowser = onOpenBrowser,
                    )
                }
            }

            // 详情二级页：宿主自带遮罩 + 底部上滑面板 + 换源弹窗。
            // 浏览 / 书架 / 阅览三个页面共用同一份实现（F-143）。
            // 底距 20dp 原来加在根 Column 上，会把整页的浮层一起抬高（R-176）——
            // 现在只给详情面板：面板仍然离窗口底边 20dp，与改动前的观感一致。
            DetailHost(
                state = detail,
                repository = repository,
                downloadManager = downloadManager,
                modifier = Modifier.padding(bottom = 20.dp),
                onRead = onRead,
                onOpenBrowser = onOpenBrowser,
            )
        }
    }
}

@Suppress("unused") // F-128：详情改为「主区退半栏」后不再需要窄结果行，保留作回滚参照
@Composable
private fun ResultRow(manga: SourceManga, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteImage(
            url = manga.coverUrl,
            contentDescription = manga.title,
            modifier = Modifier.width(84.dp).height(112.dp).background(Tokens.Color.ImagePlaceholder, RoundedCornerShape(6.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(manga.title, fontSize = 15.sp, maxLines = 2)
            manga.author?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * 章节在章节列表里的显示下标（R-08 定位 / 高亮共用）。
 *
 * 倒序显示时列表是升序列表的镜像：第 1 话在最后一行。直接用阅读进度的下标
 * 会定位到对称的另一头。进度不存在（[index] < 0）返回 -1，调用方跳过定位。
 */
internal fun chapterDisplayIndex(index: Int, chapterCount: Int, desc: Boolean): Int {
    if (index < 0 || chapterCount <= 0) return -1
    val clamped = index.coerceAtMost(chapterCount - 1)
    return if (desc) chapterCount - 1 - clamped else clamped
}
