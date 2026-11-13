package app.gamanga.desktop.source

import app.gamanga.desktop.core.AppLog
import app.gamanga.desktop.core.webview.CefRuntime
import app.gamanga.desktop.core.webview.JcefWebViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

/**
 * 借真实浏览器跑一段 JS 并取回结果（M4）。
 *
 * 用途：有些源站的章节图片列表由前端**加密下发 + 前端解密**（GoDa/包子这类把
 * `images` 字段塞成一串混淆过的密文，解码器还是 javascript-obfuscator 处理过的），
 * 与其硬逆向加密，不如让站点自己在隐藏的 JCEF 里解好，我们只读 DOM。
 *
 * 结果通道：`executeJavaScript` 不返回值，但页面改标题会经 DisplayHandler 回到
 * `uiState.title`，所以约定「结果写进 document.title，前缀 GAJS:」。
 */
object BrowserProbe {

    private const val OK = "GAJS:"

    /** 页面加载完先静置一段时间，等懒加载把图片插进 DOM。 */
    private const val SETTLE_MS = 2_500L

    /** 轮询间隔。太密会给渲染进程添无谓的 eval 负担。 */
    /**
     * 反复求值的间隔（2026-10-24 从 600 降到 400）：搜索结果在 400~800ms 内就渲染完，
     * 600ms 的粒度会白等半拍。降太快会给渲染进程添无谓的 eval 负担，400 是折中。
     */
    private const val POLL_MS = 400L

    /** 隐藏浏览器复用窗口（秒级）。太短没收益，太长白占 CEF 进程。 */
    private const val SESSION_TTL_MS = 45_000L

    /** 标题传数据的分块大小。document.title 约 4KB 会截断，2000 留足余量。 */
    private const val CHUNK = 2000
    private const val ERR = "GAJS_ERR:"

    /**
     * 探针通道数：**0 = 交互（搜索/详情）**，**1 = 后台（取图/下载）**。
     *
     * 2026-10-24 加：原来只有一把锁，下载 4 章（每章 ~8 秒）时用户去搜索要排队 30 秒，
     * 体感就是「程序卡了」。分两条通道后，交互请求不再被后台任务堵住。
     * 每条通道各自持有一个隐藏浏览器（CEF 的 browser 实例很轻，共享同一进程）。
     */
    private val LANES = 2

    /** 每通道一把锁：同一通道内串行（R-45 的并发风暴防护），通道之间并行。 */
    private val laneLocks = Array(LANES) { Mutex() }

    /**
     * **隐藏浏览器复用**（2026-10-24，用户要求「减少搜索速度」）。
     *
     * 每次新建 `JcefWebViewHost` 要 3~4 秒（CEF browser 创建 + 首屏），而一次搜索
     * 全程只有 7 秒左右——一半时间花在建浏览器上。这里把上一个 host 留着，
     * [SESSION_TTL_MS] 内直接复用它导航到新地址，省掉重建开销。
     *
     * 过期后由下一次调用负责关闭（不另起定时器）。
     */
    private val sessionHosts = arrayOfNulls<JcefWebViewHost>(LANES)
    private val sessionAt = LongArray(LANES)

    private fun takeSession(lane: Int): JcefWebViewHost? {
        val h = sessionHosts[lane] ?: return null
        if (System.currentTimeMillis() - sessionAt[lane] > SESSION_TTL_MS) {
            runCatching { h.close() }
            sessionHosts[lane] = null
            return null
        }
        // 复用前恢复帧泵（回收时暂停过）
        runCatching { h.resumeFramePump() }
        return h
    }

    /** 回收 host：留着复用（带时间戳），不立即关闭。 */
    private fun keepSession(lane: Int, host: JcefWebViewHost) {
        // 同通道只留一个：换新的时候把旧的关掉
        sessionHosts[lane]?.takeIf { it !== host }?.let { runCatching { it.close() } }
        sessionHosts[lane] = host
        sessionAt[lane] = System.currentTimeMillis()
        // 空闲期停掉帧泵：否则每 33ms 还在渲染一帧，白占 CPU
        runCatching { host.pauseFramePump() }
    }

