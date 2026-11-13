package app.gamanga.desktop.core.webview

/**
 * 半网页架构的浏览器宿主接口（ADR-0002）。
 *
 * M2 以 JCEF 实现：内嵌真实 Chromium，负责源站登录、OAuth 回调与 Cloudflare 盾；
 * 原生 HTTP 层经 [exportCookies] 借会话取数（JCEF 的 CefCookieManager.visitUrlCookies，
 * 含 HttpOnly——比驱动 CDP 更直接）。M6 的「浏览器兜底」也由该宿主承载。
 */
interface WebViewHost {
    /** 在内嵌浏览器中打开 [url]（登录 / OAuth / 盾页面 / 兜底阅读）。 */
    fun open(url: String)

    /** 导出 [domain] 下的 cookie 给原生层。 */
    fun exportCookies(domain: String, timeoutMs: Long = 3_000): Map<String, String>

    /** 关闭内嵌浏览器。 */
    fun close()
}
