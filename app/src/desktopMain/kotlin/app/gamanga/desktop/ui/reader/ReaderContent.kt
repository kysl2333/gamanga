package app.gamanga.desktop.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.model.inCenterTapBand
import app.gamanga.desktop.model.panLimit
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.requestHeaders
import app.gamanga.desktop.ui.RemoteImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 阅读器「内容」层（阶段 3.2 从 ReaderScreen.kt 拆出）。
 *
 * 只放单页/双页/条漫三种渲染里**与状态无关**的部分：拿到页码就画，
 * 翻页通过回调回传。条漫的 LazyColumn 仍在 `ReaderScreen`（它和滚动位置、
 * 比例学习、边界蓄力强耦合）。
 */

/** 页模式图片行的上下左右留白（dp）。 */
private const val PAGE_GAP_DP = 8f

/**
 * 未放大态判定「横向滑动翻页」的最小位移（px）。
 * 小于它视为按压时的抖动/误触，不当翻页；只有明确滑过去才翻。
 */
private const val SWIPE_TURN_PX = 48f

/**
 * 单页/双页的共同渲染。
 *
 * 轻点：左半边 = 上一页、右半边 = 下一页（鼠标党不必摸键盘）；
 * **中心带（宽 [inCenterTapBand] 定义的区域）= 切换沉浸模式**（R-43）。
 * 右→左模式下把页序反转，使"阅读顺序"从右往左——这是日漫双页的实体书观感。
 * 拖拽（用户 2026-10-23 要求「拖着看」）：放大（zoom>100%）后左键拖动平移看图。
 *
 * ## 2026-10-30 缩放重做（用户：放大缩小卡顿 + 放大缩小会闪退）
 *
 * **旧实现**：格子尺寸固定成「适应高度」，缩放靠 `Modifier.graphicsLayer(scaleX=zoom)`。
 * 问题是 `graphicsLayer` 的**值参数**一改就让整行（含 1~2 张 3000×4000 的原图）
 * 重新组合；而带缩放的 layer 在 Skia 上会被当成一个独立图层处理，放大 2× 等于
 * 让 Skia 按 2 倍尺寸去准备这块内容——一页扫描图放大后是 6000×8000 量级的绘制，
 * 每滚一格缩放就重做一次。实测后果就是「缩放要顿一下」，持续缩放把显存/内存
 * 顶到边缘（机器上同时留下了 NVIDIA「GPU 已断开」与 `awt.dll` 访问违例两条记录）。
 *
 * **现在**：缩放改回**布局层**——格子高度直接乘 zoom，宽高同比放大，超出视口的部分
 * 由上层 `clipToBounds` 裁掉，平移用 `Modifier.offset { }`（布局阶段读取，拖动时
 * **不触发重组**）。整条链路没有 graphicsLayer、没有独立图层、没有大尺寸离屏缓冲：
 * 放大只是"把图按更大的目标尺寸画一次"，与 GPU 纹理无关。
 *
 * 缩放本身套一层 spring 动画（约 200ms），滚一格是"化过去"而不是"跳一下"——
 * 这是用户要的"舒适动效"，而它的成本只是动画期间每帧重测 1~2 个格子。
 */
