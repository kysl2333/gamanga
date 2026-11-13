package app.gamanga.desktop.core.webview

import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.OsrInputBridge
import org.cef.callback.CefCookieVisitor
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefFocusHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 内嵌浏览器的 UI 可观测状态。 */
data class BrowserUiState(
    val url: String = "about:blank",
    val title: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

/**
 * JCEF 实现的半网页浏览器宿主（M2）。
 *
 * OSR 离屏渲染：CEF 的 paint 事件在 CEF 线程回调，转成 [java.awt.image.BufferedImage]
 * 后经 Compose 的 toPainter 绘制；鼠标/键盘事件由 Compose 层转发进来
 * （send* 是 protected，经 org.cef.browser.OsrInputBridge 同包桥接）。
 *
 * 三个 JCEF 152 OSR 的关键约束（都踩过坑）：
 *  1. **N_CreateBrowser 是异步的**：`createImmediately()` 只把创建任务投给 CEF UI
 *     线程，返回时原生 ref 仍未就绪；此窗口内调 loadURL / setFocus 会被
 *     JNI_GET_BROWSER_OR_RETURN 静默吞掉（no-op）。因此首屏 URL 必须走
 *     [client.createBrowser] 的 url 参数（跟随创建一起导航），焦点等操作挂
 *     onAfterCreated 回调，**不能**在 createImmediately 之后直调。
 *  2. **paint 帧的 GL 门槛**：`CefBrowserOsr.onPaint` 通知 listener 前要求内部
 *     GLCanvas 的 GLContext 可用，而 GLCanvas 默认不 realize——需 [GlCanvasHost]
 *     把它放进可见（屏幕外）的 Frame，否则 listener 收不到任何帧。
 *  3. **焦点必须显式给**：GLCanvas 住在不可聚焦的离屏 Frame 里，永远收不到 AWT
 *     focusGained，CefBrowserOsr 自带的 focusGained→setFocus 链路是死的；而
 *     Chromium 对无焦点的浏览器直接丢弃键盘事件（表现为「页面点得了但输不了
 *     文字」）。因此在 onAfterCreated 里 setFocus(true)，并由 UI 层在获得
 *     焦点/点击画布时经 [setFocus] 同步。
 *
 * Cookie 导出走 [CefCookieManager.visitUrlCookies]（比驱动 CDP 直接）；
 * 带 HttpOnly，正是原生 HTTP 层借会话需要的。
 *
 * @param initialUrl 首屏地址（随浏览器创建一起导航，避开创建竞态，见约束 1）
 */
/** 默认视口：和 [GlCanvasHost] 的初始尺寸一致，保证没人调 resize 时也有一帧能跑。 */
private const val DEFAULT_VIEWPORT_W = 1280
private const val DEFAULT_VIEWPORT_H = 720

/**
 * @param forceFrames 主动泵帧（每 33ms 一次 `invalidate()`）。
 *   离屏（OSR）浏览器在**没有可见窗口**时，CEF 的 windowless 帧定时器可能根本不跑
 *   （实测 `windowlessFrameRate` 读出来是 0），于是合成器不产帧、`requestAnimationFrame`
 *   永远不触发 —— 依赖客户端渲染的站点（G社/包子章节页：服务端只给转圈占位）就永远没有图。
 *   隐藏探针（BrowserProbe）必须开；有界面的覆盖层靠真实交互产帧，不必开（省 CPU）。
 */
class JcefWebViewHost(
    initialUrl: String = "about:blank",
    private val forceFrames: Boolean = false,
    /** 启动前导入的 cookie（appPrefs 里存的上次登录态）。见 [importCookies]。 */
    initialCookies: Map<String, String> = emptyMap()
) : WebViewHost {

    private val client: CefClient = CefApp.getInstance().createClient()

    /**
     * 主浏览器实例。**必须晚于 handler 注册**（见 init 注释）：
     * onAfterCreated 可能在 createImmediately 内同步到达，handler 后注册就永远收不到。
     */
    private lateinit var browser: CefBrowser

    /**
     * realize JCEF 的隐藏 GLCanvas（OSR paint 帧的前置条件，见 [GlCanvasHost]）。
     * 尺寸固定不再跟随视口——视口由 [resize] 经 `browser_rect_` 单独管理。
     */
    private var glCanvas: GlCanvasHost? = null

    /**
     * 最新一帧（CEF 线程写入，Compose 侧 collectAsState 订阅）。
     *
     * 用 StateFlow 而非「帧序号 + 每帧轮询」：轮询会逼着 Compose 每 vsync 都
     * 走一遍重组/呈现（240Hz 屏就是 240 次/秒），与 CEF 自己的 paint 节奏互相
     * 拍频，是 G-Sync/VRR 显示器闪烁与高 CPU 占用的来源。事件驱动则只在真有
     * 新帧时呈现一次。
     */
    private val _frame = kotlinx.coroutines.flow.MutableStateFlow<java.awt.image.BufferedImage?>(null)
    val frameFlow: kotlinx.coroutines.flow.StateFlow<java.awt.image.BufferedImage?> = _frame

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(BrowserUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<BrowserUiState> = _uiState

    /** 原生浏览器是否已创建完成（onAfterCreated 后置位；之前 loadURL 是静默 no-op）。 */
    private val created = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 创建完成前收到的导航请求，挂到 onAfterCreated 后补发。 */
    @Volatile
    private var pendingUrl: String? = null

    /** CEF 侧焦点状态（onGotFocus/onTakeFocus 与 [setFocus] 共用的重入保护标志）。 */
    private val cefFocus = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 目标视口尺寸。创建完成前调用 resize 时先存着，onAfterCreated 后补应用。 */
    @Volatile
    private var viewport: Pair<Int, Int>? = null

    /** 视口重发用的单线程调度器（只在 resize 后短暂工作，daemon 不阻退出）。 */
    private val resizeRetry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "osr-viewport").apply { isDaemon = true }
    }

    /**
     * 顺序关键：**先注册 handler，再创建浏览器**。
     * createImmediately() 里 onAfterCreated 可能同步到达，handler 晚注册就永远收不到
     * （踩过：浏览器其实创建成功、title 已变成 about:blank，但 created 标志永远为 false）。
     */
    init {
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                b: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean
            ) {
                _uiState.value = _uiState.value.copy(
                    isLoading = isLoading, canGoBack = canGoBack, canGoForward = canGoForward
                )
                // 主框架「加载完」是注入的最佳时机之一——**不能只等 onLoadEnd**：
                // MangaBZ 详情页有长期未完成的统计/广告请求，onLoadEnd 迟迟不触发，
                // 于是 target=_blank 的改写没执行、按钮点不动（2026-10-24 用户实测
                // 「各话按钮还是点不动，进度条一直在跑」）。
                if (!isLoading) injectNoBlank(b)
            }

            /** 文档开始加载就注入一次：此时 DOM 还没解析完，但 MutationObserver 会接住后续节点。 */
            override fun onLoadStart(
                b: CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                transitionType: org.cef.network.CefRequest.TransitionType?
            ) {
                if (frame == null || !frame.isMain) return
                injectNoBlank(b)
            }

            /**
             * 页面加载完成后把 `target="_blank"` 全部改成 `_self`。
             *
             * **为什么不能只靠 onBeforePopup**（2026-10-24 用户实测：MangaBZ 详情页
             * 「每话按钮点了没一点反应」）：`target="_blank"` 走的是「新建弹窗浏览器」
             * 流程，OSR 下弹窗不可见；即便在 onBeforePopup 里返回 true 并调 loadURL，
             * 这次导航也常常发生在弹窗创建上下文里被 CEF 丢弃（表现为彻底无反应）。
             * 直接把页面的 target 改掉，点击就变成普通同页导航，不经过弹窗路径。
             *
             * 站点章节列表是异步插入的，所以挂 MutationObserver 而不是只跑一次。
             */
            override fun onLoadEnd(b: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame == null || !frame.isMain) return
                injectNoBlank(b)
            }
        })
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(b: CefBrowser?, frame: org.cef.browser.CefFrame?, url: String?) {
                _uiState.value = _uiState.value.copy(url = url ?: "")
            }

            override fun onTitleChange(b: CefBrowser?, title: String?) {
                _uiState.value = _uiState.value.copy(title = title ?: "")
            }
        })
        // 页面（自动聚焦的输入框等）请求焦点时回灌 setFocus。
        // **必须有重入保护**：setFocus(true) 会再触发 onGotFocus，无保护即 native↔Java
        // 无限递归，CEF UI 线程被占死。官方 OSR 样例同款 browserFocus_ 守卫。
        client.addFocusHandler(object : CefFocusHandlerAdapter() {
            override fun onGotFocus(b: CefBrowser?) {
                if (cefFocus.getAndSet(true)) return
                b?.setFocus(true)
            }

            override fun onTakeFocus(b: CefBrowser?, next: Boolean) {
                cefFocus.set(false) // 只记状态；此处再调 setFocus 同样会递归
            }
        })
        // 广告/劫持域名拦截（见 [AdBlocker]）：
        //  - onBeforeBrowse 拦顶层导航，避免页面被脚本带去 tsyndicate.com 变成错误页；
        //  - getResourceRequestHandler 拦子资源，省流量并避免广告脚本改坏 DOM
        //    （隐藏浏览器跑站点解码器时，DOM 被改坏就取不到章节图）。
        if (System.getProperty("gamanga.adblock") != "0") client.addRequestHandler(object : org.cef.handler.CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                b: CefBrowser?, frame: org.cef.browser.CefFrame?,
                request: org.cef.network.CefRequest?, userGesture: Boolean, isRedirect: Boolean
            ): Boolean {
                val url = request?.getURL().orEmpty()
                if (AdBlocker.isBlocked(url)) {
                    println("[adblock] 拦截导航：$url")
                    return true // 取消
                }
                return false
            }

            override fun getResourceRequestHandler(
                b: CefBrowser?, frame: org.cef.browser.CefFrame?,
                request: org.cef.network.CefRequest?, isNavigation: Boolean, isDownload: Boolean,
                requestInitiator: String?, disableJavascriptAccess: org.cef.misc.BoolRef?
            ): org.cef.handler.CefResourceRequestHandler? {
                val url = request?.getURL().orEmpty()
                if (!AdBlocker.isBlocked(url)) return null
                return object : org.cef.handler.CefResourceRequestHandlerAdapter() {
                    override fun onBeforeResourceLoad(
                        b: CefBrowser?, frame: org.cef.browser.CefFrame?, request: org.cef.network.CefRequest?
                    ) = true // 取消该请求
                }
            }
        })

        // 原生浏览器创建完成的唯一可靠信号：此刻起 setFocus / loadURL 才真正生效
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                browser: CefBrowser?,
                frame: org.cef.browser.CefFrame?,
                targetUrl: String?,
                targetFrameName: String?
            ): Boolean {
                // target=_blank / window.open：改为当前窗口导航并取消弹窗。
                // **必须推迟到下一个事件循环**：在 onBeforePopup 回调里同步 loadURL
                // 会被 CEF 的弹窗创建流程吞掉（2026-10-24 实测「点了没反应」的
                // 另一半原因）。页面级还有 NO_BLANK_TARGET_JS 兜底，这里是 JS 没
                // 生效时（脚本禁用的页 / window.open）的第二条腿。
                if (!targetUrl.isNullOrBlank()) {
                    val url = targetUrl
                    java.awt.EventQueue.invokeLater {
                        runCatching {
                            if (created.get()) browser?.loadURL(url) else pendingUrl = url
                        }
                    }
                }
                return true
            }

            override fun onAfterCreated(b: CefBrowser?) {
                if (b == null || created.get()) return // 同一 client 的弹窗忽略，只认首个
                created.set(true)
                // 用回调里的实例而非 browser 属性：同步回调时属性可能尚未赋值
                cefFocus.set(true)
                b.setFocus(true)
                pendingUrl?.let { url ->
                    pendingUrl = null
                    b.loadURL(url)
                }
                // **必须给一个非零视口**（R-27 的真正原因）：
                // 窗口化/离屏浏览器在没收到 wasResized 之前 `browser_rect_` 是 0×0，
                // 合成器没有表面 → 一帧都不产出 → **requestAnimationFrame 永远不触发**。
                // 页面照样能加载（网络/解析不需要布局），但所有"客户端异步渲染"的站点
                // （比如 G社/包子章节页：服务端只给转圈占位，图片靠 JS 插）就永远出不来图，
                // 表现为「该话没有图片」，而且 document.visibilityState 还是 visible，极难怀疑。
                // 隐藏探针（BrowserProbe）从不调 resize，所以之前必定踩这个坑。
                val (vw, vh) = viewport ?: (DEFAULT_VIEWPORT_W to DEFAULT_VIEWPORT_H)
                applyViewport(b, vw, vh)
            }
        })

        // 先灌 cookie 再建浏览器：CEF 的 cookie store 是进程级全局的，导航前写入，
        // 首屏请求才会带上登录态。少了这一步，即使 appPrefs 存了登录态，内嵌浏览器
        // 自己也仍是未登录（原生 HTTP 层和浏览器是两套会话，用户就得反复重新登录）。
        if (initialCookies.isNotEmpty()) importCookies(initialUrl, initialCookies)

        browser = client.createBrowser(initialUrl, true, false)
        browser.createImmediately()
        browser.renderHandler.setOnPaintListener { event ->
            if (!event.popup) {
                // CEF 线程直接写 snapshot state，Compose 侧安全接收（无需切主线程）
                _frame.value = OsrFrameConverter.convert(event.renderedFrame, event.width, event.height)
            }
        }
        // 帧率必须显式给：CEF 的 windowless 帧定时器是 rAF 的唯一驱动，
        // 帧率为 0（或从不产帧）时 **requestAnimationFrame 永远不触发**，
        // 依赖客户端渲染的站点（G社章节页）就会永远停在转圈占位。
        runCatching { browser.setWindowlessFrameRate(30) }
        if (forceFrames) startFramePump()
        glCanvas = browser.uiComponent?.let { GlCanvasHost(it, 1280, 720) }
    }

    fun frame(): java.awt.image.BufferedImage? = _frame.value

    /**
     * 导航到 [url]。创建未完成时先挂起，onAfterCreated 后补发
     * （N_CreateBrowser 异步，此刻 loadURL 是静默 no-op，见类注释约束 1）。
     */
    override fun open(url: String) {
        if (created.get()) browser.loadURL(url) else pendingUrl = url
    }

    fun goBack() = browser.goBack()

    fun goForward() = browser.goForward()

    fun reload() = browser.reload()

    /**
     * 同步 OSR 视口尺寸（Compose 画布像素；CEF 按设备像素渲染，hidpi 下天然清晰）。
     *
     * 视口**只有一个写者**：[OsrInputBridge.setViewRect] 直写 `browser_rect_` 并
     * wasResized + invalidate。刻意不再让 [GlCanvasHost] 跟着改尺寸——两个写者会互相
     * 覆盖，且 canvas 每次 resize 都要在 EDT 上抢 GLContext，而 CEF 线程的 paint 也
     * 在抢，抢输的帧被 `CefBrowserOsr.onPaint` 静默丢弃（详见 [GlCanvasHost] 注释）。
     */
    private var framePump: java.util.concurrent.ScheduledExecutorService? = null
    private var invalidateMethod: java.lang.reflect.Method? = null

    /**
     * 每 33ms 逼一帧。`invalidate()` 会让 CEF 重新合成 → 帧定时器被重新唤醒 →
     * rAF 恢复回调。代价是隐藏探针持续占一点 CPU，但取图只在需要时跑，可接受。
     */
    private fun startFramePump() {
        if (framePump != null) return
        val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cef-frame-pump").apply { isDaemon = true }
        }
        framePump = exec
        exec.scheduleWithFixedDelay({
            runCatching { forceRepaint() }
        }, 0, 33, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * 暂停帧泵。
     *
     * 2026-10-24：隐藏浏览器会话复用（45 秒）后，帧泵仍在每 33ms 泵一次——
     * 空闲期白白占 CPU，机器慢的时候能感觉到整体响应变钝。复用回收时调这个。
     */
    fun pauseFramePump() {
        runCatching { framePump?.shutdownNow() }
        framePump = null
    }

    /** 恢复帧泵（下次复用前调；[forceFrames] 为 false 的 host 不受影响）。 */
    fun resumeFramePump() {
        if (forceFrames) startFramePump()
    }

    /**
     * `CefBrowser_N.invalidate()` 是 protected，只能反射调（和 [applyViewport] 同一个套路）。
     *
     * **Method 缓存一次**：旧实现每 33ms 都 `getDeclaredMethod` + `setAccessible`，
     * 30 次/秒的反射查找纯属浪费（每秒几十微秒 × 常驻 host）。
     */
    private fun forceRepaint() {
        val m = invalidateMethod ?: browser.javaClass.getDeclaredMethod("invalidate").also {
            it.isAccessible = true
            invalidateMethod = it
        }
        m.invoke(browser)
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewport = width to height
        if (created.get()) applyViewport(browser, width, height)
    }

    /**
     * 下发视口并确保真的画出新尺寸的帧。
     *
     * 一次 wasResized 不保证有帧回来（paint 仍可能与 EDT 上 CEF 自己的
     * `invokeLater(repaint)` 抢 GLContext 而丢掉一帧），所以帧尺寸没对上就在
     * 100ms 后补一次 invalidate，最多 [RETRY_TIMES] 次。只在 resize 后短暂运行，
     * 不是常驻轮询。
     */
    private fun applyViewport(b: CefBrowser, w: Int, h: Int, attempt: Int = 0) {
        if (!OsrInputBridge.setViewRect(b, w, h)) {
            glCanvas?.resize(w, h) // 反射失效的退路
            return
        }
        if (attempt >= RETRY_TIMES || resizeRetry.isShutdown) return
        resizeRetry.schedule({
            if (viewport != (w to h)) return@schedule // 拖拽连发时丢弃过期目标
            val f = _frame.value
            if (f == null || abs(f.width - w) > 2 || abs(f.height - h) > 2) {
                applyViewport(b, w, h, attempt + 1)
            }
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun currentUrl(): String = browser.getURL() ?: ""

    /**
     * 注入「把所有 `a[target=_blank]` 改成 `_self`」的脚本（可重复调用）。
     *
     * 注入点有三处（onLoadStart / onLoadEnd / loading 变 false），脚本本身幂等：
     * `fix()` 每次都跑，MutationObserver 只装一次。
     */
    private fun injectNoBlank(b: CefBrowser?) {
        runCatching { b?.executeJavaScript(NO_BLANK_TARGET_JS, "", 0) }
    }

    /**
     * 同步 CEF 焦点。UI 层在 OSR 画布获得焦点/被点击时调 true，失去焦点调 false；
     * 不调用时 Chromium 丢弃键盘事件（OSR 无自然焦点来源，见类注释第 3 条）。
     */
    fun setFocus(focused: Boolean) {
        cefFocus.set(focused)
        browser.setFocus(focused)
    }

    /**
     * 导出 [domain] 下全部 cookie（含 HttpOnly）。无 cookie 时最多阻塞 3 秒返回空表。
     */
    override fun exportCookies(domain: String, timeoutMs: Long): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val latch = CountDownLatch(1)
        val visitor = CefCookieVisitor { cookie, count, total, _ ->
            result[cookie.name] = cookie.value
            if (total <= 0 || count >= total) latch.countDown()
            true
        }
        val started = CefCookieManager.getGlobalManager()
            .visitUrlCookies("https://$domain", true, visitor)
        if (!started) return emptyMap()
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        // 读完之后立刻落盘：不 flush 的话新登录的 cookie 只在内存，应用被直接杀掉
        // （没走 ✕ 关闭流程）就丢，下次开浏览器又得重新登录。
        runCatching { CefCookieManager.getGlobalManager().flushStore { } }
        return result
    }

    /**
     * 把上次保存的 cookie 写进 CEF 的 cookie store（建浏览器前调用）。
     *
     * appPrefs 里的登录态原先只喂给原生 HTTP 适配器（[app.gamanga.desktop.source.Sources]
     * 的 cookie provider），内嵌浏览器自己永远是空会话——这就是"每次都要重新登录"的
     * 另一半原因（另一半是 CefSettings 没配 cache_path，见 CefRuntime）。
     * 过期时间给到一年：session cookie 由 persist_session_cookies 管，这两种都盖上。
     */
    private fun importCookies(url: String, cookies: Map<String, String>) {
        val domain = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        val manager = CefCookieManager.getGlobalManager()
        // **只补缺失的，不覆盖已存在的**（R-126，2026-10-26 用户实测「浏览器里登录过，
        // 关掉再打开又要重新登录」）。
        //
        // 旧实现是无条件 setCookie，于是「appPrefs 里的旧会话」会**盖掉**「浏览器里
        // 刚登录产生的新会话」：关闭浏览器那一刻的导出只有 800ms 预算，一旦超时或
        // 失败，appPrefs 留下的就是旧值；下次打开浏览器 → 把新 cookie 冲成旧的 →
        // 站点认为会话无效 → 又要登录。这条链路每次都能复现"登录留不住"。
        //
        // 读一次现有 cookie 名字（500ms 预算）就够了：只有 CEF 自己的 store 里
        // 完全没有该名字时，才用 appPrefs 的值兜底（换机/重装/清缓存后的首次恢复）。
        val existing = runCatching { exportCookies(domain, 500).keys }.getOrDefault(emptySet())
        val now = java.util.Date()
        val expires = java.util.Date(now.time + 365L * 24 * 60 * 60 * 1000)
        cookies.forEach { (name, value) ->
            if (name in existing) return@forEach
            runCatching {
                manager.setCookie(
                    "https://$domain",
                    CefCookie(name, value, domain, "/", false, false, now, now, true, expires)
                )
            }
        }
        runCatching { manager.flushStore { } }
    }

    override fun close() {
        resizeRetry.shutdownNow()
        runCatching { framePump?.shutdownNow() }
        runCatching { browser.close(true) }
        runCatching { client.dispose() }
        glCanvas?.dispose()
    }

    // ---- 输入转发 ----

    fun sendMouseEvent(e: MouseEvent) = OsrInputBridge.sendMouseEvent(browser, e)

    fun sendKeyEvent(e: KeyEvent) = OsrInputBridge.sendKeyEvent(browser, e)

    fun sendWheelEvent(e: MouseWheelEvent) = OsrInputBridge.sendMouseWheelEvent(browser, e)

    /**
     * 在页面里执行 JS（M4 借浏览器解密的通道）。
     * JCEF 的 executeJavaScript 不返回结果，取结果靠页面改 `document.title`
     * ——见 [app.gamanga.desktop.source.BrowserProbe]。
     */
    fun executeJavaScript(js: String) = browser.executeJavaScript(js, "", 0)

    private companion object {
        /** 视口下发后等新帧的最长重试次数（10 × 100ms = 1s 内收敛）。 */
        const val RETRY_TIMES = 10
        const val RETRY_DELAY_MS = 100L

        /**
         * 把页面里所有 `target="_blank"` 改成 `_self`，并持续观察新增节点。
         * 见 LoadHandler.onLoadEnd 的注释（MangaBZ 每话按钮点不动的根因）。
         */
        val NO_BLANK_TARGET_JS = """
            (function(){
              function fix(){
                var as = document.querySelectorAll('a[target="_blank"]');
                for (var i = 0; i < as.length; i++) as[i].target = '_self';
              }
              try { fix(); } catch (e) {}
              if (window.__gaNoBlankObs) return;
              try {
                new MutationObserver(fix).observe(document.documentElement || document,
                  {childList: true, subtree: true});
                window.__gaNoBlankObs = true;
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
