package app.gamanga.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.model.ChapterEdge
import app.gamanga.desktop.model.EDGE_JUMP_ACCUM_PX
import app.gamanga.desktop.model.chapterEdge
import app.gamanga.desktop.model.edgeOverscroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 条漫拖拽回弹的复现回路（第九轮 #3，mp-diagnosing-bugs 第一步）。
 *
 * 复刻 ReaderScreen 条漫滚动全部要素（Initial 通道收口 + Channel 单协程
 * scrollBy(-delta.y) + 惯性 + 边界蓄力），performMouseInput 注入真实鼠标，
 * 断言「松手后位置不回弹」。读数走 [progressNow]（主线程直读，无滞后）。
 */
class ReaderDragScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private var listStateRef: androidx.compose.foundation.lazy.LazyListState? = null
    private var wheelDeltasRef: Channel<Float>? = null

    /** 主线程直读滚动位置（index*item + offset）。 */
    private fun progressNow(): Int {
        var p = 0
        rule.runOnIdle {
            val s = listStateRef ?: return@runOnIdle
            p = s.firstVisibleItemIndex * 300 + s.firstVisibleItemScrollOffset
        }
        return p
    }

    @Composable
    private fun StripBed() {
        val listState = rememberLazyListState()
        listStateRef = listState
        val scope = rememberCoroutineScope()
        var edgeAccum = 0f
        var flingJob: Job? = null
        // 与 ReaderScreen 一致：滚轮增量汇入 Channel、常驻协程顺序滚（第九轮 #4）
        val wheelDeltas = remember { Channel<Float>(Channel.UNLIMITED) }
        wheelDeltasRef = wheelDeltas
        LaunchedEffect(Unit) {
            for (requested in wheelDeltas) {
                var consumed = 0f
                listState.scroll { consumed = scrollBy(requested) }
                val edge = chapterEdge(requested, consumed)
                edgeAccum = edgeOverscroll(edgeAccum, requested, edge)
                when (edge) {
                    ChapterEdge.PREV -> if (edgeAccum <= -EDGE_JUMP_ACCUM_PX) edgeAccum = 0f
                    ChapterEdge.NEXT -> if (edgeAccum >= EDGE_JUMP_ACCUM_PX) edgeAccum = 0f
                    ChapterEdge.NONE -> Unit
                }
            }
        }
        Box(
            // 滚轮处理挂外层（对应 ReaderScreen 的 Column 层）：Initial 通道先消费，
            // LazyColumn 原生滚轮收不到；增量走 Channel 串行。
            Modifier.fillMaxSize().testTag("strip").pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy == 0f) continue
                        event.changes.forEach { it.consume() }
                        wheelDeltas.trySend(dy * 300f)
                    }
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                    var last = down.position
                    var dragged = false
                    var totalDx = 0f
                    var totalDy = 0f
                    val touchSlop = viewConfiguration.touchSlop
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    val deltas = Channel<Offset>(Channel.UNLIMITED)
                    scope.launch {
                        try {
                            for (delta in deltas) {
                                var consumed = 0f
                                listState.scroll { consumed = scrollBy(-delta.y) }
                                val edge = chapterEdge(-delta.y, consumed)
                                edgeAccum = edgeOverscroll(edgeAccum, -delta.y, edge)
                                when (edge) {
                                    ChapterEdge.PREV -> if (edgeAccum <= -EDGE_JUMP_ACCUM_PX) edgeAccum = 0f
                                    ChapterEdge.NEXT -> if (edgeAccum >= EDGE_JUMP_ACCUM_PX) edgeAccum = 0f
                                    ChapterEdge.NONE -> Unit
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!change.pressed) break
                        val delta = change.position - last
                        if (delta == Offset.Zero) continue
                        last = change.position
                        totalDx += delta.x
                        totalDy += delta.y
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        // 累积位移超 touchSlop 才算拖拽（与产品同步：单帧判定下
                        // 小步长慢拖永远不触发——用户实测「正常幅度就回弹」的真凶）
                        if (!dragged && abs(totalDx) < touchSlop && abs(totalDy) < touchSlop) continue
                        dragged = true
                        change.consume()
                        deltas.trySend(delta)
                    }
                    deltas.close()
                    if (dragged) {
                        val vy = velocityTracker.calculateVelocity().y
                        if (abs(vy) >= 300f) {
                            // 与产品同步：下限 60（小手感）/ 上限 50% 拖拽距（防甩尾）
                            val flingCap = (abs(totalDy) * 0.5f).coerceAtLeast(60f)
                            var flingTravelled = 0f
                            flingJob = scope.launch {
                                var v = vy
                                var lastFrameNanos = withFrameNanos { it }
                                while (abs(v) > 50f) {
                                    val now = withFrameNanos { it }
                                    val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.064f)
                                    lastFrameNanos = now
                                    val stepPx = -v * dt
                                    var consumed = 0f
                                    listState.scroll { consumed = scrollBy(stepPx) }
                                    if (abs(consumed) < abs(stepPx) - 1f) break
                                    flingTravelled += abs(consumed)
                                    if (flingTravelled >= flingCap) break
                                    v *= 0.85f
                                }
                            }
                        }
                    }
                }
            }
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(50) { i ->
                    Box(
                        Modifier.fillMaxWidth().height(300.dp)
                            .background(if (i % 2 == 0) Color(0xFF334455) else Color(0xFF554433))
                    )
                }
            }
        }
    }

    private fun MouseInjectionScope.dragSteps(dy: Float, steps: Int = 12) {
        repeat(steps) { moveBy(Offset(0f, dy / steps)) }
    }

    /** 上拖 500px 到中段，等惯性停稳（主线程直读，连续 400ms 不变）。 */
    private fun scrollToMiddle() {
        rule.onNodeWithTag("strip").performMouseInput {
            moveTo(center); press(); dragSteps(-500f); release()
        }
        rule.waitUntil(3_000) { progressNow() > 400 }
        var lastP = -1
        var stableSince = 0L
        rule.waitUntil(6_000) {
            val p = progressNow()
            val now = System.currentTimeMillis()
            if (p == lastP) {
                if (now - stableSince > 400) return@waitUntil true
            } else {
                lastP = p; stableSince = now
            }
            false
        }
    }

    @Test
    fun `drag down from middle does not bounce back`() {
        rule.setContent { StripBed() }
        scrollToMiddle()
        val before = progressNow()

        rule.onNodeWithTag("strip").performMouseInput {
            moveTo(center); press(); dragSteps(250f)
        }
        rule.waitForIdle()
        val afterDrag = progressNow()
        rule.onNodeWithTag("strip").performMouseInput { release() }
        repeat(8) { i ->
            Thread.sleep(100)
        }
        rule.waitForIdle()
        val afterRelease = progressNow()

        // 向下拖 = 看前面 = 位置减少；且不应回弹到拖拽前
        assertTrue(afterDrag < before - 50, "向下拖没滚动：before=$before afterDrag=$afterDrag")
        assertTrue(
            afterRelease <= afterDrag + 2,
            "向下拖后回弹了：拖拽前=$before 松手前=$afterDrag 松手后=$afterRelease"
        )
    }

    @Test
    fun `drag up from middle does not bounce back`() {
        rule.setContent { StripBed() }
        scrollToMiddle()
        val before = progressNow()

        rule.onNodeWithTag("strip").performMouseInput {
            moveTo(center); press(); dragSteps(-250f)
        }
        rule.waitForIdle()
        val afterDrag = progressNow()
        rule.onNodeWithTag("strip").performMouseInput { release() }
        Thread.sleep(500)
        rule.waitForIdle()

        assertTrue(afterDrag > before + 50, "向上拖没滚动：before=$before afterDrag=$afterDrag")
    }

    @Test
    fun `wheel deltas are consumed in order without cancellation`() {
        rule.setContent { StripBed() }
        scrollToMiddle()
        val before = progressNow()

        // 模拟连滚 5 格（每格 300px，向上看后面）：5 个增量快速进通道。
        // 旧实现每格 launch 新协程 → MutatorMutex 互取消 → 增量被吞
        // （用户实测「往上滚回弹，等于没滚动」）；串行消费后 5 格全部生效。
        rule.runOnIdle {
            repeat(5) { wheelDeltasRef!!.trySend(300f) }
        }
        rule.waitUntil(3_000) { progressNow() >= before + 5 * 300 - 2 }
        val afterUp = progressNow()
        assertTrue(afterUp >= before + 5 * 300 - 2, "连滚增量被吞：before=$before after=$afterUp")

        // 反向 3 格（向下看前面）：同样全部生效
        rule.runOnIdle {
            repeat(3) { wheelDeltasRef!!.trySend(-300f) }
        }
        rule.waitUntil(3_000) { progressNow() <= afterUp - 3 * 300 + 2 }
        val afterDown = progressNow()
        assertTrue(afterDown <= afterUp - 3 * 300 + 2, "反向连滚被吞：afterUp=$afterUp afterDown=$afterDown")
    }

    @Test
    fun `consecutive middle-button drags do not overshoot`() {
        rule.setContent { StripBed() }
        scrollToMiddle()

        // 连拖 3 次（每次上拖 200px + 松手 + 等惯性停）——用户实测
        // 「中键连续拖动几次后约 130% 幅度异常上跳」（第十轮 #4），修完后
        // 又反馈「没有滑动感」（第十二轮 #2）——故区间定为
        // 「有惯性（>200）但不带飞（≤150%）」。
        // 注：产品手势逻辑不区分鼠标按键（awaitFirstDown 收所有键），
        // 中键与左键走同一条拖拽路径，这里用默认 press 覆盖该场景。
        val positions = mutableListOf(progressNow())
        repeat(3) {
            rule.onNodeWithTag("strip").performMouseInput {
                moveTo(center)
                press()
                dragSteps(-200f)
            }
            rule.waitForIdle()
            rule.onNodeWithTag("strip").performMouseInput { release() }
            rule.waitForIdle()
            Thread.sleep(500) // 等惯性衰减完
            rule.waitForIdle()
            positions += progressNow()
        }
        positions.zipWithNext().forEach { (a, b) ->
            val step = b - a
            // 区间：>200（有惯性滑感）+ ≤300（上限=拖拽距 150%，防甩尾带飞）
            assertTrue(
                step in 201..300,
                "单次中键拖动位移异常：$step（期望 201~300：拖200+惯性≤100；序列=$positions）"
            )
        }
    }

    @Test
    fun `small left drag from middle does not bounce back`() {
        rule.setContent { StripBed() }
        scrollToMiddle()
        val before = progressNow()

        // 左键小幅度拖 150px（正常幅度，用户实测「正常幅度下就会回弹」）
        rule.onNodeWithTag("strip").performMouseInput {
            moveTo(center); press(); dragSteps(-150f)
        }
        rule.waitForIdle()
        val afterDrag = progressNow()
        rule.onNodeWithTag("strip").performMouseInput { release() }
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()

        assertTrue(afterDrag > before + 100, "左键小拖没滚动：before=$before afterDrag=$afterDrag")
        assertTrue(
            progressNow() >= afterDrag - 2,
            "左键小拖后回弹：before=$before 松手前=$afterDrag 松手后=${progressNow()}"
        )
    }
}
