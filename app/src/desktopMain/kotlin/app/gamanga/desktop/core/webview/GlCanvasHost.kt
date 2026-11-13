package app.gamanga.desktop.core.webview

import com.jogamp.opengl.GLAutoDrawable
import java.awt.Component
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 给 JCEF OSR 浏览器内部那块 GLCanvas 一个能 realize 的容身之处。
 *
 * 背景（jcefmaven 152 的 OSR paint 链路，反编译 `CefBrowserOsr.onPaint` 确认）：
 * ```
 * GLContext c = canvas_.getContext();  if (c == null) return;      // 静默丢弃
 * if (c.makeCurrent() == NOT_CURRENT) return;                       // 静默丢弃
 * renderer_.onPaint(...); c.release();
 * 通知 onPaintListeners
 * ```
 * 而 JCEF 的 GLCanvas 只构造不 realize，实测三条路：
 *  - 无父容器 → `addNotify()` 抛 `GLException: NULL AWT GraphicsConfiguration`；
 *  - 只 `Frame.addNotify()` 不显示 → context 建了但 `makeCurrent` 失败；
 *  - 放进可见 Frame → 全链路正常。
 * 所以这里用一个**屏幕外、不可聚焦**的 JFrame 让它完成 realize；真实画面由 Compose
 * 层按 paint 帧绘制，本 Frame 永不示人。
 *
 * **尺寸刻意不再同步（踩坑后的结论）**：CEF 的视口由 `CefBrowserOsr.browser_rect_`
 * 决定，而它有两个写者——JOGL 的 `reshape` 回调（跟着 canvas 尺寸走）和我们经
 * [org.cef.browser.OsrInputBridge.setViewRect] 的直写。之前两者并行，互相覆盖，
 * 且 `GLCanvas` 每次 resize 都要在 EDT 上跑 `display()` 抢 GLContext，而 CEF 线程
 * 的 paint 也在抢同一个 context——抢输的那一帧被上面第二道门槛直接丢掉，
 * 表现为 resize 后画面停在旧尺寸（偶发，实测 3 跑挂 2）。
 * 现在视口只有一个写者（setViewRect），canvas 尺寸固定不变，竞争窗口消失。
 *
 * 本 Frame 的 GL 输出一律不看：我们读的是 CEF 给的 CPU 像素缓冲，
 * GL 画成什么尺寸、是否被裁剪都无所谓——它只负责让 `makeCurrent` 能成功。
 *
 * 另需 JVM 参数 `--add-opens java.desktop/sun.awt=ALL-UNNAMED`（见 build.gradle.kts）。
 */
class GlCanvasHost(component: Component, width: Int, height: Int) {

    private val frame = JFrame()

    /** 仅在 [org.cef.browser.OsrInputBridge.setViewRect] 反射失效时作为退路使用。 */
    private val drawable = component as? GLAutoDrawable

    init {
        frame.isUndecorated = true
        frame.setAutoRequestFocus(false)
        frame.setFocusableWindowState(false) // 不进任务栏 / 不参与 Alt+Tab / 不抢焦点
        frame.setSize(width, height)
        frame.setLocation(OFFSCREEN_X, OFFSCREEN_Y)
        frame.contentPane.add(component)
        onEdt { frame.isVisible = true }
    }

    /**
     * 退路：反射写 `browser_rect_` 失效时（JCEF 换版本改了字段名），
     * 改由 JOGL 的 reshape 链路更新视口。会重新引入 GLContext 竞争，但聊胜于无。
     */
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        onEdt {
            if (frame.width != width || frame.height != height) {
                frame.setSize(width, height)
                frame.validate() // 离屏窗口不会自动布局，必须显式 validate 才会传给 canvas
                runCatching { drawable?.display() } // JOGL 发现尺寸变了才回调 reshape
            }
        }
    }

    fun dispose() = onEdt { frame.dispose() }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private companion object {
        // 屏幕外坐标：多屏虚拟桌面也从 (0,0) 起算，负一万足以落在所有显示器之外
        const val OFFSCREEN_X = -10_000
        const val OFFSCREEN_Y = -10_000
    }
}
