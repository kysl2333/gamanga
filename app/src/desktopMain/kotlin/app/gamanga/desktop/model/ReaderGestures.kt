package app.gamanga.desktop.model

import kotlin.math.abs
import kotlin.math.pow

/**
 * 阅读器的纯函数输入映射（滚轮手势 / 布局计算 / 放大后拖拽平移）。
 *
 * 单独放一个文件是为了可测：这些是「用户手势 → 阅读器行为」的全部规则，
 * 由 desktopTest 的 [ReaderGesturesTest] 逐条锁住——UI 层只负责调用。
 */

/**
 * 竖向连续模式下「适应高度」的列宽（单位与传入的视口宽高一致，dp）。
 *
 * 默认显示改为适应高度（用户 2026-10-22 要求，R-30）：列宽 = 视口高 × 宽高比，
 * 普通页漫正好一页一屏的阅读宽度；条漫（超宽长图）按高度算会窄到不可读，
 * 下限兜到 [STRIP_MIN_WIDTH_FRACTION] 视口宽；宽高比 >1 的超宽图则不超过视口宽
 * （此时等价于适应宽度）。
 */
internal fun fitHeightColumnWidth(viewportWidth: Float, viewportHeight: Float, ratio: Float): Float {
    val byHeight = viewportHeight * ratio
    return byHeight.coerceIn(viewportWidth * STRIP_MIN_WIDTH_FRACTION, viewportWidth)
}

/** 条漫类超宽图的最小列宽占比：低于此宽度整条漫画缩到不可读。 */
internal const val STRIP_MIN_WIDTH_FRACTION = 0.4f

/**
 * 竖向模式滚轮每「行」滚动多少 px。
 *
 * Compose 桌面端 scrollDelta.y 的单位是「行」（实测一格 = 1.0）。300px 是用户实测
 * 反馈"滑动速度很慢"后定的（原 100px 一页要滚十多格）；与内嵌浏览器的一格 ≈100px
 * 手感刻意不同——那边是网页，这边是漫画页。
 */
internal const val WHEEL_SCROLL_PX_PER_LINE = 300f

/**
 * 沉浸模式（R-43）中心轻点带占画面宽度的比例：这一带里的轻点切 chrome 显隐，
 * 不再翻页（页模式左右半屏仍各翻各的）。
 */
internal const val CENTER_TAP_FRACTION = 0.3f

/**
 * 轻点是否落在沉浸模式中心带内（R-43）。
 *
 * [x] 是轻点横坐标、[width] 是画面宽度。中心带 = 画面正中间
 * [CENTER_TAP_FRACTION] 宽度——正中央命中，左右边缘翻页区不误触。
 */
internal fun inCenterTapBand(x: Float, width: Float): Boolean =
    width > 0f && abs(x - width / 2f) <= width * CENTER_TAP_FRACTION / 2f

/** 一次滚动/翻页意图越过的是哪个章节边界。 */
internal enum class ChapterEdge { NONE, PREV, NEXT }

/**
 * 条漫模式松手后的惯性衰减系数（每秒保留比例）。
 *
 * 用户 2026-10-23 要求「像手机一样滑动」：拖到一半松手，漫画按出手速度继续滑，
 * 速度指数衰减。0.08 = 一秒后剩 8% 速度；配合典型出手速度
 * 2000~4000 px/s，滑行距离约 800~1600px（距离 ≈ v0 / ln(1/0.08) ≈ v0 / 2.5）。
 */
internal const val FLING_FRICTION_PER_SECOND = 0.08f

/** 出手速度低于此值不触发惯性（px/s）——当一次普通拖动松手。
 *
 * 120 → 400（2026-10-25 日志实测）：鼠标松手速度普遍在 300~2000，
 * 阈值 120 让每一次轻微松手都触发惯性，叠加 2400px 上限就变成「一碰就飞」。
 */
internal const val FLING_MIN_VELOCITY = 400f

/** 惯性衰减到此速度以下停止（px/s），避免永远停不掉的微小移动。
 *
 * 100 → 350（2026-10-25 日志实测）：100px/s 在 300fps 下每帧只走 1px，
 * 尾巴会拖几秒，用户体感是「卡住不动 / 被慢慢拉回」。
 */
internal const val FLING_STOP_VELOCITY = 350f

/** 一帧内速度衰减系数：[FLING_FRICTION_PER_SECOND] 的 dt 次幂。纯函数便于单测。 */
internal fun flingDecayFactor(frictionPerSecond: Float, dtSeconds: Float): Float =
    frictionPerSecond.coerceIn(0f, 1f).pow(dtSeconds.coerceAtLeast(0f))

/** 边界判定容差：打满边界时 consumed 可能剩个位素像素的零头。 */
private const val EDGE_EPSILON = 2f

