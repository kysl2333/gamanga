package app.gamanga.desktop.ui.detail

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * 详情二级面板的统一动效（2026-11-08 批次65）。
 *
 * 推荐页详情面板与漫画源详情共用这一份参数：进入 513ms、退出 405ms，
 * 曲线统一为 [FastOutSlowInEasing]（起手柔和、中段推进、落点收住），
 * 淡入 220ms（延迟 90ms 让位移先起）、淡出 90ms。
 *
 * 之前两处各自写 tween：推荐页用默认曲线，源详情用 ease-out-quint；
 * 时长相同但观感不同，用户要求把推荐页的手感搬到源详情，故抽出来锁死。
 */
internal const val DetailEnterMs = 513
internal const val DetailExitMs = 405
internal val DetailEasing: Easing = FastOutSlowInEasing
internal const val DetailFadeInMs = 220
internal const val DetailFadeInDelayMs = 90
internal const val DetailFadeOutMs = 90
