package app.gamanga.desktop.ui

import app.gamanga.desktop.ui.theme.Tokens
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.shell.TextAction
import app.gamanga.desktop.ui.shell.DockReservedHeight
import app.gamanga.desktop.ui.shell.DragStripHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.DownloadManager
import app.gamanga.desktop.data.DownloadStatus
import app.gamanga.desktop.data.DownloadTask
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.detail.DetailHost
import app.gamanga.desktop.ui.detail.rememberDetailHostState
import kotlinx.coroutines.delay

/**
 * 下载管理页（R-04）。
 *
 * 按漫画分组的任务列表：进行中（进度条）/ 等待中 / 失败（重试）/ 已完成（阅读 + 删除）。
 * 顶部一行总览：缓存占用、已缓存话数、进行中任务数；并发数设置行（第七轮 #2）。
 *
 * 「阅读」从书架章节表重建 ReaderTarget（下载的书必然在书架——downloadChapter
 * 有 manga 外键），落到本章第一页；阅读器对已下载章节直接读本地文件。
 */
@Composable
fun DownloadsScreen(
    repository: LibraryRepository,
    downloadManager: DownloadManager = AppGraph.downloadManager,
    onRead: (ReaderTarget) -> Unit,
    /** 详情面板里「用浏览器打开」——本页**不跳页**，由壳层盖浏览器。 */
    onOpenBrowser: (url: String, domain: String) -> Unit,
) {
    /**
     * 详情二级页宿主（2026-10-27 第六批用户口径）。
     *
     * 原话：「历史页面和下载页面都要在自己页面打开二级详情页，点击漫画卡片就会跳转到
     * 二级详情，点击阅读进入阅读器」。本页的「卡片」就是一本漫画的分组标题行。
     */
    val detail = rememberDetailHostState(repository, downloadManager)

    /** 点书名 → 页内二级详情（封面/作者从书架条目补，离线第一帧也有内容）。 */
    fun openDetail(mangaId: String, fallbackTitle: String) {
        val type = DownloadManager.sourceTypeOf(mangaId) ?: return
        val entry = repository.mangaMetadata.value[mangaId]
        detail.open(
            SourceManga(
                sourceId = entry?.sourceMangaId ?: LibraryRepository.sourceMangaIdOf(mangaId),
                title = entry?.title ?: fallbackTitle,
                coverUrl = entry?.coverUrl,
                author = entry?.author,
            ),
            type,
        )
    }

    Box(Modifier.fillMaxSize()) {
        DownloadsContent(
            repository = repository,
            downloadManager = downloadManager,
            onRead = onRead,
            onOpenDetail = { id, title -> openDetail(id, title) },
        )

        // 详情面板：与书架页 / 历史页 / 浏览页共用同一个宿主（动画、换源、
        // 选章下载、加移书架的行为完全一致，不会各飘一套）
        DetailHost(
            state = detail,
            repository = repository,
            downloadManager = downloadManager,
            onRead = onRead,
            onOpenBrowser = onOpenBrowser,
        )
    }
}

/**
 * 下载页的一级内容（列表本体）。
 *
 * 2026-10-27 第六批从 [DownloadsScreen] 拆出来：外层要变成 `Box { 内容 + DetailHost }`，
 * 详情面板才能**盖在列表上**——直接塞进原来的 `Column` 会被排到列表下面（永远看不见）。
 */
