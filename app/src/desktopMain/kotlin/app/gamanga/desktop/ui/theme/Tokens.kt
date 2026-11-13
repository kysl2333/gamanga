package app.gamanga.desktop.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（阶段 3.1，2026-10-25）。
 *
 * **唯一事实源**：`docs/ui/UI-SPEC.md` 第 4 节（颜色/圆角/字号/间距/材质）+ 第 5 节（动效）。
 * 规格改了先改规格文件，再改这里，最后才改组件。
 *
 * 为什么要有这个文件：阶段 4 要换苹果风 + 毛玻璃，如果颜色还散在 39 个 `Color(0x…)` 里，
 * 换皮就得逐文件改、且必然漏。收进令牌后，换皮 = 改本文件的值。
 *
 * **取值口径**（用户 2026-10-25 拍板）：
 * - 壳子（书架/浏览/历史/下载/设置）→ 用 UI-SPEC 4.1 的值。规格自认还不完善，
 *   后续细化只改这里。
 * - 阅读器 → **保持现有深色值**。规格里还没定阅读器底色（第 6 节暗色也是「后补」），
 *   所以这一组标了 `TODO(阶段4)`，等原型定稿再换。
 */
object Tokens {

    /** 颜色。命名对齐 UI-SPEC 的语义名（`bg/base` → [BgBase]）。 */
    object Color {
        // ---- 壳子：UI-SPEC 4.1 亮色（主） ----

        /** `bg/base` #F6F6F8 —— 窗口底。 */
        val BgBase = Color(0xFFF6F6F8)

        /** `bg/elevated` 白 72% —— 卡片、抽屉。毛玻璃的**不透明近似值**，
         *  阶段 4 换成 `copy(alpha=.72f)` + `Modifier.blur(24.dp)` + 内高光边框。 */
        val BgElevated = Color(0xB8FFFFFF)

        /** 卡片/弹层需要实心时的底（毛玻璃失效时的兜底，如 DropdownMenu 弹层）。 */
        val BgElevatedSolid = Color(0xFFFFFFFF)

        /** `bg/sunken` #EFEFF3 —— 搜索框内底、图片占位。 */
        val BgSunken = Color(0xFFEFEFF3)

        /** `border/hairline` 黑 6% —— 1px 极细分隔。 */
        val BorderHairline = Color(0x0F000000)

        /** `border/highlight` 白 65% —— 玻璃面板顶部高光。 */
        val BorderHighlight = Color(0xA6FFFFFF)

        /** `text/primary` #1C1C1E —— 主文字。 */
        val TextPrimary = Color(0xFF1C1C1E)

        /** `text/secondary` #6E6E73 —— 次要文字、说明。 */
        val TextSecondary = Color(0xFF6E6E73)

        /** `text/tertiary` #A1A1A6 —— 禁用/占位/空状态。 */
        val TextTertiary = Color(0xFFA1A1A6)

        /** `accent` #0A84FF —— 主按钮、进度、选中。 */
        val Accent = Color(0xFF0A84FF)

        /** `accent/soft` 蓝 10% —— 选中底。 */
        val AccentSoft = Color(0x1A0A84FF)

        /** `success` #30B15A —— 已下载、源正常。 */
        val Success = Color(0xFF30B15A)

        /** `warning` #E8A33D —— 源降级、置顶标记。 */
        val Warning = Color(0xFFE8A33D)

        /** `danger` #FF453A —— 错误、危险操作。 */
        val Danger = Color(0xFFFF453A)

        /** `danger/soft` 红 10% —— 危险按钮的 hover 底（关窗键、删除键）。
         *  2026-10-26 新增：原型 `--danger-soft` 已用，规格 4.1 补登。 */
        val DangerSoft = Color(0x1AFF453A)

        /** 亮色底上的「白字」（强调色/危险色按钮的前景）。 */
        val OnAccent = Color(0xFFFFFFFF)

        // ---- 阅读器：现有深色值（UI-SPEC 未定稿，阶段 4 再换） ----
        // 用户 2026-10-25：「阅读器底色也没有决定好」——先原样保留，
        // 只做「给字面量起名字」，一个像素都不动。

        /** TODO(阶段4) 阅读器整页底 —— 现 #111111。 */
        val ReaderBg = Color(0xFF111111)

        /** TODO(阶段4) 顶栏底 —— 现 #1B1B1B。 */
        val ReaderBarBg = Color(0xFF1B1B1B)

