package app.gamanga.desktop.ui

import app.gamanga.desktop.source.BangumiClient
import app.gamanga.desktop.ui.recommend.RecommendState
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class RecommendRandomTest {
    private fun item(id: Int) = BangumiClient.Item(id, "漫画$id", null, null, null)

    @Test fun `detail tag opens filtered discovery and exits random mode`() = runTest {
        val prefs = Preferences.userRoot().node("Gamanga/tests/tag-${UUID.randomUUID()}")
        var requested = ""
        val state = RecommendState(backgroundScope, prefs, { keyword, category, _, _, _ ->
            assertEquals("", keyword)
            requested = category
            BangumiClient.Page(listOf(item(7)), 1)
        })
        try {
            state.setRandomMode(true)
            state.keyword = "旧搜索"
            state.tab = "最近浏览"
            state.stack.add(RecommendState.Detail(item(1)))
            state.browseTag("异世界")
            testScheduler.runCurrent()
            assertEquals("发现", state.tab)
            assertEquals("异世界", state.category)
            assertEquals("异世界", requested)
            assertFalse(state.random)
            assertTrue(state.stack.isEmpty())
            assertEquals(listOf(7), state.items.map { it.id })
        } finally { prefs.removeNode() }
    }

    @Test fun `random batches are finite unique and refresh keeps current ids and order`() = runTest {
        val prefs = Preferences.userRoot().node("Gamanga/tests/random-${UUID.randomUUID()}")
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val state = RecommendState(backgroundScope, prefs, { _, _, _, offset, refresh ->
            calls += offset to refresh
            BangumiClient.Page((offset until offset + 30).map(::item), 120)
        }, Random(42))
        try {
            state.setRandomMode(true)
            testScheduler.runCurrent()
            val first = state.items.map { it.id }
            assertEquals(30, first.size)
            val beforeMore = calls.size
            state.search(true)
            testScheduler.runCurrent()
            assertEquals(beforeMore, calls.size, "随机页不自动追加")
            state.refresh()
            testScheduler.runCurrent()
            assertEquals(first, state.items.map { it.id })
            assertTrue(calls.last().second, "刷新必须绕过响应缓存")
            state.nextRandomBatch()
            testScheduler.runCurrent()
            assertEquals(30, state.items.size)
            assertTrue(state.items.none { it.id in first }, "下一批不重复上一批")
        } finally { prefs.removeNode() }
    }

    @Test fun `exhausted pool stays bounded and failure keeps previous batch`() = runTest {
        val prefs = Preferences.userRoot().node("Gamanga/tests/random-${UUID.randomUUID()}")
        var calls = 0
        var fail = false
        val state = RecommendState(backgroundScope, prefs, { _, _, _, _, _ ->
            calls++
            if (fail) throw IllegalStateException("fixture failure")
            BangumiClient.Page((1..12).map(::item), 12)
        }, Random(1))
        try {
            state.setRandomMode(true)
            testScheduler.runCurrent()
            val original = state.items
            assertEquals(12, original.size)
            val before = calls
            state.nextRandomBatch()
            testScheduler.runCurrent()
            assertEquals(original, state.items)
            assertTrue(calls - before <= 4)
            assertNotNull(state.message)
            fail = true
            state.nextRandomBatch()
            testScheduler.runCurrent()
            assertEquals(original, state.items)
            assertFalse(state.loading)
        } finally { prefs.removeNode() }
    }

    @Test fun `cancelled old batch never overwrites newer query`() = runTest {
        val prefs = Preferences.userRoot().node("Gamanga/tests/random-${UUID.randomUUID()}")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val state = RecommendState(backgroundScope, prefs, { keyword, _, _, _, _ ->
            if (keyword.isEmpty()) {
                started.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                BangumiClient.Page(listOf(item(1)), 1)
            } else BangumiClient.Page(listOf(item(99)), 1)
        }, Random(1))
        try {
            state.setRandomMode(true)
            testScheduler.runCurrent()
            assertTrue(started.isCompleted)
            state.keyword = "新查询"
            state.search()
            testScheduler.runCurrent()
            assertEquals(listOf(99), state.items.map { it.id })
            release.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(listOf(99), state.items.map { it.id })
        } finally { release.complete(Unit); prefs.removeNode() }
    }
}
