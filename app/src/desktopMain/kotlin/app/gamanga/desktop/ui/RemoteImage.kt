package app.gamanga.desktop.ui

import app.gamanga.desktop.ui.theme.Tokens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.decodeTargetSize
import app.gamanga.desktop.source.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络图片（封面 / 漫画页）。
 *
 * 不引图片库：Compose Desktop 自带 Skia，`Image.makeFromEncoded` 就能解 webp/jpg/png，
 * 外面套一个 LRU 缓存避免滚动时反复下载。
 *
 * [headers] 用于防盗链（Komiic 的 `X-Image-Ticket`、GoDa 的 Referer）。
 *
 * [dynamicRatio] 给竖向连排（条漫）用：源站不下发宽高时（G社 width/height=0），
 * 调用方只能猜比例占位，猜错时 `FillWidth` 会在图下方露灰底或把图伸进下一格
 * （用户实测「条漫中间有白色间隔」）。开启后先按 [fallbackRatio] 占位，
 * 加载完成按**真实宽高比**重布——每张图紧贴排列，不再有断裂感。
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    headers: Map<String, String> = emptyMap(),
    dynamicRatio: Boolean = false,
    fallbackRatio: Float = 0.7f,
    /**
     * 采样质量（批次46）。默认 `Low`（双线性、无 mipmap）。
     *
     * **阅读器整页必须传 `Medium`**：一页扫描图 3000×4000 被缩到 800×1000 左右显示，
     * `Low` 等于每帧让 GPU 从 48MB 的纹理里双线性采样——缩得越狠、采样点越散，
     * 带宽吃得越多，滚起来就是"卡卡的"。`Medium` = 双线性 + mipmap，
     * 按缩小的级别取更小的层，带宽降一个量级，顺带消掉缩放时的闪烁。
     * （`High` 是双三次，更慢，不要用在这个尺寸上。）
     */
    filterQuality: FilterQuality = FilterQuality.Low,
    /** 真实宽高比出来时回调一次（R-98：调用方用它学习本章的真实比例，避免下次再猜错）。 */
    onRatioKnown: ((Float) -> Unit)? = null,
    /** 阅读器离屏预组合不应触发下载；其它调用点保持默认行为。 */
    loadEnabled: Boolean = true,
    /** 快速滑过时先等短暂稳定，再开始网络请求。 */
    loadDelayMillis: Long = 0,
) {
    // 已预热好的图：首帧直接给出，且比例一次到位（不产生"布局后再变高"）
    val cached = remember(url, headers) { ImageCache.peek(url, headers) }
    // **state 必须按 url 重建**（2026-10-25 线上 BUG 的教训）：
    // `produceState` 内部的 state 是 `remember { mutableStateOf(initialValue) }`，
    // **不随 key 重建**——换页后 value 还是上一页的位图，若再配 `if (value == null)` 守卫，
    // 就永远不加载新图（现象：页码在动、画面不动）。
    // 这里改成显式 `remember(url, headers)`：新页立刻拿到自己的初始值（缓存或 null），
    // 不会出现"换页后短暂显示上一页"的闪帧。
    val bitmapState = remember(url, headers) { mutableStateOf(cached) }
    var finished by remember(url, headers) { mutableStateOf(cached != null) }
    var retryKey by remember(url, headers) { androidx.compose.runtime.mutableIntStateOf(0) }
    var consumedRetryKey by remember(url, headers) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(url, headers, retryKey, loadEnabled, loadDelayMillis) {
        if (!loadEnabled) return@LaunchedEffect
        // 重进视口时可能已由其它等待者填充缓存；命中不额外等候。
        val hit = bitmapState.value ?: ImageCache.peek(url, headers)
        if (hit != null) {
            bitmapState.value = hit
            finished = true
            return@LaunchedEffect
        }
        // 同一组件的失败态保持可重试提示；重进视口不重启失败加载。
        if (finished && consumedRetryKey == retryKey) return@LaunchedEffect
        finished = false
        if (loadDelayMillis > 0) kotlinx.coroutines.delay(loadDelayMillis)
        // 点击只授权本次请求，不能让后续离屏/再显示隐式重试失败图。
        val retry = retryKey != consumedRetryKey
        consumedRetryKey = retryKey
        bitmapState.value = ImageCache.load(url, headers, foreground = true, retryFailed = retry)
        finished = true
    }
    val bitmap by bitmapState
    var ratio by remember(url) {
        mutableFloatStateOf(
            cached?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height }
                ?: fallbackRatio
        )
    }
    val bmp = bitmap
    LaunchedEffect(bmp) {
        val b = bmp
        if (dynamicRatio && b != null && b.width > 0 && b.height > 0) {
            ratio = b.width.toFloat() / b.height
            onRatioKnown?.invoke(ratio)
        }
    }
    Box(
        modifier
            // clipToBounds：比例收敛前图片可能高于盒子，不裁会画到下一格上
            .then(if (dynamicRatio) Modifier.aspectRatio(ratio).clipToBounds() else Modifier)
            .background(Tokens.Color.ImagePlaceholder)
    ) {
        when {
            bmp != null -> Image(
                bmp,
                contentDescription,
                Modifier.fillMaxSize(),
                contentScale = contentScale,
                filterQuality = filterQuality,
            )
            url == null -> Unit // 无图源：留灰底
            finished -> app.gamanga.desktop.ui.shell.TextAction(
                text = "加载失败 · 点击重试",
                onClick = { retryKey++ },
                modifier = Modifier.align(Alignment.Center),
            )
            // 匀速弧线 spinner（第十二轮 #1：默认 CircularProgressIndicator 的
            // 分段变速在主线程繁忙时「转得很奇怪」）
            else -> LoadingArc(
                Modifier.align(Alignment.Center).fillMaxSize(0.2f),
                color = Tokens.Color.CoverFallbackTint
            )
        }
    }
}

