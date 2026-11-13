package app.gamanga.desktop.source

import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.model.SourceType

/**
 * 各源站的**登录入口**。
 *
 * 为什么要单独列出来：Komiic 的登录有两处入口（官网首页的登录页、漫画页里的登录弹窗），
 * 实测**首页那条能登、弹窗那条会报密码错误**（站点只提供 `POST /api/login` 一个接口，
 * 所以差异在表单上下文而不是 API）。为了不让用户反复撞在坏的那条路上，
 * 应用内所有"去登录"的入口都直接指到已验证可用的登录页。
 *
 * 返回 null 表示该站点不提供登录、或**不建议登录**——
 * G社/包子是盗版聚合站，没有登录的必要，也不该在那里用常用密码。
 *
 * **域名走 `sources.json`（F-22）**：换域名后登录页要跟着走，否则「去登录」会把
 * 用户送到旧域名（站点搬走后就是 404），这是配置化最容易漏的一处。
 */
object LoginUrls {

    /** 按域名查登录页（调用方手里只有 domain，兼容旧签名）。 */
    fun forDomain(domain: String): String? {
        if (domain.isBlank()) return null
        val type = SourceType.entries.firstOrNull {
            SourceConfig.domain(it).isNotBlank() && SourceConfig.domain(it) == domain
        } ?: return null
        return forType(type)
    }

    /** 按源查登录页（推荐用法）。 */
    fun forType(type: SourceType): String? {
        val base = SourceConfig.baseUrl(type)
        if (base.isBlank()) return null
        return when (type) {
            // Komiic：`/login` 是实测能登的那条路（详情页弹窗会报密码错误）
            SourceType.KOMIIC -> "$base/login"
            // 拷贝漫画（2026-10-24 实测）：`/login` 是正常登录页（Vue 应用，200）；
            // `/web/login` 是 301、`/web/register` 是 404。章节列表很可能要登录态才下发，
            // 给用户一个明确的登录入口，比让他在页面里乱找强。
            SourceType.COPYMANGA -> "$base/login"
            else -> null
        }
    }
}
