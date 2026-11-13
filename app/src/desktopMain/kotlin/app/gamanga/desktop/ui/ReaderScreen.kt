package app.gamanga.desktop.ui

import app.gamanga.desktop.ui.reader.ReaderPrefetch
import app.gamanga.desktop.ui.reader.loadChapterPages
import app.gamanga.desktop.ui.reader.PageSpread
import app.gamanga.desktop.ui.detail.DetailHost
import app.gamanga.desktop.ui.detail.rememberDetailHostState
import app.gamanga.desktop.ui.reader.ReaderBar
import app.gamanga.desktop.ui.reader.ReaderBottomGroup
import app.gamanga.desktop.ui.reader.ReaderBottomInset
import app.gamanga.desktop.ui.reader.ReaderChromeAnimMs
import app.gamanga.desktop.ui.reader.ReaderChromeEasing
import app.gamanga.desktop.ui.reader.ReaderChromeExitMs
import app.gamanga.desktop.ui.reader.ReaderCornerInset
import app.gamanga.desktop.ui.reader.ReaderFullscreen
import app.gamanga.desktop.ui.reader.ReaderFullscreenButton
import app.gamanga.desktop.ui.reader.ReaderSettingsGap
import app.gamanga.desktop.ui.reader.ReaderSettingsPanel
import app.gamanga.desktop.ui.reader.ReaderChapterPanel
import app.gamanga.desktop.ui.reader.ReaderViewportMotion
import app.gamanga.desktop.ui.reader.ReaderPanelMotion
import app.gamanga.desktop.ui.reader.zoomBy
import app.gamanga.desktop.ui.shell.LocalComposeWindow
import app.gamanga.desktop.ui.theme.Tokens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.model.ChapterEdge
import app.gamanga.desktop.model.EDGE_JUMP_ACCUM_PX
import app.gamanga.desktop.model.ReaderDirection
import app.gamanga.desktop.model.ReaderMode
import app.gamanga.desktop.model.ReaderSettings
import app.gamanga.desktop.model.WHEEL_SCROLL_PX_PER_LINE
import app.gamanga.desktop.model.inCenterTapBand
import app.gamanga.desktop.model.chapterEdge
import app.gamanga.desktop.model.clampPan
import app.gamanga.desktop.model.edgeOverscroll
import app.gamanga.desktop.model.fitHeightColumnWidth
import app.gamanga.desktop.model.pageChapterEdge
import app.gamanga.desktop.model.panLimit
import app.gamanga.desktop.model.prefetchWindow
import app.gamanga.desktop.model.prefetchWindowAfter
import app.gamanga.desktop.model.wheelPageForward
import app.gamanga.desktop.model.wheelPageTurnTarget
import app.gamanga.desktop.model.wheelZoomStep
import app.gamanga.desktop.model.spreadCount
import app.gamanga.desktop.model.lastSpreadStart
import app.gamanga.desktop.model.FLING_FRICTION_PER_SECOND
import app.gamanga.desktop.model.FLING_MIN_VELOCITY
import app.gamanga.desktop.model.FLING_STOP_VELOCITY
import app.gamanga.desktop.model.flingDecayFactor
import app.gamanga.desktop.model.ReadingProgress
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.source.requestHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 阅读器入口参数：一个源站漫画 + 起始话。 */
data class ReaderTarget(
    val type: SourceType,
    val sourceMangaId: String,
    val title: String,
    val chapters: List<SourceChapter>,
    val startIndex: Int,
    /** 已入书架时的本地 id；用于记录进度/已读。null = 只在书架外试读。 */
    val localMangaId: String? = null,
    /**
     * 封面地址（R-123）。书架外的书也要进历史，而历史条目要显示封面——
     * 没有这一项就只能显示纯色占位块。可空：调用方拿不到封面时不影响功能。
     */
    val coverUrl: String? = null,
)

/**
 * 阅读器（M5）。
 *
 * 竖排连续滚动（条漫/日漫都适用），页码 = 首个可见页；键盘翻页：
 * ← / PageUp / ↑ 上一页，→ / PageDown / ↓ / 空格 下一页，
 * `[` `]` 切上一话/下一话，Esc 退出。
 *
 * 滚轮（R-29 翻页/下滑 · R-31 缩放手势，用户 2026-10-22 实测指定）：
 * 竖向连续 = 持续下滑；单页/双页 = 翻页（与 PageDown 同语义，RTL 反转）；
 * Ctrl+滚轮 / 鼠标侧滚轮 = 缩放（上滚/右滚放大）。
 *
 * 默认按「适应高度」显示（R-30）：竖向列宽 = 视口高 × 宽高比，页模式格子取真实
 * 适应高度尺寸——都不再默认拉满整屏。
 *
 * 页模式放大（zoom>100%）后左键拖着看（用户 2026-10-23 要求）：平移量按放大余量
 * 夹紧（panLimit/clampPan），放大图永远铺满视口；没放大时横向滑动 = 翻页。
 * 竖向模式放大超过视口宽时同样可横向拖动（hScroll），并给"可拖动"光标提示。
 * 双页的缩放作用于**整行**（绕行中心）——旧实现每页各自绕自身中心缩放，
 * 缩小时中间裂缝、放大时两页互相重叠（用户 2026-10-23 实测）。
 *
 * 章节边界跳话带「蓄力」：撞到边界后同向滚动要累计约两格滚轮
 * （EDGE_JUMP_ACCUM_PX）才跳，单格误触只累积不跳（用户实测"滚轮很容易
 * 触发上一话/下一话而不是翻页"）；轻点和键盘是明确的单次动作，保持即时。
 *
 * 页面预加载：当前页往后 PREFETCH_AHEAD 页提前烘进 ImageCache，翻页即开。
 *
 * 进度落库：滚动停稳后写 readingProgress，并标记该话已读。
 */
