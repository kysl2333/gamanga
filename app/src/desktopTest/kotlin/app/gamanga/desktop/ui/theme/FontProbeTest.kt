@file:OptIn(ExperimentalComposeUiApi::class)

package app.gamanga.desktop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 字体落地自检（2026-10-26）。
 *
 * ## 这个测试存在的理由
 *
 * 「Inter + MiSans 混排」能不能成立，**只能看图**，不能读代码猜。实测结论：
 * `FontFamily(Inter×3, MiSans×3)` 渲染中文与 `FontFamily(Inter)` **像素差 0**
 * ——Compose 只按 weight 选一个 typeface，缺字形走**系统**回落，
 * 不会去列表里找第二个家族。所以最终方案是「MiSans 主字体 + [MixedText] 按字符分段给 Inter」。
 *
 * 本测试渲染三行同文对照图到 `screenshots/font-probe.png`：
 * - 第 1 行 MiSans（中文形态基准，应与第 2 行**不同**）
 * - 第 2 行 Inter（中文回落系统字体）
 * - 第 3 行 [MixedText] 混排（中文同第 1 行、拉丁同第 2 行）
 *
 * 断言只锁「资源加载成功 + 混排确实产生了 span」；观感交给人看截图。
 */
class FontProbeTest {

    @Test
    fun `bundled fonts load`() {
        assertNotNull(AppFont.Ui, "MiSans 资源未加载（检查 resources/fonts/MiSans-*.ttf 是否进了 classpath）")
        assertNotNull(AppFont.Latin, "Inter 资源未加载（检查 resources/fonts/Inter-*.ttf）")
    }

    @Test
    fun `mixed text tags latin runs only`() {
        val s = MixedText.of("第 12 话 68%")
        // 「12」和「68%」两段 ASCII → 至少两个 span；中文部分不带 span
        assertTrue(s.spanStyles.size >= 2, "混排应给 ASCII 段打 span，实际 ${s.spanStyles.size}")
        val covered = s.spanStyles.flatMap { it.start until it.end }.toSet()
        assertTrue(0 !in covered, "中文不该被套 Inter")
    }

    @Test
    fun `render font comparison sheet`() {
        val out = File("../screenshots/font-probe.png").absoluteFile
        out.parentFile?.mkdirs()

        val scene = ImageComposeScene(width = 760, height = 300, density = Density(1f)) {
            Column(
                Modifier.size(760.dp, 300.dp).background(Color.White).padding(24.dp)
            ) {
                ProbeRow(AppFont.Ui, mixed = false)
                ProbeRow(AppFont.Latin, mixed = false)
                ProbeRow(AppFont.Ui, mixed = true)
            }
        }
        val image = scene.render()
        out.writeBytes(image.encodeToData()!!.bytes)
        scene.close()
        println("font probe -> ${out.path}")
    }

    @Composable
    private fun ProbeRow(family: FontFamily?, mixed: Boolean) {
        val raw = "深海列车 第12话 ABC 68%"
        Text(
            text = if (mixed) MixedText.of(raw) else AnnotatedString(raw),
            fontFamily = family,
            fontSize = 22.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            color = Color.Black,
        )
    }
}
