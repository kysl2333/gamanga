package app.gamanga.desktop.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 应用日志测试（第八轮 #11）。
 *
 * 用 dirOverride 把日志指到临时目录，不碰真实的
 * `%LOCALAPPDATA%\Gamanga\logs`。
 */
class AppLogTest {

    @AfterTest
    fun teardown() {
        AppLog.dirOverride = null
    }

    @Test
    fun `info line carries timestamp, thread, level and message`() {
        val dir = Files.createTempDirectory("applog-test")
        AppLog.dirOverride = dir
        AppLog.i("Test", "hello world")
        val text = dir.resolve("gamanga.log").toFile().readText()
        assertTrue(text.contains("I/Test: hello world"), "实际: $text")
        assertTrue(text.contains("[${Thread.currentThread().name}]"), "要带线程名: $text")
        // 时间戳前缀 yyyy-MM-dd HH:mm:ss.SSS
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[""").containsMatchIn(text))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `error line includes full stack trace`() {
        val dir = Files.createTempDirectory("applog-test")
        AppLog.dirOverride = dir
        AppLog.e("Test", "boom", RuntimeException("kaboom"))
        val text = dir.resolve("gamanga.log").toFile()
            .readText()
        assertTrue(text.contains("E/Test: boom"), "实际: $text")
        assertTrue(text.contains("java.lang.RuntimeException: kaboom"), "要带异常消息: $text")
        assertTrue(text.contains("\tat "), "要带堆栈行: $text")
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `uncaught writes thread name`() {
        val dir = Files.createTempDirectory("applog-test")
        AppLog.dirOverride = dir
        AppLog.uncaught("AWT-EventQueue-0", IllegalStateException("edt boom"))
        val text = dir.resolve("gamanga.log").toFile().readText()
        assertTrue(text.contains("thread=AWT-EventQueue-0"), "实际: $text")
        assertTrue(text.contains("IllegalStateException: edt boom"))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `oversized log rotates to gamanga 1`() {
        val dir = Files.createTempDirectory("applog-test")
        AppLog.dirOverride = dir
        // 写一个超过 5MB 的假日志，再写一条触发轮转
        val log = dir.resolve("gamanga.log").toFile()
        log.writeText("x".repeat(5 * 1024 * 1024 + 10))
        AppLog.i("Test", "after rotate")
        assertTrue(dir.resolve("gamanga.1.log").toFile().exists(), "旧内容要让位给 .1")
        // 新日志只有刚写的那一条（appendText 带尾部换行，lines() 会多一个空行，过滤掉）
        assertEquals(
            1,
            dir.resolve("gamanga.log").toFile().readText().lines().filter { it.isNotBlank() }.size
        )
        dir.toFile().deleteRecursively()
    }
}