@Composable
fun ReaderScreen(
    target: ReaderTarget,
    repository: LibraryRepository,
    onClose: () -> Unit,
    onOpenBrowser: () -> Unit,
    /** 打开该源的登录页（null = 这个源没有/不建议登录）。 */
    onOpenLogin: (() -> Unit)? = null,
    /** R-04 下载管理器：已落盘章节读本地，不调源站（省配额/不拉浏览器）。 */
    downloadManager: DownloadManager = AppGraph.downloadManager
) {
    val adapter = remember(target.type) { Sources.of(target.type) }
    /**
     * 详情二级页宿主（F-143）：阅读器内也能弹详情。
     *
     * 阅读器手上只有 [ReaderTarget]，没有完整的书架条目，所以从 target 还原一个
     * [SourceManga]——详情面板需要的字段（标题 / 封面 / 源站 id）它都有。
     */
    val detail = rememberDetailHostState(repository, downloadManager)
    var index by remember { mutableIntStateOf(target.startIndex.coerceIn(0, target.chapters.lastIndex)) }
    // 继续阅读恢复到上次页码（第十二轮 #3；2026-10-24 修正「任何一话都从第 20 页开始」）。
    //
    // **必须校验 chapterId**：`progressOf(mangaId)` 返回的是「这本书最后一次读的那一话 +
    // 那一话的第几页」。旧实现只取 `.page`，于是从详情页点开任何一话都会跳到「上次那话的
    // 第 20 页」——用户实测「我从 20 页退出，现在进入任何一话都是第 20 页开始」。
    // 只有「进度记录的那一话 == 这次要打开的初始话」时才恢复页码，否则从第 0 页起。
    val initialIndex = remember { target.startIndex.coerceIn(0, target.chapters.lastIndex) }
    val savedPage = remember(target.localMangaId, initialIndex) {
        val localId = target.localMangaId ?: return@remember 0
        val progress = repository.progressOf(localId) ?: return@remember 0
        val initialChapter = target.chapters.getOrNull(initialIndex) ?: return@remember 0
        if (progress.chapterId == "$localId#${initialChapter.id}") progress.page else 0
    }
    var pages by remember { mutableStateOf<List<PageImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableIntStateOf(if (index == initialIndex) savedPage else 0) }

    // ---- 滑动诊断日志（2026-10-25）----
    // **默认关闭**：R-98 排查期间它是把回弹钉死的关键证据，但平时刷日志只会撑大文件。
    // 需要排查时用 `-Dgamanga.scrolllog=1` 启动，tag = Scroll。
    val sSeq = remember { intArrayOf(0) }
    fun sLog(msg: String) {
        if (SCROLL_LOG) AppLog.i("Scroll", "#${++sSeq[0]} $msg")
    }
    /** 换话或点「重试」时自增，驱动下面取图的 LaunchedEffect 重跑。 */
    var reloadKey by remember { mutableIntStateOf(0) }

    // **列表状态必须在整个阅读器生命周期里唯一**（R-122，2026-10-26 用户实测
    // 「条漫滚动翻页失效、换话后一滚就提示下一话，切模式才好」）。
    //
    // 历史：2026-10-25 为了修「切上一话不回第一页」，这里写成了 `remember(index)`，
    // 换话就 new 一个 LazyListState。**代价是灾难性的**——滚轮消费者是
    // `LaunchedEffect(Unit)`、拖拽手势是 `pointerInput(Unit)`，两者都只在首次组合时
    // 捕获 `listState`。换话后它们继续滚那个**已经脱离 UI 的旧 state**：
    // `scrollBy` 消费量恒为 0 → `chapterEdge` 判成「到边界」→ 蓄力 600px →
    // 又跳下一话并弹提示。表现就是「条漫滚不动、滑动也没反应、一滚就提示下一话」；
    // 切模式会让 `when(mode)` 分支整体重建、pointerInput 重新捕获，所以「切一下就好」。
    //
    // 现在改成唯一实例，换话回第一页由 loadChapterPages 里显式 `scrollToItem` 保证
    // （那里本来就有，R-115 的另一个半边）——**不要再把 index 加回 remember 的 key**。
    val listState = remember { LazyListState() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val chapter = target.chapters.getOrNull(index)

    // 阅读设置（阅读方式 / 缩放 / 方向）落 prefs：换话、退出再进都保持，
    // 且是全局设置而不是按书——用户改的是"我的阅读习惯"，不是某一本的属性。
    var settings by remember {
        mutableStateOf(
            ReaderSettings(
                mode = ReaderMode.of(repository.getPref(ReaderSettings.KEY_MODE)),
                zoom = repository.getPref(ReaderSettings.KEY_ZOOM)?.toFloatOrNull()
                    ?.coerceIn(ReaderSettings.ZOOM_MIN, ReaderSettings.ZOOM_MAX) ?: 1f,
                direction = ReaderDirection.of(repository.getPref(ReaderSettings.KEY_DIRECTION))
            )
        )
    }
    /**
     * 缩放 prefs 的**延迟写**任务（2026-10-30：用户报「放大缩小卡顿」）。
     *
     * 旧实现每改一格缩放就同步派发 3 条 `setPrefAsync`（模式 / 缩放 / 方向各一条）。
     * 鼠标滚轮连滚、触控板一次滑动会连发十几格 → 十几×3 条 SQLite 写，全部挤在
     * 同一条写线程上，还要和「翻页落进度」抢：写队列一堵，下一格的缩放状态就晚到，
     * 体感是"滚了没反应、过一下突然跳两档"。
     *
     * 现在：模式/方向这类**低频**改动仍然立刻写；缩放走 400ms 防抖，只落最后一次。
     * 退出阅读器时（[DisposableEffect]）无条件补写一次，绝不丢用户最后一次缩放。
     */
    var zoomPrefJob by remember { mutableStateOf<Job?>(null) }
    fun updateSettings(next: ReaderSettings) {
        val prev = settings
        settings = next
        if (next.mode != prev.mode) {
            repository.setPrefAsync(ReaderSettings.KEY_MODE, next.mode.name)
        }
        if (next.direction != prev.direction) {
            repository.setPrefAsync(ReaderSettings.KEY_DIRECTION, next.direction.name)
        }
        if (next.zoom != prev.zoom) {
            zoomPrefJob?.cancel()
            zoomPrefJob = scope.launch {
                delay(ZOOM_PREF_DEBOUNCE_MS)
                repository.setPrefAsync(ReaderSettings.KEY_ZOOM, next.zoom.toString())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 防抖窗口里退出：把最后一次缩放补写掉（同步写一条 prefs，几微秒）
            zoomPrefJob?.cancel()
            runCatching { repository.setPref(ReaderSettings.KEY_ZOOM, settings.zoom.toString()) }
        }
    }

    /**
     * 缩放增量的合并通道（2026-10-30）。
     *
     * 触控板一次双指滑动会连发几十个 scroll 事件，每格都改一次 `settings` 就是几十次
     * 重组 + 几十次布局。这里把增量汇进通道，消费端**先把排队的增量取干再改一次状态**：
     * 一帧最多一次缩放，且幅度是这一帧内所有增量的和——手感更线性，也不会丢增量。
     */
    val zoomDeltas = remember { Channel<Float>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        for (first in zoomDeltas) {
            var acc = first
            while (true) {
                val r = zoomDeltas.tryReceive()
                if (r.isSuccess) acc += r.getOrNull() ?: 0f else break
            }
            if (acc != 0f) updateSettings(zoomBy(settings, acc))
        }
    }


    // ---- 章节边界跳转（用户 2026-10-22 要求：第一页再往上 = 上一话的第一页；
    // 最后一页再往下 = 下一话；最后一话读完给结束面板而不是静默无反应）----
    /** 跳话冷却：新话要异步取图，冷却期内继续滚不连环跳。 */
    var lastChapterJumpAt by remember { mutableStateOf(0L) }
    /** 跳话提示（"下一话 · 第N话"），[CHAPTER_TOAST_MS] 后自动消失。 */
    var chapterToast by remember { mutableStateOf<String?>(null) }
    /** 读完最后一话的结束面板（重读本话 / 返回书架）。 */
    var showEndPanel by remember { mutableStateOf(false) }
    /**
     * 章节边界上的「蓄力」累积量（px）：撞到边界后同向继续滚才跳话。
     * 用户实测「滚轮很容易误触发上一话/下一话」——单格滚轮打到边界只累积不跳，
     * 连续滚满 [EDGE_JUMP_ACCUM_PX] 才跳；滚回内容区由 edgeOverscroll 归零。
     */
    var edgeAccum by remember { mutableFloatStateOf(0f) }
    /**
     * 沉浸模式开关（R-43）：true = 显示顶栏 + 控件栏（默认）；
     * 轻点画面中心带切成 false，两条栏滑退、内容区补齐全屏。
     * 只影响 chrome，不影响任何输入——滚轮/键盘/轻点翻页在沉浸下全部可用。
     */
    var chromeVisible by remember { mutableStateOf(true) }

    /**
     * 阅读设置二级面板（2026-10-30 用户要求 6）：条漫 / 单页 / 双页 + 左→右 / 右→左
     * 全部收进底部悬浮组的一颗按钮，点开**从底部向上弹**一块小面板。
     *
     * 原来这些是底部常驻控件栏里的一排文字按钮（`ReaderControls`），一直占着 34dp 高度；
     * 用户要的是「整合到一个入口 + 悬浮」，所以控件栏整体撤掉，只在需要时弹出。
     */
    var settingsOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    val reducedMotion = remember { java.util.prefs.Preferences.userRoot().node("Gamanga/recommend").getBoolean("reduceMotion", false) }
    val chromeAnimMs = if (reducedMotion) 0 else ReaderChromeAnimMs
    val chromeExitMs = if (reducedMotion) 0 else ReaderChromeExitMs

    /**
     * 全屏 / 还原窗口（2026-10-30 用户要求 5；第三轮**去动画**）。
     *
     * 用户纠正：「之前说的**缩放**是指**全屏和窗口**的意思」——所以"卡顿/闪退"真正出在
     * 这颗全屏键上。旧实现用 `GraphicsDevice.fullScreenWindow`（独占全屏）：切换显示模式
     * + 重建窗口与 GL 上下文，Skia 交换链跟着重建，机器上留下的 NVIDIA「GPU 已断开」与
     * `awt.dll` 访问违例就是这么来的；退出时窗口又跳回 AWT 记忆的旧尺寸，所以"很不自然"。
     *
     * 第二轮改成 190ms 逐帧插值 `bounds`，**还是卡**——12 次窗口矩形变化 = 12 次交换链 resize
     * + 整窗重新布局 + 漫画页按新视口重新 Fit。用户 2026-10-30 第三轮明确：
     * 「把窗口和全屏的动画移除，让它能一下就变成全屏」。所以现在 [ReaderFullscreen.toggle]
     * 是**同步、一次赋值**，这里不再需要 `scope.launch`。
     */
    val composeWindow = LocalComposeWindow.current
    var fullscreen by remember(composeWindow) {
        mutableStateOf(ReaderFullscreen.isFullscreen(composeWindow))
    }
    // 沉浸模式一收起，设置面板跟着收起——否则下次点中心区出来时它还开着，
    // 用户会以为「上次那一下没关掉」。
    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) { settingsOpen = false; chaptersOpen = false }
    }
    fun toggleFullscreen() {
        val w = composeWindow ?: return
        // toggle 返回实际生效的状态：拿不到屏幕信息时它保持原状，图标不能停在"退出全屏"上。
        fullscreen = ReaderFullscreen.toggle(w, !fullscreen)
    }

    /**
     * 顶栏小封面用的地址（2026-10-30 用户：「阅读器左上角的小漫画封面没有出来，这个封面本地缓存」）。
     *
     * 两个原因叠加：①详情页「开始阅读」那条路径构造 [ReaderTarget] 时**没带 coverUrl**；
     * ②就算带了远端地址，源站有防盗链/要 Referer，顶栏这张 28×38 的小图经常直接 403。
     *
     * 所以这里走**本地封面缓存优先**：`CoverCache.localUri()` 命中就直接用 `file:` 地址
     * （书架早就把封面落过盘了，这里不再联网）；没命中才退回远端地址。
     */
    val barCoverUrl = remember(target.coverUrl, target.localMangaId) {
        val remote = target.coverUrl
            ?: target.localMangaId?.let { repository.coverUrlOf(it) }
        CoverCache.localUri(remote) ?: remote
    }

    /**
     * 条漫模式松手后的惯性滚动任务（2026-10-23「像手机一样滑动」）。
     * 新手势开始 / 退出阅读器时取消；null = 没在滑。
     */
    var flingJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { flingJob?.cancel() }
    }

    /**
     * 条漫滚轮增量通道（第九轮 #4）。
     *
     * 滚轮增量汇入这里、由常驻协程顺序 `scrollBy`——与拖拽同一套
     * Channel 串行模式。旧实现每格滚轮 `scope.launch` 一个新协程，
     * 连续滚轮时多协程并发抢 `LazyListState` 的 MutatorMutex **互相
     * 取消**：增量被吞，用户实测「往上滚回弹到滚轮前的位置，等于没
     * 滚动」（往下滚格数少/慢时不易触发，所以「往下没问题」）。
     * 消费协程声明在 [jumpChapter] 之后（局部函数要先声明后使用）。
     */
    val wheelDeltas = remember { Channel<Float>(Channel.UNLIMITED) }

    LaunchedEffect(chapterToast) {
        if (chapterToast != null) {
            delay(CHAPTER_TOAST_MS)
            chapterToast = null
        }
    }

    /**
     * 页模式（单页/双页）的当前起始页。
     * 竖向连续模式不用它——那里的"页码"等于首个可见项，由 listState 决定。
     */
    var pageIndex by remember { mutableIntStateOf(0) }

    // R-98：条漫「上拖一小段又回弹、页面原地踏步」= 占位比例猜错。
    // 源站不下发宽高时按 0.7 占位，扫描漫画（一页其实是跨页扫描）真实比例常在 1.3~1.5，
    // 图片加载完 item 高度骤减 → LazyColumn 锚点被拉回 → 表现为往上滑不动。
    // 对策：**学习本章真实比例**（取已加载页的中位数），存进 appPrefs，下次进同一话直接真值占位。
    // R-98 九轮：**每页比例都不一样**（日志实测同一话 0.36 / 0.48 / 2.24），
    // 「学一个统一比例」从根上就错了。改成**滚动锚点补偿**：记下每项实测高度，
    // 当**可视区上方**的项高度变化时，把滚动位置补回去，视觉上纹丝不动。
    val itemHeights = remember(index) { HashMap<Int, Int>() }
    val ratioPrefKey = chapter?.id?.let { "reader.ratio.$it" }
    var learnedRatio by remember(index) {
        mutableFloatStateOf(ratioPrefKey?.let { repository.getPref(it) }?.toFloatOrNull() ?: 0f)
    }

    // 换话时页模式的页码归零，收起结束面板，边界蓄力作废（新话从零开始攒）。
    // 例外：首次进入的初始话恢复到 savedPage（继续阅读，第十二轮 #3）。
    LaunchedEffect(index) {
        pageIndex = if (index == initialIndex) savedPage else 0
        showEndPanel = false
        edgeAccum = 0f
    }

    // 边界蓄力提示的衰减：停止滚动 2.5s 后蓄力归零、提示消失（防提示常驻屏幕）。
    // 每再滚一格 edgeAccum 变化 → 定时器重启，持续滚就持续显示。
    LaunchedEffect(edgeAccum) {
        if (edgeAccum != 0f) {
            delay(EDGE_HINT_MS)
            if (edgeAccum != 0f) edgeAccum = 0f
        }
    }

    // 换话 / 重试：取图 + 回到顶部
    LaunchedEffect(index, reloadKey) {
        loading = true
        error = null
        pages = emptyList()
        // R-122：`listState` 现在是全局唯一实例，换话不会自动归零——必须显式归零，
        // 否则新一章会停在上话的 firstVisibleItemIndex 上（= R-115 复活）。
        //
        // **R-125（2026-10-26 用户实测「下载页点阅读只有进度条，切一下条漫才出图」）**：
        // 这里**绝对不能**用 suspend 的 `scrollToItem`。`LazyListState.scroll` 内部会
        // `awaitScrollDependencies()`——**等列表 attach 才继续**。而单页/双页模式下
        // 根本没有 LazyColumn（只有 `mode == VERTICAL` 才建），于是这个 await
        // **永久挂起**：取图协程一步都没跑、`loading` 永远是 true，用户看到的就是
        // 「一直转进度条」；切到条漫（LazyColumn 出现）才解除挂起、图才出来。
        // `requestScrollToItem` 是**非挂起**的：只把目标位置写进 scroll position，
        // 列表下一次测量时生效，与是否 attach 无关。
        listState.requestScrollToItem(0)
        // R-45 复发修复：换话/重试会取消上一次取图协程，取消不是失败——
        // 吞掉 CancellationException 会让"僵尸协程"把 error 盖回已经出图的新章节，
        // 表现为「切到有缓存的章节也取图失败，点重试就好」。详见 loadChapterPages。
        loadChapterPages(
            load = {
                // R-04：已完整落盘的章节直接读本地文件——不调 adapter.pages()，
                // Komiic 不再烧每日配额、G社 不再拉隐藏浏览器跑解密器。
                val localId = target.localMangaId
                val chId = chapter?.id
                val localKey = if (localId != null && chId != null) "$localId#$chId" else null
                // **本地文件优先，不看 isCompleted**（2026-10-25）：DB 说"已完成"但文件被
                // 外部删掉、或状态没来得及写，都会让旧逻辑去回源——断网时就是「该话无图片」。
                // 现在只要本地真有页就直接用，本地没有才联网。
                val local = if (localKey != null) {
                    runCatching { downloadManager.localPages(localKey) }.getOrDefault(emptyList())
                } else emptyList()
                val list = if (local.isNotEmpty()) {
                    local
                } else {
                    withContext(Dispatchers.IO) { adapter?.pages(chId.orEmpty()) ?: emptyList() }
                }
                // R-98 三轮：**先把「马上要显示的那一页」拉下来，用它的真实比例定占位，再渲染列表。**
                // 只在顶部学习比例的做法救不了「从第 90 页续读」——那时 firstVisibleItemIndex 已经是 90，
                // 永远学不到，占位一直是猜的 0.7；图片一加载 item 高度减半 → 锚点回拉 → 回弹。
                // 这一页本来就要显示，所以不是额外浪费（顺带把它烘进缓存）。
                if (list.isNotEmpty()) {
                    val probe = (if (index == initialIndex) savedPage else 0)
                        .coerceIn(0, list.lastIndex)
                    // **必须限时**（2026-10-25 用户实测「进阅读器后上方进度条一直在转」）：
                    // 这段在 `loading = true` 期间执行，一旦这张图卡在网络上，进度条就永远不消失。
                    // 1.5s 拿不到就放弃，退回猜的比例——宁可第一页跳一次，也不能让阅读器像卡死。
                    val bmp = kotlinx.coroutines.withTimeoutOrNull(1500) {
                        ImageCache.load(list[probe].url, list[probe].requestHeaders(), foreground = true)
                    }
                    if (bmp != null && bmp.height > 0) {
                        val real = bmp.width.toFloat() / bmp.height
                        // **每次进章都用实测值覆盖**（差 3% 以上才写）：
                        // 只在 `learnedRatio <= 0` 时学，会把历史遗留的错值永久固化——
                        // 之前几轮学到过 0.7 附近的错值，之后无论怎么修都照样回弹（2026-10-25 实测）。
                        val stale = learnedRatio <= 0f || kotlin.math.abs(real - learnedRatio) > real * 0.03f
                        if (real in 0.2f..3.0f && stale) {
                            AppLog.i(
                                "Reader",
                                "ratio probe: chapter=${chapter?.id} real=${"%.3f".format(real)} " +
                                    "prev=${"%.3f".format(learnedRatio)} page=$probe/${list.size}"
                            )
                            learnedRatio = real
                            ratioPrefKey?.let { repository.setPref(it, real.toString()) }
                        }
                    }
                }
                list
            },
            onSuccess = { list ->
                sLog("pages SET size=${list.size} first=${list.firstOrNull()?.url?.takeLast(24)}")
                pages = list
                // 页数落库（#30）：整章图片数就是真实页数。G社 的章节接口不给页数、
                // 图片列表又是加密的，这里是唯一来源；详情页章节列表靠它显示「N 页」。
                // UPDATE 语义：没入书架就试读时 chapter 行不存在，静默跳过。
                val localId = target.localMangaId
                val chId = chapter?.id
                if (localId != null && chId != null && list.isNotEmpty()) {
                    repository.updateChapterPageCountAsync("$localId#$chId", list.size)
                }
            },
            onError = { msg -> error = msg },
        )
        loading = false
        // 继续阅读（第十二轮 #3）：初始话恢复到 savedPage，换话仍从第 0 页起。
        // savedPage 可能超过新取的页数（源站删过页），coerce 防越界。
        val restoreTo = if (index == initialIndex) savedPage else 0
        sLog("chapter RESTORE to=$restoreTo pages=${pages.size} mode=${settings.mode} ratio=%.3f".format(learnedRatio))
        // **页码先落地，再定位**：`currentPage` 只是状态赋值，跟布局无关，
        // 先写它能保证即使定位被跳过/超时，页码也不会停在上一话（R-125）。
        currentPage = restoreTo
        // 定位同样**不能用 suspend 的 scrollToItem**（理由见本 effect 开头的 R-125）：
        // 页模式下没有 LazyColumn，awaitScrollDependencies 会永久挂起；
        // 之前这里还有 `withFrameNanos {}`，窗口被遮挡/最小化时也不出帧。
        // `requestScrollToItem` 把目标位置写进 scroll position，下一次测量生效，
        // 既不需要列表已经 attach，也不需要等到下一帧。
        listState.requestScrollToItem(restoreTo.coerceIn(0, maxOf(0, pages.lastIndex)))
    }

    LaunchedEffect(index) {
        focus.requestFocus()
    }

    // F-38 观看时长：每页最多计 60 秒（用户 2026-10-25 口径「有一页停留大于 1 分钟就暂停记录」），
    // 翻页 / 切话自动重置（key 变化 = 重新开始计）。每 10 秒落一次库，
    // 剩下的在协程被取消（翻页/退出）时用 finally 补写——否则频繁翻页会一直丢零头。
    LaunchedEffect(chapter?.id, currentPage) {
        var counted = 0
        var pending = 0
        try {
            while (counted < READING_SECONDS_PER_PAGE) {
                delay(1_000)
                counted++
                pending++
                if (pending >= 10) {
                    repository.addReadingSeconds(pending)
                    pending = 0
                }
            }
        } finally {
            if (pending > 0) repository.addReadingSeconds(pending)
        }
    }

    // F-39：打开章节 = 「点过」（详情页文字变灰），**与是否读完无关**。
    //
    // R-123（2026-10-26 用户实测「看了个书架里没有的动漫，历史里没记录」）：
    // 这里必须**先补齐 manga/chapter 两行**再谈进度。根因是历史页的 selectHistory
    // 是 `readingProgress JOIN manga`——书架外的书没有 manga 行，进度写得进去，
    // JOIN 却把它整条丢掉，于是「看过但历史里没有」。
    // 补齐用的是 inLibrary = 0 的幽灵行（INSERT OR IGNORE），书架里的书不受影响。
    // **同步调用**：一话只跑一次、两条 INSERT OR IGNORE，几百微秒；同步才能保证
    // 它一定先于后面任何一次进度写入落库。
    LaunchedEffect(chapter?.id, target.localMangaId) {
        val localId = target.localMangaId
        val chapterId = chapter?.id
        if (localId != null && chapterId != null) {
            repository.ensureReadingRows(
                mangaId = localId,
                title = target.title,
                source = target.type,
                sourceMangaId = target.sourceMangaId,
                coverUrl = target.coverUrl,
                chapterId = "$localId#$chapterId",
                chapterName = chapter?.name.orEmpty(),
                chapterIndex = index,
            )
            repository.setChapterOpenedAsync("$localId#$chapterId")
        }
    }

    // 页码跟踪 + 进度落库 + **切模式保页码（R-99）**，三件事必须在同一个 effect 里
    // 按顺序完成。上一版把「切模式同步」拆成另一个 LaunchedEffect，结果页模式那个
    // 收集器先启动、立刻拿 `pageIndex` 的旧值调 record()——而竖向模式根本不更新
    // pageIndex（一直是 0），于是 currentPage 被写成 0，另一个 effect 再读 currentPage
    // 就只能读到 0，页码永远回第一页（用户实测「18 页切单页还是第 1 页」）。
    var lastMode by remember { mutableStateOf(settings.mode) }
    LaunchedEffect(index, pages.size, settings.mode) {
        val localId = target.localMangaId
        val chapterId = chapter?.id
        fun record(page: Int) {
            if (page != currentPage) {
                sLog("record page=$page (was $currentPage) mode=${settings.mode} firstVisible=${listState.firstVisibleItemIndex}")
            }
            currentPage = page
            if (localId != null && chapterId != null) {
                // 异步落库 + 顺带标记已读（同一个写线程，不会半套）；
                // 同步写会在每次滚动停稳时堵一次主线程——滑动卡顿的来源之一。
                repository.saveProgressAsync(
                    ReadingProgress(localId, "$localId#$chapterId", page, pages.size),
                    // F-39：翻到**倒数第 3 页（含）**就算读完（用户 2026-10-25 口径）。
                    // 旧逻辑是 `page > 0`（翻一页就算读完），太松；页数未知时不标记。
                    markChapterRead = pages.isNotEmpty() && page >= maxOf(0, pages.size - READ_DONE_TAIL)
                )
            }
        }
        // 只在**真的切了模式**时同步一次；换话/页数变化时不能动，否则会把新话页码带歪。
        val modeChanged = lastMode != settings.mode
        lastMode = settings.mode
        val safe = currentPage.coerceIn(0, maxOf(0, pages.lastIndex))
        if (settings.mode == ReaderMode.VERTICAL) {
            // 进条漫：新 LazyColumn 从第 0 项开始，先定位到当前页再开始跟踪。
            // **要定位两次**：第一次只能停在"按当前已知尺寸估算"的位置，等 0..safe 这一段
            // 真正测量出来（图片陆续到位会改变测量结果）位置会漂，用户看到的就是
            // 「单页快速滑动后切条漫，自己滑一段/回弹」。等一帧再对齐一次即可收敛。
            if (modeChanged) {
                sLog("mode SYNC mode=${settings.mode} safe=$safe currentPage=$currentPage pages=${pages.size}")
                listState.scrollToItem(safe)
                withFrameNanos { }
                listState.scrollToItem(safe)
                sLog("mode SYNC done firstVisible=${listState.firstVisibleItemIndex}")
            }
            snapshotFlow { listState.firstVisibleItemIndex }.collect { record(it) }
        } else {
            // 出条漫：pageIndex 先对齐当前页，再开始跟踪
            if (modeChanged) pageIndex = safe
            snapshotFlow { pageIndex }.collect { record(it) }
        }
    }

    DisposableEffect(index) {
        onDispose { /* 进度已在 snapshotFlow 里持续落库 */ }
    }

    // 预加载窗口 + 窗口跟随（R-100）：实现在 ui/reader/ReaderPageLoader.kt，
    // 这里只把「当前起始页」传进去（lambda，避免翻页重启 effect）。
    val visibleOnly = target.type == SourceType.COPYMANGA
    val imageLoadDelay = if (visibleOnly) app.gamanga.desktop.ui.reader.VISIBLE_IMAGE_SETTLE_MILLIS else 0L
    ReaderPrefetch(pages = pages, mode = settings.mode, listState = listState, visibleOnly = visibleOnly) { pageIndex }

    /**
     * 跳到上一话/下一话（delta<0 = 上一话）。按用户指定，上一话落到它的**第一页**。
     * 没有更多话了：下边界亮结束面板，上边界给 toast——都不静默无反应。
     */
    fun jumpChapter(delta: Int, edge: ChapterEdge) {
        val next = index + delta
        sLog("JUMP chapter delta=$delta from=$index to=$next accum=%.0f pos=%d".format(edgeAccum, listState.firstVisibleItemIndex))
        if (next !in target.chapters.indices) {
            if (edge == ChapterEdge.NEXT) showEndPanel = true else chapterToast = "已经是第一话了"
            return
        }
        if (System.currentTimeMillis() - lastChapterJumpAt < CHAPTER_JUMP_COOLDOWN_MS) return
        lastChapterJumpAt = System.currentTimeMillis()
        index = next
        pageIndex = 0
        chapterToast = (if (delta > 0) "下一话" else "上一话") + " · " + target.chapters.getOrNull(next)?.name.orEmpty()
    }

    /** 条漫里按"页"跳一格（F-27 左右点击用）。首项 ±1，夹在合法范围内。 */
    fun stepVerticalPage(delta: Int) {
        if (pages.isEmpty()) return
        val target = (listState.firstVisibleItemIndex + delta)
            .coerceIn(0, maxOf(0, pages.lastIndex))
        scope.launch { listState.animateScrollToItem(target) }
    }

    // 滚轮增量的常驻消费协程（第九轮 #4，与拖拽同一套 Channel 串行模式：
    // 消除每格 launch 的 MutatorMutex 互取消——用户实测「往上滚回弹」的真凶）。
    // 声明在 jumpChapter 之后：局部函数要先声明后使用。
    LaunchedEffect(Unit) {
        for (requested in wheelDeltas) {
            // R-122 加固：取图中 / 空章（pages 为空）**不参与边界蓄力**。
            // 换话瞬间新章还没出图，列表没有 item，`scrollBy` 必然消费 0 →
            // 会被判成「已到末尾」→ 攒满就跳下一话并弹提示（用户实测的
            // 「刚进下一话一滚就提示下一话」）。这一格直接丢掉，只消耗不蓄力。
            if (pages.isEmpty()) continue
            // scroll{} 的 block 返回 Unit，consumed 在块内捕获；
            // 打满边界时 consumed < requested——那一下只是"蓄力"信号
            // （chapterEdge 纯函数判定，见 ReaderGestures.kt），
            // 同向滚满 EDGE_JUMP_ACCUM_PX 才真的跳话。
            var consumed = 0f
            listState.scroll { consumed = scrollBy(requested) }
            val edge = chapterEdge(requested, consumed)
            edgeAccum = edgeOverscroll(edgeAccum, requested, edge)
            when (edge) {
                ChapterEdge.PREV -> if (edgeAccum <= -EDGE_JUMP_ACCUM_PX) {
                    edgeAccum = 0f; jumpChapter(-1, ChapterEdge.PREV)
                }
                ChapterEdge.NEXT -> if (edgeAccum >= EDGE_JUMP_ACCUM_PX) {
                    edgeAccum = 0f; jumpChapter(+1, ChapterEdge.NEXT)
                }
                ChapterEdge.NONE -> Unit
            }
        }
    }

    // 翻页助手：键盘、滚轮、拖拽共用。必须外提到 Column 之前——Column 的 modifier
    // lambda（滚轮处理）也调它们，内容 lambda 里声明的局部函数对它不可见。
    // 翻页步长：双页模式一次翻一「对」（spread），否则会出现"同一对页反复重绘一半"的错位。
    // 「上一页/下一页」的语义不随阅读方向变（← 永远是屏幕左边），但在右→左模式下，
    // 屏幕左边那一页在语义上是"下一页"，所以方向键/滚轮语义要反过来。
    // 走到章节边界时自动跳话（用户 2026-10-22 要求），不再"按不动"。
    val rtl = settings.direction == ReaderDirection.RTL && settings.mode != ReaderMode.VERTICAL
    /** 滚轮 lambda 不重启，用 rememberUpdatedState 拿最新 settings（否则连续滚轮拿旧缩放值算同一档）。 */
    val currentSettings by rememberUpdatedState(settings)
    /**
     * 页模式最后一个合法起始页（双页对齐对子边界，不出"半对"单页）。
     * 竖向模式不用它——那里逐页滚，末页就是 pages.size-1。
     *
     * **必须调用时现算，不能缓存成 val**：滚轮处理挂在 pointerInput 上，那个
     * lambda 只在键变化时重建；pages 是异步加载的——首帧 pages 为空时若把
     * lastStart 算成 0 存进 val，会被 lambda 里捕获的 goNext/wheelPageTurn
     * 闭包永久带着，图加载出来后任何滚轮都判「已在章节边界」，只能蓄力切话。
     * 用户实测「滚轮不翻页，只会切上一话/下一话」的真因就在这里。
     */
    fun lastStartNow() = lastSpreadStart(pages.size, spreadCount(currentSettings.mode))
    /** 竖向连续模式的末页下标（同样调用时现算，理由同 [lastStartNow]）。 */
    fun lastPageNow() = (pages.size - 1).coerceAtLeast(0)

    fun goPrev() {
        if (settings.mode == ReaderMode.VERTICAL) {
            // R-122：取图中 pages 为空，列表没有 item，`canScrollBackward` 恒 false——
            // 旧逻辑会立刻判成「到顶」跳上一话。空章时直接不动。
            if (pages.isEmpty()) return
            if (listState.firstVisibleItemIndex == 0 && !listState.canScrollBackward) {
                jumpChapter(-1, ChapterEdge.PREV)
                return
            }
            scope.launch {
                listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
            }
        } else {
            if (pageIndex <= 0) {
                jumpChapter(-1, ChapterEdge.PREV)
                return
            }
            pageIndex = wheelPageTurnTarget(
                pageIndex, pages.size, spreadCount(currentSettings.mode), forward = false
            )
        }
    }

    fun goNext() {
        if (settings.mode == ReaderMode.VERTICAL) {
            // R-122：同 goPrev——空章不能判成「到底」。
            if (pages.isEmpty()) return
            if (!listState.canScrollForward) {
                jumpChapter(+1, ChapterEdge.NEXT)
                return
            }
            scope.launch {
                val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(lastPageNow())
                listState.animateScrollToItem(next)
            }
        } else {
            if (pageIndex >= lastStartNow()) {
                jumpChapter(+1, ChapterEdge.NEXT)
                return
            }
            pageIndex = wheelPageTurnTarget(
                pageIndex, pages.size, spreadCount(currentSettings.mode), forward = true
            )
        }
    }

    /**
     * 页模式（单页/双页）的滚轮翻页，撞到章节边界时带「蓄力」。
     *
     * 与 [goNext]/[goPrev] 的区别只在边界：不立即跳话，先在边界上累积同向滚动
     * （每格滚轮记 [WHEEL_SCROLL_PX_PER_LINE]），滚满 [EDGE_JUMP_ACCUM_PX] 才跳。
     * 用户实测单格滚轮在话首/话尾「很容易触发上一话/下一话而不是翻页」——
     * 误触一格只累积不跳，持续滚才是明确的"我要换话"。
     * 轻点和键盘不走这里（那是明确的单次动作，保持即时跳转）。
     */
    fun wheelPageTurn(forward: Boolean) {
        // 空页守卫：取图中/取图失败时 pages 为空，旧逻辑会把 lastPage=0 当成
        // "已在边界"，每格滚轮都攒蓄力——两格就跳话。用户体感就是
        // 「滚轮不翻页，只会切话」（2026-10-23 实测反馈）。没内容可翻时
        // 滚轮什么都不做，等图到了再说。
        if (pages.isEmpty()) return
        val edge = pageChapterEdge(pageIndex, lastStartNow(), forward)
        if (edge == ChapterEdge.NONE) {
            edgeAccum = 0f
            if (forward) goNext() else goPrev()
            return
        }
        edgeAccum = edgeOverscroll(
            edgeAccum,
            if (forward) WHEEL_SCROLL_PX_PER_LINE else -WHEEL_SCROLL_PX_PER_LINE,
            edge
        )
        if (abs(edgeAccum) >= EDGE_JUMP_ACCUM_PX) {
            edgeAccum = 0f
            jumpChapter(if (forward) +1 else -1, edge)
        }
    }

    // 外面包一层 Box：详情二级页要浮在整个阅读器之上（F-143），
    // 塞进 Column 里会被 Column 的布局约束压成一条。
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && (settingsOpen || chaptersOpen)) {
            settingsOpen = false; chaptersOpen = false; focus.requestFocus(); true
        } else false
    }) {
    ReaderViewportMotion(
        visible = chromeVisible,
        reducedMotion = reducedMotion,
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Color.ReaderBg)
            // 滚轮处理挂在**整个阅读器外层**（含顶栏/控件区）：实测「有时候没响应」
            // 的一部分原因是指针在工具栏区域——那里的滚轮原来没人接。
            // Initial 通道先消费，杜绝与 LazyColumn 原生滚动双重滚动。
            // 键里带 pages.size：换话/取图完成后页数变化，滚轮 block 随之重建，
            // 闭包里的翻页函数一定拿着新一章的页数（与 lastStartNow() 现算双保险）。
            .pointerInput(currentSettings.mode, pages.size) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        val dx = change.scrollDelta.x
                        if (dy == 0f && dx == 0f) continue
                        event.changes.forEach { it.consume() }
                        // 用户接管：滚轮一来就取消还在滑的惯性。此前滚轮路径不取消，
                        // 惯性作业按原方向继续滚，和滚轮增量互相抵消——用户实测
                        // 「像上滚轮，滚两下就会回到滚轮前的位置，等于没滚动」
                        // （2026-10-23 反馈，与拖拽起手时的取消逻辑对齐）。
                        flingJob?.cancel()
                        val s = currentSettings
                        // Ctrl+滚轮 / 鼠标侧滚轮 = 缩放（上滚/右滚放大，用户指定手势）
                        val zoomStep = wheelZoomStep(
                            scrollY = dy,
                            scrollX = dx,
                            ctrl = event.keyboardModifiers.isCtrlPressed,
                            step = ReaderSettings.ZOOM_STEP
                        )
                        when {
                            // 缩放增量进合并通道（见 zoomDeltas）：一帧最多改一次状态
                            zoomStep != null -> zoomDeltas.trySend(zoomStep)
                            s.mode == ReaderMode.VERTICAL -> {
                                if (dy < 0f) showEndPanel = false // 往上滚先收起结束面板
                                // 增量汇入通道、由常驻协程顺序滚（第九轮 #4）：
                                // 旧实现每格滚轮 scope.launch 一个新协程——连续滚轮
                                // （尤其快速连滚）时多协程并发抢 LazyListState 的
                                // MutatorMutex 互相取消：增量被吞，用户实测
                                // 「往上滚回弹到滚轮前的位置，等于没滚动」。
                                // 与拖拽同一套 Channel 串行模式（见拖拽块注释）。
                                wheelDeltas.trySend(dy * WHEEL_SCROLL_PX_PER_LINE)
                            }
                            else -> when (wheelPageForward(dy)) {
                                true -> wheelPageTurn(forward = true)
                                false -> wheelPageTurn(forward = false)
                                null -> Unit
                            }
                        }
                    }
                }
            },
        toolbar = {
            // 标题、加载条与漫画共享过渡进度，内容只按目标视口重测。
            ReaderBar(
                title = target.title,
                chapterName = chapter?.name ?: "-",
                index = index,
                total = target.chapters.size,
                page = currentPage,
                pageCount = pages.size,
                onJumpPage = { n ->
                    // 用户输入的是 1-based 页码；条漫直接滚到该项，页模式改 pageIndex
                    val target = (n - 1).coerceIn(0, maxOf(0, pages.lastIndex))
                    if (settings.mode == ReaderMode.VERTICAL) {
                        scope.launch { listState.animateScrollToItem(target) }
                    } else {
                        pageIndex = if (settings.mode == ReaderMode.DOUBLE) {
                            (target / 2) * 2
                        } else target
                    }
                },
                onBrowser = onOpenBrowser,
                onClose = onClose,
                onDoubleClickDrag = { toggleFullscreen() },
                coverUrl = barCoverUrl,
                onOpenDetail = {
                    detail.open(
                        SourceManga(
                            sourceId = target.sourceMangaId,
                            title = target.title,
                            coverUrl = barCoverUrl,
                        ),
                        target.type,
                    )
                },
            )
        }, progress = {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(Tokens.ReaderChrome.LoadingHeight))
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .clipToBounds()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        // Esc 先收面板、再退阅读器（用户按 Esc 的直觉是"先关最近打开的那层"）
                        Key.Escape -> {
                            if (settingsOpen || chaptersOpen) { settingsOpen = false; chaptersOpen = false } else onClose()
                            true
                        }
                        Key.DirectionLeft, Key.PageUp, Key.DirectionUp -> {
                            if (rtl) goNext() else goPrev()
                            true
                        }
                        Key.DirectionRight, Key.PageDown, Key.DirectionDown, Key.Spacebar -> {
                            if (rtl) goPrev() else goNext()
                            true
                        }
                        Key.Equals, Key.Plus -> { updateSettings(zoomBy(settings, +ReaderSettings.ZOOM_STEP)); true }
                        Key.Minus -> { updateSettings(zoomBy(settings, -ReaderSettings.ZOOM_STEP)); true }
                        // 0 = 一键回到 100%（缩放跑了以后不用一格格按回去）
                        Key.Zero -> { updateSettings(settings.copy(zoom = 1f)); true }
                        Key.LeftBracket -> { if (index > 0) index--; true }
                        Key.RightBracket -> { if (index < target.chapters.lastIndex) index++; true }
                        else -> false
                    }
                }
        ) {
            when {
                error != null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("取图失败", color = Tokens.Color.ReaderError, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, color = Tokens.Color.ReaderTextSoft, fontSize = 13.sp)
                    // Komiic 的图片票据按账号算每日配额，匿名额度很小——直接把出路写出来，
                    // 否则用户只会看到一句英文报错，不知道该干什么
                    if (error!!.contains("quota", ignoreCase = true)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Komiic 今日图片额度已用完。在下方「用浏览器查看」里登录账号后关闭，" +
                                "登录态会自动保存，额度按账号重新计算。",
                            color = Tokens.Color.ReaderTextSecondary, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.width(420.dp)
                        )
                    }
                    if (!error!!.contains("quota", ignoreCase = true) &&
                        error!!.contains("HTTP 404")
                    ) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "源站可能已改版、改了这本书的地址。回「浏览」重新搜索这本书再打开即可。",
                            color = Tokens.Color.ReaderTextSecondary, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.width(420.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 偶发失败（网络抖动 / 源站 5xx）重取一次就能好，不用用户切话再切回来
                        Button(onClick = { reloadKey++ }) { Text("重试") }
                        // 配额类错误：真正的解法是登录（额度按账号算），直接把用户送到登录页。
                        // 只有这个源确实提供登录时才显示——盗版聚合站不给登录入口。
                        if (onOpenLogin != null && error!!.contains("quota", ignoreCase = true)) {
                            Button(onClick = onOpenLogin) { Text("登录提额度") }
                        }
                        Button(onClick = onOpenBrowser) { Text("用浏览器查看") }
                    }
                }

                pages.isEmpty() && !loading -> Text(
                    "该话没有图片",
                    color = Tokens.Color.ReaderTextSoft,
                    modifier = Modifier.align(Alignment.Center)
                )

                settings.mode == ReaderMode.VERTICAL -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 适应高度（R-30，用户 2026-10-22 要求）：列宽 = 视口高 × 首页宽高比，
                    // zoom=1 即"一页一屏"的阅读宽度，不再默认拉满整屏。
                    // 条漫类超宽长图按高度算会窄到不可读，由 fitHeightColumnWidth 兜到 40% 视口宽；
                    // 比视口还宽的超宽图则锁到视口宽（等价适应宽度）。
                    // 列宽 ≤ 视口时列**居中**——旧版恒挂 horizontalScroll，内容左对齐，
                    // zoom<1 时漫画整个靠左（R-32）；超出视口才启用横向滚动看细节。
                    // **列宽必须和 item 高度用同一个比例**（2026-10-25）：
                    // 旧代码这里只看 `pages.first()` 的宽高，源站不下发时恒用 0.7；
                    // 而 item 高度已经用 learnedRatio（跨页扫描常 1.3~1.5）。
                    // 两个值打架时列宽偏窄、`needsHScroll` 判定也跟着错，
                    // 表现为「黑执事往上滑有跳动感」。
                    val ratio = pages.firstOrNull()
                        ?.takeIf { it.width > 0 && it.height > 0 }
                        ?.let { it.width.toFloat() / it.height }
                        ?: learnedRatio.takeIf { it > 0f }
                        ?: 0.7f // 未知尺寸按常见竖版比例兜底
                    val fitWidth = fitHeightColumnWidth(maxWidth.value, maxHeight.value, ratio)
                    val targetWidth = (fitWidth * settings.zoom).dp
                    val hScroll = rememberScrollState()
                    val needsHScroll = targetWidth > maxWidth
                    // 手势里要读最新值，但手势本身不能因为翻转而重启（见下方 pointerInput(Unit)）
                    val needsHScrollState by rememberUpdatedState(needsHScroll)
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (needsHScroll) Modifier.horizontalScroll(hScroll) else Modifier)
                            // 放大超过视口宽时给"可拖动"光标，提示可以横拖看细节（与页模式一致）
                            .then(if (needsHScroll) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                            // 左键拖拽滑动（条漫基础操作，用户实测要求）。
                            //
                            // 第八轮 #2 重构（用户实测「左键往下拖滑不动，像到顶
                            // 卡在这一页」），两个病因一次修：
                            // ①旧实现每个 drag 帧 scope.launch 一个新协程去
                            //   scrollBy——多个协程并发抢 LazyListState 的
                            //   MutatorMutex 互相取消：滚动增量被吞（体感拖不动），
                            //   且每次取消在 EDT 上留一个无堆栈异常（logs/app-run.log
                            //   实测 30 连缀）。现在所有增量汇入一个通道、单协程
                            //   顺序消费，滚动连续且零竞争。
                            // ②手势跟踪走 Initial 通道（父先于子）并在此消费——
                            //   LazyColumn 的原生拖拽再也收不到，杜绝双重滚动
                            //   （与滚轮同一套 Input 收口模式，R-29~R-37 一贯做法）。
                            // **不要用 needsHScroll 当 key**：比例学习完成后列宽会变一次，
                            // needsHScroll 跟着翻转 → pointerInput 重启 → 正在拖的手势被中断，
                            // 用户看到的就是「滑着滑着跳一下」。用 Unit + rememberUpdatedState。
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    )
                                    // 新手势开始：取消还在滑的惯性（用户接管）
                                    flingJob?.cancel()
                                    var last = down.position
                                    var dragged = false
                                    var totalDx = 0f // 累积位移：慢拖时单帧 delta 可能
                                    var lastDy = 0f // 最后一帧的位移方向，用于校验惯性方向
                                    var totalDy = 0f // 永远小于 touchSlop（判定永远
                                    // 不触发），按总位移判才对（第九轮 #3 加固）
                                    val touchSlop = viewConfiguration.touchSlop
                                    val hScrollNow = needsHScrollState
                                    // 出手速度跟踪：松手时按最后几帧的移动速度决定惯性滑多远
                                    val velocityTracker = VelocityTracker()
                                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                                    // 拖拽增量 → 单协程顺序滚（见上方注释①）。
                                    // 注意：协程挂在组合级 scope 上——AwaitPointerEventScope
                                    // 不是 CoroutineScope，在这里 launch 会解析到
                                    // deprecated 的全局 launch。手势结束后 channel 关闭、
                                    // for 循环自然退出；与后续惯性的顺序由
                                    // LazyListState 的 MutatorMutex 保证（谁先进谁先滚）。
                                    val deltas = Channel<Offset>(Channel.UNLIMITED)
                                    scope.launch {
                                        try {
                                            for (delta in deltas) {
                                                // R-122 加固：取图中（pages 为空）不参与边界蓄力，
                                                // 理由同滚轮消费协程——否则换话瞬间拖一下就被判成
                                                // 「到末尾」，攒够就跳话。
                                                if (pages.isEmpty()) continue
                                                var consumed = 0f
                                                listState.scroll { consumed = scrollBy(-delta.y) }
                                                // ScrollState 没有公开 scrollBy，走 scroll{scrollBy}
                                                if (hScrollNow) hScroll.scroll { scrollBy(-delta.x) }
                                                // 拖到边界同样"蓄力"：同向拖满 EDGE_JUMP_ACCUM_PX
                                                // 才跳话（和滚轮一套判定，防误触）
                                                // **边界判定要看真实边界状态，不能只看 consumed**（2026-10-25）：
                                                // `chapterEdge` 只要「消费量 < 请求量」就判成到了边界，
                                                // 而拖拽通道被惯性作业插队时 `scrollBy` 也可能只消费一半 ——
                                                // 于是中途每一帧都往 edgeAccum 里加满额位移，攒够 600px 就跳话，
                                                // 用户看到的就是「往上滑着滑着突然弹走」。
                                                val requested = -delta.y
                                                // **方向映射不能反**（2026-10-25 用户实测「到顶不切上一话」）：
                                                // requested > 0 = 内容往前滚（看后面的页）→ 只有滚到底才是 NEXT；
                                                // requested < 0 = 往回滚 → 只有滚到顶才是 PREV。
                                                // 六轮写反了，导致到顶永远不触发上一话。
                                                val edge = when {
                                                    kotlin.math.abs(consumed) >=
                                                        kotlin.math.abs(requested) - 0.5f -> ChapterEdge.NONE
                                                    requested < 0f && !listState.canScrollBackward ->
                                                        ChapterEdge.PREV
                                                    requested > 0f && !listState.canScrollForward ->
                                                        ChapterEdge.NEXT
                                                    else -> ChapterEdge.NONE
                                                }
                                                edgeAccum = edgeOverscroll(edgeAccum, requested, edge)
                                                if (kotlin.math.abs(consumed) < kotlin.math.abs(requested) - 0.5f) {
                                                    sLog(
                                                        ("drag UNDER req=%.1f con=%.1f edge=%s accum=%.0f " +
                                                            "pos=%d canBack=%b canFwd=%b").format(
                                                            requested, consumed, edge, edgeAccum,
                                                            listState.firstVisibleItemIndex,
                                                            listState.canScrollBackward,
                                                            listState.canScrollForward
                                                        )
                                                    )
                                                }
                                                when (edge) {
                                                    ChapterEdge.PREV -> if (edgeAccum <= -EDGE_JUMP_ACCUM_PX) {
                                                        edgeAccum = 0f; jumpChapter(-1, ChapterEdge.PREV)
                                                    }
                                                    ChapterEdge.NEXT -> if (edgeAccum >= EDGE_JUMP_ACCUM_PX) {
                                                        edgeAccum = 0f; jumpChapter(+1, ChapterEdge.NEXT)
                                                    }
                                                    ChapterEdge.NONE -> Unit
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e // 取消是正常传播，不是错误（R-45 同款纪律）
                                        } catch (e: Throwable) {
                                            // 滚动异常不能再让 EDT 裸奔——无堆栈的
                                            // "Exception in thread AWT-EventQueue-0" 就是这么来的
                                            AppLog.w("Reader", "drag scroll failed", e)
                                        }
                                    }
                                    // 事件循环：只跟按下的那根指针；累积位移超过
                                    // touchSlop 才算拖拽（避免点击微抖被当成滚动）
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                        if (!change.pressed) break
                                        val delta = change.position - last
                                        if (delta == Offset.Zero) continue
                                        last = change.position
                                        totalDx += delta.x
                                        totalDy += delta.y
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        if (!dragged && abs(totalDx) < touchSlop && abs(totalDy) < touchSlop) continue
                                        if (!dragged) {
                                            sLog(
                                                ("drag START y=%.0f pos=%d/%d off=%d canBack=%b canFwd=%b " +
                                                    "ratio=%.3f").format(
                                                    down.position.y,
                                                    listState.firstVisibleItemIndex,
                                                    pages.size,
                                                    listState.firstVisibleItemScrollOffset,
                                                    listState.canScrollBackward,
                                                    listState.canScrollForward,
                                                    learnedRatio
                                                )
                                            )
                                        }
                                        dragged = true
                                        change.consume() // 消费掉，LazyColumn 不会再滚（注释②）
                                        if (delta.y < 0f) showEndPanel = false
                                        lastDy = delta.y
                                        deltas.trySend(delta)
                                    }
                                    deltas.close()
                                    // 不 join：restricted scope 里不能调成员 suspend；
                                    // 剩余增量由 scrollJob 自己排空，与惯性的先后
                                    // 由 MutatorMutex 保证（见上方注释）。
                                    // 松手惯性（用户 2026-10-23 要求「像手机一样滑动」）：
                                    // 出手速度超过阈值就按指数衰减继续滚，撞到章节边界即停
                                    // （边界跳话交给滚轮/拖拽的蓄力，惯性不跳）。
                                    if (dragged) {
                                        // **速度必须夹紧**（2026-10-25 日志实测）：
                                        // 92 次松手里 83 次 |vy|>2000、21 次 >10000、最高 20684 px/s，
                                        // 50 次惯性直接跑到 2400px 上限 = 甩一下飞 2~3 屏，
                                        // 用户体感就是「滑一下突然跳走 / 往回弹」。
                                        val rawVy = velocityTracker.calculateVelocity().y
                                        val vy = rawVy.coerceIn(-FLING_MAX_VELOCITY, FLING_MAX_VELOCITY)
                                        // **方向校验**：松手瞬间速度若与最后一帧拖拽方向相反
                                        // （减速/手抖很常见），一律不给惯性——否则「往上滑」会变成
                                        // 「往回飞」，这就是用户报的"回弹"。
                                        val sameDir = lastDy == 0f || (vy > 0f) == (lastDy > 0f)
                                        sLog(
                                            ("drag END vy=%.0f(raw=%.0f) lastDy=%.1f pos=%d accum=%.0f " +
                                                "sameDir=%b fling=%b").format(
                                                vy, rawVy, lastDy, listState.firstVisibleItemIndex, edgeAccum,
                                                sameDir, sameDir && abs(vy) >= FLING_MIN_VELOCITY
                                            )
                                        )
                                        // 兜底开关：`-Dgamanga.fling=0` 彻底关掉惯性（排查用）。
                                        val flingEnabled = System.getProperty("gamanga.fling") != "0"
                                        if (flingEnabled && sameDir && abs(vy) >= FLING_MIN_VELOCITY) {
                                            // **惯性距离按松手速度算**（2026-10-24 用户要求
                                            // 「像手机滑动那样丝滑」）：
                                            // 旧实现用「拖拽距离 × 50%」，与速度无关——快速
                                            // 轻扫（位移小、速度大）也只滑半屏，手感很「粘」。
                                            // 手机的 fling 距离 ≈ 松手速度 × 0.35 秒；
                                            // 夹在 [120px, 2400px]：轻扫也有惯性，猛甩不至于飞太远
                                            // （上一轮无上限时用户实测「约 130% 幅度异常上跳」）。
                                            // 距离也下调：0.35 → 0.22、上限 2400 → 900。
                                            // 2400px 在条漫里约 2~3 屏，一甩就"飞过头"，是跳感的直接来源。
                                            // 0.22/900 → 0.35/1500（用户「滑动距离太短」）；
                                            // 真正刹车是上面的 800ms 时间上限，这里只是兜底。
                                            val flingCap = (abs(vy) * 0.35f).coerceIn(120f, 1500f)
                                            var flingTravelled = 0f
                                            var flingFrame = 0
                                            flingJob = scope.launch {
                                                try {
                                                    var v = vy
                                                    val flingStartedAt = withFrameNanos { it }
                                                    var lastFrameNanos = flingStartedAt
                                                    while (isActive && abs(v) > FLING_STOP_VELOCITY) {
                                                        val now = withFrameNanos { it }
                                                        // **时间上限**（2026-10-25 日志实测）：高刷新率下 dt 只有 3ms，
                                                        // 速度衰减到 100px/s 后每帧只走 1px，而退出条件是「累计距离达上限」，
                                                        // 于是以 1px/帧 爬了 247+ 帧（4~8 秒）—— 用户体感就是「卡住/被拉回」。
                                                        // 惯性不该超过 450ms，到点就停。
                                                        if ((now - flingStartedAt) / 1_000_000L > FLING_MAX_MS) {
                                                            sLog("fling STOP(time) travelled=%.0f".format(flingTravelled))
                                                            break
                                                        }
                                                        // dt 夹在 [1ms, 64ms]：卡帧后一帧跳太远、0 时长死循环
                                                        val dt = ((now - lastFrameNanos) / 1_000_000_000f)
                                                            .coerceIn(0.001f, 0.064f)
                            lastFrameNanos = now
                            // 符号必须和拖拽一致：拖拽是 scrollBy(-delta.y)
                            // （手指上移 delta.y<0 → 内容向下滚=看后者），
                            // 松手时 vy<0 也要继续向下滚 → step 取 -v*dt。
                            // 此前写成 v*dt，上滑松手内容反而回滑——用户实测
                            // 「会回弹，越滑越反方向滑动」（2026-10-23 反馈）。
                            val stepPx = -v * dt
                                                        var consumed = 0f
                                                        listState.scroll { consumed = scrollBy(stepPx) }
                                                        if (flingFrame++ % 3 == 0) {
                                                            sLog(
                                                                ("fling f%d step=%.0f con=%.0f pos=%d off=%d").format(
                                                                    flingFrame, stepPx, consumed,
                                                                    listState.firstVisibleItemIndex,
                                                                    listState.firstVisibleItemScrollOffset
                                                                )
                                                            )
                                                        }
                                                        if (abs(consumed) < abs(stepPx) - 1f) {
                                                            sLog("fling STOP(edge) travelled=%.0f cap=%.0f".format(flingTravelled, flingCap))
                                                            break
                                                        }
                                                        flingTravelled += abs(consumed)
                                                        if (flingTravelled >= flingCap) {
                                                            sLog("fling STOP(cap) travelled=%.0f cap=%.0f".format(flingTravelled, flingCap))
                                                            break
                                                        }
                                                        v *= flingDecayFactor(FLING_FRICTION_PER_SECOND, dt)
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e // 取消是正常传播（新手势接管/退出阅读器）
                                                } catch (e: Throwable) {
                                                    // 惯性协程裸奔会在 EDT 上留无堆栈异常
                                                    // （logs/app-run.log 的 "Exception in thread
                                                    // AWT-EventQueue-0" 30 连缀疑凶之一，第十轮）
                                                    AppLog.w("Reader", "fling failed", e)
                                                }
                                            }
                                        }
                                    }
                                    // 没拖动过 = 轻点：**左 1/3 上一页、右 1/3 下一页、中间切沉浸**
                                    // （F-27，2026-10-25 用户要求「条漫也能左右点击翻页」）。
                                    // 条漫一屏可能跨好几页，所以这里按"当前首项 ±1 项"跳，而不是整屏。
                                    if (!dragged) {
                                        val w = size.width.toFloat()
                                        val x = down.position.x
                                        when {
                                            x < w / 3f -> stepVerticalPage(-1)
                                            x > w * 2f / 3f -> stepVerticalPage(+1)
                                            else -> chromeVisible = !chromeVisible
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.width(targetWidth).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(pages, key = { i, page -> "$i:${page.url}" }) { i, page ->
                                // LazyColumn 里高度无界，必须按图片比例占位（否则布局塌成 0 高）。
                                // G社 不下发宽高（width/height=0），猜错比例时 FillWidth 会在图下方
                                // 露出灰底、或把图伸进下一格——用户实测「条漫中间有白色间隔」（R-42）。
                                // 改由 RemoteImage 加载完按真实比例重布（dynamicRatio）；
                                // Komiic 有宽高的也会收敛到同一真值，行为一致。
                                val itemRatio = when {
                                    page.width > 0 && page.height > 0 ->
                                        page.width.toFloat() / page.height
                                    // 学到的真值优先（同一话第二次打开就从第一页起不再跳动）
                                    learnedRatio > 0f -> learnedRatio
                                    else -> 0.7f // 未知尺寸按常见竖版比例兜底
                                }
                                RemoteImage(
                                    url = page.url,
                                    contentDescription = "第 ${i + 1} 页",
                                    // 锚点补偿已撤销（2026-10-25 01:45）：日志里 `anchor` 事件 **0 次**，
                                    // 说明阅读过程中 item 高度根本没变——高度不是回弹的原因。
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth,
                                    // 条漫同样是"大图缩小显示"（1600 宽的条被压到窗口宽），
                                    // 用 mipmap 采样；放大显示时 Skia 取 LOD0，与 Low 等价，不会变糊
                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                                    headers = page.requestHeaders(),
                                    dynamicRatio = true,
                                    fallbackRatio = itemRatio,
                                    loadEnabled = !visibleOnly || app.gamanga.desktop.ui.reader.readerPageVisible(listState, i, page.url),
                                    loadDelayMillis = imageLoadDelay,
                                    onRatioKnown = { real ->
                                        // **只在还停在开头时学习**（第一页进话就加载，此时列表在顶部，
                                        // 改占位比例不会让任何内容位移）。若等到滑到第 90 页才改，
                                        // 整列 item 高度一起变 → 滚动位置被夹 → 用户看到「往上滑反而跳到 94 页」。
                                        if (learnedRatio <= 0f &&
                                            listState.firstVisibleItemIndex <= 2 &&
                                            real in 0.2f..3.0f
                                        ) {
                                            learnedRatio = real
                                            ratioPrefKey?.let { repository.setPref(it, real.toString()) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 单页：一页铺满可视高度，点击右半边/左半边翻页（鼠标党不用键盘）
                settings.mode == ReaderMode.SINGLE -> PageSpread(
                    pages = pages,
                    startIndex = pageIndex,
                    count = 1,
                    rtl = settings.direction == ReaderDirection.RTL,
                    zoom = settings.zoom,
                    loadDelayMillis = imageLoadDelay,
                    onTapNext = { pageIndex = (pageIndex + 1).coerceAtMost(lastStartNow()) },
                    onTapPrev = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                    onTapCenter = { chromeVisible = !chromeVisible }
                )

                else -> PageSpread(
                    pages = pages,
                    startIndex = pageIndex,
                    count = 2,
                    rtl = settings.direction == ReaderDirection.RTL,
                    zoom = settings.zoom,
                    loadDelayMillis = imageLoadDelay,
                    onTapNext = { pageIndex = (pageIndex + 2).coerceAtMost(lastStartNow()) },
                    onTapPrev = { pageIndex = (pageIndex - 2).coerceAtLeast(0) },
                    onTapCenter = { chromeVisible = !chromeVisible }
                )
            }

            // 跳话提示：画在内容之上，2 秒自动消失
            chapterToast?.let { msg ->
                Text(
                    msg,
                    color = Tokens.Color.ReaderText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Tokens.Color.ReaderScrim, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // 边界蓄力提示（R-46）：撞到章节边界、同向累积达到跳转阈值前，
            // 屏幕中间常驻提示「再滚就切话」——把原本沉默的蓄力状态说出来，
            // 用户知道再滚一格会发生什么，不会以为"没反应"或"误触了"。
            // edgeAccum 非零即正在蓄力（回到内容区/跳话后归零，提示随之消失）；
            // 符号表示方向：负 = 往上（上一话），正 = 往下（下一话）。
            if (edgeAccum != 0f) {
                Text(
                    if (edgeAccum < 0f) "再往上滚就切至上一话" else "再往下滚就切至下一话",
                    color = Tokens.Color.ReaderText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Tokens.Color.ReaderScrim, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            // 读完最后一话：结束面板。往上滚/跳话都会收起它（见滚轮/拖拽处理）。
            if (showEndPanel) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Tokens.Color.ReaderPanel, RoundedCornerShape(10.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("已读完 · 已是最后一话", color = Tokens.Color.ReaderText, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showEndPanel = false; reloadKey++ }) { Text("重读本话") }
                        Button(onClick = onClose) { Text("返回书架") }
                    }
                }
            }
        }
    }

        // ---- 底部悬浮控件层（2026-10-30 用户要求 5 / 6 / 7）----
        //
        // 三件事，都随沉浸模式（[chromeVisible]）一起显隐：
        //  ① 正中：上一话 / 下一话 / 阅读设置入口（一个胶囊，[ReaderBottomGroup]）；
        //  ② 设置入口点开 → 从底部往上弹一块小面板（[ReaderSettingsPanel]）；
        //  ③ 右下角：全屏 / 恢复窗口，**只有图标、没有外框**。
        //
        // 为什么这层是 `fillMaxSize` 的 Box 而不直接放在 Column 里：Column 里它会占
        // 布局高度（内容区变矮、页模式图片重新 Fit 一次）；浮层不占布局，图片始终铺满。
        // Box 本身没有 pointerInput，点击照常穿透到下面的翻页手势。
        AnimatedVisibility(
            visible = chromeVisible,
            // 外层只做**很短的**淡入淡出：遮罩与右下角全屏键跟它走；
            // 真正"抬起来"的动效由里面那两条 slide 负责（顶栏下滑、底部上滑）。
            enter = fadeIn(tween(chromeExitMs)),
            exit = fadeOut(tween(chromeExitMs)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 面板打开时，画面其余地方变成"点一下就收起"的关闭区。
                // 不加这一层，用户点画面以为会关面板，实际是翻了页——两个动作叠在一起。
                if (settingsOpen || chaptersOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { settingsOpen = false; chaptersOpen = false }
                    )
                }
                // 底部整组（上一话/下一话/设置 + 弹出的面板）**从下方滑上来**，
                // 收起时滑回去（2026-10-30 第二轮：用户「之前的动效没有了重新加上」）。
                // 这一层是浮层、不占布局，所以滑动位移不会让漫画重新 Fit——纯绘制位移，代价为零。
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = slideInVertically(
                        animationSpec = tween(chromeAnimMs, easing = ReaderChromeEasing),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(chromeAnimMs)),
                    exit = slideOutVertically(
                        animationSpec = tween(chromeExitMs, easing = ReaderChromeEasing),
                        targetOffsetY = { it },
                    ) + fadeOut(tween(chromeExitMs)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                Column(
                    Modifier.padding(bottom = ReaderBottomInset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReaderPanelMotion(settingsOpen, chaptersOpen, reducedMotion,
                        chapters = { ReaderChapterPanel(target.chapters, index, onSelect = { selected ->
                            index = selected
                            pageIndex = 0
                            currentPage = 0
                            if (settings.mode == ReaderMode.VERTICAL) listState.requestScrollToItem(0)
                            chaptersOpen = false
                            focus.requestFocus()
                        }) }, settings = { ReaderSettingsPanel(
                            settings = settings,
                            onSettings = ::updateSettings,
                        ) }
                    )
                    ReaderBottomGroup(
                        canPrev = index > 0,
                        canNext = index < target.chapters.lastIndex,
                        settingsOpen = settingsOpen,
                        // 按钮是**明确的单次动作**，不走 jumpChapter 的 800ms 冷却
                        // （那个冷却是给滚轮/拖拽蓄力防误触的，按钮连点会被它吞掉，
                        // 用户体感就是「点了没反应」）。与旧顶栏两颗按钮的语义一致。
                        onPrev = { if (index > 0) index-- },
                        onNext = { if (index < target.chapters.lastIndex) index++ },
                        onToggleSettings = { settingsOpen = !settingsOpen; chaptersOpen = false },
                        chaptersOpen = chaptersOpen,
                        onToggleChapters = { chaptersOpen = !chaptersOpen; settingsOpen = false },
                    )
                }
                }
                ReaderFullscreenButton(
                    fullscreen = fullscreen,
                    onToggle = ::toggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = ReaderCornerInset, bottom = ReaderCornerInset),
                )
            }
        }

        // 详情二级页：宿主自带遮罩 + 底部上滑面板 + 换源弹窗（F-143）。
        // 放在阅读器最外层 Box 里，盖住顶栏与底部控件栏。
        DetailHost(
            state = detail,
            repository = repository,
            downloadManager = downloadManager,
            onRead = { newTarget ->
                // 在详情面板里点了另一话：直接换 target 不合适（ReaderScreen 的
                // index 是 remember 出来的初值），所以走「关掉面板 + 交给上层重开」。
                // 但阅读器已经是顶层覆盖层，没有上层——退一步：切到那一话。
                detail.close()
                index = newTarget.startIndex.coerceIn(0, target.chapters.lastIndex)
            },
            // 阅读器的 onOpenBrowser 不带参（它只知道当前这本书），
            // 详情面板的签名是 (url, domain) —— 这里忽略那两个参数，行为一致。
            onOpenBrowser = { _, _ -> onOpenBrowser() },
        )
    }
}

private const val READ_DONE_TAIL = 3

/** 同一页最多计多少秒观看时长（F-38）：超过就当「人不在」，停止计时。 */
private const val READING_SECONDS_PER_PAGE = 60

/** 滑动诊断日志开关：默认关，`-Dgamanga.scrolllog=1` 打开。 */
private val SCROLL_LOG: Boolean = System.getProperty("gamanga.scrolllog") == "1"

/** 惯性速度上限（px/s）。日志实测鼠标能甩出 20000+，不夹紧就会飞 2~3 屏。 */
private const val FLING_MAX_VELOCITY = 3200f

/** 惯性最长持续时间（ms）。超过就停——防止低速度尾巴以 1px/帧 爬好几秒。
 *
 * 450 → 800（2026-10-25 用户「滑动距离太短」）：这个上限是惯性的**真实刹车**，
 * 距离主要由它决定（约 `v0 × (1 - 0.08^t) / 2.53`）。800ms 下 v0=3200 约滑 1100px（≈1 屏）。 */
private const val FLING_MAX_MS = 800L

/** 跳话提示停留时长（ms）。 */
private const val CHAPTER_TOAST_MS = 2_000L

/**
 * 边界蓄力提示停留时长（ms）：撞到章节边界开始蓄力时屏幕中间提示
 * 「再滚就切话」，停止滚动这么久后蓄力衰减、提示消失（R-46）。
 */
private const val EDGE_HINT_MS = 2_500L

/** 两次跳话的最小间隔（ms）：新话要异步取图，防连续滚轮连环跳。 */
private const val CHAPTER_JUMP_COOLDOWN_MS = 800L

/**
 * 缩放 prefs 的写库防抖（ms）。
 *
 * 滚轮连滚 / 触控板一次滑动会连发十几格，每格一条 `upsertPref` 会把写线程（和
 * 「翻页落进度」共用）堵住。400ms 是"用户停手"的经验值：停手后才落一次，
 * 中途退出阅读器时由 `DisposableEffect` 补写，不会丢。
 */
private const val ZOOM_PREF_DEBOUNCE_MS = 400L
