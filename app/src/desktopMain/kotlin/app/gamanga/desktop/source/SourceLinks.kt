package app.gamanga.desktop.source

import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.model.SourceType

/**
 * 源站漫画页地址（浏览器兜底 / 书架右键菜单「在浏览器中打开」）。
 *
 * 三处调用方共用：浏览页详情按钮、阅读器「用浏览器查看」、书架右键菜单。
 * 之前各自写了一份 when，改源站域名要改三个地方（阅读器那份还漏了 else 兜底）。
 *
 * **域名走 `sources.json`（F-22）**：换域名后这里必须跟着变，否则「在浏览器中打开」
 * 会把用户送到旧域名（站点搬走后就是 404），这是配置化最容易漏的一处。
 */
fun browserUrl(type: SourceType, sourceMangaId: String): String {
    val base = SourceConfig.baseUrl(type)
    return when (type) {
        SourceType.KOMIIC -> "$base/comic/$sourceMangaId"
        SourceType.GODA -> "$base/manga/$sourceMangaId"
        SourceType.MANGABZ -> "$base/${sourceMangaId}bz/"
        // 拷贝漫画：用户 2026-10-24 指定用官方地址 mangacopy.com（不再用 copy4000）
        SourceType.COPYMANGA -> "$base/comic/$sourceMangaId"
        // 未接入的源（Komga 等）没有真实地址，回落到默认源首页而不是拼出空域名
        else -> SourceConfig.baseUrl(SourceType.KOMIIC)
    }
}

/**
 * 源站搜索页地址（搜索失败/空结果时的「在浏览器里搜」兜底入口）。
 *
 * 半网页架构的安全网（ADR-0002「降级路径 A 兜底」）：原生接口被风控/改版时，
 * 用户仍能在内嵌浏览器里搜到并直接看。
 */
fun searchUrlOf(type: SourceType, keyword: String): String? {
    val base = SourceConfig.baseUrl(type)
    return when (type) {
        SourceType.MANGABZ -> "$base/search?title=${pathEncode(keyword)}"
        SourceType.COPYMANGA -> "$base/search?q=${pathEncode(keyword)}"
        SourceType.GODA -> "$base/s/${pathEncode(keyword)}"
        else -> null
    }
}

/**
 * 源站域名（cookie 持久化 key / 内嵌浏览器落地校验都按它走）。
 *
 * 取的是**当前生效的** baseUrl 的 host：换了域名，登录态就按新域名存，
 * 不会把旧站点的 cookie 发到新站点去。
 */
fun domainOf(type: SourceType): String = SourceConfig.domain(type)

/**
 * 源的人类可读名称（下载页任务行 / 书架封面角标展示「这个任务从哪来」）。
 *
 * 与适配器的 displayName 保持一致：Komiic / 包子漫画。未实现的源给占位名，
 * 不抛异常——下载列表里出现未知源时也要能显示，不能崩。
 */
fun sourceNameOf(type: SourceType): String = when (type) {
    SourceType.KOMIIC -> "Komiic"
    SourceType.GODA -> "包子漫画"
    SourceType.KOMGA -> "Komga"
    SourceType.MANGABZ -> "Mangabz"
    SourceType.COPYMANGA -> "拷贝漫画"
}
