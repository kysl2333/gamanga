package app.gamanga.desktop.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.ui.theme.Tokens

/**
 * 52dp 圆形浮空按钮——**多选条的唯一实现**（R-160 / R-162 / R-164）。
 *
 * 原来书架页与浏览历史页各有一份：书架是两个圆钮（红底垃圾桶 + 白底叉），
 * 历史页却是一条白胶囊 + 「删除 / 取消」文字键。用户 2026-10-29 给了书架那张图
 * 说「历史页多选对齐书架页的按钮风格」，所以把书架那份提出来共用，
 * 历史页不再有自己的一套。
 *
 * 视觉要点（都是用户逐轮改出来的，别自己发明）：
 * - 阴影 6dp + 更透明的环境/聚光色：12dp 硬阴影在浅底上发灰；
 * - 角标**不带底色**，只有白数字 + 一点暗描边（黑底像黑点、白底像白点，用户两次都不满意）；
 * - hover 放大 1.04（140ms），按下不额外缩。
 */
@Composable
internal fun RoundAction(
    icon: ImageVector,
    description: String,
    danger: Boolean,
    enabled: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered && enabled) 1.04f else 1f,
        animationSpec = tween(140),
        label = "roundScale",
    )
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x1A000000),
                spotColor = Color(0x26000000),
            )
            .clip(CircleShape)
            .background(if (danger) Tokens.Color.Danger else Tokens.Color.BgElevatedSolid)
            .border(1.dp, if (danger) Color.Transparent else Tokens.Color.BorderHairline, CircleShape)
            .hoverable(interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (danger) Color.White else Tokens.Color.TextSecondary,
            modifier = Modifier.size(21.dp),
        )
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer { translationX = 6.dp.toPx(); translationY = (-6).dp.toPx() }
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = Tokens.Type.Caption.size,
                    style = Tokens.Type.Caption.copy(weight = FontWeight.SemiBold).toTextStyle()
                        .copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color(0x66000000),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                blurRadius = 2f,
                            )
                        ),
                )
            }
        }
    }
}
