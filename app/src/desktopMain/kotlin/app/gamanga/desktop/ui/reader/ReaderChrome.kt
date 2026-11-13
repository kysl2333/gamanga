package app.gamanga.desktop.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.model.ReaderDirection
import app.gamanga.desktop.model.ReaderMode
import app.gamanga.desktop.model.ReaderSettings
import app.gamanga.desktop.ui.RemoteImage
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.WindowDragHandle
import app.gamanga.desktop.ui.theme.Tokens
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.ui.recommend.primaryPress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.platform.testTag

/**
 * 阅读器「外壳」层（阶段 3.2 从 ReaderScreen.kt 拆出）。
 *
 * 这里只有顶栏与底部悬浮控件：**不碰状态存储、不碰手势判定**。
 * 状态全在 `ReaderScreen`，这一层只负责把值画出来、把点击回传。
 *
 * ## 2026-10-30 第二批重排（用户：菜单栏用白色风格 · 子菜单居中对齐 · 舒适动效）
 *
 * | 元素 | 位置 | 风格 |
 * |---|---|---|
 * | 顶栏 | 贴顶 | **白底** + 底部 1px 细线；封面 / 书名·章节·页码 / 拖拽把手 / 浏览器查看 / ✕ |
 * | 上一话·下一话·设置 | 底部正中 | **白色胶囊**（白底 + 细边 + 淡投影），hover 浅灰底 |
 * | 阅读设置二级面板 | 胶囊正上方 | **白卡**；每行右侧选项**等宽均分、居中**（用户要求「子菜单居中对齐」） |
 * | 全屏切换 | 右下角 | **白色圆钮 + 裸图标**（深色画布上白底才看得见） |
 *
 * 底色仍是深色画布（看图不刺眼），但 chrome 全部换成白卡：与壳子（书架 / 详情）
 * 同一套「纯白卡片 + 6% 黑细边 + 极淡投影」语言，从主界面进阅读器不断层。
 * 所有 hover / 选中都是**颜色渐变**（140ms）而不是瞬间切换；面板弹出用 spring，
 * 按压不做位移（阅读器里任何位移都会被误读成"画面在动"）。
 */

