package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceChapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.PageImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 书架章节刷新的策略判定（F-271，2026-10-29）。
 *
 * 用户口径：手动 / 每次开启应用 / 每一天 / 每七天，默认手动。
 * 通过内存数据库和fake adapter验证真实run链路、档位与启动节流，不访问站点。
 */
class ChapterRefreshPolicyTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        db = GamangaDatabase(driver)
        ChapterRefresh.resetForTest()
    }

    private fun repo() = LibraryRepository(db)

    private fun chapters(n: Int) = (1..n).map { SourceChapter("c$it", "第${it}话", it - 1) }

    private fun adapter(read: suspend () -> List<SourceChapter>) = object : SourceAdapter {
        override val type = SourceType.COPYMANGA
        override val displayName = "test"
        override suspend fun search(keyword: String, page: Int, pageSize: Int) = emptyList<SourceManga>()
        override suspend fun detail(mangaId: String): SourceManga? = null
        override suspend fun chapters(mangaId: String): List<SourceChapter> = error("不能读旧缓存")
        override suspend fun refreshChapters(mangaId: String) = read()
        override suspend fun pages(chapterId: String) = emptyList<PageImage>()
    }

    @Test
    fun `first remote refresh with empty local list establishes baseline without dot`() = runBlocking {
        val r = repo()
        r.importFromSource(SourceType.COPYMANGA, SourceManga("pw", "书"), emptyList())
        withTimeout(2_000) { r.shelfItems.first { it.size == 1 } }
        assertEquals(0, ChapterRefresh.run(r, "test") { adapter { chapters(3) } }.updated)
        assertTrue(ChapterRefresh.pending.value.isEmpty())
        assertEquals(3, r.chapterCountOf("copymanga:pw"))
        assertTrue(ChapterRefresh.lastRun(r) > 0)
    }

    @Test
    fun `failed refresh preserves existing dot and last successful time`() = runBlocking {
        val r = repo()
        val id = r.importFromSource(SourceType.COPYMANGA, SourceManga("pw", "书"), chapters(2))
        withTimeout(2_000) { r.shelfItems.first { it.size == 1 } }
        ChapterRefresh.clear(r, id)
        assertEquals(1, ChapterRefresh.run(r, "test") { adapter { chapters(3) } }.updated)
        val last = ChapterRefresh.lastRun(r)
        assertEquals(1, ChapterRefresh.pending.value[id])
        val failed = ChapterRefresh.run(r, "test") { adapter { error("offline") } }
        assertEquals(1, failed.updated)
        assertEquals(1, failed.failed)
        assertTrue(failed.message.contains("未完成刷新"))
        assertEquals(1, ChapterRefresh.pending.value[id])
        assertEquals(last, ChapterRefresh.lastRun(r))
        assertEquals(1, ChapterRefresh.run(r, "test") { adapter { chapters(3) } }.updated)
    }

    @Test
    fun `detail viewed during refresh is not overwritten by old baseline`() = runBlocking {
        val r = repo()
        val id = r.importFromSource(SourceType.COPYMANGA, SourceManga("pw", "书"), chapters(2))
        withTimeout(2_000) { r.shelfItems.first { it.size == 1 } }
        ChapterRefresh.clear(r, id)
        assertEquals(0, ChapterRefresh.run(r, "test") { adapter {
            r.syncChapters(id, chapters(3))
            ChapterRefresh.markLoaded(r, id)
            chapters(3)
        } }.updated)
        assertTrue(ChapterRefresh.pending.value.isEmpty())
    }

    @Test
    fun `incomplete navigation cannot pollute persisted chapter baseline`() = runBlocking {
        val r = repo()
        val nav = listOf(SourceChapter("entry", "阅读入口", 0, navigationOnly = true))
        val id = r.importFromSource(SourceType.COPYMANGA, SourceManga("pw", "书"), nav)
        withTimeout(2_000) { r.shelfItems.first { it.size == 1 } }
        assertEquals(0, r.chapterCountOf(id), "入架不能保存导航为真实目录")
        r.syncChapters(id, nav)
        assertEquals(0, r.chapterCountOf(id))
        val result = ChapterRefresh.run(r, "test") { adapter { nav } }
        assertEquals(1, result.failed)
        assertEquals(0L, ChapterRefresh.lastRun(r))
        assertTrue(result.message.contains("未完成刷新"))
        assertTrue(!result.message.contains("已是最新"))
    }

    @Test
    fun `failed batch prevents immediate restart refresh without changing success time`() = runBlocking {
        val r = repo()
        r.importFromSource(SourceType.COPYMANGA, SourceManga("pw", "书"), chapters(2))
        withTimeout(2_000) { r.shelfItems.first { it.size == 1 } }
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_LAUNCH)
        ChapterRefresh.run(r, "test") { adapter { emptyList() } }
        ChapterRefresh.resetForTest() // 模拟进程重启，尝试时间必须落库。
        assertEquals(0L, ChapterRefresh.lastRun(r))
        assertFalse(ChapterRefresh.dueOnLaunch(r), "失败后重启不应立即重刷整库")
    }

    @Test
    fun `incomplete copy directory stops remaining source batch`() = runBlocking {
        val r = repo()
        repeat(6) { r.importFromSource(SourceType.COPYMANGA, SourceManga("pw$it", "书$it"), chapters(2)) }
        withTimeout(2_000) { r.shelfItems.first { it.size == 6 } }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val result = ChapterRefresh.run(r, "test") { adapter { calls.incrementAndGet(); emptyList() } }
        assertEquals(1, calls.get(), "首个拷贝目录不可用后应停止同源后续访问")
        assertEquals(6, result.failed)
        assertTrue(r.shelfItems.value.all { r.chapterCountOf(it.entry.id) == 2 })
    }

    @Test
    fun `stopped copy batch does not stop other source or overwrite local data`() = runBlocking {
        val r = repo()
        repeat(3) { r.importFromSource(SourceType.COPYMANGA, SourceManga("pw$it", "书$it"), chapters(2)) }
        val other = r.importFromSource(SourceType.KOMGA, SourceManga("other", "其它"), chapters(2))
        withTimeout(2_000) { r.shelfItems.first { it.size == 4 } }
        val result = ChapterRefresh.run(r, "test") { type ->
            if (type == SourceType.COPYMANGA) adapter { error("offline") }
            else adapter { chapters(3) }
        }
        assertEquals(3, result.failed)
        assertEquals(3, r.chapterCountOf(other))
        assertEquals(0L, ChapterRefresh.lastRun(r))
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_LAUNCH)
        val attempt = r.getPref("library.refresh.lastAttempt")!!.toLong()
        assertFalse(ChapterRefresh.dueOnLaunch(r, now = attempt + 10 * 60_000L - 1))
        assertTrue(ChapterRefresh.dueOnLaunch(r, now = attempt + 10 * 60_000L))
    }

    @Test
    fun `default mode is manual and never auto refreshes`() {
        val r = repo()
        assertEquals(ChapterRefresh.MODE_MANUAL, ChapterRefresh.mode(r))
        assertFalse(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L))
    }

    @Test
    fun `mode round trips and unknown values fall back to manual`() {
        val r = repo()
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_DAILY)
        assertEquals(ChapterRefresh.MODE_DAILY, ChapterRefresh.mode(r))
        r.setPref(ChapterRefresh.PREF_MODE, "随便写点什么")
        assertEquals(ChapterRefresh.MODE_MANUAL, ChapterRefresh.mode(r), "坏值不能把刷新弄崩")
    }

    @Test
    fun `launch mode refreshes on every start`() {
        val r = repo()
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_LAUNCH)
        assertTrue(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L))
    }

    @Test
    fun `daily mode waits 24 hours`() {
        val r = repo()
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_DAILY)
        val day = 24L * 60 * 60 * 1000
        r.setPref(ChapterRefresh.PREF_LAST_RUN, "1000000")
        assertFalse(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L + day - 1))
        assertTrue(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L + day))
    }

    @Test
    fun `weekly mode waits seven days`() {
        val r = repo()
        ChapterRefresh.setMode(r, ChapterRefresh.MODE_WEEKLY)
        val day = 24L * 60 * 60 * 1000
        r.setPref(ChapterRefresh.PREF_LAST_RUN, "1000000")
        assertFalse(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L + 6 * day))
        assertTrue(ChapterRefresh.dueOnLaunch(r, now = 1_000_000L + 7 * day))
    }

    @Test
    fun `labels match the user's wording`() {
        assertEquals("手动刷新", ChapterRefresh.labelOf(ChapterRefresh.MODE_MANUAL))
        assertEquals("每次开启应用刷新", ChapterRefresh.labelOf(ChapterRefresh.MODE_LAUNCH))
        assertEquals("每一天刷新", ChapterRefresh.labelOf(ChapterRefresh.MODE_DAILY))
        assertEquals("每七天刷新", ChapterRefresh.labelOf(ChapterRefresh.MODE_WEEKLY))
    }

    // ---- R-180：「详情页还没加载完就以为有新漫画」的基线判定 ----

    /**
     * 用户 2026-10-30 原话：「书架刷新有新漫画逻辑还需要修改，现在出现过详情页还没加载完全
     * 好像就识别以为有新漫画的问题」。
     *
     * 详情页是「先上本地那份、后台再拉源站落库」，点开那一刻本地可能 **0 章**。
     * 这时把 0 记成「看过的那一版」，下一轮刷新就会把整本书都算成新章节。
     */
    @Test
    fun `viewing with zero local chapters keeps the old baseline`() {
        assertEquals(52, seenAfterView(current = 52, loaded = 0), "0 章不能当基线")
        assertNull(seenAfterView(current = null, loaded = 0), "本来没基线就别写")
        assertEquals(60, seenAfterView(current = 52, loaded = 60), "章节加载出来后推到真实章数")
    }

    @Test
    fun `first refresh of a book with no local chapters uses the remote count`() {
        // 本地一话都没有（从没落过库）：记 0 会让下一轮把整本书算成新章节
        assertEquals(120, seenAfterRefresh(current = null, local = 0, remoteSize = 120))
        assertEquals(100, seenAfterRefresh(current = null, local = 100, remoteSize = 120), "有本地就用本地")
        assertEquals(52, seenAfterRefresh(current = 52, local = 100, remoteSize = 120), "已有基线不动")
    }
}