@Composable
private fun DownloadsContent(
    repository: LibraryRepository,
    downloadManager: DownloadManager,
    onRead: (ReaderTarget) -> Unit,
    /** 点书名 → 页内二级详情（mangaId, 兜底标题）。 */
    onOpenDetail: (String, String) -> Unit,
) {
    val tasks by downloadManager.tasks.collectAsState()
    // 分组按漫画标题排序；组内按 createdAt（入队时间）**稳定**排序——旧实现按
    // updatedAt 排，每页进度都改 updatedAt，正在下载的任务在列表里来回跳
    // （用户实测「正在下载的两个任务在列表中来回跳动」，第七轮 #2）。
    val groups = remember(tasks) {
        tasks.values
            .groupBy { it.mangaId }
            .map { (mangaId, list) -> mangaId to list.sortedBy { it.createdAt } }
            .sortedBy { (_, list) -> list.first().mangaTitle }
    }
    val totalBytes = tasks.values.sumOf { it.totalBytes }
    val completedCount = tasks.values.count { it.status == DownloadStatus.COMPLETED }
    val activeCount = tasks.values.count {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }
    // 实时下载速度（第八轮 #5）：每 500ms 采样一次 DownloadManager 的速率窗。
    // produceState 的 while(true) 在页面离开时自动取消。
    val speedBps by produceState(0L) {
        while (true) {
            value = downloadManager.speedBps()
            delay(500)
        }
    }
    // 暂停标志轮询（同上）：DownloadManager 的是普通 @Volatile var，
    // 不是 Compose state，UI 要自己采样才能重组。
    val paused by produceState(downloadManager.isPaused()) {
        while (true) {
            value = downloadManager.isPaused()
            delay(500)
        }
    }

    val collapsed = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val retainedGroups = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val collapseSnapshot = collapsed.toMap()
    LaunchedEffect(collapseSnapshot) {
        groups.forEach { (id, _) -> if (collapsed[id] != true) retainedGroups[id] = true }
        delay(180)
        groups.forEach { (id, _) -> if (collapsed[id] == true) retainedGroups[id] = false }
    }
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    pendingDelete?.let { (id, title) ->
        ConfirmDownloadDeletion(title, tasks.values.count { it.mangaId == id },
            onDismiss = { pendingDelete = null },
            onConfirm = { downloadManager.deleteMangaDownloads(id); pendingDelete = null })
    }
    val metadata by repository.mangaMetadata.collectAsState()
    var filter by remember { mutableStateOf("全部") }
    val shown = groups.map { (id, list) -> id to list.filter { task -> when (filter) {
        "进行中" -> task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED
        "已完成" -> task.status == DownloadStatus.COMPLETED
        "失败" -> task.status == DownloadStatus.FAILED
        else -> true
    } } }.filter { it.second.isNotEmpty() }
    Column(Modifier.fillMaxSize().background(Tokens.Color.BgBase).padding(horizontal = 40.dp).padding(top = DragStripHeight + Tokens.Space.Lg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MixText("下载", color = Tokens.Color.TextPrimary, fontSize = Tokens.Type.Title.size, style = Tokens.Type.Title.toTextStyle())
            Spacer(Modifier.weight(1f))
            if (activeCount > 0) TextAction(if (paused) "继续全部" else "暂停队列", onClick = { if (paused) downloadManager.resumeAll() else downloadManager.pauseAll() }, idleColor = Tokens.Color.Accent)
        }
        Spacer(Modifier.height(Tokens.Space.Md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md)) {
            DownloadMetric("本地缓存", DownloadManager.formatBytes(totalBytes), Modifier.weight(1f))
            DownloadMetric("离线章节", "$completedCount 话", Modifier.weight(1f))
            DownloadMetric(if (paused) "队列已暂停" else "等待与下载", "$activeCount 话", Modifier.weight(1f))
            DownloadMetric("当前速度", "${DownloadManager.formatBytes(speedBps)}/s", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Tokens.Space.Md))
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
            listOf("全部", "进行中", "已完成", "失败").forEach { label ->
                app.gamanga.desktop.ui.shell.PageFilter(label, filter == label) { filter = label }
            }
        }
        Spacer(Modifier.height(Tokens.Space.Sm))
        ConcurrencyRow(repository = repository, downloadManager = downloadManager)
        Spacer(Modifier.height(6.dp))

        // 空列表不再写说明文案（用户 2026-10-29 要求「去掉图片上的文字」）：
        // 页头指标 + 筛选 + 并发行已经说明了这是下载页，再堆一段长句只是噪音。
        if (groups.isEmpty()) return@Column

        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(end = 24.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(top = Tokens.Space.Sm, bottom = DockReservedHeight + Tokens.Space.Lg),
            ) {
                shown.forEach { (mangaId, list) ->
                    item(key = "gap:$mangaId") { Spacer(Modifier.height(Tokens.Space.Md)) }
                    item(key = "header:$mangaId") {
                        val title = list.first().mangaTitle
                        DownloadGroupHeader(title, metadata[mangaId]?.coverUrl, list.size,
                            expanded = collapsed[mangaId] != true,
                            onToggle = { collapsed[mangaId] = collapsed[mangaId] != true },
                            onOpenDetail = { onOpenDetail(mangaId, title) },
                            onDelete = { pendingDelete = mangaId to title })
                    }
                    if (collapsed[mangaId] != true || retainedGroups[mangaId] == true) items(list, key = { "task:${it.id}" }) { task ->
                        val expanded = collapsed[mangaId] != true
                        val visibility = remember { Animatable(.35f) }
                        LaunchedEffect(expanded) { visibility.animateTo(if (expanded) 1f else 0f, tween(if (expanded) 240 else 160)) }
                        Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, (placeable.height * visibility.value).toInt()) { placeable.placeRelative(0, 0) }
                        }.graphicsLayer { alpha = visibility.value; translationY = -6.dp.toPx() * (1f - visibility.value) }
                            .clip(if (task.id == list.last().id) RoundedCornerShape(bottomStart = Tokens.Radius.Card, bottomEnd = Tokens.Radius.Card) else RoundedCornerShape(0.dp))
                            .background(Tokens.Color.BgElevatedSolid)) {
                            DownloadTaskCard(task, paused, onRead = { readDownloaded(repository, task, onRead) }, onRetry = { downloadManager.retry(task.id) }, onDelete = { downloadManager.delete(task.id) }, grouped = true)
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("这个分类下暂无任务", Modifier.padding(Tokens.Space.Lg), color = Tokens.Color.TextSecondary) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp)
            )
        }
    }
}

