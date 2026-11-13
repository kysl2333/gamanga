package app.gamanga.desktop.ui.recommend

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.source.BangumiMirror
import app.gamanga.desktop.source.BangumiMirrors
import app.gamanga.desktop.source.MirrorProbe
import app.gamanga.desktop.source.isUsable
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 「每次启动只提示一次」的危险提示闸门。
 *
 * 用户要求：点换源按钮后要警告，但**不要每点一次就警告一次**——同一个进程里第一次打开
 * 换源窗口时警告，之后直到下次启动都不再出现。用进程内静态标志，不写盘（写盘就变成
 * 「永远只警告一次」，用户换台机器/重装才再看到，等于没警告）。
 */
internal object MirrorWarningGate {
    @Volatile
    private var shown = false

    fun claim(): Boolean {
        if (shown) return false
        shown = true
        return true
    }
}

internal fun mirrorSymbol(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw,
        )
    }.build()

/** 换源按钮的新图标：上下两条反向箭头，读作「换一条线路」。 */
internal val MirrorIcon = mirrorSymbol("换源") {
    moveTo(3f, 9f); lineTo(20f, 9f)
    moveTo(16f, 5f); lineTo(20f, 9f); lineTo(16f, 13f)
    moveTo(21f, 15f); lineTo(4f, 15f)
    moveTo(8f, 11f); lineTo(4f, 15f); lineTo(8f, 19f)
}
private val CheckIcon = mirrorSymbol("选中") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f) }
private val ServerIcon = mirrorSymbol("线路") {
    moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 10f); lineTo(4f, 10f); close()
    moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
    moveTo(7.5f, 7.2f); lineTo(7.7f, 7.2f)
    moveTo(7.5f, 16.8f); lineTo(7.7f, 16.8f)
}
private val BoltIcon = mirrorSymbol("检测") {
    moveTo(13f, 3f); lineTo(5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 21f)
    lineTo(19f, 10f); lineTo(12.5f, 10f); close()
}

/**
 * 换源窗口（2026-10-28 第八批）。
 *
 * 用户原话：「换镜像源窗口改成现在程序 UI 审美的现代窗口」「点击换源按钮后会给于危险提示
 * 【每次打开程序生效一次】」「获取最新镜像源然后填入最新镜像源」。
 *
 * 三件事各占一块，从上到下：风险提示（只首开出现）→ 候选线路（带实时状态）→ 自定义线路。
 * 点一行只是「选中」，真正生效在底部「应用线路」，避免手滑把线路点掉。
 */
