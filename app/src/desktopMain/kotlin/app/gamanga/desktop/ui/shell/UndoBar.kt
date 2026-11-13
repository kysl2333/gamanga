package app.gamanga.desktop.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 可回撤操作提示条（F-140，2026-10-27 用户要求）。
 *
 * ## 用户口径
 *
 * 「浏览页『浏览历史』旁的『清空全部』需提供回撤操作；书架与浏览页的删除动作同样支持回撤；
 * 回撤提示需符合整体 UI 审美并带动效」。
 *
 * ## 为什么抽成共用件
 *
 * 书架页原本自己写了一个私有 `UndoToast`（深色胶囊、180ms 线性淡入）。
 * 现在浏览页清空 / 删除、书架移出、封面自定义 / 取消四处都要同一条提示，
 * 复制三份必然像当初两套搜索框一样慢慢飘（UI-SPEC 3.6 的教训）。
 * **这里是唯一实现**，四处都调它。
 *
 * ## 观感
 *
 * 与整页一致：**浅色卡片**（白底 + 1px 细边 + 大阴影 + 圆角 16），不是深色胶囊——
 * 深色在浅色苹果风界面里像另一套系统。回撤键用强调蓝，底部一条细进度线走完 6 秒
 * （进度线是唯一「会动」的元素，告诉用户还剩多久，比数字倒计时克制）。
 *
 * 动效：入场 `spring` 上浮 + 0.94→1 放大 + 淡入（阻尼 0.72，**不弹跳**，UI-SPEC 第 5 节禁止回弹）；
 * 退场 200ms 下沉淡出，比入场快，不拖手感。
 *
 * ## 尺寸与位置（2026-10-27 用户第二轮反馈）
 *
 * 宽度**固定** [UndoBarWidth]（比 260dp 的 dock 窄），位置也在这里定死
 * （[UndoBarBottomOffset] = dock 让位高度 + 12dp）——调用点只给 `align`，不给 bottom padding。
 */

/**
 * 回撤条宽度：**固定 240dp**，比 dock 胶囊（`NormalShell` 里 `DockWidth` = 260dp）窄 20dp。
 *
 * 为什么固定：以前宽度由文案撑开，「已移出 3 项」和「已设置自定义封面」会一宽一窄地跳，
 * 跟底下 260dp 固定宽的 dock 摆在一起就不像同一套东西。定宽后它读起来就是
 * 「dock 上方的一条小提示」，而且文案变化时条子不会横向抖。
 * 240 是 5 个 dock 按钮宽度的 92%，两侧各内收 10dp，肉眼能看出比 dock 窄，又装得下
 * 现有最长文案（8 个汉字）+「撤销」键。
 */
private val UndoBarWidth = 240.dp

/**
 * 回撤条离窗口底部的距离。
 *
 * 2026-10-29（R-160）起改为读 [DockMetrics.floatingBottom]（dock 的**实测**顶边 + 20dp），
 * 不再手算 `88 + 12`：常量与 dock 真实尺寸有一点偏差，条子就会压在 dock 上或飘到半空。
 * 调用点仍然只负责 `Modifier.align(Alignment.BottomCenter)`，**不要再给 bottom padding**。
 */
private val UndoBarBottomOffset: Dp get() = DockMetrics.floatingBottom

/** 一条可回撤的操作。 */
@Immutable
data class UndoRequest(
    /** 提示文案，如「已移出 3 部」「已清空浏览历史」。 */
    val text: String,
    /** 点「撤销」时执行。**必须幂等且不抛异常**（这条提示已经离开用户视野，报错无处可显）。 */
    val onUndo: () -> Unit,
)

/**
 * 回撤条状态机。
 *
 * 为什么用类而不是让每个页面自己 `mutableStateOf + LaunchedEffect(delay)`：
 * 四个调用点各写一遍计时器，就会出现「两处计时器互相取消」的竞态
 * （连续删两次时，第一条的 6 秒计时器会把第二条的提示提前清掉）。
 * 这里所有调用点共用同一份状态，新提示直接覆盖旧提示并重置计时。
 */
@Stable
class UndoState internal constructor(
    private val scope: CoroutineScope,
    /** 停留时长。书架页原型是 6000ms，保持全项目一致。 */
    val durationMs: Long = 6_000L,
) {
    /** 当前提示（null = 不显示）。 */
    var current by mutableStateOf<UndoRequest?>(null)
        private set

    /** 每次 `show` 自增：进度线靠它重启动画（同一个 request 再来一次也要重跑）。 */
    var seq by mutableIntStateOf(0)
        private set

    private var dismissJob: Job? = null

    /** 显示一条提示（覆盖旧的并重置倒计时）。 */
    fun show(request: UndoRequest) {
        dismissJob?.cancel()
        current = request
        seq += 1
        dismissJob = scope.launch {
            delay(durationMs)
            current = null
        }
    }

    /** 点「撤销」：先收起提示，再执行回撤（避免回撤里抛异常时提示卡住不消失）。 */
    fun undo() {
        val request = current
        dismiss()
        request?.onUndo?.invoke()
    }

    /** 收起（超时 / 用户点了撤销 / 页面切换时主动清）。 */
    fun dismiss() {
        dismissJob?.cancel()
        dismissJob = null
        current = null
    }
}

