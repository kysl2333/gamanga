package app.gamanga.desktop.core.webview

import app.gamanga.desktop.core.AppLog
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * CEF 运行时引导（M2）。
 *
 * - jcefmaven 的 [CefAppBuilder] 负责首次运行时下载平台原生库（~150MB，缓存于用户目录），
 *   之后启动秒开。
 * - CEF 单线程消息循环模式：启动后在 EDT 上用 Swing Timer 周期泵 [CefApp.doMessageLoopWork]。
 * - OSR（离屏渲染）：`windowless_rendering_enabled = true`，画面经 paint 事件交给 Compose 绘制。
 *
 * 线程约定：本方法可在任意线程调用（下载期间会阻塞该线程，UI 侧请放 IO 调度）；
 * 消息泵固定跑 EDT——Compose desktop 的主循环即 EDT，天然满足。
 */
object CefRuntime {

    private val started = AtomicBoolean(false)
    private var pump: Timer? = null

    /**
     * 启动互斥（2026-10-24 修「点浏览器卡在准备中、无法操作」）。
     *
     * 旧实现只用 `started` 一个 CAS 标志：第一个线程进入后立刻把它置 true，
     * **在 `builder.build()` 还没返回时**，第二个调用者就认为「已经启动」直接返回，
     * 于是 UI 立刻去 `CefApp.getInstance()` 造浏览器——此时原生库根本没起来，
     * 调用会阻塞或抛错，表现为「准备中卡死」。现在拆成 starting/started 两态：
     * 后来者等在 [readyLatch] 上（带超时），拿到明确结论再继续。
     */
    private val starting = AtomicBoolean(false)
    private val readyLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * 内核搬迁状态（2026-11-13，A5b）。
     *
     * `migrateBundledRuntimeOnStartup()` 在启动的后台线程里搬 372MB，而用户可能几秒内就点开浏览器。
     * 如果不让 [ensureStarted] 等搬迁结束，CEF 会拿**旧的（中文）路径**去初始化原生库，
     * 直接触发那个只有 `hs_err_pid` 的原生崩溃。这里用一个 latch 把两者串起来。
     */
    private val migrationStarted = AtomicBoolean(false)
    private val migrationDone = java.util.concurrent.CountDownLatch(1)

    @Volatile
    var startupStage: String = "未启动"
        private set

    val isStarted: Boolean get() = started.get()