/**
 * 简易 LRU 图片缓存（按 URL + 请求头区分，按**字节预算**淘汰）。
 *
 * ## 2026-10-30 加固（用户报「放大缩小卡顿、会闪退」）
 *
 * | 问题 | 现象 | 修法 |
 * |---|---|---|
 * | 同一张图被并发解多次 | 预加载 worker 和可见页同时要一张图 → 两份 48MB 解码、两次下载 | `inFlight` 表按 key 合并，后到者等前一个的结果 |
 * | 解码并发无上限 | 快速翻页时 6~8 张原图同时解码，瞬时几百 MB | 解码信号量限 2 个（下载仍可并发） |
 * | 超大长条图 | 800×20000 一张 64MB、2000×20000 一张 160MB，直接顶穿堆 | 按 `decodeTargetSize` 只对病态尺寸等比缩小（正常页不动，见 `ImageBudget.kt`） |
 * | 淘汰发生在插入之后 | 峰值 = 预算 + 新图 | 先按「预算 − 新图」腾地方再放 |
 * | 解码 OOM 直接冒到绘制回调 | 崩在 `awt.dll` 里，用户看到"闪退" | 捕获 `OutOfMemoryError` → 砍掉一半缓存 → 重试一次 |
 */
object ImageCache {

    /** 缓存总预算（解码后字节）。超了就淘汰最久未用的。 */
    private const val BUDGET_BYTES = 256L * 1024 * 1024

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {}
    private var cachedBytes = 0L

    /** 请求由所有等待者共同持有：一个页面离开不影响其他页面，最后一个离开才取消。 */
    private class Flight(val job: Deferred<ImageBitmap?>, var waiters: Int = 0)
    private val inFlight = HashMap<String, Flight>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** 解码任务的作用域：与调用方协程解耦，后到的请求也能共享同一次解码。 */
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同时解码的图片数上限：解码是内存大户，下载才是 IO 大户，两者分开限。 */
    private val decodePermits = Semaphore(2)

    private fun bytesOf(bmp: ImageBitmap): Long =
        bmp.width.toLong() * bmp.height.toLong() * 4L

    /** 淘汰最久未用的，直到剩余预算 ≤ [target]（**必须持锁调用**）。 */
    private fun trimToLocked(target: Long, keep: String? = null) {
        val it = cache.entries.iterator()
        while (cachedBytes > target && it.hasNext()) {
            val e = it.next()
            if (e.key == keep) continue // 刚放进去/正要放的那张别淘汰
            cachedBytes -= bytesOf(e.value)
            it.remove()
        }
    }

