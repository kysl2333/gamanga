package app.gamanga.desktop.data

import app.gamanga.desktop.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-36：自检三态。
 *
 * 关键约定：**搜索返回 0 条不是「健康」**——路径模板/选择器配错时不会抛异常，
 * 只会返回空列表；旧实现把 0 条算活着，配错路径会亮绿灯，自检等于失效。
 */
class SourceHealthTest {

    @Test
    fun `zero hits is suspect not ok`() {
        val r = SourceHealth.report(SourceType.GODA, 0, 12)
        assertEquals(SourceStatus.SUSPECT, r.status)
        assertFalse(r.ok, "0 条不能算健康")
        assertTrue(r.detail.contains("0 条"))
    }

    @Test
    fun `hits mean ok`() {
        val r = SourceHealth.report(SourceType.GODA, 3, 12)
        assertEquals(SourceStatus.OK, r.status)
        assertTrue(r.ok)
    }

    @Test
    fun `empty hits stay green when the source opts out`() {
        // 用户 2026-10-25 口径：Komiic 只要站点能通就算绿灯（搜索空结果属正常）
        val r = SourceHealth.report(SourceType.KOMIIC, 0, 12, emptyIsSuspicious = false)
        assertEquals(SourceStatus.OK, r.status)
        assertTrue(r.ok)
        assertTrue(r.detail.contains("站点可达"), "要说清是「可达但 0 条」：${r.detail}")
    }

    @Test
    fun `status text avoids speculation words`() {
        // UI-SPEC §7.1：不出现「可能/大概/建议」
        val detail = SourceHealth.report(SourceType.MANGABZ, 0, 1).detail
        assertFalse(detail.contains("可能"), "自检文案不能用「可能」：$detail")
    }
}
