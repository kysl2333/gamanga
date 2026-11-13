package app.gamanga.desktop.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.SourceSearchState
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.LoginUrls
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.domainOf
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.RemoteImage
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.DragStripHeight
import app.gamanga.desktop.ui.shell.WaveIconButton
import app.gamanga.desktop.ui.theme.AppFont
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlin.math.roundToInt

/**
 * 全局搜索的结果区（2026-10-27 第二轮改版）。
 *
 * ## 这一版和上一版的区别（用户 8 条反馈里的 4 条）
 *
 * | 用户原话 | 改法 |
 * |---|---|
 * | 「我没有 komga 这个网站源可以去掉」 | 源列表不再用 `SourceType.entries`，改成调用方传进来的**实际有适配器的源**（`Sources.all()`）。Komga 只是枚举里的死项，没有适配器，自然消失 |
 * | 「把所有网站源旁边提示的按回车搜索去掉」 | 删掉 `SourceSectionTitle` 里的「按回车搜索」分支 |
 * | 「正在搜索时，漫画源增加一个伪搜索的动效图案…搜索到内容后再渐变成封面，未搜索时动效也要消失」 | 每源内容用 [Crossfade]：`Loading` 显示 2 排**呼吸骨架卡**，`Success` 交叉淡化成真封面，未搜（null）时整块不渲染 |
 * | 「每个漫画源只展示两排，剩余的叠在第二排最后一个封面位置，只有点击后才展开」 | [SourceGrid] 按列数取 `2 × columns` 个槽位；超出时第 2 排最后一格换成 **[+N] 叠加卡**，点击就地展开 |
 *
 * ## 为什么从「一个大网格」改成「每源一块」
 *
 * 上一版所有源混在一个 `LazyVerticalGrid` 里，没法做「每源只两排」——
 * 网格只认全局行，不认「某个源的第 2 排」。现在外层是 `LazyColumn`，
 * 每个源是独立 item，内部自己排两排。
 *
 * ## F-128（2026-10-27）：「只显示书架那一行」是怎么做的
 *
 * 用户口径：「搜索栏有文字但未点击搜索或回车时，只显示书架那一行；点击搜索或回车后，
 * 才显示漫画源信息」。这里**不加开关参数**，而是让调用方在「还没提交」时传
 * `sourceTypes = emptyList()` —— 没有源，自然就只剩书架那一段。
 * 少一个布尔参数，就少一处「参数和列表互相矛盾」的可能。
 */