    /** 放进缓存并按预算淘汰（**必须持锁调用**）。 */
    private fun putLocked(key: String, bmp: ImageBitmap) {
        val incoming = bytesOf(bmp)
        // **先腾地方再放**：旧实现是"放进去之后再淘汰"，峰值 = 预算 + 新图，
        // 一张 48MB 的图就能把 1.5G 的堆推到 OOM 边缘。
        trimToLocked(BUDGET_BYTES - incoming, keep = key)
        cache.put(key, bmp)?.let { cachedBytes -= bytesOf(it) }
        cachedBytes += incoming
    }

    private fun keyOf(url: String, headers: Map<String, String>): String =
        url + '|' + headers.entries.joinToString { "${it.key}=${it.value}" }

    /**
     * **同步**取缓存（不下载、不解码）。
     *
     * R-98 十一轮：往上滑时新进入视口的项在**顶部**，它一改高度，下面全部内容就整体位移——
     * 这就是"只有往上回弹"的原因。如果这张图已经预热好了，就应该在**第一帧**就用真实比例
     * 布局（高度一次到位、永不变化），而不是先按猜的比例摆一次再改。
     */
    fun peek(url: String?, headers: Map<String, String> = emptyMap()): ImageBitmap? {
        if (url == null) return null
        return synchronized(cache) { cache[keyOf(url, headers)] }
    }

    /** 这张图是否已在缓存里（预加载 worker 用它跳过已烘好的页，避免重复请求）。 */
    fun has(url: String?, headers: Map<String, String> = emptyMap()): Boolean {
        if (url == null) return false
        return synchronized(cache) { cache.containsKey(keyOf(url, headers)) }
    }

    /**
     * 正在加载的「前台」请求数（可见页 / 当前屏）。
     *
     * 单页、双页模式每次翻页都要现下两张图；如果预加载 worker 正占着连接下 3MB 的扫描图，
     * 可见页就得排队——用户体感是「翻页卡、像在等图加载完才能动」（2026-10-24 反馈）。
     * 所以：可见页标记 foreground，预加载在**有前台请求在飞时主动让路**。
     */
    private val foregroundLoads = java.util.concurrent.atomic.AtomicInteger(0)

    fun isForegroundBusy(): Boolean = foregroundLoads.get() > 0

    /** 本会话失败页不自动再探；仅用户明确点击单图重试时重新请求。 */
    internal fun canPrefetch(url: String, headers: Map<String, String>): Boolean =
        !failedAt.containsKey(keyOf(url, headers))

    /**
     * 解码；尺寸超过预算时按 `decodeTargetSize` 等比降采样。
     *
     * 缩图失败一律退回原图——绝不能因为省内存把页面搞没。
     */
    private fun decodeDownscaled(bytes: ByteArray): ImageBitmap {
        val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val target = decodeTargetSize(img.width, img.height)
        if (target.width == img.width && target.height == img.height) {
            return img.toComposeImageBitmap()
        }
        return try {
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(target.width, target.height)
            surface.canvas.drawImageRect(
                img,
                org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(target.width.toFloat(), target.height.toFloat()),
                org.jetbrains.skia.SamplingMode.LINEAR,
                null,
                true
            )
            surface.makeImageSnapshot().toComposeImageBitmap()
        } catch (t: Throwable) {
            img.toComposeImageBitmap()
        }
    }

