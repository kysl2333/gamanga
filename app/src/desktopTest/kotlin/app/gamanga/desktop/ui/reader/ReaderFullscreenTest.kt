package app.gamanga.desktop.ui.reader

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阅读器「全屏 / 还原窗口」的几何规则（2026-10-30 第二轮，用户纠正「缩放指的是全屏和窗口」）。
 *
 * 这里锁的是**还原目标**：没有历史矩形时（比如刚启动就点了全屏再还原），
 * 必须给一个屏幕内、居中的 1180×820，而不是跳到 (0,0) 或超出屏幕。
 * 真正的逐帧插值需要真窗口，属于实机验收（`ReaderFullscreen.toggle`）。
 */
class ReaderFullscreenTest {

    @Test
    fun `default bounds are centered on the screen`() {
        val screen = Rectangle(0, 0, 1920, 1080)
        val b = ReaderFullscreen.defaultBounds(screen)
        assertEquals(1180, b.width)
        assertEquals(820, b.height)
        // 左右、上下留白相等
        assertEquals(b.x, screen.width - (b.x + b.width))
        assertEquals(b.y, screen.height - (b.y + b.height))
    }

    @Test
    fun `default bounds respect a screen offset`() {
        // 副屏从 x=1920 起：还原目标要跟着副屏走，不能回主屏
        val screen = Rectangle(1920, 0, 1920, 1080)
        val b = ReaderFullscreen.defaultBounds(screen)
        assertTrue(b.x >= 1920, "还原目标落在副屏内，实际 x=${b.x}")
        assertTrue(b.x + b.width <= 1920 + 1920)
    }

    @Test
    fun `tiny screen never produces negative size`() {
        // 800×600 的小屏：1180×820 放不下，要缩到屏幕内且留 80px 边
        val screen = Rectangle(0, 0, 800, 600)
        val b = ReaderFullscreen.defaultBounds(screen)
        assertTrue(b.width > 0 && b.height > 0)
        assertTrue(b.width <= 800 && b.height <= 600)
        assertEquals(720, b.width) // 800 - 80
        assertEquals(520, b.height) // 600 - 80
    }
}
