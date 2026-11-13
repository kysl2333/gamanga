package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 源站 HTTP 底座（M3）。
 *
 * 用 JDK 自带的 [HttpClient]，不引第三方网络库——需求只有 GET/POST JSON，
 * 少一个依赖少一份体积。内容请求使用可取消的异步传输，
 * 上层取消任务时同时取消对应请求；探活保留独立短超时。
 *
 * **HTTP 代理（第八轮 #8，第九轮增强）**：全局开关 + 协议（HTTP / SOCKS5）
 * + 每个源独立开关。[proxyPolicy] 决定「这个源走不走代理」——默认全部跟随
 * 全局；设置页里可按源单独关（比如只用代理访问 Komiic，G社 直连更快）。
 * 启动时由 Main.kt 从 appPrefs 恢复配置。内嵌浏览器（CEF）的代理另由
 * CefRuntime 启动参数注入（用户要求「代理开时对应站点全走代理」）。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** 全局代理开关。 */
    @Volatile
    private var proxyEnabled = false

    @Volatile
    private var proxyHost: String? = null

    @Volatile
    private var proxyPort: Int = 0

    /** 代理协议：HTTP（CONNECT）或 SOCKS5。 */
    @Volatile
    private var proxyProtocol: String = "HTTP"

    /**
     * 每个源是否走代理（per-source 独立开关，第八轮 #8）。
     * 返回 false = 该源直连，忽略全局开关。默认全部走（跟随全局）。
     */
    @Volatile
    var proxyPolicy: (SourceType) -> Boolean = { true }

    private val proxyClientLock = Any()

    @Volatile
    private var cachedProxyClient: HttpClient? = null

    private val directClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** 代理端口 TCP 连通性检查的超时（够本地代理软件响应，又不至于让用户干等）。 */
    private const val PROXY_TCP_TIMEOUT_MS = 3_000

    /** 经代理连目标端口的超时。 */
    private const val PROXY_PROBE_TIMEOUT_MS = 8_000

    /** 当前代理配置快照（CEF 启动参数 / 设置页展示用）。 */
    data class ProxyConfig(
        val enabled: Boolean,
        val host: String?,
        val port: Int,
        val protocol: String,
    )

    fun currentProxy(): ProxyConfig = ProxyConfig(proxyEnabled, proxyHost, proxyPort, proxyProtocol)

    /** 配置/清除代理（设置页保存时调；启动时由 Main.kt 从 pref 恢复）。 */
    fun configureProxy(enabled: Boolean, host: String?, port: Int, protocol: String = "HTTP") {
        val normalized = if (protocol.uppercase() in setOf("SOCKS5", "SOCKS")) "SOCKS5" else "HTTP"
        proxyEnabled = enabled && !host.isNullOrBlank() && port in 1..65535
        proxyHost = host?.trim()?.ifBlank { null }
        proxyPort = port
        proxyProtocol = normalized
        synchronized(proxyClientLock) { cachedProxyClient = null } // 配置变了，旧 client 作废
        AppLog.i(
            "Proxy",
            if (proxyEnabled) "enabled ${proxyProtocol}://${proxyHost}:$proxyPort" else "disabled"
        )
    }

    /** 按协议造 ProxySelector：HTTP 用 JDK 内置；SOCKS5 自定义（HttpClient 认 SOCKS 类型的 selector）。 */
    private fun proxySelectorFor(host: String, port: Int, protocol: String): ProxySelector =
        when (protocol.uppercase()) {
            "SOCKS5", "SOCKS" -> object : ProxySelector() {
                private val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
                override fun select(uri: URI?): List<Proxy> = listOf(socks)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, e: IOException?) {}
            }
            else -> ProxySelector.of(InetSocketAddress(host, port))
        }

    private fun proxyClient(): HttpClient? = synchronized(proxyClientLock) {
        cachedProxyClient ?: run {
            val host = proxyHost ?: return@synchronized null
            if (proxyPort !in 1..65535) return@synchronized null
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxySelectorFor(host, proxyPort, proxyProtocol))
                .build()
                .also { cachedProxyClient = it }
        }
    }

    /** 按源选 client：全局没开 / 该源关了代理 / 代理不可用 → 直连。 */
    private fun clientFor(type: SourceType?): HttpClient {
        if (!proxyEnabled) return directClient
        if (type != null && !proxyPolicy(type)) return directClient
        return proxyClient() ?: directClient
    }

    /**
     * 测试代理连通性（设置页「测试连接」按钮，第九轮 #8；2026-10-29 重做）。
     *
     * ## 为什么重做：填个连不通的 IP 也会「通过」
     *
     * 用户 2026-10-29 报「输入一个连不通的 IP 和端口，点测试竟然通过了」。
     * 实测（JDK 21，`tools/jdk-21.0.12.1+1`）根因是 **JDK 的 `HttpClient` 对 SOCKS 代理不可靠**：
     * 同样不可达的地址，HTTP 协议稳定报 `HttpConnectTimeoutException`，
     * 而 SOCKS 协议有时**直接绕过代理走直连**（返回 204），于是「代理根本连不上」被判成通过。
     *
     * 所以现在的判据分两步，且**第一步与协议无关**：
     * 1. [probeEndpoint]：先对 `host:port` 做一次 TCP 连接。连不上就直接失败——
     *    这一步才是用户要的「这个代理 IP 到底通不通」。
     * 2. [probeThroughProxy]：再验证「经这个代理能出网」。
     *    HTTP 走 `HttpClient`；**SOCKS5 改用原生 `Socket(Proxy.Type.SOCKS)`**
     *    （实测这条路径严格遵守代理，不会偷偷直连）。
     *
     * **协议填错是最常见的失败原因**（代理软件常把 SOCKS5 与 HTTP 分在不同端口，如 1081 只开
     * SOCKS5、2081 只开 HTTP）。主协议失败时会用另一种协议再试一次，能通就直接告诉用户
     * 该把协议切成哪个，而不是甩一句「HTTP connect timed out」。
     *
     * @return null = 连通；非 null = 失败原因（直接展示给用户）。
     */
    fun testProxyConnection(host: String, port: Int, protocol: String): String? {
        val h = host.trim()
        if (h.isBlank()) return "地址为空"
        if (port !in 1..65535) return "端口无效：$port"
        // ① 代理端口本身通不通（与协议无关，也是用户最想看到的那一条）
        probeEndpoint(h, port)?.let { return it }
        val chosen = normalizeProtocol(protocol)
        val failure = probeThroughProxy(h, port, chosen)
        if (failure == null) return null
        val other = if (chosen == "SOCKS5") "HTTP" else "SOCKS5"
        if (probeThroughProxy(h, port, other) == null) {
            return "这个端口其实是 $other 代理，协议请切成 $other（当前选的是 $chosen）"
        }
        return failure
    }

    private fun normalizeProtocol(protocol: String): String =
        if (protocol.uppercase() in setOf("SOCKS5", "SOCKS")) "SOCKS5" else "HTTP"

    /** 代理端口的 TCP 连通性：null = 连得上，非 null = 失败原因（带排错提示）。 */
    private fun probeEndpoint(host: String, port: Int): String? {
        val started = System.currentTimeMillis()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), PROXY_TCP_TIMEOUT_MS)
            AppLog.i("Proxy", "endpoint ok $host:$port ${System.currentTimeMillis() - started}ms")
            null
        } catch (e: Throwable) {
            AppLog.w("Proxy", "endpoint unreachable $host:$port", e)
            "连不上代理 $host:$port（${describe(e)}）—— 确认代理软件已开启、地址端口没填错"
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 用指定协议探一次；null = 通，非 null = 失败原因。 */
    private fun probeThroughProxy(host: String, port: Int, protocol: String): String? =
        if (protocol == "SOCKS5") probeSocks(host, port) else probeHttp(host, port)

    /**
     * SOCKS5：用原生 socket 经代理连一个目标端口。
     *
     * **不用 `HttpClient`**：实测 JDK 的 HttpClient 遇到 SOCKS 会时好时坏地直连（见上面的说明），
     * 那样「代理通不通」就测不出来了。`Socket(Proxy.Type.SOCKS)` 是 JDK 里唯一稳定走 SOCKS 的路径。
     * 目标用 `createUnresolved`：DNS 交给代理去解，避免本机 DNS 污染导致误判。
     */
    private fun probeSocks(host: String, port: Int): String? = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        Socket(proxy).use { s ->
            s.connect(InetSocketAddress.createUnresolved("www.google.com", 443), PROXY_PROBE_TIMEOUT_MS)
        }
        null
    } catch (e: Throwable) {
        AppLog.w("Proxy", "socks probe failed $host:$port", e)
        "经 SOCKS5 代理连不出去（${describe(e)}）"
    }

    /** HTTP：走 `HttpClient` + `ProxySelector`（JDK 对 HTTP 代理是可靠的）。 */
    private fun probeHttp(host: String, port: Int): String? = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .proxy(proxySelectorFor(host, port, "HTTP"))
            .build()
        val targets = listOf("https://www.google.com/generate_204", "https://api.bgm.tv/")
        var last: String? = null
        for (url in targets) {
            val resp = client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() in 200..299) return null
            last = "代理返回 HTTP ${resp.statusCode()}（$url）"
        }
        last
    } catch (e: Throwable) {
        AppLog.w("Proxy", "http probe failed $host:$port", e)
        "经 HTTP 代理连不出去（${describe(e)}）"
    }

    /** 把异常翻成人话（超时/拒绝/域名解析不了，用户能据此改配置）。 */
    private fun describe(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "端口拒绝连接"
        is java.net.UnknownHostException -> "地址解析不了"
        is javax.net.ssl.SSLException -> "SSL 握手失败"
        else -> e.message?.take(80) ?: (e::class.simpleName ?: "未知错误")
    }

    /** GET 文本（UTF-8）。 */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sourceType: SourceType? = null,
    ): String = request(url, headers, null, sourceType).let { String(it, Charsets.UTF_8) }

    /** 拷贝API专用：可取消、短超时，只接受标准200，210不得被当作成功JSON吞掉。 */
    internal suspend fun getCopyApiText(url: String, headers: Map<String, String>): String =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val request = buildRequest(url, headers, null, SourceType.COPYMANGA, Duration.ofSeconds(8))
            val future = clientFor(SourceType.COPYMANGA).sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (error != null) continuation.resumeWith(Result.failure(error.cause ?: error))
                else if (response.statusCode() != 200)
                    continuation.resumeWith(Result.failure(HttpException("copy-api", response.statusCode(), "")))
                else continuation.resumeWith(Result.success(response.body()))
            }
        }

    /**
     * 只取状态码（设置页「测试连接」/ 源地址探活用）。
     *
     * 与 [getText] 的区别：**非 2xx 不抛异常**——探活要能区分「域名可达但返回 404」
     * 和「根本连不上」，这两种情况对用户的意义完全不同。
     */
    suspend fun statusOf(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sourceType: SourceType? = null,
    ): Int = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers, null, sourceType, Duration.ofSeconds(10))
        clientFor(sourceType).send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** GET 二进制（图片）。 */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sourceType: SourceType? = null,
    ): ByteArray = request(url, headers, null, sourceType)

    /** 漫画单图一次完整等待30秒，避免慢CDN被15秒切断后重发。 */
    internal suspend fun getImageBytes(url: String, headers: Map<String, String>, timeoutSeconds: Long = 30): ByteArray =
        request(url, headers, null, null, timeoutSeconds * 1000)

    /** 可取消的缩略图请求：离开页面时立即取消传输、释放推荐队列许可。 */
    internal suspend fun getThumbnailBytes(url: String, timeoutSeconds: Long = 12): ByteArray =
        withTransferDeadline(timeoutSeconds * 1000) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val request = buildRequest(url, emptyMap(), null, null, Duration.ofSeconds(timeoutSeconds))
                val future = clientFor(null).sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { response, error ->
                    if (error != null) continuation.resumeWith(Result.failure(error))
                    else if (response.statusCode() !in 200..299)
                        continuation.resumeWith(Result.failure(HttpException(url, response.statusCode(), "thumbnail")))
                    else continuation.resumeWith(Result.success(response.body()))
                }
            }
        }

    /** HttpRequest.timeout在收到响应头后可能不再覆盖正文；协程截止时间覆盖整段传输。 */
    private suspend fun <T> withTransferDeadline(timeoutMillis: Long, block: suspend () -> T): T {
        // withTimeoutOrNull只消费自身超时；外部取消必须继续向上传播。
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { Result.success(block()) }
        return result?.getOrThrow() ?: throw java.net.http.HttpTimeoutException("response body transfer timed out")
    }

    /** POST JSON（请求体已是 JSON 字符串），返回响应文本。 */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        sourceType: SourceType? = null,
    ): String =
        String(request(url, headers + ("Content-Type" to "application/json"), body, sourceType), Charsets.UTF_8)

    /**
     * 这个源该用哪个 UA（F-22）：`sources.json` 里配了就用它，否则用内置桌面 Chrome UA。
     *
     * 适配器**不要**再各自硬编码 UA——否则配置项形同虚设（站点按 UA 分流时
     * 用户改了配置却不生效，排查起来极其费时）。
     *
     * ⚠️ 只覆盖走 [Http] 的**原生请求**。内嵌浏览器与取图探针（[BrowserProbe]）
     * 跑在 CEF 里，UA 由 `CefSettings` 统一决定，这里改不到——配置页有提示。
     */
    private fun userAgentFor(type: SourceType?): String =
        type?.let { SourceConfig.userAgent(it) } ?: UA

    /** 组装请求（`request` 与 `statusOf` 共用，避免两处 UA/头逻辑漂移）。 */
    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        body: String?,
        type: SourceType?,
        timeout: Duration,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
        // 调用方自己给了 UA 就用它的（少数接口需要特定 UA），否则按源取配置
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", userAgentFor(type))
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return builder.build()
    }

    private suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String?,
        type: SourceType?,
        timeoutMillis: Long = 30_000,
    ): ByteArray = withTransferDeadline(timeoutMillis) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val request = buildRequest(url, headers, body, type, Duration.ofMillis(timeoutMillis))
            val future = clientFor(type).sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (error != null) continuation.resumeWith(Result.failure(error.cause ?: error))
                else if (response.statusCode() !in 200..299 || response.statusCode() == 210) {
                    continuation.resumeWith(Result.failure(
                        HttpException(url, response.statusCode(), response.body().decodeToString().take(200))
                    ))
                } else continuation.resumeWith(Result.success(response.body()))
            }
        }
    }
}

class HttpException(url: String, val status: Int, val snippet: String) :
    RuntimeException("HTTP $status @ $url: $snippet")
