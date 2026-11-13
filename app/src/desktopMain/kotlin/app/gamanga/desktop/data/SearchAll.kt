package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.SourceAdapter
import app.gamanga.desktop.source.SourceManga
import app.gamanga.desktop.source.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** 单个源的一次搜索结果（F-43）。 */
sealed interface SourceSearchState {
    /** 还在搜（拷贝漫画要 6~8 秒，UI 得先给个转圈）。 */
    data object Loading : SourceSearchState

    data class Success(val items: List<SourceManga>, val elapsedMs: Long) : SourceSearchState

    data class Failed(val message: String, val elapsedMs: Long) : SourceSearchState
}

/**
 * 多源并发搜索（F-43）。
 *
 * 用户 2026-10-25 口径：「各源独立一起搜，再点对应源看结果」——所以这里**不合并结果**，
 * 而是按源逐个发射状态，UI 按源分 tab 展示。
 *
 * 设计要点：
 * - **先到先显示**：每个源独立协程，谁先回来先发谁。拷贝漫画要 6~8 秒，
 *   等全部回来再渲染会让用户以为卡死。
 * - **每源独立失败**：一个源挂了不影响其它源的结果（`runCatching` 逐源包裹）。
 * - 先发 [SourceSearchState.Loading] 再发结果，UI 不用自己猜「还没开始还是已经失败」。
 *
 * [adapterOf] 可注入，测试时用假适配器，不联网。
 */
object SearchAll {

    fun search(
        keyword: String,
        types: List<SourceType> = Sources.all().map { it.type },
        adapterOf: (SourceType) -> SourceAdapter? = { Sources.of(it) },
    ): Flow<Pair<SourceType, SourceSearchState>> = channelFlow {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@channelFlow
        types.forEach { type ->
            launch {
                send(type to SourceSearchState.Loading)
                val started = System.currentTimeMillis()
                val state = runCatching {
                    adapterOf(type)?.let { searchWithFallback(it, kw) } ?: emptyList()
                }.fold(
                    onSuccess = { SourceSearchState.Success(it, System.currentTimeMillis() - started) },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        SourceSearchState.Failed(
                            e.message?.take(120) ?: e::class.simpleName ?: "未知错误",
                            System.currentTimeMillis() - started,
                        )
                    },
                )
                send(type to state)
            }
        }
    }

    /**
     * 搜一次；原词没结果且「看起来会被装饰符坑到」时按 [SearchKeywords.fallbacks] 再退一到两步。
     *
     * 2026-10-29 用户报：「长标题搜不到，只搜 9999 才搜到」。源站按整串子串匹配，
     * 标题里的 `『』！` 会把命中率打到 0；退让方案见 [SearchKeywords]。
     *
     * 只在 [SearchKeywords.needsFallback] 为真时才多跑——普通短词空就是真空，
     * 不该为它多等两轮（拷贝漫画一次要 6 秒）。
     */
    internal suspend fun searchWithFallback(adapter: SourceAdapter, keyword: String): List<SourceManga> {
        currentCoroutineContext().ensureActive()
        val first = adapter.search(keyword)
        currentCoroutineContext().ensureActive()
        if (first.isNotEmpty()) return first
        if (!SearchKeywords.needsFallback(keyword)) return first
        for (alt in SearchKeywords.fallbacks(keyword)) {
            currentCoroutineContext().ensureActive()
            val hit = try {
                adapter.search(alt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            if (hit.isNotEmpty()) {
                AppLog.i(
                    "Search",
                    "${adapter.displayName} 原词 0 条，退让「$alt」命中 ${hit.size} 条",
                )
                return hit
            }
        }
        return first
    }
}
