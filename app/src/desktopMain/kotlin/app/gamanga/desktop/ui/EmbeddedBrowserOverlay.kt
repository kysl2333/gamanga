package app.gamanga.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.core.webview.CefRuntime
import app.gamanga.desktop.core.webview.JcefWebViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.roundToInt

/**
 * 内嵌浏览器覆盖层（M2）。
 *
 * 顶栏：后退/前进/刷新 + 地址栏 + 导出 Cookie + 关闭；主体是 OSR 画面
 * （CEF paint 帧经 BufferedImagePainter 绘制），鼠标/键盘事件转发给 CEF。
 *
 * 首次启动会阻塞下载 CEF 原生库（~150MB），期间展示阶段进度。
 */
@Composable
fun EmbeddedBrowserOverlay(
    url: String,
    domain: String,
    onClose: () -> Unit,
    onCookiesExported: (Map<String, String>) -> Unit,
    /** 上次保存的登录态 cookie，建浏览器前灌进 CEF（见 [JcefWebViewHost.importCookies]）。 */
    initialCookies: Map<String, String> = emptyMap()
) {
    var stage by remember { mutableStateOf("准备中") }
    var cefError by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { CefRuntime.ensureStarted { s -> stage = s } }
            ready = true
        } catch (t: Throwable) {
            // 记日志（2026-11-11）：用户报「浏览器创建失败」时，日志里必须有这条，
            // 否则只能靠 cef.log 里 libcef 的原生错误码反推。
            AppLog.w("Browser", "CEF 引擎启动失败", t)
            cefError = t.message ?: t::class.simpleName
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            cefError != null -> Column(
                Modifier.align(Alignment.Center).widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("浏览器引擎启动失败", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(cefError!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(
                    "请关闭 GA漫画 后重新打开。浏览器内核同一时间只允许一个进程使用，" +
                        "上一个窗口（或它没退干净的浏览器子进程）还在时会起不来。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onClose) { Text("关闭（返回）") }
            }

            !ready -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingArc(size = 32.dp)
                Spacer(Modifier.height(12.dp))
                Text(stage, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次运行需下载浏览器内核（约 150MB，仅此一次）；之后由后台预热，秒开",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                // 2026-10-24：CEF 起不来时原来只能干等（用户实测「卡在准备中、无法操作」）。
                // 给一个明确的退出口——至少能回到阅读器/书架。
                OutlinedButton(onClick = onClose) { Text("取消（返回）") }
            }

            else -> BrowserContent(url, domain, onClose, onCookiesExported, initialCookies)
        }
    }
}

/**
 * 先建宿主、再渲染界面（2026-10-24 修「点浏览器卡在准备中、整个窗口无法操作」）。
 *
 * [JcefWebViewHost] 的构造里有三个**同步 JNI** 调用：`CefApp.getInstance()`、
 * `createBrowser()`、`createImmediately()`。原来它写在 `remember { }` 里——也就是在
 * Compose 组合期、**EDT 上**执行。CEF 内部一旦等锁/等进程（比如上次的 CEF 子进程
 * 没退干净、user-data-dir 被占），EDT 就永久阻塞：界面停在「准备中」，连关闭按钮
 * 都点不动（用户实测「无法进行任何操作」）。
 *
 * 现在：构造放 IO 线程，UI 先渲染「正在创建浏览器」并保留关闭入口；
 * 超过 [HOST_BUILD_TIMEOUT_MS] 没建出来就显示失败原因 + 重试/关闭。
 */
@Composable
private fun BrowserContent(
    url: String,
    domain: String,
    onClose: () -> Unit,
    onCookiesExported: (Map<String, String>) -> Unit,
    initialCookies: Map<String, String> = emptyMap()
) {
    var host by remember(url) { mutableStateOf<JcefWebViewHost?>(null) }
    var buildError by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        host = null
        buildError = null
        val built = withContext(Dispatchers.IO) {
            runCatching { JcefWebViewHost(url, initialCookies = initialCookies) }
        }
        built.onSuccess { host = it }
            .onFailure {
                // 同样必须落日志：这条路是「引擎起来了但浏览器没建出来」，
                // 2026-11-11 用户报的就是这一条（界面上只有一行"浏览器创建失败"）。
                AppLog.w("Browser", "创建 WebView 失败 url=$url", it)
                buildError = it.message ?: it::class.simpleName ?: "未知错误"
            }
    }

    val ready = host
    when {
        buildError != null -> Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("浏览器创建失败", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(buildError!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(
                "先点「重试」。仍失败就关闭 GA漫画 后重新打开——" +
                    "浏览器内核同一时间只允许一个进程使用。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { attempt += 1 }) { Text("重试") }
                OutlinedButton(onClick = onClose) { Text("关闭浏览器") }
            }
        }

        ready == null -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingArc(size = 32.dp)
            Spacer(Modifier.height(12.dp))
            Text("正在创建浏览器…", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClose) { Text("取消（返回）") }
        }

        else -> BrowserBody(ready, url, domain, onClose, onCookiesExported)
    }
}

