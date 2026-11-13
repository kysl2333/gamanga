package app.gamanga.desktop.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 壳层图标（阶段 4，2026-10-26）。
 *
 * **为什么自己写而不引 `material-icons-extended`**：那一套约 10MB，而我们只需要 7 个图标；
 * 且 Material 图标是 2px 实心/描边混合风，与 UI-SPEC 要求的「统一 1.6~1.8px 描边、20px 网格」
 * 不一致。这里把 `docs/ui/prototype/bookshelf-3x2.html` 里已经定稿的 SVG path 原样搬过来，
 * 用 [PathParser] 解析成 [ImageVector]——**原型与 Compose 共用同一份路径数据**，
 * 以后调图标只改这一处字符串，不用重新对图。
 *
 * 用法：`Icon(DockIcons.Shelf, contentDescription = "书架", tint = ...)`。
 * 描边色固定写 `Color.Black`，实际颜色由 `Icon` 的 `tint` 覆盖。
 */
internal object DockIcons {

    /** 书架：书本轮廓 + 书脊线。 */
    val Shelf: ImageVector = strokeIcon(
        "Shelf",
        "M4 5.5A2.5 2.5 0 0 1 6.5 3H19v18H6.5A2.5 2.5 0 0 1 4 18.5Z",
        "M8 3v18",
    )

    /** 浏览：放大镜。 */
    val Browse: ImageVector = strokeIcon(
        "Browse",
        "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0",
        "M20 20l-3.6-3.6",
    )

    /** 历史：时钟。 */
    val History: ImageVector = strokeIcon(
        "History",
        "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17",
        "M12 7.5V12l3 2",
    )

    /** 下载：向下箭头 + 托盘。 */
    val Download: ImageVector = strokeIcon(
        "Download",
        "M12 4v11",
        "M8 11l4 4 4-4",
        "M5 19h14",
    )

    /** 设置：齿轮。路径直接取自原型（复杂，但已验证可渲染）。 */
    val Settings: ImageVector = strokeIcon(
        "Settings",
        "M12 8.8a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4",
        "M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 " +
            "1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1" +
            "a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1" +
            "a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3" +
            "H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1" +
            "a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1" +
            "a1.6 1.6 0 0 0-1.5 1z",
    )

    /** 窗口关闭键（右上角 X）。 */
    val Close: ImageVector = strokeIcon(
        "Close",
        "M6 6l12 12",
        "M18 6L6 18",
    )

    /** 搜索键（顶栏，与 [Browse] 同形，语义不同故单独命名）。 */
    val Search: ImageVector = Browse

    // ---- 书架页右键菜单 / 多选条（2026-10-26，路径取自原型 bookshelf-3x2.html）----

    /** 开始阅读：实心三角。 */
    val Play: ImageVector = strokeIcon(
        "Play",
        "M7 5l12 7-12 7z",
    )

    /** 目录：三行列表。 */
    val Toc: ImageVector = strokeIcon(
        "Toc",
        "M4 6h16",
        "M4 12h16",
        "M4 18h10",
    )

    /** 勾（标记已读 / 多选圈内的对勾）。 */
    val Check: ImageVector = strokeIcon(
        "Check",
        "M5 13l4 4L19 7",
    )

    /** 多选：方框 + 勾。 */
    val Pick: ImageVector = strokeIcon(
        "Pick",
        "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z",
        "M8.5 12l2.5 2.5L16 9",
    )

    /** 删除：垃圾桶。 */
    val Trash: ImageVector = strokeIcon(
        "Trash",
        "M4 7h16",
        "M9 7V5h6v2",
        "M6 7l1 13h10l1-13",
        "M10 11v6",
        "M14 11v6",
    )

    /** 回车（↵）：搜索栏右端的「回车即搜索」小图标用。 */
    val Enter: ImageVector = strokeIcon(
        "Enter",
        "M9 10L5 14l4 4",
        "M5 14h9a4 4 0 0 0 4-4V6",
    )

    /** 返回（‹）：浏览页详情面板左上角的关闭返回。 */
    val Back: ImageVector = strokeIcon(
        "Back",
        "M15 5l-7 7 7 7",
    )

    /** 置顶：图钉（右键菜单「置顶 / 取消置顶」）。 */
    val Pin: ImageVector = strokeIcon(
        "Pin",
        "M12 3.5a4.5 4.5 0 0 1 4.5 4.5c0 2.6-2 4.4-2 6.5h-5c0-2.1-2-3.9-2-6.5A4.5 4.5 0 0 1 12 3.5z",
        "M12 14.5V21",
        "M9 9h6",
    )

    /** 推荐：四角星（2026-10-26）。 */
    val Sparkle: ImageVector = strokeIcon(
        "Sparkle",
        "M12 3.4l1.9 5.1 5.1 1.9-5.1 1.9-1.9 5.1-1.9-5.1-5.1-1.9 5.1-1.9z",
    )