        /** TODO(阶段4) 底部控件栏底 —— 现 #151515。 */
        val ReaderControlsBg = Color(0xFF151515)

        /** TODO(阶段4) 阅读器主文字 —— 现 #EEEEEE。 */
        val ReaderText = Color(0xFFEEEEEE)

        /** TODO(阶段4) 控件符号/次级主文字 —— 现 #DDDDDD。 */
        val ReaderTextStrong = Color(0xFFDDDDDD)

        /** TODO(阶段4) 次级文字（章节名、页码） —— 现 #9E9E9E。 */
        val ReaderTextSecondary = Color(0xFF9E9E9E)

        /** TODO(阶段4) 未选中档位文字 —— 现 #8A8A8A。 */
        val ReaderTextMuted = Color(0xFF8A8A8A)

        /** TODO(阶段4) 最弱文字（滚轮提示） —— 现 #6E6E6E。 */
        val ReaderTextDim = Color(0xFF6E6E6E)

        /** TODO(阶段4) 空态文字 —— 现 #BDBDBD。 */
        val ReaderTextSoft = Color(0xFFBDBDBD)

        /** TODO(阶段4) 深底上的错误色（亮红在深底才看得清） —— 现 #FF8A80。 */
        val ReaderError = Color(0xFFFF8A80)

        /** TODO(阶段4) 页码输入框高亮 —— 现 #FFD54F。 */
        val ReaderInputText = Color(0xFFFFD54F)

        /** TODO(阶段4) 页码输入框底 —— 现 #2A2A2A。 */
        val ReaderInputBg = Color(0xFF2A2A2A)

        /** 深底上的浮层底（提示气泡/结束面板） —— 现 #CC000000 / #E6111111。 */
        val ReaderScrim = Color(0xCC000000)
        val ReaderPanel = Color(0xE6111111)

        /**
         * 深底浮层的**边框**（2026-10-30 用户：「沿用菜单栏按钮风格并**保留菜单边框**」）。
         *
         * 一级界面的 `BorderHairline` 是 6% 黑，压在深色阅读器上等于没有；
         * 深底必须反过来用**浅色描边**，12% 白在 #111 上刚好勾出轮廓、不抢内容。
         */
        val ReaderPanelBorder = Color(0x1FFFFFFF)

        /** 深底按钮的 hover / 选中底色（菜单栏按钮那套「浅底浮起」的深色版）。 */
        val ReaderHover = Color(0x1AFFFFFF)

        /** 深底上的强调色（选中的阅读方式/方向、进度条）。 */
        val ReaderAccent = Color(0xFF7FB2FF)

        // ---- 阅读器**亮色菜单栏**（2026-10-30 用户要求「阅读器菜单栏用白色风格」） ----
        //
        // 底色仍是深色画布（[ReaderBg]，看图时最不干扰），但**所有 chrome（顶栏 /
        // 底部悬浮组 / 设置面板 / 全屏键）改成白卡**：与壳子（书架/详情）同一套
        // 「纯白卡片 + 6% 黑细边 + 极淡投影」语言，用户从主界面进阅读器不会有断层感。
        //
        // 为什么不是半透明白：白 72% 压在 #111 上会变成一块灰膜，字也发灰；
        // 阅读器是独立窗口、chrome 是浮在画面上的卡片，实心白才是「浮起」的读法。

        /** 亮色 chrome 的白底（顶栏 / 悬浮组 / 设置面板）。 */
        val ReaderSurface = Color(0xFFFFFFFF)

        /** 白卡描边（6% 黑，与壳子 [BorderHairline] 同口径）。 */
        val ReaderSurfaceBorder = Color(0x14000000)

        /** 白卡上的 hover / 选中浅底（黑 6%）。 */
        val ReaderSurfaceHover = Color(0x0F000000)

        /** 白卡主文字（与壳子 [TextPrimary] 同值，跨页视觉一致）。 */
        val ReaderInk = Color(0xFF1C1C1E)

        /** 白卡次级文字（章节名、页码、未选中选项）。 */
        val ReaderInkSecondary = Color(0xFF6E6E73)

        /** 白卡最弱文字（分组标签、禁用项）。 */
        val ReaderInkTertiary = Color(0xFFA1A1A6)

        /** 白卡强调色（选中胶囊底、选中图标）——沿用壳子主色，不再另起一套蓝。 */
        val ReaderInkAccent = Color(0xFF0A84FF)

        // ---- 兼容：仍被个别屏幕直接用到的中性色 ----