/**
 * 一次像素滚动请求是否越过了章节边界（竖向连续模式）。
 *
 * 打满边界时 [consumed] 的绝对值会明显小于 [requested]（滚不动了），差值超过
 * [EDGE_EPSILON] 才算"用户确实在往边界外继续滚"——避免贴边时的零头误触发跳话。
 *
 * @param requested 请求滚动的像素（正 = 向下/往后）
 * @param consumed 实际生效的像素
 */
internal fun chapterEdge(requested: Float, consumed: Float): ChapterEdge {
    if (requested == 0f) return ChapterEdge.NONE
    if (kotlin.math.abs(consumed) >= kotlin.math.abs(requested) - EDGE_EPSILON) return ChapterEdge.NONE
    return if (requested > 0f) ChapterEdge.NEXT else ChapterEdge.PREV
}

/**
 * 页模式（单页/双页）一次翻页意图是否越过章节边界。
 *
 * 已经在最后一页还要求"下一页"、或已经在第一页还要求"上一页" = 越界。
 * 空列表（[lastIndex] < 0）不跳——没内容可读时跳话只会连环跳。
 */
internal fun pageChapterEdge(currentIndex: Int, lastIndex: Int, forward: Boolean): ChapterEdge = when {
    lastIndex < 0 -> ChapterEdge.NONE
    forward && currentIndex >= lastIndex -> ChapterEdge.NEXT
    !forward && currentIndex <= 0 -> ChapterEdge.PREV
    else -> ChapterEdge.NONE
}

/**
 * 一次滚轮事件对缩放的增量。
 *
 * 手势（用户 2026-10-22 指定，R-31）：Ctrl+滚轮 或 鼠标侧滚轮（横向滚动）。
 * 上滚/右滚放大，下滚/左滚缩小。返回 null = 这次滚轮不缩放（普通纵向滚轮）。
 */
internal fun wheelZoomStep(scrollY: Float, scrollX: Float, ctrl: Boolean, step: Float): Float? = when {
    ctrl && scrollY != 0f -> if (scrollY < 0f) step else -step
    !ctrl && scrollX != 0f -> if (scrollX > 0f) step else -step
    else -> null
}

/**
 * 页模式下一次纵向滚轮是不是「下一页」。
 *
 * 滚轮向下 = 下一页，与 PageDown/空格同语义；RTL 下语义反转（同方向键规则）。
 * 返回 null = 纵向没有滚动。
 */
internal fun wheelPageForward(scrollY: Float): Boolean? = when {
    // **滚轮方向与阅读方向无关**（2026-10-25 用户实测修正）：
    // 旧实现按 rtl 反转，导致「右往左」模式下往上滚变成下一页。
    // 滚轮是"内容推进"的隐喻：往下滚 = 看后面的内容 = 下一页，两个方向模式都一样；
    // 阅读方向只影响"页在屏幕上摆哪边"，不该影响滚轮。
    scrollY > 0f -> true
    scrollY < 0f -> false
    else -> null
}

/**
 * 页模式（单页/双页）滚轮翻一「步」之后应该停在哪一页。
 *
 * 双页一次翻一对（[spread] 页），结果夹在合法起始页范围内（[lastSpreadStart]
 * 对齐对子边界，不出「半对」单页）；单页就是 ±1。空列表（pageCount=0）时
 * 前进停在 0——没图可翻就不动，等图到了再说（调用方另有空页守卫）。
 *
 * 与边界判定（[pageChapterEdge]）的分工：调用方先判这一下有没有撞章节边界，
 * 没撞才用本函数算目标页——它只回答「翻一步该停在哪」，不管跳不跳话。
 *
 * @param current 当前起始页下标
 * @param pageCount 本章总页数（0 = 还没取到图）
 * @param spread 一屏并排几页（[spreadCount]）
 * @param forward true = 往下一页，false = 往上一页
 */
internal fun wheelPageTurnTarget(current: Int, pageCount: Int, spread: Int, forward: Boolean): Int =
    if (forward) (current + spread).coerceAtMost(lastSpreadStart(pageCount, spread))
    else (current - spread).coerceAtLeast(0)

/**
 * 页模式一屏并排几页（双页 = 2，单页/竖向 = 1）。
 */
internal fun spreadCount(mode: ReaderMode): Int = if (mode == ReaderMode.DOUBLE) 2 else 1

/**
 * 页模式最后一个**合法的起始页**下标。
 *
 * 双页要对齐到「对子」边界：20 页 → 18（最后一对是 18,19）；奇数页时最后一页
 * 单独一屏（19 页 → 18，和实体书最后一页单独露出一致）。单页恒为 pageCount-1。
 * 空列表返回 0。
 *
 * 旧实现直接拿 pageCount-1 当边界：双页下 pageIndex 会被 coerce 到奇数末尾，
 * 屏幕上只画一页（半对），边界判定也和实际 spread 不一致——滚轮翻页手感发飘。
 */