    /** 地球（搜索结果里「打开该源内嵌浏览器」的小图标）。 */
    val Globe: ImageVector = strokeIcon(
        "Globe",
        "M12 3.2a8.8 8.8 0 1 0 0 17.6a8.8 8.8 0 1 0 0-17.6",
        "M3.4 9h17.2",
        "M3.4 15h17.2",
        "M12 3.2c2.6 2.4 2.6 15.2 0 17.6",
        "M12 3.2c-2.6 2.4-2.6 15.2 0 17.6",
    )

    /** 在浏览器打开：外链箭头。 */
    val External: ImageVector = strokeIcon(
        "External",
        "M14 4h6v6",
        "M20 4l-9 9",
        "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5",
    )

    /**
     * 刷新（圆环 + 右上箭头）：书架「全部漫画」标题右侧的章节刷新按钮用（2026-10-29）。
     *
     * 只在**刷新中**转圈，静态时是一颗普通图标——不引转圈 loading（UI-SPEC 第 5 节禁转圈），
     * 用 `graphicsLayer.rotationZ` 驱动。
     */
    val Refresh: ImageVector = strokeIcon(
        "Refresh",
        "M19.5 12a7.5 7.5 0 1 1-2.2-5.3",
        "M19.6 4.6V9.4H14.8",
    )

    /**
     * 图片（相框 + 太阳 + 山）：右键菜单「自定义封面 / 取消自定义封面」用。
     *
     * 两种状态**共用同一颗图标**（2026-10-27）：语义是「这本的封面图」，
     * 换文案已经说清是设置还是取消；换图标反而要用户重新认一遍。
     */
    val Picture: ImageVector = strokeIcon(
        "Picture",
        "M4.5 4.5h15a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V6a1.5 1.5 0 0 1 1.5-1.5z",
        "M8.5 8a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2",
        "M3.6 16.8l4.6-4.8 3.4 3.6 3.2-2.6 5.6 3.8",
    )

    /**
     * 全屏（四个角向外）：阅读器右下角的全屏切换键（2026-10-30 用户要求）。
     *
     * 只画四个角、不画边框——和 dock 里的图标同一套 1.8px 描边语言，
     * 在深色阅读器里也读得清。
     */
    val Fullscreen: ImageVector = strokeIcon(
        "Fullscreen",
        "M9 4H5.5A1.5 1.5 0 0 0 4 5.5V9",
        "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9",
        "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15",
        "M4 15v3.5A1.5 1.5 0 0 0 5.5 20H9",
    )

    /** 退出全屏（四个角向内）：与 [Fullscreen] 互为两态，同一颗按钮换图不换位置。 */
    val FullscreenExit: ImageVector = strokeIcon(
        "FullscreenExit",
        "M4 9h3.5A1.5 1.5 0 0 0 9 7.5V4",
        "M20 9h-3.5A1.5 1.5 0 0 1 15 7.5V4",
        "M15 20v-3.5a1.5 1.5 0 0 1 1.5-1.5H20",
        "M9 20v-3.5A1.5 1.5 0 0 0 7.5 15H4",
    )

    /**
     * 阅读设置（两条滑杆）：阅读器底部悬浮组的入口图标。
     *
     * 语义是「显示方式 / 排版」，用滑杆而不是齿轮——齿轮在整站已经代表「设置页」，
     * 这里点开的是**阅读器自己的**阅读方式，不能和一级设置页混为一谈。
     */
    val Sliders: ImageVector = strokeIcon(
        "Sliders",
        "M4 8h9",
        "M17 8h3",
        "M4 16h3",
        "M11 16h9",
        "M13.8 6.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6",
        "M7.8 14.2a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6",
    )

    /**
     * 把若干段 SVG path 合成一个描边 [ImageVector]（24×24 视口）。
     *
     * 描边宽度 1.8 对应原型 `stroke-width:1.8`（UI-SPEC 第 8 节：统一 1.6~1.8px）。
     * 颜色写死黑色，靠 `Icon(tint=)` 的 ColorFilter 上色——**不要**在这里传业务色，
     * 否则同一图标在不同状态（选中/未选中）就要建两份。
     */
    private fun strokeIcon(name: String, vararg pathData: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        pathData.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

/** [NavItem] → 对应图标。集中在这里，加导航项时编译器会提醒补映射。 */
internal val NavItem.icon: ImageVector
    get() = when (this) {
        NavItem.RECOMMEND -> DockIcons.Sparkle
        NavItem.LIBRARY -> DockIcons.Shelf
        NavItem.BROWSE -> DockIcons.Browse
        NavItem.HISTORY -> DockIcons.History
        NavItem.DOWNLOADS -> DockIcons.Download
        NavItem.SETTINGS -> DockIcons.Settings
    }
