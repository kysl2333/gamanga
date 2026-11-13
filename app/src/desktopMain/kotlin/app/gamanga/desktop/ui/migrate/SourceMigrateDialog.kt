package app.gamanga.desktop.ui.migrate

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamanga.desktop.data.LibraryRepository
import app.gamanga.desktop.data.MigrateCandidate
import app.gamanga.desktop.data.MigrationPlan
import app.gamanga.desktop.data.SourceMigration
import app.gamanga.desktop.model.MangaEntry
import app.gamanga.desktop.model.SourceType
import app.gamanga.desktop.source.browserUrl
import app.gamanga.desktop.source.sourceNameOf
import app.gamanga.desktop.ui.RemoteImage
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 遮罩色：`rgba(0,0,0,.20)`。
 *
 * Tokens 里没有「弹窗遮罩」这一档（`ReaderScrim` 是阅读器深底用的 80% 黑，直接拿来会压死亮色页面），
 * 而本轮不允许改 Tokens，所以按需求原文的 `0x33000000` 就地定义一次。
 * （本文件的色值字面量只允许出现在文件头这三处：遮罩、两层阴影色——都是 Tokens 尚无对应档位的。）
 */
private val ScrimColor = Color(0x33000000)

/** 卡片最大高度：再高在 3:2 窗口里会被裁掉底部按钮。 */
private val CardMaxHeight = 620.dp

/**
 * 弹窗圆角。
 *
 * 提到顶层常量不是为了省一次构造，而是为了让 `shadow` 的 `shape` 参数在重组前后**是同一个值**
 * （`RoundedCornerShape` 的 equals 相同、但顶层常量连 equals 比较都省了），
 * 避免阴影 RenderNode 在每次重组时被判为「需要重建」——那也会让阴影抖一下。
 */
private val DialogShape = RoundedCornerShape(Tokens.Radius.Drawer)

/**
 * 柔和阴影用的两层中性黑。
 *
 * 为什么不直接用纯黑：`Color.Black` 带 100% 不透明度，Compose 会在阴影边缘把不透明度从
 * `1f` 直接落到 `0f`，圆角处就是一条硬边。压到 4%/8% 后边缘过渡落在肉眼不敏感的范围里。
 * Tokens 里没有「阴影色」这一档（`Elevation.ShadowAlpha` 只是个数字，不是 Color），
 * 沿用本文件 [ScrimColor] 的既有做法：就地定义一次、全文件只有这一处。
 */
private val ShadowAmbient = Color(0x0A000000)
private val ShadowSpot = Color(0x14000000)

/**
 * 柔和投影 = 两层 shadow（远景扩散 + 近景接触）。
 *
 * 为什么单层 `shadow(24.dp)` 一定会有硬边：Compose Desktop 的阴影是「按 shape 裁剪出的
 * 模糊矩形」，blur 半径再大，圆角外侧那圈 alpha 也是从 0 陡跳到目标值的——用户截图里
 * 四角的生硬暗边就是这条跳变线。拆成两层的作用：
 * - 远景层（24dp）：只负责「离地」，浓度压到 4%，扩散得开、边界看不出来；
 * - 近景层（6dp）：只负责「贴住」，在边缘 1~2px 内补一点深度，卡片才不像浮在空中。
 *
 * `clip = false` 必须显式给：默认 `clip = elevation > 0.dp` 会把阴影裁进 shape 内部，等于没画。
 * 真正把模糊边缘「收住」的是调用点那条 1dp 的 [Tokens.Color.BorderHairline] 描边——
 * 1px 的清晰边界给了眼睛一个落点，模糊边缘就不会显得发虚发脏。
 */
private fun Modifier.softDialogShadow(shape: Shape): Modifier = this
    .shadow(
        elevation = Tokens.Space.Xxl,
        shape = shape,
        clip = false,
        ambientColor = ShadowAmbient,
        spotColor = ShadowSpot,
    )
    .shadow(
        elevation = 6.dp,
        shape = shape,
        clip = false,
        ambientColor = ShadowAmbient,
        spotColor = ShadowSpot,
    )

