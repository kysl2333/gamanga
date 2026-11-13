package app.gamanga.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens

/**
 * 页面级右键菜单的共用件（2026-10-27 抽取）。
 *
 * ## 为什么要抽
 *
 * 书架页先写了 [MenuItemRow] 与「按窗口绝对坐标定位的 Popup」这套，浏览历史的右键菜单
 * 需要一模一样的东西。复制一份就会像当初两套搜索框那样慢慢飘（用户 2026-10-27 明确
 * 「他们本身是同一个组件」）。所以这里是**唯一实现**，书架页也改成调它。
 *
 * ## 为什么按「窗口坐标」定位而不是按锚点元素
 *
 * 每张卡各持一个 Popup 会在滚动时残留（书架页踩过）。现在菜单只有一个、挂在页面层，
 * 卡片只上报「按下的窗口坐标」，菜单直接落在那儿。
 */
@Composable
internal fun WindowMenu(
    /** 菜单左上角落点（窗口绝对坐标）。 */
    position: Offset,
    onDismiss: () -> Unit,
    width: Dp = 184.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val point = IntOffset(position.x.toInt(), position.y.toInt())
    val provider = remember(point, layoutDirection) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentBounds: IntSize,
            ): IntOffset = IntOffset(
                // 贴边时把菜单推回窗口内，否则会有一半在屏幕外点不到
                point.x.coerceIn(0, (windowSize.width - popupContentBounds.width).coerceAtLeast(0)),
                point.y.coerceIn(0, (windowSize.height - popupContentBounds.height).coerceAtLeast(0)),
            )
        }
    }
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        popupPositionProvider = provider,
    ) {
        Column(
            Modifier
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.Color.BgElevatedSolid)
                .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .width(width),
            content = content,
        )
    }
}

/** 菜单分隔线。 */
@Composable
internal fun MenuSeparator() {
    Spacer(
        Modifier
            .padding(vertical = 5.dp, horizontal = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Tokens.Color.BorderHairline)
    )
}

/** 菜单项：34dp 高、hover 浅灰底，危险项红字 + 浅红底。 */
@Composable
internal fun MenuItemRow(
    label: String,
    icon: ImageVector,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !hovered -> Color.Transparent
                    danger -> Tokens.Color.DangerSoft
                    else -> Tokens.Color.BgSunken
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) Tokens.Color.Danger else Tokens.Color.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
        MixText(
            text = label,
            color = if (danger) Tokens.Color.Danger else Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.toTextStyle(),
        )
    }
}