    /** 幂等启动。首次调用会阻塞下载原生库；[progress] 收到阶段名（供 UI 展示）。 */
    fun ensureStarted(progress: (String) -> Unit = {}) {
        if (started.get()) return
        // 启动时的内核搬迁还没结束就先等它（最多 60s）：否则会用旧的中文路径初始化原生库，
        // 触发只有 hs_err_pid 的原生崩溃（2026-11-13 审查发现的竞态）。
        if (migrationStarted.get()) {
            runCatching { migrationDone.await(60, java.util.concurrent.TimeUnit.SECONDS) }
        }
        if (!starting.compareAndSet(false, true)) {
            // 另一个线程正在启动：等它出结论（最长 3 分钟），不要重复 build
            readyLatch.await(180, java.util.concurrent.TimeUnit.SECONDS)
            if (!started.get()) error("浏览器引擎启动失败：$startupStage")
            return
        }
        try {
            startupStage = "启动中"
            val builder = CefAppBuilder()
            builder.setProgressHandler { stage, _ -> progress(stage.name) }
            // **只在显式指定时覆盖 CEF 运行时目录**（2026-10-24）。
            // 不能无条件调 setInstallDir：它会走「创建安装目录」逻辑，遇到已存在的
            // jcef-bundle（预置的或 junction）就抛
            // `IOException: Could not create installation directory`（6 个 WebView 测试全挂）。
            // 默认（无属性）保持 jcefmaven 原生行为：installDir = cwd，找 cwd/jcef-bundle。
            // **2026-11-13（A5）改为统一走 [cefRootDir]**，不再只在显式属性时覆盖：
            //   · 程序目录是纯 ASCII 且已有内核  → 用它（完整版随包分发，零拷贝）
            //   · 程序目录含中文 / 没有内核      → 回退 LOCALAPPDATA → ProgramData 的纯 ASCII 目录
            //   · 轻量版该目录为空               → jcefmaven 首次点击时自行下载到那里
            // 仍然用 runCatching：`setInstallDir` 遇到已存在的目录会抛
            // `IOException: Could not create installation directory`（2026-10-24 实测 6 个 WebView
            // 测试全挂）。真抛了就退回 jcefmaven 默认行为，不至于起不来。
            val cefDir = cefRootDir()
            AppLog.i("Cef", "CEF 目录=${cefDir.absolutePath}（存在=${cefDir.isDirectory}，非ASCII=${containsNonAscii(cefDir.absolutePath)}）")
            runCatching { builder.setInstallDir(cefDir) }
                .onFailure { AppLog.w("Cef", "setInstallDir 失败，回退 jcefmaven 默认（cwd/jcef-bundle）", it) }
            // **关掉隐藏窗口的节流与冻结**（R-27 的真正原因）：
            // 我们的 OSR 宿主是一个移到屏幕外 (-10000,-10000) 的 JFrame。Windows 的
            // native window occlusion 计算会判定这个窗口"被完全遮挡"，Chromium 随后把整个
            // 页面**冻结**——实测症状极其误导：
            //   同步 JS 能执行（executeJavaScript 立刻返回）、document.visibilityState 仍报
            //   "visible"、`document.timeline.currentTime` 也在推进（tl=1508），
            //   但 **setTimeout(...,0) 与 setInterval 一次都不跑、requestAnimationFrame 永不回调**。
            // 结果：G社/包子章节页（服务端只给转圈占位、图片靠客户端 JS 异步插入）永远是转圈，
            // 表现为「该话没有图片」。
            // CalculateNativeWinOcclusion 是这条链路的总闸，关掉它页面才不会被判遮挡。
            builder.addJcefArgs(
                // **不要用 `--enable-logging`**（2026-10-25 R-97）：Windows 上它会让 Chromium
                // 给子进程**分配一个控制台窗口**——用户实测「搜索拷贝漫画时弹出一个黑框终端」，
                // 里面是 CEF 的日志。改为把日志写文件（见下方 CefSettings.log_file）。
                // 顺带关掉扩展：CEF 有独立的 user-data-dir，这条**不会影响系统 Chrome/Edge**，
                // 只是不再去扫注册表里第三方浏览器注册的扩展（那条 quark-cloud-drive 的
                // `external_registry_loader_win.cc` ERROR 就是扫出来的）。
                "--disable-extensions",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling," +
                    "TimerThrottlingForHiddenFrames,StopLoadingInBackground"
            )
            builder.getCefSettings().apply {
                windowless_rendering_enabled = true
                // ColorType 是 CefSettings 的内部类，构造参数序为 (alpha, red, green, blue)
                background_color = ColorType(255, 255, 255, 255)
                // **cookie 持久化**：不设 cache_path 时 CEF 的 cookie store 只在内存，
                // 每次重启应用内嵌浏览器都是未登录状态——用户反馈"每次都要重新登录"。
                // 目录和本地库放一起（%LOCALAPPDATA%\Gamanga\），应用卸载/重置时一起清。
                cache_path = java.io.File(
                    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                    "Gamanga/cef-cache"
                ).apply { mkdirs() }.absolutePath
                // 登录态 cookie 多数是 session cookie（无 expires），不置这个它们不落盘
                persist_session_cookies = true
                // 日志落文件（替代 --enable-logging 的控制台输出）：native crash 排查仍然有据可查，
                // 但不会再弹黑框。位置和 AppLog 放一起。
                runCatching {
                    val logDir = java.io.File(
                        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                        "Gamanga/logs"
                    ).apply { mkdirs() }
                    val cefLog = java.io.File(logDir, "cef.log")
                    // Chromium 自己不做轮转：超过 5MB 就删掉重来，避免无限增长
                    if (cefLog.length() > 5L * 1024 * 1024) cefLog.delete()
                    log_file = cefLog.absolutePath
                    log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
                }
            }
            // 兼容模式（-Dgamanga.compat=1 或 GAMANGA_COMPAT=1）：
            // G-Sync/VRR 显示器上，OSR 的独立 GPU 合成链在部分驱动组合下会让整屏
            // 明暗闪烁（figcraft#953 同现象：静止不闪、一动就闪）。关掉 GPU 合成改为
            // 软件合成即可消除；漫画页多为静态图，性能代价可忽略。
            if (System.getProperty("gamanga.compat") == "1" || System.getenv("GAMANGA_COMPAT") == "1") {
                builder.addJcefArgs("--disable-gpu-compositing")
                startupStage = "兼容模式"
            }
            // HTTP 代理（第九轮 #5）：代理开启时内嵌浏览器也走——CEF 的网络栈
            // 独立于原生 HttpClient，只在启动参数里能注（CefApp 是进程级单例，
            // 运行中改协议/地址要重启应用生效，设置页有文案说明）。
            val proxy = app.gamanga.desktop.source.Http.currentProxy()
            if (proxy.enabled && !proxy.host.isNullOrBlank() && proxy.port in 1..65535) {
                val scheme = if (proxy.protocol == "SOCKS5") "socks5" else "http"
                builder.addJcefArgs("--proxy-server=$scheme://${proxy.host}:${proxy.port}")
                startupStage = "启动中（代理 $scheme://${proxy.host}:${proxy.port}）"
            }
            startupStage = "启动中"
            val startedAt = System.currentTimeMillis()
            builder.build()
            startupStage = "就绪"
            started.set(true)
            AppLog.i("Cef", "就绪（${System.currentTimeMillis() - startedAt}ms）内核=${bundleDir().absolutePath}")
            startMessagePump()
        } catch (t: Throwable) {
            startupStage = "失败：${t.message ?: t::class.simpleName}"
            // **必须落日志**（2026-11-11 用户实测「浏览器创建失败」却查不到原因）：
            // 这条失败原来只在界面上显示一行红字，日志里一片空白，
            // 只能靠 cef.log 里 libcef 自己的 `ContentMainRun failed with exit code 21`
            // 反推是「cache_path 被另一个进程占用」。
            AppLog.w("Cef", "CEF 启动失败：stage=$startupStage", t)
            throw t
        } finally {
            starting.set(false)
            readyLatch.countDown()
        }
    }

