package app.gamanga.desktop.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.ui.theme.Tokens
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * 水位波浪填充（2026-10-26，用户要求的三处动效共用一份实现）。
 *
 * 模型很简单：一个「水位」[level]（0 = 空、1 = 满）加一个「相位」[phase]（弧度，驱动波动）。
 * 三种用法只是参数不同：
 *
 * | 用在哪 | level | amplitude |
 * |---|---|---|
 * | 底部菜单按钮 · 常态 | 0 | 0 |
 * | 底部菜单按钮 · 悬停 | 0.42（图标正中间流动） | 0.05 |
 * | 底部菜单按钮 · 选中/点击 | 1（纯蓝、图标转白） | 0 |
 * | 顶栏搜索键 · 常态 | 0.12（底部留一点蓝，用户要求 10~15%） | 0 |
 * | 顶栏搜索键 · 悬停 | 0.5 | 0.055 |
 * | 顶栏搜索键 · 点击 | → 1（从柔和色板随机取一色，平滑过渡） | 0.055 |
 *
 * **两层波叠加**（主波 + 反向副波）是用户明确要的「叠加感」，不是装饰：
 * 单层正弦在水位低时看起来像一条抖动的横线，两层才有水面感。
 *
 * 颜色由调用方算好传进来（搜索键的色板过渡在 [WaveIconButton] 里），
 * 这里**不做渐变**——用户 2026-10-26 两次纠正过：不要彩虹，要「涨的过程换色、满了定格一个颜色」。
 */
@Composable
internal fun WaveFill(
    level: Float,
    phase: Float,
    amplitude: Float,
    color: Color = Tokens.Color.Accent,
    modifier: Modifier = Modifier,
    /**
     * 在**绘制阶段**取色（优先于 [color]）。
     *
     * 颜色循环每 1.4 秒换一次色。如果在 compose 层读 `State.value`，**整个按钮会重组**
     * （Canvas 重建 + 布局校验），那一帧就是用户说的「过 2 秒卡顿一下、抽帧感」。
     * 传成 provider 后在 draw lambda 里读 —— Compose 只重绘，不重组。
     */
    colorProvider: (() -> Color)? = null,
    /**
     * 在**绘制阶段**取相位（优先于 [phase]）。理由同 [colorProvider]：
     * 相位每帧都在变，在 compose 层读会每帧重组。
     */
    phaseProvider: (() -> Float)? = null,
) {
    Canvas(modifier) {
        if (level <= 0.001f) return@Canvas
        val paintColor = colorProvider?.invoke() ?: color
        val paintPhase = phaseProvider?.invoke() ?: phase
        val w = size.width
        val h = size.height
        val baseY = h * (1f - level)
        val amp = h * amplitude

        val path = Path()
        path.moveTo(0f, baseY)
        var x = 0f
        while (x <= w) {
            val t = x / w
            // 主波（向前） + 副波（反向、频率 1.5 倍、幅度 45%）
            val y = baseY +
                sin(t * 2f * PI.toFloat() + paintPhase) * amp +
                sin(t * 3f * PI.toFloat() - phase * 0.72f) * amp * 0.45f
            path.lineTo(x, y)
            x += 1.5f
        }
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()

        drawPath(path, SolidColor(paintColor))
    }
}

/**
 * 波浪相位。`active` 为真时按 [periodMs] 匀速循环；转假时**平滑归位**（用户要求「移开有返回去的特效」），
 * 不是瞬间归零——瞬间归零会让水面「啪」地停住。
 */
@Composable
internal fun rememberWavePhase(active: Boolean, periodMs: Int = 1600): Float {
    // **流动相位必须用 infiniteTransition**（用户 2026-10-26 实测：「波浪播放结束后与开始的
    // 画面不衔接，能明显看到动效过一会儿就跳了一下」）。
    //
    // 旧写法是 `Animatable.animateTo(2π, infiniteRepeatable(...))`：每次 repeat 都会
    // **重置动画时钟**，相位不是连续累加而是每轮从 0 重新开始，循环边界那一帧会跳。
    // infiniteTransition 的时间轴是连续的，2π 与 0 的波形值相同 → 天然无缝。
    val transition = rememberInfiniteTransition(label = "wavePhase")
    val flowing by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveFlowing",
    )
    // 停止时整体乘系数归零，保留「移开有返回去」的手感（水面平滑落下，而不是啪地停住）
    val settle = remember { Animatable(1f) }
    LaunchedEffect(active) {
        settle.animateTo(
            targetValue = if (active) 1f else 0f,
            animationSpec = tween(525, easing = Tokens.Motion.DrawerEasing),
        )
    }
    return flowing * settle.value
}

