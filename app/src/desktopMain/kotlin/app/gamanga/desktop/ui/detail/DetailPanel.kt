package app.gamanga.desktop.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.DownloadStatus
import app.gamanga.desktop.data.DownloadTask
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.ChapterKind
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.browserUrl
import app.gamanga.desktop.source.domainOf
import app.gamanga.desktop.source.resolveChapterKinds
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.ChapterRow
import app.gamanga.desktop.ui.ReaderTarget
import app.gamanga.desktop.ui.RemoteImage
import app.gamanga.desktop.ui.chapterDisplayIndex
import app.gamanga.desktop.ui.chapterRows
import app.gamanga.desktop.ui.rowIndexOfChapter
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.MenuItemRow
import app.gamanga.desktop.ui.shell.MenuSeparator
import app.gamanga.desktop.ui.shell.TextAction
import app.gamanga.desktop.ui.shell.WindowMenu
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 封面「漂浮」的最大位移（用户口径「轻微跟随」，幅度必须小）。 */
private val CoverFloatDistance = 6.dp

/** 封面跟随的倾角（度）。1.2° 刚好能看出「有生命」，再多就是廉价动效。 */
private const val CoverFloatTilt = 1.2f

/** 面板底部给浮空 dock 留的高度（`NormalShell.DockReservedHeight` 88 + 一圈余量）。 */
private val DockSafeInset = 96.dp

/** 面板宽度低于这个值就把右侧进度栏折到信息区下面，不硬挤。 */
private val NarrowPanelWidth = 720.dp

/** 进度栏宽度（原型 `detail-3x2.html` 定的 172）。 */
private val ProgressRailWidth = 172.dp

/** 面板内容左右留白。右下角浮钮也要跟这个数对齐，否则会看出「差一点点」。 */
private val PanelSidePadding = 28.dp

/** 章节格高度。骨架必须跟它一致，否则数据到达时列表会跳一下。 */
private val ChapterCellHeight = 58.dp

/** 章节网格底部为右下角「下载」浮钮让出的高度（40dp 按钮 + 上下呼吸）。 */
/**
 * 详情二级页的内容（F-133，2026-10-27 从「右侧整幅滑入」改为「底部中间上滑面板」）。
 *
 * ## 三段式（`docs/ui/prototype/detail.md`）
 *
 * 1. **封面 + 信息**：封面严格 3:4、圆角 [Tokens.Radius.Card]（与书架卡/搜索卡同一颗令牌），
 *    右侧是元信息 + 简介 + 操作行；
 * 2. **进度栏**：172dp 窄栏（面板窄时折到信息区下方），只给百分比 / 细条 / 三个计数；
 * 3. **章节区**：5 列网格，整片章节共用**一层外框**（用户 2026-10-27：「减少方框的出现」，
 *    逐章加框在 5 列 × 上百话时会变成一面格子墙，视觉噪音压过章节名本身）。
 *
 * ## 为什么状态几乎都靠「传进来」而不是自己持有
 *
 * `downloadMode` / `downloadSelected` / 章节顺序必须跟着「换一本书」复位，
 * 而复位点（[app.gamanga.desktop.ui.BrowseScreen] 的 `openDetail` / `search`）在页面层。
 * 状态留在页面层、面板只做渲染，才不会出现「换本书还停在上本的多选态」。
 *
 * 面板自己只持有三类**纯局部**状态：右键菜单锚点、封面保存提示、封面漂浮量。
 */
