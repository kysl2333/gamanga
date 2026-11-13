package app.gamanga.desktop.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 「点搜索框以外的任何地方 → 搜索框失焦」（F-130，用户 2026-10-27）。
 *
 * ## 用户原话
 *
 * 「点击搜索框后点击其他任意地方无法关闭输入文本的状态」。
 *
 * ## 为什么会这样
 *
 * 页面里的卡片用的是自定义 `onPointerEvent`（为了区分左右键），按下即 `consume()`——
 * 事件被吃掉后，Compose 的焦点系统就收不到「点到别处了」的信号，输入框一直保持焦点，
 * 光标一直闪、边框一直是强调色。
 *
 * ## 为什么不用「无条件 clearFocus」
 *
 * 那样点搜索框本身也会先失焦再被输入框重新抢回焦点，光标位置/选区会跳一下。
 * 这里做**几何判定**：记下搜索栏与页面根在窗口里的位置，按下点换算到窗口坐标后
 * 落在搜索栏矩形内就不动，否则失焦。
 *
 * 用法（两个页面各一处）：
 * ```kotlin
 * val searchBounds = rememberSearchBarBounds()
 * Column(
 *     Modifier
 *         .onGloballyPositioned { searchBounds.recordPageOrigin(it.localToWindow(Offset.Zero)) }
 *         .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
 *             searchBounds.dismissIfOutside(it.changes.first().position)
 *         }
 * ) {
 *     SearchTopBar(..., bounds = searchBounds)
 *     ...
 * }
 * ```
 * 用 **Initial** 阶段：在子元素（卡片/按钮）之前判定，不消费事件，所以卡片照常响应。
 */
class SearchBarBounds {

    /** 搜索栏（420dp 的胶囊+搜索键那一组）在窗口里的矩形。 */
    private var barInWindow by mutableStateOf(Rect.Zero)

    /** 页面根在窗口里的原点。 */
    private var pageOrigin by mutableStateOf(Offset.Zero)

    private var focusManager: androidx.compose.ui.focus.FocusManager? = null

    /** 由 [SearchTopBar] 调用。 */
    fun recordBar(rect: Rect) {
        barInWindow = rect
    }

    /** 由页面根调用。 */
    fun recordPageOrigin(origin: Offset) {
        pageOrigin = origin
    }

    internal fun attach(focus: androidx.compose.ui.focus.FocusManager) {
        focusManager = focus
    }

    /**
     * 按下点在**页面根坐标系**里；换算成窗口坐标后与搜索栏矩形比较。
     * 在搜索栏外就清焦点。
     */
    fun dismissIfOutside(pagePos: Offset) {
        if (barInWindow == Rect.Zero) return
        if (!barInWindow.contains(pagePos + pageOrigin)) {
            focusManager?.clearFocus()
        }
    }
}

@Composable
fun rememberSearchBarBounds(): SearchBarBounds {
    val manager = LocalFocusManager.current
    val bounds = remember { SearchBarBounds() }
    bounds.attach(manager)
    return bounds
}

/**
 * 给页面根挂上「点外面失焦」：把这一行加在根的 `Modifier` 链上即可。
 *
 * 单独抽出来是为了让两个页面（书架 / 浏览）写出来一模一样——
 * 这个项目已经因为「两处各写一套」踩过搜索栏错位 11dp 的坑。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dismissSearchFocusOnOutsidePress(bounds: SearchBarBounds): Modifier = this
    .onGloballyPositioned { bounds.recordPageOrigin(it.localToWindow(Offset.Zero)) }
    .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        bounds.dismissIfOutside(change.position)
    }