    /**
     * 打开 [url]，等页面就绪后反复执行 [expression]（JS 表达式，返回可 JSON 序列化的值），
     * 直到拿到非 null 结果或超时。返回 JSON 字符串，失败返回 null。
     *
     * **串行化（R-45）**：每次求值都会建一个隐藏浏览器（GLCanvas + CEF browser + 整页加载）。
     * 用户快速切话时会并发起多个求值，隐藏浏览器互相抢 EDT / 显存 / CPU，
     * 表现为「切话过快就取图失败，过一会儿重试就好」。用一把互斥锁保证一次只有一个探针，
     * 且等待全部是可取消的（`delay` 而非 `Thread.sleep`）——被取消的探针立刻收工，
     * 不会占着资源跑满超时。后启动的求值拿到锁后先检查取消，已被替代的直接退出。
     */
    suspend fun evalJson(
        url: String,
        expression: String,
        timeoutMs: Long = 25_000,
        /**
         * true = 表达式只求值一次，之后只轮询结果长度。
         *
         * 给 **Promise 表达式**（`fetch(...).then(r=>r.json())`）用：每轮重新求值会
         * 重新发一次请求、并把 `__gaP` 重置回 null，永远等不到结果。
         * 同步表达式保持「反复求值直到长度稳定」——那是等懒加载把 DOM 插完的机制。
         */
        evaluateOnce: Boolean = false,
        /**
         * 期望落地的域名（如 `mangacopy.com`）。
         *
         * **必须校验**（2026-10-24 用户实测「拷贝漫画搜索失败，浏览器显示网页加载失败
         * 然后过几秒恢复」）：CEF 首次加载失败时 `isLoading` 会先变 false、url 变成
         * `chrome-error://...` 或空——旧的 `awaitLoaded` 只看 isLoading，于是在**错误页**
         * 上下文里执行 fetch，必然失败。给了 [expectHost] 就一直等到真正落在目标站点上。
         */
        expectHost: String? = null,
        /**
         * 页面就绪后的静置时长（默认 [SETTLE_MS]）。
         *
         * 给「只需 JS 渲染一屏」的页面（搜索页/详情页）调小能省 1~1.5 秒；
         * 依赖懒加载逐批插入的页面（章节图片）保持默认。
         */
        settleMs: Long = SETTLE_MS,
        /**
         * 结果长度要连续多少轮不变才算「稳定」。
         *
         * 默认 1（两次求值一样就收工）——对「一屏渲染完就不变」的页面够用。
         * 但**懒加载/逐张解密的页面**（拷贝漫画章节页：图片由前端脚本逐张解密后插入）
         * 需要更大值，否则抓到前 3 张就提前返回（2026-10-24 用户实测「阅读器只能加载
         * 3 张漫画页」）。
         */
        stableRoundsRequired: Int = 1,
        /** 通道：0 = 交互（默认），1 = 后台（取图/下载）。见 [laneLocks]。 */
        lane: Int = 0,
        /** 同一章节的解密→DOM回落可复用当前页面，避免再次发整页请求。 */
        reusePage: Boolean = false,
    ): String? =
        withContext(Dispatchers.IO) {
            laneLocks[lane.coerceIn(0, LANES - 1)].withLock {
                // 排队期间调用方可能已被取消（用户又切话了）——别为废弃请求建浏览器
                currentCoroutineContext().ensureActive()
                CefRuntime.ensureStarted()
                val reused = takeSession(lane)
                val host = reused ?: createOnEdt(url) ?: return@withLock null
                val deadline = System.currentTimeMillis() + timeoutMs
                var reusable = true
                try {
                    if (reused != null && !(reusePage && host.uiState.value.url == url)) {
                        host.open(url)
                    }
                    if (!host.awaitLoaded(deadline, expectHost, if (reused != null) url else null)) {
                        AppLog.w("Probe", "page not loaded in time: $url (last=${host.uiState.value.url})")
                        // finally 统一关闭，避免关闭后又被放回复用池。
                        reusable = false
                        return@withLock null
                    }
                    // 清掉上一轮的结果，避免复用 host 时读到旧 JSON
                    host.executeJavaScript("window.__gaP = null; document.title='';")

                    // **必须先装一条自维持的 rAF 循环**（R-27 的第一个关键）。
                    // CEF 的离屏浏览器只在"有人持续请求帧"时才产帧；单个 requestAnimationFrame
                    // 回调在没帧的环境里永远不被派发。而站点把"插图"挂在 rAF 上，于是死锁：
                    // 没帧 → 不加载 → 更没帧。这条循环一开，帧管线点亮，图片才开始进 DOM。
                    host.executeJavaScript(
                        """
                        (function(){
                          if (window.__gaKeepAlive) return;
                          window.__gaKeepAlive = { n: 0 };
                          (function loop(){ window.__gaKeepAlive.n++; requestAnimationFrame(loop); })();
                        })()
                        """.trimIndent()
                    )
                    // 装上循环后静置，让懒加载把图片逐批插进 DOM。
                    delay(settleMs)

                    // 求值 → 把结果**存进页面**，而不是直接塞进 document.title：
                    // document.title 有长度上限（实测 >4KB 就被截断），章节图片列表动辄几十上百条，
                    // 走标题必然断在半路 → JSON 解析失败 → 表现为"取不到图"。
                    //
                    // **支持 Promise**（2026-10-24 加，给拷贝漫画的 fetch 兜底用）：
                    // expression 返回 Promise 时先占位 null，等 resolve 后再写入 __gaP；
                    // 上面的轮询看到长度从 -1 变成非 0 会继续等稳定。同步表达式行为不变。
                    val store = """
                        window.__gaP = (function(){
                          try {
                            var v = ($expression);
                            if (v && typeof v.then === 'function') {
                              v.then(function(x){ window.__gaP = JSON.stringify(x); })
                               .catch(function(e){ window.__gaP = 'GAJS_ERR:' + (e && e.message || e); });
                              return null;
                            }
                            return JSON.stringify(v === undefined ? null : v);
                          } catch (e) { return 'GAJS_ERR:' + (e && e.message || e); }
                        })();
                    """.trimIndent()

                    var length = -1
                    var stableRounds = 0
                    var evaluated = false
                    while (System.currentTimeMillis() < deadline) {
                        currentCoroutineContext().ensureActive()
                        if (!evaluateOnce || !evaluated) {
                            host.executeJavaScript(store)
                            evaluated = true
                        }
                        host.executeJavaScript(
                            "document.title='$OK'+'L'+((window.__gaP===null||window.__gaP==='null'||window.__gaP===undefined)?-1:window.__gaP.length)+'E'"
                        )
                        val t = host.awaitProbeTitle(deadline) ?: return@withLock null
                        if (t.startsWith(ERR)) return@withLock null
                        // 用 L...E 包住长度：页面自己也在改标题，裸数字容易被前后缀粘住
                        val payload = t.removePrefix(OK)
                        val newLen = if (payload.startsWith("L") && payload.endsWith("E")) {
                            payload.substring(1, payload.length - 1).toIntOrNull() ?: -1
                        } else -1

                        if (newLen <= 0) {           // 还没数据（懒加载没开始/没跑完）
                            length = -1
                            stableRounds = 0
                        } else if (newLen == length) { // 长度没变 → 计数，够轮数才收工
                            if (++stableRounds >= stableRoundsRequired) break
                        } else {
                            length = newLen
                            stableRounds = 0
                        }
                        delay(POLL_MS)
                    }
                    if (length <= 0) return@withLock null

                    // 分块取回：单块 2000 字符，远低于标题上限。
                    // **必须带序号并以序号对齐**：executeJavaScript 是异步的，直接读标题会读回
                    // 上一轮的值（表现是同一块被反复追加，拼出来是重复垃圾）。
                    val sb = StringBuilder(length)
                    var offset = 0
                    var seq = 0
                    while (offset < length) {
                        currentCoroutineContext().ensureActive()
                        val tag = "C${seq}E"
                        host.executeJavaScript(
                            "document.title='$OK$tag'+window.__gaP.substr($offset,$CHUNK)"
                        )
                        val t = host.awaitProbeTitleWith(OK + tag, deadline) ?: break
                        sb.append(t.removePrefix(OK + tag))
                        offset += CHUNK
                        seq++
                    }
                    val json = sb.toString()
                    if (json.startsWith("GAJS_ERR:")) return@withLock null
                    json
                } finally {
                    if (!reusable || !currentCoroutineContext().isActive) {
                        // 已取消请求不再留着网页继续加载；下一次重新创建。
                        runCatching { host.close() }
                        if (sessionHosts[lane] === host) sessionHosts[lane] = null
                    } else {
                        keepSession(lane, host)
                    }
                }
            }
        }

