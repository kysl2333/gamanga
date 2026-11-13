package app.gamanga.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.core.AppVersion
import app.gamanga.desktop.core.RenderBackend
import app.gamanga.desktop.core.webview.CefRuntime
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.SourceConfig
import app.gamanga.desktop.source.Http
import app.gamanga.desktop.source.Sources
import app.gamanga.desktop.ui.GamangaApp
import app.gamanga.desktop.ui.shell.DragStripHeight
import app.gamanga.desktop.ui.theme.AppTheme
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import kotlin.math.roundToInt

/**
 * 窗口外观（阶段 4，2026-10-26）。
 *
 * 无系统边框 + 自绘圆角。**没有外阴影**——用户 2026-10-26 实测反馈：「窗口边缘有一层不应该有的
 * 阴影，选中对齐的是那个阴影而不是倒角的边框」，所以把 `Modifier.shadow` 和它需要的透明边距
 * 一起去掉了，窗口现在贴着内容边缘结束。
 */
private object Chrome {
    /** 初始宽高比（只在**没有保存过窗口尺寸**时用来算初始高度）。 */
    const val ASPECT = 3f / 2f

    /** 内容圆角（原型 `border-radius:12px`）。 */
    val CornerRadius = 12.dp

    /** 最小尺寸。 */
    val MinSize = Dimension(900, 600)
}

