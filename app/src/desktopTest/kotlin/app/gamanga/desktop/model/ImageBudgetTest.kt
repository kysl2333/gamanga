package app.gamanga.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图片解码内存预算（2026-10-30 用户报「阅读器放大缩小会闪退」）。
 *
 * 规则只有一条：**只对病态尺寸动手，正常页面一个像素都不改**。
 * 这里逐条锁住边界，防止以后有人为了"省内存"把普通页也缩了
 * （用户 2026-10-25 明确要求过「原图不压缩」）。
 */
class ImageBudgetTest {

    @Test
    fun `normal scanned page is untouched`() {
        // 3000×4000 是扫描页的典型尺寸：单边和像素数都在预算内，必须原样返回
        val size = decodeTargetSize(3000, 4000)
        assertEquals(3000, size.width)
        assertEquals(4000, size.height)
    }

    @Test
    fun `4k screenshot is untouched`() {
        // 4K 屏整屏截图 3840×2160：正好是"一页一屏"的上限，不能被缩
        val size = decodeTargetSize(3840, 2160)
        assertEquals(3840, size.width)
        assertEquals(2160, size.height)
    }

    @Test
    fun `ultra long strip is capped by long side`() {
        // 条漫长条 800×20000：解码后 64MB，一张就吃掉四分之一个缓存预算 → 按长边 6144 等比缩
        val size = decodeTargetSize(800, 20000)
        assertEquals(6144, size.height)
        // 宽按同一比例缩（等比，不能变形）：800 × 6144 / 20000 = 245.76 → 245
        assertEquals(245, size.width)
    }

    @Test
    fun `huge square is capped by pixels`() {
        // 8000×8000 = 6400 万像素（256MB）：单边没超 6144？超了；两者取更严的那个
        val size = decodeTargetSize(8000, 8000)
        assertTrue(size.pixels <= DECODE_MAX_PIXELS, "像素必须落回预算内，实际 ${size.pixels}")
        assertTrue(maxOf(size.width, size.height) <= DECODE_MAX_SIDE)
        // 等比：仍然是个正方形
        assertEquals(size.width, size.height)
    }

    @Test
    fun `pixel cap keeps aspect ratio`() {
        // 6000×5000 = 3000 万像素 > 2400 万，但单边 6000 < 6144 → 只有像素比例生效
        val size = decodeTargetSize(6000, 5000)
        assertTrue(size.pixels <= DECODE_MAX_PIXELS, "实际 ${size.pixels}")
        val original = 6000f / 5000f
        val scaled = size.width.toFloat() / size.height.toFloat()
        assertTrue(kotlin.math.abs(original - scaled) < 0.01f, "等比失真：$original vs $scaled")
    }

    @Test
    fun `zero or negative size never returns zero`() {
        // 源站偶尔下发 width/height = 0；返回 0 会让 Skia 建面失败
        val zero = decodeTargetSize(0, 0)
        assertTrue(zero.width >= 1 && zero.height >= 1)
        val negative = decodeTargetSize(-5, 100)
        assertTrue(negative.width >= 1 && negative.height >= 1)
    }
}