@Composable
private fun BrowserBody(
    host: JcefWebViewHost,
    url: String,
    domain: String,
    onClose: () -> Unit,
    onCookiesExported: (Map<String, String>) -> Unit,
) {
    val uiState by host.uiState.collectAsState()
    var address by remember { mutableStateOf(url) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    /**
     * 键盘归属：true = 按键发给页面，false = 留给地址栏等 Compose 输入控件。
     * 打开即认为页面持有键盘；点页面置 true，地址栏获得焦点置 false。
     */
    var pageHasKeys by remember { mutableStateOf(true) }
    /** 关闭流程只跑一次（防连点重复导出 cookie）。 */
    var closing by remember { mutableStateOf(false) }

    DisposableEffect(host) {
        onDispose { host.close() }
    }
    LaunchedEffect(host) {
        // 等一帧：节点未完成布局时 requestFocus 会静默失败
        withFrameMillis { }
        runCatching { focusRequester.requestFocus() }
    }

    // 地址栏跟随真实导航：页面内点链接、被广告跳转时地址栏原来一直是旧值
    // （用户报「包子漫画网页返回错误」时，地址栏还显示着 baozimh 的地址，误导排查）。
    // 只在页面持有键盘（地址栏没焦点）时同步，避免打字被覆盖。
    LaunchedEffect(uiState.url, pageHasKeys) {
        val u = uiState.url
        if (pageHasKeys && u.isNotBlank() && u != "about:blank") address = u
    }

    /**
     * 键盘转发走 AWT 的全局 [KeyEventDispatcher]，不用 Compose 的 onPreviewKeyEvent。
     *
     * 踩过的坑（连修两次）：Compose 的按键分发依赖「Compose 焦点正好落在那个 Box 上」，
     * 而 CEF 的焦点体系跟 Compose 焦点毫无关系——点地址栏、切窗口、任何一次焦点迁移
     * 都会让 Box 失焦，页面就彻底收不到按键；而且 Compose **从不派发 KEY_TYPED**，
     * 而 Chromium 正是靠 KEY_TYPED 往输入框插字符（只补发 KEY_PRESSED 时能滚能点但打不了字）。
     *
     * KeyEventDispatcher 在事件真正派发**之前**拿到 OS 原生事件，KEY_PRESSED /
     * KEY_RELEASED / KEY_TYPED 三种齐全，且不依赖任何焦点状态。我们只在
     * "最后一次交互在页面里"时整包转发并吞掉事件，地址栏一获得焦点就自动让路。
     */
    DisposableEffect(host) {
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { e ->
            val isKey = e.id == KeyEvent.KEY_PRESSED || e.id == KeyEvent.KEY_RELEASED ||
                e.id == KeyEvent.KEY_TYPED
            if (!isKey || !pageHasKeys) return@KeyEventDispatcher false
            // **文本只走 KEY_TYPED**（它带最终字符）。KEY_PRESSED/RELEASED 只转发非可打印键：
            //  - 同一个字符发两遍会让输入内容重复（登录密码因此永远不对）；
            //  - 小键盘的 VK_NUMPADn 经 Chromium 映射后是别的字符（"小键盘输入全乱"），
            //    而它的 KEY_TYPED 字符是准的，交给 KEY_TYPED 就够了。
            if (e.id != KeyEvent.KEY_TYPED) {
                val printable = e.keyChar != KeyEvent.CHAR_UNDEFINED &&
                    !Character.isISOControl(e.keyChar)
                val withCommandKey = e.modifiersEx and
                    (InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0
                if (printable && !withCommandKey) return@KeyEventDispatcher false
            }
            host.sendKeyEvent(e)
            true // 吞掉，否则 Compose 侧还会再处理一遍（比如空格触发按钮）
        }
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 顶栏 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = { host.goBack() }, enabled = uiState.canGoBack) { Text("←") }
            TextButton(onClick = { host.goForward() }, enabled = uiState.canGoForward) { Text("→") }
            TextButton(onClick = { host.reload() }) { Text("⟳") }
            // 站点首页 + 登录：Komiic 从漫画详情页弹出的登录框会报"密码错误"，
            // 但从首页登录页登录是正常的（实测）。所以这里直接把用户送到能登的那条路上，
            // 而不是让他自己在站内找入口。没有登录的站点（如 G社）不显示登录键——
            // 盗版聚合站不该登录。
            TextButton(onClick = { host.open("https://$domain/") }) { Text("首页") }
            app.gamanga.desktop.source.LoginUrls.forDomain(domain)?.let { loginUrl ->
                TextButton(onClick = { host.open(loginUrl) }) { Text("登录") }
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    // 地址栏一拿到焦点就把键盘让回去，否则打字会同时进页面和输入框
                    .onFocusChanged { if (it.isFocused) pageHasKeys = false },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                placeholder = { Text("输入网址", fontSize = 13.sp) }
            )
            Button(onClick = { host.open(address) }) { Text("前往", fontSize = 13.sp) }
            Button(onClick = {
                scope.launch {
                    val cookies = withContext(Dispatchers.IO) { host.exportCookies(domain) }
                    onCookiesExported(cookies)
                    snackbarHostState.showSnackbar("已导出 ${cookies.size} 个 Cookie（$domain）")
                }
            }) { Text("导出 Cookie", fontSize = 13.sp) }
            TextButton(onClick = {
                // 关闭时自动导出 cookie：登录一次就长期生效，不用每次记得点「导出 Cookie」。
                // 但**必须立刻关**：导出最长要等 3 秒，之前把 onClose 放在导出之后，
                // 用户点了没反应就会连点，每个点击又起一个协程，表现得像"点很多次才响应"。
                if (closing) return@TextButton
                closing = true
                scope.launch {
                    // 800ms 足够读到已有 cookie；没登录的域不必干等 3 秒
                    val cookies = withContext(Dispatchers.IO) { host.exportCookies(domain, 800) }
                    if (cookies.isNotEmpty()) onCookiesExported(cookies)
                    onClose()
                }
            }) { Text(if (closing) "关闭中…" else "✕") }
        }
        // 进度条别一直转（2026-10-24 用户实测「进度条一直在跑，不知道有什么没加载」）：
        // 站点常有永不结束的统计/广告请求，isLoading 可能长期为 true。超过 15 秒就
        // 换成一句可读的提示，让用户知道「不是卡死，是某个资源没回来」。
        var slowLoading by remember { mutableStateOf(false) }
        LaunchedEffect(uiState.isLoading) {
            slowLoading = false
            if (uiState.isLoading) {
                delay(15_000)
                slowLoading = true
            }
        }
        if (uiState.isLoading && !slowLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else if (slowLoading) {
            Text(
                "页面仍在加载（站点有资源未响应，通常不影响使用；点不动时先看这里）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // ---- OSR 画面 + 输入转发 ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .onSizeChanged { size: IntSize -> host.resize(size.width, size.height) }
                .focusRequester(focusRequester)
                // onFocusChanged 必须排在 focusable() **之前**才能观察到该焦点目标的状态
                .onFocusChanged { state -> host.setFocus(state.isFocused) }
                .focusable()
        ) {
            BrowserCanvas(host, focusRequester, onPagePress = {
                pageHasKeys = true
                host.setFocus(true)
            })
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

/**
 * 绘制浏览器帧并转发指针事件。帧按「画布像素 : 浏览器像素」映射坐标。
 */
@Composable
private fun BrowserCanvas(
    host: JcefWebViewHost,
    focusRequester: FocusRequester,
    onPagePress: () -> Unit
) {
    // java.awt.Component 是抽象类；事件源只需非 null 的具体 Component
    val dummy = remember { Canvas() }
    var lastButton by remember { mutableStateOf(MouseEvent.BUTTON1) }

    // 帧由 CEF 线程推入 StateFlow，Compose 只在真有新帧时重组/呈现。
    // 早期用「帧序号 + 每 vsync 轮询」，在 240Hz 屏上等于每秒逼出 240 次无意义呈现，
    // 与 CEF 的 paint 节奏拍频，是 G-Sync 闪烁的来源之一。
    val frame by host.frameFlow.collectAsState()
    val painter: Painter? = frame?.let { remember(it) { it.toPainter() } }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = "内嵌浏览器",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(host) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val pos = change.position
                            // 每次事件取当前帧尺寸：窗口放大/缩小后帧尺寸会变，
                            // 沿用启动时的尺寸会让坐标整体错位（点不到想要的元素）
                            val img = host.frame()
                            val bw = img?.width ?: 1
                            val bh = img?.height ?: 1
                            val bx = (pos.x * bw / size.width).toInt().coerceIn(0, bw - 1)
                            val by = (pos.y * bh / size.height).toInt().coerceIn(0, bh - 1)
                            val modifiers = modifiersOf(event.keyboardModifiers)

                            val id: Int
                            val button: Int
                            when {
                                event.type == PointerEventType.Press -> {
                                    // 点击画布即抢焦点（从地址栏等抢回），否则键盘事件到不了页面
                                    focusRequester.requestFocus()
                                    onPagePress()
                                    button = awtButton(event.buttons)
                                    lastButton = button
                                    id = MouseEvent.MOUSE_PRESSED
                                }

                                event.type == PointerEventType.Release -> {
                                    button = lastButton
                                    id = MouseEvent.MOUSE_RELEASED
                                }

                                event.type == PointerEventType.Scroll -> {
                                    // **单位坑**：Compose 桌面端 scrollDelta.y 的单位是「行」
                                    // （实测一格滚轮 = 1.0），而 JCEF 把 `rotation × amount`
                                    // 当**像素增量**（实测一格 ≈ 0.83px）。所以必须乘 120，
                                    // 才能还原成一格 ≈ 100px 的正常手感；
                                    // 直接拿 dy 当 rotation 的话每格只挪不到 1px = "滚不动"。
                                    val dy = change.scrollDelta.y
                                    if (dy != 0f) {
                                        val rot = (-dy * LINES_TO_PIXELS).roundToInt().let { r ->
                                            if (r == 0) (if (dy > 0) -1 else 1) else r
                                        }
                                        host.sendWheelEvent(
                                            MouseWheelEvent(
                                                dummy, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                                                modifiers, bx, by, 0, false,
                                                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rot
                                            )
                                        )
                                    }
                                    continue
                                }

                                change.pressed -> {
                                    button = lastButton
                                    id = MouseEvent.MOUSE_DRAGGED
                                }

                                else -> {
                                    button = MouseEvent.NOBUTTON
                                    id = MouseEvent.MOUSE_MOVED
                                }
                            }
                            // 按下/拖动必须把「哪个键按着」写进 modifiersEx：Chromium 靠它判断
                            // 这是一次拖拽而不是移动，缺了它拖动滚动条滑块时页面完全不动
                            // （只能点轨道跳位置）。
                            val withButtons = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED) {
                                modifiers or InputEvent.getMaskForButton(button)
                            } else {
                                modifiers
                            }
                            host.sendMouseEvent(
                                MouseEvent(
                                    dummy, id, System.currentTimeMillis(),
                                    withButtons, bx, by, 1, false, button
                                )
                            )
                        }
                    }
                }
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
    }
}

/** Compose 的「行」→ CEF 的「像素」换算系数（一格 ≈ 1 行 ≈ 100px）。 */
private const val LINES_TO_PIXELS = 120f

private fun awtButton(buttons: androidx.compose.ui.input.pointer.PointerButtons): Int = when {
    buttons.isPrimaryPressed -> MouseEvent.BUTTON1
    buttons.isSecondaryPressed -> MouseEvent.BUTTON3
    buttons.isTertiaryPressed -> MouseEvent.BUTTON2
    else -> MouseEvent.BUTTON1
}

private fun modifiersOf(m: androidx.compose.ui.input.pointer.PointerKeyboardModifiers): Int {
    var mask = 0
    if (m.isShiftPressed) mask = mask or InputEvent.SHIFT_DOWN_MASK
    if (m.isCtrlPressed) mask = mask or InputEvent.CTRL_DOWN_MASK
    if (m.isAltPressed) mask = mask or InputEvent.ALT_DOWN_MASK
    if (m.isMetaPressed) mask = mask or InputEvent.META_DOWN_MASK
    return mask
}