/**
 * 手动换源弹窗（roadmap 第 5 节 / F-46）。
 *
 * ## 为什么是「一屏五步」而不是多步向导
 *
 * 用户要的是「清晰明了的路径」。向导会把「为什么换 / 有哪些候选 / 迁过去会怎样」拆成三屏，
 * 用户每步都要点「下一步」才能看到关键信息（迁移预览），反而更难判断。
 * 这里把五步竖着排在一页里：顶部是原因，中间是候选（可滚动），下面是预览和按钮——
 * **看一眼就知道会发生什么**，误操作率更低。
 *
 * ## 为什么默认选中匹配度最高的
 *
 * 绝大多数情况下第一条就是对的；用户只需要在「标题完全相同」明显不对时改选。
 * 默认选中也让迁移预览立刻可见（否则用户不知道下面那块卡片是干什么的）。
 *
 * ## 为什么没有「自动换源」
 *
 * 用户 2026-10-25 明确：检测到源失效**只提示，不自动改数据**。这个弹窗是唯一入口，
 * 必须由用户点「确认迁移」。
 *
 * ## 入口（由壳层接）
 *
 * 1. 书架卡片右键 →「换源…」（`reason = null`）；
 * 2. 详情页「源失效？」提示条 →「换源…」（`reason` = `HTTP 404` / `搜索返回 0 条`）。
 */
