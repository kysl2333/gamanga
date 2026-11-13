package app.gamanga.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.source.LoginUrls
import app.gamanga.desktop.source.browserUrl
import app.gamanga.desktop.source.domainOf
import app.gamanga.desktop.brandPainter
import app.gamanga.desktop.ui.ReaderScreen
import app.gamanga.desktop.ui.ReaderTarget
import app.gamanga.desktop.ui.shell.LocalComposeWindow
import app.gamanga.desktop.ui.theme.AppTheme
import app.gamanga.desktop.ui.theme.Tokens
import java.awt.Dimension

/**
 * 阅读器**独立窗口**管理（2026-10-30 用户要求 4）。
 *
 * ## 为什么从「覆盖层」改成「独立窗口」
 *
 * 这是 UI-SPEC 0.1 第 9 条的原始口径（「点阅读 → 打开**独立阅读器窗口**」），
 * 阶段 4 先落成了覆盖层（省事、不用管多窗口生命周期），用户现在明确要回来：
 * **主界面可以同时开多个阅读器，各窗口的阅读操作互不干扰**。
 *
 * 覆盖层做不到这一点——同一时刻只有一本，翻到别的书就把上一本挤掉了。
 *
 * ## 怎么实现
 *
 * Compose Desktop 的 `application { }` 里可以放任意多个 `Window`。所以这里只维护
 * 一个**全局可观察列表** [open]：`main()` 在 `application` 作用域里遍历它，
 * 每个条目渲染一个 [ReaderWindowHost]。开/关窗口 = 往列表里加/删一条，
 * 组合器自己会增删对应的真实窗口。
 *
 * **每个条目各自持有 target**：`ReaderScreen` 内部的 `index` / `pages` / `listState`
 * 全是 `remember` 出来的实例级状态，所以两个窗口看同一本书也互不影响。
 *
 * ## 为什么回调从这里过一道
 *
 * 「用浏览器查看」「登录提额度」要把内嵌浏览器盖在**主窗口**上（阅读器窗口里没有
 * 浏览器层）。所以 [launch] 收一个 `onOpenBrowser` 回调，由调用方（`GamangaApp`）
 * 闭包捕获主窗口的 `browserTarget` 状态。
 */
class ReaderWindowEntry internal constructor(
    val id: Long,
    val target: ReaderTarget,
    /** 把内嵌浏览器开到**主窗口**上（阅读器窗口自己不带浏览器层）。 */
    val onOpenBrowser: (url: String, domain: String) -> Unit,
)

object ReaderWindows {

    /** 当前打开着的阅读器窗口（顺序 = 打开顺序，用于窗口错位叠放）。 */
    val open = mutableStateListOf<ReaderWindowEntry>()

    private var nextId = 1L

    /** 新开一个阅读器窗口。返回它的 id（关窗时用）。 */
    fun launch(
        target: ReaderTarget,
        onOpenBrowser: (url: String, domain: String) -> Unit,
    ): Long {
        val entry = ReaderWindowEntry(nextId++, target, onOpenBrowser)
        open += entry
        return entry.id
    }

    fun close(id: Long) {
        val idx = open.indexOfFirst { it.id == id }
        if (idx >= 0) open.removeAt(idx)
    }

    /** 关掉所有阅读器（主窗口退出时用，避免留一堆孤儿窗口）。 */
    fun closeAll() {
        open.clear()
    }
}

/**
 * 一个阅读器窗口。
 *
 * 外观与主窗口一致：**无系统边框 + 透明 + 12dp 圆角 + 3dp 静态阴影**（见 `Main.kt`
 * 里 Chrome 的注释，两边是同一套参数）。所以阅读器窗口也要自带拖拽把手（在
 * [app.gamanga.desktop.ui.reader.ReaderBar] 的空白区里）和关闭键（顶栏那颗 ✕）。
 *
 * 尺寸：初始 1180×820，最小 720×480。每多开一个往下右各错 30px，避免完全重叠。
 */
