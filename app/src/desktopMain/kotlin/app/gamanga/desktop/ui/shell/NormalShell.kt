package app.gamanga.desktop.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 正常模式外壳（阶段 4，2026-10-26）。
 *
 * 取代 [AppNavRail] 的左侧竖栏：底部浮空纯图标菜单 + 右上角窗口关闭键 + 全宽页面区。
 * 视觉依据是 `docs/ui/prototype/bookshelf-3x2.html`，交互细节按用户 2026-10-26 实测反馈调整过。
 *
 * **硬约束**（改这个文件前先读）：
 * 1. **关窗键必须在壳层**，不放进页面——窗口无系统边框，这是唯一出口；放页面里会切页重建、新增页面漏掉。
 * 2. **关窗键常驻**，包括多选态（用户口径：关闭是全局功能）。
 * 3. **dock 宽度必须固定**。选中项名称**不能**撑宽 dock：一撑宽，居中的 dock 会整体横移半个名称宽度，
 *    用户看到的「菜单栏展开时外边框回弹一下」就是这么来的。名称改成 dock 上方的浮标。
 * 4. dock 是浮层，不占布局；页面区靠 [DockReservedHeight] 主动让位。
 */
@Composable
internal fun NormalShell(
    selected: NavItem,
    onSelect: (NavItem) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 壳层自身的尺寸：dock 的实测几何要拿它换算「距窗口底多少 dp」
    var shellSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { shellSize = it.size }
            .background(Tokens.Color.BgBase)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                //
                // **上下都不让位**（用户 2026-10-27 两轮反馈：dock 下面一条白带、顶部一条白带）。
                //
                // 让位的本质是「页面区缩小 → 空出来的地方露出页面底色 = 白带」。
                // 所以壳层不再给任何浮层腾布局空间：
                // - 底部：dock 是纯浮层，封面从它下面滑过；「最后一行别被永久挡住」
                //   由网格自己的 `contentPadding.bottom` 承担（那是滚动内容，不是固定白带）。
                // - 顶部：拖拽带也是纯浮层，页面铺到窗口最顶；各页面自己的顶栏负责
                //   `padding(top = DragStripHeight)`，把**可点的控件**放到拖拽带下面。
                //   这样 0~36dp 露出来的是**内容**（封面/列表），不是白带。
                .fillMaxSize()
        ) {
            content()
        }
        // 拖拽把手：只占顶部这条带子（页面内容从它下面开始排），
        // 不和任何按钮抢位置，也不依赖事件冒泡顺序 —— 见 WindowDrag.kt 的三轮方案说明。
        WindowDragHandle(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        WindowCloseButton(
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 22.dp)
        )
        BottomDock(
            selected = selected,
            onSelect = onSelect,
            // 实测**白色胶囊本体**的矩形 → 写进 [DockMetrics]（R-174，2026-10-30）。
            // 原来量的是 BottomDock 最外层那个 Row（= 推荐按钮 + 间距 + 胶囊），
            // 它的中心恰好就是窗口中心 → centerOffsetX 永远是 0，
            // 于是多选条永远偏左 32dp（用户截图里那两个圆钮就歪在菜单栏左边）。
            onBounds = { b ->
                if (shellSize.height != 0 && b.height > 0f) {
                    with(density) {
                        DockMetrics.update(
                            topInsetDp = (shellSize.height - b.top).toDp(),
                            centerOffsetDp = (b.center.x - shellSize.width / 2f).toDp(),
                        )
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }
}

/**
 * 顶部拖拽条高度。
 *
 * 无边框窗口没有标题栏可拖，`Main.kt` 会在这里铺一条拖拽手势区。壳里留同样高度的空白，
 * 避免拖拽条压住页面内容。
 *
 * 2026-10-26 用户实测：「拖动界面窗口的部分太窄，扩大能拖拽的范围」→ 18dp 提到 **32dp**。
 * 代价是页面顶栏要一起加高（[app.gamanga.desktop.ui.library.BookshelfScreen] 的 TopBar 84dp），
 * 否则搜索框会被拖拽条压住一半。
 */
internal val DragStripHeight = 36.dp

/** 底部为浮空 dock 让出的高度：dock 自身 60 + 底距 22 + 呼吸 6。 */
internal val DockReservedHeight = 88.dp

/**
 * dock 的**实测几何**（2026-10-29 R-160）。
 *
 * ## 为什么改成实测
 *
 * 用户第三次报「多选的取消 / 删除两个圆钮跑到菜单栏后面被遮住、也没和菜单栏中心对齐」。
 * 前两次修的都是**手算的常量**（`DockReservedHeight + Space.Md` / `DockCenterOffsetX = 32dp`），
 * 而常量只要和 dock 的真实尺寸有一点偏差（dock 高度、底距、左侧推荐按钮的尺寸/间距），
 * 浮层就会偏——这已经是本项目「位置只该有一处定义」那条教训的第二次复发了。
 *
 * 现在由 [BottomDock] 在布局完成后把**真实矩形**写进来（[topInset] / [centerOffsetX]），
 * 书架页的多选工具条、封面提示条、回撤条全部读这里：
 * - 纵向：`bottom = topInset + gap` → 永远在 dock 上方；
 * - 横向：`offset(x = centerOffsetX)` → 永远和 dock 同一条中心线。
 *
 * dock 尺寸再改（加按钮、改内边距）也不用动调用点。
 */
internal object DockMetrics {

    /** dock 顶边距窗口底的距离（dp）。未测量前退回 [DockReservedHeight]。 */
    var topInset by mutableStateOf(DockReservedHeight)
        private set

    /** dock 视觉中心相对窗口中心的横向偏移（dp，右为正）。 */
    var centerOffsetX by mutableStateOf(0.dp)
        private set

    /** 浮层（多选条 / 提示条 / 回撤条）该留的底部内边距：dock 顶边再往上 [Tokens.Space.Xl]。 */
    val floatingBottom: Dp get() = topInset + Tokens.Space.Xl

    internal fun update(topInsetDp: Dp, centerOffsetDp: Dp) {
        topInset = topInsetDp
        centerOffsetX = centerOffsetDp
    }
}

/**
 * dock 的**视觉中心**相对窗口中心的横向偏移（2026-10-27 第六轮）。
 *
 * 底部那排是 `Row { 推荐按钮(52) · 间距(12) · dock(260) }`，整排居中的结果就是
 * **dock 本身右移了 (52+12)/2 = 32dp**。推荐按钮在 dock 框外左侧，用户眼里「菜单栏」是那个
 * 白色胶囊，所以任何「和菜单栏对齐」的浮层都得跟着右移 32dp。
 *
 * ⚠️ **2026-10-29 起浮层不要再用这个常量**：改用 [DockMetrics.centerOffsetX]（实测值）。
 * 这个常量只留给「dock 还没测量出来」的第一帧兜底。
 */
internal val DockCenterOffsetX = 32.dp

// ---- dock 尺寸常量（气泡定位要靠它们算，改一处要一起改）----
private val DockButtonSize = 44.dp
private val DockGap = 6.dp
private val DockPadding = 8.dp
private val DockWidth = DockPadding * 2 + DockButtonSize * 5 + DockGap * 4 // = 260dp
private val DockStep = DockButtonSize + DockGap                             // = 50dp

/** 窗口关闭键：圆形 / 触碰时 X 顺时针转一整圈、移开反向转回 / 按下缩到 .9。 */
@Composable
private fun WindowCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    // 0 → 360 是顺时针一圈；360 → 0 是逆时针一圈，正好是用户要的「移开反着转一圈」
    val rotation by animateFloatAsState(
        targetValue = if (hovered) 360f else 0f,
        animationSpec = tween(420, easing = Tokens.Motion.DrawerEasing),
        label = "closeSpin",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "closeScale",
    )
    // 三态：按住 = 亮红实心 + 白 X（用户 2026-10-26 要求）；悬停 = 淡红底红 X；常态 = 淡灰
    val bg by animateColorAsState(
        targetValue = when {
            pressed -> Tokens.Color.Danger
            hovered -> Tokens.Color.DangerSoft
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "closeBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            pressed -> Color.White
            hovered -> Tokens.Color.Danger
            else -> Tokens.Color.TextTertiary
        },
        animationSpec = tween(140),
        label = "closeTint",
    )

    Box(
        modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DockIcons.Close,
            contentDescription = "关闭程序",
            tint = tint,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * 底部浮空菜单：**宽度固定** 260dp，5 个纯图标按钮位置恒定。
 *
 * 选中项名称显示在 dock **上方的浮标**里（不是撑宽 dock）——见文件头第 3 条约束。
 *
 * 名称的显示规则（F-70，用户 2026-10-26 第二次口径）：
 * - **只有点击后才出现**，不再跟着选中态常驻；
 * - 出现后 **[BUBBLE_VISIBLE_MS] = 1 秒自动消失**；
 * - **没有外框/底色/阴影**，文字直接浮在页面上；
 * - 字号 26sp，字体是内置的得意黑（[SignatureFont]，可商用 OFL）。
 */
@Composable
private fun BottomDock(
    selected: NavItem,
    onSelect: (NavItem) -> Unit,
    /** 胶囊本体的窗口矩形（用来量浮层让位，见 [DockMetrics]）。 */
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 下载中状态：dock 的「下载」图标要变成动的（用户 2026-10-26 要求）。
    // 只要有 QUEUED / DOWNLOADING 的任务就算「下载中」——排队也是在进行，
    // 用户点完下载立刻能看到反馈，不用等第一页真的开始。
    val downloadTasks by app.gamanga.desktop.data.AppGraph.downloadManager.tasks.collectAsState()
    val downloading = remember(downloadTasks) {
        downloadTasks.values.any {
            it.status == app.gamanga.desktop.data.DownloadStatus.QUEUED ||
                it.status == app.gamanga.desktop.data.DownloadStatus.DOWNLOADING
        }
    }

    // 「正在搜索 / 刚搜完」：dock 的**浏览**按钮要跟着动（用户 2026-10-27 要求）
    val searchPhase by app.gamanga.desktop.ui.search.SearchStatus.phase.collectAsState()
    var searchDone by remember { mutableStateOf(false) }
    LaunchedEffect(searchPhase) {
        if (searchPhase == app.gamanga.desktop.ui.search.SearchStatus.Phase.Done) {
            searchDone = true
            // 用户 2026-10-27：「打勾时图标背景颜色从 UI 蓝渐变成绿色然后再过 2 秒渐变回来」
            // → 绿色**保持 2 秒**再退（上一版 1.6 秒，还没看清就没了）
            delay(2_000)
            searchDone = false
            app.gamanga.desktop.ui.search.SearchStatus.idle()
        }
    }

    // 「刚下载完」状态：全部任务结束的那一刻，下载图标变成绿色打勾并停 5 秒
    // （用户 2026-10-26：「全部任务下载完成后会变成好看的纯绿色图标变成打勾的图标停 5 秒
    // 然后正常恢复到最初按钮状态」）。
    var showDone by remember { mutableStateOf(false) }
    var everDownloaded by remember { mutableStateOf(false) }
    LaunchedEffect(downloading) {
        if (downloading) {
            everDownloaded = true
            showDone = false
        } else if (everDownloaded) {
            showDone = true
            delay(5_000)
            showDone = false
            everDownloaded = false
        }
    }

    // 浮标要显示哪个名字 / 是否可见 / 第几次触发（自增用来重置 1 秒计时）
    var bubbleItem by remember { mutableStateOf(NavItem.LIBRARY) }
    var bubbleVisible by remember { mutableStateOf(false) }
    var bubbleSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(bubbleSeq) {
        if (bubbleSeq > 0) {
            delay(BUBBLE_VISIBLE_MS)
            bubbleVisible = false
        }
    }

    // 浮标左边缘 = 按钮中心 − 半个浮标宽；按钮中心 = padding + i*step + 半个按钮
    val bubbleWidth = 80.dp
    val bubbleIndex = DockItems.indexOf(bubbleItem)
    val bubbleX = DockPadding + DockStep * bubbleIndex + DockButtonSize / 2 - bubbleWidth / 2
    val x by animateDpAsState(bubbleX, tween(200, easing = Tokens.Motion.DrawerEasing), label = "bubbleX")
    // 出现/消失：淡入 + 从 92% 放大 + 从下方 6dp 浮上来（消失时反向）
    val appear by animateFloatAsState(
        targetValue = if (bubbleVisible) 1f else 0f,
        animationSpec = tween(220, easing = Tokens.Motion.DrawerEasing),
        label = "bubbleAppear",
    )
    val liftPx = with(LocalDensity.current) { 6.dp.toPx() }

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 推荐按钮：**在 dock 框外**、最前面（用户 2026-10-26 明确要求「不在菜单栏框内」）
        RecommendButton(
            active = selected == NavItem.RECOMMEND,
            onClick = { onSelect(NavItem.RECOMMEND) },
        )
        Box {
        // 浮标：只有文字，没有外框/底色/阴影（用户明确要求去掉边框）
        Box(
            Modifier
                .offset(x = x, y = (-(30 + 8)).dp)
                .width(bubbleWidth)
                .height(30.dp)
                .graphicsLayer {
                    alpha = appear
                    val s = 0.92f + 0.08f * appear
                    scaleX = s
                    scaleY = s
                    translationY = (1f - appear) * liftPx
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = bubbleItem.label,
                color = Tokens.Color.TextPrimary,
                fontSize = 26.sp,
                fontFamily = SignatureFont,
                maxLines = 1,
            )
        }

        // dock 本体：宽度写死，不随选中态变化
        Row(
            Modifier
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .width(DockWidth)
                .height(DockButtonSize + DockPadding * 2)
                .shadow(elevation = Tokens.Elevation.Overlay, shape = RoundedCornerShape(Tokens.Radius.Capsule), clip = false)
                .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                .background(Tokens.Color.BgElevatedSolid)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Capsule))
                .padding(horizontal = DockPadding, vertical = DockPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DockGap),
        ) {
            DockItems.forEach { item ->
                DockButton(
                    item = item,
                    active = item == selected,
                    downloading = downloading && item == NavItem.DOWNLOADS,
                    searching = searchPhase == app.gamanga.desktop.ui.search.SearchStatus.Phase.Searching &&
                        item == NavItem.BROWSE,
                    // 下载完成 / 搜索完成都走同一套「绿底打勾」
                    done = (showDone && item == NavItem.DOWNLOADS) ||
                        (searchDone && item == NavItem.BROWSE),
                    onClick = {
                        onSelect(item)
                        // 名字只在「点了」之后出现，1 秒后自动消失
                        bubbleItem = item
                        bubbleVisible = true
                        bubbleSeq++
                    },
                )
            }
        }
        }
    }
}

