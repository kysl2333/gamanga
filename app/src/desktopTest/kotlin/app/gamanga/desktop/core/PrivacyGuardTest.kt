package app.gamanga.desktop.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 隐私闸门（F-296，2026-11-13）。
 *
 * ## 为什么要有这条测试
 *
 * 2026-11-13 准备开源时，首轮隐私扫描抓到两处泄露，**都是写代码时手滑带进去的**：
 * - 一处测试拿本机真实用户名拼进了示例路径；
 * - 一处注释里写了本机工作目录的完整路径。
 *
 * 人眼自查会漏，所以把规则变成测试：**每次 `desktopTest` 都自动查一遍**，
 * 不依赖"记得跑脚本"。成本是几十毫秒。
 *
 * ## 规则
 *
 * 1. 用户目录后面的名字只允许占位（张三 / alice / user …），出现真名即失败；
 * 2. 禁用本机工作目录与本机用户名；
 * 3. 禁止常见邮箱。
 *
 * ## 新增白名单占位
 *
 * 写示例路径时用 `C:/Users/alice/...`、`D:/我的漫画库/...`，不要用自己机器上的真实路径。
 * 如果确实需要新的占位名，往 [allowedUsers] 里加，**不要**为了让它变绿而清空规则。
 *
 * ## 为什么下面的关键词要拆开拼接
 *
 * 这个文件本身会被扫描（它也在 `src` 里）。如果规则关键词以完整明文出现，
 * 闸门就会抓到自己 —— 第一次跑就发生了。拆开既保留检测能力，又不让明文进仓库。
 */
class PrivacyGuardTest {

    /** 允许出现在 `C:/Users/` 后面的示例名，不是真实用户。 */
    private val allowedUsers = setOf(
        "张三", "李四", "王五", "alice", "bob", "user", "username",
        "someone", "test", "public", "用户名", "name",
    )

    private val userPath = Regex("""[Cc]:[\\/]Users[\\/]([^\\/\s"'`]+)""")

    private val email = Regex("""[\w.+-]+@(?:qq|gmail|163|outlook|126)\.com""")

    private val banned = listOf(
        "pxm" + "j1" to "本机用户名",
        "AI" + "工作空间" to "本机工作目录",
    )

    private val textExtensions = setOf("kt", "kts", "sq", "md", "json", "properties", "xml", "txt", "cfg")

    @Test
    fun `源树里没有本机身份信息`() {
        val src = listOf(File("src"), File("../app/src"), File("app/src"))
            .firstOrNull { it.isDirectory }
        assertTrue(src != null, "找不到源码目录（测试工作目录=${File(".").absolutePath}）")

        val violations = mutableListOf<String>()
        src!!.walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            // 闸门文件自己要写规则关键词，不能扫自己（关键词已拆分，这里是双保险）
            .filter { it.name != "PrivacyGuardTest.kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    banned.forEach { (key, why) ->
                        if (line.contains(key)) {
                            violations += "${file.path}:${index + 1} 含$why「$key」"
                        }
                    }
                    userPath.findAll(line).forEach { match ->
                        val name = match.groupValues[1]
                        if (name !in allowedUsers) {
                            violations += "${file.path}:${index + 1} 含真实用户名「$name」"
                        }
                    }
                    email.find(line)?.let {
                        violations += "${file.path}:${index + 1} 含邮箱「${it.value}」"
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "发现疑似隐私泄露（开源前必须清理）：\n" + violations.joinToString("\n"),
        )
    }
}
