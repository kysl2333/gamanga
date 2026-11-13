package app.gamanga.desktop.source

import kotlinx.coroutines.launch
import app.gamanga.desktop.core.AppLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP 代理的参数校验与配置测试（第九轮 #8/#9）。
 *
 * 只测**不发网络请求**的部分：参数校验（空地址/坏端口）、协议归一化、
 * 开关语义、per-source 策略。真实连通性由设置页「测试连接」按钮在用户
 * 环境验证（开发机网络到代理不可达，写不进自动化测试）。
 */
class HttpProxyTest {

    private lateinit var logDir: java.nio.file.Path

    @org.junit.Before
    fun setup() {
        // 日志隔离：configureProxy 会写 AppLog，别污染真实日志位置
        logDir = Files.createTempDirectory("httpproxy-test")
        AppLog.dirOverride = logDir
    }

    @org.junit.After
    fun teardown() {
        Http.configureProxy(false, null, 0, "HTTP")
        Http.proxyPolicy = { true }
        AppLog.dirOverride = null
        logDir.toFile().deleteRecursively()
    }

    @Test
    fun `thumbnail cancellation releases caller while server is stalled`() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, 1); exchange.responseBody.use { it.write(1) } }
            exchange.close()
        }
        server.start()
        try {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                Http.getThumbnailBytes("http://127.0.0.1:${server.address.port}/slow")
            }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancel()
            kotlinx.coroutines.withTimeout(1000) { job.join() }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            println("thumbnail cancellation: ${elapsed}ms while response withheld")
            assertTrue(elapsed < 1000, "取消不应继续等待网络响应")
        } finally { release.countDown(); server.stop(0) }
    }

    @Test
    fun `native get and post cancellation release stalled requests`() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = executor
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.LinkedBlockingQueue<String>()
        server.createContext("/slow") { exchange ->
            entered.offer(exchange.requestMethod)
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, 1); exchange.responseBody.use { it.write(1) } }
            exchange.close()
        }
        server.start()
        try {
            for (method in listOf("GET", "POST")) {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val url = "http://127.0.0.1:${server.address.port}/slow"
                    if (method == "GET") Http.getText(url) else Http.postJson(url, "{}")
                }
                assertEquals(method, entered.poll(3, java.util.concurrent.TimeUnit.SECONDS))
                job.cancel()
                kotlinx.coroutines.withTimeout(1000) { job.join() }
                assertTrue(job.isCancelled)
            }
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test
    fun `native post preserves body headers and rejects explicit 210`() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val captured = java.util.concurrent.LinkedBlockingQueue<List<String>>()
        server.createContext("/post") { exchange ->
            captured.offer(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Cookie"), exchange.requestBody.readAllBytes().decodeToString()))
            val bytes = "返回内容".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/deny") { exchange ->
            exchange.sendResponseHeaders(210, -1); exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("返回内容", Http.postJson("$base/post", "{\"q\":\"中文\"}", mapOf("Cookie" to "test=value")))
            assertEquals(listOf("POST", "test=value", "{\"q\":\"中文\"}"), captured.poll())
            val failure = kotlin.test.assertFailsWith<HttpException> { Http.getText("$base/deny") }
            assertEquals(210, failure.status)
        } finally { server.stop(0) }
    }

    @Test
    fun `test connection rejects blank host and bad port without network`() {
        assertNotNull(Http.testProxyConnection("", 7890, "HTTP"), "空地址要拦")
        assertNotNull(Http.testProxyConnection("   ", 7890, "HTTP"), "空白地址要拦")
        assertNotNull(Http.testProxyConnection("127.0.0.1", 0, "HTTP"), "端口 0 要拦")
        assertNotNull(Http.testProxyConnection("127.0.0.1", 70000, "HTTP"), "超大端口要拦")
        assertNotNull(Http.testProxyConnection("127.0.0.1", -1, "SOCKS5"), "负端口要拦")
    }

    @Test
    fun `configure proxy normalizes protocol and gating`() {
        Http.configureProxy(true, " 10.0.0.1 ", 1080, "socks5")
        val c = Http.currentProxy()
        assertTrue(c.enabled, "合法配置应启用")
        assertEquals("10.0.0.1", c.host, "host 要 trim")
        assertEquals(1080, c.port)
        assertEquals("SOCKS5", c.protocol, "协议归一化为大写 SOCKS5")

        // 端口非法 → 整体关闭（不能留半开状态）
        Http.configureProxy(true, "10.0.0.1", 0, "HTTP")
        assertTrue(!Http.currentProxy().enabled, "端口无效时不开代理")

        // 开关关 → 不开
        Http.configureProxy(false, "10.0.0.1", 1080, "HTTP")
        assertTrue(!Http.currentProxy().enabled)

        // 空 host → 不开
        Http.configureProxy(true, "  ", 1080, "HTTP")
        assertTrue(!Http.currentProxy().enabled)
    }

    @Test
    fun `per-source policy defaults to follow global`() {
        Http.proxyPolicy = { true }
        assertTrue(Http.proxyPolicy(app.gamanga.desktop.model.SourceType.KOMIIC))
        Http.proxyPolicy = { it != app.gamanga.desktop.model.SourceType.GODA }
        assertTrue(Http.proxyPolicy(app.gamanga.desktop.model.SourceType.KOMIIC), "Komiic 跟全局")
        assertTrue(!Http.proxyPolicy(app.gamanga.desktop.model.SourceType.GODA), "G社 单独关")
        Http.proxyPolicy = { true }
    }
}
