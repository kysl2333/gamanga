package app.gamanga.desktop.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 注释卫生门禁。
 *
 * 公开仓库里的注释只保留技术信息：为什么这样写、踩过什么坑、有什么约束。
 * 内部台账编号、开发批次号、带日期的用户反馈、内部文档路径都属于开发过程，
 * 不应该出现在公开代码里。
 *
 * 存量已用 `tools/clean_comments.py` 批量清洗；本测试负责**防止回潮** ——
 * 以后写代码时手滑带上这些痕迹，`desktopTest` 会直接失败。
 */
class CommentHygieneTest {

    /**
     * 违规模式与说明。
     *
     * 注意规则要**精确**：单说「批次」是技术术语（分页批次、asHx 批次数），只有
     * 「批次 + 数字」才是开发批次号；`UTF-8` 里的 `` 也不是台账编号，代码里显式排除。
     */
    private val rules = listOf(
        Regex("""[（(]\s*[RFTP]-\d+""") to "内部台账编号",
        Regex("""批次\s*\d+""") to "开发批次号",
        Regex("""第[一二三四五六七八九十\d]{1,3}轮""") to "开发轮次编号",
        Regex("""\d{4}-\d{2}-\d{2}""") to "开发日期记录",
        Regex("""用户\s*20\d{2}-\d{2}-\d{2}""") to "开发日期记录",
        Regex("""用户说""") to "开发过程叙述",
        Regex("""docs/(plan|sources|agents)/""") to "内部文档引用",
    )

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    @Test
    fun `注释里没有内部开发痕迹`() {
        val src = listOf(File("src"), File("../app/src"), File("app/src"))
            .firstOrNull { it.isDirectory }
        assertTrue(src != null, "找不到源码目录（工作目录=${File(".").absolutePath}）")

        val violations = mutableListOf<String>()
        src!!.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts") }
            // 规则词以明文写在本文件里，扫自己必然误报
            .filter { it.name != "CommentHygieneTest.kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (!isComment(line)) return@forEachIndexed
                    if (line.contains("UTF-8")) return@forEachIndexed
                    rules.forEach { (regex, why) ->
                        if (regex.containsMatchIn(line)) {
                            violations += "${file.path}:${index + 1} 含$why：${line.trim().take(80)}"
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "注释里有内部开发痕迹（公开前必须清理，或用 tools/clean_comments.py）：\n" +
                violations.joinToString("\n"),
        )
    }
}
