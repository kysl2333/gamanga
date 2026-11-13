package app.gamanga.desktop.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.gamanga.desktop.data.ReadingStats
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlin.math.roundToInt

/**
 * 阅读统计的入口按钮 + 点击弹出的小框（F-38 的 UI 落地）。
 *
 * ## 为什么拆成「按钮」和「小框」两层
 *
 * 按钮要贴在一级页（书架）的搜索栏最左侧、和 dock 一样浮在内容之上——**放在哪儿由调用方决定**，
 * 所以定位的 [Modifier] 从外面传进来；小框的位置则必须跟着按钮走，于是 `Popup` 声明在按钮所在的
 * `Box` 里，以该 Box 的布局位置为锚点（Compose 的 Popup 锚点就是它所在的父布局范围）。
 *
 * ## 为什么图标自己画
 *
 * `DockIcons` 里没有「统计/柱状图」，而项目刻意不引 `material-icons-extended`（10MB 只为几个图标，
 * 见 `DockIcons.kt` 文件头）。三根递增的圆角竖条语义最直白、粗细可控，也不会混进第二种图标语言。
 *
 * ## 数据来源
 *
 * [statsProvider] 是 lambda 而不是快照：**展开的那一刻才查库**。
 * 收着的时候一次都不查（书架页每重组一次就打一次 SQLite 是白烧）；
 * 展开时重查又保证「在设置里清空了统计 → 回来点开就是 0」，不必等重启。
 */
