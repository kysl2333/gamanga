package app.gamanga.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索词退让字典（F-272，2026-10-29）。
 *
 * 用户报：搜超长轻小说标题（带 `『』！`）时源站 0 条，只搜 `9999` 才有。
 * 源站按整串子串匹配，装饰符会把它拖死；这里验证「先原词、再退让」的两步策略。
 */
class SearchKeywordsTest {

    private val longTitle =
        "被信赖的伙伴在地下城深处追杀依靠恩惠『无限扭蛋』召唤等级9999的同伴们向世界和原队友复仇还要再补上一句『活该！』"

    @Test
    fun `long decorated title needs fallback`() {
        assertTrue(SearchKeywords.needsFallback(longTitle))
    }

    @Test
    fun `plain short keyword does not trigger extra searches`() {
        // 空结果就是真空，不该为它多跑两轮（拷贝漫画一次 6 秒）
        assertFalse(SearchKeywords.needsFallback("9999"))
        assertFalse(SearchKeywords.needsFallback("海贼王"))
        assertFalse(SearchKeywords.needsFallback(""))
    }

    @Test
    fun `cleaned drops decorative brackets and punctuation`() {
        val cleaned = SearchKeywords.cleaned("鬼滅之刃『第一季』！（上）")
        assertEquals("鬼滅之刃 第一季 上", cleaned)
        assertFalse(cleaned.contains("『"))
        assertFalse(cleaned.contains("！"))
    }

    @Test
    fun `chunks split on decoration and sort by length`() {
        val chunks = SearchKeywords.chunks(longTitle)
        assertTrue(chunks.size >= 3, "至少能切出几段，实际=$chunks")
        assertEquals(chunks.sortedByDescending { it.length }, chunks, "长的在前，先拿最有辨识度的那段去搜")
        assertTrue(chunks.none { it.length < 2 }, "单字片段是噪音，要丢掉")
    }

    @Test
    fun `fallbacks are at most two and never equal to the raw keyword`() {
        val list = SearchKeywords.fallbacks(longTitle)
        assertTrue(list.size in 1..2, "最多退让两步，实际=$list")
        assertTrue(list.none { it == longTitle })
        assertTrue(list.none { it.isBlank() })
    }

    @Test
    fun `title with numbers keeps the numeric chunk searchable`() {
        // 用户原话：「我只搜索 9999 才搜索到」——数字段必须能作为退让候选出现
        val chunks = SearchKeywords.chunks(longTitle)
        assertTrue(chunks.any { it.contains("9999") }, "实际=$chunks")
    }
}
