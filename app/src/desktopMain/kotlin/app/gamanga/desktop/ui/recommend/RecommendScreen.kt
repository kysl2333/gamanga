@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.gamanga.desktop.ui.recommend

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import app.gamanga.desktop.source.BangumiClient
import app.gamanga.desktop.source.BangumiMirrors
import app.gamanga.desktop.ui.detail.DetailEasing
import app.gamanga.desktop.ui.detail.DetailEnterMs
import app.gamanga.desktop.ui.detail.DetailExitMs
import app.gamanga.desktop.ui.detail.DetailFadeInDelayMs
import app.gamanga.desktop.ui.detail.DetailFadeInMs
import app.gamanga.desktop.ui.detail.DetailFadeOutMs
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import app.gamanga.desktop.ui.search.SearchBarTopInset
import app.gamanga.desktop.ui.search.SearchTopBar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Desktop
import java.net.URI

internal object RecommendTokens {
    val Pink = Color(0xFFD94C7B)
    val Bright = Color(0xFFFB7299)
    val Soft = Color(0xFFFFEDF3)
    val Ground = Tokens.Color.BgBase
    val Ink = Tokens.Color.TextPrimary
    val Muted = Tokens.Color.TextSecondary
    val Pad = 28.dp
    val Gap = 12.dp
    val Shape = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(50)
    val Floating = 46.dp
    val Bottom = 100.dp
    val FloatBottom = 32.dp
    val DetailWidth = .72f
    val Cover = 144.dp
    val RelatedColumns = 6
    val IconSize = 36.dp
    val ChartHeight = 92.dp
    val Pale = Color(0xFFFFF7FA)
    val NavWidth = 108.dp
    val NavHeight = 48.dp
    val NavShape = RoundedCornerShape(12.dp)
    val AccountWidth = 480.dp
    val FieldHeight = 48.dp
}
internal fun Modifier.primaryPress(action: () -> Unit): Modifier = this
    .semantics { role = Role.Button; onClick { action(); true } }
    .onKeyEvent { if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) { action(); true } else false }
    .focusable().onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isPrimaryPressed && event.changes.any { !it.isConsumed }) { event.changes.forEach { it.consume() }; action() } }
internal val LocalReduceMotion = staticCompositionLocalOf { false }
private val labels = listOf("想读", "读过", "在读", "搁置", "抛弃")
private val categories = listOf("全部", "恋爱", "冒险", "奇幻", "日常", "科幻", "悬疑", "搞笑", "校园", "热血")
private fun symbol(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round, pathBuilder = draw)
}.build()
private val SearchIcon = symbol("搜索") { moveTo(10f,3f); curveTo(1f,3f,1f,17f,10f,17f); curveTo(19f,17f,19f,3f,10f,3f); moveTo(15f,15f); lineTo(21f,21f) }
private val RefreshIcon = symbol("刷新") { moveTo(20f,10f); curveTo(19f,3f,9f,1f,5f,7f); moveTo(20f,4f); lineTo(20f,10f); lineTo(14f,10f); moveTo(4f,14f); curveTo(5f,21f,15f,23f,19f,17f); moveTo(4f,20f); lineTo(4f,14f); lineTo(10f,14f) }
private val SortIcon = symbol("排序") { moveTo(4f,6f); lineTo(19f,6f); moveTo(4f,12f); lineTo(14f,12f); moveTo(4f,18f); lineTo(9f,18f); moveTo(18f,11f); lineTo(18f,21f); moveTo(15f,18f); lineTo(18f,21f); lineTo(21f,18f) }
private val MotionIcon = symbol("动效") { moveTo(3f,8f); curveTo(7f,1f,11f,15f,15f,8f); moveTo(3f,16f); curveTo(7f,9f,11f,23f,15f,16f); moveTo(20f,5f); lineTo(20f,19f) }
private val UserIcon = symbol("账号") { moveTo(12f,3f); curveTo(5f,3f,5f,12f,12f,12f); curveTo(19f,12f,19f,3f,12f,3f); moveTo(3f,22f); curveTo(3f,12f,21f,12f,21f,22f) }
private val TrashIcon = symbol("删除") { moveTo(4f,6f); lineTo(20f,6f); moveTo(9f,6f); lineTo(9f,3f); lineTo(15f,3f); lineTo(15f,6f); moveTo(6f,6f); lineTo(7f,21f); lineTo(17f,21f); lineTo(18f,6f); moveTo(10f,10f); lineTo(10f,17f); moveTo(14f,10f); lineTo(14f,17f) }
internal val CloseIcon = symbol("关闭") { moveTo(6f,6f); lineTo(18f,18f); moveTo(18f,6f); lineTo(6f,18f) }
private val UpIcon = symbol("回到顶部") { moveTo(5f,12f); lineTo(12f,5f); lineTo(19f,12f); moveTo(12f,5f); lineTo(12f,21f) }
private val LinkIcon = symbol("外部打开") { moveTo(13f,4f); lineTo(21f,4f); lineTo(21f,12f); moveTo(21f,4f); lineTo(10f,15f); moveTo(9f,5f); lineTo(4f,5f); lineTo(4f,21f); lineTo(20f,21f); lineTo(20f,16f) }
private val CopyIcon = symbol("复制") { moveTo(8f,8f); lineTo(21f,8f); lineTo(21f,21f); lineTo(8f,21f); close(); moveTo(16f,4f); lineTo(4f,4f); lineTo(4f,16f) }
private val ShuffleIcon = symbol("随机") { moveTo(3f,5f); lineTo(7f,5f); lineTo(17f,19f); lineTo(22f,19f); moveTo(18f,16f); lineTo(22f,19f); lineTo(18f,22f); moveTo(3f,19f); lineTo(7f,19f); lineTo(17f,5f); lineTo(22f,5f); moveTo(18f,2f); lineTo(22f,5f); lineTo(18f,8f) }