/**
 * 并发数设置行（第七轮 #2）：同时下载几章。
 *
 * 推荐 2：Komiic 每章取图烧一份每日配额、G社 每章拉一次隐藏浏览器，章节级
 * 并发再高也只是让取图阶段互相排队；真正能并行加速的只有章内图片字节下载
 * （固定 4 路）。其余章节排队等待，列表顺序稳定不乱跳。
 */
@Composable
private fun ConcurrencyRow(repository: LibraryRepository, downloadManager: DownloadManager) {
    var concurrency by remember {
        mutableStateOf(
            repository.getPref(LibraryRepository.PREF_DOWNLOAD_CONCURRENCY)?.toIntOrNull()
                ?: downloadManager.maxParallelChapters()
        )
    }
    fun apply(value: Int) {
        concurrency = value.coerceIn(1, 8)
        downloadManager.setMaxParallelChapters(concurrency)
        repository.setPrefAsync(LibraryRepository.PREF_DOWNLOAD_CONCURRENCY, concurrency.toString())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("同时下载", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { apply(concurrency - 1) },
            enabled = concurrency > 1,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text("−", fontSize = 16.sp) }
        Text("$concurrency", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        TextButton(
            onClick = { apply(concurrency + 1) },
            enabled = concurrency < 8,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text("+", fontSize = 16.sp) }
        Text(" 章", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(Tokens.Radius.Card)).background(Tokens.Color.BgElevatedSolid)) {
        Canvas(Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 30.dp).size(86.dp)) {
            val ink = Tokens.Color.TextTertiary.copy(alpha = .10f)
            val stroke = 5.dp.toPx()
            val w = size.width; val h = size.height
            when (label) {
                "本地缓存" -> {
                    val layer = androidx.compose.ui.graphics.Path().apply { moveTo(w*.12f,h*.28f); lineTo(w*.5f,h*.08f); lineTo(w*.88f,h*.28f); lineTo(w*.5f,h*.48f); close() }
                    drawPath(layer, ink, style = Stroke(stroke))
                    listOf(.46f,.65f).forEach { y -> drawLine(ink, Offset(w*.12f,h*y), Offset(w*.5f,h*(y+.2f)), stroke); drawLine(ink, Offset(w*.5f,h*(y+.2f)), Offset(w*.88f,h*y), stroke) }
                }
                "离线章节" -> { drawRoundRect(ink, Offset(w*.2f,h*.1f), androidx.compose.ui.geometry.Size(w*.58f,h*.78f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(stroke)); drawLine(ink, Offset(w*.34f,h*.35f), Offset(w*.64f,h*.35f), stroke); drawLine(ink, Offset(w*.34f,h*.52f), Offset(w*.64f,h*.52f), stroke) }
                "当前速度" -> { drawArc(ink, 180f, 180f, false, Offset(w*.12f,h*.25f), androidx.compose.ui.geometry.Size(w*.76f,h*.76f), style = Stroke(stroke)); drawLine(ink, Offset(w*.5f,h*.64f), Offset(w*.76f,h*.31f), stroke) }
                else -> { drawLine(ink, Offset(w*.5f,h*.12f), Offset(w*.5f,h*.7f), stroke); drawLine(ink, Offset(w*.25f,h*.47f), Offset(w*.5f,h*.72f), stroke); drawLine(ink, Offset(w*.5f,h*.72f), Offset(w*.75f,h*.47f), stroke) }
            }
        }
        Column(Modifier.padding(Tokens.Space.Md)) {
            Text(label, color = Tokens.Color.TextSecondary, fontSize = Tokens.Type.Caption.size)
            Spacer(Modifier.height(Tokens.Space.Sm))
            Text(value, color = Tokens.Color.TextPrimary, fontSize = Tokens.Type.Subtitle.size, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ConfirmDownloadDeletion(title: String, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        containerColor = Color.White, titleContentColor = Tokens.Color.TextPrimary, textContentColor = Tokens.Color.TextSecondary,
        title = { Text("删除《$title》的下载？") },
        text = { Text("将删除这部漫画的 $count 个下载任务及本地章节缓存，书架与阅读记录保留。") },
        confirmButton = { TextAction("删除下载", danger = true, onClick = onConfirm) },
        dismissButton = { TextAction("取消", onClick = onDismiss) })
}

@Composable
internal fun DownloadGroupHeader(title: String, coverUrl: String?, count: Int, expanded: Boolean,
    onToggle: () -> Unit, onOpenDetail: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val localCover = remember(coverUrl) { app.gamanga.desktop.data.CoverCache.localUri(coverUrl) }
    Row(Modifier.fillMaxWidth().hoverable(interaction)
        .clip(if (expanded) RoundedCornerShape(topStart = Tokens.Radius.Card, topEnd = Tokens.Radius.Card) else RoundedCornerShape(Tokens.Radius.Card))
        .background(Tokens.Color.BgElevatedSolid).primaryClick(onClick = onToggle).padding(Tokens.Space.Md), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md)) {
        Box(Modifier.size(32.dp, 44.dp).clip(RoundedCornerShape(5.dp))
            .semantics { contentDescription = "查看详情：$title"; onClick { onOpenDetail(); true } }
            .primaryClick(onClick = onOpenDetail)) {
            RemoteImage(localCover ?: coverUrl, null, Modifier.fillMaxSize())
        }
        Row(Modifier.weight(1f).semantics { contentDescription = "${if (expanded) "收起" else "展开"}下载：$title"; onClick { onToggle(); true } }
            .primaryClick(onClick = onToggle).padding(vertical = Tokens.Space.Sm), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = Tokens.Type.Subtitle.size, fontWeight = FontWeight.SemiBold,
                color = Tokens.Color.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 话  ${if (expanded) "⌃" else "⌄"}", color = Tokens.Color.TextSecondary, fontSize = Tokens.Type.Caption.size)
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (hovered) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Tokens.Radius.Control))
                .semantics { contentDescription = "删除整部下载：$title"; onClick { onDelete(); true } }
                .primaryClick(onClick = onDelete).padding(7.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Tokens.Color.Danger; val s = 1.6.dp.toPx(); val w = size.width; val h = size.height
                    drawLine(c, Offset(w*.12f,h*.24f), Offset(w*.88f,h*.24f), s)
                    drawLine(c, Offset(w*.37f,h*.08f), Offset(w*.63f,h*.08f), s)
                    drawLine(c, Offset(w*.24f,h*.3f), Offset(w*.3f,h*.9f), s)
                    drawLine(c, Offset(w*.76f,h*.3f), Offset(w*.7f,h*.9f), s)
                    drawLine(c, Offset(w*.3f,h*.9f), Offset(w*.7f,h*.9f), s)
                }
            }
        }
    }
}