        /** 图片占位底（与 [BgSunken] 同值，语义是「图还没到」）。 */
        val ImagePlaceholder = Color(0xFFEFEFEF)

        /** 无封面时的兜底图标色。 */
        val CoverFallbackTint = Color(0xFF7C6BD6)
    }

    /**
     * 圆角。**值来自 UI-SPEC 4.2**（卡片 16 / 抽屉 20 / 按钮·输入框·封面 10 / 胶囊 999）。
     *
     * 阶段 3 只建令牌、**不迁移现有 18 处 `RoundedCornerShape(4/6/8/10/12.dp)`**：
     * 现值与规格不同（8 → 16 是肉眼可见的变化），迁移必须和阶段 4 的原型一起做，
     * 否则「结构准备」这轮就没法用「视觉零变化」验收。
     */
    object Radius {
        /** 卡片。 */
        val Card = 16.dp

        /** 抽屉、浮空菜单。 */
        val Drawer = 20.dp

        /** 按钮、输入框、封面。 */
        val Control = 10.dp

        /** 胶囊（标签、进度条）。 */
        val Capsule = 999.dp
    }

    /** 间距基数 **4**（UI-SPEC 4.2）。同理：阶段 3 只建令牌，不批量替换 257 处 `.dp`。 */
    object Space {
        val Xs = 4.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 20.dp
        val Xxl = 24.dp
        val Xxxl = 32.dp
    }

    /**
     * 字号（UI-SPEC 4.3 的「角色表」）。
     *
     * 一个角色 = 尺寸 + 行高 + 字重 + 字距，调用点只选角色、不再逐项写 `fontSize`。
     * 硬约束来自 `jakubkrehel/better-typography`（2026-10-25 查证）：
     * - 18px 以下字重必须 ≥400（本项目只用 400/500/600，天然合规）；
     * - 正文行高 1.5~1.6，会折行的文字 ≥1.4；
     * - 说明文字 13px 起、**尽量不低于 12px** → [Micro] 只给角标/单位，不承载正文；
     * - 变动的数字一律 `tabular-nums`（页码/百分比/进度）。
     *
     * 现有代码的取值分布（13sp ×88、12sp ×63、11sp ×24…）与这张表基本吻合，
     * 所以阶段 3 **不批量替换**——替换 200+ 处只是噪音，阶段 4 会重写这些屏。
     */
    object Type {
        /** 页面标题、书名。20 / 26 / 600 / -0.2（20px 标题取 -0.01em）。 */
        val Title = TypeRole(20.sp, 26.sp, FontWeight.SemiBold, (-0.2).sp)

        /** 分区标题、卡片主标题。15 / 20 / 500 / 0。 */
        val Subtitle = TypeRole(15.sp, 20.sp, FontWeight.Medium, 0.sp)

        /** 正文、按钮文字。13 / 20 / 400 / 0（行高 1.54）。 */
        val Body = TypeRole(13.sp, 20.sp, FontWeight.Normal, 0.sp)

        /** 说明、元信息、页码。12 / 17 / 400 / 0。 */
        val Caption = TypeRole(12.sp, 17.sp, FontWeight.Normal, 0.sp)

        /** 只给角标/单位（不得承载正文）。11 / 15 / 400 / 0。 */
        val Micro = TypeRole(11.sp, 15.sp, FontWeight.Normal, 0.sp)
    }

    /** 阴影（UI-SPEC 4.4：`0 8 24 rgba(0,0,0,.08)`）。阶段 4 毛玻璃卡片用。 */
    object Elevation {
        /** 卡片浮起高度。 */
        val Card = 8.dp

        /** 浮空菜单/抽屉。 */
        val Overlay = 12.dp

        /** 阴影不透明度。 */
        const val ShadowAlpha = 0.08f
    }

    /** 动效（UI-SPEC 第 5 节）。阶段 4 的转场/抽屉/卡片抬升按这张表取值。 */
    object ReaderChrome {
        val LoadingHeight = 2.dp
        val SettingsRowHeight = 36.dp
        val ControlSize = 36.dp
        val IconSize = 18.dp
        val BorderWidth = 1.dp
        val ChapterWidth = 340.dp
        val ChapterHeight = 280.dp
        val ChapterRowHeight = 40.dp
        const val PanelMs = 180
    }

    object Motion {
        /** 抽屉推入/推出。 */
        const val DrawerMs = 280

        /** 卡片 hover 抬升。 */
        const val HoverMs = 160

        /** 页面/内容切换。 */
        const val PageSwitchMs = 200

