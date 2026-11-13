package app.gamanga.desktop.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 中英混排：**中文用 MiSans、拉丁字母与数字用 Inter**。
 *
 * ## 为什么需要它
 *
 * Compose 的 `FontFamily` 不会跨家族回落（实测见 [AppFont] 文件头），
 * 所以「Inter + MiSans」只能在**文本内部按字符区间分段**实现：
 * 把连续的可打印 ASCII 段套一个 `SpanStyle(fontFamily = Inter)`，其余留在 MiSans。
 *
 * 合并字体（fontTools Merger）那条路已试过并放弃，原因记在 [AppFont]。
 *
 * ## 用法
 *
 * 界面文本一律用 [MixText]，不要直接用 `Text` —— 直接用的地方数字会变成 MiSans 的
 * 数字，和别处不一致（本项目的数字很多：话数、百分比、页码、时间）。
 *
 * ```kotlin
 * MixText("第 12 话　68%", style = Tokens.Type.Caption.toTextStyle(), color = ...)
 * ```
 */
object MixedText {

    /** 连续可打印 ASCII（空格到 `~`）——这段交给 Inter。 */
    private val AsciiRun = Regex("[\\x20-\\x7E]+")

    /**
     * 把 [text] 转成混排 [AnnotatedString]。
     *
     * [latin] 为 null（字体资源缺失）时原样返回，不产生任何 span。
     */
    fun of(text: String, latin: FontFamily? = AppFont.Latin): AnnotatedString {
        if (latin == null) return AnnotatedString(text)
        // 没有 ASCII 字符就不必建 AnnotatedString，省一次分配（书架一屏几百个文本节点）
        if (text.none { it.code in 0x20..0x7E }) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            val style = SpanStyle(fontFamily = latin)
            AsciiRun.findAll(text).forEach { m ->
                addStyle(style, m.range.first, m.range.last + 1)
            }
        }
    }
}

/**
 * 混排文本组件：等价于 `Text(MixedText.of(text))`，但把常用参数透传出来。
 *
 * 为什么不直接改 `Text` 的默认行为：全局替换 `Text` 影响阅读器等深色界面
 * （那里字号/字重是另一套口径），风险大于收益。新 UI 显式用这个组件。
 */
@Composable
fun MixText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = AppFont.Ui,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    // remember(text) 是必须的：书架一屏几十张卡、每张 2 个文本，不缓存会每帧重建 AnnotatedString
    val annotated = remember(text) { MixedText.of(text) }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}
