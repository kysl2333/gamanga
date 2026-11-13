package app.gamanga.desktop.ui.detail

import app.gamanga.desktop.data.CoverCache
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.model.SourceType
import java.io.File
import java.net.URI

/**
 * 详情页封面「右键 → 保存到本地」（2026-10-27 用户要求）。
 *
 * ## 为什么先问路径、再决定字节从哪来
 *
 * 弹框必须在 **EDT**（Compose 主线程就是 AWT EDT）同步弹，用户取消就什么都不做——
 * 这样「取消」不会白跑一次网络。字节来源则分两条：
 *
 * 1. 封面已经落过盘（[CoverCache.localUri] 给了 `file:`）→ 直接复制文件。
 *    这是常态：打开详情时 `openDetail` 已经预热过封面，用户右键时几乎总能命中，
 *    省一次源站请求（部分源站封面有防盗链/配额）。
 * 2. 没落盘 → 走 [Http.getBytes] 现拉。**不能**用 `RemoteImage` 的内存缓存：
 *    那是解码后的位图，写回去要重新编码，画质与体积都不可控。
 *
 * 失败一律返回 [Result.failure] 由调用方转成一句内联提示，不抛给 UI 线程。
 */

/**
 * 弹系统「另存为」框，返回用户选定的目标文件；取消返回 null。
 *
 * 写法照 `ui/SettingsScreen.kt` 的「更改缓存位置」：切系统 LAF（Windows 资源管理器风格）、
 * `showSaveDialog(null)`（裸 owner，Compose 窗口拿不到稳定句柄，模态行为一样）、
 * 用完还原 LAF（内嵌浏览器周边的 Swing 组件可能受影响）。
 */
internal fun chooseCoverSaveFile(defaultName: String): File? {
    val previousLaf = runCatching { javax.swing.UIManager.getLookAndFeel() }.getOrNull()
    return try {
        runCatching {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        }
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "保存封面"
            // 默认落在用户主目录：留空路径会让对话框从「程序安装目录」打开，
            // 用户不一定有写权限，也不符合「另存为」的直觉
            currentDirectory = File(System.getProperty("user.home") ?: ".")
            selectedFile = File(defaultName)
            isAcceptAllFileFilterUsed = false
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "图片 (*.jpg, *.png)", "jpg", "jpeg", "png"
            )
        }
        if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    } catch (e: Exception) {
        null
    } finally {
        runCatching {
            if (previousLaf != null) {
                javax.swing.UIManager.setLookAndFeel(previousLaf)
            } else {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getCrossPlatformLookAndFeelClassName()
                )
            }
        }
    }
}

/**
 * 把封面字节写到 [target]。
 *
 * @param coverUrl 远端封面地址（null = 这本书连封面地址都没有，直接失败）
 * @param type 源类型：只用于让 [Http] 选对该源的 UA / 代理，不影响本地复制路径
 */
internal suspend fun writeCoverTo(
    coverUrl: String?,
    type: SourceType,
    target: File,
): Result<Unit> = runCatching {
    // 用 error() 而不是 require()：isNullOrBlank 没有 smart-cast 契约，
    // require 之后 coverUrl 仍是 String?，传给 Http 会编译不过
    val url = coverUrl?.takeIf { it.isNotBlank() } ?: error("这本书没有封面地址")
    target.parentFile?.mkdirs()
    val local = CoverCache.localUri(url)
    if (local != null && local.startsWith("file:")) {
        File(URI(local)).copyTo(target, overwrite = true)
    } else {
        val bytes = Http.getBytes(url, emptyMap(), type)
        if (bytes.isEmpty()) error("源站返回了空图片")
        target.writeBytes(bytes)
    }
    // 两个分支的返回值类型不同（copyTo 返回 Long），这里显式收敛成 Unit，
    // 否则 runCatching 会推成 Result<Any>，与声明的 Result<Unit> 不符
    Unit
}

/**
 * 默认文件名：`<书名>.jpg`。
 *
 * Windows 文件名不允许 `\ / : * ? " < > |`，书名里带 `?` 或 `:` 很常见（「Re:Zero」），
 * 不替换的话 JFileChooser 会直接抛 `InvalidPathException`。
 */
internal fun defaultCoverFileName(title: String): String {
    val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "封面" }
    // 过长书名在部分文件系统上会超 255 字节，截一段即可（后缀才是给系统认格式用的）
    return safe.take(80) + ".jpg"
}