@Composable
fun SourceMigrateDialog(
    entry: MangaEntry,
    repository: LibraryRepository,
    /** 当前源为什么不可用（`HTTP 404` / `搜索返回 0 条` / null=用户主动换源）。 */
    reason: String? = null,
    onDismiss: () -> Unit,
    onMigrated: (newLocalId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ---- 入场动画：200ms 淡入 + 0.96→1（UI-SPEC 第 5 节，DrawerEasing） ----
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Tokens.Motion.PageSwitchMs, easing = Tokens.Motion.DrawerEasing),
        label = "migrateAppear",
    )

    // ---- 状态 ----
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<MigrateCandidate>>(emptyList()) }
    var selected by remember { mutableStateOf<MigrateCandidate?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    // ---- 「闪一下」的根因修复（一）：预览卡不要整块消失 ----
    //
    // 改选候选时 `preview` 必须清空（业务语义：旧计划绝不能被「确认迁移」拿去执行），
    // 但如果**画面**也跟着清空，`shownPreview?.let { PreviewCard(...) }` 会把整块卡片从
    // 组合里摘掉：弹窗高度塌一次（候选区 `weight(1f, fill = false)` 立刻长高、底部按钮上移），
    // 等新计划算完再长回来——一次点击换来两趟完整布局跳动，这就是用户看到的「闪」。
    //
    // 所以把「逻辑用的 preview」和「画面用的 shownPreview」拆开：
    // 前者照旧清空（`canConfirm` 依赖它，行为零变化），后者只单向记录最新的一份，用来占住版面。
    var shownPreview by remember { mutableStateOf<Preview?>(null) }

    var planning by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 候选：逐源增量到达（拷贝漫画常最后回来，别让用户对着空列表等 8 秒）
    LaunchedEffect(entry.id) {
        loading = true
        try {
            SourceMigration.findCandidatesFlow(repository, entry).collect { batch ->
                candidates = (candidates + batch)
                    .distinctBy { it.key() }
                    .sortedWith(compareByDescending<MigrateCandidate> { it.score }.thenByDescending { it.chapters.size })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = "搜索候选失败 · 其他源的结果没有拿到 · 关闭后用浏览页手动搜索"
        } finally {
            loading = false
        }
    }

    // 默认选中第一条（匹配度最高）；用户改选后不再被后续批次顶掉
    LaunchedEffect(candidates) {
        val cur = selected
        if (cur == null || candidates.none { it.key() == cur.key() }) selected = candidates.firstOrNull()
    }

    // 选中变化 → 重算预览（读库放 IO，别在主线程查两次 SQLite）
    LaunchedEffect(selected?.key()) {
        val cand = selected
        if (cand == null) {
            preview = null
            return@LaunchedEffect
        }
        planning = true
        // 先清掉旧预览：否则用户改选后、新计划算完前，卡片上还是上一个候选的话数
        preview = null
        preview = withContext(Dispatchers.IO) {
            try {
                val plan = SourceMigration.plan(repository, entry, cand)
                val progress = repository.progressOf(entry.id)
                val oldPos = if (progress == null) -1 else {
                    repository.chaptersOf(entry.id)
                        .sortedBy { it.index }
                        .indexOfFirst { it.id == progress.chapterId }
                }
                Preview(plan, oldPos, progress?.page ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "读取迁移预览失败 · 这本书的旧章节状态没读到 · 重选一次候选"
                null
            }
        }
        planning = false
    }

    val canConfirm = selected != null &&
        selected?.chapters?.isNotEmpty() == true &&
        preview != null &&
        !planning &&
        !migrating

    // 新计划到了才替换画面上那一份；planning 期间什么都不做（宁可多显示 100ms 旧数据，
    // 也不要让版面空一拍）。`preview` 为 null 时这里不会把 shownPreview 也置空。
    LaunchedEffect(preview) {
        preview?.let { shownPreview = it }
    }

    // 重算期间把预览压暗到 40% 表示「这份数据正在更新」，而不是让它消失。
    // 注意这里**不**用 `by` 解构：只在 graphicsLayer 的 lambda 里读 `.value`，
    // 动画每帧只触发重绘，不触发重组——否则 160ms 内整个候选列表会跟着重组十来次，
    // 修完闪烁反而多出掉帧。
    val previewAlpha = animateFloatAsState(
        targetValue = if (preview == null) 0.4f else 1f,
        animationSpec = tween(Tokens.Motion.HoverMs, easing = Tokens.Motion.DrawerEasing),
        label = "migratePreviewAlpha",
    )

    // ---- 结构：遮罩 → 居中白卡（固定头 + 可滚候选 + 固定预览/按钮） ----
    Box(
        Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !migrating,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = appear
                    scaleX = 0.96f + 0.04f * appear
                    scaleY = 0.96f + 0.04f * appear
                }
                .width(560.dp)
                .heightIn(max = CardMaxHeight)
                // 阴影参数全部是常量（shape 走顶层 [DialogShape]）：阴影节点挂在
                // graphicsLayer 之外，入场动画只改变换矩阵，不会重建阴影 RenderNode。
                .softDialogShadow(DialogShape)
                .clip(DialogShape)
                .background(Tokens.Color.BgElevatedSolid)
                // 这条 1dp 不是装饰：它是柔和阴影的「收边」，见 [softDialogShadow] 的说明。
                .border(1.dp, Tokens.Color.BorderHairline, DialogShape)
                // 吃掉卡片内的点击，否则点候选行会穿透到遮罩把弹窗关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            DialogHeader(entry, reason)

            // 候选区（可滚动，占据剩余空间）
            // 行距 8：原来 4 + 行内 8 上下边距，两条候选的选中底色几乎贴在一起，看不出「一条一条」。
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Tokens.Space.Xxl, vertical = Tokens.Space.Lg),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
            ) {
                when {
                    loading && candidates.isEmpty() -> {
                        MixText(
                            text = "正在其他源搜索…",
                            color = Tokens.Color.TextTertiary,
                            style = Tokens.Type.Caption.toTextStyle(),
                        )
                        Spacer(Modifier.height(Tokens.Space.Sm))
                        repeat(3) { SkeletonRow() }
                    }

                    candidates.isEmpty() -> EmptyCandidates(entry)

                    else -> {
                        // 标题与列表之间留 Lg（这里 8 + 外层 spacedBy 的 8 = 16）
                        MixText(
                            text = "其他源上的同一本书 · ${candidates.size} 条",
                            modifier = Modifier.padding(bottom = Tokens.Space.Sm),
                            color = Tokens.Color.TextSecondary,
                            style = Tokens.Type.Caption.toTextStyle(),
                        )
                        // ---- 「闪一下」的根因修复（二）：候选行要有稳定 key ----
                        //
                        // 候选是逐源增量到达的，每批到达都会重新 sort，行序会变。
                        // 普通 Column 里子项按**位置**复用 remember 状态：第 3 行的 `RemoteImage`
                        // 里 `remember(url, headers)` 记的是上一轮第 3 行的图，行序一变就错配，
                        // 封面会短暂回到占位色再重新加载——用户看到的就是「列表闪白」。
                        // 用 `key(源名:sourceId)` 把状态钉在候选身份上，重排只移动节点、不重载图片。
                        candidates.forEach { cand ->
                            key(cand.key()) {
                                CandidateRow(
                                    candidate = cand,
                                    selected = cand.key() == selected?.key(),
                                    onClick = { if (!migrating) selected = cand },
                                )
                            }
                        }
                        // 还有源在搜：列表已经能用，但不让用户以为「就这些了」
                        if (loading) {
                            MixText(
                                text = "正在其他源搜索…",
                                color = Tokens.Color.TextTertiary,
                                style = Tokens.Type.Caption.toTextStyle(),
                            )
                        }
                    }
                }
            }

            // 迁移预览（固定，不随候选列表滚走）
            // 渲染用 shownPreview：见文件上方「闪一下」根因（一）。
            // alpha 只读 `.value`，让压暗动画停在重绘阶段。
            shownPreview?.let { p ->
                PreviewCard(
                    preview = p,
                    modifier = Modifier.graphicsLayer { alpha = previewAlpha.value },
                )
            }

            // 选中了「章节未知」的候选：说清为什么不能点确认
            if (selected != null && selected?.chapters?.isEmpty() == true) {
                MixText(
                    text = "这个源没有返回章节列表 · 迁移后无法阅读 · 换一个候选",
                    modifier = Modifier.padding(horizontal = Tokens.Space.Xxl, vertical = Tokens.Space.Md),
                    color = Tokens.Color.Warning,
                    style = Tokens.Type.Caption.toTextStyle(),
                )
            }

            DialogFooter(
                error = error,
                migrating = migrating,
                canConfirm = canConfirm,
                onCancel = { if (!migrating) onDismiss() },
                onConfirm = {
                    val p = preview
                    if (p == null) {
                        error = "迁移预览没有准备好 · 这次迁移不会执行 · 重选一次候选"
                    } else {
                        migrating = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    Result.success(SourceMigration.execute(repository, p.plan))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Result.failure(e)
                                }
                            }
                            migrating = false
                            result.fold(
                                onSuccess = { newId -> onMigrated(newId) },
                                onFailure = { e ->
                                    // 三段式：发生了什么 + 影响什么 + 一个动作
                                    val detail = e.message?.take(80)?.takeIf { it.isNotBlank() }
                                    error = "迁移没有完成" + (detail?.let { "（$it）" } ?: "") +
                                        " · 这本书的本地记录不完整 · 关闭后从源站重新加入书架"
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

/** 预览需要的额外旧状态（[MigrationPlan] 只带目标侧，旧章节位置由调用方补）。 */
private data class Preview(
    val plan: MigrationPlan,
    /** 旧书读到的章节下标（0-based），-1 = 没有进度。 */
    val oldChapterIndex: Int,
    /** 旧书读到的页（0-based）。 */
    val oldPage: Int,
)

private fun MigrateCandidate.key(): String = "${source.name}:${manga.sourceId}"

// ---- 顶部：书名 + 当前源 + 失败原因 ----

@Composable
private fun DialogHeader(entry: MangaEntry, reason: String?) {
    // 内边距 24：弹窗是「一屏五步」的信息密度，20 会让标题、源名、失败原因挤成一坨
    Column(Modifier.fillMaxWidth().padding(Tokens.Space.Xxl)) {
        MixText(
            text = entry.title,
            color = Tokens.Color.TextPrimary,
            style = Tokens.Type.Title.toTextStyle(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Tokens.Space.Sm))
        MixText(
            text = "当前源：${sourceNameOf(entry.source)}",
            color = Tokens.Color.TextSecondary,
            style = Tokens.Type.Caption.toTextStyle(),
        )
        if (!reason.isNullOrBlank()) {
            Spacer(Modifier.height(Tokens.Space.Xs))
            MixText(
                text = "当前源不可用 · $reason",
                color = Tokens.Color.Danger,
                style = Tokens.Type.Caption.toTextStyle(),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Color.BorderHairline))
}

// ---- 候选行 ----

@Composable
private fun CandidateRow(
    candidate: MigrateCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }

    // ---- 「闪一下」的根因修复（三）：选中态不要硬切 ----
    //
    // 原来 `background(if (selected) AccentSoft else Transparent)` 是**一帧内**从全透明跳到
    // 蓝底，同时标题色也从主文字色跳到强调色。两次颜色突变叠在预览重算的那一帧上，
    // 观感就是「点一下，整行闪一下」。160ms 过渡（Tokens.Motion.HoverMs，与卡片 hover 同档）
    // 后，点击变成一次「渗入」，不会跟后面的内容变化抢注意力。
    val spec = tween<Color>(Tokens.Motion.HoverMs, easing = Tokens.Motion.DrawerEasing)
    val rowBg by animateColorAsState(
        targetValue = if (selected) Tokens.Color.AccentSoft else Color.Transparent,
        animationSpec = spec,
        label = "migrateRowBg",
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) Tokens.Color.Accent else Tokens.Color.TextPrimary,
        animationSpec = spec,
        label = "migrateRowTitle",
    )
    val tagColor by animateColorAsState(
        targetValue = if (selected) Tokens.Color.Accent else Tokens.Color.TextTertiary,
        animationSpec = spec,
        label = "migrateRowTag",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(rowBg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // 纵向 12（原 8）：选中底色是一整块，行高太矮时上下两条会粘在一起
            .padding(horizontal = Tokens.Space.Md, vertical = Tokens.Space.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md),
    ) {
        RemoteImage(
            url = candidate.manga.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp, 54.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Control)),
        )
        // 标题 / 副标题的行距用 Xs，替掉原来的 2dp 碎值
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
            MixText(
                text = candidate.manga.title,
                color = titleColor,
                style = Tokens.Type.Body.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MixText(
                text = metaLine(candidate),
                color = Tokens.Color.TextSecondary,
                style = Tokens.Type.Caption.toTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MixText(
            text = candidate.matchLabel,
            color = tagColor,
            style = Tokens.Type.Caption.toTextStyle(),
            maxLines = 1,
        )
    }
}

/** 「源名 · 作者 · N 话」；章节拉取失败时写「章节未知」。 */
private fun metaLine(candidate: MigrateCandidate): String {
    val author = candidate.manga.author?.takeIf { it.isNotBlank() } ?: "作者未知"
    val chapters = if (candidate.chapters.isEmpty()) "章节未知" else "${candidate.chapters.size} 话"
    return "${sourceNameOf(candidate.source)} · $author · $chapters"
}

// ---- 加载骨架（呼吸式明度循环，不用旋转圈：UI-SPEC 第 5 节禁止旋转 loading） ----

@Composable
private fun SkeletonRow() {
    val transition = rememberInfiniteTransition(label = "migrateSkeleton")
    val breathe by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Tokens.Motion.SkeletonMs / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "migrateSkeletonAlpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = breathe }
            // 与 CandidateRow 的纵向内边距对齐：骨架行和真行切换时高度不跳
            .padding(vertical = Tokens.Space.Md),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Md),
    ) {
        Box(
            Modifier
                .size(40.dp, 54.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Control))
                .background(Tokens.Color.BgSunken),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(Tokens.Space.Md)
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.BgSunken),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(Tokens.Space.Sm)
                    .clip(RoundedCornerShape(Tokens.Radius.Capsule))
                    .background(Tokens.Color.BgSunken),
            )
        }
    }
}

