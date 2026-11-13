package app.gamanga.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阅读器手势/布局纯函数（R-29 滚轮翻页 · R-30 适应高度 · R-31 滚轮缩放）。
 *
 * 这些断言锁住的是用户 2026-10-22 实测指定的行为；UI 层（ReaderScreen）只负责调用。
 * 浮点都用二进制精确的宽高比（0.5/0.75/2.0/0.125），断言值是精确值。
 */
class ReaderGesturesTest {

    // ---- fitHeightColumnWidth：适应高度（R-30）----

    @Test
    fun `normal page fits viewport height`() {
        // 1280×800 视口、宽高比 0.75 → 600：一页一屏的阅读宽度，两侧留暗边
        assertEquals(600f, fitHeightColumnWidth(1280f, 800f, 0.75f))
    }

    @Test
    fun `wide image clamps to viewport width`() {
        // 宽高比 2.0 的宽图按高度算 = 1600 > 视口宽 → 锁到视口宽（等价适应宽度）
        assertEquals(1280f, fitHeightColumnWidth(1280f, 800f, 2.0f))
    }

    @Test
    fun `long strip floors at 40 percent width`() {
        // 8:1 长条按高度算只有 100px → 不可读，兜到 40% 视口宽 = 512
        assertEquals(512f, fitHeightColumnWidth(1280f, 800f, 0.125f))
    }

    // ---- wheelZoomStep：Ctrl+滚轮 / 侧滚轮缩放（R-31）----

    @Test
    fun `ctrl wheel zooms`() {
        assertEquals(0.1f, wheelZoomStep(scrollY = -1f, scrollX = 0f, ctrl = true, step = 0.1f), "上滚放大")
        assertEquals(-0.1f, wheelZoomStep(scrollY = 1f, scrollX = 0f, ctrl = true, step = 0.1f), "下滚缩小")
    }

    @Test
    fun `side wheel zooms`() {
        assertEquals(0.1f, wheelZoomStep(scrollY = 0f, scrollX = 1f, ctrl = false, step = 0.1f), "右滚放大")
        assertEquals(-0.1f, wheelZoomStep(scrollY = 0f, scrollX = -1f, ctrl = false, step = 0.1f), "左滚缩小")
    }

    @Test
    fun `plain vertical wheel never zooms`() {
        assertNull(wheelZoomStep(scrollY = 1f, scrollX = 0f, ctrl = false, step = 0.1f))
        assertNull(wheelZoomStep(scrollY = -1f, scrollX = 0f, ctrl = false, step = 0.1f))
    }

    // ---- wheelPageForward：滚轮翻页（R-29）----

    @Test
    fun `wheel direction is independent of reading direction`() {
        // 2026-10-25 用户实测修正：旧实现按 rtl 反转，右往左模式下「往上滚」变成下一页。
        assertEquals(true, wheelPageForward(1f), "下滚 = 下一页")
        assertEquals(false, wheelPageForward(-1f), "上滚 = 上一页")
        assertNull(wheelPageForward(0f), "没有纵向滚动就没有翻页")
    }

    // ---- chapterEdge：竖向滚/拖到边界跳话 ----

    @Test
    fun `scroll past bottom jumps next chapter`() {
        // 请求滚 300px 只生效 12px = 已经到底，那一下就是"我要下一话"
        assertEquals(ChapterEdge.NEXT, chapterEdge(requested = 300f, consumed = 12f))
    }

    @Test
    fun `scroll past top jumps prev chapter`() {
        assertEquals(ChapterEdge.PREV, chapterEdge(requested = -300f, consumed = -8f))
    }

    @Test
    fun `scroll that lands inside does not jump`() {
        assertEquals(ChapterEdge.NONE, chapterEdge(requested = 300f, consumed = 300f), "正常滚完不跳")
        assertEquals(ChapterEdge.NONE, chapterEdge(requested = -300f, consumed = -299.5f), "差 0.5px 在容差内")
        assertEquals(ChapterEdge.NONE, chapterEdge(requested = 0f, consumed = 0f))
    }

    // ---- pageChapterEdge：页模式翻到边界跳话 ----

    @Test
    fun `page mode flips chapters only at real edges`() {
        assertEquals(ChapterEdge.NEXT, pageChapterEdge(currentIndex = 9, lastIndex = 9, forward = true), "最后一页还往下 = 下一话")
        assertEquals(ChapterEdge.PREV, pageChapterEdge(currentIndex = 0, lastIndex = 9, forward = false), "第一页还往上 = 上一话")
        assertEquals(ChapterEdge.NONE, pageChapterEdge(currentIndex = 5, lastIndex = 9, forward = true), "中间页不跳")
        assertEquals(ChapterEdge.NONE, pageChapterEdge(currentIndex = 5, lastIndex = 9, forward = false))
        assertEquals(ChapterEdge.NONE, pageChapterEdge(currentIndex = 0, lastIndex = -1, forward = true), "空列表不跳（否则连环跳）")
    }

