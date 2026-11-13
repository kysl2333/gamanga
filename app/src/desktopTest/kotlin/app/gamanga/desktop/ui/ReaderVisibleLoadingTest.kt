package app.gamanga.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.model.ReaderMode
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.ui.reader.ReaderPrefetch
import app.gamanga.desktop.ui.reader.PageSpread
import app.gamanga.desktop.ui.reader.readerPageVisible
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** 产品组件 + 本地HTTP；程序化状态/语义调用，不操作用户鼠标键盘或访问真实源站。 */
class ReaderVisibleLoadingTest {
    @get:Rule val rule = createComposeRule()
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")

    private class Fixture(val server: HttpServer, val executor: java.util.concurrent.ExecutorService) : AutoCloseable {
        val base get() = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Fixture {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val e = Executors.newCachedThreadPool()
        s.executor = e
        s.createContext("/") { x -> try { handler(x) } finally { x.close() } }
        s.start()
        return Fixture(s, e)
    }
    private fun reply(x: com.sun.net.httpserver.HttpExchange) {
        x.sendResponseHeaders(200, png.size.toLong())
        x.responseBody.use { it.write(png) }
    }

    @Test fun `offscreen composition makes no image request`() {
        val forbidden = CountDownLatch(1)
        val control = CountDownLatch(1)
        server { x ->
            if (x.requestURI.path == "/offscreen") forbidden.countDown() else control.countDown()
            reply(x)
        }.use { f ->
            rule.setContent {
                Box {
                    RemoteImage("${f.base}/offscreen", null, Modifier.size(50.dp), loadEnabled = false)
                    RemoteImage("${f.base}/control", null, Modifier.size(50.dp))
                }
            }
            assertTrue(control.await(3, TimeUnit.SECONDS), "正控制必须真实到达HTTP服务")
            assertFalse(forbidden.await(500, TimeUnit.MILLISECONDS), "离屏预组合不能发请求")
        }
    }

    @Test fun `rapid page changes request only settled destination`() {
        val requested = ConcurrentHashMap.newKeySet<String>()
        server { x -> requested.add(x.requestURI.path); reply(x) }.use { f ->
            val page = mutableStateOf(0)
            rule.setContent {
                RemoteImage("${f.base}/page/${page.value}", null, Modifier.size(50.dp), loadDelayMillis = 400)
            }
            rule.runOnIdle { page.value = 20 }
            rule.runOnIdle { page.value = 59 }
            rule.waitUntil(3000) { ImageCache.has("${f.base}/page/59") }
            assertEquals(setOf("/page/59"), requested, "停留前滑过的页不能先请求再取消")
        }
    }

