package app.gamanga.desktop.ui.recommend

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.gamanga.desktop.ui.shell.WindowMenu
import app.gamanga.desktop.ui.shell.MenuItemRow
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.detail.chooseCoverSaveFile
import app.gamanga.desktop.ui.detail.defaultCoverFileName
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.gamanga.desktop.source.BangumiMirror
import app.gamanga.desktop.source.BangumiMirrors
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 封面与人物图的路由。
 *
 * 官方线路与镜像线路的图片路径完全一致（`/r/{宽}/pic/...`），只有域名不同，
 * 所以这里做两件事：统一按需请求 400px 缩略图；把域名换成当前线路的图片域名。
 * 账号快照 / 最近浏览里存的是历史域名，切换线路后靠 [resolve] 纠回来，不必重存。
 */
internal object RecommendImageRouting {
    /** 当前线路的图片域名；官方线路即 `lain.bgm.tv`。 */
    private val targetHost: String
        get() = BangumiMirrors.hostOf(BangumiMirrors.current.image) ?: "lain.bgm.tv"

    /**
     * 判据用**路径**而不是域名白名单。
     *
     * 镜像站会换域名，用户还能自己填一条，白名单永远追不上；但路径结构是全行业照抄官方的
     * （`/pic/cover/…`、`/pic/crt/…`、`/pic/user/…`，可带 `/r/{宽}/` 前缀）。
     * 推荐页渲染的所有图都来自 Bangumi 接口，按路径判定不会误伤其他站。
     */
    private val imagePath = Regex("^/r/\\d+/pic/(cover|crt|user)/.+|^/pic/(cover|crt|user)/.+")
    private fun isBangumiImage(uri: java.net.URI): Boolean = imagePath.matches(uri.rawPath.orEmpty())

    fun thumbnail(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        if (!isBangumiImage(uri)) return url
        val path = uri.rawPath.replace(Regex("^/r/\\d+/"), "/")
        // 只缩略封面与人物立绘；用户头像（/pic/user/）保持原样。
        if (!path.startsWith("/pic/cover/") && !path.startsWith("/pic/crt/")) return url
        return "${uri.scheme}://${uri.rawAuthority}/r/400$path" + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    /** 把 Bangumi 图片域名统一指向当前线路；已经是目标域名就原样返回。 */
    fun resolve(url: String): String {
        val source = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        if (!isBangumiImage(source)) return url
        val target = targetHost
        if (source.host.equals(target, ignoreCase = true)) return url
        return "https://$target" + source.rawPath + (source.rawQuery?.let { "?$it" } ?: "")
    }
}

/** Directory thumbnails have a separate budget; never evict or downsample reader pages. */
internal object RecommendImageCache {
    const val MAX_SIDE = 384
    const val BUDGET = 64L * 1024 * 1024
    /** 同一张图只发一次请求；不同图之间不再排队（用户要求取消并发限额）。 */
    private val inFlight = HashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val cache = LinkedHashMap<String, ImageBitmap>(64, .75f, true)
    private var bytes = 0L
    fun peek(url: String): ImageBitmap? = synchronized(cache) { cache[url] }
    /** 切换线路后旧域名解出来的图一律作废。 */
    fun clear() = synchronized(cache) { cache.clear(); bytes = 0L }
    internal fun decode(data: ByteArray): ImageBitmap {
        // Subsample JPEG/PNG during decode, rather than allocating a full cover first.
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(data))
        val sampled = stream?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) null else {
                val reader = readers.next()
                try {
                    reader.input = input
                    val factor = (maxOf(reader.getWidth(0), reader.getHeight(0)) / MAX_SIDE).coerceAtLeast(1)
                    if (factor == 1) return@use null
                    val param = reader.defaultReadParam.apply { setSourceSubsampling(factor, factor, 0, 0) }
                    val image = reader.read(0, param)
                    try { ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() } }
                    finally { image.flush() }
                } finally { reader.dispose() }
            }
        }
        return org.jetbrains.skia.Image.makeFromEncoded(sampled ?: data).use { image ->
            val size = thumbnailSize(image.width, image.height)
            if (size.first == image.width && size.second == image.height) image.toComposeImageBitmap()
            else org.jetbrains.skia.Surface.makeRasterN32Premul(size.first, size.second).use { surface ->
                surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()), org.jetbrains.skia.Rect.makeWH(size.first.toFloat(), size.second.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }
    internal fun thumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        // 去重：同一 URL 只发一次请求，后来者等同一个结果；不同 URL 直接并发，不排队。
        val (created, running) = synchronized(inFlight) {
            val active = inFlight[url]
            if (active != null) null to active
            else CompletableDeferred<ImageBitmap?>().also { inFlight[url] = it } to null
        }
        if (running != null) return running.await()
        val mine = created ?: return null
        val started = System.nanoTime()
        try {
            val networkStarted = System.nanoTime()
            val data = Http.getThumbnailBytes(url)
            val networkMs = (System.nanoTime() - networkStarted) / 1_000_000
            val decodeStarted = System.nanoTime()
            val bitmap = withContext(Dispatchers.Default) { decode(data) }
            app.gamanga.desktop.core.AppLog.i("RecommendImage", "host=${java.net.URI(url).host} networkMs=$networkMs decodeMs=${(System.nanoTime() - decodeStarted) / 1_000_000} bytes=${data.size}")
            currentCoroutineContext().ensureActive()
            synchronized(cache) {
                cache.put(url, bitmap)?.let { bytes -= it.width.toLong() * it.height * 4 }
                bytes += bitmap.width.toLong() * bitmap.height * 4
                val iterator = cache.entries.iterator()
                while (bytes > BUDGET && iterator.hasNext()) {
                    val old = iterator.next().value
                    bytes -= old.width.toLong() * old.height * 4
                    iterator.remove()
                }
            }
            mine.complete(bitmap)
            return bitmap
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } catch (_: Exception) {
            mine.complete(null)
            return null
        } finally {
            synchronized(inFlight) { inFlight.remove(url) }
            if ((System.nanoTime() - started) / 1_000_000 > 5_000) {
                app.gamanga.desktop.core.AppLog.w("RecommendImage", "slow image ${(System.nanoTime() - started) / 1_000_000}ms $url")
            }
        }
    }
}

