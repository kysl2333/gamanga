package app.gamanga.desktop.data

import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.PageImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * F-43 多源并发搜索。
 *
 * 锁定的行为：每个源先发 Loading 再发结果；**一个源失败不影响其它源**；
 * 关键词为空时不发任何东西（UI 不用处理空搜索）。
 */
class SearchAllTest {

    private fun fake(
        type: SourceType,
        items: List<SourceManga> = emptyList(),
        error: String? = null,
    ): SourceAdapter = object : SourceAdapter {
        override val type = type
        override val displayName = type.name
        override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
            if (error != null) throw IllegalStateException(error)
            return items
        }
        override suspend fun detail(mangaId: String): SourceManga? = null
        override suspend fun chapters(mangaId: String): List<SourceChapter> = emptyList()
        override suspend fun pages(chapterId: String): List<PageImage> = emptyList()
    }

    @Test
    fun `each source emits loading then result`() = runTest {
        val g = SourceManga("1", "灌篮高手")
        val adapters = mapOf(
            SourceType.GODA to fake(SourceType.GODA, listOf(g)),
            SourceType.KOMIIC to fake(SourceType.KOMIIC, listOf(g, g)),
        )
        val events = SearchAll.search("灌篮", listOf(SourceType.GODA, SourceType.KOMIIC)) {
            adapters[it]
        }.toList()

        // 每个源一条 Loading + 一条结果
        assertEquals(4, events.size, "两个源应各发 Loading 与结果：$events")
        events.filter { it.second is SourceSearchState.Loading }.let {
            assertEquals(setOf(SourceType.GODA, SourceType.KOMIIC), it.map { e -> e.first }.toSet())
        }
        val ok = events.mapNotNull { (t, s) -> (s as? SourceSearchState.Success)?.let { t to it.items.size } }.toMap()
        assertEquals(1, ok[SourceType.GODA])
        assertEquals(2, ok[SourceType.KOMIIC])
    }

    @Test
    fun `one source failing does not kill the others`() = runTest {
        val adapters = mapOf(
            SourceType.GODA to fake(SourceType.GODA, listOf(SourceManga("1", "A"))),
            SourceType.MANGABZ to fake(SourceType.MANGABZ, error = "HTTP 404"),
        )
        val events = SearchAll.search("x", listOf(SourceType.GODA, SourceType.MANGABZ)) { adapters[it] }.toList()
        val success = events.mapNotNull { it.second as? SourceSearchState.Success }
        val failed = events.mapNotNull { it.second as? SourceSearchState.Failed }
        assertEquals(1, success.size, "好源要照常出结果")
        assertEquals(1, failed.size)
        assertTrue(failed.first().message.contains("404"))
    }

    @Test
    fun `cancelled slow search never emits after newer search succeeds`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : SourceAdapter by fake(SourceType.KOMIIC) {
            override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
                if (keyword == "旧词") {
                    started.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                return listOf(SourceManga(keyword, keyword))
            }
        }
        val events = mutableListOf<SourceSearchState>()
        val old = launch { SearchAll.search("旧词", listOf(SourceType.KOMIIC)) { adapter }.collect { events += it.second } }
        started.await()
        old.cancel()
        SearchAll.search("新词", listOf(SourceType.KOMIIC)) { adapter }.collect { events += it.second }
        release.complete(Unit)
        old.join()
        assertEquals(listOf("新词"), events.filterIsInstance<SourceSearchState.Success>().flatMap { it.items }.map { it.title })
        assertTrue(events.none { it is SourceSearchState.Failed })
    }

    @Test
    fun `fallback cancellation stops immediately instead of trying another keyword`() = runTest {
        val words = mutableListOf<String>()
        val adapter = object : SourceAdapter by fake(SourceType.KOMIIC) {
            override suspend fun search(keyword: String, page: Int, pageSize: Int): List<SourceManga> {
                words += keyword
                if (words.size == 2) throw CancellationException("cancel fallback")
                return emptyList()
            }
        }
        assertFailsWith<CancellationException> {
            SearchAll.searchWithFallback(adapter, "很长的主要标题『副标题』！")
        }
        assertEquals(2, words.size, "取消后不能继续发第三个搜索")
    }

    @Test
    fun `blank keyword emits nothing`() = runTest {
        val events = SearchAll.search("   ", listOf(SourceType.GODA)) {
            fake(SourceType.GODA, listOf(SourceManga("1", "A")))
        }.toList()
        assertTrue(events.isEmpty(), "空关键词不该发事件：$events")
    }
}
