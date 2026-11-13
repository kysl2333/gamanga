package app.gamanga.desktop.core.webview

import java.awt.image.BufferedImage
import java.nio.ByteBuffer

/**
 * CEF OSR 帧 → BufferedImage 转换（M2）。
 *
 * CEF OnPaint 在 Windows 上给 **BGRA**（每像素 B,G,R,A 四字节，行主序自上而下）。
 * 这里组装成 `TYPE_INT_ARGB`（0xAARRGGBB）的 [BufferedImage]，交给 Compose 的
 * `toComposeImageBitmap()` 绘制。
 *
 * 每帧全量逐像素转换（1280×800 ≈ 100 万像素，约 5-15ms）。登录页帧率足够；
 * M5 阅读器若要更高帧率，再换 Skia `BGRA_8888` 零拷贝路径。
 */
object OsrFrameConverter {

    fun convert(buffer: ByteBuffer, width: Int, height: Int): BufferedImage {
        require(width > 0 && height > 0) { "invalid frame size ${width}x$height" }
        val bytes = ByteArray(width * height * 4)
        buffer.get(bytes)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(width * height)
        var p = 0
        for (i in pixels.indices) {
            val b = bytes[p].toInt() and 0xFF
            val g = bytes[p + 1].toInt() and 0xFF
            val r = bytes[p + 2].toInt() and 0xFF
            val a = bytes[p + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            p += 4
        }
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return image
    }
}