/** 在页面里创建一份回撤状态（随组合生命周期）。 */
@Composable
fun rememberUndoState(): UndoState {
    val scope = rememberCoroutineScope()
    return remember(scope) { UndoState(scope) }
}

/**
 * 回撤条本体。
 *
 * 调用方**只**给 `Modifier.align(Alignment.BottomCenter)`（或其它对齐），
 * 底部的让位距离由本组件内部统一加（见 [UndoBarBottomOffset]）——调用点不要再给 bottom padding。
 *
 * @param state [rememberUndoState] 拿到的那份
 */
@Composable
fun UndoBar(state: UndoState, modifier: Modifier = Modifier) {
    val request = state.current
    // 退出动画期间 current 已经是 null，直接读它会让提示条缩成空壳——
    // 留一份「最后一次显示的内容」（与详情二级页 `lastDetail` 同一个坑）
    var last by remember { mutableStateOf<UndoRequest?>(null) }
    LaunchedEffect(request) { if (request != null) last = request }

    AnimatedVisibility(
        visible = request != null,
        enter = fadeIn(tween(180, easing = Tokens.Motion.DrawerEasing)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                // 从下方 40% 自身高度浮上来：幅度小才像「浮起」，不是「弹窗」
                initialOffsetY = { it * 2 / 5 },
            ) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            ),
        exit = fadeOut(tween(160, easing = Tokens.Motion.DrawerEasing)) +
            slideOutVertically(tween(200, easing = Tokens.Motion.DrawerEasing)) { it / 3 } +
            scaleOut(tween(200, easing = Tokens.Motion.DrawerEasing), targetScale = 0.96f),
        // 底部让位在组件内部加：四个调用点共用同一份位置，避免各页自己算（有人 18dp、有人漏写）。
        // 2026-10-29 起横向也读 dock 实测中心线（[DockMetrics.centerOffsetX]），
        // 不再用写死的 32dp —— dock 一旦改了推荐按钮尺寸，条子会自动跟着对齐。
        modifier = modifier
            .padding(bottom = UndoBarBottomOffset)
            .offset(x = DockMetrics.centerOffsetX),
    ) {
        UndoBarContent(
            text = (request ?: last)?.text.orEmpty(),
            seq = state.seq,
            durationMs = state.durationMs,
            onUndo = { state.undo() },
        )
    }
}

@Composable
private fun UndoBarContent(
    text: String,
    seq: Int,
    durationMs: Long,
    onUndo: () -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.Card)
    Column(
        Modifier
            // 固定宽度（不是 widthIn）：宽度只在这里定义，文案长短不再影响条子宽度
            .width(UndoBarWidth)
            .shadow(
                elevation = Tokens.Elevation.Overlay,
                shape = shape,
                clip = false,
                ambientColor = Color(0x14000000),
                spotColor = Color(0x26000000),
            )
            .clip(shape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, Tokens.Color.BorderHairline, shape),
    ) {
        Row(
            // 条子收窄后左右内边距跟着收：18→16 给文字多一点宽度，右侧 8 让「撤销」的
            // hover 底色贴近边缘（它自己还有 10dp 内边距）；上下 10→8 让条子更薄一点
            Modifier.padding(
                start = Tokens.Space.Lg,
                end = Tokens.Space.Sm,
                top = Tokens.Space.Sm,
                bottom = Tokens.Space.Sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md),
        ) {
            MixText(
                text = text,
                // weight 把「撤销」推到右边缘：定宽后若不加权重，短文案会让键浮在中间
                modifier = Modifier.weight(1f),
                color = Tokens.Color.TextPrimary,
                fontSize = Tokens.Type.Body.size,
                style = Tokens.Type.Body.toTextStyle(),
                // 定宽 + 单行 + 省略号：宁可截断也不许换行把条子撑高（高度一变，浮起动画会抖）
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UndoAction(onUndo)
        }
        // 剩余时间：一条 2dp 的细线走完 6 秒。线性、只在 draw 阶段读动画值，不重组整条提示。
        UndoProgressLine(seq = seq, durationMs = durationMs)
    }
}

/** 「撤销」文字键：hover 变强调蓝 + 浅蓝底，按下缩 0.94（与 `TextAction` 同一套手感）。 */
@Composable
private fun UndoAction(onUndo: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onUndo)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            text = "撤销",
            color = Tokens.Color.Accent,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.copy(weight = FontWeight.SemiBold).toTextStyle(),
        )
    }
}

/** 底部进度细线（宽度 100% → 0%）。 */
@Composable
private fun UndoProgressLine(seq: Int, durationMs: Long) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(seq) {
        progress.snapTo(1f)
        progress.animateTo(0f, tween(durationMs.toInt(), easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        Box(
            Modifier
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .height(2.dp)
                .background(Tokens.Color.Accent.copy(alpha = 0.55f)),
        )
    }
}
