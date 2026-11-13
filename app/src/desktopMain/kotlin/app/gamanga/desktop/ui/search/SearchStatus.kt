package app.gamanga.desktop.ui.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局搜索的**活动状态**（2026-10-27 新增）。
 *
 * ## 为什么需要它
 *
 * 用户要求：「菜单按钮的浏览页按钮也像下载按钮一样，在搜索的时候菜单的浏览页按钮增加搜索的动效，
 * 搜索完毕后做一个有趣的完毕图标动效然后变为浏览页按钮点击后的状态」。
 *
 * 但 dock 在**壳层**（`NormalShell`），搜索发生在**页面**（`BrowseScreen`）——
 * 两者之间隔着 `GamangaApp` 好几层。一路传参会污染所有中间签名，
 * 所以用一个进程内单例让壳层直接读。
 *
 * ## 生命周期
 *
 * `BrowseScreen.search()` 开始时 [searching]，`SearchAll` 的 flow 收完后 [done]，
 * 壳层播完 1.6 秒的「完成」动效后调 [idle] 收尾。
 *
 * 单例是安全的：搜索只有一个入口、桌面端只有一个窗口。
 */
object SearchStatus {

    enum class Phase {
        /** 没在搜。 */
        Idle,

        /** 正在搜（dock 的浏览按钮要播搜索动效）。 */
        Searching,

        /** 刚搜完（dock 播一次「完成」动效，然后壳层调 [idle]）。 */
        Done,
    }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    fun searching() {
        _phase.value = Phase.Searching
    }

    fun done() {
        _phase.value = Phase.Done
    }

    fun idle() {
        _phase.value = Phase.Idle
    }
}
