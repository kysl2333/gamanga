package app.gamanga.desktop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import java.awt.MouseInfo
import java.awt.Point
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * 当前窗口（`Main.kt` 通过 [LocalComposeWindow] 注入）。
 *
 * 为什么用 CompositionLocal 而不是一路传参：拖拽把手要出现在**壳层和页面内部**好几个地方
 * （NormalShell 顶部、阅读器顶栏），一路加参数会把签名污染到底。
 */
val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow?> { null }

/**
 * 窗口拖拽把手：一条透明、只负责「拖动窗口」的横条。
 *
 * ## 为什么单独抽出来（2026-10-27 第三次改）
 *
 * 拖拽区的位置试了三轮才对：
 * 1. **铺在整窗最上层** → 顶部 45dp 把阅读器 ✕ / 上一话 / 下一话 全吃掉（点击进不来）；
 * 2. **铺在最底层，靠「上层不消费就穿透」** → 对有滚动容器的页面完全失效
 *    （书架网格、设置页列表、阅读器翻页手势都会消费垂直拖拽），用户实测「拖不动」；
 * 3. **本方案**：把手**只放在壳层/顶栏自己的布局带里**（页面内容从它下面开始排），
 *    既不和任何按钮抢位置，也不依赖事件冒泡顺序。
 *
 * ## 拖动算法（两个坑都踩过）
 *
 * - 不能用「当前窗口位置 + 每次 drag 增量」：AWT 的 `setLocation` 是**异步**的，
 *   下一次 drag 读到的 `window.location` 还是旧值，增量被丢掉一半 →
 *   表现为「鼠标跑远了窗口只跟了一半」+ 左右抽搐。
 * - 正确做法：记住按下时的**鼠标屏幕坐标**和**窗口位置**，每次都用绝对偏移算。
 */
@Composable
fun WindowDragHandle(
    modifier: Modifier = Modifier,
    height: Dp = DragStripHeight,
    onDoubleClick: (() -> Unit)? = null,
) {
    val window = LocalComposeWindow.current ?: return
    var lastClickAt by remember(window) { mutableLongStateOf(0L) }
    Box(
        // 宽度由调用方决定：壳层用 fillMaxWidth（整条顶带），
        // 阅读器用 weight(1f)（顶栏中间那段空白）
        modifier.height(height)
             .pointerInput(window, onDoubleClick) {
                var startPointer: Point? = null
                var startWindow: Point? = null
                detectDragGestures(
                    onDragStart = {
                        startPointer = MouseInfo.getPointerInfo()?.location
                        startWindow = window.location
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pointer = MouseInfo.getPointerInfo()?.location
                        val fromPointer = startPointer
                        val fromWindow = startWindow
                        if (pointer != null && fromPointer != null && fromWindow != null) {
                            window.setLocation(
                                fromWindow.x + (pointer.x - fromPointer.x),
                                fromWindow.y + (pointer.y - fromPointer.y),
                            )
                        }
                    },
                )
            }
            .pointerInput(window, onDoubleClick) {
                onDoubleClick?.let { detectTapGestures(onDoubleTap = { it() }) }
            },
        // 对齐由调用方给（通常是 TopCenter）；这里不写死，方便阅读器复用
    )
}
