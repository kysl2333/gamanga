package app.gamanga.desktop.core.webview

import app.gamanga.desktop.source.GodaSource
import app.gamanga.desktop.source.Http
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内嵌浏览器集成回归（M2 输入/分辨率 + M4 借浏览器解密）。
 *
 * **输入链路**（踩过：首次打开的内嵌浏览器页面无法点击/输入文字，关闭后再打开才恢复）：
 * 用 data URI 页内按钮 / 文档级 keydown 的 title 变化做探针，依次创建两个浏览器实例
 * （首个 = 冷启动路径，最易暴露创建竞态）。
 *
 * **渲染分辨率**（踩过：窗口最大化后网页发糊）：判据是 paint 帧像素尺寸 == 视口像素尺寸。
 * 帧比视口小就意味着 Compose 在放大位图，必然糊。
 *
 * **借浏览器解密**（M4）：GoDa 的章节图片列表是前端解密下发的，靠隐藏浏览器跑站点的
 * 解码器再读 DOM——这条链路只能在这里验。
 *
 * 注意：CefApp 是进程级单例，dispose 后无法再初始化，故整类共享一次生命周期，
 * 在 [disposeCef] 统一收尾。**新增需要 CEF 的测试请并入本类**，不要另开测试类。
 */
@OptIn(kotlin.test.ExperimentalKotlinTestApi::class)
class WebViewIntegrationTest {

    @Test
    fun `mouse and keyboard input reach the page in first and second browser`() {
        CefRuntime.ensureStarted()
        repeat(2) { round ->
            val tag = "browser#$round"
            // 轮 0 走 about:blank + open（应用实际流程，含创建未完成时的挂起补发路径）；
            // 轮 1 用 data URI 直接做初始 URL，覆盖 createBrowser 首屏导航路径
            val host = if (round == 0) {
                JcefWebViewHost().also { it.open(PAGE_URI) }
            } else {
                JcefWebViewHost(PAGE_URI)
            }

            assertTrue(host.awaitTitle("READY", 30_000) == "READY") {
                val s = host.uiState.value
                "$tag: page did not load (title='${s.title}' url='${s.url}' loading=${s.isLoading})"
            }

            // 等首帧 paint：OSR 视口由 GL reshape 建立，没有帧说明视口还没建好，
            // 此时点击会落在视口外被 CEF 丢弃
            assertTrue(host.awaitFrame(30_000)) { "$tag: no paint frame (view rect not ready)" }

            // ---- 鼠标：按钮在 (100,100)-(500,400)，点中心 ----
            host.clickAt(300, 250)
            assertEquals("CLICKED", host.awaitTitle("CLICKED", 5_000)) {
                "$tag: mouse click did not reach page"
            }

            // ---- 键盘：文档级 keydown 探针（合成事件的 e.key 可能为 Unidentified，
            //      只验证「按键到达页面」本身） ----
            host.typeKey(KeyEvent.VK_A, 'a')
            assertTrue(host.awaitTitlePrefix("KEY:", 5_000)) {
                "$tag: key event did not reach page (title=${host.uiState.value.title})"
            }

            host.close()
        }
    }

    @Test
    fun `render resolution follows viewport after resize`() {
        CefRuntime.ensureStarted()
        val host = JcefWebViewHost(PAGE_URI)
        assertTrue(host.awaitTitle("READY", 30_000) == "READY") { "page did not load" }
        assertTrue(host.awaitFrame(30_000)) { "no first paint frame" }

        // 逐级放大到 4K 级：每次 resize 后帧尺寸都必须跟上，否则就是被拉伸放大（糊）
        for ((w, h) in listOf(640 to 480, 1600 to 900, 2400 to 1400)) {
            host.resize(w, h)
            assertTrue(host.awaitFrameSize(w, h, 8_000)) {
                val f = host.frame()
                "resize ${w}x$h: frame stuck at ${f?.width}x${f?.height}（视口未跟随）"
            }
        }
        host.close()
    }

    /**
     * R-01 回归：按键必须真的能把文字打进输入框。
     *
     * 上一版只验了 `keydown` 探针——按键"到达页面"就算过，结果**测试全绿、功能全废**：
     * Chromium 靠 `KEY_TYPED` 插入字符，而 Compose 只派发 KeyDown/KeyUp，
     * 不补发 `KEY_TYPED` 的话页面能滚能点、输入框一个字都打不进去。
     * 这里直接断言输入框的 value。
     */
    @Test
    fun `typing reaches the page input`() {
        CefRuntime.ensureStarted()
        val host = JcefWebViewHost(PAGE_URI)
        assertTrue(host.awaitTitle("READY", 30_000) == "READY") { "page did not load" }
        assertTrue(host.awaitFrame(30_000)) { "no first paint frame" }

        host.clickAt(300, 530) // 输入框在 (100,500)-(500,560)
        host.typeKey(KeyEvent.VK_A, 'a')
        host.typeKey(KeyEvent.VK_B, 'b')
        assertTrue(host.awaitTitlePrefix("VAL:ab", 5_000)) {
            "文字没进输入框（title=${host.uiState.value.title}）——KEY_TYPED 没送到"
        }
        host.close()
    }