        /** 进度条变化。 */
        const val ProgressMs = 200

        /** 加载骨架呼吸周期。 */
        const val SkeletonMs = 1200

        /** 抽屉曲线 `cubic-bezier(.32,.72,0,1)`——苹果式「快起慢收」。 */
        val DrawerEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    }

    /** 简洁模式窗口尺寸（UI-SPEC：440×640 固定、不可缩放）。阶段 4 窗口形态用。 */
    object Window {
        val SimpleWidth: Dp = 440.dp
        val SimpleHeight: Dp = 640.dp
    }

    /**
     * 封面墙布局（2026-10-26 用户拍板「2K-4K 先按你推荐的来」）。
     *
     * **规则：只按宽度算列数，不做任何整体系数缩放。**
     *
     * 为什么不做系数缩放：Compose Desktop 的 `dp` **已经跟着系统 DPI 缩放走**
     * （Windows 150% 时 1dp = 1.5px）。再乘一个 1.5 的系数就是双重放大，
     * 字号和间距会一起失控。要改的只有一件事——**窗口变宽时列数要不要变多**。
     *
     * 为什么用「目标卡片宽」而不是固定列数：固定 7 列在 4K（3840）上每张封面 520dp，
     * 一屏只看得到 7 本，封面墙就失去意义；反过来固定 12 列在 1280 小窗上每张只有 96dp，
     * 书名会被压成省略号。所以按目标宽度反算列数，再夹在 [MinColumns, MaxColumns]。
     *
     * 实测对照（gap 16 / 内容左右各 40）：
     * - 1280 窗口 → 6 列（卡片 183dp）
     * - 1920 窗口 → 10 列（卡片 169dp）
     * - 2560（2K）→ 12 列（卡片 194dp）
     * - 3840（4K）→ 16 列（卡片 222dp）
     */
    object Grid {
        /** 反算列数用的目标卡片宽（原型 `minmax(154px,1fr)` 的甜点值再放宽一点）。 */
        val CoverTargetWidth: Dp = 176.dp

        /** 卡片最小宽（再窄书名就没法看）。 */
        val CoverMinWidth: Dp = 154.dp

        /** 列数下限：小窗也至少 4 列。 */
        const val MinColumns = 4

        /** 列数上限：4K 也最多 16 列，再多就是邮票。 */
        const val MaxColumns = 16

        /** 卡片之间的横/纵间距（原型 `gap:22px 16px`）。 */
        val CoverGapH: Dp = 16.dp
        val CoverGapV: Dp = 22.dp

        /** 内容区左右留白（原型 `.content{padding:4px 40px 132px}`）。 */
        val ContentPaddingH: Dp = 40.dp

        /**
         * 按可用宽度算列数。
         *
         * @param availableWidth 内容区可用宽（已扣掉左右留白）
         */
        fun columnsFor(availableWidth: Dp): Int {
            val gap = CoverGapH
            val usable = availableWidth.value
            if (usable <= 0f) return MinColumns
            val n = ((usable + gap.value) / (CoverTargetWidth.value + gap.value)).toInt()
            return n.coerceIn(MinColumns, MaxColumns)
        }
    }
}

/**
 * 一个排版角色的完整定义。
 *
 * 为什么把四项绑在一起：如果调用点分别写 `fontSize` / `lineHeight` / `fontWeight` / `letterSpacing`，
 * 同一个角色在不同屏幕就会被配出不同组合（本项目现状：13sp 出现 88 次、行高从不指定）。
 * 绑成角色后，改一处就整体一致。
 */
data class TypeRole(
    /** 字号。 */
    val size: TextUnit,
    /** 行高（建议值见 UI-SPEC 4.3：标题 1.2~1.3 / 正文 1.5~1.6）。 */
    val lineHeight: TextUnit,
    /** 字重。只用 400 / 500 / 600。 */
    val weight: FontWeight,
    /** 字距。正文 0；20px 以上标题取 -0.01em 左右。 */
    val tracking: TextUnit,
) {
    /**
     * 转成 Compose 的 [TextStyle]，同时套上界面字体（[AppFont.Ui]，缺资源时为 null）。
     *
     * 为什么不在这里套 Inter：中英混排要靠 [MixedText] 按字符分段，
     * 家族级回落不生效（见 `AppFont.kt` 文件头）。
     */
    fun toTextStyle(family: FontFamily? = AppFont.Ui): TextStyle = TextStyle(
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        letterSpacing = tracking,
        fontFamily = family,
    )
}