@Composable
fun GlobalSearchPane(
    query: String,
    /** 书架命中（已按简繁归一化过滤好）。 */
    shelfHits: List<LibraryRepository.ShelfItem>,
    /** **实际可用的源**（调用方传 `Sources.all().map { it.type }`，不要传 `SourceType.entries`）。 */
    sourceTypes: List<SourceType>,
    /** 各源搜索结果状态（key 缺省 = 还没搜）。 */
    sourceStates: Map<SourceType, SourceSearchState>,
    onOpenShelf: (LibraryRepository.ShelfItem) -> Unit,
    onOpenSource: (SourceType, SourceManga) -> Unit,
    onOpenBrowser: (url: String, domain: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 用户 2026-10-26：「再次搜索时界面没回到顶点」——关键词变化就回顶
    LaunchedEffect(query) { listState.requestScrollToItem(0) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = maxWidth - Tokens.Grid.ContentPaddingH * 2
        val columns = Tokens.Grid.columnsFor(contentWidth)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Tokens.Grid.ContentPaddingH,
                end = Tokens.Grid.ContentPaddingH,
                // 顶部让出「拖拽带 36 + 顶栏 60」= 96dp，与书架页的 contentPadding.top 同值
                top = 96.dp,
                // 底部让出 dock（浮层），否则最后一行被永久遮住
                bottom = 96.dp,
            ),
        ) {
            // ---- 书架命中 ----
            if (query.isNotBlank()) {
                item(key = "sec-shelf") {
                    SectionTitle(
                        title = "书架",
                        count = if (shelfHits.isEmpty()) null else shelfHits.size,
                        hint = if (shelfHits.isEmpty()) "书架里没有这本" else null,
                    )
                    if (shelfHits.isNotEmpty()) {
                        SourceGrid(
                            columns = columns,
                            items = shelfHits,
                            keyOf = { it.entry.id },
                        ) { item ->
                            SearchCard(
                                title = item.entry.title,
                                coverUrl = item.entry.coverUrl,
                                placeholderColor = Color(item.coverColorArgb),
                                subtitle = item.chapterLabel,
                                onClick = { onOpenShelf(item) },
                            )
                        }
                    }
                }
            }

            // ---- 各源 ----
            sourceTypes.forEach { type ->
                item(key = "src-$type") {
                    val state = sourceStates[type]
                    SourceSectionTitle(
                        type = type,
                        state = state,
                        onOpenSite = {
                            val domain = domainOf(type)
                            val url = LoginUrls.forDomain(domain) ?: "https://$domain"
                            onOpenBrowser(url, domain)
                        },
                    )
                    // 三态交叉淡化：未搜(null) / 搜索中(Loading) / 有结果(Success)
                    val phase = when (state) {
                        null -> 0
                        is SourceSearchState.Loading -> 1
                        is SourceSearchState.Success -> 2
                        is SourceSearchState.Failed -> 3
                    }
                    Crossfade(targetState = phase, animationSpec = tween(280), label = "srcPhase") { p ->
                        when (p) {
                            1 -> SkeletonGrid(columns = columns)
                            2 -> {
                                val items = (state as? SourceSearchState.Success)?.items.orEmpty()
                                if (items.isEmpty()) {
                                    StateLine("这个源没有这本")
                                } else {
                                    SourceGrid(
                                        columns = columns,
                                        items = items,
                                        keyOf = { it.sourceId },
                                    ) { manga ->
                                        SearchCard(
                                            title = manga.title,
                                            coverUrl = manga.coverUrl,
                                            placeholderColor = Tokens.Color.BgSunken,
                                            subtitle = manga.author,
                                            onClick = { onOpenSource(type, manga) },
                                        )
                                    }
                                }
                            }
                            3 -> {
                                // 失败不只是一句红字：网络类失败（连不上 / 超时 / DNS）要把
                                // 「可能需要开代理」说清楚，否则用户只会以为程序坏了。
                                val message = (state as? SourceSearchState.Failed)?.message?.take(60) ?: "搜索失败"
                                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StateLine(message, danger = true, padded = false)
                                    StateLine(
                                        if (looksLikeNetworkFailure(message)) "网络原因导致，可能需要开启代理后重试"
                                        else "可稍后重试，或在浏览器里打开该站点看看",
                                        padded = false,
                                    )
                                }
                            }
                            else -> Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一个源的卡片网格：**最多两排**，超出部分折叠成第 2 排末位的「+N」卡。
 *
 * ## 为什么不用 LazyVerticalGrid
 *
 * 外层已经是 `LazyColumn`（每个源一个 item），LazyGrid 套在 Lazy 里高度不可知
 * （无限高度约束会崩）。这里每源最多两排，用 `Row + weight` 手工排更稳、也更好控制折叠。
 *
 * 展开状态存在本组件内（`remember(items.size)`）：换关键词时结果变了就自动收回到两排。
 */
@Composable
private fun <T> SourceGrid(
    columns: Int,
    items: List<T>,
    keyOf: (T) -> String,
    card: @Composable (T) -> Unit,
) {
    var expanded by remember(items.size, columns) { mutableStateOf(false) }
    val capacity = columns * 2
    // 超出容量时，最后一格留给「+N」，所以可见卡 = capacity - 1
    val collapsed = !expanded && items.size > capacity
    val visible = if (expanded || !collapsed) items else items.take(capacity - 1)
    val hidden = items.size - visible.size

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapV)) {
        var index = 0
        while (index < visible.size) {
            val rowCount = minOf(columns, visible.size - index)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapH),
            ) {
                repeat(rowCount) { i ->
                    Box(Modifier.weight(1f)) { card(visible[index + i]) }
                }
                // 折叠卡占最后一排的最后一格
                val showExpand = collapsed && index + rowCount == visible.size && rowCount < columns
                if (showExpand) {
                    Box(Modifier.weight(1f)) {
                        ExpandBadge(hidden = hidden) { expanded = true }
                    }
                }
                // 空位补 **剩下的** 格子：必须扣掉折叠卡占的那一格，
                // 否则「6 张卡 + 1 个 Spacer + 1 个折叠卡」= 8 份权重，
                // 每张卡只有 7/8 的宽度 → 折叠时封面比例偏窄，展开后才正常
                // （用户 2026-10-27：「封面排版比例还是不对，只有点击展开后才会变成正常排版比例」）。
                repeat(columns - rowCount - (if (showExpand) 1 else 0)) {
                    Spacer(Modifier.weight(1f))
                }
            }
            index += rowCount
        }
        // 极端情况：visible 正好排满整排但还有隐藏项 → 单独补一排放折叠卡
        if (collapsed && visible.size % columns == 0) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapH),
            ) {
                Box(Modifier.weight(1f)) { ExpandBadge(hidden = hidden) { expanded = true } }
                repeat(columns - 1) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 「+N」叠加卡：与封面同尺寸、同圆角，压在第 2 排末位。点开就地展开。 */
@Composable
private fun ExpandBadge(hidden: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(Tokens.Radius.Card))
            .background(if (hovered) Tokens.Color.BgSunken else Color(0xCCFFFFFF))
            .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Card))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MixText(
                text = "+$hidden",
                color = Tokens.Color.Accent,
                fontSize = Tokens.Type.Subtitle.size,
                style = Tokens.Type.Subtitle.copy(weight = FontWeight.SemiBold).toTextStyle()
                    .copy(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.height(2.dp))
            MixText(
                text = "展开",
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
        }
    }
}

