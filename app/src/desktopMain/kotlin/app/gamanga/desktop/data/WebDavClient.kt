package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * F-03：WebDAV 客户端（坚果云 / Nextcloud / 群晕自建等通用）。
 *
 * 零第三方依赖：JDK 自带 [HttpClient] 就能发 PROPFIND/MKCOL/PUT/DELETE，
 * Basic auth 预置请求头（不吃 401 挑战往返）。固定 HTTP/1.1——多家 WebDAV
 * 服务在 HTTP/2 下行为不一致（连接复用 + 大 body 有坑），1.1 最稳。
 *
 * 只实现备份需要的四个动作 + 目录创建；Digest auth / OC 软删除等一概不支持
 * （坚果云、Nextcloud 主流配置都是 Basic）。
 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
    /**
     * 备份子目录。**留空 = 直接放在上面那个地址里**，不再套一层（用户 2026-10-24 反馈：
     * 地址已经指向备份文件夹时，再套 `Gamanga/` 会多出一层同名目录）。
     */
    val dir: String = BACKUP_DIR
) {

    /** 预置 Basic auth：`user:pass` 的 Base64。空用户时退化成匿名。 */
    private val authHeader: String? =
        if (user.isBlank() && pass.isBlank()) null
        else "Basic " + Base64.getEncoder()
            .encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        // 301/302 很常见（http→https、少写结尾斜杠、坚果云换域名）。
        // 不跟随就只能看到一个裸 301，用户完全不知道真实地址是什么（R-104 实测）。
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private suspend fun send(
        method: String,
        url: String,
        body: HttpRequest.BodyPublisher?,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("User-Agent", "${AppVersion.UA_PRODUCT} (WebDAV backup)")
        authHeader?.let { builder.header("Authorization", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val code = response.statusCode()
        // 跟随失败（或服务器给的地址仍然 3xx）时，把 Location 直接告诉用户——
        // 比"HTTP 301"这种裸码有用得多：改地址就能好。
        if (code in 300..399) {
            val location = response.headers().firstValue("Location").orElse("")
            throw WebDavException(
                if (location.isBlank()) "$method 被重定向（HTTP $code），但服务器没给新地址"
                else "$method 被重定向（HTTP $code）→ $location。请把 WebDAV 地址改成它后重试"
            )
        }
        code to (response.body() ?: ByteArray(0))
    }

    /**
     * 列目录（Depth 1）。返回文件条目（不含目录自身）。
     * 目录不存在 / 认证失败抛 [WebDavException]。
     */
    suspend fun list(dir: String = this.dir): List<WebDavEntry> {
        val url = joinUrl(baseUrl, dir) + "/"
        val (code, body) = send(
            "PROPFIND", url,
            HttpRequest.BodyPublishers.ofString(PROPFIND_BODY, Charsets.UTF_8),
            mapOf("Depth" to "1", "Content-Type" to "application/xml")
        )
        if (code !in 200..299) throw WebDavException("PROPFIND $url → HTTP $code（地址/账号密码？）")
        val entries = parsePropfind(body.toString(Charsets.UTF_8))
        // 目录自身的 href 以 "/" 结尾 → name 为空，顺带过滤掉
        return entries.filter { it.name.isNotBlank() }
    }

    /** 下载文件。非 2xx 抛 [WebDavException]。 */
    suspend fun download(path: String): ByteArray {
        val (code, body) = send("GET", joinUrl(baseUrl, path), null)
        if (code !in 200..299) throw WebDavException("GET $path → HTTP $code")
        return body
    }

    /** 上传（覆盖写）。true = 服务器接受。 */
    suspend fun upload(path: String, bytes: ByteArray): Boolean {
        val (code, _) = send("PUT", joinUrl(baseUrl, path), HttpRequest.BodyPublishers.ofByteArray(bytes))
        return code in 200..299
    }

    /**
     * 移动/重命名（WebDAV MOVE）。true = 成功。
     *
     * 用来把 `gamanga-latest.zip` 改名成 `gamanga-prev.zip` 再上传新版（F-44 覆盖式备份）。
     * **部分服务器不支持 MOVE**（405/501）→ 返回 false，调用方降级为「不留上一版」，
     * 不能因此让整个备份失败。
     */
    suspend fun move(from: String, to: String): Boolean {
        val (code, _) = send(
            "MOVE", joinUrl(baseUrl, from), null,
            mapOf("Destination" to joinUrl(baseUrl, to), "Overwrite" to "T")
        )
        return code in 200..299
    }

    /** 删除。true = 成功（404 也当成功——目标本来就不在）。 */
    suspend fun delete(path: String): Boolean {
        val (code, _) = send("DELETE", joinUrl(baseUrl, path), null)
        return code in 200..299 || code == 404
    }

    /**
     * 确保目录存在（MKCOL）。返回是否**新创建**（405 = 已存在，返回 false）。
     * 父目录缺失时部分服务器（坚果云）不会自动建，调用方需从根逐级建。
     */
    suspend fun ensureDir(dir: String): Boolean {
        if (dir.isBlank()) return false // 留空 = 用根地址，不建子目录
        // **结尾斜杠必须带**：Apache-WebDAV 对 `/dir` 的 MKCOL 会回 301 → `/dir/`，
        // 而反代后面生成的 Location 常是 `http://`（方案丢失）→ JDK 拒绝 https→http 降级，
        // 于是永远建不成目录（2026-10-24 用户实测：`MKCOL 被重定向（HTTP 301）→ http://webdav.kysl.top/Gamanga/`）。
        val (code, _) = send("MKCOL", joinUrl(baseUrl, dir) + "/", null)
        if (code in 200..299) return true
        if (code == 405) return false // 已存在
        throw WebDavException("MKCOL $dir → HTTP $code")
    }

    /**
     * 测试连接：目录不在就建，再列一次。返回目录下现有备份数。
     * 任何失败抛 [WebDavException]（消息已够 UI 直接展示）。
     */
    suspend fun testConnection(): Int {
        val listing = try {
            list()
        } catch (e: WebDavException) {
            // 列不出来多半是目录没建——建一个再试；仍失败就把原始错误抛出去
            ensureDir(dir)
            list()
        }
        return listing.size
    }

    companion object {
        /** appPrefs 里的 WebDAV 配置 key。 */
        const val PREF_URL = "webdav.url"
        const val PREF_USER = "webdav.user"
        const val PREF_PASS = "webdav.pass"

        /** 备份子目录的默认值（可在设置里改；留空 = 直接用配置的地址）。 */
        const val BACKUP_DIR = "Gamanga"

        /** appPrefs 里备份子目录的 key。 */
        const val PREF_DIR = "webdav.dir"

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
            </d:prop></d:propfind>
        """.trimIndent()

        /**
         * 解析 PROPFIND 响应（Depth 1）。
         *
         * 按 **localName** 匹配而不是 tagName：不同服务器前缀五花八门
         * （`d:` `D:` `ns0:`，Nextcloud 还有 `x1:`），但 namespace-aware 解析后
         * localName 都是 `response`/`href`/`getcontentlength`。纯函数，测试直接喂 XML。
         */
        fun parsePropfind(xml: String): List<WebDavEntry> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val out = ArrayList<WebDavEntry>()
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                if (el.localName() != "response") continue
                val descendants = el.descendants()
                val href = descendants.firstOrNull { it.localName() == "href" }?.textContent?.trim()
                    ?: continue
                val size = descendants.firstOrNull { it.localName() == "getcontentlength" }
                    ?.textContent?.trim()?.toLongOrNull() ?: 0L
                val modified = descendants.firstOrNull { it.localName() == "getlastmodified" }
                    ?.textContent?.trim() ?: ""
                // **目录要跳过**：Depth 1 会把目录自身也返回，其 href 以 "/" 结尾。
                // 旧实现 trimEnd('/') 后把目录名当成文件 → 列表里多出一条 0KB 的 `Gamanga`，
                // 点「删除」还会变成 `DELETE /Gamanga/Gamanga` 报 301（2026-10-25 用户实测）。
                if (href.endsWith("/")) continue
                // href 是 URL 编码过的路径，取最后一段并解码
                val raw = href.substringAfterLast('/')
                val name = try {
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } catch (e: Exception) {
                    raw
                }
                out += WebDavEntry(name = name, sizeBytes = size, lastModified = modified)
            }
            return out
        }

        /** DOM 无前缀/命名空间前缀通用本地名。 */
        private fun Element.localName(): String {
            val ln = localName
            if (!ln.isNullOrBlank()) return ln
            return tagName.substringAfter(':')
        }

        private fun Element.descendants(): List<Element> {
            val out = ArrayList<Element>()
            val kids = childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n is Element) {
                    out += n
                    out += n.descendants()
                }
            }
            return out
        }
    }
}

/**
 * 拼接 base 与相对路径：base 尾 `/`、parts 首尾 `/` 都容忍，
 * 空 part 跳过。`joinUrl("https://x/dav/", "Gamanga", "a.zip")`
 * → `https://x/dav/Gamanga/a.zip`。
 */
fun joinUrl(base: String, vararg parts: String): String {
    val b = base.trim().trimEnd('/')
    val p = parts.filter { it.isNotBlank() }.joinToString("/") { it.trim('/') }
    return if (p.isEmpty()) b else "$b/$p"
}

/**
 * **历史**备份文件名：`gamanga_yyyy-MM-dd_HH-mm-ss.zip`（F-44 起不再新建）。
 *
 * 现在走覆盖式（`gamanga-latest.zip` 单槽位），这个函数只用于识别/清理老版本留下的
 * 时间戳文件，别再拿它生成新文件名。
 */
fun backupFilename(millis: Long): String {
    val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    return "gamanga_$ts.zip"
}

data class WebDavEntry(
    val name: String,
    val sizeBytes: Long,
    val lastModified: String
)

class WebDavException(message: String) : RuntimeException(message)
