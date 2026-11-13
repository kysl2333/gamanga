package app.gamanga.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.model.HistoryEntry
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.detail.DetailHost
import app.gamanga.desktop.ui.detail.rememberDetailHostState
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.DockReservedHeight
import app.gamanga.desktop.ui.shell.DragStripHeight
import app.gamanga.desktop.ui.shell.MenuItemRow
import app.gamanga.desktop.ui.shell.MenuSeparator
import app.gamanga.desktop.ui.shell.TextAction
import app.gamanga.desktop.ui.shell.UndoBar
import app.gamanga.desktop.ui.shell.UndoRequest
import app.gamanga.desktop.ui.shell.WindowMenu
import app.gamanga.desktop.ui.shell.rememberUndoState
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 历史页（排期第 4 项，2026-10-27 第五轮重做）。
 *
 * ## 用户口径（2026-10-27 原话）
 *
 * 「历史页的做这左右两排卡片往下滚动的界面，每个卡片是一个动漫的历史信息，
 *   一行 2 个卡片，还是符合现在的 ui 风格，动效是慢慢显示出来」。
 *
 * ## 这一版和上一版的差别
 *
 * | | 上一版 | 这一版 |
 * |---|---|---|
 * | 布局 | `LazyColumn` 单列细行（高 60dp 的封面） | `LazyVerticalGrid` **2 列卡片** |
 * | 配色 | `MaterialTheme.colorScheme`（与全站令牌体系脱节） | 全部走 [Tokens] |
 * | 卡片 | 无卡片，行与行之间只有留白 | 白卡 + 16 圆角 + 细边 + 阴影，hover 抬 3dp |
 * | 动效 | 无 | 进入时按序**淡入 + 上浮**（错峰 55ms，快起长尾曲线） |
 * | 删除 | 直接删 + snackbar | 删 + **6 秒回撤条**（`UndoBar`） |
 *
 * ## 为什么删掉那一列按钮
 *
 * 上一版每行右边挂「继续阅读 / 删除」两个 Material 按钮，一行 2 个按钮 × 20 行 = 40 个
 * 实心描边按钮，视觉噪音比信息还重。卡片化之后：**点卡片 = 继续阅读**（主操作），
 * 卡片底部留一个文字动作做显式提示，删除降级成右下角的垃圾桶图标 + 右键菜单。
 *
 * ## 数据
 *
 * 来自 `readingProgress` 表（进度一直在写，之前只是没有入口）：按 `updatedAt` 倒序，
 * 列出「哪本书 · 哪一话 · 第几页」。点一下回到上次的位置——但阅读器需要**完整章节数组**
 * 才能上一话/下一话，所以要异步取章节列表（见 [HistoryScreen] 里的 `resume`）。
 */

/** 卡片固定高度：152 = 上下内边距 12×2 + 封面 128（3:4 → 宽 96）。 */
private val CardHeight = 152.dp

/** 内容区左右留白（与书架页同一个值，两页切换时标题不横移）。 */
private val PagePaddingH = 40.dp

/** 两列之间的横向间距 / 行间距。 */
private val CardGapH = 16.dp
private val CardGapV = 14.dp

/** 内容最大宽度：窗口再宽也不把卡片拉成一条 900dp 的横条。 */
private val ContentMaxWidth = 1180.dp

/** 错峰：相邻两张卡的启动间隔（毫秒）。 */
private const val RevealStepMs = 55

/** 单张卡片的淡入上浮时长。 */
private const val RevealDurationMs = 460

/** 最多前多少张参与错峰（再多用户会觉得「怎么还没出来」）。 */
private const val MaxStagger = 10

/**
 * 入场曲线 `cubic-bezier(.22,1,.36,1)`：**快起 + 长尾收**。
 *
 * 与启动闪屏（`Main.kt` 的 `SplashEasing`）同一根曲线——用户已经验收过那种「浮上来」的手感。
 * 不用线性：线性淡入像加载失败重试，没有「落定」的感觉。
 */
