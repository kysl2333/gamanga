package app.gamanga.desktop.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.backup.MihonCandidate
import app.gamanga.desktop.data.backup.MihonImportChoice
import app.gamanga.desktop.data.backup.MihonImportMode
import app.gamanga.desktop.data.backup.MihonImportProgress
import app.gamanga.desktop.data.backup.MihonImportPreview
import app.gamanga.desktop.data.backup.MihonImportReport
import app.gamanga.desktop.data.backup.MihonImportRow
import app.gamanga.desktop.data.backup.MihonRowStatus
import app.gamanga.desktop.data.backup.includes
import app.gamanga.desktop.data.backup.titleMatchRank
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * mihon 备份导入向导（F-275，2026-10-29）。
 *
 * 用户原话：「mihon 里面应该有很多没办法使用的源的漫画，需要换源和对齐章数的名字而不是第几章，
 * 但是完全搜索不到的或者只能用几个字部分搜索才能搜索到的要实现解决它，
 * 需要导入 mihon 备份后给用户选择还有改别称让用户自己修改的界面」。
 *
 * 所以这一屏做四件事：
 * 1. **列出来**：每部一行，标明「可直接导入 / 需要换源 / 已在书架（只回填进度）」；
 * 2. **能选**：默认只勾「可直接导入 + 已在书架」，认不出源的要用户自己决定；
 * 3. **能改别称**：搜索用名默认是去掉装饰符的标题，用户可以改成自己认得的短词
 *    （超长轻小说标题搜不到就是这个原因，见 `SearchKeywords`）；
 * 4. **能换源**：点「搜索」在全部源里并发找（原词 0 条会自动退让再搜），候选点一下就绑定。
 *
 * 视觉：白卡 + 遮罩，与换源窗口 / 选图弹窗同一档（`Radius.Drawer`、26dp 柔化阴影）。
 *
 * **2026-10-29 返工（R-165 / R-166）**：上一版 `RowState` 的 `selected` / `alias` / `picked`
 * 写成了普通 `var`，不是 Compose 状态 → 点了不重组，表现为「勾选点不动、全选全不选没反应、
 * 滚一下才出现勾」「别称删不掉」。现在三个字段全部 `mutableStateOf`。
 * 搜索也从「串行等五个源」改成流式（`Flow`，谁先回来先显示），按钮里带转圈。
 */