@Composable
internal fun MirrorDialog(state: RecommendState, onDismiss: () -> Unit) {
    val reduce = state.reduceMotion
    val showWarning = remember { MirrorWarningGate.claim() }
    var pending by remember { mutableStateOf(state.mirror) }
    var customOpen by remember { mutableStateOf(state.mirror.id == "custom") }
    var apiText by remember { mutableStateOf(if (state.mirror.id == "custom") state.mirror.api else "") }
    var imageText by remember { mutableStateOf(if (state.mirror.id == "custom") state.mirror.image else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<BangumiMirror?>(null) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
        // 打开就探一次：否则用户看到一排「未检测」，还得自己找按钮。
        if (state.probes.isEmpty()) state.probeMirrors()
    }

    // 检测完成后只在「当前选中的线路不可用」时填入最快的可用线路。
    // 2026-10-28 用户反馈：以前每次打开都无条件挑最快，导致明明在用官方、勾却跑到 ANIBT 上。
    LaunchedEffect(state.probes) {
        if (state.probes.isEmpty()) return@LaunchedEffect
        if (state.probes[pending.id]?.isUsable == true) return@LaunchedEffect
        val best = state.probes.entries
            .filter { it.value.isUsable }
            .minByOrNull { (it.value as MirrorProbe.Ok).latencyMs } ?: return@LaunchedEffect
        state.mirrors.firstOrNull { it.id == best.key }?.let { pending = it; customOpen = false; error = null }
    }

    val scale by animateFloatAsState(if (entered) 1f else .97f, spring(dampingRatio = .82f, stiffness = 420f))
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(if (reduce) 0 else 160))

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0x33000000))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val width = minOf(620.dp, maxWidth - Tokens.Space.Xxl * 2)
        val height = minOf(660.dp, maxHeight - Tokens.Space.Xxl * 2)
        Column(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .clickable(remember { MutableInteractionSource() }, null, onClick = {})
                .width(width).heightIn(max = height)
                .shadow(28.dp, RoundedCornerShape(Tokens.Radius.Drawer), clip = false, ambientColor = Color(0x0A000000), spotColor = Color(0x1A000000))
                .clip(RoundedCornerShape(Tokens.Radius.Drawer))
                .background(Tokens.Color.BgElevatedSolid)
                .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Drawer))
                .padding(Tokens.Space.Xl)
                .testTag("mirror-dialog"),
        ) {
            // ── 标题 ──────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(Tokens.Radius.Control)).background(RecommendTokens.Soft),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(MirrorIcon, null, Modifier.size(20.dp), tint = RecommendTokens.Pink) }
                Column(Modifier.weight(1f)) {
                    Copy("线路与镜像", strong = true)
                    Copy("当前：${state.mirror.name}", small = true, color = RecommendTokens.Muted)
                }
                RoundAction(CloseIcon, "关闭") { onDismiss() }
            }

            Spacer(Modifier.height(Tokens.Space.Lg))

            // ── 风险提示 ─────────────────────────────────────────
            // 两条触发条件：①本次启动第一次打开窗口（长说明只出一次）；②当前选中的是非官方线路
            // ——这时每次都要红字提示，因为「你正在用别人的服务器转发 API」必须一直看得见。
            val risky = pending.id != BangumiMirrors.OFFICIAL_ID
            val showRisk = showWarning || risky
            AnimatedVisibility(
                visible = showRisk,
                enter = fadeIn(tween(if (reduce) 0 else 220)) + expandVertically(tween(if (reduce) 0 else 260)),
                exit = fadeOut(tween(if (reduce) 0 else 140)) + shrinkVertically(tween(if (reduce) 0 else 200)),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
                    Copy(
                        if (risky) "非官方线路：你的 API 请求会被镜像站看到"
                        else "换用非官方线路前请先读这段",
                        strong = true, color = Tokens.Color.Danger,
                    )
                    Copy(
                        "切换后你的 Bangumi 访问令牌（API Token）与所有请求都会经过镜像服务器转发，" +
                            "镜像站管理者可以看到你的 API 调用、账号凭证与阅读数据。切勿在非官方网站直接输入账号密码。",
                        small = true, color = RecommendTokens.Muted,
                    )
                }
            }
            if (showRisk) Spacer(Modifier.height(Tokens.Space.Lg))

            // ── 候选线路 ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Copy("可用线路", strong = true)
                Spacer(Modifier.weight(1f))
                ProbeButton(state)
            }
            Spacer(Modifier.height(Tokens.Space.Sm))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                state.mirrors.forEachIndexed { index, mirror ->
                    MirrorRow(
                        mirror = mirror,
                        selected = !customOpen && pending.id == mirror.id,
                        probe = state.probes[mirror.id],
                        probing = state.probing,
                        index = index,
                    ) { pending = mirror; customOpen = false; error = null; confirmTarget = null }
                }
                CustomRow(customOpen, pending) { customOpen = !customOpen; error = null }
                AnimatedVisibility(
                    visible = customOpen,
                    enter = fadeIn(tween(if (reduce) 0 else 180)) + expandVertically(tween(if (reduce) 0 else 220)),
                    exit = fadeOut(tween(if (reduce) 0 else 120)) + shrinkVertically(tween(if (reduce) 0 else 180)),
                ) {
                    Column(Modifier.padding(start = Tokens.Space.Xxl, top = Tokens.Space.Sm), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                        OutlinedTextField(
                            apiText, { apiText = it; error = null },
                            label = { Copy("接口地址", small = true) }, singleLine = true,
                            shape = RoundedCornerShape(Tokens.Radius.Control), modifier = Modifier.fillMaxWidth().testTag("mirror-api-input"),
                        )
                        OutlinedTextField(
                            imageText, { imageText = it; error = null },
                            label = { Copy("图片地址", small = true) }, singleLine = true,
                            shape = RoundedCornerShape(Tokens.Radius.Control), modifier = Modifier.fillMaxWidth().testTag("mirror-image-input"),
                        )
                        Copy("两条都要 HTTPS。图片站需沿用 /pic/cover/… 的路径结构。", small = true, color = RecommendTokens.Muted)
                    }
                }
            }

            error?.let { Spacer(Modifier.height(Tokens.Space.Sm)); Copy(it, small = true, color = Tokens.Color.Danger) }

            Spacer(Modifier.height(Tokens.Space.Lg))
            // 二次确认：换线路 = 账号令牌要经过第三方服务器，不能点一下就生效。
            val target = confirmTarget
            if (target != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                    Copy("确认使用「${target.name}」？", strong = true)
                    if (target.id != BangumiMirrors.OFFICIAL_ID) {
                        Copy("非官方线路有风险：你的 API 请求与访问令牌会被镜像站看到", strong = true, color = Tokens.Color.Danger)
                    }
                    Copy("切换后 Bangumi 接口请求与封面加载都会经过该线路转发，确认继续？", small = true, color = RecommendTokens.Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Chip("取消") { confirmTarget = null }
                        Chip("确认使用", selected = true) { confirmTarget = null; state.selectMirror(target); onDismiss() }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm), verticalAlignment = Alignment.CenterVertically) {
                    Copy(if (state.probing) "正在检测线路…" else "共 ${state.mirrors.size} 条线路 · 目录 bangumi.wiki", small = true, color = RecommendTokens.Muted)
                    Spacer(Modifier.weight(1f))
                    Chip("取消") { onDismiss() }
                    Chip("应用线路", selected = true) {
                        val picked = if (customOpen) BangumiMirrors.custom(apiText, imageText) else pending
                        when {
                            picked == null -> error = "请填写两个有效的 HTTPS 地址"
                            picked.id == state.mirror.id -> onDismiss()
                            else -> { error = null; confirmTarget = picked }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeButton(state: RecommendState) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hover && !state.probing) RecommendTokens.Soft else Color(0xFFFFF7FA), tween(160))
    Row(
        Modifier.clip(RecommendTokens.Button).background(bg).hoverable(interaction)
            .then(if (state.probing) Modifier else Modifier.primaryPress { state.probeMirrors() })
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("mirror-probe"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.probing) CircularProgressIndicator(Modifier.size(14.dp), color = RecommendTokens.Pink, strokeWidth = 1.8.dp)
        else androidx.compose.material3.Icon(BoltIcon, null, Modifier.size(14.dp), tint = RecommendTokens.Pink)
        Copy(if (state.probing) "检测中" else "获取最新镜像源", small = true, color = RecommendTokens.Pink, strong = true)
    }
}

