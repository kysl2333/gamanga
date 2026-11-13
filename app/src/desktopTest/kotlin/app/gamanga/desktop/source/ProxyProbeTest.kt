package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 代理测试必须**真的**测代理（R-161，2026-10-29）。
 *
 * 用户报：「输入一个连不通的 IP 和端口，点测试竟然通过了」。
 * 实测根因是 JDK 的 `HttpClient` 对 SOCKS 代理不可靠——不可达的代理也会返回 204（走直连）。
 * 现在 `Http.testProxyConnection` 先做**代理端口 TCP 连通性**检查，
 * 这一步与协议无关，任何「连不上的代理」都必须被判失败。
 *
 * 这些用例只连本机回环端口（必然被拒绝），不发外网请求，可离线跑。
 */
class ProxyProbeTest {

    private lateinit var logDir: java.nio.file.Path

    @org.junit.Before
    fun setup() {
        logDir = Files.createTempDirectory("proxyprobe-test")
        AppLog.dirOverride = logDir
    }

    @org.junit.After
    fun teardown() {
        AppLog.dirOverride = null
        logDir.toFile().deleteRecursively()
    }

    /** 1 号端口在本机必然拒绝连接 —— 这就是「填了个连不通的端口」。 */
    @Test
    fun `unreachable proxy fails for http`() {
        val error = Http.testProxyConnection("127.0.0.1", 1, "HTTP")
        assertNotNull(error, "连不上的代理不能判成通过")
        assertTrue(error.contains("连不上代理"), "要说清是代理本身连不上，实际=$error")
    }

    @Test
    fun `unreachable proxy fails for socks5`() {
        // 这条正是用户踩的坑：JDK 的 HttpClient 会对 SOCKS 直连绕过，旧实现判成通过
        val error = Http.testProxyConnection("127.0.0.1", 1, "SOCKS5")
        assertNotNull(error, "SOCKS5 代理连不上时也必须失败")
        assertTrue(error.contains("连不上代理"), "实际=$error")
    }

    @Test
    fun `blank host and bad port are rejected`() {
        assertNotNull(Http.testProxyConnection("  ", 7890, "HTTP"))
        assertNotNull(Http.testProxyConnection("127.0.0.1", 0, "HTTP"))
        assertNotNull(Http.testProxyConnection("127.0.0.1", 70000, "HTTP"))
    }
}