// ---- 空态：给出「在浏览器打开」的出路 ----

@Composable
private fun EmptyCandidates(entry: MangaEntry) {
    val canBrowse = remember(entry.source) {
        // 未接入的源（Komga）拼出来的地址是兜底首页，点开只会去错地方——不放按钮
        entry.source in setOf(
            SourceType.KOMIIC,
            SourceType.GODA,
            SourceType.MANGABZ,
            SourceType.COPYMANGA,
        ) && runCatching { java.awt.Desktop.isDesktopSupported() }.getOrDefault(false)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm)) {
        MixText(
            text = "其他源没找到这本书",
            color = Tokens.Color.TextSecondary,
            style = Tokens.Type.Body.toTextStyle(),
        )
        if (canBrowse) {
            DialogButton(
                label = "在浏览器打开",
                primary = false,
                enabled = true,
                onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop()
                            .browse(java.net.URI(browserUrl(entry.source, entry.sourceMangaId)))
                    }
                },
            )
        }
    }
}

// ---- 迁移预览 ----

@Composable
private fun PreviewCard(preview: Preview, modifier: Modifier = Modifier) {
    val plan = preview.plan
    Column(
        Modifier
            .fillMaxWidth()
            .then(modifier)
            .padding(horizontal = Tokens.Space.Xxl)
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(Tokens.Color.BgSunken)
            .padding(Tokens.Space.Md),
        // 标题 / 新旧对比 / 说明三块之间 8，原来 4 太挤，三行像一段连续文字
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
    ) {
        MixText(
            text = "迁移预览",
            color = Tokens.Color.TextPrimary,
            style = Tokens.Type.Subtitle.toTextStyle(),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Sm),
        ) {
            Column(Modifier.weight(1f)) {
                MixText(
                    text = "旧 · ${plan.fromChapterCount} 话",
                    color = Tokens.Color.TextSecondary,
                    style = Tokens.Type.Caption.toTextStyle(),
                )
                MixText(
                    text = if (preview.oldChapterIndex >= 0) {
                        "读到第 ${preview.oldChapterIndex + 1} 话第 ${preview.oldPage + 1} 页"
                    } else {
                        "没有阅读进度"
                    },
                    color = Tokens.Color.TextPrimary,
                    style = Tokens.Type.Body.toTextStyle(),
                )
            }
            MixText(
                text = "→",
                color = Tokens.Color.TextTertiary,
                style = Tokens.Type.Body.toTextStyle(),
            )
            Column(Modifier.weight(1f)) {
                MixText(
                    text = "新 · ${plan.to.chapters.size} 话",
                    color = Tokens.Color.TextSecondary,
                    style = Tokens.Type.Caption.toTextStyle(),
                )
                MixText(
                    text = if (plan.alignedChapterIndex >= 0) {
                        "对齐到第 ${plan.alignedChapterIndex + 1} 话第 ${plan.alignedPage + 1} 页"
                    } else {
                        "从第一话开始"
                    },
                    color = Tokens.Color.TextPrimary,
                    style = Tokens.Type.Body.toTextStyle(),
                )
            }
        }
        MixText(
            text = plan.note,
            color = Tokens.Color.TextTertiary,
            style = Tokens.Type.Caption.toTextStyle(),
        )
    }
}

