package app.gamanga.desktop.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LastBaseline

/**
 * 把一个**非文本元素**（图标、圆钮…）的**底边**声明成它的基线。
 *
 * ## 为什么需要它（R-182，2026-10-30 用户）
 *
 * 用户：「书架页这三个都要底部对齐，对齐全部漫画」。分区标题那一行里混着
 * 「全部漫画」(16sp) + 「2 部」(12sp) + 刷新图标(24dp 圆)。
 *
 * `Row(verticalAlignment = Alignment.Bottom)` 对齐的是**布局盒子的底边**，不是字形的底边：
 * 文字盒子的底边 = 基线 + 降部（descent，中文 16sp 约 3~4dp），字号越小降部越小。
 * 于是「底边对齐」的结果是：小字比大字高、图标比字低 —— 三条底边各差 2~4dp，
 * 肉眼看就是「没对齐」。用户报的正是这个。
 *
 * ## 做法
 *
 * 让所有元素**按基线对齐**：文字自带基线，用 `Modifier.alignByBaseline()` 即可；
 * 图标没有基线，就用本组件把它的底边当成基线报给 `Row`——
 * 于是图标的视觉底边正好落在文字基线上（= 字形底边），三种元素齐平。
 *
 * 只包一层 `Layout`，不测量两次、不改约束，元素尺寸完全不变。
 */
@Composable
fun BaselineAtBottom(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        // 基线 = 底边（height）：`Row` 的 `alignByBaseline()` 会把它和文字的基线拉到同一条线上
        layout(placeable.width, placeable.height, alignmentLines = mapOf(LastBaseline to placeable.height)) {
            placeable.place(0, 0)
        }
    }
}