fun main() = application {
    installCrashLogging()
    val repository: LibraryRepository = AppGraph.libraryRepository
    // ---- 渲染后端必须在第一个 Window（也就是第一个 SkiaLayer）之前定下来 ----
    //
    // 2026-10-30 批次46：skiko 在 `SkiaLayer` 初始化时读 `skiko.renderApi`，
    // 之后再设**完全无效**。`application { }` 的内容是组合期执行的，
    // 第一个 `Window(...)` 出现在后面，所以放在这里仍然早于任何 Skia 组件。
    runCatching {
        RenderBackend.apply(hwAccel = repository.getPref(RenderBackend.PREF_HW_ACCEL) != "0")
    }.onFailure { AppLog.w("Render", "读取渲染偏好失败，按默认（硬件加速）启动", it) }
    // **不再回填示例数据**（用户 2026-10-28 明确要求）：首次安装、以及「清空所有数据」
    // 之后重启，书架和阅读历史都必须是真的空的。以前这里调 seedIfEmpty() 塞 6 本假漫画
    // + 1 条假进度，导致清空数据后一重启又是满屏测试封面。
    // 需要造数据的场景（单测）显式调 `seedDemoData()`，生产路径不再调用。
    // 老版本已经装过/跑过的机器，库里还留着那 6 本 → 这里一次性清掉（判据保守：
    // id 是种子 id 且没有封面，用户自己补过封面的不动）。
    runCatching { repository.purgeDemoData() }
        .onSuccess { n -> if (n > 0) AppLog.i("Library", "已清理 $n 本历史示例数据") }
    // F-22：源站地址配置（sources.json）。**必须在任何 Sources.all() / of() 之前**——
    // 适配器是惰性单例，第一次构造时就把 baseUrl / UA 读进去了，之后再改配置要重启。
    // remember：`application { }` 的内容会随重组再执行，不能每次重组都重读文件。
    remember { SourceConfig.loadOnce() }
    // 源适配器借内嵌浏览器导出的登录态（Komiic 图片配额按账号算，登录后额度才够用）。
    // 必须在任何 Sources.all() / of() 之前装好——适配器是惰性创建的。
    Sources.installCookieProvider { domain ->
        repository.getPref(LibraryRepository.cookiePrefKey(domain))
    }
    // R-04：上次进程被杀时停在一半的下载任务退回队列续跑（借登录态之后才能取图）
    AppGraph.downloadManager.resumeInterrupted()

    // 第八轮 #8：恢复 HTTP 代理配置（全局开关 + 协议 + 每个源的独立开关）
    Http.configureProxy(
        enabled = repository.getPref(LibraryRepository.PREF_PROXY_ENABLED) == "1",
        host = repository.getPref(LibraryRepository.PREF_PROXY_HOST),
        port = repository.getPref(LibraryRepository.PREF_PROXY_PORT)?.toIntOrNull() ?: 0,
        protocol = repository.getPref(LibraryRepository.PREF_PROXY_PROTOCOL) ?: "HTTP",
    )
    Http.proxyPolicy = { type ->
        repository.getPref(LibraryRepository.proxySourcePrefKey(type)) != "0"
    }

    // 后台预热浏览器内核：原生库已下载过就直接起 CEF，第一次开内嵌浏览器不再等那 2 秒
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 2026-11-13（A5b）：完整版把随包内核**启动就搬到纯 ASCII 目录**。
            // 用户口径「直接挪，下完整版就说明需要浏览器」——不等第一次点浏览器。
            // 开发环境下 `app/jcef-bundle` 是符号链接，函数内部会跳过，不会动开发环境。
            runCatching { CefRuntime.migrateBundledRuntimeOnStartup() }
            runCatching { CefRuntime.warmUpIfDownloaded() }
        }
    }

    // 窗口尺寸：存过就用存的（用户缩成什么样就是什么样）；没存过用 3:2 作初始值。
    // **不再强制锁定 3:2**——用户 2026-10-26：「固定 3:2 比例是最初打开程序的一个状态」，
    // 也就是只作初始尺寸，之后上下左右都能自由缩放。
    val savedSize = repository.getPref(LibraryRepository.PREF_WINDOW_SIZE)
    val savedWidth = savedSize?.substringBefore(',')?.toIntOrNull()
    val savedHeight = savedSize?.substringAfter(',')?.toIntOrNull()
    val initialWidth = savedWidth ?: 1280
    val initialHeight = savedHeight ?: (initialWidth / Chrome.ASPECT).roundToInt()
    val windowState = rememberWindowState(
        width = initialWidth.dp,
        height = initialHeight.dp,
        position = WindowPosition(Alignment.Center)
    )

    /** 关窗统一走这里：存尺寸 → 退应用。右上角 X 与系统关闭都调它。 */
    fun closeApp() {
        runCatching {
            repository.setPref(
                LibraryRepository.PREF_WINDOW_SIZE,
                "${windowState.size.width.value.toInt()},${windowState.size.height.value.toInt()}"
            )
        }
        // 2026-10-30：阅读器现在是**独立窗口**（可多开）。退出应用前先把它们收干净，
        // 否则 AWT 会留下几个孤儿窗口，进程也不一定退得掉。
        app.gamanga.desktop.ui.reader.ReaderWindows.closeAll()
        exitApplication()
    }

    Window(
        onCloseRequest = ::closeApp,
        state = windowState,
        title = AppVersion.TITLE,
        // 无系统边框：标题栏、边框、系统按钮全部自绘
        undecorated = true,
        // 必须透明，否则四角会露出窗口底色，圆角等于白做
        transparent = true,
        // 程序图标（用户 2026-10-27 给的 logo-C，已去底裁成 512 方图）
        icon = brandPainter("brand/logo-mark-512.png"),
    ) {
        // 窗口自身背景刷透明（AWT 默认浅灰会在圆角外露出一圈）
        LaunchedEffect(window) {
            window.background = java.awt.Color(0, 0, 0, 0)
            window.minimumSize = Chrome.MinSize
            // 把**实际**跑起来的渲染后端写进日志（2026-10-30 批次46）：
            // skiko 的回退是静默的，驱动不支持时会自己掉到 CPU 光栅而界面照开，
            // 用户只会觉得"卡"。日志里有这一行，"卡"到底是 GPU 没起来还是别的原因，
            // 一眼就能分开。等 1.5s 是为了让 SkiaLayer 走完第一帧、把 renderApi 定下来。
            delay(1500)
            AppLog.i("Render", "实际渲染后端：${RenderBackend.describe(window)}")
        }

        // 把窗口注入 CompositionLocal：壳层/阅读器的拖拽把手要靠它 setLocation
        androidx.compose.runtime.CompositionLocalProvider(
            app.gamanga.desktop.ui.shell.LocalComposeWindow provides window
        ) {
        AppTheme {
            // 窗口边界（用户 2026-10-26）：「界面的边界跟电脑画面不够清晰，给边界加个小阴影
            // 不要太死，尽量集中，不要吃太多性能做静态」。
            //
            // 做法：四周留 **4dp 透明边距**给阴影落脚，内容是**静态** shadow(4dp) + 1dp 描边。
            // - 不能用动画/模糊：静态阴影由 Skia 一次性光栅化，零每帧成本；
            // - 4dp 是刻意压窄的：R-118 那次被否掉是因为阴影铺得太宽（12dp+），
            //   用户看到的是「选中对齐的是阴影不是边框」。4dp 只做过渡，不抢边框。
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .shadow(
                        // 用户三轮反馈收敛到：①要有阴影 ②要加深 ③**四角太明显像方形**
                        //
                        // 为什么 6dp 会「像方形」：Skia 的阴影在大 blur 半径下，12dp 圆角会被
                        // 模糊摊平，四个角就堆出一块方形暗影。所以**不能靠加大 elevation 加深**。
                        // 现在改成 3dp（阴影贴边、圆角保形）+ 更深的两层色，靠不透明度而不是半径加深。
                        elevation = 3.dp,
                        shape = RoundedCornerShape(Chrome.CornerRadius),
                        clip = false,
                        ambientColor = Color(0x4D000000),
                        spotColor = Color(0x8A000000),
                    )
                    .clip(RoundedCornerShape(Chrome.CornerRadius))
                    .background(Tokens.Color.BgBase)
                    .border(1.dp, Color(0x0F000000), RoundedCornerShape(Chrome.CornerRadius))
            ) {
                // 页面内容。**拖拽把手不再放在这里**——它现在属于壳层（`NormalShell`）和
                // 阅读器顶栏各自的布局带里，见 `ui/shell/WindowDrag.kt` 的三轮方案说明。
                // 放在这里（窗口层）会盖住阅读器顶栏按钮，且与滚动容器抢垂直拖拽。
                LaunchGate { GamangaApp(repository, onClose = ::closeApp) }
            }
        }
        }
    }

    // ---- 阅读器窗口层（2026-10-30 用户要求 4）----
    //
    // **必须在主 Window 之外**（同一层 `application` 作用域）：阅读器是独立窗口，
    // 主界面可以同时开多个，各窗口的阅读状态互不干扰。开关窗口 = 增删这个列表里的条目，
    // Compose 自己会创建/销毁对应的真实窗口。
    app.gamanga.desktop.ui.reader.ReaderWindowLayer(repository)
}