// ---- 底部：错误行 + 取消 / 确认 ----

@Composable
private fun DialogFooter(
    error: String?,
    migrating: Boolean,
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(Tokens.Space.Xxl)) {
        if (error != null) {
            MixText(
                text = error,
                color = Tokens.Color.Danger,
                style = Tokens.Type.Caption.toTextStyle(),
            )
            // 错误文案与按钮之间 12：错误是两行的时候贴太近会读成按钮的说明
            Spacer(Modifier.height(Tokens.Space.Md))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogButton(
                label = "取消",
                primary = false,
                enabled = !migrating,
                onClick = onCancel,
            )
            Spacer(Modifier.width(Tokens.Space.Sm))
            DialogButton(
                label = if (migrating) "迁移中…" else "确认迁移",
                primary = true,
                enabled = canConfirm,
                onClick = onConfirm,
            )
        }
    }
}

/** 弹窗按钮：主按钮实心强调色，次按钮浅灰底；禁用时整体降透明度且不接收点击。 */
@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.Control)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (primary) Tokens.Color.Accent else Tokens.Color.BgSunken)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Lg, vertical = Tokens.Space.Sm),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            text = label,
            color = if (primary) Tokens.Color.OnAccent else Tokens.Color.TextPrimary,
            style = Tokens.Type.Body.toTextStyle(),
        )
    }
}
