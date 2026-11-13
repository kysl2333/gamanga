package app.gamanga.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.ui.library.BookshelfScreen
import app.gamanga.desktop.ui.reader.ReaderWindows
import app.gamanga.desktop.ui.recommend.RecommendScreen
import app.gamanga.desktop.ui.shell.AppShell
import app.gamanga.desktop.ui.shell.NavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GamangaApp(repository: LibraryRepository, onClose: () -> Unit) {
    var selected by remember { mutableStateOf(NavItem.LIBRARY) }
    var browserTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 主窗口句柄：阅读器窗口要「用浏览器查看」时把主窗口提到前面。 */
    val mainWindow = app.gamanga.desktop.ui.shell.LocalComposeWindow.current
    /**
     * 打开阅读器（2026-10-30 用户要求 4）：**独立窗口，可多开**。
     *
     * 原来这里是 `readerTarget` 状态 + 一个覆盖整个主窗口的 `ReaderScreen`——同一时刻
     * 只能看一本，换一本就把上一本挤掉。现在改成往 [ReaderWindows.open] 里加一条，
     * `main()` 的 `application` 作用域会给它开一个真正的窗口。
     *
     * 「用浏览器查看」要盖在**主窗口**上（阅读器窗口里没有浏览器层），所以这里把
     * 主窗口的 `browserTarget` 闭包传下去。
     */
    val openReader: (ReaderTarget) -> Unit = { target ->
        ReaderWindows.launch(target) { url, domain ->
            browserTarget = url to domain
            // 浏览器是开在**主窗口**里的；阅读器现在是独立窗口，很可能盖在它上面，
            // 不把主窗口提到前面，用户会以为「用浏览器查看」点了没反应。
            runCatching { mainWindow?.toFront() }
        }
    }
    /** 书架点进来的漫画：切到「浏览」并直接打开它的详细页。 */
    var browsePrefill by remember { mutableStateOf<Pair<app.gamanga.desktop.model.SourceType, SourceManga>?>(null) }
    /** 书架顶栏搜索键带过来的词：切到「浏览」并自动搜一次（2026-10-26）。 */
    var browseKeyword by remember { mutableStateOf<String?>(null) }
    var browseAutoSearch by remember { mutableStateOf(false) }
    /**
     * F-129：dock 里重复点「浏览」的次数。
     *
     * 详情是浏览页的二级页，用户要求「鼠标点击到一级菜单就把二级菜单收回去」——
     * 但那时 BROWSE 已经选中，`selected = it` 不产生任何状态变化，页面收不到信号。
     * 所以这里把「重复点同一个一级菜单项」单独计一次，页面据此关闭二级页。
     */
    var browseNavClick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val recommendState = remember { app.gamanga.desktop.ui.recommend.RecommendState(scope) }
    LaunchedEffect(recommendState) { recommendState.initialize() }
    val snackbar = remember { SnackbarHostState() }

    // **启动静默自检**（F-20）：后台并发探一次各源，红灯写日志、设置页亮灯。
    // 放在 IO 上跑，绝不阻塞首屏；失败了也无所谓（自检本身不该影响使用）。
    LaunchedEffect(Unit) {
        // 用**应用级作用域**跑：composition 被取消也不能掐断自检（否则会误报红灯）
        app.gamanga.desktop.data.SourceHealth.checkAllInBackground()
    }

    /**
     * 书架章节刷新（F-271，2026-10-29 用户要求）。
     *
     * 先把「看过的话数」读进内存（绿点要立刻显示，不能等网络），
     * 再按设置里的档位决定要不要在启动时自动刷一轮：
     * 手动 = 不刷（用户点书架标题旁的刷新图标）；每次开启 = 刷；
     * 每天 / 每七天 = 距上次成功刷新到期才刷；所有自动档均受10分钟尝试间隔保护。
     */
    LaunchedEffect(Unit) {
        app.gamanga.desktop.data.ChapterRefresh.initialize(repository)
        if (app.gamanga.desktop.data.ChapterRefresh.dueOnLaunch(repository)) {
            runCatching { app.gamanga.desktop.data.ChapterRefresh.run(repository, "启动自动刷新") }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AppShell(
            selected = selected,
            onSelect = { item ->
                // 重复点「浏览」= 点一级菜单 → 让浏览页收起二级页（详情）
                if (item == NavItem.BROWSE && selected == NavItem.BROWSE) browseNavClick++
                selected = item
            },
            // 无边框窗口没有系统关闭按钮：右上角那颗 X 走这条路（F-60）
            onClose = onClose,
        ) {
            // 除书架外，其余页面统一在顶部让出拖拽带：
            // 它们都是 Column 结构（顶栏 + 列表），0~36dp 会被壳层拖拽带盖住，
            // 搜索框/标题按钮点不到。书架是沉浸式 Box（内容铺满），自己处理了。
            // 书架和浏览页都是**沉浸式**（页面铺到窗口顶，顶栏自己加 padding 让出拖拽带）；
            // 其余页面是普通列表，由这里统一让位。
            //
            // 2026-10-27 用户截图对比发现两边搜索框差了约 11dp：根因就是**两套让位机制**——
            // 书架在 `TopBarOverlay` 内部 padding、浏览页在 GamangaApp 外层 padding，
            // 一旦某一层调整（比如取消白带那次），两者就错开。现在两边走同一套。
            // 2026-10-29：推荐页也加入沉浸式——它的搜索栏改由自己让位（SearchBarTopInset），
            // 外层再让一次拖拽带就叠成两份，和浏览页 / 书架页永远差 20dp。
            val immersive = selected == NavItem.LIBRARY || selected == NavItem.BROWSE ||
                selected == NavItem.RECOMMEND
            val pageTop = if (immersive) 0.dp else app.gamanga.desktop.ui.shell.DragStripHeight
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().padding(top = pageTop)
            ) {
            when (selected) {
                    // 推荐页（2026-10-26）：Bangumi 书籍排行，点一本 → 带着书名去全局搜索
                    NavItem.RECOMMEND -> RecommendScreen(
                        state = recommendState,
                        onPick = { title ->
                            browsePrefill = null
                            browseAutoSearch = true
                            browseKeyword = title
                            selected = NavItem.BROWSE
                        }
                    )
                    // 2026-10-26：换成新书架页（原型 bookshelf-3x2 落地）。
                    // 旧 LibraryScreen 保留不删——简洁模式还要用它那套卡片，
                    // 且它的右键菜单/置顶逻辑是回滚参照。
                    NavItem.LIBRARY -> BookshelfScreen(
                        repository = repository,
                        // F-143（2026-10-27 用户口径）：「在书架页点击漫画封面时，
                        // 在**该书架页内**展示二级详情页」——不再把用户拽去浏览页。
                        // 面板里的「阅读」直接进阅读器。
                        onRead = openReader,
                        onOpenBrowser = { url, domain -> browserTarget = url to domain },
                        // 「继续阅读 → 查看全部」进历史页
                        onSeeAllHistory = { selected = NavItem.HISTORY },
                        // 空书架的「去推荐页看看」（用户 2026-10-28）
                        onOpenRecommend = { selected = NavItem.RECOMMEND },
                        // 顶栏搜索键：把词带去浏览页做全网搜索（书架内过滤是输入框实时做的）
                        onSearch = { q ->
                            browseKeyword = q.ifBlank { null }
                            // **必须清掉**：不清的话上一次从书架点开的那本书还在，
                            // 浏览页一进去就直接打开它的详情页（用户 2026-10-26 实测
                            // 「输入第一个字马上跳转到浏览页内的详情页」就是这个）。
                            browsePrefill = null
                            selected = NavItem.BROWSE
                        },
                        // 右键「开始阅读」→ 直接进阅读器（2026-10-26 用户要求）。
                        // 章节**从库里取**（本地优先）：书架里的书入架时就落库了，
                        // 走网络要等 8~10 秒（拷贝漫画），用户会以为点了没反应。
                        onResume = { item ->
                            val localId = item.entry.id
                            val chapters = repository.chaptersOf(localId).map { c ->
                                SourceChapter(
                                    id = LibraryRepository.sourceChapterIdOf(c.id),
                                    name = c.name,
                                    index = c.index,
                                    pageCount = c.pageCount,
                                )
                            }
                            if (chapters.isEmpty()) {
                                // 库里还没章节（刚入架、详情没拉过）→ 去详情页，那里会拉
                                browsePrefill = item.entry.source to SourceManga(
                                    sourceId = item.entry.sourceMangaId,
                                    title = item.entry.title,
                                )
                                selected = NavItem.BROWSE
                            } else {
                                val progress = repository.progressOf(localId)
                                val idx = progress?.let { pr ->
                                    chapters.indexOfFirst { "$localId#${it.id}" == pr.chapterId }
                                } ?: -1
                                openReader(
                                    ReaderTarget(
                                        type = item.entry.source,
                                        sourceMangaId = item.entry.sourceMangaId,
                                        title = item.entry.title,
                                        chapters = chapters,
                                        startIndex = if (idx >= 0) idx else 0,
                                        localMangaId = localId,
                                        coverUrl = item.entry.coverUrl,
                                    )
                                )
                            }
                        }
                    )
                    NavItem.BROWSE -> BrowseScreen(
                        repository = repository,
                        onOpenBrowser = { url, domain -> browserTarget = url to domain },
                        onRead = openReader,
                        initialDetail = browsePrefill,
                        initialKeyword = browseKeyword,
                        autoSubmitInitialKeyword = browseAutoSearch,
                        navClickSeq = browseNavClick,
                        // 消费后必须置空：否则切页回来（BrowseScreen 新建实例）会再弹一次详情。
                        // 2026-10-27 第五轮用户实测 bug：「菜单栏点浏览按钮进入的是个漫画的二级菜单」。
                        onInitialConsumed = {
                            browsePrefill = null
                            browseKeyword = null
                            browseAutoSearch = false
                        },
                    )
                    // R-04：下载管理页（任务进度 / 重试 / 删除 / 直接阅读已缓存章节）
                    // 2026-10-27 第六批：点书名在本页弹出二级详情
                    NavItem.DOWNLOADS -> DownloadsScreen(
                        repository = repository,
                        onRead = openReader,
                        onOpenBrowser = { url, domain -> browserTarget = url to domain },
                    )
                    // F-03：真设置页（WebDAV 备份 / 本地备份 / mihon 导入）
                    NavItem.SETTINGS -> SettingsScreen(
                        repository = repository,
                        backupManager = AppGraph.backupManager
                    )
                    // 排期第 4 项：历史页（续读入口 + 删除单条 + 页内二级详情）
                    // 2026-10-27 第六批：详情**在本页弹出**，不再跳浏览页
                    NavItem.HISTORY -> HistoryScreen(
                        repository = repository,
                        onRead = openReader,
                        onOpenBrowser = { url, domain -> browserTarget = url to domain },
                        downloadManager = AppGraph.downloadManager,
                    )
            }
            }
        }
        // 阅读器**不再是覆盖层**（2026-10-30 用户要求 4）：它现在是独立窗口，
        // 由 `main()` 里的 `ReaderWindowLayer` 渲染，主界面可以同时开多个。
        // 内嵌浏览器覆盖层仍然留在主窗口里，阅读器里的「用浏览器查看」通过
        // `openReader` 传下去的回调把地址送回这里。
        browserTarget?.let { (url, domain) ->
            // 把上次 ✕ 关闭时导出的登录态解析回 map，建浏览器前灌进 CEF——
            // 否则浏览器自己永远是空会话，用户每次都要重新登录（cache_path 之外的
            // 第二道保险，也覆盖"缓存被清但 prefs 还在"的情况）。
            val savedCookies = remember(domain) {
                repository.getPref(LibraryRepository.cookiePrefKey(domain))
                    ?.split(';')
                    ?.mapNotNull { part ->
                        val eq = part.indexOf('=')
                        // 值里可能还有 '='（base64 票据），只按第一个 '=' 切
                        if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
                    }
                    ?.toMap()
                    ?: emptyMap()
            }
            EmbeddedBrowserOverlay(
                url = url,
                domain = domain,
                onClose = { browserTarget = null },
                initialCookies = savedCookies,
                onCookiesExported = { cookies ->
                    // 持久化供 M3/M4 原生适配器借会话（name=value; name=value）
                    repository.setPref(
                        LibraryRepository.cookiePrefKey(domain),
                        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    )
                }
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
