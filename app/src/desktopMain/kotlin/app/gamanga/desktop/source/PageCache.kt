package app.gamanga.desktop.source

import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 章节图片列表的进程内缓存（LRU）。
 *
 * **为什么必须缓存**：Komiic 的 `imageTicketsByChapterId` 每调用一次就**消耗一份每日图片配额**，
 * 而票据本身 10 分钟内都有效。来回切话、退出再进同一话、翻回上一话，都会白烧一次配额——
 * 配额耗尽后整章取不到图，用户看到的就是「点下一话报错」「从书架进去图加载失败」。
 * GoDa 那边更贵：每次取图都要拉起隐藏浏览器跑站点自己的解码器。
 *
 * 只缓存**非空**结果：空列表意味着"这次没取到"，缓存它会把一次失败固化成永久失败。
 */
object PageCache {

    private const val CAPACITY = 24

    private val cache = object : LinkedHashMap<String, List<PageImage>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PageImage>>) =
            size > CAPACITY
    }

    fun get(type: SourceType, chapterId: String): List<PageImage>? =
        synchronized(cache) { cache[key(type, chapterId)] }

    fun put(type: SourceType, chapterId: String, pages: List<PageImage>) {
        if (pages.isEmpty()) return
        synchronized(cache) { cache[key(type, chapterId)] = pages }
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun key(type: SourceType, chapterId: String) = "$type:$chapterId"
}

/**
 * 给任意源套上「图片列表缓存 + 瞬时失败重试」。
 *
 * 用 Kotlin 的接口委托（`by delegate`）只覆写 [pages]，其余能力原样透传——
 * 新增源不用改任何代码就自动获得这两个能力。
 */
internal class CachedPages(private val delegate: SourceAdapter) : SourceAdapter by delegate {

    private class ChapterGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val gates = mutableMapOf<String, ChapterGate>()

    override suspend fun pages(chapterId: String): List<PageImage> {
        currentCoroutineContext().ensureActive()
        PageCache.get(delegate.type, chapterId)?.let { return it }
        val gate = synchronized(gates) {
            gates.getOrPut(chapterId) { ChapterGate() }.also { it.users++ }
        }
        try {
            return gate.mutex.withLock {
                currentCoroutineContext().ensureActive()
                PageCache.get(delegate.type, chapterId) ?: retryOnce { delegate.pages(chapterId) }.also {
                    PageCache.put(delegate.type, chapterId, it)
                }
            }
        } finally {
            synchronized(gates) {
                if (--gate.users == 0) gates.remove(chapterId)
            }
        }
    }
}

/**
 * 源站条目失效（404）。
 *
 * 包子漫画这类站点会**按标题重新生成 slug**——源站改了拼音/补了字，旧地址就直接 404，
 * 而我们书架里存的是当时那个旧地址。症状就是「网页打不开」「该话没有图片」全来了。
 */
val Throwable.isGoneError: Boolean
    get() = message.orEmpty().contains("HTTP 404")

/** 配额类错误：重试没有意义（还白烧一次请求），直接抛给 UI 提示用户登录/换源。 */
val Throwable.isQuotaError: Boolean
    get() = message.orEmpty().contains("quota", ignoreCase = true)

/** 明确的业务拒绝不自动重试；HTTP 408和5xx仍保留原有瞬时重试。 */
internal val Throwable.isNonRetryableSourceError: Boolean
    get() = isQuotaError || (this is HttpException && (status == 210 || status in 400..499 && status != 408))

/**
 * 瞬时失败重试一次（网络抖动、源站 5xx）。
 *
 * 只重试一次：这是给「偶发失败」用的，不是给持续故障用的——重试太多次只会让用户等更久，
 * 而且配额类错误每次重试都在真实消耗额度。
 */
internal suspend fun <T> retryOnce(block: suspend () -> T): T = try {
    block()
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException || e.isNonRetryableSourceError) throw e
    delay(400)
    block()
}
