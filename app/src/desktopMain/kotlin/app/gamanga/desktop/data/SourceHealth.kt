package app.gamanga.desktop.data

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.Sources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检三态（F-36 起）。
 *
 * 为什么需要中间的 [SUSPECT]：路径模板/选择器配错时**不会抛异常**，只是搜不到东西——
 * 旧实现把「返回 0 条」也算活着，于是配错路径会亮绿灯，自检等于失效。
 */
enum class SourceStatus {
    /** 探针拿到数据。 */
    OK,

    /** 探针跑通了但返回 0 条：站点改版或路径模板配错。 */
    SUSPECT,

    /** 探针抛异常：连不上 / HTTP 错误 / 解析炸。 */
    FAIL,
}

/** 单个源的一次自检结果。 */
data class SourceHealthReport(
    val type: SourceType,
    val status: SourceStatus,
    val latencyMs: Long,
    /** 成功：一句信息（如「搜索返回 3 条」）；失败：异常摘要。 */
    val detail: String,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    /** 兼容旧调用点：只有 [SourceStatus.OK] 算健康。 */
    val ok: Boolean get() = status == SourceStatus.OK
}

/**
 * 源健康自检（F-20）。
 *
 * 站点改版要能**自己亮红灯**，而不是等用户搜不到才发现。设计：
 * - 启动后**静默跑一次**（不阻塞界面），也可在设置页手动「全部检测」；
 * - 并发跑，**拷贝漫画给 10s**（它的搜索要走隐藏浏览器），其它源 3s；
 * - 红灯时把异常**写进 AppLog**（tag=`SourceHealth`），便于事后查真实响应。
 */
object SourceHealth {

    private val _reports = MutableStateFlow<Map<SourceType, SourceHealthReport>>(emptyMap())

    /** 最近一次自检结果，设置页订阅它渲染状态灯。 */
    val reports: StateFlow<Map<SourceType, SourceHealthReport>> = _reports

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 拷贝漫画搜索要走**隐藏浏览器**，实测 9.7s 才出结果（2026-10-25 日志），
     * 原来 10s 会稳定误报红灯 —— 放宽到 20s。其余源 3s 足够。
     */
    private fun timeoutFor(type: SourceType): Long =
        if (type == SourceType.COPYMANGA) 20_000L else 3_000L

    /**
     * 应用级作用域：**不能挂在 composition 上**。
     * 2026-10-25 日志实测启动自检被取消过一次：
     * `红灯 COPYMANGA（4248ms）：The coroutine scope left the composition` ——
     * 界面一重组（切页/换窗口）就把自检掐了，结果被误判成红灯。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动时静默自检（不阻塞界面、不受 composition 生命周期影响）。 */
    fun checkAllInBackground() {
        appScope.launch { runCatching { checkAll() } }
    }

    /** 全部源并发自检，结果写回 [reports]。 */
    suspend fun checkAll(): Map<SourceType, SourceHealthReport> = coroutineScope {
        val list = Sources.all().map { adapter ->
            async {
                val started = System.currentTimeMillis()
                val report = runCatching {
                    withTimeout(timeoutFor(adapter.type)) { adapter.probe() }
                }.fold(
                    onSuccess = { n ->
                        report(adapter.type, n, System.currentTimeMillis() - started, adapter.probeEmptyIsSuspicious)
                    },
                    onFailure = { e ->
                        val msg = e.message?.take(120) ?: e::class.simpleName ?: "未知错误"
                        SourceHealthReport(
                            type = adapter.type,
                            status = SourceStatus.FAIL,
                            latencyMs = System.currentTimeMillis() - started,
                            detail = msg,
                        )
                    },
                )
                if (report.status != SourceStatus.OK) {
                    AppLog.w(
                        "SourceHealth",
                        "${if (report.status == SourceStatus.FAIL) "红灯" else "黄灯"} " +
                            "${adapter.type}（${report.latencyMs}ms）：${report.detail}",
                        null,
                    )
                }
                report
            }
        }.awaitAll()
        val map = list.associateBy { it.type }
        _reports.value = map
        AppLog.i(
            "SourceHealth",
            "自检完成 " + timeFmt.format(Date()) + "：" +
                map.values.joinToString { "${it.type}=${if (it.ok) "ok" else "FAIL"}(${it.latencyMs}ms)" },
        )
        map
    }

    /**
     * 探针结果 → 报告（F-36）：**0 条判黄灯**。
     *
     * 这是路径模板/选择器配错的唯一可观测信号：那种错误不抛异常，只是列表空。
     * 文案按 UI-SPEC §7 写（发生了什么 + 影响什么），不用「可能」。
     */
    internal fun report(
        type: SourceType,
        hits: Int,
        latencyMs: Long,
        emptyIsSuspicious: Boolean = true,
    ): SourceHealthReport = when {
        hits > 0 -> SourceHealthReport(type, SourceStatus.OK, latencyMs, "搜索可用（$hits 条）")
        // 站点可达但搜索空：只有「搜索本该有结果」的源才判黄灯（Komiic 关掉这个判定）
        emptyIsSuspicious -> SourceHealthReport(
            type, SourceStatus.SUSPECT, latencyMs,
            "搜索返回 0 条（站点改版或路径配错）",
        )
        else -> SourceHealthReport(type, SourceStatus.OK, latencyMs, "站点可达（搜索 0 条）")
    }

    /** 单源自检（设置页点某个源时用）。 */
    suspend fun checkOne(type: SourceType): SourceHealthReport? {
        val adapter = Sources.of(type) ?: return null
        val started = System.currentTimeMillis()
        val report = runCatching {
            withTimeout(timeoutFor(type)) { adapter.probe() }
        }.fold(
            onSuccess = { n ->
                report(type, n, System.currentTimeMillis() - started, adapter.probeEmptyIsSuspicious)
            },
            onFailure = { e ->
                SourceHealthReport(
                    type, SourceStatus.FAIL, System.currentTimeMillis() - started,
                    e.message?.take(120) ?: e::class.simpleName ?: "未知错误",
                )
            },
        )
        _reports.value = _reports.value + (type to report)
        return report
    }
}
