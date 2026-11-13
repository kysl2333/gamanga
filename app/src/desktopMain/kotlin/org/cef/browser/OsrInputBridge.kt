/**
 * JCEF 152 的 sendMouseEvent / sendKeyEvent / sendMouseWheelEvent / wasResized 是
 * CefBrowser_N 的 protected final 成员，包外不可见。按 JCEF OSR 集成的标准做法，
 * 在本包（org.cef.browser）内放一个桥接对象暴露它们。
 *
 * 注意：CefBrowser_N 是**包私有类**，包外代码无法命名该类型，因此桥接的入参
 * 一律收公有的 [CefBrowser] 接口，内部再转型（OSR 模式下 createBrowser 返回的
 * 运行时实例必为 CefBrowser_N 子类 CefBrowserOsr）。
 */
package org.cef.browser

import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

object OsrInputBridge {

    private fun nativeBrowser(browser: CefBrowser): CefBrowser_N = browser as CefBrowser_N

    /** CefBrowserOsr 的 OSR 视口矩形（getViewRect 直接返回它）。 */
    private val viewRectField = runCatching {
        CefBrowserOsr::class.java.getDeclaredField("browser_rect_").apply { isAccessible = true }
    }.getOrNull()

    fun sendMouseEvent(browser: CefBrowser, event: MouseEvent) =
        nativeBrowser(browser).sendMouseEvent(event)

    fun sendKeyEvent(browser: CefBrowser, event: KeyEvent) =
        nativeBrowser(browser).sendKeyEvent(event)

    fun sendMouseWheelEvent(browser: CefBrowser, event: MouseWheelEvent) =
        nativeBrowser(browser).sendMouseWheelEvent(event)

    /** OSR 视口尺寸（原生侧据此更新 getViewRect 返回的 browser_rect_）。 */
    fun wasResized(browser: CefBrowser, width: Int, height: Int) {
        nativeBrowser(browser).wasResized(width, height)
    }

    /**
     * 请求 CEF 按当前视口重画一次。
     * CEF 只在有 damage 时才发 paint 事件——窗口放大后页面内容没变，
     * 不主动 invalidate 就会一直拿着旧尺寸的帧（表现为画面被拉伸放大）。
     */
    fun invalidate(browser: CefBrowser) {
        nativeBrowser(browser).invalidate()
    }

    /**
     * 直接把 OSR 视口设为 width×height 并让 CEF 重排重画。
     *
     * 为什么不靠 JOGL：`browser_rect_` 只在 GLCanvas 的 `reshape` 回调里更新，而离屏窗口
     * 收不到 WM_PAINT，Swing 不一定会及时 validate，reshape 的时机实测不可靠（窗口放大后
     * 视口偶发停在旧尺寸）。这里直接写矩形 + wasResized + invalidate，一次到位。
     *
     * @return false 表示反射不可用（JCEF 版本变了），调用方需退回 reshape 路径
     */
    fun setViewRect(browser: CefBrowser, width: Int, height: Int): Boolean {
        val field = viewRectField ?: return false
        val osr = browser as? CefBrowserOsr ?: return false
        val rect = runCatching { field.get(osr) as? Rectangle }.getOrNull() ?: return false
        rect.setBounds(0, 0, width, height)
        wasResized(browser, width, height)
        invalidate(browser)
        return true
    }
}
