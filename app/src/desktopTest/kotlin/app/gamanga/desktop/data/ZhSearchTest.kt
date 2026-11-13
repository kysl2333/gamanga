package app.gamanga.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 书架搜索的匹配规则（简繁归一化 + 拼音首字母）。
 *
 * 用户 2026-10-27 的两条原话就是这里的验收标准：
 * - 「自动简体繁体相互转换，只命中一个字也能搜到」
 * - 「还要首拼音查找，比如 g 就能找到鬼灭之刃，zbwzq 就能找到装备我最强」
 */
class ZhSearchTest {

    @Test
    fun `简体搜繁体书名`() {
        // 库里同时有简体和繁体条目（用户真实书架里就有「旋风管家」和「旋風管家」）
        assertTrue(ZhSearch.matches("旋風管家", "旋风"), "简体输入应命中繁体书名")
        assertTrue(ZhSearch.matches("旋风管家", "旋風"), "繁体输入应命中简体书名")
    }

    @Test
    fun `单字也能命中`() {
        assertTrue(ZhSearch.matches("鬼灭之刃", "灭"))
        assertTrue(ZhSearch.matches("装备我最强", "装"))
    }

    @Test
    fun `拼音首字母命中`() {
        // 用户举的两个例子
        assertTrue(ZhSearch.matches("鬼灭之刃", "g"), "g 应命中鬼灭之刃")
        assertTrue(ZhSearch.matches("鬼灭之刃", "gmz"), "gmz 应命中鬼灭之刃")
        assertTrue(ZhSearch.matches("装备我最强", "zbwzq"), "zbwzq 应命中装备我最强")
        // 首字母串的中间段也算命中（contains 语义）
        assertTrue(ZhSearch.matches("装备我最强", "bwz"))
    }

    @Test
    fun `拼音不该误命中`() {
        assertFalse(ZhSearch.matches("鬼灭之刃", "xyz"))
        assertFalse(ZhSearch.matches("装备我最强", "gmzr"))
    }

    @Test
    fun `空格分词按全部命中`() {
        assertTrue(ZhSearch.matches("鬼灭之刃 第1卷", "鬼灭 卷"))
        assertFalse(ZhSearch.matches("鬼灭之刃", "鬼灭 火影"))
    }

    @Test
    fun `简体字不该被错转（字典错位回归）`() {
        // 2026-10-27 事故：ts-characters 里混进 293 个非 BMP 生僻字，
        // 「每 2 char 一对」的等距解析整体错位，把「装」映射成「裡」，
        // 导致搜「装备」永远搜不到。这条用例锁死「简体字必须原样保留」。
        assertEquals("装备我最强", ZhSearch.normalize("装备我最强"))
        assertEquals("鬼灭之刃", ZhSearch.normalize("鬼灭之刃"))
        assertEquals("旋风管家", ZhSearch.normalize("旋风管家"))
        // 繁体仍然要能转
        assertEquals("旋风管家", ZhSearch.normalize("旋風管家"))
    }

    @Test
    fun `首字母串生成`() {
        // 数字保留、标点跳过
        assertTrue(ZhSearch.initialsOf("鬼灭之刃 第1卷").startsWith("gmzr"))
    }
}