/**
 * 读 classpath 里的品牌图（`app/src/desktopMain/resources/brand/`）。
 *
 * 为什么不用 `painterResource`：Compose Multiplatform 1.7 之后那个 API 迁到了
 * `org.jetbrains.compose.resources`（需要生成的 `Res` 类），而项目没有引入资源插件。
 * `classLoader.getResourceAsStream` + Skia 解码是纯 desktop 路径，零配置、启动时一次性解码。
 *
 * 也不用 `androidx.compose.ui.res.loadImageBitmap`：它在 1.7 已标 `@Deprecated`
 * （提示迁到 `decodeToImageBitmap`），而这个项目当前 compose 版本里两种写法都能编，
 * 但 Skia 这条路不依赖任何 res 包，跨版本最稳。
 */
private fun decodeBrand(path: String): ImageBitmap? = runCatching {
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(path) ?: return null
    val bytes = stream.use { it.readBytes() }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

internal fun brandPainter(path: String, quality: FilterQuality = FilterQuality.High): Painter? =
    decodeBrand(path)?.let { BitmapPainter(it, filterQuality = quality) }

private fun brandBitmap(path: String): ImageBitmap? = decodeBrand(path)

/** 揭幕缓动：ease-out-quint。起手果断、落点极慢，慢而不拖（与详情面板同一颗曲线）。 */
private val SplashEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** 揭幕时长。原 420ms，2026-10-27 第五轮用户「再慢 50%」→ **630ms**。 */
private const val SplashRevealMs = 630

/**
 * logo 抖动（原 `settle`）时长。
 *
 * **曲线与幅度完全沿用旧实现**：`tween(900, SplashEasing)` 跑 0→1，驱动
 * `sin(t·2π)` 的 4° 摆动与 `sin(t·π)` 的 6dp 浮动。
 * 2026-11-13 只改了一件事：**起点提前到与弹入同刻**（原来是等横幅落定才开始）。
 * 这里由 900 放到 1100，是为了让整段摆动在闪屏消失前能完整看完一遍（不改变单次摆动的形状）。
 */
private const val SplashWobbleMs = 1100

/**
 * 抖动跑完到揭幕之间的停顿。
 *
 * **删掉了原来 1600ms 的长停留**：用户要「logo 动画结束就进主菜单」。
 * 120ms 只是让最后一帧停稳，不是展示时长。
 */
private const val SplashSettlePauseMs = 120L

/** logo 直径。原 136dp，2026-11-13 用户要求「放大到 160%」→ 217.6 ≈ **218dp**。 */
private val SplashLogoSize = 218.dp

/**
 * 启动闪屏（F-131；2026-11-13 开源版简化）。
 *
 * ## 2026-11-13 用户口径（1.2.1）
 *
 * - 「移除万圣节的错峰进场」→ 横幅整段删掉，闪屏只剩一枚居中的 logo。
 * - 「要等主图标动画结束再进主菜单，应该会比带横幅的快」→ 删掉 1600ms 长停留。
 * - 「logo 动效不变，现在 logo 是 100% 的话 logo 大小要放大到 160%」→ 136dp → 218dp，曲线不动。
 * - 「logo 出现后开抖动，而不是像以前要等横幅出现才跟着一起抖」→ **只把抖动起点提前**，
 *   动效本身（30dp 升起 + spring 弹入 + 4° 摆动 + 6dp 浮动）一点不改。
 * - 2026-11-13 中途试过「从上方 200dp 弹簧掉落」，用户否决：「还是不对，就用以前的动效去掉横幅，
 *   把 logo 抖动提前就行了」→ 已回滚到原动效。
 *
 * ## 节奏
 *
 * 弹入与抖动同时开始，**两段都跑完**（约 1.1s）→ 停顿 120ms → 揭幕 630ms，
 * 整段约 1.85s，比带横幅的约 2.4s 快四分之一。
 *
 * ## 性能约定
 *
 * 主界面**在闪屏期间就已经完成布局**（只是透明），所以揭幕时不会有一帧空窗 ——
 * 这是「加快出现主菜单」的关键：缩短的是遮挡时间，不是加载时间。
 */
@Composable
private fun LaunchGate(content: @Composable () -> Unit) {
    val badge = remember { brandBitmap("brand/logo-badge-512.png") }
    val appear = remember { Animatable(0f) }
    val wobble = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var splashDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 弹入与抖动**同时**开始、**都跑完**才往下走。
        // 旧实现是「弹入 → 横幅掉落 → 才开始抖」，用户要的就是把最后那一步并到第一步里。
        coroutineScope {
            launch {
                appear.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
                )
            }
            wobble.animateTo(1f, tween(SplashWobbleMs, easing = SplashEasing))
        }
        // 抖动跑完后的极短停顿：最后一帧停稳就走
        delay(SplashSettlePauseMs)
        // 揭幕 630ms：闪屏上浮 + 放大 + 淡出，主界面同时放大 + 淡入
        reveal.animateTo(1f, tween(SplashRevealMs, easing = SplashEasing))
        splashDone = true
    }

    Box(Modifier.fillMaxSize()) {
        // 主界面：闪屏期间透明且略小，揭幕时「放大 + 淡入」浮上来
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = reveal.value
                    val s = 0.99f + 0.01f * reveal.value
                    scaleX = s
                    scaleY = s
                }
        ) { content() }

        if (!splashDone) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Tokens.Color.BgBase)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } } }
                    .graphicsLayer {
                        // 整块闪屏**向上浮走**（不是原地淡出）：有离开方向才不会「跳跃换页」
                        alpha = 1f - reveal.value
                        translationY = -18.dp.toPx() * reveal.value
                        val s = 1f + 0.04f * reveal.value
                        scaleX = s
                        scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 2026-11-13：整块 Stage 的 -2° 倾斜与 badge 的 -86dp 横移一并去掉 —— 用户要「居中圆形 logo」
                Box(contentAlignment = Alignment.Center) {
                    if (badge != null) {
                        Image(
                            bitmap = badge,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SplashLogoSize)
                                .graphicsLayer {
                                    // 以下四项**逐字沿用旧实现**，只有 settle 换成提前启动的 wobble
                                    val s = 0.82f + 0.18f * appear.value
                                    scaleX = s
                                    scaleY = s
                                    alpha = appear.value.coerceIn(0f, 1f)
                                    // 从下方 30dp 升起 + 6dp 上下浮动
                                    translationY = -30.dp.toPx() * (1f - appear.value) -
                                        6.dp.toPx() * kotlin.math.sin(wobble.value * Math.PI).toFloat()
                                    // 入场回正（-14° → 0）+ 4° 左右摆动
                                    rotationZ = -14f * (1f - appear.value) +
                                        4f * kotlin.math.sin(wobble.value * Math.PI * 2).toFloat()
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把所有未捕获异常写进 [AppLog]（带完整堆栈 + 时间戳 + 线程名）。
 *
 * **为什么换掉 System.err**：实测多线程并发写 System.err 时，30 个 EDT 异常的
 * printStackTrace 互相覆盖，日志里只剩 30 句 "Exception in thread ..." 前缀、
 * 一条堆栈都不剩——「知道出错了，但查不到任何东西」。AppLog 用一把锁 + 每次
 * 落盘，保证头部与堆栈连续完整；AWT 内部自己吞掉的异常则由关键路径的
 * try/catch 双保险（下载协程、拖拽滚动协程各自记录）。
 *
 * 只记录、不吞：EDT 上的异常本就不会终止应用，这里不改变行为，只是让它可见。
 */
private fun installCrashLogging() {
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { AppLog.uncaught(thread.name, error) }
            .onFailure { System.err.println("AppLog uncaught write failed: ${it.message}") }
    }
}
