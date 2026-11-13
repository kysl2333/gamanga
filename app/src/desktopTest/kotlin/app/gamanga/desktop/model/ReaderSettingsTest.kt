package app.gamanga.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 阅读设置（F-02）。
 *
 * 这些断言锁住的是**用户能感知的约束**：非法/缺失的 prefs 值不能让阅读器崩或出现怪值，
 * 缩放必须落在可用区间（0.4×~2.0×，两种模式通用——页模式的缩放现在直接作用在图片上，
 * 见 R-32 修复）。滚轮手势与布局计算见 ReaderGesturesTest。
 */
class ReaderSettingsTest {

    @Test
    fun `missing prefs fall back to defaults`() {
        assertEquals(ReaderMode.VERTICAL, ReaderMode.of(null), "没设置过就该是竖向连续")
        assertEquals(ReaderDirection.LTR, ReaderDirection.of(null))
        assertEquals(1.0f, ReaderSettings().zoom)
    }

    @Test
    fun `garbage prefs fall back to defaults`() {
        // prefs 是纯文本，历史版本/手改都可能留下不认识的值
        assertEquals(ReaderMode.VERTICAL, ReaderMode.of("SOMETHING_ELSE"))
        assertEquals(ReaderDirection.LTR, ReaderDirection.of(""))
    }

    @Test
    fun `known prefs are honoured`() {
        ReaderMode.entries.forEach { assertEquals(it, ReaderMode.of(it.name)) }
        ReaderDirection.entries.forEach { assertEquals(it, ReaderDirection.of(it.name)) }
    }

    @Test
    fun `zoom clamps at both ends`() {
        var s = ReaderSettings()
        repeat(20) { s = s.zoomIn() }
        assertEquals(ReaderSettings.ZOOM_MAX, s.zoom, "放大不能超过上限")

        s = ReaderSettings()
        repeat(20) { s = s.zoomOut() }
        assertEquals(ReaderSettings.ZOOM_MIN, s.zoom, "缩小不能低于下限")
    }

    @Test
    fun `zoom round trips through prefs string`() {
        // 存的是 Float.toString()，读回必须还原（否则每次启动缩放都会漂）
        val z = 1.3f
        assertEquals(z, z.toString().toFloatOrNull())
    }
}
