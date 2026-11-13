package app.gamanga.desktop.ui

import app.gamanga.desktop.source.Http
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ImageCacheLoadingTest {
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")

    @Test
    fun `image body must respect timeout after headers arrive`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val release = CountDownLatch(1)
        server.createContext("/stalled") { e ->
            runCatching {
                e.sendResponseHeaders(200, png.size.toLong())
                e.responseBody.write(png, 0, 1)
                e.responseBody.flush()
                release.await(3, TimeUnit.SECONDS)
                e.responseBody.write(png, 1, png.size - 1)
            }
            e.close()
        }
        server.start()
        try {
            val started = System.nanoTime()
            val result = runCatching { Http.getImageBytes("http://127.0.0.1:${server.address.port}/stalled", emptyMap(), timeoutSeconds = 1) }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("stalled image body: elapsed=${elapsed}ms failed=${result.isFailure}")
            assertTrue(result.isFailure, "响应头到达后图片正文卡住也必须超时，不能一直转圈")
            assertTrue(elapsed < 2300, "完整传输应受1秒超时约束，实际${elapsed}ms")
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test
    fun `cancelled first waiter keeps live shared request and foreground priority`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server.createContext("/shared") { e ->
            requests.incrementAndGet(); entered.countDown()
            release.await(4, TimeUnit.SECONDS)
            runCatching { e.sendResponseHeaders(200, png.size.toLong()); e.responseBody.use { it.write(png) } }
            e.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/shared"
            val first = async(Dispatchers.IO) { ImageCache.load(url) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val visible = async(start = CoroutineStart.UNDISPATCHED) { ImageCache.load(url, foreground = true) }
            val foreground = ImageCache.isForegroundBusy()
            first.cancelAndJoin()
            val next = async(start = CoroutineStart.UNDISPATCHED) { ImageCache.load(url) }
            release.countDown()
            assertNotNull(visible.await())
            assertNotNull(next.await())
            assertEquals(1, requests.get(), "首个等待者取消后，其余等待者仍应共用原请求")
            assertTrue(foreground, "可见页等待预加载时也必须标记前台忙碌")
            assertFalse(ImageCache.isForegroundBusy())
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test
    fun `failed images do not retry automatically and explicit retry is isolated`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val recoveries = AtomicInteger()
        val denied = AtomicInteger()
        server.createContext("/recover") { e ->
            if (recoveries.incrementAndGet() == 1) e.sendResponseHeaders(503, -1)
            else { e.sendResponseHeaders(200, png.size.toLong()); e.responseBody.use { it.write(png) } }
            e.close()
        }
        server.createContext("/denied") { e -> denied.incrementAndGet(); e.sendResponseHeaders(403, -1); e.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val recoverUrl = "$base/recover"
            assertNull(ImageCache.load(recoverUrl, foreground = true))
            assertEquals(1, recoveries.get(), "网络失败不自动重试")
            assertFalse(ImageCache.canPrefetch(recoverUrl, emptyMap()))
            assertNull(ImageCache.load(recoverUrl, foreground = true))
            assertEquals(1, recoveries.get(), "可见页重建不应隐式重试")
            assertNotNull(ImageCache.load(recoverUrl, foreground = true, retryFailed = true))
            assertEquals(2, recoveries.get(), "仅明确单图重试发出第二次请求")
            val url = "$base/denied?token=must-not-appear"
            assertNull(ImageCache.load(url, foreground = true))
            assertEquals(1, denied.get(), "403不自动重试")
            assertFalse(ImageCache.canPrefetch(url, emptyMap()))
            assertNull(ImageCache.load(url))
            assertEquals(1, denied.get(), "预加载不应反复攻击失败图片")
            assertNull(ImageCache.load(url, foreground = true, retryFailed = true))
            assertEquals(2, denied.get(), "用户明确点击可再次加载")
        } finally { server.stop(0) }
    }

    @Test
    fun `last waiter cancellation stops transfer and allows a fresh request`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val requests = AtomicInteger()
        server.createContext("/cancel") { e ->
            val number = requests.incrementAndGet()
            if (number == 1) { entered.countDown(); release.await(4, TimeUnit.SECONDS) }
            runCatching { e.sendResponseHeaders(200, png.size.toLong()); e.responseBody.use { it.write(png) } }
            e.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/cancel"
            val first = async(Dispatchers.IO) { ImageCache.load(url, foreground = true) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(1000) { first.cancelAndJoin() }
            assertFalse(ImageCache.isForegroundBusy())
            assertNotNull(withTimeout(2000) { ImageCache.load(url, foreground = true) })
            assertEquals(2, requests.get())
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test
    fun `different images download concurrently while same image is shared`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val arrived = CountDownLatch(3)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server.createContext("/image") { e ->
            requests.incrementAndGet(); arrived.countDown()
            release.await(4, TimeUnit.SECONDS)
            runCatching { e.sendResponseHeaders(200, png.size.toLong()); e.responseBody.use { it.write(png) } }
            e.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/image"
            val jobs = (0..2).flatMap { i -> (0..1).map { async(Dispatchers.IO) { ImageCache.load("$base/$i") } } }
            val parallel = arrived.await(3, TimeUnit.SECONDS)
            release.countDown()
            val images = jobs.awaitAll()
            assertTrue(parallel, "至少3张不同图片应同时发出下载")
            assertEquals(3, requests.get(), "同图同时请求应只下载一次")
            assertTrue(images.all { it != null }, "并发下载后图片应成功解码")
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
