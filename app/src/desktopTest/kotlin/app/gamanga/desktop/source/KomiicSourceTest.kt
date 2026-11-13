package app.gamanga.desktop.source

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M3 源适配联网回归：对着真实站点跑「搜索 → 详情 → 章节 → 图片票据 → 下载」全链路。
 *
 * 这是**有意的联网测试**：源站改版/接口变动时它会立刻红，比人工点一遍更可靠。
 * 默认用 JUnit 跳过；显式传入 `-Dgamanga.network=1` 才访问真实站点。
 */
class KomiicSourceTest {

    @Before
    fun requireNetwork() {
        Assume.assumeTrue("真实源站测试需 -Dgamanga.network=1", System.getProperty("gamanga.network") == "1")
    }

    @Test
    fun `komiic full chain returns downloadable pages`() = runBlocking {
        val source = KomiicSource()

        val results = source.search("剑来")
        assertTrue(results.isNotEmpty(), "搜索无结果")
        val manga = results.first()
        assertTrue(manga.title.isNotBlank() && manga.sourceId.isNotBlank(), "搜索结果缺字段：$manga")

        val detail = source.detail(manga.sourceId)
        assertNotNull(detail, "详情为空")
        assertTrue(detail.coverUrl != null, "详情缺封面")

        val chapters = source.chapters(manga.sourceId)
        assertTrue(chapters.isNotEmpty(), "章节列表为空")
        assertTrue(chapters.map { it.index } == chapters.indices.toList(), "章节顺序/下标不对")
        // 服务端返回顺序是乱的（实测 000,001,239,268,274,002,003），必须自己按 serial 排好
        val serials = chapters.map { it.name }
        val numeric = serials.mapNotNull { s -> Regex("""\d+(\.\d+)?""").find(s)?.value?.toDoubleOrNull() }
        if (numeric.size == serials.size) {
            assertTrue(numeric == numeric.sorted(), "章节没按话数升序：${serials.take(8)}")
        }

        val chapter = chapters.firstOrNull { it.pageCount > 0 } ?: chapters.first()
        // Komiic 对图片票据有服务端「每日配额」，用尽后当天所有取图都返回
        // `Daily image quota exceeded`。这是账号/IP 级的环境限制，不是代码回归，
        // 所以这里跳过而不是判红——但会把原因打出来，避免被误认为"测过了"。
        val pages = try {
            source.pages(chapter.id)
        } catch (e: IllegalStateException) {
            if (e.message.orEmpty().contains("quota", ignoreCase = true)) {
                Assume.assumeNoException("Komiic 图片配额已用尽，未完成取图验证", e)
            }
            throw e
        }
        assertTrue(pages.isNotEmpty(), "图片列表为空（chapter=${chapter.id}）")

        // 票据必须真的能换到图：这是 M3 最容易悄悄坏掉的一环
        val first = pages.first()
        val ticket = first.ticket
        assertTrue(!ticket.isNullOrBlank(), "图片缺票据（Komiic 走 X-Image-Ticket 防盗链）")
        val bytes = Http.getBytes(first.url, mapOf("X-Image-Ticket" to ticket))
        assertTrue(bytes.size > 1024, "图片下载过小：${bytes.size}B")
        assertTrue(first.width > 0 && first.height > 0, "图片缺尺寸信息")
    }

    @Test
    fun `komiic search with nonsense keyword returns empty instead of throwing`() = runBlocking {
        val results = KomiicSource().search("zzzz不存在的漫画名zzzz")
        assertTrue(results.isEmpty(), "无关关键词竟然有结果：${results.map { it.title }}")
    }
}
