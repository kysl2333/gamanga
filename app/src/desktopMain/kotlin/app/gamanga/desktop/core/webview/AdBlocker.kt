package app.gamanga.desktop.core.webview

import java.net.URI

/**
 * 站点广告 / 劫持域名黑名单。
 *
 * **为什么必须拦**：包子漫画（baozimh）页面里挂着广告脚本，实测会把**顶层导航**跳到
 * `tsyndicate.com`——内嵌浏览器直接变成 "This site can't be reached"（用户看到的那一屏）。
 * 更要命的是：隐藏浏览器里跑站点自己的图片解码器时，页面一被劫走，
 * `#chapcontent img[data-path]` 就永远不存在，表现为「该话没有图片」（条漫章节最明显）。
 *
 * 只做**域名级**黑名单，不做通配规则：宁可漏掉几个广告，也不能误杀漫画图片域名
 * （`c-nd2-1.6wm.top` / `c-nd3-1.6wm.top` / `public.komiic.com` 之类）。
 */
object AdBlocker {

    private val HOSTS = listOf(
        "tsyndicate.com",
        "tsyndicate.net",
        "magsrv.com",
        "exoclick.com",
        "juicyads.com",
        "popads.net",
        "popcash.net"
    )

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (host.isEmpty()) return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
