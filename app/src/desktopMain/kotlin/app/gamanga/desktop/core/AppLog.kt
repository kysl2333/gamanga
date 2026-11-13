package app.gamanga.desktop.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 应用日志（第八轮 #11，用户要求「能看到问题操作并进行修复」）。
 *
 * **为什么不是 println**：实测 `System.err` 被多线程并发写时，30 个 EDT 异常的
 * `printStackTrace` 互相覆盖，日志里只剩 30 句 `Exception in thread "AWT-EventQueue-0"`
 * 前缀、**一条堆栈都不剩**（2026-10-23 用户实测期间出现，logs/app-run.log 第 41 行）。
 * 本类用一把锁 + 每次落盘，保证一条日志的「头部 + 堆栈」连续完整。
 *
 * **落点**：`%LOCALAPPDATA%\Gamanga\logs\gamanga.log`（与 DB、下载缓存同根，分发后
 * 位置稳定；开发时 gradle 的 stdout/stderr 另有 logs/app-run.log）。
 *
 * **隐私**：只记 mangaId / chapterId / 章节名 / 错误信息，**不记 cookie 与请求体**。
 *
 * 用法：`AppLog.i("Download", "enqueue ${chapters.size} chapters for $mangaId")`。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val MAX_BYTES = 5L * 1024 * 1024 // 超过 5MB 轮换成 .1

    private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    /** 测试可注入目录；null = 用默认位置。 */
    @Volatile
    internal var dirOverride: Path? = null

    private val dir: Path
        get() = dirOverride
            ?: Paths.get(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                "Gamanga",
                "logs"
            )

    private val file: File
        get() = dir.resolve("gamanga.log").toFile()

    /** 当前日志文件路径（设置页「打开日志位置」可展示）。 */
    fun logPath(): String = file.absolutePath

    fun i(tag: String, msg: String) = write("I", tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /**
     * 未捕获异常统一入口（Main.kt 的 default handler 调它）。
     *
     * AWT 的 `EventDispatchThread` 对 dispatch 中的异常有自己的兜底打印，
     * 不一定经过 default handler——所以关键路径（下载协程、拖拽滚动协程等）
     * 各自 try/catch 后也调 [e]，双保险。
     */
    fun uncaught(thread: String, t: Throwable) {
        write("E", "Uncaught", "thread=$thread ${t::class.simpleName}: ${t.message}", t)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(ts))
            append(" [").append(Thread.currentThread().name).append("] ")
            append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) {
                append('\n')
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append(sw)
            }
        }
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                rotateIfBig()
                file.appendText(line + "\n")
            }.onFailure {
                // 日志写不出去（磁盘满/权限）不能把应用搞崩——静默降级到 stderr
                System.err.println("$TAG: write failed: ${it.message}")
            }
        }
    }

    /** 超过 5MB 就把当前日志让位给 gamanga.1.log（只留一代，避免无限增长）。 */
    private fun rotateIfBig() {
        val f = file
        if (f.length() > MAX_BYTES) {
            runCatching {
                val old = dir.resolve("gamanga.1.log").toFile()
                if (old.exists()) old.delete()
                Files.move(f.toPath(), old.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