@Composable
internal fun Copy(text: String, modifier: Modifier = Modifier, strong: Boolean = false, small: Boolean = false, color: Color = RecommendTokens.Ink, maxLines: Int = Int.MAX_VALUE) {
    val role = if (small) Tokens.Type.Caption else Tokens.Type.Body
    MixText(text, modifier = modifier, color = color, fontSize = role.size, style = role.copy(weight = if (strong) FontWeight.SemiBold else FontWeight.Normal).toTextStyle(), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable
internal fun Chip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }; val hover by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (selected) RecommendTokens.Pink else if (hover) RecommendTokens.Soft else RecommendTokens.Pale, tween(if (LocalReduceMotion.current) 0 else Tokens.Motion.HoverMs))
    val lift by animateFloatAsState(if (hover && enabled && !LocalReduceMotion.current) -1f else 0f, tween(Tokens.Motion.HoverMs))
    Box(Modifier.graphicsLayer { translationY = lift }.clip(RecommendTokens.Button).background(bg).hoverable(interaction).then(if (enabled) Modifier.primaryPress(onClick) else Modifier)
        .semantics(mergeDescendants = true) { this.selected = selected; if (!enabled) disabled() }
        .padding(horizontal = 16.dp, vertical = 9.dp)) { Copy(text, strong = selected, color = if (!enabled) RecommendTokens.Muted else if (selected) Color.White else RecommendTokens.Pink) }
}
@Composable
internal fun RoundAction(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }; val hover by interaction.collectIsHoveredAsState()
    var clicked by remember { mutableStateOf(false) }
    LaunchedEffect(clicked) { if (clicked) { delay(1400); clicked = false } }
    TooltipArea(tooltip = { Surface(shadowElevation = 4.dp, shape = RecommendTokens.Button, color = Color.White) { Copy(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), small = true) } }, delayMillis = 350) {
        Box(Modifier.size(RecommendTokens.IconSize).clip(CircleShape).background(if (selected || hover) RecommendTokens.Soft else Color.White)
            .border(1.dp, Tokens.Color.BorderHairline, CircleShape).hoverable(interaction)
            .semantics { contentDescription = label }.then(if (enabled) Modifier.primaryPress { clicked = true; onClick() } else Modifier), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) RecommendTokens.Pink else Tokens.Color.TextTertiary)
        }
        if (clicked) Popup(alignment = Alignment.BottomCenter) { Surface(shape = RecommendTokens.Button, shadowElevation = 3.dp) { Copy(label, Modifier.padding(8.dp), small = true) } }
    }
}
private fun external(url: String, state: RecommendState) { try { Desktop.getDesktop().browse(URI.create(url)) } catch (_: Exception) { state.message = "无法打开系统浏览器" } }

