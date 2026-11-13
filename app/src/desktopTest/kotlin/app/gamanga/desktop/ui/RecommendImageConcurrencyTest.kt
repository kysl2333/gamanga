package app.gamanga.desktop.ui

import app.gamanga.desktop.ui.recommend.RecommendImageCache
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 推荐缩略图不再有并发上限（用户 2026-10-28：「感觉按顺序慢慢加载，取消并发限额」）。
 *
 * 做法：起 9 个互相独立的慢端点，每个都阻塞到「9 个请求全部到达」才返回。
 * - 取消限额后：9 个请求同时打到服务器，闸门打开，测试通过；
 * - 若仍保留旧的 7 并发：第 8、9 个排队进不来，闸门永远等不齐 → 超时失败。
 *
 * 这比断言「Semaphore 不存在」更直接：它验证的是真实请求并发度。
 */
class RecommendImageConcurrencyTest {

    @Test
    fun nineDifferentThumbnailsRequestAtTheSameTime() = runBlocking {
        val count = 9
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 默认 executor 是单线程，会把并发请求串行化——必须换成线程池才能测并发
        server.executor = Executors.newFixedThreadPool(count + 3)
        val arrived = CountDownLatch(count)
        val release = CountDownLatch(1)
        repeat(count) { index ->
            server.createContext("/t$index") { exchange ->
                arrived.countDown()
                release.await(6, TimeUnit.SECONDS)
                runCatching {
                    exchange.sendResponseHeaders(200, 1)
                    exchange.responseBody.use { it.write(0) }
                }
                exchange.close()
            }
        }
        server.start()
        try {
            val jobs = (0 until count).map { index ->
                async(Dispatchers.IO) {
                    RecommendImageCache.load("http://127.0.0.1:${server.address.port}/t$index")
                }
            }
            val allArrived = arrived.await(5, TimeUnit.SECONDS)
            release.countDown()
            jobs.awaitAll()
            assertTrue(allArrived, "$count 张不同缩略图应同时发出请求，不应被并发上限卡住")
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