@Composable
internal fun DetailPanel(
    manga: SourceManga,
    sourceType: SourceType,
    repository: LibraryRepository,
    chapters: List<SourceChapter>,
    cachedPageCounts: Map<String, Int>,
    downloadTasks: Map<String, DownloadTask>,
    chapterDesc: Boolean,
    onToggleOrder: () -> Unit,
    downloadMode: Boolean,
    downloadSelected: Set<String>,
    /** 页面层给的内联提示（入队 / 进多选），4 秒后由页面层清掉。 */
    hint: String?,
    chapterNotice: String?,
    onRefresh: () -> Unit,
    /** 章节还在拉取中（面板已弹出但数据未到）——章节区显示骨架，而不是「章节列表还没取到」。 */
    loading: Boolean = false,
    onClose: () -> Unit,
    onRead: (ReaderTarget) -> Unit,
    onAddToShelf: () -> Unit,
    onRemoveFromShelf: () -> Unit,
    onEnterDownloadMode: () -> Unit,
    onExitDownloadMode: () -> Unit,
    onToggleSelected: (chapterId: String) -> Unit,
    onSelectAllUncached: () -> Unit,
    /** 全选全部章节（2026-10-30 用户要求 2）。 */
    onSelectAll: () -> Unit = {},
    /** 取消全选（清空勾选）。 */
    onClearSelection: () -> Unit = {},
    /** 右键「多选章节」：带着点中的那一章进多选（用户要求 3）。 */
    onEnterMultiSelect: (chapterId: String) -> Unit = {},
    /** 批量标记已读（true）/ 未读（false）（用户要求 3）。 */
    onMarkSelectedRead: (Boolean) -> Unit = {},
    onStartDownload: () -> Unit,
    onEnqueueChapter: (SourceChapter) -> Unit,
    onRetryDownload: (chapterLocalId: String) -> Unit,
    onOpenBrowser: (url: String, domain: String) -> Unit,
    /** F-45：换源入口（源站挂了就把这本书迁到别的源）。 */
    onMigrate: () -> Unit,
    panelWidth: Dp,
) {
    val localId = "${sourceType.name.lowercase()}:${manga.sourceId}"
    val libraryItems by repository.libraryItems.collectAsState()
    val inShelf = libraryItems.any { it.entry.id == localId }
    // 进度版本：阅读器每写一次进度就自增，放进 key 才能「从阅读器返回」时重读。
    val progressTick by repository.progressVersion.collectAsState()
    val saved = remember(localId, chapters.size, inShelf, progressTick) {
        repository.progressOf(localId)
    }
    // F-39：章节四态（未点过 / 点过 / 正在读 / 已读）。
    val chapterStates = remember(localId, chapters.size, inShelf, progressTick) {
        repository.chaptersOf(localId).associateBy { it.id }
    }

    // ---- 章节类型（话 / 卷 / 番外）：用户 2026-11-11 反馈「话和卷都在一起了」----
    // 有些源同一本书既有「连载话」又有「单行本卷」，两边编号都是 01/02/03…
    // 全塞进一个列表就是 01(52页)/01(192页)/02… 交错。这里先按
    // 源自带类型 → 名字标记 → 页数证据 把每条判成 话/卷/番外，
    // 再让用户按类型筛选，默认只看「话」。
    val kindChapters = remember(chapters) { resolveChapterKinds(chapters) }
    val volumeCount = kindChapters.count { !it.navigationOnly && it.kind == ChapterKind.VOLUME }
    val extraCount = kindChapters.count { !it.navigationOnly && it.kind == ChapterKind.EXTRA }
    val chapterCount = kindChapters.count { !it.navigationOnly } - volumeCount - extraCount
    // 有卷才需要筛选；只有话的书不显示这组控件（不给用户多出来的一个选择）。
    val hasVolume = volumeCount > 0 && chapterCount > 0
    var showVolumes by remember(chapters) { mutableStateOf(false) }

    val baseChapters = remember(kindChapters, showVolumes, hasVolume) {
        when {
            !hasVolume -> kindChapters
            showVolumes -> kindChapters.filter { it.kind == ChapterKind.VOLUME || it.navigationOnly }
            // 看「话」时把番外一并带上：番外不属于卷，也不该被藏掉。
            else -> kindChapters.filter { it.kind != ChapterKind.VOLUME || it.navigationOnly }
        }
    }
    val savedIndex = saved?.let { p ->
        kindChapters.indexOfFirst { "$localId#${it.id}" == p.chapterId }
    } ?: -1
    val startAt = savedIndex.coerceAtLeast(0)
    // R-08：定位 + 高亮共用「显示下标」——倒序时列表是镜像的，用 savedIndex 会定位到对称那头。
    // 筛选生效时下标要按**筛选后的列表**重算，否则会滚到别的话上。
    val savedDisplayIndex = remember(baseChapters, saved, chapterDesc) {
        val inBase = baseChapters.indexOfFirst { "$localId#${it.id}" == saved?.chapterId }
        if (inBase < 0) -1 else chapterDisplayIndex(inBase, baseChapters.size, chapterDesc)
    }
    val displayChapters = remember(baseChapters, chapterDesc) {
        if (chapterDesc) baseChapters.asReversed() else baseChapters
    }
    val chapterRowList = remember(displayChapters) {
        // 兜底：源站解析出重复章节 id 会让网格的 key 冲突直接闪退（MangaBZ 实测过）。
        chapterRows(displayChapters.distinctBy { it.id })
    }
    val savedRowIndex = rowIndexOfChapter(chapterRowList, savedDisplayIndex)

    val gridState = rememberLazyGridState()
    // 打开详情（章节到手）/ 切正倒序时滚到当前话，否则倒序后当前话跑出屏幕。
    // 网格的 item 下标 = 章节行下标（Gap 也是 item），rowIndexOfChapter 已经算好，直接复用。
    LaunchedEffect(chapters, chapterDesc, savedRowIndex) {
        if (savedRowIndex >= 0) gridState.scrollToItem(savedRowIndex)
    }

    // ---- 封面漂浮：面板级监听鼠标，算出相对面板中心的归一化偏移（-1..1）----
    val floatX = remember { Animatable(0f) }
    val floatY = remember { Animatable(0f) }
    val floatSpec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
    val panelInteraction = remember { MutableInteractionSource() }
    val panelHovered by panelInteraction.collectIsHoveredAsState()
    val floatScope = rememberCoroutineScope()
    // 用 hoverable 判「离开面板」而不是靠 Exit 指针事件：hoverable 的进出判定最稳，
    // 鼠标滑到子元素（章节格）上时父节点仍然算 hovered，不会误回中。
    LaunchedEffect(panelHovered) {
        if (!panelHovered) {
            launch { floatX.animateTo(0f, floatSpec) }
            launch { floatY.animateTo(0f, floatSpec) }
        }
    }

    // ---- 局部状态 ----
    var menuChapterId by remember { mutableStateOf<String?>(null) }
    // 右键菜单锚点是**窗口绝对坐标**（不是相对格子的 DpOffset）：
    // 2026-10-27 第五轮用户「详情页右键还没修改为现在风格的右键菜单栏」——
    // 换成项目统一的 `WindowMenu` 之后，它自己按窗口坐标定位（和书架卡右键同一套），
    // 于是这里必须把落点换算成窗口坐标再传进去。
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    var coverHint by remember { mutableStateOf<String?>(null) }
    var coverHintFailed by remember { mutableStateOf(false) }
    LaunchedEffect(coverHint) {
        if (coverHint != null) {
            delay(4000)
            coverHint = null
        }
    }

    val readCount = chapterStates.values.count { it.read }
    val downloadedCount = downloadTasks.values.count {
        it.mangaId == localId && it.status == DownloadStatus.COMPLETED
    }
    /**
     * 百分比按「已读话数」算（原型口径：112/292 → 38%），没有已读记录时退回「读到第几话」——
     * 否则刚点开一本书、一话都没标已读时显示 0%，但下面明明写着「第 283 话」。
     */
    val percent = when {
        chapters.isEmpty() -> 0
        readCount > 0 -> readCount * 100 / chapters.size
        savedIndex >= 0 -> (savedIndex + 1) * 100 / chapters.size
        else -> 0
    }
    val narrow = panelWidth < NarrowPanelWidth
    val coverWidth = (panelWidth * 0.22f).coerceIn(180.dp, 240.dp)

    Box(
        Modifier
            .fillMaxSize()
            .hoverable(panelInteraction)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Move) continue
                        val p = e.changes.firstOrNull()?.position ?: continue
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) continue
                        val nx = (p.x / w * 2f - 1f).coerceIn(-1f, 1f)
                        val ny = (p.y / h * 2f - 1f).coerceIn(-1f, 1f)
                        // 变化太小时不重启动画：Move 每秒上百条，无谓地取消/重启会让漂浮发抖
                        if (abs(nx - floatX.value) > 0.01f) {
                            floatScope.launch { floatX.animateTo(nx, floatSpec) }
                        }
                        if (abs(ny - floatY.value) > 0.01f) {
                            floatScope.launch { floatY.animateTo(ny, floatSpec) }
                        }
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = PanelSidePadding)
                .padding(top = 10.dp)
                // dock 浮在窗口底部中间，会压住面板下沿 —— 内容整体让出 96dp
                .padding(bottom = DockSafeInset)
        ) {
            // ---- 顶部行：返回 + 书名 ----
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailBackButton(onClick = onClose)
                Spacer(Modifier.width(12.dp))
                MixText(
                    text = manga.title,
                    style = Tokens.Type.Subtitle.toTextStyle(),
                    color = Tokens.Color.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---- 第一段：封面 + 信息（+ 宽面板时的进度栏）----
            Row(Modifier.fillMaxWidth()) {
                DetailCover(
                    manga = manga,
                    sourceType = sourceType,
                    width = coverWidth,
                    // 传 lambda 而不是 Float：让 `.value` 在 graphicsLayer 的**绘制阶段**才读，
                    // 否则鼠标每动一帧都会重组整个面板（含几百个章节格），必然掉帧。
                    floatX = { floatX.value },
                    floatY = { floatY.value },
                    onSaved = { name ->
                        coverHint = "已保存封面：$name"
                        coverHintFailed = false
                    },
                    onFailed = { msg ->
                        coverHint = msg
                        coverHintFailed = true
                    },
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    MixText(
                        text = listOfNotNull(sourceNameOf(sourceType), manga.status)
                            .joinToString(" · "),
                        style = Tokens.Type.Caption.toTextStyle(),
                        color = Tokens.Color.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    MixText(
                        text = manga.title,
                        style = Tokens.Type.Title.toTextStyle(),
                        color = Tokens.Color.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    MixText(
                        text = listOfNotNull(manga.author, manga.year?.toString())
                            .joinToString(" · ").ifBlank { "-" },
                        style = Tokens.Type.Body.toTextStyle(),
                        color = Tokens.Color.TextSecondary,
                    )
                    if (manga.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // 标签不是链接（点了没反应），用强调蓝会让人误以为可点 —— 走次要灰
                        MixText(
                            text = manga.tags.take(6).joinToString("  "),
                            style = Tokens.Type.Body.toTextStyle(),
                            color = Tokens.Color.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    manga.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(10.dp))
                        MixText(
                            text = it,
                            style = Tokens.Type.Body.toTextStyle(),
                            color = Tokens.Color.TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    DetailActions(
                        inShelf = inShelf,
                        hasChapters = chapters.isNotEmpty(),
                        loading = loading,
                        downloadMode = downloadMode,
                        continueLabel = if (savedIndex >= 0) {
                            "继续阅读 · ${chapters[savedIndex].name}"
                        } else {
                            "开始阅读"
                        },
                        onRead = {
                            onRead(
                                ReaderTarget(
                                    type = sourceType,
                                    sourceMangaId = manga.sourceId,
                                    title = manga.title,
                                    chapters = chapters,
                                    startIndex = startAt.coerceIn(
                                        0, chapters.lastIndex.coerceAtLeast(0)
                                    ),
                                    localMangaId = localId,
                                    // 2026-10-30：阅读器顶栏的小封面读这个值。
                                    // 原来这条（最常用的「开始阅读/继续阅读」）漏了它，
                                    // 于是顶栏只剩一个灰占位块。
                                    coverUrl = manga.coverUrl,
                                )
                            )
                        },
                        onAdd = onAddToShelf,
                        onRemove = onRemoveFromShelf,
                        onDownload = onEnterDownloadMode,
                        onCancelDownload = onExitDownloadMode,
                        onBrowser = {
                            onOpenBrowser(
                                browserUrl(sourceType, manga.sourceId),
                                domainOf(sourceType),
                            )
                        },
                        onMigrate = onMigrate,
                    )
                    // 章节缺失汇总（与网格里的 Gap 行同源同规则）：详情页就能看到缺了哪几话
                    val gaps = remember(chapterRowList) {
                        chapterRowList.filterIsInstance<ChapterRow.Gap>()
                    }
                    if (gaps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        // 缺章是**信息**不是错误（源站本来就没这几话），红字会让整页刺眼
                        MixText(
                            text = "缺 ${gaps.size} 处章节：" +
                                gaps.joinToString("、") { it.text.removePrefix("此处缺失 ") },
                            style = Tokens.Type.Caption.toTextStyle(),
                            color = Tokens.Color.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    hint?.let {
                        Spacer(Modifier.height(8.dp))
                        MixText(
                            text = it,
                            style = Tokens.Type.Caption.toTextStyle(),
                            color = Tokens.Color.Accent,
                        )
                    }
                    coverHint?.let {
                        Spacer(Modifier.height(8.dp))
                        MixText(
                            text = it,
                            style = Tokens.Type.Caption.toTextStyle(),
                            // 成功走次要灰，只有真失败才用红
                            color = if (coverHintFailed) Tokens.Color.Danger else Tokens.Color.TextSecondary,
                        )
                    }
                }
                if (!narrow) {
                    Spacer(Modifier.width(20.dp))
                    ProgressRail(
                        percent = percent,
                        chapterLabel = if (savedIndex >= 0) chapters[savedIndex].name else "未开始",
                        total = chapters.size,
                        readCount = readCount,
                        downloadedCount = downloadedCount,
                        modifier = Modifier.width(ProgressRailWidth),
                    )
                }
            }

            if (narrow) {
                Spacer(Modifier.height(16.dp))
                ProgressRail(
                    percent = percent,
                    chapterLabel = if (savedIndex >= 0) chapters[savedIndex].name else "未开始",
                    total = chapters.size,
                    readCount = readCount,
                    downloadedCount = downloadedCount,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- 第三段：章节区 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MixText(
                    text = when {
                        // 加载中先说「加载中」：写「章节 0 话」会让用户以为这本书是空的
                        loading && chapters.isEmpty() -> "章节加载中…"
                        chapters.any { it.navigationOnly } -> "章节目录未取全"
                        volumeCount > 0 && chapterCount > 0 -> "$chapterCount 话 · $volumeCount 卷"
                        volumeCount > 0 -> "$volumeCount 卷"
                        extraCount > 0 -> "章节 $chapterCount 话 · $extraCount 番外"
                        else -> "章节 $chapterCount 话"
                    },
                    style = Tokens.Type.Subtitle.toTextStyle(),
                    color = Tokens.Color.TextPrimary,
                )
                if (chapterNotice != null && !loading) {
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    MixText(
                        text = chapterNotice,
                        style = Tokens.Type.Caption.toTextStyle(),
                        color = Tokens.Color.TextSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                // ---- 话 / 卷 滑动开关（用户 2026-11-11：先要能分开，后给了玻璃拟态开关参考图）----
                // 只有「有话也有卷」的书才出现：单行本类、纯连载类的书不需要多一个选择。
                if (hasVolume && !loading) {
                    ChapterKindSwitch(showVolumes = showVolumes, onChange = { showVolumes = it })
                    Spacer(Modifier.width(Tokens.Space.Sm))
                }
                if (!loading) TextAction(
                    text = "刷新",
                    onClick = onRefresh,
                    idleColor = Tokens.Color.Accent,
                )
                // 正序/倒序只影响显示顺序；ReaderTarget 始终传升序列表，否则「下一话」会往回走。
                TextAction(
                    text = if (chapterDesc) "倒序 ↓" else "正序 ↑",
                    onClick = onToggleOrder,
                    idleColor = Tokens.Color.Accent,
                )
            }
            if (downloadMode) {
                DownloadToolbar(
                    selectedCount = downloadSelected.size,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onSelectAllUncached = onSelectAllUncached,
                    onMarkRead = { onMarkSelectedRead(true) },
                    onMarkUnread = { onMarkSelectedRead(false) },
                    onStart = onStartDownload,
                    onCancel = onExitDownloadMode,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 章节区：**不再套灰色容器**（用户 2026-10-28：「灰色框去掉，只给白色话数框加阴影」）。
            // 层级改由每张白卡自己的常驻投影承担——阴影比底色差更轻，也不会在面板里
            // 再切出一块灰色矩形。hover / 当前话的强调色描边仍保留（状态指示，不是静态边框）。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = Tokens.Space.Sm),
            ) {
                when {
                    chapterRowList.isNotEmpty() -> LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
                        // end 留给滚动条（否则最后一列贴着条）
                        contentPadding = PaddingValues(end = 10.dp),
                    ) {
                        itemsIndexed(
                            items = chapterRowList,
                            key = { _, row -> row.key },
                            span = { _, row ->
                                if (row is ChapterRow.Gap) GridItemSpan(maxLineSpan)
                                else GridItemSpan(1)
                            },
                        ) { i, row ->
                            when (row) {
                                // R-55：编号跳空提示整行占满 5 列
                                is ChapterRow.Gap -> Box(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MixText(
                                        text = row.text,
                                        style = Tokens.Type.Caption.toTextStyle(),
                                        color = Tokens.Color.TextTertiary,
                                    )
                                }

                                is ChapterRow.Item -> {
                                    val ch = row.chapter
                                    val st = chapterStates["$localId#${ch.id}"]
                                    val dlTask = downloadTasks["$localId#${ch.id}"]
                                    val pages = if (ch.pageCount > 0) ch.pageCount
                                    else cachedPageCounts[ch.id] ?: 0
                                    ChapterCell(
                                        chapter = ch,
                                        current = i == savedRowIndex,
                                        opened = (st?.lastOpenedAt ?: 0L) > 0L,
                                        read = st?.read == true,
                                        dlTask = dlTask,
                                        pages = pages,
                                        downloadMode = downloadMode,
                                        checked = ch.id in downloadSelected,
                                        onToggle = { onToggleSelected(ch.id) },
                                        onOpen = {
                                            onRead(
                                                ReaderTarget(
                                                    type = sourceType,
                                                    sourceMangaId = manga.sourceId,
                                                    title = manga.title,
                                                    chapters = chapters,
                                                    // **按 id 反查数组下标**，不能用 ch.index：
                                                    // 源站 index 是倒序前的序号，reversed 后没重算
                                                    startIndex = chapters
                                                        .indexOfFirst { it.id == ch.id }
                                                        .coerceIn(
                                                            0,
                                                            chapters.lastIndex.coerceAtLeast(0)
                                                        ),
                                                    localMangaId = localId,
                                                    coverUrl = manga.coverUrl,
                                                )
                                            )
                                        },
                                        onContext = { windowPoint ->
                                            menuAnchor = windowPoint
                                            menuChapterId = ch.id
                                        },
                                        onRetry = { onRetryDownload("$localId#${ch.id}") },
                                        menu = {
                                            // 项目统一的右键菜单（2026-10-27 第五轮用户：
                                            // 「详情页右键还没修改为现在风格的右键菜单栏」）。
                                            // 之前是 Material3 的 `DropdownMenu`：圆角 4dp、无图标、
                                            // 无 hover 底色 —— 和书架卡的 `WindowMenu` 一眼能看出是两套东西。
                                            if (menuChapterId == ch.id) {
                                                WindowMenu(
                                                    position = menuAnchor,
                                                    onDismiss = { menuChapterId = null },
                                                ) {
                                                    MenuItemRow(
                                                        label = "开始阅读",
                                                        icon = DockIcons.Play,
                                                        danger = false,
                                                    ) {
                                                        menuChapterId = null
                                                        onRead(
                                                            ReaderTarget(
                                                                type = sourceType,
                                                                sourceMangaId = manga.sourceId,
                                                                title = manga.title,
                                                                chapters = chapters,
                                                                startIndex = chapters
                                                                    .indexOfFirst { it.id == ch.id }
                                                                    .coerceIn(
                                                                        0,
                                                                        chapters.lastIndex.coerceAtLeast(0)
                                                                    ),
                                                                localMangaId = localId,
                                                                coverUrl = manga.coverUrl,
                                                            )
                                                        )
                                                    }
                                                    MenuSeparator()
                                                    MenuItemRow(
                                                        label = "标记已读",
                                                        icon = DockIcons.Check,
                                                        danger = false,
                                                    ) {
                                                        menuChapterId = null
                                                        // R-159：带上章节名与序号——库里还没有这一行时
                                                        // 由仓储补一行幽灵章节，否则右键完全没反应
                                                        repository.setChapterRead(
                                                            "$localId#${ch.id}", true, ch.name, ch.index,
                                                        )
                                                    }
                                                    MenuItemRow(
                                                        label = "标记未读",
                                                        icon = DockIcons.Pick,
                                                        danger = false,
                                                    ) {
                                                        menuChapterId = null
                                                        // 已读 + 点过一起清，章节恢复原色（F-39）
                                                        repository.setChapterRead(
                                                            "$localId#${ch.id}", false, ch.name, ch.index,
                                                        )
                                                    }
                                                    MenuSeparator()
                                                    // 2026-10-30 用户要求 3：「章节列表支持通过右键进入多选模式」。
                                                    // 进的是**同一个**勾选界面（下载用的那套），只是工具条多了
                                                    // 标记已读/未读；点中的这一章进来就是勾上的状态。
                                                    MenuItemRow(
                                                        label = "多选章节",
                                                        icon = DockIcons.Pick,
                                                        danger = false,
                                                    ) {
                                                        menuChapterId = null
                                                        onEnterMultiSelect(ch.id)
                                                    }
                                                    MenuItemRow(
                                                        label = "下载本章",
                                                        icon = DockIcons.Download,
                                                        danger = false,
                                                    ) {
                                                        menuChapterId = null
                                                        onEnqueueChapter(ch)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 骨架也走同一个外框：数据到达时只是框内内容替换，框线不动，避免「先弹一个空框再跳」
                    loading -> ChapterSkeleton()

                    else -> MixText(
                        text = "章节列表还没取到",
                        style = Tokens.Type.Caption.toTextStyle(),
                        color = Tokens.Color.TextTertiary,
                    )
                }
                if (chapterRowList.isNotEmpty()) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(gridState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 左上角返回钮（**36dp** 圆，hover 变强调色浅底）。
 *
 * 尺寸 26 → 36（2026-10-27 第四轮用户：「这种触碰出现蓝色范围的都做大，要大气点要符合规范」）：
 * 26dp 的热区只有半个指头宽，hover 出来的蓝圈也小得像装饰。36dp 是桌面端最小舒适热区，
 * 蓝圈的直径也跟着上来了，才读得出「这是个按钮」。
 */
@Composable
private fun DetailBackButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DockIcons.Back,
            contentDescription = "返回",
            tint = if (hovered) Tokens.Color.Accent else Tokens.Color.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 封面（严格 3:4、圆角 [Tokens.Radius.Card]）+ 下方的一层极淡落影。
 *
 * 三件事叠在这一块上：
 * 1. **漂浮**：位移量由面板算好传进来（[floatX]/[floatY]），这里只负责套 `graphicsLayer`。
 *    幅度 6dp / 1.2°，超过就变成「晃」，用户要的是「轻微跟随」。
 * 2. **落影**：交给 [CoverDropShadow]，它只跟横向漂移，不跟倾角。
 * 3. **右键菜单**：右键**不再直接弹** `JFileChooser`——系统文件框是模态、抢焦点的重动作，
 *    右键误触时非常恼人。现在先出菜单，由用户点「保存图片到本地」再弹。
 */
@Composable
private fun DetailCover(
    manga: SourceManga,
    sourceType: SourceType,
    width: Dp,
    floatX: () -> Float,
    floatY: () -> Float,
    onSaved: (fileName: String) -> Unit,
    onFailed: (message: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var coverUri by remember(manga.coverUrl) { mutableStateOf(CoverCache.localUri(manga.coverUrl)) }
    LaunchedEffect(manga.coverUrl) { coverUri = CoverCache.resolve(manga.coverUrl) }

    // 右键菜单锚点 = **窗口绝对坐标**（跟章节格右键菜单同一套做法）
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    var originInWindow by remember { mutableStateOf(IntOffset.Zero) }

    fun saveCover() {
        // JFileChooser 必须同步弹在主线程（Compose 主线程就是 EDT），
        // 与设置页「更改缓存位置」一致：阻塞到用户选完，取消就什么都不做。
        val target = chooseCoverSaveFile(defaultCoverFileName(manga.title)) ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                writeCoverTo(manga.coverUrl, sourceType, target)
            }
            result.fold(
                onSuccess = { onSaved(target.name) },
                onFailure = { onFailed("封面保存失败：${it.message ?: "未知原因"}") },
            )
        }
    }

    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .graphicsLayer {
                    translationX = CoverFloatDistance.toPx() * floatX()
                    translationY = CoverFloatDistance.toPx() * floatY()
                    rotationZ = CoverFloatTilt * floatX()
                }
                .clip(RoundedCornerShape(Tokens.Radius.Card))
                .onGloballyPositioned { coords ->
                    val o = coords.localToWindow(Offset.Zero)
                    originInWindow = IntOffset(o.x.roundToInt(), o.y.roundToInt())
                }
                .pointerInput(manga.coverUrl, manga.title) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Press || !e.buttons.isSecondaryPressed) continue
                            val at = e.changes.first().position
                            menuAnchor = Offset(originInWindow.x + at.x, originInWindow.y + at.y)
                            menuOpen = true
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            RemoteImage(
                url = coverUri ?: manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 项目统一的右键菜单（2026-10-27 第五轮）：与书架卡右键同一套观感
            if (menuOpen) {
                WindowMenu(position = menuAnchor, onDismiss = { menuOpen = false }) {
                    MenuItemRow(
                        label = "保存图片到本地",
                        icon = DockIcons.Picture,
                        danger = false,
                    ) {
                        menuOpen = false
                        saveCover()
                    }
                }
            }
        }
        CoverDropShadow(
            floatX = floatX,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * 封面下的一团「几乎看不见」的落影。
 *
 * 为什么不用 `Modifier.shadow`：elevation 阴影是**矩形轮廓**的高斯模糊，铺在这张圆角封面上
 * 会从两侧漏出直线，看起来像一张贴纸；这里要的是「书压在桌上」那种没有边界的软阴影。
 *
 * 参数全部取保守值（峰值 11% 黑、微点 4.5% 黑）：用户明确要「不明显」，
 * 一旦能看出这是个什么形状，就从质感变成了廉价特效。
 */
@Composable
private fun CoverDropShadow(floatX: () -> Float, modifier: Modifier = Modifier) {
    // 固定种子：重组时点阵不重新撒，否则会「闪」
    val dots = remember {
        val rnd = Random(20261027)
        List(14) {
            val angle = rnd.nextFloat() * 6.2832f
            // sqrt 让点在圆内均匀分布，而不是全挤在圆心
            val dist = sqrt(rnd.nextFloat()) * 0.82f
            Offset(cos(angle) * dist, sin(angle) * dist)
        }
    }
    val ink = Tokens.Color.TextPrimary
    Canvas(
        modifier
            // 宽度只取封面的 72%：和封面同宽会显得像「底座」，收窄才像自然投影
            .fillMaxWidth(0.72f)
            .height(10.dp)
            // 上移 3dp，让阴影「压」在封面底边下面，而不是从底边才开始
            .offset(y = (-3).dp)
            .graphicsLayer {
                // 只跟横向漂移（系数 0.4）：投影若跟着 3D 倾角一起转，会脱离封面「飞」出去
                translationX = CoverFloatDistance.toPx() * floatX() * 0.4f
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width / 2f
        // 纵向压扁到画布高度：radialGradient 只有一个半径，靠 scale 得到真正的椭圆，
        // 否则渐变会在上下边缘被硬切出一条直线。
        scale(scaleX = 1f, scaleY = size.height / size.width, pivot = center) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ink.copy(alpha = 0.11f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        // 点状质感：1px 微点撒在椭圆内。必须比渐变还淡，宁可见不到也不能变噪点
        dots.forEach { d ->
            drawCircle(
                color = ink.copy(alpha = 0.045f),
                radius = 0.6f,
                center = Offset(center.x + d.x * radius, center.y + d.y * size.height / 2f),
            )
        }
    }
}

/**
 * 操作区。
 *
 * **排布**（2026-10-27 用户第四轮：「下载按钮不应该在最右下角，应该跟阅读一排……
 * 上面是下载和添加到书架，下面是阅读按钮」「阅读按钮太大了」）：
 * 1. 第一行 —— 「下载」+「放入/移出书架」，两个同等分量的**次动作**各占一半；
 * 2. 第二行 —— 「阅读」独占一行，是唯一主按钮；
 * 3. 第三行 —— 「浏览器查看」「换源…」仍是 [TextAction]：它们是**退路**，
 *    天天用的不是它们，做成大按钮只会跟主路径抢注意力。
 *
 * 为什么「下载」不再做右下角浮钮：浮钮盖住章节网格的最后一行（旧版要给网格垫
 * 72dp 的 [DownloadFabClearance]），而下载是「先选章节再执行」的两段式动作，
 * 归到操作区后与「放入书架」同层级，比一个飘着的圆钮更好解释。
 *
 * 为什么主按钮吃 `fillMaxWidth()` 而不是 `weight(1f)`：上一版它和「移出书架」
 * 挤在一行各吃 weight，窄面板下文字被压成省略号、视觉上又因为实底 + 大字显得过大。
 * 独占一行后它有了明确的主路径地位，高度反而收到 38dp。
 */
@Composable
private fun DetailActions(
    inShelf: Boolean,
    hasChapters: Boolean,
    loading: Boolean,
    downloadMode: Boolean,
    continueLabel: String,
    onRead: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onBrowser: () -> Unit,
    onMigrate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
        // **第一行：下载 + 放入书架**（两个同等分量的次动作，各占一半）
        // 用户 2026-10-27 第四轮：「下载按钮不应该在最右下角，应该跟阅读一排……
        // 上面是下载和添加到书架，下面是阅读按钮」。
        //
        // 2026-10-27 第五轮：「点击下载按钮不应该消失，而是再点一次下载按钮后取消下载界面」。
        // 上一版进了下载模式就把这颗按钮整个拿掉，用户想退出只能去找网格上方那条工具栏的
        // 「取消」——按钮消失本身就是「我点错了？」的信号。现在它**常驻**：
        // 文案与图标随模式切换，点它就是在「进 / 出下载模式」之间切换。
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
            DetailButton(
                // 2026-10-30：文案从「取消下载」改成「取消多选」——右键也能进这个模式，
                // 那时并没有在下载，写「取消下载」会让人以为点它会停掉下载队列。
                text = if (downloadMode) "取消多选" else "下载",
                primary = false,
                icon = if (downloadMode) DockIcons.Close else DockIcons.Download,
                onClick = if (downloadMode) onCancelDownload else onDownload,
                modifier = Modifier.weight(1f),
            )
            DetailButton(
                text = if (inShelf) "移出书架" else "放入书架",
                primary = false,
                onClick = if (inShelf) onRemove else onAdd,
                modifier = Modifier.weight(1f),
            )
        }
        // **第二行：阅读**（唯一主按钮，独占一行）
        // 上一版它和「移出书架」挤在一行里各吃 weight，窄面板下文字被压成省略号，
        // 视觉上又因为实底 + 大字显得「太大」。独占一行后它有了明确的主路径地位，
        // 高度反而可以收到 38dp。
        if (hasChapters) {
            DetailButton(
                text = continueLabel,
                primary = true,
                onClick = onRead,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MixText(
                text = if (loading) "章节加载中…" else "章节列表还没取到",
                style = Tokens.Type.Caption.toTextStyle(),
                color = Tokens.Color.TextTertiary,
                modifier = Modifier.padding(vertical = Tokens.Space.Sm),
            )
        }
        // 退路：文字键，不抢主路径
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Xs),
        ) {
            TextAction(text = "浏览器查看", onClick = onBrowser)
            // F-45 换源：源站改版 / 404 时唯一的出路（roadmap 第 5 节五步流程的入口之一）。
            // 不做「检测到失效才显示」——检测要额外打一次源站请求，而用户主动换源也是合理需求。
            TextAction(text = "换源…", onClick = onMigrate)
        }
    }
}

/**
 * 操作区的大按钮（38dp 高 / 10dp 圆角 / 实底 + 描边）。
 *
 * 为什么不复用 [TextAction]：文字键的点击热区只有一行字高，和信息区里成片的说明文字
 * 混在一起时「哪个能点」要靠猜；实底按钮把主路径从文字里拎出来。
 *
 * 尺寸走 [Tokens.Radius.Control]（10dp）而不是胶囊：胶囊在 38dp 高度上太「重」，
 * 会让面板看起来像一堆药丸，与章节区的圆角卡片语言也不一致。
 */
@Composable
private fun DetailButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.Radius.Control)
    val container by animateColorAsState(
        targetValue = when {
            // 主按钮 hover 用「同色降透明度」而不是换色：换色会让主按钮看起来像换了功能
            primary -> if (hovered) Tokens.Color.Accent.copy(alpha = 0.88f) else Tokens.Color.Accent
            hovered -> Tokens.Color.AccentSoft
            else -> Tokens.Color.BgElevatedSolid
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "detailButtonBg",
    )
    val outline by animateColorAsState(
        targetValue = when {
            primary -> Color.Transparent
            hovered -> Tokens.Color.Accent.copy(alpha = 0.28f)
            else -> Tokens.Color.BorderHairline
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "detailButtonOutline",
    )
    val content = if (primary) Tokens.Color.OnAccent else Tokens.Color.TextPrimary
    Row(
        modifier
            // 40 → 38dp：用户说「阅读按钮太大了」。它现在独占一行、有主路径地位，
            // 高度反而该收一点，否则整块像一条横幅。
            .height(DetailButtonHeight)
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Tokens.Space.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Tokens.Space.Sm))
        }
        MixText(
            text = text,
            style = Tokens.Type.Body.copy(weight = FontWeight.SemiBold).toTextStyle(),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 详情面板主/次按钮统一高度。 */
private val DetailButtonHeight = 38.dp

/**
 * 章节骨架（加载态）。
 *
 * 5 列 × 3 行、58dp 高——尺寸和 [ChapterCell] **完全一致**，数据到达时不会「跳一下」。
 * 呼吸用 `Tokens.Motion.SkeletonMs` 的无限往返：比闪烁（0/1 硬切）温和，
 * 也不会像 loading 转圈那样抢注意力。
 */
@Composable
private fun ChapterSkeleton() {
    val transition = rememberInfiniteTransition(label = "chapterSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Tokens.Motion.SkeletonMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chapterSkeletonAlpha",
    )
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
            ) {
                repeat(5) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(ChapterCellHeight)
                            .clip(RoundedCornerShape(Tokens.Radius.Card))
                            .background(Tokens.Color.BgSunken.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

/**
 * 进度栏（原型右侧 172dp 那一栏）。
 *
 * 数据来源：`repository.progressOf`（读到第几话）、`repository.chaptersOf`（已读计数）、
 * `downloadManager.tasks`（已下载计数）。**没有任何新查询**——这三个都是页面已经在用的流。
 *
 * [modifier] 传 `fillMaxWidth()` 时就是窄面板的折叠形态（一行横排），此时 [compact] 传 true。
 */
@Composable
private fun ProgressRail(
    percent: Int,
    chapterLabel: String,
    total: Int,
    readCount: Int,
    downloadedCount: Int,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 进度条变化 200ms（UI-SPEC 第 5 节），不做回弹
    val fraction by animateFloatAsState(
        targetValue = (percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(Tokens.Motion.ProgressMs),
        label = "detailProgress",
    )
    val tnum = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum")

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            MixText(
                text = "$percent%",
                style = Tokens.Type.Title.toTextStyle().copy(fontFeatureSettings = "tnum"),
                color = Tokens.Color.TextPrimary,
            )
            if (compact) {
                Spacer(Modifier.width(12.dp))
                MixText(
                    text = "$chapterLabel / 共 $total 话",
                    style = tnum,
                    color = Tokens.Color.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 整页唯一的「条」：6dp 胶囊，强调色填充
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                .background(Tokens.Color.BgSunken),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Tokens.Color.Accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MixText("已读 $readCount 话", style = tnum, color = Tokens.Color.TextSecondary)
                MixText("已下载 $downloadedCount 话", style = tnum, color = Tokens.Color.TextSecondary)
            }
        } else {
            MixText("$chapterLabel / 共 $total 话", style = tnum, color = Tokens.Color.TextSecondary)
            Spacer(Modifier.height(6.dp))
            MixText("已读 $readCount 话", style = tnum, color = Tokens.Color.TextSecondary)
            Spacer(Modifier.height(6.dp))
            MixText("已下载 $downloadedCount 话", style = tnum, color = Tokens.Color.TextSecondary)
        }
    }
}

/**
 * 多选工具条（全选 / 取消全选 / 全选未缓存 · 已选 N 话 · 标记已读/未读 · 下载 · 取消）。
 *
 * 位置在章节标题行下方、网格上方：用户点「下载」后视线还在章节区，
 * 工具条出现在这里才不需要回头找。
 *
 * ## 2026-10-30 扩充（用户要求 2 + 3）
 *
 * - **要求 2**：加「全选」「取消全选」——原来的「全选未缓存」只能全选，想反选就得一个个点掉。
 * - **要求 3**：这一条工具条现在**同时**服务「下载」和「右键进多选」两种进入方式，
 *   所以多了「标记已读 / 标记未读」两个批量动作。
 *
 * 为什么拆成两行：七个动作塞一行在窄面板（窗口 900px 时面板只有 450dp）里会挤成
 * 一条看不清的灰字。第一行管**选**，第二行管**做**，动作按「危险/破坏性」从左到右排。
 */
@Composable
private fun DownloadToolbar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAllUncached: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(text = "全选", onClick = onSelectAll)
            TextAction(text = "取消全选", onClick = onClearSelection)
            TextAction(text = "全选未缓存", onClick = onSelectAllUncached)
            Spacer(Modifier.weight(1f))
            MixText(
                text = "已选 $selectedCount 话",
                style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
                color = Tokens.Color.TextSecondary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextAction(text = "取消", onClick = onCancel)
            TextAction(text = "标记未读", onClick = onMarkUnread)
            TextAction(text = "标记已读", onClick = onMarkRead)
            TextAction(text = "开始下载", onClick = onStart, idleColor = Tokens.Color.Accent)
        }
    }
}

/**
 * 「话 / 卷」滑动开关（用户 2026-11-11：先要能分开，随后给了玻璃拟态日夜开关的参考图）。
 *
 * 参考图是「浅色轨道 + 一枚带光晕的玻璃浮片滑到选中侧」。正常模式**不用毛玻璃**
 * （ADR-0007/0002 的视觉纪律），所以用项目已有的语言表达同一件事：
 * 浅灰轨道（`BgSunken`）+ 白色浮片（2dp 阴影 + 4% 黑描边），浮片 180ms 滑到选中侧，
 * 选中字主色半粗、另一侧三级灰。
 *
 * 为什么不做成两个文字链（上一版的写法）：用户截图里「话 卷 刷」挤在一行、
 * 选中态只靠颜色区分，读起来像一个胶囊里塞了三个按钮。开关把「二选一」的语义
 * 直接画出来，也不会和右边的「刷新 / 正序」抢同一种视觉语言。
 */
@Composable
private fun ChapterKindSwitch(
    showVolumes: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val trackWidth = 78.dp
    val trackHeight = 26.dp
    val inset = 2.dp
    val thumbWidth = (trackWidth - inset * 2) / 2
    val thumbOffset by animateDpAsState(
        targetValue = if (showVolumes) thumbWidth else 0.dp,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "chapterKindThumb",
    )
    val pill = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(pill)
            .background(Tokens.Color.BgSunken),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = inset + thumbOffset)
                .width(thumbWidth)
                .height(trackHeight - inset * 2)
                .shadow(
                    elevation = 2.dp,
                    shape = pill,
                    clip = false,
                    ambientColor = Color(0x1A000000),
                    spotColor = Color(0x26000000),
                )
                .background(Color.White, pill)
                .border(1.dp, Color(0x0F000000), pill),
        )
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onChange(false) },
                contentAlignment = Alignment.Center,
            ) { ChapterKindText("话", !showVolumes) }
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onChange(true) },
                contentAlignment = Alignment.Center,
            ) { ChapterKindText("卷", showVolumes) }
        }
    }
}

@Composable
private fun ChapterKindText(text: String, selected: Boolean) {
    val color by animateColorAsState(
        targetValue = if (selected) Tokens.Color.Accent else Tokens.Color.TextTertiary,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "chapterKindText",
    )
    MixText(
        text = text,
        style = Tokens.Type.Caption.toTextStyle().copy(
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        ),
        color = color,
        maxLines = 1,
    )
}

/**
 * 一个章节格。
 *
 * **层级**（2026-10-27 第四轮用户：「章节有边框但是没跟外面区分要改，章节小框也没有了要加，加悬浮感」）：
 * 外层是**凹陷容器**（`BgSunken` 浅灰，见章节区 `Box`），格子是**凸起白卡**
 * （`BgElevatedSolid` + `Radius.Control`）。凹 / 凸两层靠**底色差**读出来。
 * 第五轮（用户：「去掉详情页章节框的边框线」）把两侧的静态线都撤了：
 * 容器的 `border` 删掉、格子默认态描边改透明 —— 线不参与层级，底色才参与。
 *
 * **悬浮感**（三件事叠起来才叫浮起来，只做一件都不像）：上移 1dp + 4dp 中性阴影 +
 * 描边转主色。下载模式下不给 hover 位移（那时点击是勾选，位移会让人以为格子会跑）。
 *
 * 着色：当前话主色字 + Medium + 主色描边；点过（`lastOpenedAt > 0`）文字变灰；
 * 其余主色字。灰 ≠ 已读（用户 2026-10-25 明确），已读在章节名右侧挂一枚中性灰底白勾。
 *
 * [onContext] 给的是**窗口绝对坐标**（不是相对本格的 DpOffset）：菜单换成
 * 项目统一的 `WindowMenu` 之后按窗口坐标定位，格子这里得先把落点换算过去。
 * 展开状态仍由面板统一持有，保证同时只有一格弹菜单。
 */
@Composable
private fun ChapterCell(
    chapter: SourceChapter,
    current: Boolean,
    opened: Boolean,
    read: Boolean,
    dlTask: DownloadTask?,
    pages: Int,
    downloadMode: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onContext: (Offset) -> Unit,
    onRetry: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.Radius.Control)
    // 本格左上角的**窗口坐标**：右键落点是格内坐标，加它才是 WindowMenu 要的绝对坐标。
    // 用 `localToWindow(Offset.Zero)`（Compose 1.12 起没有 positionInWindow()）。
    var originInWindow by remember { mutableStateOf(IntOffset.Zero) }
    // ---- 悬浮感（2026-10-27 第四轮用户要求）----
    // 三件事叠起来才叫「浮起来」：上移 1dp + 一层 4dp 中性阴影 + 描边转强调色。
    // 只做阴影会像「贴了张纸」；只做位移会像「抖一下」。
    val lift by animateDpAsState(
        targetValue = if (hovered && !downloadMode) (-1).dp else 0.dp,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "chapterLift",
    )
    // 白卡要在**白色面板**上立起来，靠 2dp 影子看不出来（用户 2026-10-28 反馈
    // 「阴影不明显、没有按钮感、跟背景没区分开」）。常驻 4dp + 更深的 spot 色，
    // hover 抬到 10dp；再叠一条 4% 黑描边勾出边缘，白底上才有实体感。
    val elevation by animateDpAsState(
        targetValue = if (hovered && !downloadMode) 10.dp else 4.dp,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "chapterElevation",
    )
    val outline by animateColorAsState(
        targetValue = when {
            current -> Tokens.Color.Accent.copy(alpha = 0.45f)
            hovered && !downloadMode -> Tokens.Color.Accent.copy(alpha = 0.28f)
            // 默认态保留一条 4% 黑的极淡描边：外层灰底撤掉后，纯靠阴影在白面板上
            // 边界会发虚（用户反馈「跟背景没区分开」）。这条线只勾轮廓，不抢视觉。
            else -> Color(0x0A000000)
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "chapterOutline",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(ChapterCellHeight)
            .onGloballyPositioned { coords ->
                val o = coords.localToWindow(Offset.Zero)
                originInWindow = IntOffset(o.x.roundToInt(), o.y.roundToInt())
            }
            .offset(y = lift)
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                // 中性黑：彩色阴影会让白卡发脏（与书架卡片同一口径）
                ambientColor = Color(0x1F000000),
                spotColor = Color(0x42000000),
            )
            .clip(shape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, outline, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                if (downloadMode) onToggle() else onOpen()
            }
            // 右键必须在 clickable **内层**：先 consume 掉按下，clickable 就不会再当左键点击
            .pointerInput(chapter.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Press || !e.buttons.isSecondaryPressed) continue
                        val at = e.changes.first().position
                        // 格内坐标 → 窗口坐标（WindowMenu 按绝对坐标定位）
                        onContext(
                            Offset(originInWindow.x + at.x, originInWindow.y + at.y)
                        )
                        e.changes.forEach { it.consume() }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MixText(
                    text = chapter.name,
                    style = Tokens.Type.Body.toTextStyle().copy(
                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = when {
                        current -> Tokens.Color.Accent
                        opened -> Tokens.Color.TextTertiary
                        else -> Tokens.Color.TextPrimary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 已读标记跟着章节名走（第一行末尾）：以前挤在第二行和「已缓存 / 页数」里，
                // 10dp 的细勾在 5 列网格上几乎看不见。现在 14dp 实心圆一眼可辨。
                if (read && !downloadMode) {
                    Spacer(Modifier.width(Tokens.Space.Xs))
                    ReadBadge()
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (current && !downloadMode) {
                    MixText(
                        text = "读到这",
                        style = Tokens.Type.Micro.toTextStyle(),
                        color = Tokens.Color.Accent,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 下载状态徽标：「已缓存」常显，进度/失败跟着任务走
                when (dlTask?.status) {
                    // 「已缓存」是信息不是成功：走中性灰，把绿色从详情页彻底去掉
                    DownloadStatus.COMPLETED -> MixText(
                        text = "已缓存",
                        style = Tokens.Type.Micro.toTextStyle(),
                        color = Tokens.Color.TextSecondary,
                    )
                    DownloadStatus.DOWNLOADING -> MixText(
                        text = "${dlTask.downloadedPages}/${dlTask.pageCount}",
                        style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                        color = Tokens.Color.Accent,
                    )
                    DownloadStatus.QUEUED -> MixText(
                        text = "等待中",
                        style = Tokens.Type.Micro.toTextStyle(),
                        color = Tokens.Color.TextTertiary,
                    )
                    // 重试做成文字链：格子里塞不进第二个按钮，点一下就重试，语义仍然明确
                    DownloadStatus.FAILED -> MixText(
                        text = "失败·重试",
                        style = Tokens.Type.Micro.toTextStyle(),
                        color = Tokens.Color.Danger,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                    null -> Unit
                }
                // 每章页数（#30）：源站给的优先，源站不给时用库里阅读回填的兜底
                if (pages > 0) {
                    if (dlTask != null) Spacer(Modifier.width(5.dp))
                    MixText(
                        text = "$pages 页",
                        style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                        color = Tokens.Color.TextSecondary,
                    )
                }
                // 多选勾选框挪到**右下角**、做成**圆形灰圈**（2026-10-27 第五轮用户：
                // 「下载框放在右下，用圆型灰色的框」）。
                // 原来它是左上角的 Material3 方形 Checkbox：和章节名抢同一行的左端，
                // 一眼看过去像「章节标题的图标」，而且方块在满屏圆角里是唯一的直角。
                if (downloadMode) {
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    SelectRing(checked = checked)
                }
            }
        }
        menu()
    }
}

/**
 * 多选勾选框：16dp **圆形灰圈**，选中后填主色 + 白勾。
 *
 * 2026-10-27 第五轮用户：「下载框放在右下，用圆型灰色的框」（附截图红圈标在格子右下角）。
 * 换掉 Material3 的方形 `Checkbox` 有两个理由：
 * 1. **位置**：原来在左上角，和章节名抢同一行左端，看着像标题前的图标；多选态下用户要扫的是
 *    「这一格勾没勾」，右下角是视觉动线的落点，不跟文字抢。
 * 2. **形状**：整页（章节白卡、封面、返回钮、回撤条）都是圆角语言，方框是唯一的直角。
 * 灰圈用 `TextTertiary` 描边而不是 `BorderHairline`：后者 6% 黑在白卡上根本看不见，
 * 勾选框的第一要务是**能看见**。
 */
@Composable
private fun SelectRing(checked: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (checked) Tokens.Color.Accent else Color.Transparent,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "selectRingFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (checked) Tokens.Color.Accent else Tokens.Color.TextTertiary,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "selectRingStroke",
    )
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = DockIcons.Check,
                contentDescription = "已选中",
                tint = Tokens.Color.OnAccent,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/**
 * 「已读」标记：14dp 深灰底圆 + 白色对勾。
 *
 * **为什么不用绿色**（2026-10-27 第四轮用户：「详情页色彩太多太杂，不要又是红又是绿又蓝」）：
 * 整页只有「当前/可操作」用蓝、「真错误」用红，其余全是黑灰阶。已读是一个**已完成的过去时**，
 * 不是「成功状态」，用中性深灰既清楚又不引入第四种颜色。
 *
 * 为什么仍是实心圆而不是描边勾：5 列网格里每格只有 ~120dp 宽，细描边在浅灰底上几乎没有对比度；
 * 实心圆是**一块色**，扫视时一眼可辨。
 */
@Composable
private fun ReadBadge() {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Tokens.Color.TextSecondary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DockIcons.Check,
            contentDescription = "已读",
            tint = Tokens.Color.OnAccent,
            modifier = Modifier.size(9.dp),
        )
    }
}