private val RevealEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun HistoryScreen(
    repository: LibraryRepository,
    onRead: (ReaderTarget) -> Unit,
    /** 详情面板里「用浏览器打开」——本页**不跳页**，直接由壳层盖浏览器。 */
    onOpenBrowser: (url: String, domain: String) -> Unit,
    /** R-04 下载管理器：详情面板的「已缓存」标记与选章下载都读它。 */
    downloadManager: DownloadManager = AppGraph.downloadManager,
) {
    val entries by repository.history.collectAsState()
    var period by remember { mutableStateOf("全部记录") }
    val now = remember { System.currentTimeMillis() }
    val visibleEntries = remember(entries, period) { entries.filter { period == "全部记录" || now - it.updatedAt < if (period == "最近七天") 7L * 86_400_000 else 86_400_000L } }
    val scope = rememberCoroutineScope()
    /** 回撤条（共用件 `ui/shell/UndoBar.kt`）：删除历史可撤销，防误点。 */
    val undo = rememberUndoState()
    /** 正在取章节的书（mangaId）；非 null 时禁用其它点击，避免并发起一堆请求。 */
    var resuming by remember { mutableStateOf<String?>(null) }
    /** 失败原因（内联显示，比提示条更容易看到）。 */
    var error by remember { mutableStateOf<String?>(null) }
    /** 右键菜单的目标（条目 + 按下点的窗口坐标；null = 关）。 */
    var ctx by remember { mutableStateOf<HistoryCtx?>(null) }

    /**
     * 详情二级页宿主（2026-10-27 第六批用户口径）。
     *
     * 原话：「历史页面和下载页面都要在自己页面打开二级详情页，点击漫画卡片就会跳转到
     * 二级详情，点击阅读进入阅读器」。上一版是把用户甩到浏览页去弹详情——回来还要重新
     * 找位置，等于「点一下丢一次上下文」。
     *
     * 与书架页共用同一个宿主（`DetailHost`），所以面板动画、换源、选章下载、
     * 加/移书架这些行为三个页面完全一致，不会各飘一套（UI-SPEC 3.6 的教训）。
     */
    val detail = rememberDetailHostState(repository, downloadManager)

    /** 打开某条历史的详情：标题/封面从这条记录直接带过去，面板第一帧就有内容。 */
    fun openDetail(entry: HistoryEntry) {
        detail.open(
            SourceManga(
                sourceId = entry.sourceMangaId,
                title = entry.title,
                coverUrl = entry.coverUrl,
            ),
            entry.source,
        )
    }

    /**
     * 入场动画是否已经跑完。
     *
     * 为什么需要这个开关：`LazyVerticalGrid` 的 item 是**滚动时才组合**的。
     * 如果每张卡都在组合那一刻开始淡入，那么用户往下滚时，新出现的卡永远在「慢慢浮现」——
     * 一屏一屏地闪，像卡了。所以错峰只在首屏跑一次，之后组合出来的卡直接是 1.0。
     */
    var entranceDone by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) {
            entranceDone = true
            return@LaunchedEffect
        }
        val last = (minOf(entries.size, MaxStagger) - 1).coerceAtLeast(0)
        delay(last.toLong() * RevealStepMs + RevealDurationMs + 80L)
        entranceDone = true
    }

    fun resume(entry: HistoryEntry) {
        if (resuming != null) return
        resuming = entry.mangaId
        error = null
        scope.launch {
            try {
                // **本地优先（2026-10-26 用户实测「第一次点阅读要等很久」）**：
                // 旧实现先 `withContext(IO) { adapter.chapters(...) }` 等网络回来才开阅读器——
                // 拷贝漫画要 8~10 秒（它得先起隐藏浏览器），用户看到的就是「点了没反应」。
                // 而书架里的书**入架时章节就落库了**，本地那份足够撑起阅读器（上一话/下一话都在）。
                // 所以顺序反过来：本地有 → 立刻开；本地空 → 才去网络要。
                val local = repository.chaptersOf(entry.mangaId)
                    .map { c ->
                        app.gamanga.desktop.source.SourceChapter(
                            id = LibraryRepository.sourceChapterIdOf(c.id),
                            name = c.name,
                            index = c.index,
                            pageCount = c.pageCount,
                        )
                    }
                val chapters = if (local.isNotEmpty()) {
                    local
                } else {
                    val adapter = Sources.of(entry.source)
                        ?: error("源「${sourceNameOf(entry.source)}」暂不可用")
                    withContext(Dispatchers.IO) {
                        runCatching { adapter.chapters(entry.sourceMangaId) }.getOrNull().orEmpty()
                    }
                }
                if (chapters.isEmpty()) {
                    error = "没能取到《${entry.title}》的章节列表（源站无响应，且本地没有缓存）"
                } else {
                    // 进度里的 chapterId 是本地复合 id（mangaId#源章节 id）
                    val idx = chapters.indexOfFirst { "${entry.mangaId}#${it.id}" == entry.chapterId }
                    onRead(
                        ReaderTarget(
                            type = entry.source,
                            sourceMangaId = entry.sourceMangaId,
                            title = entry.title,
                            chapters = chapters,
                            startIndex = if (idx >= 0) idx else 0,
                            localMangaId = entry.mangaId,
                            coverUrl = entry.coverUrl,
                        )
                    )
                }
            } catch (e: CancellationException) {
                // 取消（离开页面）不是错误，别写状态
                throw e
            } catch (e: Throwable) {
                error = "打开失败：${e.message ?: e::class.simpleName}"
            } finally {
                resuming = null
            }
        }
    }

    /** 删除一条历史（只删进度行，不动书架与已下载文件），并挂一条 6 秒回撤。 */
    fun deleteEntry(entry: HistoryEntry) {
        repository.deleteProgress(entry.mangaId)
        undo.show(
            UndoRequest("已从历史移除《${entry.title}》") {
                // 回撤写回**原始时间戳**：用 saveProgress 会把时间改成「现在」，
                // 那条记录就跳到列表最前面——用户撤的是「误删」，不是「刚读完」。
                repository.restoreHistory(entry)
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HistoryHeader(count = entries.size)
            Row(Modifier.fillMaxWidth().padding(horizontal = PagePaddingH).padding(bottom = Tokens.Space.Md), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm), verticalAlignment = Alignment.CenterVertically) {
                listOf("全部记录", "最近一天", "最近七天").forEach { label ->
                    app.gamanga.desktop.ui.shell.PageFilter(label, period == label) { period = label }
                }
                Spacer(Modifier.weight(1f))
                MixText("${visibleEntries.size} 部漫画", color = Tokens.Color.TextTertiary, fontSize = Tokens.Type.Caption.size, style = Tokens.Type.Caption.toTextStyle())
            }

            error?.let { msg ->
                MixText(
                    text = msg,
                    color = Tokens.Color.Danger,
                    fontSize = Tokens.Type.Caption.size,
                    style = Tokens.Type.Caption.toTextStyle(),
                    modifier = Modifier.padding(
                        start = PagePaddingH,
                        end = PagePaddingH,
                        bottom = Tokens.Space.Sm,
                    ),
                )
            }

            if (visibleEntries.isEmpty()) {
                EmptyHistory(filtered = entries.isNotEmpty())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(
                            start = PagePaddingH,
                            end = PagePaddingH,
                            top = Tokens.Space.Xs,
                            // 底部给 dock 让位：这是**滚动内容**的一部分，不是固定白带——
                            // 滚到底时最后一行能完整停在 dock 上方，书名不会被永久遮住。
                            bottom = DockReservedHeight + Tokens.Space.Lg,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(CardGapH),
                        verticalArrangement = Arrangement.spacedBy(CardGapV),
                    ) {
                        itemsIndexed(visibleEntries, key = { _, e -> e.mangaId }) { index, entry ->
                            HistoryCard(
                                entry = entry,
                                index = index,
                                entranceDone = entranceDone,
                                busy = resuming == entry.mangaId,
                                enabled = resuming == null,
                                onResume = { resume(entry) },
                                // 2026-10-27 第六批：卡片主键 = 进二级详情
                                // （「点卡片跳转到二级详情，点阅读进入阅读器」）；
                                // 「继续阅读」仍是卡片右下角那颗文字动作。
                                onOpenDetail = { openDetail(entry) },
                                onDelete = { deleteEntry(entry) },
                                onContextMenu = { pos -> ctx = HistoryCtx(entry, pos) },
                            )
                        }
                    }
                }
            }
        }

        // 回撤条：位置（dock 上方 + 一段间距）由 UndoBar 内部统一定义，这里只给「底部居中」
        UndoBar(state = undo, modifier = Modifier.align(Alignment.BottomCenter))

        ctx?.let { target ->
            HistoryContextMenu(
                target = target,
                onDismiss = { ctx = null },
                onResume = {
                    ctx = null
                    resume(target.entry)
                },
                onOpenDetail = {
                    ctx = null
                    openDetail(target.entry)
                },
                onDelete = {
                    ctx = null
                    deleteEntry(target.entry)
                },
            )
        }

        // 详情二级页：宿主自带遮罩 + 底部上滑面板 + 换源弹窗（与书架页/浏览页同一份）。
        // 声明在右键菜单之后 = 画在菜单之上（点卡片进来的面板要盖住一切）。
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
private data class HistoryCtx(val entry: HistoryEntry, val position: Offset)

