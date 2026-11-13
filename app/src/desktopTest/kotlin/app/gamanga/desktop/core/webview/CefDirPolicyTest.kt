package app.gamanga.desktop.core.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CEF 目录策略（A5，2026-11-13）。
 *
 * 只测纯函数部分 [CefRuntime.containsNonAscii] —— [CefRuntime.cefRootDir] 依赖
 * `user.dir` / `LOCALAPPDATA` / `ProgramData` 三个环境量，在测试进程里改不干净，
 * 它靠发行包实机验证（`docs/plan/github-release.md` D2）。
 *
 * 这条判断的代价很高：判错了（把中文路径当成 ASCII 放过去）CEF 会**原生崩溃**，
 * 只有 `hs_err_pid*.log`，没有任何 Java 堆栈 —— 2026-10-24 已经踩过一次。
 */
class CefDirPolicyTest {

    @Test
    fun `program path with Chinese chars is rejected`() {
        assertTrue(CefRuntime.containsNonAscii("D:/我的漫画库/Gamanga/app/jcef-bundle"))
        assertTrue(CefRuntime.containsNonAscii("C:/Users/张三/AppData/Local/Gamanga/cef-root"))
        assertTrue(CefRuntime.containsNonAscii("D:/我的软件/Gamanga"))
    }

    @Test
    fun `pure ASCII paths are accepted`() {
        assertFalse(CefRuntime.containsNonAscii("C:/Users/alice/AppData/Local/Gamanga/cef-root"))
        assertFalse(CefRuntime.containsNonAscii("C:/Program Files/Gamanga/jcef-bundle"))
        assertFalse(CefRuntime.containsNonAscii("D:/Apps/Gamanga"))
    }

    @Test
    fun `empty path has no non-ASCII char`() {
        assertFalse(CefRuntime.containsNonAscii(""))
    }
}
