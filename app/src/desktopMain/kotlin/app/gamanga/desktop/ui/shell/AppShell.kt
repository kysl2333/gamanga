package app.gamanga.desktop.ui.shell

import androidx.compose.runtime.Composable

/**
 * 应用外壳（阶段 3.3 拆出，阶段 4 换实现）。
 *
 * 页面内容通过 [content] 插槽传入，壳自己不认识任何具体页面——换壳只改这里和
 * [NormalShell]，页面不用动。
 *
 * **阶段 4（2026-10-26）**：正常模式改用 [NormalShell]（底部浮空 dock + 右上角关窗键 +
 * 无边框窗口）。旧的 [AppNavRail] 左侧竖栏**保留不删**——简洁模式 440×640 还要用它
 * （那里没有底部 dock 的位置，但需要竖向入口），等简洁模式原型定稿再决定去留。
 */
@Composable
internal fun AppShell(
    selected: NavItem,
    onSelect: (NavItem) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    NormalShell(
        selected = selected,
        onSelect = onSelect,
        onClose = onClose,
        content = content,
    )
}