internal val LocalRecommendImageMenu = staticCompositionLocalOf { false }

@Composable
internal fun RecommendImage(url: String?, description: String?, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val routed = url?.let(RecommendImageRouting::thumbnail)?.let(RecommendImageRouting::resolve)
    var image by remember(routed) { mutableStateOf(routed?.let(RecommendImageCache::peek)) }
    LaunchedEffect(routed) { if (routed != null) image = RecommendImageCache.load(routed) ?: image }
    val menuEnabled = LocalRecommendImageMenu.current && url != null
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var menu by remember { mutableStateOf<Offset?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    Box(modifier.background(Tokens.Color.ImagePlaceholder)
        .onGloballyPositioned { origin = it.localToWindow(Offset.Zero) }
        .then(if (menuEnabled) Modifier.pointerInput(url) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        menu = origin + event.changes.first().position
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        } else Modifier)) {
        image?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment) }
        menu?.let { anchor -> WindowMenu(anchor, onDismiss = { menu = null }) {
            MenuItemRow("复制图片链接", DockIcons.Picture, false) {
                menu = null
                clipboard.setText(AnnotatedString(url.orEmpty()))
                message = "已复制图片链接"
            }
            MenuItemRow("保存图片", DockIcons.Picture, false) {
                menu = null
                val target = chooseCoverSaveFile(defaultCoverFileName(description ?: "图片"))
                if (target != null && routed != null) scope.launch {
                    try {
                        val data = Http.getThumbnailBytes(RecommendImageRouting.resolve(url!!))
                        withContext(Dispatchers.IO) { target.writeBytes(data) }
                        message = "图片已保存"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "保存失败，请重试" }
                }
            }
        } }
        message?.let { androidx.compose.material3.Text(it, color = Tokens.Color.TextPrimary,
            modifier = Modifier.align(Alignment.BottomCenter).background(androidx.compose.ui.graphics.Color.White)) }
    }
}
