package app.gamanga.desktop.core

import java.awt.Component
import java.awt.Container
import org.jetbrains.skiko.SkiaLayer

/**
 * 渲染后端（硬件加速）开关 + 自检（2026-10-30 批次46）。
 *
 * ## 为什么需要这一层
 *
 * 用户问「我的程序没有使用硬件加速吗」——**能查证，不能靠猜**。
 *
 * Compose Desktop 的绘制全交给 skiko，skiko 在 Windows 上的默认后端是
 * `ANGLE`（`skiko.rendering.angle.enabled` 为真时）否则 `DIRECT3D`，
 * 两者都是 **D3D11 硬件加速**；回退队列是 `ANGLE → DIRECT3D → SOFTWARE_FAST → SOFTWARE_COMPAT`。
 * 也就是说**默认就是硬件加速**，本项目也从没设过 `skiko.renderApi`。
 * 实测证据：`%LOCALAPPDATA%\Gamanga\logs\gamanga.log` 里的绘制线程栈是
 * `org.jetbrains.skiko.redrawer.Direct3DRedrawer` —— 确实是 GPU 路径。
 *
 * 那为什么还要这个开关：
 * 1. **回退是静默的**。显卡驱动有问题时 skiko 会自己掉到 `SOFTWARE_FAST`，
 *    界面照样能开，只是每帧都慢——用户只会觉得「卡」，日志里一行提示都没有。
 *    所以设置页要能**看见**当前到底在跑哪条路径。
 * 2. 少数机器（老核显、远程桌面、驱动崩过）GPU 路径反而更慢或花屏，
 *    需要一个「关掉硬件加速」的逃生门。
 *
 * ## 时序约束（关键）
 *
 * skiko 在 **`SkiaLayer` 初始化时**读 `skiko.renderApi` / `skiko.fps.enabled`，
 * 之后再改系统属性**完全无效**。所以 [apply] 必须在 `application { }` 之前调用
 * （见 `Main.kt` 的 `main()`），设置页里改完只能提示「重启程序后生效」。
 */
object RenderBackend {

    /** 硬件加速偏好：`"0"` = 强制 CPU 光栅；缺省或其它值 = 交给 skiko 自动挑（GPU 优先）。 */
    const val PREF_HW_ACCEL = "render.hwAccel"

    /** 本次进程**请求**的后端（`AUTO` 或 `SOFTWARE_FAST`），只用于日志与展示。 */
    @Volatile
    var requested: String = "AUTO"
        private set

    /** 窗口还没建好 / 找不到 SkiaLayer 时的展示文案。 */
    private const val UNKNOWN = "初始化中…"

    /**
     * 偏好 → `skiko.renderApi` 取值。
     *
     * `null` 表示**不设这个系统属性**，让 skiko 走自己的回退队列
     * （`ANGLE → DIRECT3D → SOFTWARE_FAST → SOFTWARE_COMPAT`）——
     * 这是比硬写 `ANGLE` 更稳的「硬件加速」：真机 GPU 路径起不来时它还能降级，
     * 而不是黑屏。
     */
    internal fun renderApiProperty(hwAccel: Boolean): String? = if (hwAccel) null else "SOFTWARE_FAST"

    /**
     * 把偏好写进系统属性。**必须在任何 Skia 组件创建之前调用**。
     *
     * 只处理硬件加速一项：帧率浮层（`skiko.fps.enabled`）用户 2026-10-30 明确要求去掉，
     * 别再往这里加回来。
     */
    fun apply(hwAccel: Boolean) {
        val api = renderApiProperty(hwAccel)
        if (api != null) System.setProperty("skiko.renderApi", api)
        requested = api ?: "AUTO"
        AppLog.i(
            "Render",
            "请求渲染后端=$requested（硬件加速=$hwAccel）" +
                if (api == null) "，未设 skiko.renderApi → 走 skiko 回退队列" else ""
        )
    }

    /**
     * 读**实际**在跑的后端。
     *
     * 只能从 `SkiaLayer` 上取：`renderApi` 是它最终选定的枚举，
     * `renderInfo` 带 GPU/驱动描述（形如 `Direct3D 12 · NVIDIA GeForce RTX 3060`）。
     * 这两个值在窗口出第一帧之后才有效，所以调用方要接受「初始化中…」这个中间态。
     */
    fun describe(window: java.awt.Window?): String {
        val layer = window?.let { findSkiaLayer(it) } ?: return UNKNOWN
        val api = runCatching { layer.renderApi.name }.getOrNull()
        val info = runCatching { layer.renderInfo }.getOrNull()
        return formatRenderBackend(api, info)
    }

    /** 当前是否真的走 GPU（给设置页写提示语用；未知一律按"未知"处理，不假装）。 */
    fun isHardware(window: java.awt.Window?): Boolean? {
        val layer = window?.let { findSkiaLayer(it) } ?: return null
        return when (runCatching { layer.renderApi.name }.getOrNull()?.uppercase()) {
            "ANGLE", "DIRECT3D", "OPENGL", "VULKAN", "METAL" -> true
            "SOFTWARE_FAST", "SOFTWARE_COMPAT", "SOFTWARE", "DIRECT_SOFTWARE" -> false
            else -> null
        }
    }

    /** `GraphicsApi` 枚举名 → 人话。`info` 为空/与 api 重复时不追加。 */
    internal fun formatRenderBackend(api: String?, info: String?): String {
        val label = when (api?.uppercase()) {
            null, "", "UNKNOWN" -> return UNKNOWN
            "ANGLE" -> "Direct3D · ANGLE（GPU 加速）"
            "DIRECT3D" -> "Direct3D（GPU 加速）"
            "OPENGL" -> "OpenGL（GPU 加速）"
            "VULKAN" -> "Vulkan（GPU 加速）"
            "METAL" -> "Metal（GPU 加速）"
            "SOFTWARE_FAST", "SOFTWARE_COMPAT", "SOFTWARE", "DIRECT_SOFTWARE" ->
                "CPU 软件光栅（未使用 GPU）"
            else -> api
        }
        // 实测 skiko 的 `renderInfo` 在这台机器上**只重复枚举名**（形如
        // `"GraphicsApi: DIRECT3D"` 或就是 `"DIRECT3D"`），没有 GPU 型号。
        // 直接拼上去会显示成 "Direct3D（GPU 加速） · GraphicsApi: DIRECT3D"，
        // 所以把枚举名本身和常见前缀一起剥掉，剥空了就不追加。
        val bare = info?.trim()?.removePrefix("GraphicsApi:")?.trim()
        val stripped = bare
            ?.replace(Regex(Regex.escape(api), RegexOption.IGNORE_CASE), "")
            ?.trim(' ', '\t', '\n', ':', '·', '-', '|')
        val extra = stripped
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > 72) it.take(72) + "…" else it }
        return if (extra == null) label else "$label · $extra"
    }

    /**
     * 在 AWT 组件树里找 `SkiaLayer`。
     *
     * 层级是 `ComposeWindow.contentPane → ComposeLayer → SkiaLayer`（Compose 版本间可能多一层
     * 容器），所以递归找而不是写死路径。
     */
    private fun findSkiaLayer(c: Component?): SkiaLayer? {
        if (c == null) return null
        if (c is SkiaLayer) return c
        if (c is Container) {
            for (i in 0 until c.componentCount) {
                findSkiaLayer(c.getComponent(i))?.let { return it }
            }
        }
        return null
    }
}
