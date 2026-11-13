package app.gamanga.desktop.ui.reader

import androidx.compose.runtime.mutableStateMapOf
import java.awt.Rectangle
import java.awt.Window
import java.util.WeakHashMap

/**
 * 阅读器「全屏 / 还原窗口」（2026-10-30 第三轮：**改成瞬间切换，不做动画**）。
 *
 * ## 三轮演进（别把前两轮的结论丢掉）
 *
 * | 轮次 | 做法 | 结果 |
 * |---|---|---|
 * | 批次42 | `GraphicsDevice.fullScreenWindow`（独占全屏） | **闪退**：切显示模式 + 重建窗口/GL 上下文，机器上留下 NVIDIA `GPU has been disconnected (code 10)` 与 `awt.dll` 访问违例 |
 * | 批次45 | 190ms 逐帧插值 `window.bounds` | 不崩了，但**还是卡**：190ms 里要改 12 次窗口矩形，每次都是一次"交换链 resize + 整窗重新布局 + 漫画页按新视口重新 Fit" |
 * | 批次46 | **一次赋值，瞬间到位** | 用户原话「把窗口和全屏的动画移除，让它能一下就变成全屏」 |
 *
 * ## 为什么动画在这个场景里注定贵
 *
 * 一次 `window.bounds` 赋值在 Windows 上要连着做三件事：
 * ① `SetWindowPos` → ② Skia 的 D3D 交换链 `ResizeBuffers` → ③ Compose 整棵树重新 measure/layout。
 * 第 ③ 步里阅读器那张 3000×4000 的扫描图会按新视口**重新算一次 Fit 目标矩形**。
 * 单帧看都不贵，但连做 12 帧就是"卡"。而且全屏时像素数从 1180×820 涨到 2560×1440（约 3.8 倍），
 * 每帧要填的像素本身也变多了 —— 所以**分辨率和动画是叠加的**，动画一去掉，只剩一次性的开销。
 *
 * ## 现在只保留三件必要的事
 *
 * 1. 进入前把当前 `bounds` 存进 [savedBounds]（WeakHashMap，多开互不干扰、窗口关了自动丢）；
 * 2. 全屏期间临时 `isAlwaysOnTop = true` —— 任务栏自己是置顶窗口，不置顶就会被它压住；
 *    还原时把置顶状态**恢复成进入前的值**（用户可能本来就手动置顶过）；
 * 3. 还原时精确回到存下来的矩形，所以"回到窗口"一定是原来的位置和大小。
 *
 * 宿主（[ReaderWindowHost]）通过 [isFullscreen] 观察状态，同步把 4dp 阴影边距与 12dp 圆角
 * 收成 0——全屏时窗口边缘不该再有圆角缝。那两处也**不做过渡**：它们会改 `padding`，
 * 逐帧变化等于逐帧重新 Fit 一次大图，和动画是同一个病。
 */
object ReaderFullscreen {

    /** 哪些窗口当前是全屏（Compose 可观察）。 */
    private val active = mutableStateMapOf<Window, Boolean>()

    /** 进入全屏前的窗口矩形。 */
    private val savedBounds = WeakHashMap<Window, Rectangle>()

    /**
     * 进入全屏**之前**的置顶状态。
     *
     * 必须单独记：还原那一刻窗口已经是置顶的了（进全屏时设的），
     * 拿"当前值"当"原值"会把置顶永久留在窗口上——用户关掉阅读器重开还是置顶。
     */
    private val savedOnTop = WeakHashMap<Window, Boolean>()

    fun isFullscreen(window: Window?): Boolean = window != null && active[window] == true

    /**
     * 切换全屏。返回**实际生效的状态**（拿不到屏幕信息时保持原状，图标不能停在错的状态）。
     *
     * 同步执行：一次 `bounds` 赋值，调用方拿返回值直接更新按钮图标即可，不需要协程。
     */
    fun toggle(window: Window, on: Boolean): Boolean {
        val screen = runCatching { window.graphicsConfiguration?.bounds }.getOrNull()
            ?: return isFullscreen(window)
        val from = window.bounds
        val to = if (on) {
            savedBounds[window] = Rectangle(from)
            savedOnTop[window] = window.isAlwaysOnTop
            Rectangle(screen)
        } else {
            savedBounds.remove(window) ?: defaultBounds(screen)
        }
        if (to == from) {
            active[window] = on
            return on
        }
        if (on) {
            // 先置顶再改矩形：任务栏自己是置顶窗口，顺序反了会在变大那一下被它压住闪一帧。
            runCatching { window.isAlwaysOnTop = true }
            runCatching { window.bounds = to }
        } else {
            // 先缩回原矩形，再把置顶状态恢复成进入前的值（不是"当前值"）。
            runCatching { window.bounds = to }
            val previous = savedOnTop.remove(window) ?: false
            runCatching { window.isAlwaysOnTop = previous }
        }
        active[window] = on
        return on
    }

    /** 没有历史矩形时的还原目标：屏幕居中 1180×820，且不超出屏幕（internal 便于单测）。 */
    internal fun defaultBounds(screen: Rectangle): Rectangle {
        val w = minOf(1180, screen.width - 80)
        val h = minOf(820, screen.height - 80)
        return Rectangle(
            screen.x + (screen.width - w) / 2,
            screen.y + (screen.height - h) / 2,
            w,
            h,
        )
    }
}
