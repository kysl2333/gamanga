package app.gamanga.desktop.source

import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 图片列表缓存 + 瞬时失败重试（R-05 / R-07）。
 *
 * 背景：Komiic 每调一次 `imageTicketsByChapterId` 就烧一份每日配额，来回切话会把额度烧光，
 * 用户看到的就是「点下一话报错」「从书架进去图加载失败，切话再回来才好」。
 * 这里用计数假源锁住三条行为：命中缓存不再回源、瞬时失败重试一次、配额错误不重试。
 */
class PageCacheTest {

    /** 计数假源：前 [failTimes] 次抛 [error]，之后返回一页图。 */
    private class CountingSource(
        override val type: SourceType,
        private val failTimes: Int = 0,
        private val error: () -> Exception = { IllegalStateException("boom") }
    ) : SourceAdapter {
        var calls = 0

        override val displayName: String = "test"

        override suspend fun search(keyword: String, page: Int, pageSize: Int) = emptyList<SourceManga>()
        override suspend fun detail(mangaId: String): SourceManga? = null
        override suspend fun chapters(mangaId: String) = emptyList<SourceChapter>()

        override suspend fun pages(chapterId: String): List<PageImage> {
            calls++
            if (calls <= failTimes) throw error()
            return listOf(PageImage("https://example.invalid/$chapterId-1.jpg"))
        }
    }

    @Test
    fun `second call for same chapter is served from cache`() = runBlocking {
        PageCache.clear()
        val src = CountingSource(SourceType.KOMIIC)
        val cached = CachedPages(src)

        cached.pages("ch-1")
        cached.pages("ch-1")
        cached.pages("ch-1")

        assertEquals(1, src.calls, "同一章重复取图不该再打源站（会白烧配额）")
    }

    @Test
    fun `different chapters are cached separately`() = runBlocking {
        PageCache.clear()
        val src = CountingSource(SourceType.KOMIIC)
        val cached = CachedPages(src)

        cached.pages("a")
        cached.pages("b")
        assertEquals(2, src.calls)
    }

    @Test
    fun `transient failure is retried once`() = runBlocking {
        PageCache.clear()
        val src = CountingSource(SourceType.GODA, failTimes = 1)
        val pages = CachedPages(src).pages("ch-2")

        assertEquals(1, pages.size, "重试后应拿到图")
        assertEquals(2, src.calls, "第一次失败 + 重试一次")
    }

    @Test
    fun `quota error is not retried`() = runBlocking {
        PageCache.clear()
        val src = CountingSource(
            SourceType.KOMIIC,
            failTimes = 99,
            error = { IllegalStateException("Komiic 接口报错：Daily image quota exceeded") }
        )

        assertFailsWith<IllegalStateException> { CachedPages(src).pages("ch-3") }
        assertEquals(1, src.calls, "配额错误重试只会白烧额度，必须直接抛给 UI")
        assertTrue(IllegalStateException("Daily image quota exceeded").isQuotaError)
    }

    @Test
    fun `same chapter concurrent readers consume one ticket request`() = runBlocking {
        PageCache.clear()
        var calls = 0
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = object : SourceAdapter by CountingSource(SourceType.KOMIIC) {
            override suspend fun pages(chapterId: String): List<PageImage> {
                calls++
                entered.complete(Unit)
                release.await()
                return listOf(PageImage("https://s/page"))
            }
        }
        val cached = CachedPages(src)
        kotlinx.coroutines.coroutineScope {
            val first = async { cached.pages("shared") }
            entered.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cached.pages("shared") }
            release.complete(Unit)
            assertEquals(first.await(), second.await())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `different chapters keep independent concurrency`() = runBlocking {
        PageCache.clear()
        val firstEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = object : SourceAdapter by CountingSource(SourceType.GODA) {
            override suspend fun pages(chapterId: String): List<PageImage> {
                if (chapterId == "a") { firstEntered.complete(Unit); secondEntered.await() }
                else { firstEntered.await(); secondEntered.complete(Unit) }
                return listOf(PageImage("https://s/$chapterId"))
            }
        }
        val cached = CachedPages(src)
        kotlinx.coroutines.withTimeout(2_000) {
            kotlinx.coroutines.coroutineScope {
                val a = async { cached.pages("a") }
                val b = async { cached.pages("b") }
                a.await(); b.await()
                Unit
            }
        }
    }

    @Test
    fun `explicit http refusals do not retry even with misleading body`() = runBlocking {
        for (status in listOf(210, 400, 401, 403, 404, 429)) {
            var calls = 0
            val error = HttpException("https://s/private?token=secret", status, "connection timed out")
            assertFailsWith<HttpException> { retryOnce { calls++; throw error } }
            assertEquals(1, calls, "HTTP $status 不应自动重试")
            assertTrue(!isConnectionFailure(error), "业务响应正文不能让换域名重试误判")
        }
    }

    @Test
    fun `cancelled page load is never retried`() = runBlocking {
        var calls = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            retryOnce { calls++; throw kotlinx.coroutines.CancellationException("closed") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `empty result is not cached`() = runBlocking {
        PageCache.clear()
        val src = object : SourceAdapter by CountingSource(SourceType.KOMIIC) {
            override suspend fun pages(chapterId: String) = emptyList<PageImage>()
        }
        val cached = CachedPages(src)

        cached.pages("ch-4")
        cached.pages("ch-4")
        // 空结果意味着"这次没取到"，缓存它会把一次失败固化成永久失败
        assertEquals(null, PageCache.get(SourceType.KOMIIC, "ch-4"))
    }
}