@Composable
fun MihonImportDialog(
    preview: MihonImportPreview,
    busy: Boolean,
    result: MihonImportReport?,
    /** 导入进行中的进度（`done/total` + 当前书名），null = 还没开始（R-172）。 */
    progress: MihonImportProgress? = null,
    onDismiss: () -> Unit,
    onSearch: (alias: String, title: String) -> Flow<MihonCandidate>,
    /** [mode] 由用户在界面上选：只恢复书架 / 书架 + 观看历史（R-171）。 */
    onImport: (List<MihonImportChoice>, MihonImportMode) -> Unit,
    /** 导入进行中点「取消」：掐掉导入作业（R-193，已导入的书保留）。 */
    onCancelImport: () -> Unit = {},
) {
    val rows = remember(preview) {
        mutableStateListOf<RowState>().apply {
            preview.rows.forEach { r ->
                add(
                    RowState(
                        row = r,
                        // 默认勾选：认得出源的和已在书架的；「需要换源」默认不勾——那种要用户先确认
                        selected = r.status != MihonRowStatus.UNKNOWN_SOURCE,
                        alias = r.alias,
                    )
                )
            }
        }
    }
    val scope = rememberCoroutineScope()
    var showOnlyUnresolved by remember { mutableStateOf(false) }
    // R-171：默认「只恢复书架」——用户明确说「大多数人一般都是恢复书架的」
    var mode by remember { mutableStateOf(MihonImportMode.SHELF_ONLY) }
    // R-181（2026-10-30 用户）：「恢复备份还在搜索中时点击导入」会出问题。
    // 每行的搜索是个独立协程，导入开始时它还在往 `state.candidates` 里写；
    // 现在把 job 都留着，点「开始导入」/「取消」时**先全部掐掉**再往下走。
    val searchJobs = remember { mutableMapOf<String, Job>() }
    fun cancelSearches() {
        searchJobs.values.toList().forEach { it.cancel() }
        searchJobs.clear()
        rows.forEach { it.searching = false }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                onClick = {
                    cancelSearches()
                    onDismiss()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val dialogWidth = minOf(760.dp, maxWidth - Tokens.Space.Xxl * 2)
        val dialogHeight = minOf(620.dp, maxHeight - Tokens.Space.Xxl * 2)
        Column(
            Modifier
                // 卡片自己吃掉点击，避免点在空白处穿透到遮罩把窗口关掉（CoverPickerDialog 踩过）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .width(dialogWidth)
                .height(dialogHeight)
                .shadow(
                    elevation = 26.dp,
                    shape = RoundedCornerShape(Tokens.Radius.Drawer),
                    clip = false,
                    ambientColor = Color(0x0A000000),
                    spotColor = Color(0x14000000),
                )
                .clip(RoundedCornerShape(Tokens.Radius.Drawer))
                .background(Tokens.Color.BgElevatedSolid)
                .padding(Tokens.Space.Xxl),
        ) {
            // ---- 标题 + 统计 ----
            // R-178：统计跟着**当前导入范围**走（「只恢复书架」时只数书架里的书），
            // 否则标题写着 40 部、列表里只有 12 行，用户会以为丢数据了
            val modeRows = rows.filter { mode.includes(it.row) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MixText(
                        "导入 mihon 备份",
                        color = Tokens.Color.TextPrimary,
                        fontSize = Tokens.Type.Subtitle.size,
                        style = Tokens.Type.Subtitle.toTextStyle(),
                    )
                    Spacer(Modifier.height(4.dp))
                    MixText(
                        "共 ${modeRows.size} 部 · 可直接导入 " +
                            "${modeRows.count { it.row.status == MihonRowStatus.READY }}" +
                            " · 需要换源 ${modeRows.count { it.row.status == MihonRowStatus.UNKNOWN_SOURCE }}" +
                            " · 已在书架 ${modeRows.count { it.row.status == MihonRowStatus.IN_LIBRARY }}" +
                            " · 备份内 ${modeRows.sumOf { it.row.chapterCount }} 章" +
                            "（已读 ${modeRows.sumOf { it.row.readCount }}）",
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Caption.size,
                        style = Tokens.Type.Caption.toTextStyle(),
                    )
                }
                if (result == null) {
                    SmallAction(if (showOnlyUnresolved) "显示全部" else "只看需要换源") {
                        showOnlyUnresolved = !showOnlyUnresolved
                    }
                }
            }

            Spacer(Modifier.height(Tokens.Space.Md))

            if (result == null) {
                // ---- 导入范围（先问用户，默认只恢复书架）----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip(
                        text = "只恢复书架",
                        selected = mode == MihonImportMode.SHELF_ONLY,
                        enabled = !busy,
                    ) { mode = MihonImportMode.SHELF_ONLY }
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    ModeChip(
                        text = "书架 + 观看历史",
                        selected = mode == MihonImportMode.SHELF_AND_HISTORY,
                        enabled = !busy,
                    ) { mode = MihonImportMode.SHELF_AND_HISTORY }
                    Spacer(Modifier.width(Tokens.Space.Md))
                    MixText(
                        when (mode) {
                            MihonImportMode.SHELF_ONLY ->
                                "只恢复 mihon 书架里的书，连阅读记录一起恢复"
                            MihonImportMode.SHELF_AND_HISTORY ->
                                "连只有阅读记录（不在书架）的书也列出来，一起恢复"
                        },
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Micro.size,
                        style = Tokens.Type.Micro.toTextStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(Tokens.Space.Lg))

            if (result != null) {
                // ---- 完成态：报告 ----
                ImportReport(result)
            } else {
                // ---- 列表 ----
                // R-178：先按导入范围筛（只恢复书架 / 书架 + 历史），再按「只看需要换源」筛
                val visible = modeRows.filter {
                    !showOnlyUnresolved || it.row.status == MihonRowStatus.UNKNOWN_SOURCE
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
                        items(visible, key = { it.row.url }) { state ->
                            ImportRow(
                                state = state,
                                busy = busy,
                                onToggle = { state.selected = !state.selected },
                                onAlias = { state.alias = it },
                                onSearch = {
                                    if (state.searching) return@ImportRow
                                    state.searching = true
                                    state.error = null
                                    state.candidates = emptyList()
                                    state.expanded = false
                                    // R-181：同一行重复点搜索时先掐掉上一次，避免两股流同时写候选
                                    searchJobs.remove(state.row.url)?.cancel()
                                    searchJobs[state.row.url] = scope.launch {
                                        val seen = HashSet<String>()
                                        val acc = ArrayList<MihonCandidate>()
                                        try {
                                            // 流式收集：哪个源先回来就先显示哪条，不用等最慢的源
                                            onSearch(state.alias, state.row.title).collect { c ->
                                                if (seen.add(c.source.name + ":" + c.sourceId)) {
                                                    acc += c
                                                    // R-173：实时按**当前别称**的匹配度排（用户边打字边搜，
                                                    // 用备份原题打分会让改短的别称搜到的结果排到最后）
                                                    // R-179：「越靠前的文字优先级越高」→ 用位置加权的
                                                    // [titleMatchRank]（开头命中 900 > 中间命中 800-位置）
                                                    val kw = state.alias
                                                    state.candidates = acc.sortedWith(
                                                        compareByDescending<MihonCandidate> {
                                                            titleMatchRank(kw, it.title)
                                                        }
                                                            .thenByDescending { it.score }
                                                            .thenBy { it.title.length }
                                                    )
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            // 被「开始导入 / 取消 / 重搜」掐掉：不是错误，别往界面上写红字
                                            throw e
                                        } catch (e: Exception) {
                                            state.error = "搜索失败：${e.message ?: e::class.simpleName}"
                                        } finally {
                                            state.searching = false
                                            searchJobs.remove(state.row.url)
                                        }
                                        if (acc.isEmpty() && state.error == null) {
                                            state.error = "没搜到，换个更短的别称再试"
                                        }
                                    }
                                },
                                onPick = { c ->
                                    state.picked = c
                                    state.selected = true
                                    state.candidates = emptyList()
                                    state.error = null
                                },
                                onExpand = { state.expanded = !state.expanded },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Tokens.Space.Lg))
                if (busy && progress != null) {
                    ImportProgressRow(progress)
                    Spacer(Modifier.height(Tokens.Space.Sm))
                }
                // ---- 底栏 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 批量操作只作用于**当前范围内的行**（用户切到「只恢复书架」时，
                    // 「全选」不该把范围外的书也勾上）
                    val chosen = modeRows.count { it.selected }
                    SmallAction("全选") { modeRows.forEach { it.selected = true } }
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    SmallAction("全不选") { modeRows.forEach { it.selected = false } }
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    SmallAction("只选可直接导入") {
                        modeRows.forEach { it.selected = it.row.status != MihonRowStatus.UNKNOWN_SOURCE }
                    }
                    Spacer(Modifier.weight(1f))
                    MixText(
                        "已选 $chosen 部",
                        color = Tokens.Color.TextSecondary,
                        fontSize = Tokens.Type.Caption.size,
                        style = Tokens.Type.Caption.toTextStyle(),
                    )
                    Spacer(Modifier.width(Tokens.Space.Md))
                    // R-193：导入中「取消」要能真的停下来——导入几十部要几分钟，
                    // 之前这里 `enabled = !busy` 把唯一出口也锁死了（用户体感「按钮点不动」）。
                    SmallAction(
                        text = if (busy) "取消导入" else "取消",
                        enabled = true,
                    ) {
                        if (busy) {
                            onCancelImport()
                        } else {
                            cancelSearches()
                            onDismiss()
                        }
                    }
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    PrimaryAction(
                        text = if (busy) "导入中…" else "开始导入",
                        enabled = !busy && chosen > 0,
                    ) {
                        // R-181：导入前先掐掉所有在跑的搜索——否则它还在往列表里写候选，
                        // 和导入过程抢状态（用户报的就是「搜索中点导入」后的怪现象）
                        cancelSearches()
                        onImport(
                            modeRows.filter { it.selected }.map { s ->
                                MihonImportChoice(
                                    url = s.row.url,
                                    alias = s.alias.trim().ifBlank { s.row.alias },
                                    source = s.picked?.source ?: s.row.source,
                                    sourceId = s.picked?.sourceId ?: s.row.sourceId,
                                    // R-169：名字与封面以新源为准（否则书架里还是备份的旧名字 + 失效封面）
                                    pickedTitle = s.picked?.title,
                                    pickedCover = s.picked?.coverUrl,
                                )
                            },
                            mode,
                        )
                    }
                }
            }

            if (result != null) {
                Spacer(Modifier.height(Tokens.Space.Lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    PrimaryAction(text = "完成", enabled = true) { onDismiss() }
                }
            }
        }
    }
}

/**
 * 一行的可变状态（勾选 / 别称 / 搜索结果 / 已绑定的候选）。
 *
 * ⚠️ 这三个字段**必须是 Compose 状态**：写成普通 `var` 时点勾选、删别称、选候选都不会触发重组，
 * 用户看到的就是「点了没反应，滚一下才更新」（R-165 的原始症状）。
 */
private class RowState(
    val row: MihonImportRow,
    selected: Boolean,
    alias: String,
) {
    var selected by mutableStateOf(selected)
    var alias by mutableStateOf(alias)
    var picked by mutableStateOf<MihonCandidate?>(null)
    var searching by mutableStateOf(false)
    var candidates by mutableStateOf<List<MihonCandidate>>(emptyList())
    var expanded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** 候选列表默认露几条，其余折叠（用户：「显示 4 个其他折叠」→ 4 条 + 一行「显示全部」）。 */
private const val CANDIDATE_PREVIEW = 4

@Composable
private fun ImportRow(
    state: RowState,
    busy: Boolean,
    onToggle: () -> Unit,
    onAlias: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (MihonCandidate) -> Unit,
    onExpand: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(Tokens.Color.BgSunken.copy(alpha = 0.5f))
            .padding(horizontal = Tokens.Space.Md, vertical = Tokens.Space.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckDot(state.selected, enabled = !busy, onClick = onToggle)
            Spacer(Modifier.width(Tokens.Space.Md))
            Column(Modifier.weight(1f)) {
                MixText(
                    state.row.title,
                    color = Tokens.Color.TextPrimary,
                    fontSize = Tokens.Type.Body.size,
                    style = Tokens.Type.Body.toTextStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(state.row.status)
                    Spacer(Modifier.width(Tokens.Space.Sm))
                    MixText(
                        "${state.row.chapterCount} 章 · 已读 ${state.row.readCount}" +
                            (if (state.row.hasCoverImage) " · 含封面图" else "") +
                            (state.picked?.let { " · 已选「${it.title}」" } ?: ""),
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Micro.size,
                        style = Tokens.Type.Micro.toTextStyle(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(Tokens.Space.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 别称输入：搜索用的词，用户自己改（超长标题就靠它救）
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.BgElevatedSolid)
                    .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Capsule))
                    .padding(horizontal = Tokens.Space.Md),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.alias.isEmpty()) {
                    MixText(
                        "搜索用别称（可改）",
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Caption.size,
                        style = Tokens.Type.Caption.toTextStyle(),
                    )
                }
                BasicTextField(
                    value = state.alias,
                    onValueChange = onAlias,
                    singleLine = true,
                    textStyle = Tokens.Type.Caption.toTextStyle().copy(color = Tokens.Color.TextPrimary),
                    cursorBrush = SolidColor(Tokens.Color.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(Tokens.Space.Sm))
            SearchButton(
                searching = state.searching,
                found = state.candidates.size,
                enabled = !busy && !state.searching,
                onClick = onSearch,
            )
        }

        state.error?.let {
            Spacer(Modifier.height(Tokens.Space.Xs))
            MixText(
                it,
                color = Tokens.Color.Danger,
                fontSize = Tokens.Type.Micro.size,
                style = Tokens.Type.Micro.toTextStyle(),
            )
        }

        if (state.candidates.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.Space.Xs))
            val shown = if (state.expanded) state.candidates else state.candidates.take(CANDIDATE_PREVIEW)
            shown.forEach { c -> CandidateRow(c) { onPick(c) } }
            if (state.candidates.size > CANDIDATE_PREVIEW) {
                val rest = state.candidates.size - CANDIDATE_PREVIEW
                SmallAction(if (state.expanded) "收起" else "显示全部 ${state.candidates.size} 个（还有 $rest 个）") {
                    onExpand()
                }
            }
        }
    }
}

/**
 * 「搜索」按钮：搜索中显示转圈 + 已找到的条数（流式，数字会自己涨）。
 *
 * 用户 2026-10-29：「搜索速度太慢，搜索到的优先发出来并在适合的位置放置一个加载圈」。
 * 加载圈放在按钮里——用户视线本来就在这（刚点完），不用另找地方放全局 loading。
 */
@Composable
private fun SearchButton(searching: Boolean, found: Int, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val text = when {
        searching && found > 0 -> "已找到 $found"
        searching -> "搜索中"
        else -> "搜索"
    }
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(
                when {
                    searching -> Tokens.Color.AccentSoft
                    hovered && enabled -> Tokens.Color.AccentSoft
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                if (hovered && enabled || searching) Tokens.Color.Accent.copy(alpha = 0.35f)
                else Tokens.Color.BorderHairline,
                RoundedCornerShape(Tokens.Radius.Capsule),
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            InlineSpinner()
            Spacer(Modifier.width(6.dp))
        }
        MixText(
            text,
            color = if (searching || hovered && enabled) Tokens.Color.Accent else Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Caption.size,
            style = Tokens.Type.Caption.toTextStyle().copy(fontFeatureSettings = "tnum"),
        )
    }
}

/** 导入范围选择胶囊（R-171）：选中 = 强调色底 + 强调色字。 */
@Composable
private fun ModeChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(if (selected) Tokens.Color.Accent else Color.Transparent)
            .border(
                1.dp,
                when {
                    selected -> Color.Transparent
                    hovered && enabled -> Tokens.Color.Accent.copy(alpha = 0.45f)
                    else -> Tokens.Color.BorderHairline
                },
                RoundedCornerShape(Tokens.Radius.Capsule),
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Md),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            text,
            color = when {
                selected -> Tokens.Color.OnAccent
                !enabled -> Tokens.Color.TextTertiary
                else -> Tokens.Color.TextSecondary
            },
            fontSize = Tokens.Type.Caption.size,
            style = Tokens.Type.Caption.toTextStyle(),
        )
    }
}

