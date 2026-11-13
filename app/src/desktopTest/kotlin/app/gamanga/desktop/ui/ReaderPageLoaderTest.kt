package app.gamanga.desktop.ui

import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.ui.reader.loadChapterPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 取图 effect 的「取消 vs 失败」分流（R-45 复发修复的回归锁）。
 *
 * 用户实测症状：G社「无缓存切换有缓存的章节时也可能触发（取图失败），点重试就会恢复」。
 * 根因是旧实现用 `runCatching` 把换话时抛来的 CancellationException 也吞了，
 * 被取消的旧协程以僵尸身份把 error 写回已经出图的新章节。这里把三条契约锁住：
 * 成功只走 onSuccess、真失败才走 onError、取消原样抛出且不碰任何回调。
 */
class ReaderPageLoaderTest {

    @Test
    fun `success only calls onSuccess`() = runTest {
        var ok: List<PageImage>? = null
        var err: String? = null
        loadChapterPages(
            load = { listOf(PageImage("a"), PageImage("b")) },
            onSuccess = { ok = it },
            onError = { err = it },
        )
        assertEquals(listOf("a", "b"), ok?.map { it.url })
        assertNull(err)
    }

    @Test
    fun `failure routes message to onError`() = runTest {
        var err: String? = null
        loadChapterPages(
            load = { throw IllegalStateException("boom") },
            onSuccess = { error("onSuccess 不应被调用") },
            onError = { err = it },
        )
        assertEquals("boom", err)
    }

    @Test
    fun `failure without message falls back to class name`() = runTest {
        var err: String? = null
        loadChapterPages(
            load = { throw IllegalStateException() },
            onSuccess = { error("onSuccess 不应被调用") },
            onError = { err = it },
        )
        assertEquals("IllegalStateException", err)
    }

    @Test
    fun `cancellation is rethrown and writes no state`() = runTest {
        assertFailsWith<CancellationException> {
            loadChapterPages(
                load = { throw CancellationException("换话了") },
                onSuccess = { error("onSuccess 不应被调用") },
                onError = { error("onError 不应被调用") },
            )
        }
    }

    /**
     * 复现用户报的场景：旧取图协程在 load 里挂着（G社探针一轮 3~5 秒），
     * 用户切到有缓存的章节 → 旧协程被取消。旧代码在这里把
     * 「Job was cancelled」写进 error，盖住新章节已出图的页面。
     */
    @Test
    fun `cancelled in-flight load leaves newer chapter state untouched`() = runTest {
        var ok: List<PageImage>? = null
        var err: String? = null
        var ranToCompletion = false
        val job = launch {
            loadChapterPages(
                load = { delay(10_000); listOf(PageImage("slow")) },
                onSuccess = { ok = it },
                onError = { err = it },
            )
            ranToCompletion = true // 吞掉取消的僵尸协程会执行到这一行
        }
        runCurrent() // 让 load 挂到 delay 上（协程真正在取图中）
        job.cancelAndJoin()

        assertNull(ok, "被取消的取图不得当作成功")
        assertNull(err, "被取消的取图不得写成错误（僵尸协程不得盖掉新章节）")
        assertFalse(ranToCompletion, "取消必须重新抛出，而不是被吞掉后正常返回")
        assertTrue(job.isCancelled)
    }
}