@Composable
fun RecommendScreen(state: RecommendState, onPick: (String) -> Unit) {
    var account by remember { mutableStateOf(false) }; var access by remember { mutableStateOf("") }; var confirmClear by remember { mutableStateOf(false) }
    var mirror by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.initialize() }
    val visible = when(state.tab) {
        "我的漫画" -> state.accountItems.values.filter { state.collectionType == 0 || state.records[it.id]?.type == state.collectionType }.filter { state.keyword.isBlank() || it.title.contains(state.keyword, true) || it.original.contains(state.keyword, true) }
        "最近浏览" -> state.history.mapNotNull { state.known[it] }
        else -> state.items
    }
    val grid = state.grid
    LaunchedEffect(grid, state.tab) {
        snapshotFlow { Triple(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, grid.layoutInfo.totalItemsCount, state.loading || state.stack.isNotEmpty()) }
            .distinctUntilChanged().collect { (last, count, busy) ->
                 if (state.tab == "发现" && !state.randomBrowse && state.stack.isEmpty() && !busy && state.error == null && count > 0 && last >= count - 3 && state.offset < state.total) state.search(true)
            }
    }
    CompositionLocalProvider(LocalReduceMotion provides state.reduceMotion) {
        MaterialTheme(colorScheme = lightColorScheme(primary = RecommendTokens.Pink, secondary = RecommendTokens.Bright, surface = Color.White)) {
            Box(Modifier.fillMaxSize().background(RecommendTokens.Ground)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth()) {
                        SearchTopBar(
                            state.keyword,
                            { state.keyword = it },
                            { if (state.tab == "发现") state.search() },
                            accent = RecommendTokens.Pink,
                            // 与浏览页 / 书架页同一个顶距常量（2026-10-29 三页对齐）
                            modifier = Modifier.padding(top = SearchBarTopInset),
                        )
                        Box(Modifier.align(Alignment.CenterEnd).padding(end = RecommendTokens.Pad)) {
                            if (state.avatar != null) Box(Modifier.size(36.dp).clip(CircleShape).background(RecommendTokens.Soft).primaryPress { account = true }.semantics { contentDescription = "Bangumi 已连接，管理账号" }, contentAlignment = Alignment.Center) { Icon(UserIcon, null, Modifier.size(18.dp), tint = RecommendTokens.Pink); RecommendImage(state.avatar!!, "账号头像", Modifier.fillMaxSize()) }
                            else RoundAction(UserIcon, if (state.username.isBlank()) "连接 Bangumi 账号" else "Bangumi 已连接，管理账号", state.token.isNotBlank()) { account = true }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = RecommendTokens.Pad, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 三个标签自己一组：底部对齐（28sp 与 15sp 混排时按中心对齐会让小字浮在半空），
                        // 彼此间距 12dp，和右侧圆形按钮组的 6dp 分开算。
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                            listOf("发现", "我的漫画", "最近浏览").forEach { tab ->
                                key(tab) { NavigationLabel(tab, state.tab == tab) { state.switchTab(tab) } }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 默认走官方线路；只有官方确实不通（或已经在镜像上）才露出换源入口。
                        if (state.mirror.id != BangumiMirrors.OFFICIAL_ID || state.officialUnreachable) {
                            RoundAction(MirrorIcon, "换源 · 当前 ${state.mirror.name}") { mirror = true }
                        }
                        if (state.tab == "发现") RoundAction(ShuffleIcon, if (state.random) "随机漫画，点击切回推荐" else "推荐漫画，点击切换随机", state.random) { state.setRandomMode(!state.random || state.keyword.isNotBlank()) }
                        if (state.tab == "最近浏览") RoundAction(TrashIcon, "清空最近浏览", enabled = state.history.isNotEmpty()) { confirmClear = true }
                        RoundAction(MotionIcon, if (state.reduceMotion) "恢复动效" else "减少动效", state.reduceMotion) { state.setReducedMotion(!state.reduceMotion) }
                         RoundAction(RefreshIcon, if (state.tab == "我的漫画") "同步我的漫画" else "刷新", enabled = !state.loading && !state.syncing) {
                            if (state.tab == "我的漫画") { if (state.token.isBlank()) account = true else state.connect(state.token, false) }
                             else if (state.tab == "发现") state.refresh()
                        }
                    }
                    if (state.tab != "最近浏览") Row(Modifier.fillMaxWidth().padding(horizontal = RecommendTokens.Pad).horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.tab == "发现") {
                             (categories + listOf(state.category).filter { it !in categories }).forEach { c ->
                                 Box(Modifier.padding(end = Tokens.Space.Xs)) { Chip(c, state.category == c) { state.category = c; state.search() } }
                             }
                            if (!state.random) RoundAction(SortIcon, if (state.sort == "rank") "排名优先，点击切换评分" else "评分优先，点击切换排名") { state.sort = if (state.sort == "rank") "score" else "rank"; state.search() }
                        } else {
                            Chip("全部", state.collectionType == 0) { state.collectionType = 0 }
                            labels.forEachIndexed { i, name -> Chip(name, state.collectionType == i + 1) { state.collectionType = i + 1 } }
                        }
                    }
                    if (state.stack.isEmpty()) state.message?.let { text -> Row(Modifier.padding(horizontal = RecommendTokens.Pad), verticalAlignment = Alignment.CenterVertically) { Copy(text, Modifier.weight(1f), small = true, color = RecommendTokens.Pink); RoundAction(CloseIcon, "关闭提示") { state.message = null } } }
                    state.error?.let { text -> Row(Modifier.padding(horizontal = RecommendTokens.Pad), verticalAlignment = Alignment.CenterVertically) { Copy(text, Modifier.weight(1f), small = true); Chip("重试") { state.retry() } } }
                    if (state.loading || state.showSyncProgress) LinearProgressIndicator(Modifier.fillMaxWidth(), color = RecommendTokens.Bright, trackColor = RecommendTokens.Soft)
                    if (visible.isEmpty() && !state.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Copy(when(state.tab) { "我的漫画" -> if (state.showSyncProgress) "正在同步账号漫画…" else if (state.token.isBlank()) "连接 Bangumi 账号，查看我的漫画" else "这个收藏状态下暂无漫画"; "最近浏览" -> "最近浏览会显示在这里"; else -> "没有找到漫画，试试其他分类或关键词" }, color = RecommendTokens.Muted)
                    } else LazyVerticalGrid(GridCells.Adaptive(135.dp), state = grid, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = RecommendTokens.Pad, end = RecommendTokens.Pad, top = 12.dp, bottom = RecommendTokens.Bottom)) {
                         items(visible, key = { it.id }) { item ->
                             CoverCard(item, onDelete = if (state.tab == "最近浏览") ({ state.removeHistory(item.id) }) else null) { state.open(item) }
                         }
                         if (state.tab == "发现" && state.randomBrowse) item(key = "random-next", span = { GridItemSpan(maxLineSpan) }) {
                             RandomBatchAction(state.loading, state::nextRandomBatch)
                         }
                        if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = RecommendTokens.Pink, strokeWidth = 2.dp) } }
                    }
                }
                val detail = state.stack.lastOrNull()
                if (detail != null) Box(Modifier.fillMaxSize().primaryPress { state.clearDetails() })
                AnimatedVisibility(detail != null, enter = slideInVertically(tween(if (state.reduceMotion) 0 else DetailEnterMs, easing = DetailEasing)) { it } + fadeIn(tween(if (state.reduceMotion) 0 else DetailFadeInMs, delayMillis = if (state.reduceMotion) 0 else DetailFadeInDelayMs)), exit = slideOutVertically(tween(if (state.reduceMotion) 0 else DetailExitMs, easing = DetailEasing)) { it } + fadeOut(tween(if (state.reduceMotion) 0 else DetailFadeOutMs)), modifier = Modifier.align(Alignment.BottomCenter)) {
                    var retained by remember { mutableStateOf(detail) }; if (detail != null) retained = detail
                    retained?.let { DetailPanel(state, it, onPick) }
                }
            }
            SyncBubble(state)
            if (mirror) MirrorDialog(state) { mirror = false }
            // 官方线路不通时探到的可用镜像：先问用户，不擅自换源（账号令牌会经过第三方服务器）。
            state.suggestedMirror?.let { candidate ->
                AlertDialog(
                    onDismissRequest = { state.dismissSuggestedMirror() },
                    containerColor = Color.White,
                    titleContentColor = RecommendTokens.Ink,
                    textContentColor = RecommendTokens.Ink,
                    title = { Copy("官方线路不可达", strong = true) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Copy("检测到「${candidate.name}」可用。切换后 Bangumi 接口请求与封面加载会经过该镜像转发，是否使用？", small = true)
                        // 换源风险必须用红字加粗，和换源窗口里同一句话（用户要求两处一致）。
                        Copy("非官方线路有风险：你的 API 请求与访问令牌会被镜像站看到", strong = true, color = Tokens.Color.Danger)
                        Copy("继续用官方线路：推荐页会保持不可用，可稍后重试。", small = true, color = RecommendTokens.Muted)
                    } },
                    confirmButton = { Chip("使用该线路", selected = true) { state.confirmSuggestedMirror() } },
                    dismissButton = { Chip("继续用官方") { state.dismissSuggestedMirror() } },
                )
            }
            if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = Color.White, titleContentColor = RecommendTokens.Ink, textContentColor = RecommendTokens.Ink, title = { Copy("清空最近浏览？", strong = true) }, text = { Copy("只清除推荐页的访问记录。") }, confirmButton = { Chip("清空") { state.clearHistory(); confirmClear = false } }, dismissButton = { Chip("取消") { confirmClear = false } })
            if (account) BangumiAccountDialog(state, access, { access = it },
                onCreate = { external("https://next.bgm.tv/demo/access-token", state) },
                onDismiss = { account = false; access = "" })
        }
    }
}

