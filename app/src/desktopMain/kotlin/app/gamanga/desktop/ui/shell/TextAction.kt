package app.gamanga.desktop.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens

/**
 * 文字动作按钮（「查看全部」/「清除全部」/「删除」/「取消」共用）。
 *
 * ## 为什么抽成共用件
 *
 * 用户 2026-10-27：「书架页 查看全部文字按键添加触碰和点击的自然动效」，同一轮还要给
 * 浏览历史的「清除全部」加同一个东西。两处各写一份必然飘（搜索框已经踩过一次），
 * 所以这里是唯一实现。
 *
 * ## 动效（三件事叠起来才「自然」）
 *
 * 1. **触碰**：文字色 160ms 过渡到强调色（危险动作到红色），同时背后浮出一块
 *    同色系的浅底（`accent/soft`），从「一个词」变成「一个按钮」，但不高亮描边、不加阴影；
 * 2. **按下**：整块缩到 **0.94**，90ms 下压（快），松手 220ms 回弹（慢）——
 *    下压快、回弹慢是手感的关键，反过来就是「橡皮泥」；
 * 3. 点击语义用 `clickable`（这是真按钮，不是卡片），所以键盘/无障碍语义天然正确。
 *
 * @param idleColor 静止时的文字色。次要动作传 `TextTertiary`；强调链接（「查看全部」）传 `Accent`。
 */
@Composable
internal fun PageFilter(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.clip(RoundedCornerShape(Tokens.Radius.Control))
        .background(if (selected) Tokens.Color.AccentSoft else if (hovered) Tokens.Color.BgSunken else Color.Transparent)
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = Tokens.Space.Md, vertical = Tokens.Space.Sm)) {
        MixText(text, color = if (selected) Tokens.Color.Accent else Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Caption.size, style = Tokens.Type.Caption.toTextStyle())
    }
}

@Composable
internal fun TextAction(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    idleColor: Color = Tokens.Color.TextTertiary,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val accent = if (danger) Tokens.Color.Danger else Tokens.Color.Accent
    val soft = if (danger) Tokens.Color.DangerSoft else Tokens.Color.AccentSoft
    val color by animateColorAsState(
        targetValue = when {
            hovered || pressed -> accent
            else -> idleColor
        },
        animationSpec = tween(160),
        label = "textActionColor",
    )
    val bg by animateColorAsState(
        targetValue = if (hovered || pressed) soft else Color.Transparent,
        animationSpec = tween(160),
        label = "textActionBg",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(if (pressed) 90 else 220),
        label = "textActionScale",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 2026-10-27 第四轮用户：「这种触碰出现蓝色范围的都做大，要大气点要符合规范的」。
            // 原来 10×5 的内边距 + 8dp 圆角 + Caption 字号，hover 出来的蓝底只有一小块，
            // 像给一个词加了底色；现在是 14×8 + 10dp 圆角 + Body 字号，蓝底是一颗**按钮**。
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Tokens.Space.Lg - Tokens.Space.Xs, vertical = Tokens.Space.Sm),
    ) {
        MixText(
            text = text,
            color = color,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.copy(weight = FontWeight.Medium).toTextStyle(),
        )
    }
}