@Composable
fun ReaderWindowHost(entry: ReaderWindowEntry, repository: LibraryRepository) {
    // 窗口状态必须在这里 remember：entry 是普通数据类，拿不到 Compose 的 rememberWindowState。
    // key = entry.id 由调用方保证（见 main() 里的 `key(entry.id)`）。
    val state = rememberWindowState(
        width = 1180.dp,
        height = 820.dp,
        position = WindowPosition(androidx.compose.ui.Alignment.Center),
    )
    Window(
        onCloseRequest = { ReaderWindows.close(entry.id) },
        state = state,
        title = "${entry.target.title} · Gaaa漫画",
        undecorated = true,
        transparent = true,
        // 任务栏/Alt+Tab 的窗口图标（2026-10-30 用户：阅读器没有图标）。
        // 与主窗口同一张 512 方图；不设的话 Windows 会给一个空白图标。
        icon = brandPainter("brand/logo-mark-512.png"),
    ) {
        LaunchedEffect(Unit) {
            // 透明底 + 最小尺寸：AWT 默认浅灰会在圆角外露出一圈
            window.background = java.awt.Color(0, 0, 0, 0)
            window.minimumSize = Dimension(720, 480)
            // 多开时错位叠放（第一个不动，之后每个往右下挪 30px）
            val offset = (ReaderWindows.open.indexOfFirst { it.id == entry.id }).coerceAtLeast(0) * 30
            if (offset > 0) {
                val loc = window.location
                window.setLocation(loc.x + offset, loc.y + offset)
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalComposeWindow provides window
        ) {
            AppTheme {
                ReaderWindowSurface(entry = entry, repository = repository, window = window)
            }
        }
    }
}

/**
 * 窗口内容（全屏感知的圆角/阴影外框 + [ReaderScreen]）。
 *
 * **为什么单独一个 Composable**：`window` 只有 `Window { }` 的内容 lambda 里才有
 * （`FrameWindowScope`），而"要不要收圆角"取决于 `ReaderFullscreen` 的状态——
 * 状态在组合期读，window 也只能在内容里拿，两者必须在同一层。
 */
@Composable
private fun ReaderWindowSurface(
    entry: ReaderWindowEntry,
    repository: LibraryRepository,
    window: java.awt.Window,
) {
    // 全屏时窗口矩形就是屏幕矩形，圆角/阴影边距必须收成 0——否则屏幕四角会露出桌面，
    // 看起来像"窗口没铺满"。
    //
    // **这里刻意不做过渡**（2026-10-30 第三轮）：`padding` 逐帧变化等于逐帧重新布局，
    // 内容区那张 3000×4000 的扫描图会跟着逐帧重新 Fit 一次——和"全屏动画"是同一个病。
    // 用户要的是「一下就变成全屏」，所以圆角跟着矩形一起瞬间收掉。
    val fullscreen = ReaderFullscreen.isFullscreen(window)
    val inset = if (fullscreen) 0.dp else 4.dp
    val radius = if (fullscreen) 0.dp else 12.dp
    val shape = RoundedCornerShape(radius)

    Box(
        Modifier
            .fillMaxSize()
            .padding(inset)
            .shadow(
                elevation = if (fullscreen) 0.dp else 3.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x4D000000),
                spotColor = Color(0x8A000000),
            )
            .clip(shape)
            .background(Tokens.Color.ReaderBg)
            .border(if (fullscreen) 0.dp else 1.dp, Color(0x14FFFFFF), shape)
    ) {
        ReaderScreen(
            target = entry.target,
            repository = repository,
            onClose = { ReaderWindows.close(entry.id) },
            onOpenBrowser = {
                entry.onOpenBrowser(
                    browserUrl(entry.target.type, entry.target.sourceMangaId),
                    domainOf(entry.target.type),
                )
            },
            // 配额错误直送登录页：Komiic 只有首页那条登录路径稳定
            onOpenLogin = LoginUrls.forDomain(domainOf(entry.target.type))
                ?.let { loginUrl ->
                    { entry.onOpenBrowser(loginUrl, domainOf(entry.target.type)) }
                },
        )
    }
}

/** 在 `application { }` 里铺开所有阅读器窗口（`main()` 只调这一处）。 */
@Composable
fun ReaderWindowLayer(repository: LibraryRepository) {
    ReaderWindows.open.forEach { entry ->
        key(entry.id) { ReaderWindowHost(entry, repository) }
    }
}