@Composable
internal fun RandomBatchAction(loading: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Space.Lg), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
        Chip(if (loading) "正在随机…" else "随机新的一批漫画", selected = true, enabled = !loading, onClick = onClick)
    }
}
@Composable
internal fun NavigationLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val duration = if (LocalReduceMotion.current) 0 else Tokens.Motion.HoverMs
    val scale by animateFloatAsState(if (selected) 1.15f else 1f, tween(duration))
    val ink by animateColorAsState(if (selected) RecommendTokens.Ink else RecommendTokens.Muted, tween(duration))
    // 固定占位并禁止折行，选择动画只改绘制尺寸，不挤动相邻标签与内容。
    Box(Modifier.width(RecommendTokens.NavWidth).height(RecommendTokens.NavHeight)
        .clip(RecommendTokens.NavShape).hoverable(interaction).primaryPress(onClick)
        .semantics(mergeDescendants = true) { this.selected = selected }, contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier.graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .5f)
                scaleX = scale
                scaleY = scale
            },
            color = ink,
            fontSize = Tokens.Type.Subtitle.size,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
internal fun SyncBubble(state: RecommendState) {
    val busy = state.saving || state.showSyncProgress
    var confirming by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(busy, state.syncNoticeVersion) {
        if (busy) { visible = true; confirming = false }
        else if (state.syncNotice != null) {
            val version = state.syncNoticeVersion
            visible = true; confirming = true
            delay(if (state.reduceMotion) 0 else 420)
            confirming = false
            delay(2800)
            visible = false
            state.consumeSyncNotice(version)
        } else visible = false
    }
    DisposableEffect(state) {
        onDispose { state.consumeSyncNotice(state.syncNoticeVersion) }
    }
    if (visible) Popup(alignment = Alignment.BottomCenter, offset = androidx.compose.ui.unit.IntOffset(0, -110)) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 9.dp, border = BorderStroke(1.dp, Tokens.Color.BorderHairline)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 13.dp).testTag("recommend-sync-bubble"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy || confirming) CircularProgressIndicator(Modifier.size(19.dp), color = RecommendTokens.Pink, strokeWidth = 2.dp)
                else Text("✓", color = Color(0xFF239861), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Copy(if (busy) "正在同步 Bangumi…" else state.syncNotice.orEmpty(), strong = true)
            }
        }
    }
}

