package app.gamanga.desktop.source

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M4 GoDa(包子漫画系) 联网回归：搜索 / 详情 / 章节。
 *
 * 章节**图片**不在这里测：那一路要借 JCEF 让站点自己解密（[BrowserProbe]），
 * 属于 UI 集成路径，见阅读器验收。
 */
class GodaSourceTest {

    private val source = GodaSource()

    @Test
    fun `detail snapshot shares html and force refresh reads remote again`() = runBlocking {
        var htmlCalls = 0
        var apiCalls = 0
        val src = GodaSource(
            htmlReader = { htmlCalls++; """<h1>漫画甲</h1><div data-mid="42"></div>""" },
            jsonReader = {
                apiCalls++
                """{"data":{"chapters":[{"id":"1","attributes":{"slug":"c1","title":"第一卷","order":1}}]}}"""
            },
        )
        val first = src.loadDetails("book")
        kotlin.test.assertEquals("漫画甲", first.manga?.title)
        kotlin.test.assertEquals("第一卷", first.chapters.single().name)
        kotlin.test.assertEquals(1, htmlCalls)
        kotlin.test.assertEquals(1, apiCalls)
        src.loadDetails("book", forceRefresh = true)
        kotlin.test.assertEquals(2, htmlCalls)
        kotlin.test.assertEquals(2, apiCalls)
    }

    @Test
    fun `directory failure preserves detail while cancellation propagates`() = runBlocking {
        val html = """<h1>漫画甲</h1><div data-mid="42"></div>"""
        val src = GodaSource(htmlReader = { html }, jsonReader = { throw HttpException("test", 429, "") })
        val result = src.loadDetails("book")
        kotlin.test.assertEquals("漫画甲", result.manga?.title)
        assertTrue(!result.chaptersComplete)
        val cancelled = GodaSource(htmlReader = { html }, jsonReader = { throw kotlinx.coroutines.CancellationException() })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { cancelled.loadDetails("book") }
        Unit
    }

    @Test
    fun `goda search parses cards`() = runBlocking {
        Assume.assumeTrue("真实源站测试需 -Dgamanga.network=1", System.getProperty("gamanga.network") == "1")
        val results = source.search("剑来")
        assertTrue(results.isNotEmpty(), "搜索无结果")
        assertTrue(
            results.all { it.title.isNotBlank() && it.sourceId.isNotBlank() },
            "有卡片缺标题或 slug：${results.take(3)}"
        )
        // 封面在 CDN 上，至少要有一个能解析出来
        assertTrue(results.any { it.coverUrl?.startsWith("http") == true }, "没解析到任何封面")
        // R-41 回归锁：站改版后搜索变路径式 /s/<关键词>，旧查询串写法服务端忽略 q，
        // 对任意关键词返回同一份默认列表——只断言「非空」永远绿，必须验证结果与关键词相关。
        assertTrue(
            results.any { it.title.contains("剑来") },
            "搜索结果与关键词无关（疑似又收到了默认列表）：${results.take(3).map { it.title }}"
        )
        // 不同关键词必须返回不同结果集（默认列表对所有关键词都一样）
        val other = source.search("猎人")
        assertTrue(
            other.map { it.sourceId } != results.map { it.sourceId },
            "不同关键词返回了相同结果（搜索失效）：${other.take(3).map { it.title }}"
        )
    }

    @Test
    fun `goda detail and chapters`() = runBlocking {
        Assume.assumeTrue("真实源站测试需 -Dgamanga.network=1", System.getProperty("gamanga.network") == "1")
        val slug = source.search("女体化").firstOrNull()?.sourceId
        assertNotNull(slug, "探针搜索无结果，未完成详情与章节验证")

        val detail = source.detail(slug)
        assertNotNull(detail, "详情为空：$slug")
        assertTrue(detail.title.isNotBlank(), "详情缺标题")
        // 标题里不能混进 H1 内的状态徽章
        assertTrue(detail.title.length < 80 && !detail.title.contains("連載中"), "标题混入了状态徽章：${detail.title}")
        // 曾经整段漏解析（作者/状态/简介全 null，UI 只显示 "-"）
        assertNotNull(detail.author, "详情缺作者：$slug")
        assertNotNull(detail.status, "详情缺状态：$slug")
        assertNotNull(detail.description, "详情缺简介：$slug")

        val chapters = source.chapters(slug)
        assertTrue(chapters.isNotEmpty(), "章节列表为空：$slug")
        assertTrue(chapters.all { it.id.split('|').size == 3 }, "章节 id 编码不对（应为 mid|cid|slug）：${chapters.first()}")
        assertTrue(chapters.first().name.isNotBlank(), "章节缺名称")
    }
}