    private fun startMessagePump() {
        val start = Runnable {
            var timer: Timer? = null
            timer = Timer(8) {
                // **CefApp 可能在退出时先被终止**（2026-10-25 日志：`E/Uncaught:
                // IllegalStateException: CefApp was terminated` 在 EDT 上刷屏）。
                // 这里自己兜住：终止就把表停掉，不再每秒 125 次往 EDT 抛异常。
                try {
                    CefApp.getInstance().doMessageLoopWork(0L)
                } catch (e: IllegalStateException) {
                    AppLog.i("Cef", "CefApp 已终止，停止消息泵")
                    timer?.stop()  // javax.swing.Timer 用 stop()
                    pump = null
                } catch (e: Throwable) {
                    AppLog.w("Cef", "消息泵异常", e)
                }
            }
            pump = timer
            timer?.start()
        }
        if (SwingUtilities.isEventDispatchThread()) start.run() else SwingUtilities.invokeLater(start)
    }

    /**
     * 预热：原生库**已经下载过**就在后台把 CEF 起起来，这样第一次点「浏览器查看」不用再干等
     * 约 2 秒的初始化（用户看到的就是那个 `INITIALIZED` 屏）。
     * 没下载过则什么都不做——不能因为用户没点浏览器就偷偷下 150MB。
     */
    /**
     * CEF 运行时根目录（其下应有 `jcef-bundle/`）。
     *
     * **默认是 cwd**。但 cwd 可能是中文路径（例如 `D:/我的漫画库/Gamanga/app`），而 CEF 的
     * Windows 实现对非 ASCII 路径支持很差——用户实测「点拷贝漫画源过几秒闪退」，
     * 且崩溃只有 `hs_err_pid*.log`（native crash，无 Java 堆栈），首要嫌疑就是路径。
     *
     * **不要用 `CefAppBuilder.setInstallDir()` 来做这件事**（2026-10-24 实测）：
     * 它会走「创建安装目录」逻辑，和预置好的 `jcef-bundle` 冲突，
     * 直接抛 `IOException: Could not create installation directory`（6 个 WebView 集成测试全挂）。
     * 正确做法是**改 cwd**：启动脚本先 `cd` 到纯英文目录（里面有 `jcef-bundle` 的目录联结），
     * jcefmaven 默认 installDir 就是 cwd，自然走英文路径。
     */
    /**
     * 程序根目录。
     *
     * **不能用 `user.dir` 当程序目录**（2026-11-13 打包冒烟抓到的真 bug）：
     * jpackage 的 exe 双击启动时，`user.dir` 是启动器继承来的当前目录，**不是 exe 所在目录**。
     * 实测完整版解压到 `D:\我的软件\Gamanga` 后双击，搬迁逻辑找不到 `jcef-bundle`，
     * 包里的 372MB 内核白带，程序回退去联网重下。
     *
     * jpackage 启动器会设置 `jpackage.app-path` 系统属性（exe 的绝对路径），用它反推才可靠。
     * 开发态（bat 启动）没有这个属性，退回 `user.dir`（bat 里已 cd 到 app 目录）。
     */
    private fun appHomeDir(): java.io.File {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { path ->
            java.io.File(path).parentFile?.let { return it }
        }
        return java.io.File(System.getProperty("user.dir"))
    }