@Composable
fun StatsButton(
    statsProvider: () -> ReadingStats,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(statsProvider()) }
    LaunchedEffect(expanded) {
        if (expanded) stats = statsProvider()
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // 展开时按钮保持「选中」态：否则小框挂在下面，用户看不出是哪个键弹出来的
    val active = hovered || expanded
    val tint by animateColorAsState(
        targetValue = if (active) Tokens.Color.Accent else Tokens.Color.TextTertiary,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "statsTint",
    )
    val bg by animateColorAsState(
        targetValue = if (active) Tokens.Color.AccentSoft else Color.Transparent,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "statsBg",
    )
    // 按下 90ms 下压、松手 220ms 回位——与 TextAction / 详情返回钮同一套手感
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(if (pressed) 90 else 220),
        label = "statsScale",
    )

    Box(modifier) {
        Box(
            Modifier
                // **微长方形**（44×30，宽 > 高）：用户 2026-10-27 第四轮
                // 「统计页图标按钮用微长方形的，风格像推荐页按钮的风格的还有大小」。
                // 圆形在搜索栏左边像一个「头像」，长方形才读得出「一个按钮」。
                .size(width = StatsButtonWidth, height = StatsButtonHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(Tokens.Radius.Control))
                .background(bg)
                .hoverable(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .semantics { contentDescription = "阅读统计" },
            contentAlignment = Alignment.Center,
        ) {
            StatsGlyph(
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            StatsPopover(stats = stats, onDismiss = { expanded = false })
        }
    }
}

/**
 * 按钮尺寸：**44 × 30 的微长方形**。
 *
 * 28dp 正圆在搜索栏左边太像一颗「头像」；44×30 与搜索键（42dp）同一体量，
 * 横向略宽、纵向略矮，读起来是「一个扁按钮」，和推荐页那种小按钮的语言一致。
 */
private val StatsButtonWidth = 44.dp
private val StatsButtonHeight = 30.dp

/** 小框宽度：320dp 刚好排下两列数字而不用压缩标签，也不会在 440 宽的简洁模式里顶满。 */
private val PopoverWidth = 320.dp

/**
 * 弹层阴影的两个颜色。
 *
 * 不用 `shadow()` 的默认单层黑影：那一档在大面积浅色底上会显出硬边，像贴了张纸。
 * 这里走「环境光极淡 + 直射光稍重」的双层，`clip = false` 让阴影完整落在卡片外。
 * 阴影不是语义色（不属于 `Tokens.Color` 的色板），所以就近定义，避免把一次性的值塞进令牌。
 */
private val PopoverShadowAmbient = Color(0x0A000000)
private val PopoverShadowSpot = Color(0x14000000)

/** 统计图标：三根高度递增的圆角竖条（0.44 / 0.7 / 1.0）。 */
@Composable
private fun StatsGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val gap = size.width * 0.16f
        val barWidth = (size.width - gap * 2f) / 3f
        // 从左到右递增：读起来是「趋势」，三根等高的柱子只会像菜单图标
        floatArrayOf(0.44f, 0.7f, 1f).forEachIndexed { index, ratio ->
            val barHeight = size.height * ratio
            drawRoundRect(
                color = tint,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/**
 * 统计小框本体。
 *
 * 排版按「统计库」的读法组织：**一个主数字（今日）+ 一条占比线 + 四个计数**。
 * 六个数字全部出现且不重复——「累计」放在占比行右侧当参照，「今日」独占主视觉，
 * 比把六个数字平铺成 2×3 更能一眼看出「今天读了多少、占总量的多少」。
 */
@Composable
private fun StatsPopover(stats: ReadingStats, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val anchorGap = with(density) { 8.dp.roundToPx() }
    // 贴在按钮正下方，靠边时把整块推回窗口内（否则小框有一半在窗口外点不到）
    val positionProvider = remember(anchorGap, layoutDirection) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentBounds: IntSize,
            ): IntOffset = IntOffset(
                x = anchorBounds.left.coerceIn(
                    0,
                    (windowSize.width - popupContentBounds.width).coerceAtLeast(0),
                ),
                y = (anchorBounds.bottom + anchorGap).coerceAtMost(
                    (windowSize.height - popupContentBounds.height).coerceAtLeast(0),
                ),
            )
        }
    }

    // 淡入 + 轻微上浮/放大：以顶边中点为原点，视觉上是「从按钮下面长出来」
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 180, easing = Tokens.Motion.DrawerEasing),
        )
    }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        popupPositionProvider = positionProvider,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * -6.dp.toPx()
                    scaleX = 0.97f + 0.03f * appear.value
                    scaleY = 0.97f + 0.03f * appear.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(
                    elevation = Tokens.Elevation.Overlay,
                    shape = RoundedCornerShape(Tokens.Radius.Drawer),
                    clip = false,
                    ambientColor = PopoverShadowAmbient,
                    spotColor = PopoverShadowSpot,
                )
                .clip(RoundedCornerShape(Tokens.Radius.Drawer))
                .background(Tokens.Color.BgElevatedSolid)
                .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Drawer))
                .width(PopoverWidth)
                .padding(horizontal = Tokens.Space.Xl, vertical = Tokens.Space.Lg),
        ) {
            // 顶部：小标签 + 一条极细分隔线（统计库的「表头」，不靠大标题压场）
            Row(verticalAlignment = Alignment.CenterVertically) {
                MixText(
                    text = "阅读统计",
                    color = Tokens.Color.TextTertiary,
                    fontSize = Tokens.Type.Micro.size,
                    // 小号全大写标签按规格加 0.05em 字距；中文同样吃这个值，读起来更「标牌」
                    style = Tokens.Type.Micro.copy(
                        weight = FontWeight.Medium,
                        tracking = 0.55.sp,
                    ).toTextStyle(),
                )
                Spacer(Modifier.width(Tokens.Space.Sm))
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Tokens.Color.BorderHairline),
                )
            }

            Spacer(Modifier.height(Tokens.Space.Lg))

            // 主视觉：今日观看时长（秒 → 「N 小时 M 分」）
            MixText(
                text = formatDuration(stats.todaySeconds),
                color = Tokens.Color.TextPrimary,
                fontSize = Tokens.Type.Title.size,
                style = Tokens.Type.Title.toTextStyle().copy(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.height(Tokens.Space.Xs))
            MixText(
                text = "今日观看",
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )

            Spacer(Modifier.height(Tokens.Space.Md))
            ReadingShareBar(todaySeconds = stats.todaySeconds, totalSeconds = stats.totalSeconds)
            Spacer(Modifier.height(Tokens.Space.Sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MixText(
                    text = "占累计 ${sharePercent(stats.todaySeconds, stats.totalSeconds)}%",
                    color = Tokens.Color.TextTertiary,
                    fontSize = Tokens.Type.Micro.size,
                    style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                )
                Spacer(Modifier.weight(1f))
                // 「累计」在这里当参照物，不放网格：它和「今日」是同一根时间轴上的两个刻度
                MixText(
                    text = "累计 ${formatDuration(stats.totalSeconds)}",
                    color = Tokens.Color.TextSecondary,
                    fontSize = Tokens.Type.Micro.size,
                    style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                )
            }

            Spacer(Modifier.height(Tokens.Space.Lg))

            // 四个计数：两列两行。数字在上、标签在下，列间留白代替竖线（规格：少方框）
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Lg)) {
                Row {
                    StatCell(
                        value = stats.libraryCount.toString(),
                        unit = "部",
                        label = "书架藏书",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        value = stats.readChapterCount.toString(),
                        unit = "话",
                        label = "已读章节",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    StatCell(
                        value = stats.downloadedChapterCount.toString(),
                        unit = "话",
                        label = "下载章节",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        value = stats.downloadedMangaCount.toString(),
                        unit = "部",
                        label = "下载漫画",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 一个计数格：大数字 + 小单位（基线对齐）+ 极小标签。 */
@Composable
private fun StatCell(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row {
            MixText(
                text = value,
                modifier = Modifier.alignByBaseline(),
                color = Tokens.Color.TextPrimary,
                fontSize = Tokens.Type.Subtitle.size,
                style = Tokens.Type.Subtitle.toTextStyle().copy(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.width(3.dp))
            MixText(
                text = unit,
                modifier = Modifier.alignByBaseline(),
                color = Tokens.Color.TextTertiary,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
        }
        Spacer(Modifier.height(2.dp))
        MixText(
            text = label,
            color = Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Micro.size,
            style = Tokens.Type.Micro.toTextStyle(),
        )
    }
}

/**
 * 今日占累计的比例条（3dp 胶囊）。
 *
 * 只做视觉锚点：真正要读的数字在两侧文字里，条子本身不承载精度，
 * 所以 200ms `linear`（规格：进度条变化 200ms linear），不做回弹。
 */
@Composable
private fun ReadingShareBar(todaySeconds: Long, totalSeconds: Long) {
    val ratio by animateFloatAsState(
        targetValue = shareRatio(todaySeconds, totalSeconds),
        animationSpec = tween(durationMillis = Tokens.Motion.ProgressMs, easing = LinearEasing),
        label = "statsShare",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(Tokens.Color.BgSunken),
    ) {
        if (ratio > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.Accent),
            )
        }
    }
}

/** 今日 / 累计（0~1）。累计为 0 时返回 0，避免除零。 */
private fun shareRatio(todaySeconds: Long, totalSeconds: Long): Float =
    if (totalSeconds <= 0L) 0f
    else (todaySeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)

/** 占比百分数（整数）。 */
private fun sharePercent(todaySeconds: Long, totalSeconds: Long): Int =
    (shareRatio(todaySeconds, totalSeconds) * 100f).roundToInt()

/**
 * 秒 → 人能读的时长。
 *
 * 「2 小时 0 分」这种写法不像人话，所以整点只写「2 小时」；不足 1 分钟直接「0 分钟」——
 * 统计数字宁可小也不能凭空进位（文案纪律：不写「可能/大约」）。
 */
private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0L && minutes > 0L -> "$hours 小时 $minutes 分"
        hours > 0L -> "$hours 小时"
        else -> "$minutes 分钟"
    }
}