/** 顶栏：关闭 / 封面（点开详情）/ 书名 · 章节 · 页码（可点输入跳转）/ 浏览器查看。 */
@Composable
internal fun ReaderBar(
    title: String,
    chapterName: String,
    index: Int,
    total: Int,
    page: Int,
    pageCount: Int,
    /** F-26：点页码输入数字跳转（用户 2026-10-25 要求）。 */
    onJumpPage: (Int) -> Unit,
    onBrowser: () -> Unit,
    onClose: () -> Unit,
    onDoubleClickDrag: (() -> Unit)? = null,
    /**
     * F-143（2026-10-27 用户口径）：「在阅览页点击漫画封面时，在该阅览页内展示二级详情页」。
     * 阅读器原来**没有封面元素**，这里补一个 30×40 的小封面当入口——
     * 比再加一颗图标按钮更符合直觉（用户认的是「封面」这个物件）。
     */
    coverUrl: String? = null,
    onOpenDetail: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Color.ReaderSurface)
            // 白底与深色画布之间只留 1px 细线（用户 2026-10-27：「上面加了一层 UI 风格不好看」，
            // 所以不做阴影、不做圆角，就是一条干净的白色横条）。
            .drawBehind {
                val y = size.height - 1f
                drawLine(
                    color = Tokens.Color.ReaderSurfaceBorder,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            // 2026-10-30 第二轮用户「菜单栏靠上一点」：垂直内边距 6 → 3dp，
            // 配合阅读器窗口的 4dp 阴影边距，白条上沿到窗口顶边只剩 ~7dp。
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 出口：只剩一颗 ✕（用户 2026-10-26：「以后把返回去掉，只保留 X」）。
        // 独立窗口之后它 = **关闭这个阅读器窗口**（不影响主界面和其它阅读器）。
        // 小封面入口：点它弹二级详情页（整本换源 / 看章节进度都在那儿）
        Box(
            Modifier
                .size(width = 26.dp, height = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Tokens.Color.BgSunken)
                .primaryPress(onOpenDetail),
        ) {
            RemoteImage(
                url = coverUrl,
                contentDescription = "打开详情",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.width(230.dp)) {
            Text(
                title,
                color = Tokens.Color.ReaderInk,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            var editing by remember { mutableStateOf(false) }
            var input by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$chapterName · ${index + 1}/${total}",
                    color = Tokens.Color.ReaderInkSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pageCount > 0) {
                    Text(" · ", color = Tokens.Color.ReaderInkTertiary, fontSize = 11.5.sp)
                    if (editing) {
                        // F-26：点页码变输入框，回车跳转、Esc 取消
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.filter { c -> c.isDigit() }.take(5) },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Tokens.Color.ReaderInkAccent,
                                fontSize = 11.5.sp,
                            ),
                            modifier = Modifier
                                .width(46.dp)
                                .background(Tokens.Color.BgSunken, RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Enter, Key.NumPadEnter -> {
                                            input.toIntOrNull()?.let(onJumpPage)
                                            editing = false
                                            true
                                        }
                                        Key.Escape -> { editing = false; true }
                                        else -> false
                                    }
                                }
                        )
                        Text(
                            "/$pageCount",
                            color = Tokens.Color.ReaderInkSecondary,
                            fontSize = 11.5.sp,
                        )
                    } else {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val ink by animateColorAsState(
                            targetValue = if (hovered) Tokens.Color.ReaderInkAccent
                            else Tokens.Color.ReaderInkSecondary,
                            animationSpec = tween(140),
                            label = "pageInk",
                        )
                        Text(
                            "${page + 1}/$pageCount",
                            color = ink,
                            fontSize = 11.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .hoverable(interaction)
                                .clickable(interactionSource = interaction, indication = null) {
                                    input = (page + 1).toString()
                                    editing = true
                                }
                                .padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
        // 顶栏中间的空白 = **窗口拖拽把手**（用户 2026-10-27：「需要拖拽把手，现在阅读器拽不动」）。
        // 阅读器是独立窗口，这个把手就是拖窗口的唯一入口（无系统边框）。
        // 高度 40 → 34dp：它是顶栏里最高的一颗（撑起整条的高度），
        // 不跟着收窄的话，上面 6 → 3dp 的收窄等于白做。
        WindowDragHandle(Modifier.weight(1f).padding(horizontal = 18.dp), height = 42.dp, onDoubleClick = onDoubleClickDrag)
        ReaderTextAction(text = "浏览器查看", onClick = onBrowser)
        ReaderIconButton(
            icon = DockIcons.Close,
            contentDescription = "关闭阅读器",
            size = Tokens.ReaderChrome.ControlSize,
            iconSize = Tokens.ReaderChrome.IconSize,
            onClick = onClose,
        )
    }
}

/**
 * 白卡上的文字按钮（顶栏「浏览器查看」）：hover 时浅灰底 + 主色字。
 *
 * 不用 Material3 `TextButton`：它在白底上自带一层紫色 ripple 与内边距，
 * 和这套「白卡 + 细边 + 140ms 渐变」的语言对不上。
 */
@Composable
private fun ReaderTextAction(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.ReaderSurfaceHover else Color.Transparent,
        animationSpec = tween(140),
        label = "textActionBg",
    )
    val ink by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.ReaderInk else Tokens.Color.ReaderInkSecondary,
        animationSpec = tween(140),
        label = "textActionInk",
    )
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interaction)
            .primaryPress(onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = ink, fontSize = 12.5.sp)
    }
}