    fun cefRootDir(): java.io.File {
        // ① 显式指定优先级最高（开发期 bat / 高级用户手动指定）
        System.getProperty("gamanga.cef.dir")?.takeIf { it.isNotBlank() }?.let {
            return java.io.File(it)
        }

        // ② 程序自己的目录（完整版把内核随包放的默认位置）
        val bundled = java.io.File(appHomeDir(), "jcef-bundle")
        if (bundled.isDirectory && !containsNonAscii(bundled.absolutePath)) return bundled

        // ③ 回退到纯 ASCII 的用户目录 / 公共目录（2026-11-13 新增）
        //
        // 为什么必须回退：CEF 的 Windows 实现在**非 ASCII 路径**下会原生崩溃（无 Java 堆栈，
        // 只有 hs_err_pid）。中文用户常见的 `C:\Users\张三\`、`D:\我的软件\` 全部中招。
        // 顺序：LOCALAPPDATA（每用户可写，正常安装不需要提权）→ ProgramData（公共可写）。
        val candidates = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { java.io.File(it, "Gamanga/cef-root") },
            System.getenv("ProgramData")?.let { java.io.File(it, "Gamanga/cef-root") },
        )
        return candidates.firstOrNull { !containsNonAscii(it.absolutePath) } ?: bundled
    }

    /** CEF 对非 ASCII 路径支持极差（实测原生崩溃）。判断字符串里有没有非 ASCII 字符。 */
    internal fun containsNonAscii(path: String): Boolean = path.any { it.code > 0x7F }

    /**
     * CEF 运行时实际所在目录。
     *
     * 注意 `CefAppBuilder.setInstallDir(x)` 的语义是 **x 本身就是 bundle 目录**
     * （2026-10-24 实测：setInstallDir(cefRoot) 之后文件直接落在 cefRoot/ 下，
     * 不是 cefRoot/jcef-bundle/）。所以这里返回的是「含 libcef.dll 的那个目录」。
     */
    fun bundleDir(): java.io.File = cefRootDir()

    /** CEF 的磁盘缓存目录（cookie / GPU cache 等运行时数据）。 */
    fun cacheDir(): java.io.File = java.io.File(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "Gamanga/cef-cache",
    )

    /**
     * 完整版启动时把**随包的内核**搬到纯 ASCII 目录。
     *
     * 2026-11-13 用户口径（Q13 修订）：「直接挪，下完整版就说明需要浏览器，看本地的用另一个不带浏览器的。」
     * 所以不等到第一次点浏览器，**启动就搬**。
     *
     * 只在以下条件全部成立时才动手，任何一条不满足都直接返回：
     * 1. 程序目录下确实有 `jcef-bundle/` 且里面有 `libcef.dll`；
     * 2. 程序目录路径**含非 ASCII**（纯英文路径直接用，零拷贝）；
     * 3. `jcef-bundle` **不是符号链接**——开发期它就是指向 `%LOCALAPPDATA%` 的链接，
     *    搬它会把链接本身搬走，直接毁掉开发环境（这条是硬保护，不要为了"统一逻辑"去掉）；
     * 4. 目标目录没有已有的内核（有就沿用，别覆盖用户已经跑起来的那份）。
     *
     * 跨盘 `ATOMIC_MOVE` 会抛异常，此时退化为「复制 + 删源」，不会留半份。
     *
     * @return true 表示这次真的搬了（调用方可以据此提示"首次启动稍慢"）。
     */
    fun migrateBundledRuntimeOnStartup(): Boolean {
        if (!migrationStarted.compareAndSet(false, true)) {
            // 已经有人在搬：等它出结论再返回，绝不让两个线程同时动同一批文件
            runCatching { migrationDone.await(60, java.util.concurrent.TimeUnit.SECONDS) }
            return false
        }
        try {
            return doMigrateBundledRuntime()
        } finally {
            migrationDone.countDown()
        }
    }

    private fun doMigrateBundledRuntime(): Boolean {
        val bundled = java.io.File(appHomeDir(), "jcef-bundle")
        if (!hasNativeLib(bundled)) return false
        if (!containsNonAscii(bundled.absolutePath)) return false
        if (runCatching { java.nio.file.Files.isSymbolicLink(bundled.toPath()) }.getOrDefault(false)) {
            AppLog.i("Cef", "内核是符号链接，跳过搬迁：${bundled.absolutePath}")
            return false
        }

        val target = cefRootDir()
        if (target.absolutePath.equals(bundled.absolutePath, ignoreCase = true)) return false
        if (hasNativeLib(target)) return false
        // 目标存在但不是空目录 → 不碰它
        if (target.exists() && target.listFiles()?.isNotEmpty() == true) return false

        return runCatching {
            AppLog.i("Cef", "内核搬迁开始：${bundled.absolutePath} → ${target.absolutePath}")
            target.parentFile?.mkdirs()
            if (target.exists()) target.deleteRecursively()
            try {
                java.nio.file.Files.move(
                    bundled.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (t: Throwable) {
                AppLog.w("Cef", "原子移动失败，改为复制后删源", t)
                bundled.copyRecursively(target, overwrite = true)
                bundled.deleteRecursively()
            }
            AppLog.i("Cef", "内核搬迁完成：${target.absolutePath}")
            true
        }.onFailure { AppLog.w("Cef", "内核搬迁失败，继续用原位置", it) }.getOrDefault(false)
    }

    /** 目录里有没有 CEF 的原生库（判断"这份内核是不是真的能用"）。 */
    private fun hasNativeLib(dir: java.io.File): Boolean =
        dir.isDirectory && dir.walkTopDown().any {
            it.name.equals("libcef.dll", true) || it.name == "libcef.so"
        }

    /** 递归统计目录占用；目录不存在返回 0。 */
    private fun directoryBytes(dir: java.io.File): Long =
        if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun runtimeBytes(): Long = directoryBytes(cefRootDir())

    fun cacheBytes(): Long = directoryBytes(cacheDir())

    /**
     * 移除浏览器内核与运行时缓存（设置页「移除内核」调用）。
     *
     * 只删 [cefRootDir] 与 [cacheDir] 两处，**不碰**数据库、封面、下载。
     * CEF 正在运行时删不干净（dll 被占用），调用方应先确认应用重启后再删，或接受部分失败。
     *
     * @return 实际释放的字节数（按删除前的统计值）
     */
    fun removeRuntime(): Long {
        val freed = runtimeBytes() + cacheBytes()
        runCatching { cefRootDir().deleteRecursively() }
            .onFailure { AppLog.w("Cef", "删除内核目录失败", it) }
        runCatching { cacheDir().deleteRecursively() }
            .onFailure { AppLog.w("Cef", "删除内核缓存失败", it) }
        AppLog.i("Cef", "已移除内核，释放约 ${freed / 1024 / 1024} MB")
        return freed
    }

    fun warmUpIfDownloaded() {
        if (isStarted) return
        val dir = bundleDir()
        if (!dir.isDirectory) return
        val hasNativeLib = dir.walkTopDown().any {
            it.name.equals("libcef.dll", true) || it.name == "libcef.so" || it.name == "Chromium Embedded Framework"
        }
        if (!hasNativeLib) return
        println("[cef] 预热：${dir.absolutePath} 内核已存在，后台启动")
        // 预热失败不能吞掉（2026-11-11）：原来 `runCatching` 把异常丢掉，日志里连一行
        // 都没有，用户点开浏览器只看到「创建失败」，事后完全查不到根因。
        runCatching { ensureStarted() }
            .onFailure { AppLog.w("Cef", "预热失败（点开浏览器时会重试一次）", it) }
    }

    /** 停泵并关闭 CEF（进程退出/测试收尾用）。 */
    fun dispose() {
        if (!started.getAndSet(false)) return
        runCatching { pump?.stop() }
        pump = null
        runCatching { CefApp.getInstance().dispose() }
    }
}