    /**
     * 滚轮与拖拽必须真的到页面（用户报「滚轮滑不动」「滚动条滑块拖不动」）。
     *
     * 拖拽的关键是 `modifiersEx` 里要带**按下的按钮位**——Chromium 靠它区分「拖动」和
     * 「移动」，缺了就只有 mousemove 没有 drag，滑块自然不动。
     */
    @Test
    fun `wheel scrolls and drag reaches the page`() {
        CefRuntime.ensureStarted()
        val host = JcefWebViewHost(SCROLL_URI)
        assertTrue(host.awaitTitle("READY", 30_000) == "READY") { "page did not load" }
        assertTrue(host.awaitFrame(30_000)) { "no first paint frame" }

        // ---- 滚轮：先把指针移进页面，再滚一格（rotation=-120 ≈ 一格滚轮的像素量）----
        host.moveTo(300, 250)
        host.wheelRaw(MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -120)
        assertTrue(host.awaitTitlePrefix("SCROLL:", 3_000)) {
            "滚轮事件没到页面（title=${host.uiState.value.title}）"
        }
        val scrolled = host.uiState.value.title.removePrefix("SCROLL:").toIntOrNull() ?: 0
        // 之前每格只挪 3px（且方向反了），体感就是"滚不动"；一格至少要滚几十像素
        assertTrue(scrolled > 40) { "一格滚轮只滚了 ${scrolled}px，体感会像「滚不动」" }

        // ---- 拖拽：按住方块从 (150,150) 拖到 (450,150) ----
        host.dragFromTo(150, 150, 450, 150)
        assertTrue(host.awaitTitlePrefix("DRAG:", 5_000)) {
            "拖拽没到页面（title=${host.uiState.value.title}）"
        }
        host.close()
    }


    /**
     * R-25 回归：条漫章节也要能取到图。
     *
     * 用户报「包子漫画该话没有图片」，页面被广告脚本跳到 tsyndicate.com 后
     * 隐藏浏览器里的 DOM 被带走，`#chapcontent img[data-path]` 自然取不到。
     * 用那条确实出问题的条漫（slug 来自用户截图里的地址栏）锁定。
     */
    @Test
    fun `goda manhua chapter images decode`() {
        Assume.assumeTrue("真实源站测试需 -Dgamanga.network=1", System.getProperty("gamanga.network") == "1")
        CefRuntime.ensureStarted()
        val source = GodaSource()
        val slug = "zhuangbeiwozuiqiang-teamargomonohumbugredicestudiosaenalredicestudio-a01"

        val chapters = runBlocking { source.chapters(slug) }
        assertTrue(chapters.isNotEmpty(), "探针章节列表为空：$slug")
        val pages = runBlocking { source.pages(chapters.first().id) }
        assertTrue(pages.size > 3, "条漫章节解出的页数太少（${pages.size}）：${chapters.first().id}")
        assertTrue(pages.all { it.url.startsWith("https://c-nd") }, "图片域名不对：${pages.first().url}")

        // 端到端：解出来的地址必须真的能下载到图片（否则只是拼了个像样的 URL）
        val bytes = runBlocking { Http.getBytes(pages.first().url, mapOf("Referer" to "https://m.g-mh.org/")) }
        // 看格式头而不是体积：条漫首页经常是空白天头，只有几 KB 但确实是图
        val head = bytes.take(12).toByteArray().decodeToString()
        assertTrue(head.startsWith("RIFF") && head.contains("WEBP"), "首页不是 WebP（${bytes.size}B）：$head")
    }

    /**
     * M4：GoDa 的章节图片列表由站点前端解密，我们借隐藏浏览器跑它的解码器再读 DOM。

     * 断言到「能拿到多页 URL 且真能下载」为止。
     */
    @Test
    fun `goda chapter images decode inside the hidden browser`() {
        Assume.assumeTrue("真实源站测试需 -Dgamanga.network=1", System.getProperty("gamanga.network") == "1")
        CefRuntime.ensureStarted()
        val source = GodaSource()

        val slug = runBlocking { source.search("女体化").firstOrNull()?.sourceId }
        kotlin.test.assertNotNull(slug, "探针搜索无结果，未完成浏览器取图验证")
        val chapters = runBlocking { source.chapters(slug) }
        assertTrue(chapters.isNotEmpty(), "章节列表为空：$slug")

        val pages = runBlocking { source.pages(chapters.first().id) }
        assertTrue(pages.size > 3, "解出的页数太少（${pages.size}）：${pages.take(2)}")
        assertTrue(
            pages.all { it.url.startsWith("https://c-nd") },
            "图片域名不对：${pages.first().url}"
        )

        val bytes = runBlocking { Http.getBytes(pages.first().url, mapOf("Referer" to "https://baozimh.org/")) }
        assertTrue(bytes.size > 1024, "图片下载过小：${bytes.size}B")
    }

    // ---- 探针 ----

    private fun JcefWebViewHost.awaitTitle(expected: String, timeoutMs: Long): String? {
        awaitTitlePrefix(expected, timeoutMs, exact = true)
        return uiState.value.title
    }