@Composable
internal fun CoverCard(item: BangumiClient.Item, compact: Boolean = false, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }; val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val lift by animateFloatAsState(if (hovered && !LocalReduceMotion.current) -3f else 0f, spring(dampingRatio = .85f, stiffness = 350f))
    Column(Modifier.fillMaxWidth().hoverable(interaction).primaryPress(onClick).graphicsLayer { translationY = lift }) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RecommendTokens.Shape).background(Tokens.Color.BgSunken)) {
            item.coverUrl?.let { RecommendImage(it, item.title, Modifier.fillMaxSize()) }
            if (onDelete != null && (hovered || focused)) Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { RoundAction(TrashIcon, "删除最近浏览：${item.title}") { onDelete() } }
            if (!compact) item.score?.let { score -> Copy("★ $score", Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.White, RecommendTokens.Button).padding(horizontal = 8.dp, vertical = 4.dp), small = true, color = RecommendTokens.Pink, strong = true) }
        }
        Spacer(Modifier.height(8.dp)); Copy(item.title, strong = !compact, small = compact, maxLines = 2)
    }
}
@Composable
internal fun RatingBlock(item: BangumiClient.Item, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text(item.score?.toString() ?: "—", color = RecommendTokens.Ink, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Copy(item.rank?.let { "综合排名 #$it" } ?: "尚无排名", small = true, color = RecommendTokens.Muted)
                Copy("${item.votes.values.sum()} 人评分", small = true, color = RecommendTokens.Muted)
            }
            val max = item.votes.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            Spacer(Modifier.weight(1f))
            Row(Modifier.testTag("recommend-vote-chart").widthIn(max = 320.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                (1..10).forEach { n -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(RecommendTokens.ChartHeight).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height((RecommendTokens.ChartHeight * ((item.votes[n] ?: 0).toFloat() / max)).coerceAtLeast(1.dp)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(RecommendTokens.Bright))
                    }
                    Copy(n.toString(), small = true, color = RecommendTokens.Muted)
                } }
            }
        }
        HorizontalDivider(color = Tokens.Color.BorderHairline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("wish", "doing", "collect", "on_hold", "dropped").forEachIndexed { i, key -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Copy((item.collections[key] ?: 0).toString(), strong = true); Copy(listOf("想读", "在读", "读过", "搁置", "抛弃")[i], small = true, color = RecommendTokens.Muted) } }
        }
    }
}
@Composable
internal fun DetailPanel(state: RecommendState, entry: RecommendState.Detail, onPick: (String) -> Unit) {
    val item = entry.content; val scope = rememberCoroutineScope(); val record = state.records[item.id] ?: RecommendState.Record()
    CompositionLocalProvider(LocalRecommendImageMenu provides true) {
    Box(Modifier.fillMaxWidth(RecommendTokens.DetailWidth).fillMaxHeight(.96f).shadow(18.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = RecommendTokens.Pad, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(CloseIcon, if (state.stack.size > 1) "返回上一本" else "收起详情") { state.closeDetail() }
                Spacer(Modifier.weight(1f)); RoundAction(LinkIcon, "在浏览器打开") { external("https://bgm.tv/subject/${item.id}", state) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(entry.scroll).padding(horizontal = RecommendTokens.Pad).padding(bottom = RecommendTokens.Bottom), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.testTag("recommend-cover").width(RecommendTokens.Cover).aspectRatio(.75f).clip(RecommendTokens.Shape).background(Tokens.Color.BgSunken)) { item.coverUrl?.let { RecommendImage(it, item.title, Modifier.fillMaxSize()) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MixText(item.title, color = RecommendTokens.Ink, fontSize = Tokens.Type.Title.size, style = Tokens.Type.Title.toTextStyle())
                            item.tags.take(6).forEach { tag -> Chip(tag) { state.browseTag(tag) } }
                        }
                        if (item.original != item.title) Copy(item.original, small = true, color = RecommendTokens.Muted)
                        RatingBlock(item, Modifier.fillMaxWidth().testTag("recommend-rating"))
                    }
                }
                Copy("作品介绍", strong = true)
                Copy(item.summary.ifBlank { if (entry.loading) "正在加载简介…" else "暂无简介" })
                if (entry.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = RecommendTokens.Bright)
                entry.error?.let { text -> Chip(text) { state.load(entry) } }
                state.message?.let { Copy(it, small = true, color = RecommendTokens.Pink) }
                Row(verticalAlignment = Alignment.CenterVertically) { Copy("我的漫画", strong = true); Spacer(Modifier.weight(1f)); RoundAction(CopyIcon, "复制书名") { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(item.title), null); state.message = "已复制书名" } }
                if (state.token.isBlank()) Copy("连接 Bangumi 账号后可标记收藏与评分", small = true, color = RecommendTokens.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { i, label -> Chip(label, record.type == i + 1, enabled = state.token.isNotBlank() && !state.saving && !state.syncing) { state.record(item, record.copy(type = i + 1)) } }
                }
                if (state.savingId == item.id) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = RecommendTokens.Pink, strokeWidth = 2.dp); Copy("正在同步收藏与评分…", small = true) }
                if (state.token.isNotBlank()) Copy("我的评分 · ${if (record.rate > 0) "${record.rate} 分" else "尚未评分"}（1 分最低，10 分最高；再点已选分数可清除）", small = true, color = RecommendTokens.Muted)
                if (state.token.isNotBlank()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..10).forEach { n -> Chip("$n 分", record.rate == n, enabled = !state.saving && !state.syncing) { state.record(item, record.copy(type = record.type.takeIf { it > 0 } ?: 3, rate = if (record.rate == n) 0 else n)) } }
                }
                item.info.forEach { (key,value) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Copy(key, Modifier.width(90.dp), small = true, color = RecommendTokens.Muted); Copy(value, Modifier.weight(1f), small = true) } }
                if (entry.related.isNotEmpty()) { Copy("关联作品", strong = true); MiniRow(entry.related.take(6)) { state.open(it) } }
                Copy("同标签发现", strong = true)
                if (entry.similar.isEmpty()) Copy("暂无同标签漫画", small = true, color = RecommendTokens.Muted) else MiniRow(entry.similar.take(6)) { state.open(it) }
                if (entry.people.isNotEmpty()) {
                    Copy("创作人员与角色", strong = true)
                    entry.people.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { person -> Column(Modifier.weight(1f).primaryPress { external("https://bgm.tv/${if (person.character) "character" else "person"}/${person.id}", state) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RecommendTokens.Shape).background(Tokens.Color.BgSunken)) { person.image?.let { RecommendImage(it, person.name, Modifier.fillMaxSize(), alignment = Alignment.TopCenter) } }
                            Copy(person.name, small = true, maxLines = 2); Copy(person.role, small = true, color = RecommendTokens.Muted)
                        } }; repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
            }
        }
        val driftX = remember { Animatable(0f) }; val driftY = remember { Animatable(0f) }
        val reduced = state.reduceMotion || LocalReduceMotion.current
        LaunchedEffect(reduced) {
            if (reduced) { driftX.snapTo(0f); driftY.snapTo(0f) }
            else while (isActive) { coroutineScope {
                launch { driftX.animateTo(kotlin.random.Random.nextFloat() * 40f - 20f, tween(2692, easing = FastOutSlowInEasing)) }
                launch { driftY.animateTo(kotlin.random.Random.nextFloat() * 40f - 20f, tween(3231, easing = FastOutSlowInEasing)) }
            } }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = RecommendTokens.Pad, bottom = RecommendTokens.FloatBottom).size(86.dp, 142.dp)) {
            Column(Modifier.align(Alignment.Center).graphicsLayer { translationX = driftX.value.dp.toPx(); translationY = driftY.value.dp.toPx() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = { onPick(BangumiClient.searchTitle(item.title)) }, modifier = Modifier.size(RecommendTokens.Floating), shape = CircleShape, containerColor = RecommendTokens.Pink, contentColor = Color.White) { Icon(SearchIcon, "在浏览页搜索") }
                SmallFloatingActionButton(onClick = { scope.launch { if (state.reduceMotion) entry.scroll.scrollTo(0) else entry.scroll.animateScrollTo(0, tween(420)) } }, shape = CircleShape, containerColor = Color.White) { Icon(UpIcon, "回到顶部", tint = RecommendTokens.Pink) }
            }
        }
    }
    }
}
@Composable
internal fun MiniRow(items: List<BangumiClient.Item>, onPick: (BangumiClient.Item) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RecommendTokens.Gap)) {
        items.forEach { item -> Box(Modifier.weight(1f)) { CoverCard(item, true) { onPick(item) } } }
        repeat((6 - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}
