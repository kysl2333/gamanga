package app.gamanga.desktop.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.BrowseHistoryEntry
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.shell.DockMetrics
import app.gamanga.desktop.ui.shell.RoundAction
import app.gamanga.desktop.ui.shell.MenuItemRow
import app.gamanga.desktop.ui.shell.TextAction
import app.gamanga.desktop.ui.shell.MenuSeparator
import app.gamanga.desktop.ui.shell.UndoBar
import app.gamanga.desktop.ui.shell.UndoRequest
import app.gamanga.desktop.ui.shell.WindowMenu
import app.gamanga.desktop.ui.shell.rememberUndoState
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens

/**
 * 浏览页的**首屏**：浏览历史（F-128，2026-10-27；F-129 补右键菜单与清除）。
 *
 * ## 用户口径（原话）
 *
 * 「进入浏览页后，界面中央只展示浏览历史，包括在浏览页点过的漫画源封面，
 * 以及在浏览页点过的书架漫画封面」「初始画面必须干净，搜索相关结果元素默认不显示」
 * 「点击菜单栏浏览页即进入该浏览历史画面；清空搜索栏文字后同样返回该画面」。
 *
 * 第二轮补充（F-129）：「浏览历史右键点击封面出现，进入详情页、多选、删除记录的菜单选项
 * 浏览历史多少部后面增加一个清除全部的文字按键」。
 *
 * ## 设计依据（现有规范，不另起一套）
 *
 * - 布局与书架页的封面墙一致：`LazyVerticalGrid` + `Tokens.Grid.columnsFor` 反算列数，
 *   左右 `ContentPaddingH`，顶 96dp（拖拽带 36 + 顶栏 60），底 88dp（让出 dock）；
 * - 卡片直接复用搜索结果那张 [SearchCard]（3:4 封面 + 标题 + 副标题，hover 抬 3dp 同书架），
 *   副标题放**源站名**——历史里同一本书可能来自不同源，标出来才知道点的是哪个；
 * - 右键菜单、多选勾选圈、危险项配色全部沿用书架页那一套（[MenuItemRow] / `SearchCard` 的
 *   `multi`+`picked`），两页的手感必须一致。
 *
 * ## 回撤（F-140，2026-10-27）
 *
 * 用户口径：「浏览页『浏览历史』旁的『清空全部』需提供回撤操作；书架与浏览页的删除动作
 * 同样支持回撤」。三条删除路径（右键「删除记录」/ 多选「删除」/ 「清除全部」）都走
 * 下面那个 `deleteWithUndo`：**先抓快照 → 再删 → 最后弹 [UndoBar]**。
 *
 * 顺序是硬性的：`onDelete` / `onClearAll` 会同步写 store，`items` 由 StateFlow 推上来，
 * 删完当帧列表里就没有这几条了——事后再想从 `items` 里捞快照只会捞到空。
 *
 * 回撤条**不能挂在「列表非空」分支里**：清空全部之后列表就是空的，挂进去等于永远不显示。
 * 所以整页最外层是一个不依赖 `items` 的 `Box`，空态 / 网格都是它的子节点，回撤条与多选条
 * 同级叠在底部。
 */