    @Test fun `disposing reader prefetch cancels all last waiters without filling cache`() {
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        server { x ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { reply(x) }
        }.use { f ->
            val open = mutableStateOf(true)
            val pages = (0..2).map { PageImage("${f.base}/dispose/$it") }
            try {
                rule.setContent {
                    if (open.value) {
                        ReaderPrefetch(pages, ReaderMode.SINGLE, LazyListState(), visibleOnly = true) { 0 }
                        RemoteImage(pages[0].url, null, Modifier.size(50.dp))
                    }
                }
                rule.waitUntil(3000) { entered.count == 0L }
                rule.runOnIdle { open.value = false }
                rule.waitUntil(2000) { !ImageCache.isForegroundBusy() }
                release.countDown()
                assertFalse(CountDownLatch(1).await(300, TimeUnit.MILLISECONDS))
                assertTrue(pages.none { ImageCache.has(it.url) }, "阅读器组件退出后不能继续填旧章节缓存")
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `copy window downloads current and next two concurrently without old broad prefetch`() {
        val arrived = CountDownLatch(3)
        val release = CountDownLatch(1)
        val requested = ConcurrentHashMap.newKeySet<String>()
        server { x ->
            requested.add(x.requestURI.path)
            arrived.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { reply(x) }
        }.use { f ->
            val compact = mutableStateOf(true)
            val pages = (0..99).map { PageImage("${f.base}/prefetch/$it") }
            try {
                rule.setContent {
                    ReaderPrefetch(pages, ReaderMode.SINGLE, LazyListState(), visibleOnly = compact.value) { 59 }
                }
                rule.waitUntil(3000) { arrived.count == 0L }
                assertEquals(setOf("/prefetch/59", "/prefetch/60", "/prefetch/61"), requested)
                release.countDown()
                rule.waitUntil(3000) { (59..61).all { ImageCache.has(pages[it].url) } }
                rule.runOnIdle { compact.value = false }
                rule.waitUntil(3000) { requested.size > 3 }
            } finally { release.countDown() }
        }
    }

    @Test fun `compact window rapidly follows destination and keeps overlapping downloads`() {
        val requested = ConcurrentHashMap<String, AtomicInteger>()
        val release = CountDownLatch(1)
        server { x ->
            requested.computeIfAbsent(x.requestURI.path) { AtomicInteger() }.incrementAndGet()
            release.await(8, TimeUnit.SECONDS)
            runCatching { reply(x) }
        }.use { f ->
            val page = mutableStateOf(0)
            val pages = (0..99).map { PageImage("${f.base}/compact/$it") }
            val state = LazyListState()
            try {
                rule.setContent {
                    ReaderPrefetch(pages, ReaderMode.SINGLE, state, visibleOnly = true) { page.value }
                    RemoteImage(pages[page.value].url, null, Modifier.size(50.dp), loadDelayMillis = 150)
                }
                rule.runOnIdle { page.value = 20 }
                rule.runOnIdle { page.value = 59 }
                rule.waitUntil(3000) { requested.size == 3 }
                assertEquals(setOf("/compact/59", "/compact/60", "/compact/61"), requested.keys)
                rule.runOnIdle { page.value = 60 }
                rule.waitUntil(3000) { requested.containsKey("/compact/62") }
                assertTrue(requested.values.all { it.get() == 1 }, "窗口重叠页不能取消后重复下载")
                rule.runOnIdle { page.value = 90 }
                rule.waitUntil(3000) { requested.containsKey("/compact/92") }
                release.countDown()
                rule.waitUntil(3000) { (90..92).all { ImageCache.has(pages[it].url) } }
                assertFalse(ImageCache.has(pages[60].url), "最后等待者离开旧窗口后不能继续填缓存")
                rule.runOnIdle { page.value = 60 }
                rule.waitUntil(3000) { (60..62).all { ImageCache.has(pages[it].url) } }
                assertEquals(2, requested["/compact/60"]?.get(), "取消不记失败，回看允许正常重取")
            } finally { release.countDown() }
        }
    }

    @Test fun `compact failed prefetched page is not retried when window returns`() {
        val requested = ConcurrentHashMap<String, AtomicInteger>()
        server { x ->
            requested.computeIfAbsent(x.requestURI.path) { AtomicInteger() }.incrementAndGet()
            x.sendResponseHeaders(403, -1)
        }.use { f ->
            val page = mutableStateOf(0)
            val pages = (0..9).map { PageImage("${f.base}/failed-window/$it") }
            rule.setContent {
                ReaderPrefetch(pages, ReaderMode.SINGLE, LazyListState(), visibleOnly = true) { page.value }
            }
            rule.waitUntil(3000) { requested.size == 3 && (0..2).all { !ImageCache.canPrefetch(pages[it].url, emptyMap()) } }
            rule.runOnIdle { page.value = 6 }
            rule.waitUntil(3000) { requested.size == 6 }
            rule.runOnIdle { page.value = 0 }
            rule.waitForIdle()
            assertFalse(CountDownLatch(1).await(500, TimeUnit.MILLISECONDS))
            assertTrue(requested.values.all { it.get() == 1 })
        }
    }

    @Test fun `compact vertical viewport prewarms only destination window`() {
        val requested = ConcurrentHashMap.newKeySet<String>()
        server { x -> requested.add(x.requestURI.path); reply(x) }.use { f ->
            val pages = (0..99).map { PageImage("${f.base}/compact-vertical/$it") }
            val state = LazyListState()
            lateinit var scope: CoroutineScope
            rule.setContent {
                scope = rememberCoroutineScope()
                ReaderPrefetch(pages, ReaderMode.VERTICAL, state, visibleOnly = true) { 0 }
                LazyColumn(state = state, modifier = Modifier.size(100.dp)) {
                    itemsIndexed(pages, key = { i, p -> "$i:${p.url}" }) { _, _ -> Box(Modifier.size(100.dp)) }
                }
            }
            rule.runOnIdle { scope.launch { state.scrollToItem(59) } }
            rule.waitUntil(3000) { (59..61).all { ImageCache.has(pages[it].url) } }
            assertEquals(setOf("/compact-vertical/59", "/compact-vertical/60", "/compact-vertical/61"), requested)
        }
    }

    @Test fun `compact double mode requests only three pages and end does not wrap`() {
        val requested = ConcurrentHashMap.newKeySet<String>()
        server { x -> requested.add(x.requestURI.path); reply(x) }.use { f ->
            val page = mutableStateOf(4)
            val pages = (0..9).map { PageImage("${f.base}/compact-double/$it") }
            rule.setContent {
                ReaderPrefetch(pages, ReaderMode.DOUBLE, LazyListState(), visibleOnly = true) { page.value }
            }
            rule.waitUntil(3000) { (4..6).all { ImageCache.has(pages[it].url) } }
            assertEquals(setOf("/compact-double/4", "/compact-double/5", "/compact-double/6"), requested)
            rule.runOnIdle { page.value = 9 }
            rule.waitUntil(3000) { ImageCache.has(pages[9].url) }
            assertEquals(setOf("/compact-double/4", "/compact-double/5", "/compact-double/6", "/compact-double/9"), requested)
        }
        assertEquals(listOf(2, 3, 4, 5), app.gamanga.desktop.ui.reader.compactReaderWindow(listOf(2, 3, 4, 5), 10))
        assertEquals(emptyList(), app.gamanga.desktop.ui.reader.compactReaderWindow(emptyList(), 10))
    }

    @Test fun `real viewport jump loads destination and excludes offscreen composition`() {
        val requested = ConcurrentHashMap.newKeySet<String>()
        server { x -> requested.add(x.requestURI.path); reply(x) }.use { f ->
            val pages = (0..99).map { PageImage("${f.base}/viewport/$it") }
            val state = LazyListState()
            lateinit var scope: CoroutineScope
            rule.setContent {
                scope = rememberCoroutineScope()
                Box(Modifier.size(100.dp)) {
                    LazyColumn(state = state, modifier = Modifier.size(100.dp)) {
                        itemsIndexed(pages, key = { i, p -> "$i:${p.url}" }) { i, p ->
                            RemoteImage(p.url, null, Modifier.size(100.dp),
                                loadEnabled = readerPageVisible(state, i, p.url), loadDelayMillis = 400)
                        }
                    }
                    // 明确模拟Lazy预组合项；组件存在，但它不在真实视口中。
                    RemoteImage(pages[80].url, null, Modifier.size(1.dp),
                        loadEnabled = readerPageVisible(state, 80, pages[80].url), loadDelayMillis = 400)
                }
            }
            rule.runOnIdle { scope.launch { state.scrollToItem(20) } }
            rule.waitUntil(2000) { state.firstVisibleItemIndex == 20 }
            rule.runOnIdle { scope.launch { state.scrollToItem(59) } }
            rule.waitUntil(3000) { ImageCache.has(pages[59].url) }
            assertEquals(setOf("/viewport/59"), requested)
        }
    }

    @Test fun `viewport key rejects a previous chapter layout`() {
        val requests = AtomicInteger()
        server { x -> requests.incrementAndGet(); reply(x) }.use { f ->
            val state = LazyListState()
            val allowed = mutableStateOf(true)
            rule.setContent {
                LazyColumn(state = state, modifier = Modifier.size(100.dp)) {
                    item(key = "0:old-chapter") { Box(Modifier.size(100.dp)) }
                }
                allowed.value = readerPageVisible(state, 0, "${f.base}/new-chapter")
                RemoteImage("${f.base}/new-chapter", null, Modifier.size(1.dp), loadEnabled = allowed.value)
            }
            rule.waitForIdle()
            assertFalse(allowed.value)
            assertFalse(CountDownLatch(1).await(400, TimeUnit.MILLISECONDS))
            assertEquals(0, requests.get())
        }
    }

    @Test fun `two visible page spread images still download concurrently`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        server { x ->
            entered.countDown()
            release.await(4, TimeUnit.SECONDS)
            runCatching { reply(x) }
        }.use { f ->
            try {
                rule.setContent {
                    Box(Modifier.size(200.dp)) {
                        PageSpread(listOf(PageImage("${f.base}/one"), PageImage("${f.base}/two")),
                            0, 2, false, 1f, {}, {}, {}, loadDelayMillis = 100)
                    }
                }
                rule.waitUntil(3000) { entered.count == 0L }
                release.countDown()
                rule.waitUntil(3000) { ImageCache.has("${f.base}/one") && ImageCache.has("${f.base}/two") }
            } finally { release.countDown() }
        }
    }

    @Test fun `leaving viewport cancels last waiter and reentry can load`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server { x ->
            if (requests.incrementAndGet() == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            runCatching { reply(x) }
        }.use { f ->
            val enabled = mutableStateOf(true)
            val url = "${f.base}/cancel"
            try {
                rule.setContent { RemoteImage(url, null, Modifier.size(50.dp), loadEnabled = enabled.value) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                rule.runOnIdle { enabled.value = false }
                rule.waitUntil(2000) { !ImageCache.isForegroundBusy() }
                rule.runOnIdle { enabled.value = true }
                rule.waitUntil(3000) { ImageCache.has(url) }
                assertEquals(2, requests.get(), "取消不应被记录成失败，重新可见允许加载")
            } finally { release.countDown() }
        }
    }

    @Test fun `one offscreen waiter does not cancel another visible waiter`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server { x -> requests.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); runCatching { reply(x) } }.use { f ->
            val enabled = mutableStateOf(true)
            val url = "${f.base}/shared"
            try {
                rule.setContent {
                    Box {
                        RemoteImage(url, null, Modifier.size(50.dp), loadEnabled = enabled.value)
                        RemoteImage(url, null, Modifier.size(50.dp))
                    }
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                rule.runOnIdle { enabled.value = false }
                rule.waitForIdle()
                assertTrue(ImageCache.isForegroundBusy())
                release.countDown()
                rule.waitUntil(3000) { ImageCache.has(url) }
                assertEquals(1, requests.get())
            } finally { release.countDown() }
        }
    }

    @Test fun `cached reentry bypasses settling delay and makes no request`() {
        val requests = AtomicInteger()
        server { x -> requests.incrementAndGet(); reply(x) }.use { f ->
            val url = "${f.base}/cached"
            val enabled = mutableStateOf(false)
            val ratioKnown = CountDownLatch(1)
            rule.setContent {
                RemoteImage(url, null, Modifier.size(50.dp), loadEnabled = enabled.value,
                    loadDelayMillis = 5000, dynamicRatio = true, onRatioKnown = { ratioKnown.countDown() })
            }
            assertNotNull(runBlocking { ImageCache.load(url) })
            rule.runOnIdle { enabled.value = true }
            rule.waitUntil(1500) { ratioKnown.count == 0L }
            assertEquals(1, requests.get())
        }
    }

    @Test fun `failed image reentry never reuses consumed explicit retry`() {
        val requests = AtomicInteger()
        server { x -> requests.incrementAndGet(); x.sendResponseHeaders(403, -1) }.use { f ->
            val enabled = mutableStateOf(true)
            rule.setContent { RemoteImage("${f.base}/denied", null, Modifier.size(200.dp), loadEnabled = enabled.value) }
            rule.waitUntil(3000) { requests.get() == 1 && !ImageCache.isForegroundBusy() }
            rule.waitForIdle()
            // 直接调用组件语义回调，不注入系统或测试鼠标/键盘。
            rule.onNodeWithText("加载失败 · 点击重试").performSemanticsAction(SemanticsActions.OnClick) { it() }
            rule.waitUntil(3000) { requests.get() == 2 && !ImageCache.isForegroundBusy() }
            rule.runOnIdle { enabled.value = false }
            rule.runOnIdle { enabled.value = true }
            rule.waitForIdle()
            assertFalse(CountDownLatch(1).await(500, TimeUnit.MILLISECONDS))
            assertEquals(2, requests.get(), "每次点击只授权一次，重入视口不能再次重试403")
        }
    }
}