    // ---- wheelPageTurnTarget：滚轮翻一"步"停在哪（2026-10-23 滚轮只能切话的真因修复）----

    @Test
    fun `single page wheel steps one page at a time`() {
        // 单页模式：滚轮逐页翻——从第 0 页连滚三格应到第 3 页，不是直接跳话
        var page = 0
        repeat(3) { page = wheelPageTurnTarget(page, pageCount = 10, spread = 1, forward = true) }
        assertEquals(3, page)
        repeat(2) { page = wheelPageTurnTarget(page, pageCount = 10, spread = 1, forward = false) }
        assertEquals(1, page)
    }

    @Test
    fun `double page wheel steps one spread at a time`() {
        // 双页模式：一次翻一对，且永远落在对子边界（不出"半对"单页）
        var page = 0
        repeat(2) { page = wheelPageTurnTarget(page, pageCount = 20, spread = 2, forward = true) }
        assertEquals(4, page)
        page = wheelPageTurnTarget(page, pageCount = 20, spread = 2, forward = false)
        assertEquals(2, page)
    }

    @Test
    fun `wheel forward clamps to last legal start`() {
        // 20 页双页：最后一对是 18,19——从 16 再翻一格应停在 18，不越界
        assertEquals(18, wheelPageTurnTarget(current = 16, pageCount = 20, spread = 2, forward = true))
        // 奇数页（19）：最后一对 18 单独一屏，同样夹到 18
        assertEquals(18, wheelPageTurnTarget(current = 16, pageCount = 19, spread = 2, forward = true))
        // 已经在最后一对还翻：原地不动（边界判定归调用方，这里只保证不越界）
        assertEquals(18, wheelPageTurnTarget(current = 18, pageCount = 20, spread = 2, forward = true))
    }

    @Test
    fun `wheel backward clamps to first page`() {
        assertEquals(0, wheelPageTurnTarget(current = 0, pageCount = 20, spread = 2, forward = false), "第一页还往上不越界")
        assertEquals(0, wheelPageTurnTarget(current = 1, pageCount = 20, spread = 1, forward = false))
    }

    @Test
    fun `wheel turn target stays put with no pages`() {
        // 取图中 pages 为空：目标页锁在 0，等图到了再说（旧实现把空列表算成
        // "已在边界"，滚轮全部变成蓄力切话——用户实测"滚轮只会切上下话"）
        assertEquals(0, wheelPageTurnTarget(current = 0, pageCount = 0, spread = 2, forward = true))
        assertEquals(0, wheelPageTurnTarget(current = 0, pageCount = 0, spread = 2, forward = false))
    }

    // ---- panLimit / clampPan：页模式放大后拖着看（2026-10-23）----

    @Test
    fun `pan limit is half of the overflow`() {
        // 1000px 宽的图放大 2 倍 = 2000，视口 800 → 每侧超出 600，平移范围 ±600
        assertEquals(600f, panLimit(zoom = 2f, contentSize = 1000f, viewportSize = 800f))
    }

    @Test
    fun `pan limit vanishes when nothing overflows`() {
        assertEquals(0f, panLimit(zoom = 1f, contentSize = 800f, viewportSize = 800f), "恰好铺满")
        assertEquals(0f, panLimit(zoom = 0.5f, contentSize = 1000f, viewportSize = 800f), "缩小只会更小")
        assertEquals(0f, panLimit(zoom = 2f, contentSize = 300f, viewportSize = 800f), "小图放 2 倍也没超出视口")
    }

    @Test
    fun `pan is clamped into the limit`() {
        assertEquals(600f, clampPan(999f, zoom = 2f, contentSize = 1000f, viewportSize = 800f))
        assertEquals(-600f, clampPan(-999f, zoom = 2f, contentSize = 1000f, viewportSize = 800f))
        assertEquals(120f, clampPan(120f, zoom = 2f, contentSize = 1000f, viewportSize = 800f), "范围内的不动")
        assertEquals(0f, clampPan(500f, zoom = 1f, contentSize = 700f, viewportSize = 800f), "没放大时归零、露不出背景")
    }

    // ---- edgeOverscroll：边界跳话要蓄力，防单格滚轮误跳（2026-10-23）----

    @Test
    fun `single notch at edge only charges the jump`() {
        // 单格滚轮（300px）打到边界：只累积，不够跳——用户实测"很容易误触发跳话"
        val accum = edgeOverscroll(previous = 0f, requested = 300f, edge = ChapterEdge.NEXT)
        assertEquals(300f, accum)
        assertTrue(accum < EDGE_JUMP_ACCUM_PX, "一格不够跳")
    }

