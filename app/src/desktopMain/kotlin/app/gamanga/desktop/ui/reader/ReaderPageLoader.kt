package app.gamanga.desktop.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import app.gamanga.desktop.model.ReaderMode
import app.gamanga.desktop.model.prefetchWindowAfter
import app.gamanga.desktop.source.PageImage
import app.gamanga.desktop.source.requestHeaders
import app.gamanga.desktop.ui.ImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阅读器「取图」层（阶段 3.2 从 ReaderScreen.kt 拆出）。
 *
 * 只管**怎么把图拿到、拿哪些、什么时候让路**：章节页列表的 effect 主体 +
 * 预加载窗口。一个 UI 修饰都不放在这里。
 */

/** 预加载窗口：当前页往后提前烘进 ImageCache 的页数。
 *
 * 用户实测「加载看着难受」要求预加载 5-10 页。8 页是阅读流畅与源站配额间的
 * 折中——Komiic 图片按账号日限额，预加载了不看的页会白烧额度。 */
private const val PREFETCH_AHEAD = 8

/** 预加载并发度（用户要求「同时加载 2-3 页」）。 */
private const val PREFETCH_PARALLEL = 3

/** 往回预加载的页数（往上滑时要用的页）。 */
private const val PREFETCH_BACK = 3

/**
 * 取图失败时展示给用户的文案。异常没有 message 时回落类名——
 * 用户看到的不能是空白一行。
 */
internal fun loadErrorMessage(e: Throwable): String = e.message ?: e::class.simpleName ?: "未知错误"

/**
 * 阅读器「取一话的图片列表」的 effect 主体（从 ReaderScreen 抽出，便于单测）。
 *
 * **为什么必须把取消单独择出来**：换话 / 点重试都会让 `LaunchedEffect(index, reloadKey)`
 * 取消上一次取图协程。而 `runCatching` 连 [CancellationException] 一起吞——被取消的
 * 协程就会以"僵尸"身份继续跑完 effect 的剩余部分，把 `error` / `loading` 写到
 * **已经属于新章节**的状态上：错误页盖住刚出图的页面，表现就是
 * 「切换到（有缓存的）章节也取图失败，点重试就好」。
 *
 * 这正是 R-45 复发的真因：探针改可取消的 `delay` 之后（并发风暴修复），
 * 快速切话必然取消在飞的探针，race 窗口从「偶尔」变成「每次切话」。
 * G社 探针一轮 3~5 秒、全是取消点，所以只在 G社 明显；Komiic 取图是秒回的
 * HTTP 调用，窗口极窄，很少撞上。
 *
 * 约定：取消 → 原样重新抛出（协程正常死亡，一个状态字节都不碰）；
 * 真失败 → [onError]（文案见 [loadErrorMessage]）；成功 → [onSuccess]。
 */
internal suspend fun loadChapterPages(
    load: suspend () -> List<PageImage>,
    onSuccess: (List<PageImage>) -> Unit,
    onError: (String) -> Unit,
) {
    val result = runCatching { load() }
    result.onFailure { e ->
        if (e is CancellationException) throw e
        onError(loadErrorMessage(e))
    }
    result.onSuccess { onSuccess(it) }
}

/** 快速滑过的页不发新请求；缓存命中不等待。仅拷贝源启用。 */
internal const val VISIBLE_IMAGE_SETTLE_MILLIS = 150L

/** LazyColumn 可以提前组合离屏项；必须同时匹配当前页键和真实视口交集。 */
@Composable
internal fun readerPageVisible(listState: LazyListState, index: Int, url: String): Boolean {
    val visible by remember(listState, index, url) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.any { item ->
                item.index == index && item.key == "$index:$url" &&
                    item.size > 0 && item.offset < layout.viewportEndOffset &&
                    item.offset.toLong() + item.size > layout.viewportStartOffset
            }
        }
    }
    return visible
}

