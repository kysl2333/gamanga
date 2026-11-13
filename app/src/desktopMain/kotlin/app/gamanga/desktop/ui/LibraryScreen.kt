package app.gamanga.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.source.browserUrl
import app.gamanga.desktop.source.domainOf
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.WaveIconButton
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 书架封面墙（M1：数据来自 SQLDelight，Flow 驱动）。
 *
 * 视觉对齐 M0 目标样稿：顶部标题 + 「全部 / 未读」筛选 chip；3 列封面网格；
 * 底部「最近阅读」条。新增：未读角标、长按封面移出书架（Snackbar 反馈）、
 * 右键上下文菜单（R-09：打开 / 置顶 / 浏览器打开 / 移出书架）、空库空态。
 */
@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpen: (MangaEntry) -> Unit,
    /** 右键「在浏览器中打开」（内嵌浏览器兜底，源站改版/要登录态时用）。 */
    onOpenBrowser: (url: String, domain: String) -> Unit
) {
    val items by repository.libraryItems.collectAsState()
    val recent by repository.recentReading.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 用户 2026-10-26：右上角「全部 8 / 未读 1263」两个筛选 chip 直接去掉，不需要。
    // 连带 unreadOnly 状态与「没有未读章节」文案一起删干净，避免留死代码。
    val visible = items

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("书架", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                // 搜索键：三态波浪动效（用户 2026-10-26 口述规格）。
                // 旧书架页还没有搜索页，先给一条状态提示；新书架页 Compose 化时接到真实搜索入口。
                WaveIconButton(
                    icon = DockIcons.Search,
                    contentDescription = "搜索",
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar("搜索页待做") }
                    },
                )
            }
            Spacer(Modifier.height(18.dp))

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("(·_·;)", fontSize = 28.sp, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "书架是空的，去「浏览」找漫画",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    // 自适应列数：固定 3 列在最大化（2560 宽）时每张卡近 850px、右边大片留白
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visible, key = { it.entry.id }) { item ->
                        CoverCell(
                            item = item,
                            onClick = { onOpen(item.entry) },
                            // 2026-10-24 用户要求：长按不再直接移除书架，改为弹出右键菜单，
                            // 「移出书架」只能从菜单里点（避免误触把书删掉）。
                            onRemove = {
                                val title = item.entry.title
                                repository.removeFromLibrary(item.entry.id)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已移出《$title》")
                                }
                            },
                            onOpenBrowser = {
                                onOpenBrowser(
                                    browserUrl(item.entry.source, item.entry.sourceMangaId),
                                    domainOf(item.entry.source)
                                )
                            },
                            onTogglePin = { repository.togglePin(item.entry.id) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = recent?.let { "最近阅读：${it.mangaTitle} · ${it.chapterName} · ${it.percent}%" }
                        ?: "还没开始阅读",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun CoverCell(
    item: LibraryRepository.LibraryItem,
    onClick: () -> Unit,
    /** 菜单里的「移出书架」。长按只负责开菜单，不直接执行。 */
    onRemove: () -> Unit,
    onOpenBrowser: () -> Unit,
    onTogglePin: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    /** 按下点在格子内的坐标（px）。 */
    var pressInCell by remember { mutableStateOf(Offset.Zero) }
    /** 格子左上角的**窗口**坐标（px）——onGloballyPositioned 拿，滚动时跟着更新。 */
    var cellOriginInWindow by remember { mutableStateOf(IntOffset.Zero) }
    Box {
        Column(
            Modifier
                .combinedClickable(
                    onClick = onClick,
                    // 长按 = 打开同一个菜单（与右键一致）。原来长按直接移出书架，
                    // 手指一滑就误删，用户要求改成菜单（2026-10-24）。
                    onLongClick = {
                        menuOpen = true
                        // pressInCell 是**格子内**坐标（菜单定位会再加 cellOriginInWindow），
                        // 这里给封面中心附近即可，别把窗口坐标混进来（会双重偏移）。
                        pressInCell = Offset(60f, 80f)
                    }
                )
                .onGloballyPositioned { coords ->
                    // 1.12 起没有 positionInWindow()，用 localToWindow(零点) 拿格子
                    // 左上角的窗口坐标——和 Popup positionProvider 同一坐标空间
                    val origin = coords.localToWindow(Offset.Zero)
                    cellOriginInWindow = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                }
                // 右键菜单（R-09）。两个坑：
                // 1) 必须放在 combinedClickable **之后**——同一节点链上后应用的修饰符
                //    在 Main pass 先收到事件，这里才能赶在长按判定之前截胡；
                // 2) 必须立即 consume——combinedClickable 的长按判定不区分左右键
                //    （翻过 foundation 字节码确认无按钮过滤），不消费的话右键按住
                //    不放会误触发「移出书架」。
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        pressInCell = event.changes.first().position
                        menuOpen = true
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color(item.coverColorArgb), RoundedCornerShape(8.dp))
            ) {
                // 有远端封面就盖在占位色上（M3 起源站封面可用）。
                // **封面落盘缓存**（R-101）：优先用本地文件，断网时书架不会变成一片灰块；
                // 首次见到这张封面时后台下载并落盘（CoverCache）。
                val remoteCover = item.entry.coverUrl
                var coverUri by remember(remoteCover) {
                    mutableStateOf(CoverCache.localUri(remoteCover))
                }
                LaunchedEffect(remoteCover) {
                    if (remoteCover != null) coverUri = CoverCache.resolve(remoteCover)
                }
                if (remoteCover != null) {
                    RemoteImage(
                        url = coverUri ?: remoteCover,
                        contentDescription = item.entry.title,
                        modifier = Modifier.fillMaxSize().aspectRatio(3f / 4f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
                if (item.pinned) {
                    Text(
                        text = "置顶",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(
                                MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                if (item.unreadCount > 0) {
                    Text(
                        text = item.unreadCount.toString(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.entry.title,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 网站源标签（第七轮 #5）：书架页直接显示每本漫画来自哪个源站
            // （Komiic / 包子漫画），多源书架不用点进详情也能分清。
            Text(
                sourceNameOf(item.entry.source),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
        // 右键菜单（R-09 修复：菜单要贴在按下点，不是离鼠标老远）。
        //
        // 旧实现用 DropdownMenu + DpOffset(格子内坐标)——DropdownMenu 的 offset 是相对
        // 锚点的又一套坐标空间，格子内坐标喂进去菜单直接弹到窗口别处（用户实测
        // 「右键里鼠标距离很远很远」）。改用 Popup + PopupPositionProvider：
        // 按下点（格子内）+ 格子左上角（窗口坐标）合成**窗口绝对坐标**，菜单顶左角
        // 直接落在按下点上；再按窗口尺寸夹取，靠边时不会弹出屏幕外。
        if (menuOpen) {
            val pressWindow = IntOffset(
                cellOriginInWindow.x + pressInCell.x.roundToInt(),
                cellOriginInWindow.y + pressInCell.y.roundToInt()
            )
            val provider = remember(pressWindow) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentBounds: IntSize
                    ): IntOffset = IntOffset(
                        pressWindow.x.coerceIn(
                            0,
                            (windowSize.width - popupContentBounds.width).coerceAtLeast(0)
                        ),
                        pressWindow.y.coerceIn(
                            0,
                            (windowSize.height - popupContentBounds.height).coerceAtLeast(0)
                        )
                    )
                }
            }
            Popup(
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
                popupPositionProvider = provider
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    // width(IntrinsicSize.Max) 是必须的（Material3 自家
                    // DropdownMenuContent 也这么做）：桌面端 Popup 的内容按
                    // **父窗口大小**做最大约束测量，而 DropdownMenuItem 内部带
                    // fillMaxWidth——不加这一行，每项都会被撑到整个窗口宽，
                    // 弹层跟着变成全屏 Surface，用户实测「右键面积太大了
                    // 覆盖了整个画面」（2026-10-23 反馈）。加上后 fillMaxWidth
                    // 解析到最宽文本项的固有宽度，菜单恢复窄条。
                    Column(
                        Modifier
                            .padding(vertical = 4.dp)
                            .width(IntrinsicSize.Max)
                    ) {
                        DropdownMenuItem(
                            text = { Text("打开", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onClick() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.pinned) "取消置顶" else "置顶", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onTogglePin() }
                        )
                        DropdownMenuItem(
                            text = { Text("在浏览器中打开", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onOpenBrowser() }
                        )
                        DropdownMenuItem(
                            text = { Text("移出书架", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}