/**
 * 伪搜索骨架：两排「还没拿到封面的空卡」，用**明度呼吸**表示在等数据。
 *
 * 用户 2026-10-27：「增加一个伪搜索的动效图案，就是那种等待搜索出封面的感觉」。
 * UI-SPEC 第 5 节明令**禁止转圈 loading**，所以这里用呼吸（alpha 0.45↔0.85，1400ms 一周期）。
 */
@Composable
private fun SkeletonGrid(columns: Int) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val breath by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonBreath",
    )
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapV)) {
        repeat(2) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapH),
            ) {
                repeat(columns) {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .graphicsLayer { alpha = breath }
                            .clip(RoundedCornerShape(Tokens.Radius.Card))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Tokens.Color.BgSunken, Color(0xFFEDEDF0)),
                                )
                            )
                    )
                }
            }
        }
    }
}

/** 一行状态文字（空结果 / 失败）。 */
@Composable
private fun StateLine(text: String, danger: Boolean = false, padded: Boolean = true) {
    MixText(
        text = text,
        color = if (danger) Tokens.Color.Danger else Tokens.Color.TextTertiary,
        fontSize = Tokens.Type.Caption.size,
        style = Tokens.Type.Caption.toTextStyle(),
        modifier = if (padded) Modifier.padding(vertical = 6.dp) else Modifier,
    )
}

/** 连接类失败（超时 / 连不上 / DNS / SSL / 重置）才提示开代理，解析错、404 之类不误导。 */
internal fun looksLikeNetworkFailure(message: String): Boolean {
    val text = message.lowercase()
    return listOf("connect", "timed out", "timeout", "unknown host", "unresolved", "ssl", "handshake", "reset", "refused", "unreachable", "连接", "超时", "网络")
        .any { text.contains(it) }
}

/** 分区标题（书架）：标题 + 计数 + 右侧提示。 */
@Composable
private fun SectionTitle(title: String, count: Int?, hint: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MixText(
            text = title,
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.copy(weight = FontWeight.SemiBold).toTextStyle(),
        )
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            MixText(
                text = "$count 部",
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
            )
        }
        if (hint != null) {
            Spacer(Modifier.width(10.dp))
            MixText(
                text = hint,
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
            )
        }
    }
}

/**
 * 源分区标题：源名 + **登录小图标** + 状态。
 *
 * 2026-10-27 去掉了「按回车搜索」提示（用户：「把所有网站源旁边提示的按回车搜索去掉」）——
 * 那句提示在五个源下面重复五遍，视觉噪音很大；回车提示改到搜索栏里（右端一个小 ↵ 图标）。
 */
