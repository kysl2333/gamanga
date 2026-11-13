package app.gamanga.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gamanga.desktop.db.GamangaDatabase
import app.gamanga.desktop.model.SourceType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浏览历史（F-128）的不变量回归。
 *
 * 用户 2026-10-27 的口径就是这里的验收标准：
 * - 「浏览页历史只记录在浏览页点过的漫画封面……与记录阅读进度的历史页相互独立」
 *   → 存的是自己的 pref 键，一个字段都不碰 `readingProgress`；
 * - 「点击菜单栏浏览页即进入该浏览历史画面」→ 重启后还在（能反序列化回来）。
 */
class BrowseHistoryTest {

    private lateinit var db: GamangaDatabase

    @BeforeTest
    fun setup() {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        GamangaDatabase.Schema.create(driver)
        db = GamangaDatabase(driver)
    }

    private fun repo() = LibraryRepository(db)

    private fun entry(id: String, source: SourceType = SourceType.KOMIIC) = BrowseHistoryEntry(
        source = source,
        sourceMangaId = id,
        title = "漫画 $id",
        coverUrl = "https://example.com/$id.jpg",
        author = "作者 $id",
    )

    @Test
    fun `记录后按时间倒序`() {
        val store = BrowseHistoryStore(repo())
        store.record(entry("a"))
        store.record(entry("b"))
        assertEquals(listOf("b", "a"), store.items.value.map { it.sourceMangaId })
        assertTrue(store.items.value.all { it.visitedAt > 0L }, "visitedAt 应由 record 盖上")
    }

    @Test
    fun `同一本只留一条并提到最前`() {
        val store = BrowseHistoryStore(repo())
        store.record(entry("a"))
        store.record(entry("b"))
        store.record(entry("c"))
        // 再点一次 a —— 不能出现两条 a
        store.record(entry("a"))
        assertEquals(listOf("a", "c", "b"), store.items.value.map { it.sourceMangaId })
    }

    @Test
    fun `不同源的同名 id 算两本`() {
        val store = BrowseHistoryStore(repo())
        store.record(entry("123", SourceType.KOMIIC))
        store.record(entry("123", SourceType.MANGABZ))
        assertEquals(2, store.items.value.size, "去重键必须带源类型，否则两个源的同 id 会互相顶掉")
    }

    @Test
    fun `超过上限丢最旧的`() {
        val store = BrowseHistoryStore(repo())
        repeat(BrowseHistoryStore.MAX_ENTRIES + 5) { i -> store.record(entry("m$i")) }
        assertEquals(BrowseHistoryStore.MAX_ENTRIES, store.items.value.size)
        assertEquals("m${BrowseHistoryStore.MAX_ENTRIES + 4}", store.items.value.first().sourceMangaId)
        assertTrue(
            store.items.value.none { it.sourceMangaId == "m0" },
            "最早那条应被挤掉",
        )
    }

    @Test
    fun `重启后还在`() {
        val first = BrowseHistoryStore(repo())
        first.record(entry("a", SourceType.COPYMANGA))
        first.record(entry("b", SourceType.GODA))
        // 新实例 = 冷启动：只能从 pref 反序列化
        val second = BrowseHistoryStore(repo())
        assertEquals(listOf("b", "a"), second.items.value.map { it.sourceMangaId })
        assertEquals(SourceType.GODA, second.items.value.first().source)
        assertEquals("https://example.com/b.jpg", second.items.value.first().coverUrl)
    }

    @Test
    fun `坏数据不崩`() {
        assertEquals(emptyList(), BrowseHistoryStore.decode(null))
        assertEquals(emptyList(), BrowseHistoryStore.decode(""))
        assertEquals(emptyList(), BrowseHistoryStore.decode("不是 JSON"))
        assertEquals(emptyList(), BrowseHistoryStore.decode("{}"))
        // 单条缺字段 / 源类型不认识 → 跳过那一条，其余照读
        val json = """[{"source":"NOPE","sourceMangaId":"x","title":"t"},"""
            .plus("""{"source":"KOMIIC","sourceMangaId":"ok","title":"留下的"}]""")
        assertEquals(listOf("ok"), BrowseHistoryStore.decode(json).map { it.sourceMangaId })
    }

    @Test
    fun `编码解码往返不丢字段`() {
        val src = listOf(
            BrowseHistoryEntry(SourceType.MANGABZ, "s1", "标题一", null, null, 111L),
            BrowseHistoryEntry(SourceType.COPYMANGA, "s2", "标题二", "https://c/2.jpg", "某人", 222L),
        )
        assertEquals(src, BrowseHistoryStore.decode(BrowseHistoryStore.encode(src)))
    }

    @Test
    fun `不污染阅读进度历史`() {
        val r = repo()
        val store = BrowseHistoryStore(r)
        store.record(entry("only-browsed"))
        assertTrue(
            r.history.value.isEmpty() && r.progressOf("komiic:only-browsed") == null,
            "浏览历史必须与阅读进度历史完全独立：只点开过封面不该产生 progress 行",
        )
    }
}