/**
 * 页头：标题 + 计数 + 一句操作提示。
 *
 * 顶部留 [DragStripHeight]（无边框窗口的拖拽带），否则标题会被拖拽条压住一半——
 * 壳层是纯浮层，不替页面让位（`NormalShell` 文件头第 4 条）。
 */
@Composable
private fun HistoryHeader(count: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = PagePaddingH,
                end = PagePaddingH,
                top = DragStripHeight + Tokens.Space.Lg,
                bottom = Tokens.Space.Md,
            )
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            MixText(
                text = "历史",
                color = Tokens.Color.TextPrimary,
                fontSize = Tokens.Type.Title.size,
                style = Tokens.Type.Title.toTextStyle(),
            )
            Spacer(Modifier.width(Tokens.Space.Md))
            MixText(
                text = if (count == 0) "还没有阅读记录" else "$count 条 · 按最近阅读排序",
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                // 条数会变，用等宽数字（UI-SPEC 4.3 硬约束）
                style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(Tokens.Space.Xs))
        MixText(
            text = "点卡片查看详情 · 「继续阅读」接着上次的位置看 · 右键可移除这条记录",
            color = Tokens.Color.TextTertiary,
            fontSize = Tokens.Type.Caption.size,
            style = Tokens.Type.Caption.toTextStyle(),
        )
    }
}