/**
 * 搜索键注满时随机挑一个的**柔和色板**（F-71，用户 2026-10-26 第二次口径）。
 *
 * 上一版让色相在整个色环上旋转（210° → 210+90+random(300)°），用户实测
 * 「色系太多、闪得太快」。问题不在动画时长，在于**色相环本身跨度过大**：
 * 一段 420ms 的过渡里从蓝扫到橙，中间必然经过一堆高饱和的中间色。
 *
 * 现在改成**固定的 5 个柔和色**（低饱和、高亮度，同一明度带）：
 * 按下时随机挑一个，从品牌蓝平滑过渡过去；松开过渡回品牌蓝。
 * 保留「随机」的手感，但任意两次之间最多只跨一个色相区，不再闪。
 */
internal val SoftFillPalette = listOf(
    Color(0xFF7FA8E8), // 雾蓝
    Color(0xFF7FC4BC), // 青瓷
    Color(0xFF9B93D8), // 藕荷
    Color(0xFFE09AAE), // 藕粉
    Color(0xFF9CBE84), // 苔绿
)

/**
 * 在柔和色板之间**循环流动**的颜色（2026-10-26 用户要求）。
 *
 * 用户原话：「颜色只有一个且这个颜色并不会变换成颜色流动的感觉」「需要像搜索键被按住一样在那变色」。
 * 上一版是「随机挑一色然后固定」，观感是死的一个色块。这里改成：
 * 每隔一段把目标色切到色板里的下一个，用 [animateColorAsState] 平滑过渡过去——
 * 视觉上就是颜色在水面上缓慢流动，而不是硬切。
 *
 * @param active 只有 true 时才流动（不在状态里就别烧动画）
 * @param stepMs 每次换色间隔；默认 1400ms，一整轮 5 色约 7 秒
 */
@Composable
internal fun rememberCyclingColor(
    active: Boolean,
    palette: List<Color> = SoftFillPalette,
    stepMs: Long = 1400L,
): Color {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(stepMs)
            index = (index + 1) % palette.size
        }
    }
    val animated by animateColorAsState(
        targetValue = palette[index % palette.size],
        animationSpec = tween(durationMillis = stepMs.toInt(), easing = LinearEasing),
        label = "cyclingColor",
    )
    return animated
}

/**
 * 波浪相位（按**帧时间累加**，永不重置）。
 *
 * ## 为什么不用 infiniteTransition / Animatable
 *
 * 用户实测过两版都有「过几秒突然跳一帧」：
 * - `Animatable.animateTo(2π, infiniteRepeatable(...))`：每次 repeat **重置动画时钟**；
 * - `rememberInfiniteTransition.animateFloat(0f, 2π, Restart)`：Restart 在边界那一帧
 *   仍然是从终值瞬间回初值，某些帧率下会丢帧/跳变。
 *
 * 现在改成**自己按帧间隔累加**：`phase += dt / period * 2π`，超过 2π 就减去 2π。
 * 因为 sin 的周期是 2π，减去 2π 后波形完全等价 —— **数学上不可能跳**。
 *
 * 返回值是 State，调用方应传给 [WaveFill] 的 `phaseProvider`（draw 阶段读，只重绘）。
 *
 * 停止时**冻结相位**（不再回卷）：回卷会让波形横向滑一下，而水位同时在降，
 * 视觉上反而更乱。水位到 0 后波浪本来就看不见了。
 */
@Composable
internal fun rememberWavePhaseState(active: Boolean, periodMs: Int = 1600): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dtMs = (now - last) / 1_000_000f
                last = now
                val twoPi = (2f * PI).toFloat()
                var next = phase.floatValue + (dtMs / periodMs) * twoPi
                if (next >= twoPi) next -= twoPi
                phase.floatValue = next
            }
        }
    }
    return phase
}

