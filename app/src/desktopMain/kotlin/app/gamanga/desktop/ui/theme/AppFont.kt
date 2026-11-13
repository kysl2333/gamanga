package app.gamanga.desktop.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as platformFont
import app.gamanga.desktop.core.AppLog

/**
 * 界面字体（2026-10-26 用户拍板：**Inter + MiSans**）。
 *
 * 用户口径：「2K-4K 先按你推荐的来适配，用 Inter + MiSans 来看整体效果」。
 *
 * | 字体 | 角色 | 授权 | 随包分发 |
 * |---|---|---|---|
 * | **Inter** | 拉丁字母、数字、标点 | SIL OFL 1.1 | ✅（许可证 `Inter-OFL.txt` 随包） |
 * | **MiSans** | 中文 | 小米免费商用 | ✅ 可商用；⚠️ 需在软件内注明「本软件使用了 MiSans 字体」 |
 *
 * 两者都是几何无衬线 + 大 x-height，观感最接近苹方 + SF 的苹果风组合。
 * 许可证原文都在 `app/src/desktopMain/resources/fonts/`，**发版时必须一起带出去**。
 *
 * ## 为什么不用「一个 FontFamily 装两个家族」
 *
 * 实测过（`screenshots/font-probe.png`，`FontProbeTest` 可复现）：
 * `FontFamily(Inter×3, MiSans×3)` 渲染中文的结果与 `FontFamily(Inter)` **像素差 0**。
 * 原因：Compose 先按 weight/style 选**一个** typeface，缺字形时走**系统**回落，
 * 不会去列表里找第二个我们塞进去的字体。所以「Inter 管拉丁、MiSans 管中文」
 * 只能靠**分段**（见 [MixedText]）或合并字体实现。
 *
 * 合并字体试过 `tools/merge-font.py`（fontTools Merger）：Inter 的 `unitsPerEm=2816`、
 * MiSans `=1000`，先 `scale_upem` 对齐后仍报 `TypeError: '>' not supported between
 * 'int' and 'NotImplementedType'`（两字体表结构差异，删可选表也不解决）。
 * 强行合并要手改 glyf，不可复现、难维护 → **不采用**，走分段方案。
 *
 * ## 加载失败不崩
 *
 * 读不到资源（例如从 IDE 跑、资源没进 classpath）时返回 `null`，
 * 调用方 `fontFamily = null` 就是「用主题默认字体」，比硬塞空 FontFamily 安全。
 */
object AppFont {

    /** 中文界面主字体（MiSans）。null = 资源缺失，用系统默认。 */
    val Ui: FontFamily? by lazy { load("ui", ::misansFonts) }

    /** 拉丁/数字专用（Inter）。配合 [MixedText] 用。null = 资源缺失。 */
    val Latin: FontFamily? by lazy { load("latin", ::interFonts) }

    private fun load(tag: String, build: () -> List<Font>?): FontFamily? = runCatching {
        val fonts = build() ?: return@runCatching null
        if (fonts.isEmpty()) return@runCatching null
        FontFamily(fonts)
    }.onFailure {
        AppLog.w("UI", "内置字体($tag)加载失败，退回系统字体：${it.message}")
    }.getOrNull()

    private fun interFonts(): List<Font>? = fontList(
        "ga-inter",
        listOf(
            "fonts/Inter-Regular.ttf" to FontWeight.Normal,
            "fonts/Inter-Medium.ttf" to FontWeight.Medium,
            "fonts/Inter-SemiBold.ttf" to FontWeight.SemiBold,
        ),
    )

    private fun misansFonts(): List<Font>? = fontList(
        "ga-misans",
        listOf(
            "fonts/MiSans-Regular.ttf" to FontWeight.Normal,
            "fonts/MiSans-Medium.ttf" to FontWeight.Medium,
            "fonts/MiSans-Semibold.ttf" to FontWeight.SemiBold,
        ),
    )

    /**
     * 把 (资源路径, 字重) 读成 [Font] 列表。
     *
     * 缺某一个字重不算失败——少一档只是退化成最近邻，不该让整族失效
     * （所以是 `mapNotNull` 而不是 `map`）。
     */
    private fun fontList(idPrefix: String, files: List<Pair<String, FontWeight>>): List<Font>? {
        val loaded = files.mapNotNull { (path, weight) ->
            readResource(path)?.let { bytes ->
                platformFont(identity = "$idPrefix-${weight.weight}", data = bytes, weight = weight)
            }
        }
        return loaded.ifEmpty { null }
    }

    private fun readResource(path: String): ByteArray? = runCatching {
        AppFontAnchor::class.java.classLoader
            ?.getResourceAsStream(path)
            ?.use { it.readBytes() }
    }.getOrNull()

    /** 只用来取 classloader 的锚点，不实例化。 */
    private object AppFontAnchor
}