@Composable
private fun SourceSectionTitle(
    type: SourceType,
    state: SourceSearchState?,
    onOpenSite: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MixText(
            text = sourceNameOf(type),
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.copy(weight = FontWeight.SemiBold).toTextStyle(),
        )
        Spacer(Modifier.width(8.dp))
        // 登录入口：圆形小图标，hover 变强调色
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onOpenSite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DockIcons.Globe,
                contentDescription = "打开内嵌浏览器",
                tint = if (hovered) Tokens.Color.Accent else Tokens.Color.TextTertiary,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        // 需要登录态的源（拷贝漫画 / Komiic）在浏览器图标旁挂一个灰字提示：
        // 用户不知道「点地球图标」是要先登录，搜不到就以为是源坏了。
        if (LoginUrls.forType(type) != null && !hovered) {
            MixText(
                text = "需登录",
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
            Spacer(Modifier.width(10.dp))
        }
        when {
            hovered -> MixText(
                text = "打开内嵌浏览器",
                color = Tokens.Color.Accent,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
            state is SourceSearchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = Tokens.Color.Accent)
                Spacer(Modifier.width(6.dp))
                MixText(
                    text = "搜索中",
                    color = Tokens.Color.TextTertiary,
                    fontSize = Tokens.Type.Micro.size,
                    style = Tokens.Type.Micro.toTextStyle(),
                )
            }
            state is SourceSearchState.Failed -> MixText(
                text = "连接失败",
                color = Tokens.Color.Danger,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
            state is SourceSearchState.Success -> MixText(
                text = "${state.items.size} 部 · ${state.elapsedMs} ms",
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
            )
            // 未搜 / 已搜完但该源还没回来 → 什么都不显示（不再有「按回车搜索」）
            else -> Unit
        }
    }
}