@Composable
internal fun PageSpread(
    pages: List<PageImage>,
    startIndex: Int,
    count: Int,
    rtl: Boolean,
    zoom: Float,
    onTapNext: () -> Unit,
    onTapPrev: () -> Unit,
    onTapCenter: () -> Unit,
    loadDelayMillis: Long = 0,
) {
    val slice = (0 until count).mapNotNull { pages.getOrNull(startIndex + it) }
    if (slice.isEmpty()) return
    val ordered = if (rtl) slice.reversed() else slice

    // ---- 拖拽平移状态（放大后「拖着看」）----
    // pan 是**屏幕 px**；余量由下面的内容尺寸与视口尺寸直接算出来（不再测量 Row，
    // 因为 Row 的尺寸本身随 zoom 变，测出来的值总慢半拍）。
    var pan by remember { mutableStateOf(Offset.Zero) }

    // 缩放动画：spring 一点点过冲就回弹，手感"跟手但不生硬"。
    // 用 animateFloatAsState 而不是在 graphicsLayer 里做，是为了让图片按**目标尺寸
    // 重新采样**（清晰度不丢），而不是把已画好的位图拉伸。
    val animatedZoom by animateFloatAsState(
        targetValue = zoom,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "readerPageZoom",
    )

    val ratios = ordered.map { p ->
        if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 0.7f
    }
    val totalRatio = ratios.sum()
    val gapTotal = if (ordered.size > 1) PAGE_GAP_DP * (ordered.size - 1) else 0f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 适应高度（R-30）：zoom=1 时整页正好铺满视口高度。
        val baseCellHeight = if (totalRatio > 0f) {
            minOf(maxHeight.value, (maxWidth.value - gapTotal) / totalRatio)
        } else {
            maxHeight.value
        }
        val cellHeight = (baseCellHeight * animatedZoom).coerceAtLeast(1f)
        val contentWidth = cellHeight * totalRatio + gapTotal
        val contentHeight = cellHeight

        // 余量用「算出来的尺寸」而不是「测出来的尺寸」：两者只差一帧，但算出来的
        // 在缩放的同一帧就正确，拖动不会先撞一次假边界。
        val density = LocalDensity.current
        val contentWpx = with(density) { contentWidth.dp.toPx() }
        val contentHpx = with(density) { contentHeight.dp.toPx() }
        val viewportWpx = with(density) { maxWidth.toPx() }
        val viewportHpx = with(density) { maxHeight.toPx() }
        // 内容已经是"放大后"的尺寸，所以 panLimit 的 zoom 传 1f（余量 = 超出视口的一半）
        val limitX = panLimit(1f, contentWpx, viewportWpx)
        val limitY = panLimit(1f, contentHpx, viewportHpx)
        // 组合期就夹紧：缩小后余量变紧，平移自动收回范围内（缩回 100% 归零）
        val clampedPan = Offset(
            pan.x.coerceIn(-limitX, limitX),
            pan.y.coerceIn(-limitY, limitY),
        )

        // 手势不重启（pointerInput(Unit)）：用 rememberUpdatedState 拿最新余量，
        // 否则放大后第一次拖拽还在按 zoom=1 的旧余量算，表现为拖不动。
        val limitXState by rememberUpdatedState(limitX)
        val limitYState by rememberUpdatedState(limitY)

        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                // 放大态给可拖动光标：用户不知道新功能存在时，光标是唯一提示
                .then(
                    if (limitX > 0f || limitY > 0f) Modifier.pointerHoverIcon(PointerIcon.Hand)
                    else Modifier
                )
                // 拖拽平移 + 轻点翻页统一收口成一个手势：旧实现是左右两个 clickable
                // 热区叠在图上，拖拽会被 clickable 判成按下后移动而取消点击——
                // 平移和点击不能简单并存，这里合成一个 awaitEachGesture。
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var last = down.position
                        var swipeX = 0f // 未放大态累积的横向滑动（松手后判翻页）
                        drag(down.id) { change ->
                            val delta = change.position - last
                            last = change.position
                            change.consume()
                            if (!moved && (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop)) {
                                moved = true
                            }
                            val lx = limitXState
                            val ly = limitYState
                            if (lx > 0f || ly > 0f) {
                                // 放大态：拖着看，两个轴都夹紧在余量内
                                pan = Offset(
                                    (pan.x + delta.x).coerceIn(-lx, lx),
                                    (pan.y + delta.y).coerceIn(-ly, ly),
                                )
                            } else {
                                // 未放大：只攒横向滑动（页模式没有竖向内容，竖向滑不翻页）
                                swipeX += delta.x
                            }
                        }
                        // 松手判定：没动过 = 轻点（中心带切沉浸、左右半屏翻页）；
                        // 动过且没放大 = 横向滑动翻页
                        val lx = limitXState
                        val ly = limitYState
                        when {
                            !moved -> {
                                // 中心带 = 沉浸模式开关（R-43）：左右半屏仍各翻各的页
                                if (inCenterTapBand(down.position.x, size.width.toFloat())) {
                                    onTapCenter()
                                } else if (down.position.x < size.width / 2f) {
                                    onTapPrev()
                                } else {
                                    onTapNext()
                                }
                            }
                            lx <= 0f && ly <= 0f && abs(swipeX) > SWIPE_TURN_PX ->
                                if (swipeX < 0f) onTapNext() else onTapPrev()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    // offset 的 lambda 版本在**布局阶段**读 pan：拖动时只重新摆放，
                    // 不重组、不重绘子树——这是"拖着看"跟手的关键。
                    .offset { IntOffset(clampedPan.x.roundToInt(), clampedPan.y.roundToInt()) }
                    // requiredSize 忽略父约束：放大后内容本来就该超出视口，
                    // 用普通 size 会被父约束夹回视口大小（那就是"放大只放大白边"的 R-32）。
                    .requiredSize(width = contentWidth.dp, height = contentHeight.dp),
                horizontalArrangement = if (ordered.size > 1) {
                    Arrangement.spacedBy(PAGE_GAP_DP.dp)
                } else {
                    Arrangement.Center
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ordered.forEachIndexed { i, page ->
                    RemoteImage(
                        url = page.url,
                        contentDescription =
                            "第 ${startIndex + (if (rtl) slice.lastIndex - i else i) + 1} 页",
                        modifier = Modifier
                            .height(cellHeight.dp)
                            .aspectRatio(ratios[i], matchHeightConstraintsFirst = true),
                        contentScale = ContentScale.Fit,
                        // Medium = 双线性 + mipmap：整页缩小显示时带宽降一个量级，
                        // 也是"滚起来卡卡的"最直接的解（见 RemoteImage.filterQuality 注释）
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        headers = page.requestHeaders(),
                        loadDelayMillis = loadDelayMillis,
                    )
                }
            }
        }
    }
}