    @Test
    fun `sustained scroll at edge arms the jump`() {
        // 连续第二格累积满 600px：这才允许跳话（明确的、持续的同向输入）
        val accum = edgeOverscroll(previous = 300f, requested = 300f, edge = ChapterEdge.NEXT)
        assertTrue(accum >= EDGE_JUMP_ACCUM_PX, "连续两格才跳")
    }

    @Test
    fun `edge overscroll resets when leaving the edge`() {
        // 滚回内容区（edge=NONE）累积作废：不会攒到下次误跳
        assertEquals(0f, edgeOverscroll(previous = 500f, requested = 100f, edge = ChapterEdge.NONE))
        // 反方向边界（上一话）从零开始记负值
        assertEquals(-300f, edgeOverscroll(previous = 0f, requested = -300f, edge = ChapterEdge.PREV))
    }

    // ---- inCenterTapBand：沉浸模式中心轻点带（R-43）----

    @Test
    fun `center tap band covers the middle 30 percent`() {
        // 1000px 宽画面：中心带 = 850~150（±150）。正中与带内命中，
        // 带外的左右半屏翻页区不误触——点中间藏控件、点两边翻页，互不抢。
        assertTrue(inCenterTapBand(500f, 1000f), "正中央应命中")
        assertTrue(inCenterTapBand(400f, 1000f), "带内（左半）应命中")
        assertTrue(inCenterTapBand(600f, 1000f), "带内（右半）应命中")
        assertTrue(inCenterTapBand(649f, 1000f), "带边缘内测应命中")
        assertTrue(inCenterTapBand(350f, 1000f), "带边缘（含边界）应命中")
    }

    @Test
    fun `taps outside center band turn pages instead`() {
        assertTrue(!inCenterTapBand(200f, 1000f), "左翻页区不应命中中心带")
        assertTrue(!inCenterTapBand(799f, 1000f), "右翻页区不应命中中心带")
        assertTrue(!inCenterTapBand(651f, 1000f), "带外一像素即翻页区")
        // 零宽/负宽画面（尚未布局）不命中，交给默认翻页行为
        assertTrue(!inCenterTapBand(0f, 0f), "未布局时不应命中")
    }
}

/**
 * 预加载窗口（R-100 回归）。
 *
 * 2026-10-24 线上崩溃：换话时 `pages` 变空，`coerceIn(0, -1)` 抛
 * `Cannot coerce value to an empty range`，炸在 AWT 事件线程上。
 * 这组断言锁住「任何尺寸都不许抛」。
 */
class PrefetchWindowTest {

    @Test
    fun `empty chapter yields empty window instead of throwing`() {
        assertEquals(IntRange.EMPTY, prefetchWindow(0, 0), "0 页必须是空区间，不能抛异常")
        assertEquals(IntRange.EMPTY, prefetchWindow(5, 0), "越界起点 + 0 页也不能抛")
        assertEquals(IntRange.EMPTY, prefetchWindow(0, -3), "负数尺寸按空处理")
    }

    @Test
    fun `window starts at current page and covers ahead pages`() {
        assertEquals(0 until 8, prefetchWindow(0, 16))
        assertEquals(10 until 16, prefetchWindow(10, 16), "接近末尾时窗口被夹住，不能越界")
        assertEquals(15 until 16, prefetchWindow(15, 16))
    }

    @Test
    fun `start is clamped into range`() {
        assertEquals(0 until 8, prefetchWindow(-5, 16), "负起点夹到 0")
        assertEquals(15 until 16, prefetchWindow(999, 16), "超界起点夹到最后一页")
        assertEquals(0 until 3, prefetchWindow(0, 3, ahead = 8), "不足 8 页就只取实际页数")
    }

    @Test
    fun `zero ahead means no prefetch`() {
        assertEquals(IntRange.EMPTY, prefetchWindow(3, 16, ahead = 0))
    }
}

/** 预加载窗口不能包含当前页（否则同一张图会被请求两次）。 */
class PrefetchWindowAfterTest {

    @Test
    fun `window never contains the current page`() {
        for (current in 0 until 20) {
            val w = prefetchWindowAfter(current, 20)
            assertTrue(current !in w, "current=$current 不该出现在窗口 $w 里")
        }
    }

    @Test
    fun `last page has nothing to prefetch`() {
        assertEquals(IntRange.EMPTY, prefetchWindowAfter(19, 20), "最后一页之后没有页")
        assertEquals(IntRange.EMPTY, prefetchWindowAfter(0, 0), "空章节不抛异常")
    }

    @Test
    fun `window starts right after current page`() {
        assertEquals(1 until 9, prefetchWindowAfter(0, 20))
        assertEquals(11 until 19, prefetchWindowAfter(10, 20))
    }
}