@Composable
internal fun DownloadTaskCard(task: DownloadTask, paused: Boolean, onRead: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, grouped: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift = animateFloatAsState(if (hovered) -2f else 0f, tween(Tokens.Motion.HoverMs))
    val active = task.status == DownloadStatus.DOWNLOADING
    val failed = task.status == DownloadStatus.FAILED
    Column(Modifier.fillMaxWidth()) {
    if (grouped) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = Tokens.Space.Md), color = Tokens.Color.BorderHairline, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().hoverable(interaction).graphicsLayer { translationY = if (grouped) 0f else lift.value.dp.toPx() }
        .then(if (grouped) Modifier else Modifier.clip(RoundedCornerShape(Tokens.Radius.Card)).background(Tokens.Color.BgElevatedSolid)
        .border(1.dp, if (hovered) Tokens.Color.Accent.copy(alpha = .22f) else Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Card)))
        .padding(Tokens.Space.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(Tokens.Radius.Control)).background(if (failed) Tokens.Color.Danger.copy(alpha = .08f) else if (active) Tokens.Color.AccentSoft else Tokens.Color.BgSunken), contentAlignment = Alignment.Center) {
            Text(when (task.status) { DownloadStatus.COMPLETED -> "✓"; DownloadStatus.FAILED -> "!"; DownloadStatus.DOWNLOADING -> "↓"; else -> "·" }, color = if (failed) Tokens.Color.Danger else if (active) Tokens.Color.Accent else Tokens.Color.TextSecondary, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
            Text(task.chapterName, color = Tokens.Color.TextPrimary, fontSize = Tokens.Type.Body.size, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = DownloadManager.sourceTypeOf(task.mangaId)?.let { sourceNameOf(it) } ?: "未知源"
            val status = when (task.status) {
                DownloadStatus.DOWNLOADING -> "下载中 · ${task.downloadedPages}/${task.pageCount} 页"
                DownloadStatus.QUEUED -> if (paused) "已暂停 · ${task.downloadedPages}/${task.pageCount} 页" else "等待下载"
                DownloadStatus.COMPLETED -> "${task.pageCount} 页 · ${DownloadManager.formatBytes(task.totalBytes)}"
                DownloadStatus.FAILED -> "失败 · ${task.error ?: "请重试"}"
            }
            Text("$source · $status", color = if (failed) Tokens.Color.Danger else Tokens.Color.TextSecondary, fontSize = Tokens.Type.Caption.size, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (active || task.status == DownloadStatus.QUEUED && task.downloadedPages > 0) LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Tokens.Color.Accent, trackColor = Tokens.Color.BgSunken)
        }
        if (task.status == DownloadStatus.COMPLETED) TextAction("阅读", onClick = onRead)
        if (failed) TextAction("重试", onClick = onRetry)
        TextAction(if (task.status == DownloadStatus.COMPLETED) "删除" else "取消", onClick = onDelete, danger = true)
    }
    }
}