/** 通用白卡图标按钮（顶栏 ✕）：固定热区 + hover 浅灰底。 */
@Composable
private fun ReaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.ReaderSurfaceHover else Color.Transparent,
        animationSpec = tween(140),
        label = "iconBtnBg",
    )
    val tint by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.ReaderInk else Tokens.Color.ReaderInkSecondary,
        animationSpec = tween(140),
        label = "iconBtnTint",
    )
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .hoverable(interaction)
            .primaryPress(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * 阅读器**底部正中**的悬浮按钮组（2026-10-30 用户要求 6 + 7）。
 *
 * 一个胶囊里放三件事：上一话 / 下一话 / 阅读设置入口。
 * 2026-10-30 第二批：**从深色半透明换成白色卡片**（用户「菜单栏用白色风格」）——
 * 白底 + 6% 黑细边 + 18dp 淡投影，和书架 dock 同一套几何；深色画面上这块白
 * 就是「浮起来的一条控制栏」，比原来的半透明黑更清楚，也不会糊在漫画上。
 *
 * 为什么不拆成三个独立圆钮：用户明确要「**按钮组**」，而且三个圆钮在画面上
 * 会散成三个孤点；一个胶囊读起来才是「一条控制栏」。
 */
@Composable
internal fun ReaderBottomGroup(
    canPrev: Boolean,
    canNext: Boolean,
    settingsOpen: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleSettings: () -> Unit,
    modifier: Modifier = Modifier,
    chaptersOpen: Boolean = false,
    onToggleChapters: () -> Unit = {},
) {
    val shape = RoundedCornerShape(Tokens.Radius.Capsule)
    // 静态投影避免面板切换期间重复生成阴影。
    val elevation = Tokens.Elevation.Overlay
    Row(
        modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color(0x33000000),
                spotColor = Color(0x55000000),
            )
            .clip(shape)
            .background(Tokens.Color.ReaderSurface)
            .border(1.dp, Tokens.Color.ReaderSurfaceBorder, shape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReaderPillText(text = "上一话", enabled = canPrev, onClick = onPrev)
        ReaderPillText(text = "下一话", enabled = canNext, onClick = onNext)
        // 细分隔线：把「翻话」和「设置」两组动作分开，不加就是三颗并排的按钮
        Spacer(
            Modifier
                .padding(horizontal = 3.dp)
                .width(1.dp)
                .height(18.dp)
                .background(Tokens.Color.ReaderSurfaceBorder)
        )
        ReaderPillIcon(
            icon = DockIcons.Toc,
            contentDescription = "查看目录",
            active = chaptersOpen,
            onClick = onToggleChapters,
        )
        ReaderPillIcon(
            icon = DockIcons.Sliders,
            contentDescription = "阅读设置",
            active = settingsOpen,
            onClick = onToggleSettings,
        )
    }
}

@Composable
internal fun ReaderChapterPanel(
    chapters: List<SourceChapter>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))
    LaunchedEffect(currentIndex) { list.requestScrollToItem(currentIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) }
    val shape = RoundedCornerShape(Tokens.Radius.Drawer)
    Column(modifier.width(Tokens.ReaderChrome.ChapterWidth).shadow(Tokens.Elevation.Overlay, shape)
        .clip(shape).background(Tokens.Color.ReaderSurface).border(Tokens.ReaderChrome.BorderWidth, Tokens.Color.ReaderSurfaceBorder, shape)
        .padding(Tokens.Space.Md).testTag("reader-chapter-panel")) {
        Row(Modifier.fillMaxWidth().padding(bottom = Tokens.Space.Sm), verticalAlignment = Alignment.CenterVertically) {
            Text("目录", color = Tokens.Color.ReaderInk, style = Tokens.Type.Body.toTextStyle(), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${chapters.size} 话", color = Tokens.Color.ReaderInkSecondary, style = Tokens.Type.Caption.toTextStyle())
        }
        Box(Modifier.fillMaxWidth().heightIn(max = Tokens.ReaderChrome.ChapterHeight)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().padding(end = Tokens.Space.Md),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
                itemsIndexed(chapters, key = { i, chapter -> "$i:${chapter.id}" }) { i, chapter ->
                    val active = i == currentIndex
                    Row(Modifier.fillMaxWidth().height(Tokens.ReaderChrome.ChapterRowHeight)
                        .clip(RoundedCornerShape(Tokens.Radius.Control))
                        .background(if (active) Tokens.Color.AccentSoft else Color.Transparent)
                        .testTag("reader-chapter-$i")
                        .semantics { selected = active }
                        .primaryPress { onSelect(i) }.padding(horizontal = Tokens.Space.Md), verticalAlignment = Alignment.CenterVertically) {
                        Text(chapter.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = Tokens.Type.Body.toTextStyle(), color = if (active) Tokens.Color.ReaderInkAccent else Tokens.Color.ReaderInk)
                        if (active) Icon(DockIcons.Check, "当前章节", Modifier.size(Tokens.ReaderChrome.IconSize), tint = Tokens.Color.ReaderInkAccent)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** 悬浮组里的文字按钮（上一话 / 下一话）：34dp 高、hover 浅底、不可用转最弱灰。 */
@Composable
private fun ReaderPillText(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) Tokens.Color.ReaderSurfaceHover else Color.Transparent,
        animationSpec = tween(140),
        label = "pillTextBg",
    )
    val ink by animateColorAsState(
        targetValue = when {
            !enabled -> Tokens.Color.ReaderInkTertiary
            hovered -> Tokens.Color.ReaderInk
            else -> Tokens.Color.ReaderInkSecondary
        },
        animationSpec = tween(140),
        label = "pillTextInk",
    )
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(bg)
            .hoverable(interaction)
            .then(if (enabled) Modifier.primaryPress(onClick) else Modifier)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = ink, fontSize = 12.5.sp)
    }
}

/** 悬浮组里的图标按钮（阅读设置）：34×34，选中态常驻浅蓝底 + 主色图标。 */
@Composable
private fun ReaderPillIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            active -> Tokens.Color.AccentSoft
            hovered -> Tokens.Color.ReaderSurfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "pillIconBg",
    )
    val tint by animateColorAsState(
        targetValue = if (active || hovered) Tokens.Color.ReaderInkAccent
        else Tokens.Color.ReaderInkSecondary,
        animationSpec = tween(140),
        label = "pillIconTint",
    )
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(bg)
            .hoverable(interaction)
            .primaryPress(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 阅读设置二级面板（2026-10-30 用户要求 6；第二批重做为白卡 + 居中选项）。
 *
 * 用户原话：「将条漫、单页、双页及从左向右、从右向左阅读设置整合到一个入口。
 * 点击后，从底部向上弹出小巧、精炼的二级设置面板，清晰展示当前选项并允许修改」；
 * 第二批追加：「调整条漫单双页的子菜单居中对齐」。
 *
 * **对齐方式**（这是本轮视觉重点）：每一行 = 左侧固定宽度标签 + 右侧**等宽均分**的
 * 选项组。三选一（阅读方式）每颗占 1/3、二选一（翻页方向）每颗占 1/2，于是三行
 * 的选项**左右两端对齐、组中心也都在同一条竖线上**——这就是"居中对齐"。
 * 旧实现是「左对齐 + 内容自适应宽度」，三行右边缘参差不齐，看起来像没对齐。
 *
 * 面板从底部往上弹（调用方用 spring slide），位置在按钮组正上方居中。
 */
@Composable
internal fun ReaderSettingsPanel(
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Tokens.Radius.Drawer)
    Column(
        modifier
            .width(ReaderPanelWidth)
            .shadow(
                elevation = 24.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x1F000000),
                spotColor = Color(0x33000000),
            )
            .clip(shape)
            .background(Tokens.Color.ReaderSurface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(DockIcons.Sliders, null, Modifier.size(Tokens.ReaderChrome.IconSize), tint = Tokens.Color.ReaderInkAccent)
            Spacer(Modifier.width(Tokens.Space.Sm))
            Text("阅读设置", style = Tokens.Type.Subtitle.toTextStyle(), color = Tokens.Color.ReaderInk)
        }
        ReaderSettingRow(label = "阅读方式") {
            ReaderMode.entries.forEach { mode ->
                ReaderChoice(
                    text = mode.label,
                    selected = settings.mode == mode,
                    modifier = Modifier.weight(1f),
                ) { onSettings(settings.copy(mode = mode)) }
            }
        }
        ReaderSettingRow(label = "翻页方向") {
            // 条漫是连续滚动，没有「翻页方向」——这一行在条漫下压暗但**不隐藏**，
            // 隐藏会让面板高度跳变（用户点开面板时内容整体上蹿一格）。
            val enabled = settings.mode != ReaderMode.VERTICAL
            ReaderDirection.entries.forEach { dir ->
                ReaderChoice(
                    text = dir.label,
                    selected = settings.direction == dir,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { onSettings(settings.copy(direction = dir)) }
            }
        }
        ReaderSettingRow(label = "缩放") {
            ReaderChoice(
                text = "−",
                selected = false,
                enabled = settings.zoom > ReaderSettings.ZOOM_MIN,
                modifier = Modifier.weight(1f),
            ) { onSettings(zoomBy(settings, -ReaderSettings.ZOOM_STEP)) }
            // 数值格与选项同宽同高，三格等分 → 中间的百分比天然落在行中心
            Box(
                Modifier.weight(1f).height(ReaderChipHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${(settings.zoom * 100).toInt()}%",
                    color = Tokens.Color.ReaderInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            ReaderChoice(
                text = "+",
                selected = false,
                enabled = settings.zoom < ReaderSettings.ZOOM_MAX,
                modifier = Modifier.weight(1f),
            ) { onSettings(zoomBy(settings, +ReaderSettings.ZOOM_STEP)) }
        }
    }
}

/**
 * 设置面板的一行：上方小标签 + 下方**无灰底轨道**的选项组（2026-11-08 批次65）。
 *
 * 旧版每组套一层 `BgSunken` 圆角灰框，框里再放白/灰胶囊，视觉上「框套框」；
 * 现在去掉轨道，选项直接落在白卡上，靠间距和选中色分层。
 * 选项组仍然 `weight(1f)` 等分，三行的左右边缘严格对齐。
 */
@Composable
private fun ReaderSettingRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
        Text(
            text = label,
            color = Tokens.Color.ReaderInkSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * 面板里的选项胶囊（2026-11-08 批次65 重做）。
 *
 * - 选中：主色 12% 浅底 + 主色半粗字，一眼能看出当前值，又不会像实心按钮那样抢眼；
 * - 未选中：完全透明、无描边，hover 时才浮出一层 6% 黑；
 * - 禁用：只压暗字色，不额外加框。
 *
 * 宽度由调用方 `weight(1f)` 等分，文字水平居中。
 */
@Composable
private fun ReaderChoice(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.Radius.Capsule)
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Tokens.Color.ReaderInkAccent.copy(alpha = 0.12f)
            hovered && enabled -> Tokens.Color.ReaderSurfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "choiceBg",
    )
    val ink by animateColorAsState(
        targetValue = when {
            selected -> Tokens.Color.ReaderInkAccent
            !enabled -> Tokens.Color.ReaderInkTertiary
            hovered -> Tokens.Color.ReaderInk
            else -> Tokens.Color.ReaderInkSecondary
        },
        animationSpec = tween(140),
        label = "choiceInk",
    )
    Box(
        modifier
            .height(ReaderChipHeight)
            .clip(shape)
            .background(bg)
            .hoverable(interaction)
            .then(if (enabled) Modifier.primaryPress(onClick) else Modifier)
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ink,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 右下角全屏 / 恢复窗口切换（2026-10-30 用户要求 5；第二批改白底）。
 *
 * 用户原话：「仅显示图标，不添加菜单栏外框，图标风格与现有菜单栏按钮一致」——
 * 所以这里**没有胶囊、没有描边文字**，只有一颗白色圆钮 + 裸图标。
 * 为什么不能像原来那样"裸图标无底"：底色是深色画布，深色图标会整个消失；
 * 白底圆钮既保证可见，又和顶栏/悬浮组同色，读起来是同一套 chrome。
 */
@Composable
internal fun ReaderFullscreenButton(
    fullscreen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.BgSunken else Tokens.Color.ReaderSurface,
        animationSpec = tween(140),
        label = "fsBg",
    )
    val tint by animateColorAsState(
        targetValue = if (hovered) Tokens.Color.ReaderInk else Tokens.Color.ReaderInkSecondary,
        animationSpec = tween(140),
        label = "fsTint",
    )
    Box(
        modifier
            .size(38.dp)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x26000000),
                spotColor = Color(0x44000000),
            )
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Tokens.Color.ReaderSurfaceBorder, CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (fullscreen) DockIcons.FullscreenExit else DockIcons.Fullscreen,
            contentDescription = if (fullscreen) "恢复窗口" else "全屏",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 缩放一步（0.4×~2.0×，两种模式通用）。
 *
 * zoom 的基准是「适应高度」：竖向模式下是列宽倍率；页模式下缩放的是**格子尺寸**
 * （宽高同比放大，超出部分裁剪 + 拖动平移），不再用 graphicsLayer 缩放图片——
 * 理由见 `PageSpread` 的注释（大图缩放会让 Skia 反复重建图层，是卡顿与闪退的来源）。
 */
internal fun zoomBy(s: ReaderSettings, delta: Float): ReaderSettings =
    s.copy(zoom = (s.zoom + delta).coerceIn(ReaderSettings.ZOOM_MIN, ReaderSettings.ZOOM_MAX))

/** 设置面板宽度：三行标签 + 三格选项，340dp 下每格约 82dp，正好放得下「左→右」。 */
internal val ReaderPanelWidth = 340.dp

/** 面板内选项胶囊高度。 */
private val ReaderChipHeight = Tokens.ReaderChrome.SettingsRowHeight

/** 面板左侧标签列宽。 */

/** 面板顶部到按钮组的间距（弹出时两者不贴在一起）。 */
internal val ReaderSettingsGap = 10.dp

/** 底部控件离窗口底的距离（给一点呼吸，不贴边）。 */
internal val ReaderBottomInset = 18.dp

/** 右下角全屏键的边距：比按钮组略靠外一点，视觉上才算「角落」。 */
internal val ReaderCornerInset = 14.dp

/** 面板/按钮组的动画时长（与 dock 的 200~220ms 同一档，不抢阅读）。 */
internal const val ReaderChromeAnimMs = 200

/**
 * chrome 收起的时长：比进场**短**（170 vs 200）。
 *
 * 交互惯例——出现要看得见过程，消失要快，否则用户会觉得"点了一下没反应"。
 */
internal const val ReaderChromeExitMs = 170

/**
 * chrome 进出的缓动曲线（ease-out-quint）：起手快、落点慢。
 *
 * 和主界面揭幕用的是同一颗曲线（`Main.kt` 的 `SplashEasing`），
 * 阅读器与壳子的手感因此是同一套；不用 `FastOutSlowIn`——那颗在 200ms 里前段太肉。
 */
internal val ReaderChromeEasing: androidx.compose.animation.core.Easing =
    androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