/** 拷贝的小窗口：当前屏优先，补足三页；短页同屏超过三页时不牺牲可见内容。 */
internal fun compactReaderWindow(visible: List<Int>, pageCount: Int): List<Int> {
    val current = visible.filter { it in 0 until pageCount }.distinct().sorted()
    if (current.isEmpty()) return emptyList()
    val window = current.toMutableList()
    var next = current.last() + 1
    while (window.size < 3 && next < pageCount) window.add(next++)
    return window
}

/**
 * 每页一个受控等待者，仅在该页退出小窗口时取消。窗口重叠页保留任务，
 * 避免顺序翻页把尚未完成的下一页下载取消重来；已完成/失败页不循环重探。
 */
@Composable
private fun CompactReaderPrefetch(
    pages: List<PageImage>,
    mode: ReaderMode,
    listState: LazyListState,
    currentPage: () -> Int,
) {
    LaunchedEffect(pages, mode, listState) {
        val list = pages
        if (list.isEmpty()) return@LaunchedEffect
        coroutineScope {
            val jobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
            snapshotFlow {
                val visible = if (mode == ReaderMode.VERTICAL) {
                    val layout = listState.layoutInfo
                    layout.visibleItemsInfo.filter { item ->
                        item.index in list.indices && item.key == "${item.index}:${list[item.index].url}" &&
                            item.size > 0 && item.offset < layout.viewportEndOffset &&
                            item.offset.toLong() + item.size > layout.viewportStartOffset
                    }.map { it.index }
                } else {
                    val start = currentPage().coerceIn(0, list.lastIndex)
                    (start until minOf(list.size, start + if (mode == ReaderMode.DOUBLE) 2 else 1)).toList()
                }
                compactReaderWindow(visible, list.size)
            }.collect { window ->
                (jobs.keys - window.toSet()).forEach { jobs.remove(it)?.cancel() }
                window.forEach { i ->
                    if (i !in jobs) jobs[i] = launch {
                        val page = list[i]
                        val headers = page.requestHeaders()
                        if (!ImageCache.has(page.url, headers)) delay(VISIBLE_IMAGE_SETTLE_MILLIS)
                        ImageCache.load(page.url, headers)
                    }
                }
            }
        }
    }
}

/**
 * 预加载窗口（R-100）；R-207拷贝源用当前屏补足三页的小窗口。
 *
 * 旧实现是 `pages.drop(start).take(8).forEach { scope.launch { ImageCache.load() } }`——
 * **每页一个 fire-and-forget 协程，且从不取消**。快速跳页（2 秒 1→60 页）时
 * `snapshotFlow` 每一帧都发射，瞬间压进上百个请求：可视页排在队尾、
 * Komiic 票据配额被沿途几十页吃光 → 一直转圈、点了重试才好。
 *
 * 其它源保持一个常驻 worker：每轮按当前窗口挑未缓存页，批次结束重算窗口；
 * 已发批次不随页码移动反复取消。拷贝源另用紧凑窗口与可见RemoteImage共享等待，
 * 窗口重叠页不中断，退出窗口且没有可见等待者的页立即取消。
 *
 * @param currentPage 当前起始页。**必须传 lambda 而不是值**：它被 `snapshotFlow` 观察，
 *   传值会让 effect 每次翻页重启。
 */