@Composable
private fun EmptyHistory(filtered: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MixText(
                text = if (filtered) "这段时间没有阅读记录" else "还没有阅读记录",
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Subtitle.size,
                style = Tokens.Type.Subtitle.toTextStyle(),
            )
            Spacer(Modifier.height(Tokens.Space.Sm))
            MixText(
                text = "读过的书会出现在这里，点一下就能接着上次的位置看",
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
            )
        }
    }
}

/**
 * 一张历史卡片：左封面（3:4）+ 右信息 + 底部动作行。
 *
 * ## 交互（三条都不能改，都是踩过坑的）
 *
 * 1. **主键点击 = 进二级详情**（2026-10-27 第六批口径：点卡片跳详情，点「继续阅读」进阅读器）。
 *    不用 `Modifier.clickable`：桌面端它把右键也算点击，
 *    右键会既弹菜单又开详情（书架页 R-09 的教训）。这里显式只认 `isPrimaryPressed`。
 * 2. **右键 = 页面级唯一菜单**。卡片只上报「按下的窗口坐标」，菜单挂在页面层——
 *    每张卡各持一个 Popup 会在滚动时残留。
 * 3. **内层按钮先截胡**：卡片上的「继续阅读 / 垃圾桶」是真按钮（`clickable`），
 *    它们在 Main 阶段先 consume 掉按下事件；卡片自己那层 handler 看到 `isConsumed`
 *    就跳过，否则点垃圾桶会同时触发「继续阅读」。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    index: Int,
    entranceDone: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onResume: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
    onContextMenu: (Offset) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // hover 只抬 3dp（与书架卡同一套）：整页几十张卡同时大动会晕
    val lift by animateDpAsState(
        targetValue = if (hovered) 3.dp else 0.dp,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "historyLift",
    )
    // Keep the shadow static; hover motion only changes the drawing layer.
    var originInWindow by remember { mutableStateOf(IntOffset.Zero) }

    // 入场：初始值就按「错峰是否已经跑完」定，滚到后面的卡直接 1.0，不再延迟淡入
    val appear = remember { Animatable(if (entranceDone) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) {
            delay(index.coerceAtMost(MaxStagger - 1).coerceAtLeast(0).toLong() * RevealStepMs)
            appear.animateTo(1f, tween(RevealDurationMs, easing = RevealEasing))
        }
    }

    val shape = RoundedCornerShape(Tokens.Radius.Card)
    Row(
        Modifier
            .fillMaxWidth()
            .height(CardHeight)
            // 动画值只在 draw 阶段读：alpha/位移变化不重组整张卡
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx() - lift.toPx()
            }
            .onGloballyPositioned { coords ->
                // 1.12 起没有 positionInWindow()，用 localToWindow(零点) 拿窗口坐标
                val o = coords.localToWindow(Offset.Zero)
                originInWindow = IntOffset(o.x.roundToInt(), o.y.roundToInt())
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                // 内层按钮（继续阅读 / 垃圾桶）已经接管这次按下，卡片不再响应
                if (event.changes.any { it.isConsumed }) return@onPointerEvent
                if (event.buttons.isSecondaryPressed) {
                    val press = event.changes.first().position
                    event.changes.forEach { it.consume() }
                    onContextMenu(
                        Offset(
                            originInWindow.x + press.x,
                            originInWindow.y + press.y,
                        )
                    )
                } else if (event.buttons.isPrimaryPressed) {
                    event.changes.forEach { it.consume() }
                    // 卡片主键 = 二级详情；「继续阅读」是右下角那颗文字动作（第六批口径）
                    if (enabled) onOpenDetail()
                }
            }
            .hoverable(interaction)
            .shadow(
                elevation = 4.dp,
                shape = shape,
                clip = false,
                // 阴影用中性黑：彩色阴影会让整页发脏（书架卡同款取值）
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000),
            )
            .clip(shape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, if (hovered) Tokens.Color.Accent.copy(alpha = .24f) else Tokens.Color.BorderHairline, shape)
            .padding(Tokens.Space.Md),
        verticalAlignment = Alignment.Top,
    ) {
        HistoryCover(entry = entry, busy = busy, onClick = onOpenDetail)
        Spacer(Modifier.width(Tokens.Space.Md))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MixText(
                text = entry.title,
                color = Tokens.Color.TextPrimary,
                fontSize = Tokens.Type.Subtitle.size,
                style = Tokens.Type.Subtitle.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            MixText(
                text = buildString {
                    append(entry.chapterName ?: "（章节名未记录）")
                    if (entry.totalPages > 0) append(" · 第 ${entry.page + 1}/${entry.totalPages} 页")
                },
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            MixText(
                text = "${sourceNameOf(entry.source)} · ${relativeTime(entry.updatedAt)}",
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    text = if (busy) "打开中…" else "继续阅读",
                    onClick = { if (enabled) onResume() },
                    idleColor = Tokens.Color.Accent,
                )
                Spacer(Modifier.weight(1f))
                if (entry.totalPages > 0) {
                    MixText(
                        text = "${entry.percent}%",
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Caption.size,
                        // 百分比会变，等宽数字（UI-SPEC 4.3）
                        style = Tokens.Type.Caption.toTextStyle()
                            .copy(fontFeatureSettings = "tnum"),
                    )
                    Spacer(Modifier.width(Tokens.Space.Sm))
                }
                DeleteIconButton(enabled = enabled, onClick = onDelete)
            }
        }
    }
}

/**
 * 只认主键的点击（桌面端专用）。
 *
 * 为什么不用 `Modifier.clickable`：桌面端它把右键也算点击——封面右键会先打开详情页，
 * 卡片那层的右键菜单就永远轮不到（书架页 R-09 的同一个坑）。这里显式只认 `isPrimaryPressed`，
 * 并且**只在事件还没被更深的子件消费时**才触发（`isConsumed` 判断）。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.primaryPress(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Press) { event ->
        if (!enabled) return@onPointerEvent
        if (event.buttons.isPrimaryPressed && event.changes.none { it.isConsumed }) {
            event.changes.forEach { it.consume() }
            onClick()
        }
    }

/**
 * 封面：3:4、圆角 10、底部一条白色进度。
 *
 * 点封面 = 进详情页（用户 2026-10-24 定的口径：封面是「这本书」，标题行是「这一话」）。
 * 封面走本地缓存（`CoverCache`）：源站删图/改域名之后历史页还能显示。
 */
