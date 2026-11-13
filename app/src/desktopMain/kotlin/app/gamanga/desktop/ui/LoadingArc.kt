package app.gamanga.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 匀速旋转的加载弧线（第十二轮 #1）。
 *
 * 替代 [androidx.compose.material3.CircularProgressIndicator]：后者的
 * indeterminate 动画是分段变速的，在主线程繁忙（滚动+解码同时进行）时
 * 会呈现「转得很奇怪」的卡顿跳跃感（用户实测反馈）。这里用 Canvas 画
 * 一条 270° 圆头弧线，LinearEasing 匀速旋转，帧率掉时也只是变慢不会跳。
 */
@Composable
fun LoadingArc(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
) {
    val transition = rememberInfiniteTransition(label = "loading-arc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        // **必须按短边画圆**（2026-10-24 用户反馈「加载圈是椭圆形」）：
        // Canvas 的实际尺寸受父约束支配——调用方带 fillMaxWidth / 放在 Row 里被
        // 拉伸时，width ≠ height，直接拿 this.size 画弧就成了椭圆。取短边 + 居中，
        // 无论外面给什么约束都是正圆。
        val diameter = minOf(this.size.width, this.size.height)
        if (diameter <= stroke) return@Canvas
        val inset = stroke / 2f
        val left = (this.size.width - diameter) / 2f + inset
        val top = (this.size.height - diameter) / 2f + inset
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