internal fun lastSpreadStart(pageCount: Int, count: Int): Int {
    if (pageCount <= 0) return 0
    val last = pageCount - 1
    return (last - last.mod(count)).coerceAtLeast(0)
}

/**
 * 页模式（单页/双页）放大后单轴可平移的像素上限（用户 2026-10-23「拖着看」）。
 * 图片经 graphicsLayer 以视口中心为锚点放大 [zoom] 倍后，超出视口的部分每侧有
 * (contentSize*zoom - viewportSize)/2 像素。平移量夹在 ±返回值 内，恰好保证
 * 放大图永远铺满视口、露不出画布背景；没有超出时返回 0，平移自动归零。
 *
 * @param zoom 当前缩放（1.0 = 适应高度的原始尺寸）
 * @param contentSize 图片行（Row）的**布局**尺寸（px；graphicsLayer 只做视觉缩放，不随 zoom 变）
 * @param viewportSize 阅读区视口尺寸（px）
 */
internal fun panLimit(zoom: Float, contentSize: Float, viewportSize: Float): Float =
    ((contentSize * zoom - viewportSize) / 2f).coerceAtLeast(0f)

/**
 * 把一次拖拽累积的平移量夹进 [panLimit] 允许的范围。
 *
 * 与 [panLimit] 分开写是为了让 UI 层只有一处夹紧逻辑，且规则可单测。
 */
internal fun clampPan(pan: Float, zoom: Float, contentSize: Float, viewportSize: Float): Float {
    val limit = panLimit(zoom, contentSize, viewportSize)
    return pan.coerceIn(-limit, limit)
}

/**
 * 在章节边界上触发跳话需要累计滚动的像素（约两格滚轮）。
 *
 * 用户实测「滚轮很容易误触发上一话/下一话而不是翻页」：旧逻辑单格滚轮打到
 * 边界就跳。现在要求在边界上同向滚满这个量才跳——误触一格只累积不跳。
 */
internal const val EDGE_JUMP_ACCUM_PX = 600f

/**
 * 章节边界上的「蓄力」累积量（px）。
 *
 * 打到边界时这一轴就滚不动了（[chapterEdge] 判定非 NONE），此时同向继续滚才叫
 * "我要去下一话/回上一话"：累积量超过 [EDGE_JUMP_ACCUM_PX] 才允许跳。
 * 一旦滚回内容区（[edge] 变 NONE）累积作废——不会攒着下次一起跳。
 *
 * @param previous 之前的累积量（正 = 下一话方向，负 = 上一话方向）
 * @param requested 本次请求滚动的像素（符号同 [previous] 的语义）
 * @param edge 本次滚动撞到哪个边界（NONE = 没撞边界，累积作废）
 */
internal fun edgeOverscroll(previous: Float, requested: Float, edge: ChapterEdge): Float =
    if (edge == ChapterEdge.NONE) 0f else previous + requested

/**
 * 预加载窗口：从 [start] 起、最多 [ahead] 页，夹在 `[0, size)` 内。
 *
 * **为什么要有这个纯函数**（2026-10-24 线上崩溃的教训）：
 * 旧写法 `start.coerceIn(0, pages.lastIndex)` 在 `size == 0` 时上界是 **-1**，
 * `coerceIn(0, -1)` 会抛 `IllegalArgumentException: Cannot coerce value to an
 * empty range`。而 `pages` 是 Compose 的 state，`collectLatest` 的 lambda 里读它
 * 拿到的是**最新值**——换话瞬间 `pages` 被清空，正在跑的预加载就炸在滚轮事件线程上。
 *
 * 所以：①窗口计算抽成纯函数，`size <= 0` 一律返回空区间；②调用方必须**先把 pages
 * 快照成本地 val** 再进 lambda，不要在协程里读 state。
 */
internal fun prefetchWindow(start: Int, size: Int, ahead: Int = 8): IntRange {
    if (size <= 0 || ahead <= 0) return IntRange.EMPTY
    val from = start.coerceIn(0, size - 1)
    return from until minOf(from + ahead, size)
}

/**
 * 预加载窗口（**跳过当前页**）：当前页由可见的 RemoteImage 自己加载，
 * 预加载只负责后面 [ahead] 页。避免同一张图被并发请求两次。
 */
internal fun prefetchWindowAfter(current: Int, size: Int, ahead: Int = 8): IntRange {
    if (size <= 0 || ahead <= 0) return IntRange.EMPTY
    // 已在最后一页：没有"后面"可预加载。这里不能交给 prefetchWindow 去夹取——
    // 它会把 start=size 夹回 size-1，等于又把当前页算进窗口。
    if (current >= size - 1) return IntRange.EMPTY
    return prefetchWindow(current + 1, size, ahead)
}