@Composable
fun BrowseHistoryPane(
    items: List<BrowseHistoryEntry>,
    onOpen: (BrowseHistoryEntry) -> Unit,
    /** 删除若干条（按 [BrowseHistoryEntry.key]）。 */
    onDelete: (List<String>) -> Unit = {},
    /** 清除全部。 */
    onClearAll: () -> Unit = {},
    /** 回撤：把删掉的条目放回去（由页面层转给 store.restore）。 */
    onRestore: (List<BrowseHistoryEntry>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 多选 / 右键菜单都是**页面级**的一个（每张卡各持一个 Popup 会在滚动时残留）
    var multi by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var ctx by remember { mutableStateOf<CtxTarget?>(null) }
    // 三条删除路径共用一份回撤状态：连续删两次时新提示覆盖旧的并重置 6 秒倒计时
    val undo = rememberUndoState()

    /**
     * 三条删除路径的唯一入口：**先抓快照，再删，最后弹回撤条**。
     *
     * 快照是 `targets` 这批 `BrowseHistoryEntry` 本身（不是 key）——回撤要的正是
     * 标题 / 封面 / 源站 / `visitedAt`，只留 key 就补不回来了。
     */
    fun deleteWithUndo(targets: List<BrowseHistoryEntry>) {
        if (targets.isEmpty()) return
        onDelete(targets.map { it.key })
        undo.show(UndoRequest("已删除 ${targets.size} 部") { onRestore(targets) })
    }

    // 外层 Box 与 items 是否为空无关：清空全部之后要靠它继续承载回撤条
    Box(modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            EmptyHistory()
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val available = maxWidth - Tokens.Grid.ContentPaddingH * 2
                val columns = Tokens.Grid.columnsFor(available)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Tokens.Grid.ContentPaddingH,
                        end = Tokens.Grid.ContentPaddingH,
                        top = 96.dp,
                        bottom = 88.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapH),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Grid.CoverGapV),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sec-browse-history") {
                        HistoryHeader(
                            count = items.size,
                            multi = multi,
                            onClearAll = {
                                // 快照必须在清空之前取（清完 items 就是空列表了）
                                val snapshot = items
                                multi = false
                                picked = emptySet()
                                onClearAll()
                                undo.show(UndoRequest("已清空浏览历史") { onRestore(snapshot) })
                            },
                        )
                    }
                    items(items, key = { it.key }) { entry ->
                        SearchCard(
                            title = entry.title,
                            coverUrl = entry.coverUrl,
                            placeholderColor = Tokens.Color.BgSunken,
                            subtitle = sourceNameOf(entry.source),
                            multi = multi,
                            picked = entry.key in picked,
                            onClick = {
                                if (multi) {
                                    picked = if (entry.key in picked) picked - entry.key
                                    else picked + entry.key
                                } else {
                                    onOpen(entry)
                                }
                            },
                            onContextMenu = { pos -> if (!multi) ctx = CtxTarget(entry, pos) },
                        )
                    }
                }
            }
        }

        // 多选操作条：浮在 dock 之上（与书架页 `MultiActions` 同位置同形态）
        //
        // 2026-10-29 用户第三次报同一现象（前两次修的是书架页）：「历史页右键多选后的界面
        // 按钮藏在菜单栏下面」。根因就是这里写死 `padding(bottom = 18.dp)`——18dp 连 dock
        // 的顶边都够不着。现在和书架页读**同一份实测几何**：纵向 = dock 顶边 + 一段间距，
        // 横向 = dock 中心线偏移。以后 dock 怎么改，两页一起动。
        MultiBar(
            visible = multi,
            count = picked.size,
            onDelete = {
                // 快照同样在删除之前取；picked 一清就查不到这批条目了
                val targets = items.filter { it.key in picked }
                picked = emptySet()
                multi = false
                deleteWithUndo(targets)
            },
            onExit = {
                multi = false
                picked = emptySet()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = DockMetrics.floatingBottom)
                .offset(x = DockMetrics.centerOffsetX),
        )

        // 回撤条与多选条同一个位置（删除都会退出多选态），所以多选态下直接不挂：
        // 否则 `MultiBar` 那 200ms 的淡出会和回撤条的浮起叠在同一处。
        if (!multi) {
            UndoBar(
                state = undo,
                // 只给对齐：底部让位（dock 高度 + 间距）由 `UndoBar` 内部统一加，
                // 这里再给 bottom padding 会叠加成两份，条子被顶到半空
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        ctx?.let { target ->
            WindowMenu(position = target.position, onDismiss = { ctx = null }) {
                MenuItemRow("进入详情页", DockIcons.Play, danger = false) {
                    ctx = null
                    onOpen(target.entry)
                }
                MenuItemRow("多选", DockIcons.Pick, danger = false) {
                    ctx = null
                    multi = true
                    picked = setOf(target.entry.key)
                }
                MenuSeparator()
                MenuItemRow("删除记录", DockIcons.Trash, danger = true) {
                    ctx = null
                    deleteWithUndo(listOf(target.entry))
                }
            }
        }
    }
}

/** 右键菜单的目标（条目 + 按下的窗口坐标）。 */
private data class CtxTarget(val entry: BrowseHistoryEntry, val position: Offset)

/** 分区标题：浏览历史 + 计数 + 右侧「清除全部」。与书架页 `SectionHeader` 同一套字号/间距。 */
@Composable
private fun HistoryHeader(count: Int, multi: Boolean, onClearAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MixText(
            text = "浏览历史",
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.copy(weight = FontWeight.SemiBold).toTextStyle(),
        )
        Spacer(Modifier.width(10.dp))
        MixText(
            text = "$count 部",
            color = Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Caption.size,
            style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
        )
        Spacer(Modifier.weight(1f))
        // 多选态下藏掉「清除全部」：那时用户是在挑几条删，不该有个一键清空的按钮在旁边
        if (!multi) {
            TextAction(text = "清除全部", danger = false, onClick = onClearAll)
        }
    }
}

/**
 * 多选操作条：**与书架页同一套两个圆钮**（R-164，2026-10-29 用户）。
 *
 * 用户给了书架那张图（红底垃圾桶 + 白底叉）说「历史页多选对齐书架页的按钮风格」。
 * 原来这里是白胶囊 + 「已选 N 部 / 删除 / 取消」三个文字键——同一功能两种长相。
 * 现在直接复用 `ui/shell/RoundAction`：删掉的份数挂在垃圾桶右上角标上，
 * 不再是左侧那行「已选 N 部」文字（书架页也没有这行）。
 */
@Composable
private fun MultiBar(
    visible: Boolean,
    count: Int,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200, easing = Tokens.Motion.DrawerEasing),
        label = "multiBarAppear",
    )
    if (appear <= 0.01f) return
    val enabled = visible && count > 0
    Row(
        modifier.graphicsLayer {
            alpha = appear
            // 与书架页一致：从下方 8dp 浮起（书架用 translationY，这里也用它，别用缩放）
            translationY = (1f - appear) * 8.dp.toPx()
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(
            icon = DockIcons.Trash,
            description = "删除所选",
            danger = enabled,
            enabled = enabled,
            badge = if (count > 0) count.toString() else null,
            onClick = onDelete,
        )
        RoundAction(
            icon = DockIcons.Close,
            description = "退出多选",
            danger = false,
            enabled = visible,
            badge = null,
            onClick = onExit,
        )
    }
}

/**
 * 空历史：整页居中一句话。
 *
 * 用户要求「初始画面必须干净」——所以这里**只有一句话**，不放插图、不放按钮、
 * 不放「去搜索」引导（搜索框就在上面，本来就在眼前）。
 *
 * `modifier` 默认 `Modifier`：现在它挂在 [BrowseHistoryPane] 最外层那个 `Box` 里，
 * 外部 modifier 已经由外层应用过一次，再传进来会重复施加 padding 之类的修饰。
 */
@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MixText(
            text = "在浏览页点开过的漫画会出现在这里",
            color = Tokens.Color.TextTertiary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.toTextStyle(),
        )
    }
}
