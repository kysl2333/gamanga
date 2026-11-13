package app.gamanga.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 导航栏（阶段 3.3 从 GamangaApp.kt 拆出）。
 *
 * **这是普通模式的壳**：左侧竖向导航 + 右侧页面区。阶段 4 的简洁模式不用它
 * （440×640 里没有竖向导航的位置，改成底部浮空菜单），所以这一层只做「搬出来」，
 * 不做任何视觉调整。
 */

/** 导航项。顺序 = 侧栏显示顺序。 */
internal enum class NavItem(val label: String) {
    /**
     * 推荐（2026-10-26）。**排在第一位**是用户要求：「菜单栏区域增加推荐页的按钮并放在菜单栏的最前面」。
     * 但它在 dock 里**不占框内位置**——底部菜单的推荐按钮单独浮在框外左侧（见 `NormalShell`）。
     */
    RECOMMEND("推荐"),
    LIBRARY("书架"),
    BROWSE("浏览"),
    HISTORY("历史"),
    DOWNLOADS("下载"),
    SETTINGS("设置")
}

/** 左侧竖向导航栏。 */
@Composable
internal fun AppNavRail(selected: NavItem, onSelect: (NavItem) -> Unit) {
    Column(
        Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
        )
        NavItem.entries.forEach { item ->
            val active = item == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(item) }
                    .padding(4.dp)
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    fontSize = 13.sp,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
