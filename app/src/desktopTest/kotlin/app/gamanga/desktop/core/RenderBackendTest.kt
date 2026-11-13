package app.gamanga.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 渲染后端开关（2026-10-30 批次46，用户问「我的程序没有使用硬件加速吗」）。
 *
 * 这里只锁**纯逻辑**：偏好怎么翻译成 `skiko.renderApi`、枚举名怎么变成人话。
 * 「实际跑的是哪个后端」没法在单测里断言（要真窗口 + 真 GPU），
 * 那一半靠启动日志里 `I/Render: 实际渲染后端：…` 那行现场取证。
 */
class RenderBackendTest {

    @Test
    fun `hardware acceleration leaves render api to skiko`() {
        // 开硬件加速 = **不设** skiko.renderApi，让 skiko 走自己的回退队列
        // ANGLE → DIRECT3D → SOFTWARE_FAST → SOFTWARE_COMPAT。
        // 硬写成 "ANGLE" 反而更脆：GPU 路径起不来时没有降级，直接黑屏。
        assertNull(RenderBackend.renderApiProperty(hwAccel = true))
    }

    @Test
    fun `software rendering pins skiko to fast software raster`() {
        assertEquals("SOFTWARE_FAST", RenderBackend.renderApiProperty(hwAccel = false))
    }

    @Test
    fun `gpu backends are labelled as hardware`() {
        listOf("ANGLE", "DIRECT3D", "OPENGL", "VULKAN", "METAL").forEach { api ->
            assertTrue(
                RenderBackend.formatRenderBackend(api, null).contains("GPU 加速"),
                "$api 必须被认成硬件加速，实际：${RenderBackend.formatRenderBackend(api, null)}",
            )
        }
    }

    @Test
    fun `software backends say no gpu`() {
        listOf("SOFTWARE_FAST", "SOFTWARE_COMPAT", "SOFTWARE", "DIRECT_SOFTWARE").forEach { api ->
            val text = RenderBackend.formatRenderBackend(api, null)
            assertTrue(text.contains("未使用 GPU"), "$api 必须被认成软件渲染，实际：$text")
        }
    }

    @Test
    fun `unknown or missing api is not faked as hardware`() {
        // 拿不到值时**不许**默认说"硬件加速"——那样等于把问题藏起来
        assertEquals("初始化中…", RenderBackend.formatRenderBackend(null, null))
        assertEquals("初始化中…", RenderBackend.formatRenderBackend("", null))
        assertEquals("初始化中…", RenderBackend.formatRenderBackend("UNKNOWN", null))
    }

    @Test
    fun `gpu description is appended once`() {
        val text = RenderBackend.formatRenderBackend("DIRECT3D", "Direct3D 12 · NVIDIA GeForce RTX 3060")
        assertTrue(text.contains("Direct3D（GPU 加速）"))
        assertTrue(text.contains("NVIDIA GeForce RTX 3060"))
    }

    @Test
    fun `redundant description is dropped`() {
        // skiko 的 renderInfo 有时就是枚举名本身，重复拼一遍会变成 "Direct3D · DIRECT3D"
        assertEquals(
            RenderBackend.formatRenderBackend("DIRECT3D", "direct3d"),
            RenderBackend.formatRenderBackend("DIRECT3D", null),
        )
        assertEquals(
            RenderBackend.formatRenderBackend("DIRECT3D", "   "),
            RenderBackend.formatRenderBackend("DIRECT3D", null),
        )
    }

    @Test
    fun `renderInfo with GraphicsApi prefix is dropped when it only repeats the enum`() {
        // 本机实测原样返回的是 "GraphicsApi: DIRECT3D"（没有 GPU 型号），
        // 不剥前缀会显示成 "Direct3D（GPU 加速） · GraphicsApi: DIRECT3D"
        assertEquals(
            "Direct3D（GPU 加速）",
            RenderBackend.formatRenderBackend("DIRECT3D", "GraphicsApi: DIRECT3D"),
        )
        // 裸枚举名同样要吞掉（不同 skiko 版本 renderInfo 格式不一样，两种都得兼容）
        assertEquals(
            "Direct3D（GPU 加速）",
            RenderBackend.formatRenderBackend("DIRECT3D", "DIRECT3D"),
        )
        assertEquals(
            "Direct3D（GPU 加速）",
            RenderBackend.formatRenderBackend("DIRECT3D", "Direct3D:"),
        )
        // 前缀后面是**真信息**时要保留
        assertEquals(
            "Direct3D（GPU 加速） · NVIDIA GeForce RTX 3060",
            RenderBackend.formatRenderBackend("DIRECT3D", "GraphicsApi: NVIDIA GeForce RTX 3060"),
        )
    }

    @Test
    fun `long gpu description is truncated`() {
        // 设置页那一行放不下长驱动串，截断而不是把整行撑破
        val long = "Direct3D 12 · " + "X".repeat(200)
        val text = RenderBackend.formatRenderBackend("DIRECT3D", long)
        assertTrue(text.endsWith("…"), "长描述必须截断，实际：$text")
        assertTrue(text.length < 130, "截断后仍然太长：${text.length}")
    }
}