    private fun JcefWebViewHost.awaitTitlePrefix(
        prefix: String,
        timeoutMs: Long,
        exact: Boolean = false
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = 0L
        while (System.currentTimeMillis() < deadline) {
            val title = uiState.value.title
            if (if (exact) title == prefix else title.startsWith(prefix)) return true
            val now = System.currentTimeMillis()
            if (now - lastLog > 2000) {
                lastLog = now
                val s = uiState.value
                println("[trace] waiting '$prefix': title='${s.title}' url='${s.url}' loading=${s.isLoading}")
            }
            Thread.sleep(50)
        }
        return false
    }

    private fun JcefWebViewHost.awaitFrame(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = frame()
            if (f != null && f.width > 100 && f.height > 100) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun JcefWebViewHost.awaitFrameSize(w: Int, h: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = frame()
            // GL 视口可能取偶数对齐，允许 2px 误差
            if (f != null && kotlin.math.abs(f.width - w) <= 2 && kotlin.math.abs(f.height - h) <= 2) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun JcefWebViewHost.clickAt(x: Int, y: Int) {
        val source = Canvas()
        sendMouseEvent(
            MouseEvent(source, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)
        )
        sendMouseEvent(
            MouseEvent(source, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)
        )
    }

    private fun JcefWebViewHost.typeKey(keyCode: Int, keyChar: Char) {
        val source = Canvas()
        sendKeyEvent(KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, keyChar))
        sendKeyEvent(KeyEvent(source, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, keyChar))
        sendKeyEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, keyChar))
    }

    private fun JcefWebViewHost.moveTo(x: Int, y: Int) {
        sendMouseEvent(
            MouseEvent(Canvas(), MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.NOBUTTON)
        )
    }

    private fun JcefWebViewHost.wheel(rotation: Int) = wheelRaw(MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation)

    private fun JcefWebViewHost.wheelRaw(scrollType: Int, amount: Int, rotation: Int) {
        sendWheelEvent(
            MouseWheelEvent(
                Canvas(), MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 300, 250, 0, false,
                scrollType, amount, rotation
            )
        )
    }

    /** 按住左键从 (x1,y1) 拖到 (x2,y2)：中间必须带按钮位，否则 Chromium 只当移动。 */
    private fun JcefWebViewHost.dragFromTo(x1: Int, y1: Int, x2: Int, y2: Int) {
        val src = Canvas()
        val down = InputEvent.BUTTON1_DOWN_MASK
        sendMouseEvent(MouseEvent(src, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), down, x1, y1, 1, false, MouseEvent.BUTTON1))
        for (step in 1..6) {
            val x = x1 + (x2 - x1) * step / 6
            val y = y1 + (y2 - y1) * step / 6
            sendMouseEvent(MouseEvent(src, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), down, x, y, 1, false, MouseEvent.BUTTON1))
            Thread.sleep(30)
        }
        sendMouseEvent(MouseEvent(src, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x2, y2, 1, false, MouseEvent.BUTTON1))
    }

    private companion object {
        /** 整类跑完后收尾（CefApp 单例 dispose 后不可重新初始化，只能一次）。 */
        @JvmStatic
        @AfterClass
        fun disposeCef() {
            runCatching { CefRuntime.dispose() }
        }

        /** data URI 探针页：大按钮 + 文档级 keydown 探针，事件命中即改 title。 */
        val PAGE_URI: String = run {
            val html = """
                <html><head><title>READY</title></head><body style="margin:0">
                <button id="b" style="position:absolute;left:100px;top:100px;width:400px;height:300px"
                        onclick="document.title='CLICKED'">BTN</button>
                <input id="i" style="position:absolute;left:100px;top:500px;width:400px;height:60px"
                       oninput="document.title='VAL:'+this.value">
                <script>
                  // keydown 探针在 oninput 之前触发，所以输入框的 VAL: 会覆盖 KEY:
                  document.addEventListener('keydown', function(e) { document.title = 'KEY:' + e.key; });
                </script>
                </body></html>
            """.trimIndent()
            // URLEncoder 把空格编码成 '+'，而 data URI 不解 '+'为空格——必须换成 %20
            "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20")
        }

        /** 长页面探针：滚轮改 title 为 `SCROLL:<scrollY>`，按住拖动改 `DRAG:<clientX>`。 */
        val SCROLL_URI: String = run {
            val html = """
                <html><head><title>READY</title></head><body style="margin:0;height:3000px">
                <div id="d" style="position:absolute;left:50px;top:50px;width:200px;height:200px;background:#ccc"></div>
                <script>
                  // 两级探针：WHEEL 说明事件到了页面，SCROLL 说明真的滚起来了
                  document.addEventListener('wheel', function(e) { document.title = 'WHEEL:' + Math.round(e.deltaY); });
                  window.addEventListener('scroll', function() { document.title = 'SCROLL:' + Math.round(window.scrollY); });
                  document.addEventListener('mousemove', function(e) {
                    if (e.buttons) document.title = 'DRAG:' + Math.round(e.clientX);
                  });
                </script>
                </body></html>
            """.trimIndent()
            "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20")
        }
    }
}