    suspend fun load(
        url: String?,
        headers: Map<String, String> = emptyMap(),
        foreground: Boolean = false,
        retryFailed: Boolean = false,
    ): ImageBitmap? {
        if (url == null) return null
        val key = keyOf(url, headers)
        synchronized(cache) { cache[key] }?.let { return it }

        // **同一张图只解一次**：预加载 worker 与可见页经常同时要同一张，
        // 旧实现各下一份、各解一份（48MB×2），是快速翻页时内存冲高的主因之一。
        if (!retryFailed && failedAt.containsKey(key)) return null
        val flight = synchronized(inFlight) {
            val active = inFlight[key] ?: Flight(decodeScope.async(start = CoroutineStart.LAZY) {
                // 锁内外缓存检查之间可能已有一次加载完成。
                peek(url, headers) ?: downloadAndDecode(url, headers, key)
            }).also { created ->
                inFlight[key] = created
                created.job.invokeOnCompletion {
                    synchronized(inFlight) { if (inFlight[key] === created) inFlight.remove(key) }
                }
            }
            active.waiters++
            active
        }
        if (foreground) foregroundLoads.incrementAndGet()
        try {
            flight.job.start()
            return flight.job.await()
        } finally {
            if (foreground) foregroundLoads.decrementAndGet()
            synchronized(inFlight) {
                flight.waiters--
                if (flight.waiters == 0 && !flight.job.isCompleted) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                    flight.job.cancel()
                }
            }
        }
    }

    private suspend fun downloadAndDecode(
        url: String,
        headers: Map<String, String>,
        key: String
    ): ImageBitmap? {
        return try {
            // **下载与解码都放 IO**（2026-10-24）：旧实现只把下载放 IO，
            // `Image.makeFromEncoded` 仍在调用方线程（Compose 主线程）解码——
            // 大图解码几十毫秒，快速滚动时连续触发就会卡帧（用户实测
            // 「滚动太快图片没加载出来时卡了几秒，以为程序卡死了」）。
            val bitmap = withContext(Dispatchers.IO) {
                // R-04 下载：已落盘章节的页面走 file://（阅读不再调源站，不烧配额）。
                // Skia 按魔术数字识别真实格式，本地文件扩展名不影响解码。
                // 前缀匹配 "file:" 而非 "file://"：Path.toUri() 给 file:///，
                // 个别手搓 URI 可能是 file:/ 单斜杠形态，都放行。
                val bytes = if (url.startsWith("file:")) {
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(java.net.URI(url)))
                } else {
                    fetchImageBytes(url, headers)
                }
                decodeGuarded(bytes)
            }
            failedAt.remove(key)
            synchronized(cache) { putLocked(key, bitmap) }
            bitmap
        } catch (e: kotlinx.coroutines.CancellationException) {
            // **取消必须原样抛出**（R-45 的教训）：快速滚动时上一张图的加载会被取消，
            // 吞掉它会让上层 LaunchedEffect 以为「加载完了但没图」，白占一个槽位。
            throw e
        } catch (t: Throwable) {
            // 有界淘汰最老记录，不整表清空，避免一批失败页一起重新被预加载。
            synchronized(failedAt) {
                if (failedAt.size >= 2048 && !failedAt.containsKey(key)) {
                    failedAt.minByOrNull { it.value }?.key?.let { failedAt.remove(it) }
                }
                failedAt[key] = System.currentTimeMillis()
            }
            // HttpException.message含原URL及响应正文，不能直接写堆栈。
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "local"
            val category = if (t is app.gamanga.desktop.source.HttpException) "http-${t.status}" else t.javaClass.simpleName
            AppLog.w("ImageCache", "image failed host=$host image=${Integer.toHexString(url.hashCode())} category=$category")
            null
        }
    }

    private suspend fun fetchImageBytes(url: String, headers: Map<String, String>): ByteArray {
        val started = System.nanoTime()
        val bytes = Http.getImageBytes(url, headers)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (elapsed >= 3000) {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
            AppLog.i("ImageCache", "image slow host=$host image=${Integer.toHexString(url.hashCode())} networkMs=$elapsed bytes=${bytes.size}")
        }
        return bytes
    }

    /**
     * 带内存兜底的解码：OOM 时砍掉一半缓存再试一次。
     *
     * 这条路径存在的意义是**别让 `OutOfMemoryError` 冒到绘制回调里**——
     * 那种崩法在 Windows 上表现为 `awt.dll` 访问违例，用户看到的就是"闪退"，
     * 日志里连一行 Java 异常都没有。这里把它收敛成"这张图暂时没出来"。
     */
    private suspend fun decodeGuarded(bytes: ByteArray): ImageBitmap {
        return try {
            decodePermits.withPermit { decodeDownscaled(bytes) }
        } catch (e: OutOfMemoryError) {
            AppLog.w(
                "ImageCache",
                "decode OOM (${bytes.size} bytes), trimming half cache and retrying once",
                e,
            )
            synchronized(cache) { trimToLocked(BUDGET_BYTES / 2) }
            try {
                decodePermits.withPermit { decodeDownscaled(bytes) }
            } catch (again: OutOfMemoryError) {
                AppLog.w("ImageCache", "decode OOM retry failed", again)
                throw again
            }
        }
    }
}