@Composable
private fun HistoryCover(entry: HistoryEntry, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.Control)
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(Tokens.Color.ImagePlaceholder)
            .primaryPress(onClick = onClick),
    ) {
        if (entry.coverUrl != null) {
            var coverUri by remember(entry.coverUrl) {
                mutableStateOf(CoverCache.localUri(entry.coverUrl))
            }
            LaunchedEffect(entry.coverUrl) {
                coverUri = CoverCache.resolve(entry.coverUrl)
            }
            RemoteImage(
                url = coverUri ?: entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // 底部进度条：黑 20% 轨道 + 白条（与书架卡同一套观感）
        if (entry.percent > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33000000)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(entry.percent / 100f)
                        .fillMaxSize()
                        .background(Color.White),
                )
            }
        }

        // 取章节中：白色半透明蒙层 + 转圈，避免「点了没反应」的错觉
        if (busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCCFFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                LoadingArc(size = 20.dp, color = Tokens.Color.Accent, strokeWidth = 2.dp)
            }
        }
    }
}

/** 垃圾桶：28dp 圆形，hover 才浮出淡红底 + 红图标（常驻红点太吵）。 */
@Composable
private fun DeleteIconButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = hovered && enabled
    val bg by animateColorAsState(
        targetValue = if (active) Tokens.Color.DangerSoft else Color.Transparent,
        animationSpec = tween(140),
        label = "historyDelBg",
    )
    val tint by animateColorAsState(
        targetValue = if (active) Tokens.Color.Danger else Tokens.Color.TextTertiary,
        animationSpec = tween(140),
        label = "historyDelTint",
    )
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg)
            .hoverable(interaction)
            // 同样只认主键：右键点垃圾桶要弹菜单，不是直接删（没有二次确认的动作更不能误触）
            .primaryPress(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DockIcons.Trash,
            contentDescription = "从历史移除",
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 右键菜单（页面级唯一实例）。
 *
 * 定位沿用书架页的教训（R-09）：`DropdownMenu` 的 `offset` 是相对锚点的另一套坐标空间，
 * 喂格子内坐标会弹到窗口别处。这里直接把**窗口绝对坐标**当菜单左上角。
 */
@Composable
private fun HistoryContextMenu(
    target: HistoryCtx,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    WindowMenu(position = target.position, onDismiss = onDismiss) {
        MenuItemRow(label = "继续阅读", icon = DockIcons.Play, danger = false, onClick = onResume)
        MenuItemRow(label = "查看详情", icon = DockIcons.Enter, danger = false, onClick = onOpenDetail)
        MenuSeparator()
        MenuItemRow(label = "从历史移除", icon = DockIcons.Trash, danger = true, onClick = onDelete)
    }
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期。
 *
 * 用 `System.currentTimeMillis()` 而不是 `Instant.now()`——项目只跑 JVM 桌面端，
 * 少一层时间库依赖。
 */
internal fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return "时间未知"
    val delta = (now - timestamp).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}
