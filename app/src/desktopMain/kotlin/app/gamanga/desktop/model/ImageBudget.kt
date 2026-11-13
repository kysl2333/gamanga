package app.gamanga.desktop.model

import kotlin.math.sqrt

/**
 * 图片解码的**内存预算**（2026-10-30，用户报「放大缩小会闪退」）。
 *
 * ## 为什么需要它
 *
 * `ImageCache` 曾经是「按原图解码、不设任何尺寸上限」（用户 2026-10-25 明确要求
 * 「改回原图不压缩」）。常规页漫（3000×4000）解码后是 48MB，能接受；但条漫里
 * 经常出现 800×20000 这种**长条扫描**——一张就是 64MB，2000×20000 则直接 160MB。
 * 预加载窗口一次要烘 8~12 张，几张长条叠在一起就把 1.5G 的堆顶穿，表现为
 * 读到某一话突然闪退（Windows 事件日志里是 `awt.dll` 回调里抛异常崩掉，
 * 因为绘制回调里没人接 `OutOfMemoryError`）。
 *
 * ## 口径（**不破坏"原图不压缩"**）
 *
 * 只对**病态尺寸**设上限，正常页面一个像素都不动：
 * - 单边 ≤ [DECODE_MAX_SIDE]（6144）：4K 屏一页最多 3840 高，6144 已经留了 1.6 倍余量；
 * - 总像素 ≤ [DECODE_MAX_PIXELS]（2400 万，约 96MB）。
 *
 * 超限时按**等比**缩到刚好合规——长条图缩的是"离屏分辨率"，而它在屏幕上永远
 * 是按列宽显示的，缩完仍然远高于实际显示密度。
 */
internal const val DECODE_MAX_SIDE = 6144

/** 解码后的最大总像素（宽 × 高）。2400 万 ≈ 96MB（ARGB）。 */
internal const val DECODE_MAX_PIXELS = 24_000_000L

/** 解码目标尺寸。 */
internal data class DecodeSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * 算出一张 [width]×[height] 的图**应该解码成多大**。
 *
 * 纯函数（不碰 Skia、不碰缓存），由 `ImageBudgetTest` 锁住规则：
 * 合规尺寸原样返回；超单边或超像素时取**更严的那个比例**等比缩小，最小 1px。
 */
internal fun decodeTargetSize(
    width: Int,
    height: Int,
    maxSide: Int = DECODE_MAX_SIDE,
    maxPixels: Long = DECODE_MAX_PIXELS,
): DecodeSize {
    if (width <= 0 || height <= 0) return DecodeSize(maxOf(1, width), maxOf(1, height))
    val longSide = maxOf(width, height)
    val sideScale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
    val pixels = width.toLong() * height.toLong()
    val pixelScale = if (pixels > maxPixels) {
        sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
    } else 1f
    val scale = minOf(sideScale, pixelScale)
    if (scale >= 1f) return DecodeSize(width, height)
    return DecodeSize(
        width = maxOf(1, (width * scale).toInt()),
        height = maxOf(1, (height * scale).toInt()),
    )
}
