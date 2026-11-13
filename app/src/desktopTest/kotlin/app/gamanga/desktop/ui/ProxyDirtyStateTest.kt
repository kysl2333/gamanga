package app.gamanga.desktop.ui

import androidx.compose.runtime.mutableStateMapOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 代理设置页的「有未保存更改」判据（2026-10-29 用户反馈：保存了还一直显示）。
 *
 * 根因是 `SnapshotStateMap` 没有重写 `equals`，直接和普通 Map 比会退化成引用比较。
 * 这两条测试把这个坑钉住：一条记录 Compose 的行为，一条验证真正的判据函数。
 */
class ProxyDirtyStateTest {

    private val saved = ProxySnapshot(
        on = true, host = "192.168.3.2", port = "1081", protocol = "SOCKS5",
        perSource = mapOf("KOMIIC" to true, "COPYMANGA" to false),
    )

    @Test fun snapshotStateMapDoesNotCompareByContent() {
        val state = mutableStateMapOf("KOMIIC" to true)
        val plain = hashMapOf("KOMIIC" to true)
        // 旧代码的写法是 `sourceUseProxy != savedSourceUseProxy`，接收者正是 SnapshotStateMap；
        // 它没有重写 equals，所以这里按引用比较得到 false —— 内容相同也判为「脏」。
        assertFalse(state == plain, "SnapshotStateMap 应退化为引用比较")
        // 转成普通 Map 再比才是内容比较。
        assertEquals(plain, state.toMap())
    }

    @Test fun sameContentIsNotDirty() {
        val current = ProxySnapshot(
            on = true, host = "192.168.3.2", port = "1081", protocol = "SOCKS5",
            perSource = mutableStateMapOf("KOMIIC" to true, "COPYMANGA" to false),
        )
        assertFalse(isProxyDirty(current, saved))
    }

    @Test fun anySingleFieldChangeIsDirty() {
        assertTrue(isProxyDirty(saved.copy(on = false), saved))
        assertTrue(isProxyDirty(saved.copy(host = "127.0.0.1"), saved))
        assertTrue(isProxyDirty(saved.copy(port = "1080"), saved))
        assertTrue(isProxyDirty(saved.copy(protocol = "HTTP"), saved))
        assertTrue(isProxyDirty(saved.copy(perSource = mapOf("KOMIIC" to false, "COPYMANGA" to false)), saved))
        // 新增一个源（老快照里没有这个 key）默认跟随全局，也算改动。
        assertTrue(isProxyDirty(saved.copy(perSource = saved.perSource + ("MANGABZ" to false)), saved))
    }
}
