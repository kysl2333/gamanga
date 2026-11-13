package app.gamanga.desktop.ui.search

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 浏览页「连接失败」的二次提示只在**网络类失败**时给（用户 2026-10-29 要求：
 * 失败不能只甩一句红字，要说明可能需要开代理）。
 *
 * 判据必须收紧：把解析错、404 也说成"网络问题，开代理"是误导。
 */
class SearchFailureHintTest {

    @Test fun connectionProblemsSuggestProxy() {
        listOf(
            "HTTP connect timed out",
            "连接失败：Connection refused",
            "Unknown host: api.komiic.cc",
            "java.net.SocketTimeoutException: Read timed out",
            "SSL handshake aborted",
            "Connection reset by peer",
        ).forEach { assertTrue(looksLikeNetworkFailure(it), "应判为网络类：$it") }
    }

    @Test fun parseAndHttpErrorsDoNotBlameTheNetwork() {
        listOf(
            "解析失败：Unexpected JSON token",
            "HTTP 404 Not Found",
            "站点返回空列表",
            "登录状态已失效",
        ).forEach { assertFalse(looksLikeNetworkFailure(it), "不该判成网络类：$it") }
    }
}