/**
 * 搜索结果卡：封面 3:4 + 一行标题 + 一行副标题。
 *
 * `internal` 而不是 `private`：浏览历史（[BrowseHistoryPane]）用的是**同一张卡**——
 * 用户要求「展示样式依据现有 UI 风格及规范设计」，两处各写一份必然走样。
 *
 * ## hover 动效（2026-10-27 第五轮：用户「浏览页动效太死硬，要对齐书架」）
 *
 * 旧实现是 `.padding(top = if (hovered) 0.dp else 3.dp)` —— 一帧内**布局**跳 3dp，
 * 阴影也没有动画，所以鼠标移上去像被「啪」地弹了一下。
 *
 * 现在与 `ShelfCard` 完全同一套：`animateDpAsState(tween(170))` 驱动
 * **抬升 3dp**（`graphicsLayer.translationY`，只动封面不动文字、不触发布局）
 * + **阴影 5 → 18dp**，环境色/点色都取中性黑（彩色阴影会让整墙发脏）。
 *
 * @param multi 多选态（浏览历史用）：此时不抬升，改在右上角画勾选圈
 * @param picked 已被勾选
 * @param onContextMenu 右键回调，上报**窗口绝对坐标**（菜单是页面级的一个，卡片不自己弹）
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SearchCard(
    title: String,
    coverUrl: String?,
    placeholderColor: Color,
    subtitle: String?,
    onClick: () -> Unit,
    multi: Boolean = false,
    picked: Boolean = false,
    onContextMenu: ((Offset) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 多选态下不做 hover 抬升：那时用户要的是「点选」，抬升会让人以为已经选中（同书架口径）
    val lifted = hovered && (!multi || picked)
    val lift by animateDpAsState(
        targetValue = if (lifted) 3.dp else 0.dp,
        animationSpec = tween(170),
        label = "searchCardLift",
    )
    val elevation by animateDpAsState(
        targetValue = if (lifted) 18.dp else 5.dp,
        animationSpec = tween(170),
        label = "searchCardElevation",
    )
    var originInWindow by remember { mutableStateOf(IntOffset.Zero) }

    Column(
        Modifier
            .onGloballyPositioned { coords ->
                val o = coords.localToWindow(Offset.Zero)
                originInWindow = IntOffset(o.x.roundToInt(), o.y.roundToInt())
            }
            // 右键必须在点击之前截胡并 consume：`clickable` 不区分左右键，
            // 右键会既弹菜单又打开详情页（书架页踩过，这里同一套写法）
            .onPointerEvent(PointerEventType.Press) { event ->
                when {
                    event.buttons.isSecondaryPressed && onContextMenu != null -> {
                        val press = event.changes.first().position
                        event.changes.forEach { it.consume() }
                        onContextMenu(Offset(originInWindow.x + press.x, originInWindow.y + press.y))
                    }
                    event.buttons.isPrimaryPressed -> {
                        event.changes.forEach { it.consume() }
                        onClick()
                    }
                }
            }
            .hoverable(interaction)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 抬升只作用在封面上（文字保持原位）——书架卡片就是这样，整块动会让人眼晕
                .graphicsLayer { translationY = -lift.toPx() }
                .shadow(
                    elevation = elevation,
                    shape = RoundedCornerShape(Tokens.Radius.Card),
                    clip = false,
                    ambientColor = Color(0x14000000),
                    spotColor = Color(0x1F000000),
                )
                .clip(RoundedCornerShape(Tokens.Radius.Card))
                .then(
                    if (picked) Modifier.border(3.dp, Tokens.Color.Accent, RoundedCornerShape(Tokens.Radius.Card))
                    else Modifier
                )
                .aspectRatio(3f / 4f)
                .background(placeholderColor),
        ) {
            if (coverUrl != null) {
                RemoteImage(
                    url = coverUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (multi) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (picked) Tokens.Color.Accent else Color(0xEBFFFFFF))
                        .border(
                            width = 1.5.dp,
                            color = if (picked) Tokens.Color.Accent else Color(0x2E000000),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) {
                        Icon(
                            imageVector = DockIcons.Check,
                            contentDescription = null,
                            tint = Tokens.Color.OnAccent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // **文字区固定高度**（用户 2026-10-27：「封面没有对齐，封面比例各不相同」）。
        // 根因：同一排里有的卡有副标题、有的没有（源结果 author 常为 null），
        // 卡片总高就参差不齐，视觉上像「封面比例不同」。固定 36dp = 标题 22 + 间隙 2 + 副标题 16。
        Box(Modifier.height(36.dp).fillMaxWidth()) {
            Column {
                MixText(
                    text = title,
                    color = Tokens.Color.TextPrimary,
                    fontSize = Tokens.Type.Body.size,
                    style = Tokens.Type.Body.copy(weight = FontWeight.Medium).toTextStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    MixText(
                        text = subtitle,
                        color = Tokens.Color.TextSecondary,
                        fontSize = Tokens.Type.Caption.size,
                        style = Tokens.Type.Caption.toTextStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 搜索顶栏：居中「胶囊输入框 + 圆形搜索键」。
 *
 * 与书架页的顶栏同构（宽度也固定 420dp）。区别：这里的搜索键/回车才真正开始搜源。
 *
 * @param showEnterAction 输入了内容、但还没提交时，在**搜索栏最右端**浮出一个小回车图标。
 *   用户 2026-10-27 第四版口径：「取消气泡回车按钮的提示，改为在搜索栏最右侧放置一个
 *   回车小图标，并与现有 UI 风格保持统一」——前三版都是往搜索框**外面**挂东西
 *   （气泡/水滴），这一版回到框内、与搜索框同高同色，就是搜索栏的一部分。
 * @param bounds 传同一个 [SearchBarBounds] 给页面根的
 *   [dismissSearchFocusOnOutsidePress]，就能实现「点搜索框外面失焦」。
 * @param leading 搜索条**左端外侧**的一个槽位（书架页放阅读统计入口，F-38）。
 *   为什么不用「Row 里排一排」：那样加了按钮整条搜索栏会右移，两边页面就不齐了。
 *   这里用 `offset` 把槽位锚在居中搜索条的左端之外，搜索条自己的位置**一个像素都不动**。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    showEnterAction: Boolean = false,
    bounds: SearchBarBounds? = null,
    leading: (@Composable () -> Unit)? = null,
    accent: Color = Tokens.Color.Accent,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier.fillMaxWidth().height(60.dp).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(SearchBarWidth)
                // 记下这一组在窗口里的矩形：页面根据此判断「这一下是不是点在搜索框外」
                .onGloballyPositioned { bounds?.recordBar(it.boundsInWindow()) },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .shadow(2.dp, RoundedCornerShape(Tokens.Radius.Capsule))
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.BgElevatedSolid)
                    .border(
                        width = 1.dp,
                        color = if (focused) accent.copy(alpha = 0.45f) else Tokens.Color.BorderHairline,
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
                            text = "搜索漫画",
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
                        cursorBrush = SolidColor(accent),
                        interactionSource = interaction,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                }
                // 回车小图标：搜索栏**内部最右端**，与输入框同高的一条，属于搜索栏本身
                EnterActionIcon(visible = showEnterAction, onClick = onSearch)
            }
            WaveIconButton(
                icon = DockIcons.Search,
                contentDescription = "搜索",
                onClick = onSearch,
                size = 42.dp,
                accent = accent,
            )
        }

        // 左端外侧的槽位：中心左移「半个搜索条 + 间距 + 半个按钮」，
        // 于是它的右边缘正好落在搜索条左边缘外 [LeadingGap]。
        // 搜索条自己的位置**一个像素都不动**——两边页面的搜索栏仍然严格对齐。
        leading?.let { slot ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = -(SearchBarWidth / 2 + LeadingGap + LeadingSize / 2)),
            ) { slot() }
        }
    }
}

/** 搜索条固定宽度（原硬编码 420dp，抽成常量给 offset 计算用）。 */
private val SearchBarWidth = 420.dp