/**
 * 同 [rememberCyclingColor]，但返回 **State**。
 *
 * 调用方把 `{ state.value }` 传给 [WaveFill] 的 `colorProvider`，颜色变化就只触发**重绘**。
 * 直接在 compose 层读 value 会每次换色都重组整个按钮 —— 那就是「每 1~2 秒卡一下」的来源。
 */
@Composable
internal fun rememberCyclingColorState(
    active: Boolean,
    palette: List<Color> = SoftFillPalette,
    stepMs: Long = 1400L,
): State<Color> {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(stepMs)
            index = (index + 1) % palette.size
        }
    }
    return animateColorAsState(
        targetValue = palette[index % palette.size],
        animationSpec = tween(durationMillis = stepMs.toInt(), easing = LinearEasing),
        label = "cyclingColorState",
    )
}

/**
 * 圆形波浪图标按钮 —— 顶栏搜索键用的就是它（用户 2026-10-26 口述的三态）。
 *
 * | 状态 | 水位 | 波浪 | 颜色 | 图标 |
 * |---|---|---|---|---|
 * | 常态 | **0.12**（底部留一点蓝，用户要求 10~15%） | 静止 | 纯蓝 | 强调色（白底上要清晰） |
 * | 悬停 | 0.50（涨到圆心） | 两层叠加流动 | 纯蓝 | 强调色 |
 * | 按下 | → 1.00（注满） | 流动 | **随机一个柔和色**（[SoftFillPalette]） | 转白 |
 * | 松开 | 落回 0.12 | 平滑归位 | 过渡回品牌蓝 | 转回强调色 |
 */
@Composable
internal fun WaveIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 19.dp,
    idleLevel: Float = 0.12f,
    hoverLevel: Float = 0.5f,
    amplitudeActive: Float = 0.055f,
    accent: Color = Tokens.Color.Accent,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val level by animateFloatAsState(
        targetValue = when {
            pressed -> 1f
            hovered -> hoverLevel
            else -> idleLevel
        },
        // 上涨比回落快一点：涨 420ms、落 520ms，手感上「按下去很有劲、松开慢慢退」
        animationSpec = tween(
            durationMillis = if (pressed) 420 else 520,
            easing = Tokens.Motion.DrawerEasing,
        ),
        label = "waveBtnLevel",
    )

    // 颜色：从柔和色板里随机挑一个，一次平滑过渡过去（F-71）。
    // **目标色存在 state 里、由 LaunchedEffect(pressed) 决定**，而不是在组合里现算——
    // 组合里现算 `Random` 会让每次重组都换色（视觉上就是闪）。
    var targetFill by remember { mutableStateOf(accent) }
    LaunchedEffect(pressed) {
        targetFill = if (pressed) {
            if (accent == Tokens.Color.Accent) SoftFillPalette[Random.nextInt(SoftFillPalette.size)] else accent
        } else {
            accent
        }
    }
    val fill by animateColorAsState(
        targetValue = targetFill,
        // 过渡放慢到 520/620ms（上一版 420/520 且扫过整个色相环，观感就是「闪」）
        // 2026-10-26 用户：点击注满/回落也各降 25%（520→650 / 620→775）
        animationSpec = tween(if (pressed) 650 else 775, easing = Tokens.Motion.DrawerEasing),
        label = "waveBtnFill",
    )

    val phaseState = rememberWavePhaseState(active = hovered || pressed)
    val amplitude = if (hovered || pressed) amplitudeActive else 0f
    // 被水淹没时图标转白；常态保持强调色（白底上要够清晰）
    val tint by animateColorAsState(
        targetValue = if (level > 0.55f) Color.White else accent,
        animationSpec = tween(200),
        label = "waveBtnTint",
    )

    Box(
        modifier
            .size(size)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(Tokens.Color.BgElevatedSolid)
            .border(1.dp, Tokens.Color.BorderHairline, CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WaveFill(
            level = level,
            phase = 0f,
            phaseProvider = { phaseState.value },
            amplitude = amplitude,
            color = fill,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