/** dock 框内的 5 个导航项（推荐在框外，不在此列）。 */
private val DockItems = NavItem.entries.filter { it != NavItem.RECOMMEND }

/**
 * 推荐按钮（2026-10-26 用户口述规格）。
 *
 * | 状态 | 表现 |
 * |---|---|
 * | 常态 | 白底圆 + 细边，**没有波浪** |
 * | 悬停 | **只加阴影**（用户原话：「不做触碰波浪动效，而是触碰后增加点阴影」） |
 * | 点击 | 水位从**最底部**涨满（波浪形式） |
 * | 在推荐页 | 水位**常驻 1.0**，颜色固定在柔和色板里的一个色（= 搜索键按住时的效果） |
 */
@Composable
private fun RecommendButton(active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    // 视觉**和 dock 里的按钮完全一致**（用户 2026-10-27：「这个推荐按钮还是像其他菜单按钮
    // 一样的图标风格吧」）。之前那套玻璃/渐变/内高光全部删掉——它既有白圈又和邻居不搭。
    // 区别只剩三处：①它在 dock 框**外面**；②常态不涨水；③hover 只加阴影（用户最初的口径）。
    val level by animateFloatAsState(
        targetValue = if (active || pressed) 1f else 0f,
        animationSpec = tween(525, easing = Tokens.Motion.DrawerEasing),
        label = "recommendLevel",
    )
    val amplitude by animateFloatAsState(
        targetValue = if (active) 0.04f else 0f,
        animationSpec = tween(420, easing = Tokens.Motion.DrawerEasing),
        label = "recommendAmplitude",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            pressed -> 4.dp
            hovered -> 14.dp
            else -> 6.dp
        },
        animationSpec = tween(180),
        label = "recommendShadow",
    )
    val tint by animateColorAsState(
        targetValue = if (active) Color.White else Tokens.Color.TextSecondary,
        animationSpec = tween(180),
        label = "recommendTint",
    )
    val phaseState = rememberWavePhaseState(active = active)
    val fillState = rememberCyclingColorState(active = active, stepMs = 1600L)

    Box(
        Modifier
            .size(52.dp)
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x33000000),
                spotColor = Color(0x4D000000),
            )
            .clip(CircleShape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, Tokens.Color.BorderHairline, CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WaveFill(
            level = level,
            phase = 0f,
            phaseProvider = { phaseState.value },
            amplitude = amplitude,
            colorProvider = { fillState.value },
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = DockIcons.Sparkle,
            contentDescription = "推荐",
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}


/**
 * 单个 dock 按钮：水位波浪三态（用户 2026-10-26 要求）。
 *
 * - **常态**：无波浪，灰图标；
 * - **悬停**：蓝色波浪在图标中间流动（水位 42%，图标在水面之上）；
 * - **点击/选中**：水位拉满 → 纯蓝圆 + 白色图标。
 */
@Composable
private fun DockButton(
    item: NavItem,
    active: Boolean,
    downloading: Boolean,
    /** 正在搜索（只给「浏览」按钮用）：图标脉冲 + 循环色波浪，和下载的上下浮动区分开。 */
    searching: Boolean,
    done: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val flooded = active || pressed
    val level by animateFloatAsState(
        targetValue = when {
            flooded -> 1f
            hovered -> 0.42f
            else -> 0f
        },
        // 2026-10-26 用户：dock 波浪上涨降速 25%（320 → 400）
        animationSpec = tween(400, easing = Tokens.Motion.DrawerEasing),
        label = "dockLevel",
    )
    // ---- 下载中动效（用户 2026-10-26）----
    // 「图标变成能动的下载按钮动效」+「图标背景为鼠标碰到按钮那样的中间开始波浪」+
    // 「颜色是搜索按钮点击按住时的那几个柔和颜色」。
    // 水位固定 0.55（比 hover 的 0.42 高一点，能看出「在忙」），颜色从柔和色板里随机挑一个。
    // 颜色要**流动**，不是随机定一色（用户 2026-10-26：「波浪没有变色，而是一个随机颜色的单色」）
    // 用 State + colorProvider：换色只重绘不重组（否则每 1.4 秒整颗按钮重组 = 卡一下）
    // 下载 / 搜索共用这套「忙碌」表现（用户 2026-10-27：「像下载按钮一样」）
    val busy = downloading || searching
    val downloadColorState = rememberCyclingColorState(active = busy && !flooded)
    // 完成态：进场 320ms（干脆），**退场 700ms + 缓出**（用户 2026-10-27：
    // 「完成时的动效转换太过生硬」——上一版进退都是 400ms 线性，绿底「啪」地消失）
    val doneLevel by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (done) 320 else 700,
            easing = if (done) Tokens.Motion.DrawerEasing else androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "dockDoneLevel",
    )
    // 水位：
    // - 下载中 → 固定 0.55（「有活儿在跑」的稳定水位）
    // - **搜索中 → 呼吸 0.30 ↔ 0.72**（用户 2026-10-27：「搜索中的特效还是像鼠标碰到按钮时的
    //   中间波浪带颜色变化」——hover 是固定的 0.42，所以搜索必须用「会呼吸的水位」区分开）
    val busyLevel by animateFloatAsState(
        targetValue = if (busy && !flooded) 1f else 0f,
        animationSpec = tween(520, easing = Tokens.Motion.DrawerEasing),
        label = "dockBusyLevel",
    )
    val breath = rememberInfiniteTransition(label = "dockSearchBreath")
    val breathPhase by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "dockBreathPhase",
    )
    // 0~1 的正弦（-1..1 → 0..1），搜索时用它把水位在 0.30~0.72 之间推
    // sin 只有 Double 重载 → 这里显式转 Float，避免整条表达式变成 Double
    val breathWave = ((kotlin.math.sin(breathPhase * 2.0 * Math.PI) + 1.0) / 2.0).toFloat()
    val downloadLevel: Float = busyLevel * if (searching) {
        0.30f + 0.42f * breathWave
    } else {
        0.55f
    }
    // 图标上下浮动：一个周期 1.1 秒，振幅 2.5dp——幅度再大就像在跳，不像在「下载」
    val bounce = rememberInfiniteTransition(label = "dockDownloadBounce")
    val bouncePhase by bounce.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "dockDownloadPhase",
    )
    val iconDy = if (downloading && !done) {
        (kotlin.math.sin(bouncePhase * 2 * Math.PI) * 2.5).toFloat()
    } else {
        0f
    }
    // 搜索中的图标：脉冲 0.94~1.06 + **左右轻摆 ±12°**（放大镜在找东西的感觉）
    val iconScale = if (searching && !done) {
        (1f + breathWave * 0.12f - 0.06f)
    } else {
        1f
    }
    val iconRotate: Float = if (searching && !done) {
        (kotlin.math.sin(breathPhase * 2.0 * Math.PI) * 12.0).toFloat()
    } else {
        0f
    }

    // 波浪只在「没被水淹没」时才流动——满水时再晃就是水面抖，很脏
    val phaseState = rememberWavePhaseState(active = (hovered && !flooded) || busy)
    // amplitude **必须动画过渡**：上一版直接按 hover 赋 0/0.05，鼠标一碰水面起伏瞬间从 0
    // 跳到 5% —— 那就是用户说的「还是有跳动，没有连贯」。相位连续不代表振幅连续。
    val amplitude by animateFloatAsState(
        targetValue = when {
            hovered && !flooded -> 0.05f
            busy && !flooded -> 0.035f
            else -> 0f
        },
        animationSpec = tween(420, easing = Tokens.Motion.DrawerEasing),
        label = "dockWaveAmplitude",
    )
    val tint by animateColorAsState(
        // done 时整颗按钮被绿底淹满，图标必须转白（否则灰图标压在绿底上看不清）
        targetValue = if (flooded || done) Color.White else Tokens.Color.TextSecondary,
        animationSpec = tween(180),
        label = "dockTint",
    )

    // ---- 完成态：蓝 ⇄ 绿 的颜色渐变 + 图标弹簧互换（用户 2026-10-27）----
    //
    // 用户原话：「搜索后菜单键浏览页按钮的图标从一个放大镜到一个打勾应该有一个自然来回
    // 切换的动效，且打勾时图标背景颜色从 UI 蓝渐变成绿色然后在过 2 秒渐变回来」。
    //
    // 旧实现的两个毛病：
    // 1. 图标是 `if (done) Check else icon` —— **硬切**，一帧内换完，所以「死硬」；
    // 2. 绿色是**另一层 WaveFill 铺上去**的，颜色从蓝到绿也是硬切（绿层水位涨起来而已），
    //    而且它声明在蓝色层**下面**，蓝层满水时其实盖住了绿层。
    //
    // 现在：底色只有一层，颜色由 animateColorAsState 在蓝/绿之间过渡；
    // 图标是「放大镜缩小淡出 + 打勾放大淡入」，两者**反向旋转 80°**，用 spring 收尾带一点回弹。
    val doneColor = animateColorAsState(
        targetValue = if (done) Tokens.Color.Success else Tokens.Color.Accent,
        animationSpec = tween(
            durationMillis = if (done) 420 else 700,
            easing = if (done) Tokens.Motion.DrawerEasing
            else androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "dockDoneColor",
    )
    val swap by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "dockIconSwap",
    )

    Box(
        Modifier
            .size(DockButtonSize)
            .clip(CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 下载中的水在下面（流动的柔和色），常态/完成的水在上面——
        // 两层叠着，选中下载页时仍然看得出「正在下载」
        if (downloadLevel > 0.001f) {
            WaveFill(
                level = downloadLevel,
                phase = 0f,
                phaseProvider = { phaseState.value },
                amplitude = amplitude,
                colorProvider = { downloadColorState.value },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 完成态整颗铺满（level 抬到 1），颜色由 doneColor 渐变；其余情况就是原来的水位
        val fillLevel = maxOf(level, doneLevel)
        if (fillLevel > 0.001f) {
            WaveFill(
                level = fillLevel,
                phase = 0f,
                phaseProvider = { phaseState.value },
                amplitude = amplitude,
                // draw 阶段取色：颜色动画每帧都在变，在 compose 层读会每帧重组整颗按钮
                colorProvider = { doneColor.value },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 图标：放大镜 ⇄ 打勾（两个图标叠着做交叉换，不是 if/else 硬切）
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (swap < 0.999f) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = 1f - swap
                            // 搜索中还有脉冲/摆动，完成时这两项自然收敛到 0
                            val s = (1f - 0.42f * swap) * iconScale
                            scaleX = s
                            scaleY = s
                            rotationZ = iconRotate - 80f * swap
                            translationY = iconDy
                        },
                )
            }
            if (swap > 0.001f) {
                Icon(
                    imageVector = DockIcons.Check,
                    contentDescription = item.label,
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            alpha = swap
                            val s = 0.55f + 0.45f * swap
                            scaleX = s
                            scaleY = s
                            // 从另一侧转进来，和放大镜的退出方向对称
                            rotationZ = 80f * (1f - swap)
                        },
                )
            }
        }
    }
}
