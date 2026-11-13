package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.SourceType
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/**
 * 地址候选与换域名重试（F-22）。
 *
 * 源站换域名有两种表现：**整个站点搬走**（旧域名 301/404）和**旧域名直接被墙**
 * （连接超时/DNS 失败）。后者才值得「换下一个地址重试」——业务错误（404/403/JSON
 * 结构不符）换域名也没用，反而把一次明确的失败拖成 N 倍耗时。
 */
internal fun isConnectionFailure(e: Throwable): Boolean {
    // 已收到HTTP响应：正文里的timeout/connect不是传输层连接失败。
    if (e is HttpException) return false
    if (e is ConnectException || e is UnknownHostException ||
        e is HttpConnectTimeoutException || e is HttpTimeoutException
    ) {
        return true
    }
    val msg = (e.message ?: "").lowercase()
    return msg.contains("handshake") || msg.contains("timed out") ||
        msg.contains("timeout") || msg.contains("unknownhost") ||
        msg.contains("unresolved") || msg.contains("no route") ||
        msg.contains("connect")
}

/**
 * 按 [bases] 顺序请求 `base + path`，连接类失败自动换下一个地址。
 *
 * [headersFor] 收 base：**Referer 必须跟着当前地址变**——用 A 站的 Referer 去请求
 * B 站，部分站点会直接 403，那就白重试了。
 *
 * 全部失败时抛**最后一个**异常（用户看到的是最贴近当前地址的原因）。
 */
internal suspend fun fetchTextWithFallback(
    bases: List<String>,
    path: String,
    type: SourceType,
    tag: String,
    headersFor: (String) -> Map<String, String> = { emptyMap() },
): String {
    var last: Throwable? = null
    for (base in bases) {
        try {
            return Http.getText(base + path, headersFor(base), type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = e
            if (!isConnectionFailure(e)) throw e
            AppLog.w(tag, "地址 $base 不可用（${e::class.simpleName}），尝试下一个")
        }
    }
    throw last ?: IllegalStateException("${type.name}：所有站点地址均不可达")
}

/** [fetchTextWithFallback] 的 POST 版（Komiic 的 GraphQL 走这条）。 */
internal suspend fun postJsonWithFallback(
    bases: List<String>,
    path: String,
    body: String,
    type: SourceType,
    tag: String,
    headersFor: (String) -> Map<String, String> = { emptyMap() },
): String {
    var last: Throwable? = null
    for (base in bases) {
        try {
            return Http.postJson(base + path, body, headersFor(base), type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = e
            if (!isConnectionFailure(e)) throw e
            AppLog.w(tag, "接口 $base 不可用（${e::class.simpleName}），尝试下一个")
        }
    }
    throw last ?: IllegalStateException("${type.name}：所有接口地址均不可达")
}