/** 一本漫画的下载分组（标题 + 章节行）。 */
@Composable
private fun DownloadGroup(
    mangaId: String,
    title: String,
    tasks: List<DownloadTask>,
    paused: Boolean,
    /** 点书名 → 页内二级详情（第六批口径）。 */
    onOpenDetail: () -> Unit,
    onRead: (DownloadTask) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                // 只认主键：桌面端 `clickable` 把右键也算点击（书架页 R-09 的教训）
                .primaryClick(onClick = onOpenDetail)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${tasks.size} 话 · ${DownloadManager.formatBytes(tasks.sumOf { it.totalBytes })}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        tasks.forEach { task ->
            Row(
                Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    // 章节名 + 下载源标签（第七轮 #4：每个任务都要能看出从哪来）。
                    // 源按 mangaId 反解（"komiic:228" → Komiic），解析不了显示「未知源」。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.chapterName,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            DownloadManager.sourceTypeOf(task.mangaId)?.let { sourceNameOf(it) } ?: "未知源",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { task.progress },
                                modifier = Modifier.fillMaxWidth().height(3.dp)
                            )
                            Text(
                                "下载中 ${task.downloadedPages}/${task.pageCount} 页",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DownloadStatus.QUEUED -> {
                            // 暂停中保留已下载进度（第十二轮 #9）：下载过的页
                            // 仍在磁盘上，进度条按 downloadedPages/pageCount 停住，
                            // 文案区分「已暂停」（用户手动暂停）与「等待中」（排队）。
                            val pausedNow = paused
                            if (task.downloadedPages > 0 && task.pageCount > 0) {
                                Spacer(Modifier.height(3.dp))
                                LinearProgressIndicator(
                                    progress = { task.progress },
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                            }
                            Text(
                                if (pausedNow) "已暂停 ${task.downloadedPages}/${task.pageCount} 页"
                                else "等待中",
                                fontSize = 12.sp,
                                color = if (pausedNow) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DownloadStatus.COMPLETED -> Text(
                            "${task.pageCount} 页 · ${DownloadManager.formatBytes(task.totalBytes)}",
                            fontSize = 12.sp,
                            color = Tokens.Color.Success
                        )
                        DownloadStatus.FAILED -> Text(
                            "失败：${task.error ?: "未知原因"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when (task.status) {
                    DownloadStatus.COMPLETED -> {
                        TextButton(
                            onClick = { onRead(task) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("阅读", fontSize = 13.sp) }
                    }
                    DownloadStatus.FAILED -> {
                        TextButton(
                            onClick = { onRetry(task.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("重试", fontSize = 13.sp) }
                    }
                    else -> Unit
                }
                TextButton(
                    onClick = { onDelete(task.id) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (task.status == DownloadStatus.COMPLETED) "删除" else "取消",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 从书架章节表重建 ReaderTarget 打开某一话。
 *
 * 章节 id 反解回源站 id（`komiic:228#3276` → `3276`）——ReaderTarget 的
 * chapters 是 [SourceChapter]（源站形态），阅读器按 sourceChapterId 回源/读本地。
 */
private fun readDownloaded(
    repository: LibraryRepository,
    task: DownloadTask,
    onRead: (ReaderTarget) -> Unit,
) {
    val type = DownloadManager.sourceTypeOf(task.mangaId) ?: return
    val chapters = repository.chaptersOf(task.mangaId).map { ch ->
        SourceChapter(
            id = LibraryRepository.sourceChapterIdOf(ch.id),
            name = ch.name,
            index = ch.index,
            pageCount = ch.pageCount,
        )
    }
    if (chapters.isEmpty()) return // 漫画已不在书架（孤儿任务，等清理）
    // **两边都是源站 id，直接比**（2026-10-25 修正）：
    // `enqueue()` 存进 DownloadTask 的 `chapterId` 就是**源站 id**（`guimiezhiren#<uuid>`），
    // 而上面 `chapters` 也已经用 `sourceChapterIdOf` 反解成源站 id 了。
    // 上一版又对它调了一次 `sourceChapterIdOf` → 拷贝漫画的 `guimiezhiren#` 前缀被二次切掉
    // → 找不到 → startIndex 恒 0 → 打开"第一话" → 本地没这一话 → 回源 → 断网「该话无图片」。
    val startIndex = chapters.indexOfFirst { it.id == task.chapterId }.coerceAtLeast(0)
    onRead(
        ReaderTarget(
            type = type,
            sourceMangaId = LibraryRepository.sourceMangaIdOf(task.mangaId),
            title = task.mangaTitle,
            chapters = chapters,
            startIndex = startIndex,
            localMangaId = task.mangaId,
        )
    )
}

/**
 * 只认主键的点击（桌面端专用，与 `HistoryScreen.primaryPress` 同一套口径）。
 *
 * 为什么不用 `Modifier.clickable`：桌面端它把右键也算点击——右键点书名会直接开详情，
 * 用户想弹上下文菜单时会被抢走（书架页 R-09 的教训）。这里显式只认 `isPrimaryPressed`，
 * 并且**只在事件还没被更深的子件消费时**才触发。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.primaryClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Press) { event ->
        if (!enabled) return@onPointerEvent
        if (event.buttons.isPrimaryPressed && event.changes.none { it.isConsumed }) {
            event.changes.forEach { it.consume() }
            onClick()
        }
    }
