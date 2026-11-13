package app.gamanga.desktop.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G社章节排序的**离线**回归（不联网）。
 *
 * 锁 2026-10-23 用户实测 bug：API 的 order 字段偶尔是小数（装备我最强 292 章实录：
 * 239 话 order=240.1、268 话 order=270.1、274 话 order=275.1），旧代码用
 * `intOrNull` 解析失败 → 兜底 `?: 1` → 这三章 chapterIndex 全变 0，稳定排序后
 * 夹在第 1 话后面，章序大乱。
 */
class GodaSourceChapterOrderTest {

    private val source = GodaSource()

    private fun chapter(cid: String, title: String, order: Any?, slug: String) = buildJsonObject {
        put("id", cid)
        put("attributes", buildJsonObject {
            put("title", title)
            put("slug", slug)
            when (order) {
                null -> Unit // 缺 order：走数组位置兜底
                is Int -> put("order", order)
                is Double -> put("order", order)
                else -> put("order", order.toString())
            }
        })
    }

    /** 复刻装备我最强的异常结构：前几章正常 int order，中间夹小数 order。 */
    private fun sample(): JsonArray = buildJsonArray {
        add(chapter("481004", "000 我是谁？", 0, "0"))
        add(chapter("481005", "001 装备我最强", 1, "1"))
        add(chapter("481006", "002 伯爵的愤怒", 2, "2"))
        add(chapter("A1", "238 小人物", 239, "238"))
        add(chapter("1611954", "239 破坏", 240.1, "329-046574490-240")) // 小数 order
        add(chapter("A2", "240 终极强化", 241, "240"))
        add(chapter("1694461", "268 各自的战斗", 270.1, "329-054733060-270")) // 小数 order
        add(chapter("1709690", "274 成为王的资格", 275.1, "329-056965310-275")) // 小数 order
        add(chapter("A3", "275 大恶魔登场", 276, "275"))
    }

    @Test
    fun `float order chapters sort into place instead of collapsing to index 0`() {
        val chapters = source.mapChapters(sample(), mid = "329")

        val names = chapters.map { it.name }
        assertEquals(
            listOf(
                "000 我是谁？", "001 装备我最强", "002 伯爵的愤怒", "238 小人物",
                "239 破坏", "240 终极强化", "268 各自的战斗", "274 成为王的资格",
                "275 大恶魔登场"
            ),
            names,
            "小数 order 章节没排到正确位置"
        )
        // 239/268/274 不能再是 index 0（旧 bug 三章全压到第 1 话）
        assertTrue(chapters.none { it.index == 0 && it.name != "000 我是谁？" },
            "有非首章占了 index 0：${chapters.map { "${it.name}=${it.index}" }}")
    }

    @Test
    fun `indexes are sequential after renumbering`() {
        val chapters = source.mapChapters(sample(), mid = "329")
        assertEquals(chapters.indices.toList(), chapters.map { it.index },
            "index 必须排完序后连续回填，不能有并列/跳号")
    }

    @Test
    fun `missing order falls back to array position`() {
        val arr = buildJsonArray {
            add(chapter("C1", "001 甲", null, "1"))
            add(chapter("C2", "002 乙", 5, "2"))
            add(chapter("C3", "003 丙", null, "3"))
        }
        val chapters = source.mapChapters(arr, mid = "9")
        // 有缺 order 的 → 整表信任数组顺序，不混排
        assertEquals(listOf("001 甲", "002 乙", "003 丙"), chapters.map { it.name })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
    }

    @Test
    fun `shuffled array with complete orders is sorted by order`() {
        val arr = buildJsonArray {
            add(chapter("D3", "003 丙", 2, "3"))
            add(chapter("D1", "001 甲", 0, "1"))
            add(chapter("D2", "002 乙", 1, "2"))
        }
        val chapters = source.mapChapters(arr, mid = "9")
        assertEquals(listOf("001 甲", "002 乙", "003 丙"), chapters.map { it.name },
            "order 齐全时必须按 order 排，不能傻信数组顺序")
    }

    @Test
    fun `id keeps mid cid slug triple encoding`() {
        val chapters = source.mapChapters(sample(), mid = "329")
        assertTrue(chapters.all { it.id.split('|').size == 3 && it.id.startsWith("329|") },
            "id 编码坏了：${chapters.first().id}")
    }

    @Test
    fun `empty and malformed input yields empty list`() {
        assertTrue(source.mapChapters(buildJsonArray { }, mid = "1").isEmpty())
        val garbage = Json.parseToJsonElement("""[{"id":"x"},{"attributes":{}},"str"]""").let { it as JsonArray }
        assertTrue(source.mapChapters(garbage, mid = "1").isEmpty(), "畸形元素应被跳过而不是抛异常")
    }
}
