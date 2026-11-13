package app.gamanga.desktop.ui.shell

import androidx.compose.ui.text.font.FontFamily
import app.gamanga.desktop.core.AppLog

/**
 * 底部菜单「选中项名称」用的艺术字体（F-70）。
 *
 * 用户 2026-10-26 口径：**修长、有设计感、符合现代审美、可商用**。
 *
 * 选型：**得意黑 Smiley Sans**（`atelier-anchor/smiley-sans`）。
 * - 授权：**SIL Open Font License 1.1**，允许嵌入与随软件分发、允许商用；
 *   许可证原文随包放在 `resources/fonts/SmileySans-OFL.txt`（OFL 要求分发时必须带上）。
 * - 字形：倾斜、窄长、笔画几何化，正好是「修长 + 设计感」，且不是书法体（可读性够）。
 *
 * ⚠️ **上一版用的是系统「华文行楷 STXINGKA」，那个不能用**：微软/华为系统字体只授权
 * 在本机随系统使用，**不允许随第三方软件分发**，商用发布会被追责。已整体替换。
 *
 * 兜底策略（字体缺失不能崩，也不能变成豆腐块）：
 * 1. 优先读包内资源 `/fonts/SmileySans-Oblique.ttf`；
 * 2. 读不到（例如从 IDE 跑、资源没进 classpath）退回系统楷体，再退回 null（= 默认字体）。
 *
 * 为什么返回 `FontFamily?` 而不是给个默认值：`Text(fontFamily = null)` 就是「用主题默认字体」，
 * 比硬塞一个不存在的 FontFamily 更安全——后者在部分平台上会渲染成空白。
 */
internal val SignatureFont: FontFamily? by lazy { loadSignatureFont() }

private fun loadSignatureFont(): FontFamily? {
    bundledSignatureFont()?.let { return it }
    // 兜底：系统楷体。只在本机调试时用得上，正式包一定有内置资源。
    return runCatching {
        val fontDir = java.io.File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts")
        val file = listOf("simkai.ttf", "STKAITI.TTF")
            .asSequence()
            .map { java.io.File(fontDir, it) }
            .firstOrNull { it.isFile }
            ?: return@runCatching null
        FontFamily(
            androidx.compose.ui.text.platform.Font(
                identity = "ga-signature-fallback",
                data = file.readBytes(),
            )
        )
    }.getOrNull()
}

private fun bundledSignatureFont(): FontFamily? = runCatching {
    val stream = SignatureFontResource.javaClass
        .getResourceAsStream("/fonts/SmileySans-Oblique.ttf")
        ?: return@runCatching null
    val bytes = stream.use { it.readBytes() }
    FontFamily(
        androidx.compose.ui.text.platform.Font(identity = "ga-signature", data = bytes)
    )
}.onFailure {
    AppLog.w("UI", "内置艺术字体加载失败，退回系统字体：${it.message}")
}.getOrNull()

/** 只用来取 classloader 的锚点，不实例化。 */
private object SignatureFontResource

/**
 * 名称浮标停留时长。
 *
 * 2026-10-26 用户第一版：「点击过后才出现，过一秒钟自动消失」→ 1s。
 * 2026-10-26 晚用户实测后：「出现到消失的时间缩短 0.4 秒」→ **0.6s**。
 */
internal const val BUBBLE_VISIBLE_MS = 600L