@Composable
private fun MirrorRow(
    mirror: BangumiMirror,
    selected: Boolean,
    probe: MirrorProbe?,
    probing: Boolean,
    index: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current
    // 列表错峰入场：一眼看出「这几条是一起探测出来的」。
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if (reduce) 0 else index * 55L); shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 8f, tween(if (reduce) 0 else 260))
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (reduce) 0 else 260))
    // 选中态只用浅灰底 + 一个勾，不再上粉色描边（用户要求：不要花的颜色框）。
    val bg by animateColorAsState(if (selected) Color(0xFFF6F6F8) else if (hover) Color(0xFFFAFAFC) else Color.White, tween(if (reduce) 0 else 160))
    val border by animateColorAsState(Tokens.Color.BorderHairline, tween(if (reduce) 0 else 160))
    Row(
        Modifier.fillMaxWidth().graphicsLayer { translationY = slide; alpha = fade }
            .clip(RoundedCornerShape(Tokens.Radius.Card)).background(bg)
            .border(1.dp, border, RoundedCornerShape(Tokens.Radius.Card))
            .hoverable(interaction).primaryPress(onClick)
            .padding(horizontal = Tokens.Space.Lg, vertical = Tokens.Space.Md)
            .testTag("mirror-row-${mirror.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(Tokens.Radius.Control)).background(if (selected) Color.White else Tokens.Color.BgSunken), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(ServerIcon, null, Modifier.size(16.dp), tint = if (selected) RecommendTokens.Ink else Tokens.Color.TextSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Copy(mirror.name, strong = true, small = false)
            Copy("${BangumiMirrors.hostOf(mirror.api) ?: mirror.api} · ${mirror.note}", small = true, color = RecommendTokens.Muted, maxLines = 1)
        }
        ProbeBadge(probe, probing)
        if (selected) androidx.compose.material3.Icon(CheckIcon, null, Modifier.size(16.dp), tint = RecommendTokens.Ink)
    }
}

/** 状态只用文字体现（可用=黑字，其余=灰字），不带任何彩色底框。 */
@Composable
private fun ProbeBadge(probe: MirrorProbe?, probing: Boolean) {
    val (text, color) = when {
        probing && probe == null -> "检测中" to RecommendTokens.Muted
        probe is MirrorProbe.Ok && probe.imageOk -> "可用 ${probe.latencyMs}ms" to RecommendTokens.Ink
        probe is MirrorProbe.Ok -> "接口通 · 图片不可达" to RecommendTokens.Muted
        probe == MirrorProbe.Blocked -> "被人机验证拦截" to RecommendTokens.Muted
        probe == MirrorProbe.Down -> "不可达" to RecommendTokens.Muted
        else -> "未检测" to RecommendTokens.Muted
    }
    Copy(text, small = true, color = color, strong = probe is MirrorProbe.Ok && probe.imageOk)
}

@Composable
private fun CustomRow(open: Boolean, pending: BangumiMirror, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (open) RecommendTokens.Soft else if (hover) Color(0xFFFAFAFC) else Color.White, tween(160))
    val border by animateColorAsState(if (open) RecommendTokens.Pink.copy(alpha = .45f) else Tokens.Color.BorderHairline, tween(160))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.Radius.Card)).background(bg)
            .border(1.dp, border, RoundedCornerShape(Tokens.Radius.Card))
            .hoverable(interaction).primaryPress(onToggle)
            .padding(horizontal = Tokens.Space.Lg, vertical = Tokens.Space.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(Tokens.Radius.Control)).background(if (open) Color.White else Tokens.Color.BgSunken), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(ServerIcon, null, Modifier.size(16.dp), tint = if (open) RecommendTokens.Pink else Tokens.Color.TextSecondary)
        }
        Column(Modifier.weight(1f)) {
            Copy("自定义线路", strong = true)
            Copy(if (open) "填写接口与图片地址" else "填入其他镜像的接口地址", small = true, color = RecommendTokens.Muted, maxLines = 1)
        }
        if (pending.id == "custom") androidx.compose.material3.Icon(CheckIcon, null, Modifier.size(16.dp), tint = RecommendTokens.Pink)
    }
}
