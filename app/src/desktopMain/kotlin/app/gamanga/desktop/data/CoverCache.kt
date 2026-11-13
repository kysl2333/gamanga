package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.source.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 书架封面的**磁盘缓存**（R-101 / 用户需求「书架封面缓存到本地」）。
 *
 * 为什么不能只靠 [app.gamanga.desktop.ui.ImageCache]：那是**进程内** LRU，重启即空；
 * 而封面的价值恰恰是「断网时书架别变成一片灰块」。所以落盘到
 * `%LOCALAPPDATA%\Gamanga\covers\<sha1(url)>.img`。
 *
 * 扩展名统一 `.img`：Skia 按魔术数字识别真实格式，扩展名不影响解码
 * （和 R-04 下载页面的做法一致）。
 */
object CoverCache {

    private val dir: File by lazy {
        File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "Gamanga/covers"
        ).apply { mkdirs() }
    }

    private fun fileFor(url: String): File {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$sha1.img")
    }

    /** **同步**查本地缓存（只是一次文件存在性检查，可以在组合期调用）。 */
    fun localUri(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("file:")) return url
        val f = fileFor(url)
        return if (f.length() > 0) f.toPath().toUri().toString() else null
    }

    /**
     * 取封面地址：本地有就直接给本地；没有就下载落盘再给本地；失败退回远端地址。
     *
     * 返回 `file:` URI 时 [app.gamanga.desktop.ui.RemoteImage] 会走本地读取，**不再联网**——
     * 这就是断网时书架仍有图的机制。
     */
    suspend fun resolve(url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext url
        if (url.startsWith("file:")) return@withContext url
        localUri(url)?.let { return@withContext it }
        runCatching {
            val bytes = Http.getBytes(url, emptyMap())
            if (bytes.isEmpty()) return@runCatching null
            val f = fileFor(url)
            // 先写临时文件再原子改名：避免半截文件被下次当成"已缓存"
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return@runCatching null
            }
            f.toPath().toUri().toString()
        }.onFailure {
            AppLog.w("Cover", "cache failed url=${url.take(80)}", it)
        }.getOrNull() ?: url
    }

    /** 当前占用：张数 + 总字节（设置页展示用）。 */
    fun usage(): Pair<Int, Long> {
        val files = dir.listFiles() ?: return 0 to 0L
        var n = 0
        var bytes = 0L
        files.forEach { if (it.isFile) { n++; bytes += it.length() } }
        return n to bytes
    }

    /** 清掉本地封面（设置页「清理封面缓存」）。返回删掉的张数。 */
    fun clearAll(): Int {
        val files = dir.listFiles() ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }
}