/**
 * 导入进度（R-172）：用户原话「导入时间慢，不知道在干什么」。
 *
 * 一行「正在导入 3/12：《书名》」+ 一条 4dp 的进度条。导入一部要拉一次源站章节列表，
 * 十几部就是十几秒，没有这个就只能干瞪眼。
 */
@Composable
private fun ImportProgressRow(p: MihonImportProgress) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InlineSpinner(11.dp)
            Spacer(Modifier.width(6.dp))
            MixText(
                "正在导入 ${p.done}/${p.total}：《${p.title}》",
                color = Tokens.Color.TextSecondary,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                .background(Tokens.Color.BgSunken),
        ) {
            val ratio = if (p.total <= 0) 0f else (p.done.toFloat() / p.total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .height(4.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.Accent),
            )
        }
    }
}

/** 12dp 细圈加载指示器（与章节刷新按钮同一套「细弧线转圈」，不用 Material 的粗圆环）。 */
@Composable
private fun InlineSpinner(size: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "searchSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "searchAngle",
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
        drawArc(
            color = Tokens.Color.Accent,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = this.size.minDimension * 0.16f, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun StatusBadge(status: MihonRowStatus) {
    val (text, color) = when (status) {
        MihonRowStatus.READY -> "可直接导入" to Tokens.Color.Success
        MihonRowStatus.IN_LIBRARY -> "已在书架" to Tokens.Color.TextSecondary
        MihonRowStatus.UNKNOWN_SOURCE -> "需要换源" to Tokens.Color.Warning
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        MixText(text, color = color, fontSize = Tokens.Type.Micro.size, style = Tokens.Type.Micro.toTextStyle())
    }
}

@Composable
private fun CheckDot(checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (checked) Tokens.Color.Accent else Tokens.Color.TextTertiary
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) Tokens.Color.Accent else Color.Transparent)
            .border(1.5.dp, tint, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size(11.dp)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.55f)
                    lineTo(size.width * 0.42f, size.height * 0.82f)
                    lineTo(size.width * 0.9f, size.height * 0.2f)
                }
                drawPath(
                    p,
                    color = Tokens.Color.OnAccent,
                    style = Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun ImportReport(result: MihonImportReport) {
    Column(Modifier.fillMaxWidth()) {
        MixText(
            "导入完成",
            color = Tokens.Color.TextPrimary,
            fontSize = Tokens.Type.Subtitle.size,
            style = Tokens.Type.Subtitle.toTextStyle(),
        )
        Spacer(Modifier.height(Tokens.Space.Sm))
        MixText(
            "新入架 ${result.mangaImported} 部（${result.chaptersImported} 章），" +
                "回填观看历史 ${result.historyRestored} 章" +
                (if (result.coversRestored > 0) "，还原封面 ${result.coversRestored} 张" else ""),
            color = Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.toTextStyle(),
        )
        Spacer(Modifier.height(4.dp))
        MixText(
            when (result.mode) {
                MihonImportMode.SHELF_ONLY -> "本次恢复了 mihon 书架里的书（含阅读记录）"
                MihonImportMode.SHELF_AND_HISTORY -> "本次恢复了书架 + 观看历史里的书（含阅读记录）"
            },
            color = Tokens.Color.TextTertiary,
            fontSize = Tokens.Type.Caption.size,
            style = Tokens.Type.Caption.toTextStyle(),
        )
        if (result.skipped.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.Space.Md))
            MixText(
                "跳过 ${result.skipped.size} 部",
                color = Tokens.Color.Warning,
                fontSize = Tokens.Type.Caption.size,
                style = Tokens.Type.Caption.toTextStyle(),
            )
            Spacer(Modifier.height(Tokens.Space.Xs))
            LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(result.skipped) { s ->
                    MixText(
                        "· ${s.title}：${s.reason}",
                        color = Tokens.Color.TextTertiary,
                        fontSize = Tokens.Type.Micro.size,
                        style = Tokens.Type.Micro.toTextStyle(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 文字小按钮：hover 变强调色（与全站 TextAction 同一套观感，但这里是弹窗内部用）。 */
@Composable
private fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    MixText(
        text,
        color = when {
            !enabled -> Tokens.Color.TextTertiary
            hovered -> Tokens.Color.Accent
            else -> Tokens.Color.TextSecondary
        },
        fontSize = Tokens.Type.Caption.size,
        style = Tokens.Type.Caption.toTextStyle(),
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 主按钮：强调色胶囊。 */
@Composable
private fun PrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Capsule))
            .background(if (enabled) Tokens.Color.Accent else Tokens.Color.BgSunken)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Lg),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            text,
            color = if (enabled) Tokens.Color.OnAccent else Tokens.Color.TextTertiary,
            fontSize = Tokens.Type.Body.size,
            style = Tokens.Type.Body.toTextStyle(),
        )
    }
}

/** 候选行：hover 出浅底，点一下绑定这一本。 */
@Composable
private fun CandidateRow(candidate: MihonCandidate, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Tokens.Space.Sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MixText(
            "用这本",
            color = Tokens.Color.Accent,
            fontSize = Tokens.Type.Micro.size,
            style = Tokens.Type.Micro.toTextStyle(),
        )
        Spacer(Modifier.width(Tokens.Space.Sm))
        MixText(
            candidate.label(sourceNameOf(candidate.source)),
            color = Tokens.Color.TextSecondary,
            fontSize = Tokens.Type.Micro.size,
            style = Tokens.Type.Micro.toTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