    /** 构造必须在 EDT：JCEF 建 GLCanvas 会碰 AWT/JOGL。 */
    private fun createOnEdt(url: String): JcefWebViewHost? {
        var host: JcefWebViewHost? = null
        val task = Runnable { host = runCatching { JcefWebViewHost(url, forceFrames = true) }.getOrNull() }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        return host
    }

    private suspend fun JcefWebViewHost.awaitLoaded(
        deadline: Long,
        expectHost: String? = null,
        /** 复用浏览器时要求 url 已经切到目标地址（否则会读到上一个页面的「已加载」状态）。 */
        expectUrlPrefix: String? = null,
    ): Boolean {
        while (System.currentTimeMillis() < deadline) {
            val s = uiState.value
            val u = s.url
            val landed = u.isNotBlank() && u != "about:blank" &&
                !u.startsWith("chrome-error") && !u.startsWith("data:")
            val hostOk = expectHost == null || u.contains(expectHost, ignoreCase = true)
            val urlOk = expectUrlPrefix == null || u.startsWith(expectUrlPrefix)
            if (!s.isLoading && landed && hostOk && urlOk) return true
            delay(50) // 2026-10-24：100 → 50，页面就绪能早半拍发现
        }
        return false
    }

    /** 等到标题以 [prefix] 开头（用于分块取回时对齐序号，避免读到上一轮的旧值）。 */
    private suspend fun JcefWebViewHost.awaitProbeTitleWith(prefix: String, deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            val t = uiState.value.title
            if (t.startsWith(prefix)) return t
            delay(30)
        }
        return null
    }

    /** 等带 GAJS 前缀的标题（页面自己也会改标题，要过滤掉）。 */
    private suspend fun JcefWebViewHost.awaitProbeTitle(deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            val t = uiState.value.title
            if (t.startsWith(OK) || t.startsWith(ERR)) return t
            delay(50)
        }
        return null
    }
}