@Composable
internal fun ReaderPrefetch(
    pages: List<PageImage>,
    mode: ReaderMode,
    listState: LazyListState,
    visibleOnly: Boolean = false,
    currentPage: () -> Int,
) {
    // R-207：只补足三页，不恢复前3后8/12的大窗口；退出窗口取消本等待者。
    if (visibleOnly) {
        CompactReaderPrefetch(pages, mode, listState, currentPage)
        return
    }
    val prefetchStart = remember { MutableStateFlow(0) }

    LaunchedEffect(pages, mode) {
        // **必须先把 pages 快照成本地 val**：它是 Compose state，在长生命周期的
        // 循环里读会拿到"未来值"——换话瞬间被清空，`coerceIn(0, lastIndex=-1)`
        // 直接抛 "Cannot coerce value to an empty range"（2026-10-24 23:16 线上崩溃 R-105）。
        val list = pages
        if (list.isEmpty()) return@LaunchedEffect
        // 单一常驻 worker，每轮都**重新按当前窗口**挑最近的一张：
        // 旧实现（每页一个协程 / collectLatest 整批取消）在快速滚动时要么压垮队列、
        // 要么不停取消重来，结果一页也烘不出来（用户实测「预加载像失效了」）。
        // 现在窗口移动不需要取消任何东西——下一轮自然从新窗口的最近页开始。
        while (isActive) {
            val start = prefetchStart.value
            // 当前页由可见的 RemoteImage 自己加载；预加载从**下一页**开始，
            // 同图请求由ImageCache合并，可见页等待时会提升前台优先级。
            // 双页一次吃两张、翻页又最密，窗口给大一点（8 → 12，约 3 屏），
            // 否则连翻两下就把预热吃光，第三下开始等图（用户实测双页最慢）。
            val ahead = if (mode == ReaderMode.DOUBLE) PREFETCH_AHEAD + 4 else PREFETCH_AHEAD
            val window = prefetchWindowAfter(start, list.size, ahead)
            // **往回也预热**（R-98 十一轮）：只往后预热的话，往上滑进入的页永远是"没缓存的新页"，
            // 必然先按猜的比例布局再变高 —— 而顶部项一变高，下面全部位移，就是回弹。
            // 往回预热的页数不用多（3 页 ≈ 1.5 屏），够覆盖一次回翻即可。
            val backCandidates = ((start - 1) downTo (start - PREFETCH_BACK))
                .filter { it in list.indices }
            // **并行烘 3 张**（2026-10-25 用户要求「单页/双页也同时加载 2-3 页」）：
            // 原来一张一张串行，用户翻一屏要等两轮 RTT；条漫之所以显得"同时加载"，
            // 是因为同一屏里多个 RemoteImage 各自并发。这里补上同样的并发度。
            // 先往回（近的优先），再往后
            val batch = (backCandidates + window.toList()).filterNot {
                ImageCache.has(list[it].url, list[it].requestHeaders())
            }.filter {
                ImageCache.canPrefetch(list[it].url, list[it].requestHeaders())
            }.take(PREFETCH_PARALLEL)
            if (batch.isEmpty()) {
                delay(120) // 窗口已烘满：等滚动/翻页把窗口挪走
                continue
            }
            // 可见页在下载/解码时让路：单页/双页每次翻页都要现下两张，
            // 预加载占着连接会让用户觉得「翻页卡、像在等图」（2026-10-24 反馈）。
            if (ImageCache.isForegroundBusy()) {
                delay(40)
                continue
            }
            coroutineScope {
                batch.forEach { i ->
                    launch { ImageCache.load(list[i].url, list[i].requestHeaders()) }
                }
            }
        }
    }

    // ---- 窗口跟随阅读位置（用户 2026-10-23 要求"能预加载 5-10 页"）----
    // 没有它，翻页时新页从 0 开始下载，spinner 转半天很难受。当前页往后
    // PREFETCH_AHEAD 页提前烘进 ImageCache（已缓存的瞬间返回；LRU 256 张，
    // 翻远了自然淘汰）。窗口跟着阅读位置滑：页模式看 pageIndex，竖向看首个可见项。
    // 代价：预加载了不看的页会白烧源站配额（Komiic 图片按账号日限额）——
    // 8 页是读流畅与配额间的折中，用户明确要过这个功能。
    LaunchedEffect(pages, mode) {
        if (pages.isEmpty()) return@LaunchedEffect
        if (mode == ReaderMode.VERTICAL) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { prefetchStart.value = it }
        } else {
            // 双页一次翻 2 页：下一屏从 pageIndex+2 开始，预加载窗口也跟着挪 2，
            // 否则会先去烘「当前屏的第二页」（已经显示出来了），白白占用带宽（双页卡顿的成因之一）。
            val step = if (mode == ReaderMode.DOUBLE) 2 else 1
            snapshotFlow { currentPage() }.collect { prefetchStart.value = it + step - 1 }
        }
    }
}