/**
 * **搜索栏到窗口顶边的距离**（2026-10-29 用户第三次提「搜索栏没对齐」后定的唯一来源）。
 *
 * 用户原话：「两截图搜索栏没对齐，推荐页和书架的搜索栏位置应该对齐浏览页的位置，
 * 且放大缩小都是一样对齐的状态」。
 *
 * 之前三个页面各走一套让位：浏览页 = 根 padding 20dp + 拖拽带 36dp = **56dp**；
 * 书架页 = 只让拖拽带 = **36dp**；推荐页 = GamangaApp 外层让拖拽带 = **36dp**。
 * 于是书架/推荐比浏览页高 20dp（用户截图里就是那一条）。
 *
 * 现在**只有这一个常量**：谁用 [SearchTopBar] 谁就在自己身上 `padding(top = SearchBarTopInset)`，
 * 且该页面必须是「沉浸式」（GamangaApp 不再额外让拖拽带），否则会叠成两份。
 * 横向不受影响：搜索条固定 [SearchBarWidth] 居中，窗口放大缩小都保持同一条中线。
 */
val SearchBarTopInset: Dp = DragStripHeight + 20.dp

/** 左端槽位与搜索条的间距。 */
private val LeadingGap = 8.dp

/** 左端槽位的宽度（与 `StatsButton` 的 44dp 微长方形对齐；offset 只关心横向中心）。 */
private val LeadingSize = 44.dp

/**
 * 搜索栏右端的回车小图标（用户 2026-10-27 第四版）。
 *
 * ## 为什么要占位 26dp 而不是「有才占」
 *
 * 用户输入第一个字时图标才出现。若用 `if (visible)` 直接插入/移除，
 * 输入框宽度会突然少/多 26dp —— 正在打字的人会看到光标和文字**横跳一下**。
 * 所以外壳恒定 26dp，只有里面的圆钮做透明度 + 缩放进出（160ms）。
 *
 * 视觉：与源分区标题里的「登录小地球」同构（24dp 圆、hover 变 AccentSoft 底 + Accent 色），
 * 保证整页只有一种小图标语言。
 */
@Composable
private fun EnterActionIcon(visible: Boolean, onClick: () -> Unit) {
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160, easing = Tokens.Motion.DrawerEasing),
        label = "enterActionAppear",
    )
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        if (appear <= 0.01f) return@Box
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    alpha = appear
                    val s = 0.82f + 0.18f * appear
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DockIcons.Enter,
                contentDescription = "按回车搜索",
                tint = if (hovered) Tokens.Color.Accent else Tokens.Color.TextTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
