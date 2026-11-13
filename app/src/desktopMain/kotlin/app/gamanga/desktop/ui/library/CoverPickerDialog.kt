package app.gamanga.desktop.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamanga.desktop.data.AppGraph
import app.gamanga.desktop.ui.shell.DockIcons
import app.gamanga.desktop.ui.theme.MixText
import app.gamanga.desktop.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 自定义封面的选图弹窗（2026-10-27 第五轮）。
 *
 * 用户原话：「自定义封面 2 种方式，一种是网络链接下载图片，一种是直接从本地文件中选择，
 * 现在只有本地图片选择，本地选择框使用现代的框」。
 *
 * ## 为什么自己画一个文件选择框
 *
 * 之前用的是 `JFileChooser`（系统 LAF）。问题不在功能，在**观感**：
 * 它长着 Windows 95 的树形控件和灰按钮，弹在满屏圆角白卡的界面中间，像另一个程序。
 * 这里按项目自己的语言重做：白卡 + `Radius.Drawer` + 双层柔化阴影 + 令牌间距，
 * 左侧常用位置、中间文件列表、右侧预览 —— 和书架/详情同一套观感。
 *
 * ## 两种来源共用同一个「确定」
 *
 * 顶部的分段开关只切**取图方式**，下面的「使用这张图」按钮对两种方式都成立：
 * 本地选中一个文件 → 交文件；网络填了链接 → 交链接。这样用户不用记「哪种方式该点哪个按钮」。
 *
 * ## 边界
 *
 * - 只列 jpg / jpeg / png / webp / bmp（与旧实现一致）：封面最终会被 Skia 解码，
 *   列一堆解不出来的格式只是制造失败；
 * - 预览图**超过 24MB 不解码**：一张几百 MB 的 TIFF 会直接把 UI 线程卡死；
 * - 所有文件系统调用都 `runCatching`：目录没权限 / 盘符断开时给空列表，不崩。
 */
@Composable
fun CoverPickerDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPickFile: (File) -> Unit,
    onPickUrl: (String) -> Unit,
) {
    // 遮罩：0x33 黑，与换源弹窗同一档（Tokens 里没有「弹窗遮罩」这一档）
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // **尺寸跟着窗口走**（2026-10-27 第六轮修）：原来写死 880×520，
        // 窗口小于这个尺寸时卡片会被窗口边缘裁掉——用户看到的是「三栏只剩半截、点不到文件」。
        val dialogWidth = minOf(880.dp, maxWidth - Tokens.Space.Xxl * 2)
        val dialogHeight = minOf(520.dp, maxHeight - Tokens.Space.Xxl * 2)
        Box(
            Modifier
                // **必须 enabled = true**（第六轮修）：上一版写的是 enabled = false，
                // 那个 clickable 完全不参与事件分发 → 点在卡片空白处（预览区、路径栏、栏间留白）
                // 会穿透到遮罩层，把整个弹窗关掉。用户看到的就是「刚点两下就没了，根本没法浏览」。
                // 现在卡片自己吃掉所有点击（onClick 空实现），只有遮罩区域才关窗。
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
                .border(1.dp, Tokens.Color.BorderHairline, RoundedCornerShape(Tokens.Radius.Drawer))
                .padding(Tokens.Space.Xl),
        ) {
            PickerBody(
                busy = busy,
                error = error,
                onDismiss = onDismiss,
                onPickFile = onPickFile,
                onPickUrl = onPickUrl,
            )
        }
    }
}

@Composable
private fun PickerBody(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPickFile: (File) -> Unit,
    onPickUrl: (String) -> Unit,
) {
    var urlMode by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf(initialCoverDir()) }
    var picked by remember { mutableStateOf<File?>(null) }

    val entries = remember(dir) { listCoverEntries(dir) }
    val locations = remember { quickLocations() }

    // 记住这次浏览到哪了：下次打开直接回到同一个目录。
    // 用户 2026-10-27 第六轮反馈「本地文件并不能浏览图片」——一半原因是每次打开都回到
    // 「图片」目录，而他的图在别的盘，每次都得从头点一遍，体感就是「根本不能浏览」。
    LaunchedEffect(dir.absolutePath) {
        AppGraph.libraryRepository.setPrefAsync(CoverLastDirKey, dir.absolutePath)
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 标题行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            MixText(
                text = "自定义封面",
                style = Tokens.Type.Title.toTextStyle().copy(fontWeight = FontWeight.Medium),
                color = Tokens.Color.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (busy) {
                MixText(
                    text = "处理中…",
                    style = Tokens.Type.Caption.toTextStyle(),
                    color = Tokens.Color.TextTertiary,
                )
                Spacer(Modifier.width(Tokens.Space.Sm))
            }
            IconCircleButton(
                enabled = !busy,
                onClick = onDismiss,
            ) {
                androidx.compose.foundation.Image(
                    imageVector = DockIcons.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Spacer(Modifier.height(Tokens.Space.Lg))

        // ---- 取图方式：分段开关 ----
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.Xs)) {
            SegmentTab("本地文件", selected = !urlMode, enabled = !busy) { urlMode = false }
            SegmentTab("网络链接", selected = urlMode, enabled = !busy) { urlMode = true }
        }

        Spacer(Modifier.height(Tokens.Space.Lg))

        if (urlMode) {
            UrlPane(
                url = url,
                onUrlChange = { url = it },
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(Modifier.weight(1f)) {
                // 左：常用位置
                Column(
                    Modifier
                        .width(168.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Tokens.Radius.Control))
                        .background(Tokens.Color.BgSunken)
                        .padding(vertical = Tokens.Space.Sm),
                ) {
                    locations.forEach { (label, file) ->
                        LocationRow(
                            label = label,
                            active = file.absolutePath == dir.absolutePath,
                        ) {
                            dir = file
                            picked = null
                        }
                    }
                }
                Spacer(Modifier.width(Tokens.Space.Lg))

                // 中：当前目录 + 文件列表
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PathBar(
                        dir = dir,
                        count = entries.count { it.isFile },
                        onUp = {
                            dir.parentFile?.let {
                                dir = it
                                picked = null
                            }
                        },
                        // 兜底出口：自绘文件列表再全，也可能遇到「某些盘符列不出来 / 网络位置很慢」，
                        // 系统文件框是用户熟悉的老朋友，留一个按钮永远不会卡死在这。
                        onNativePick = {
                            nativePickImage(dir)?.let(onPickFile)
                        },
                    )
                    Spacer(Modifier.height(Tokens.Space.Sm))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(Tokens.Radius.Control))
                            .background(Tokens.Color.BgSunken),
                    ) {
                        if (entries.isEmpty()) {
                            // 空目录要给「下一步点哪」：只说「没有图片」用户会以为弹窗坏了
                            Column(
                                Modifier.align(Alignment.Center).padding(Tokens.Space.Md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MixText(
                                    text = "这个目录里没有可用的图片",
                                    style = Tokens.Type.Caption.toTextStyle(),
                                    color = Tokens.Color.TextSecondary,
                                )
                                Spacer(Modifier.height(Tokens.Space.Xs))
                                MixText(
                                    text = "点左边换个位置，或点上面「系统文件框…」",
                                    style = Tokens.Type.Caption.toTextStyle(),
                                    color = Tokens.Color.TextTertiary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize().padding(Tokens.Space.Xs)) {
                                items(entries, key = { it.absolutePath }) { f ->
                                    EntryRow(
                                        file = f,
                                        selected = picked?.absolutePath == f.absolutePath,
                                        onOpen = {
                                            if (f.isDirectory) {
                                                dir = f
                                                picked = null
                                            } else {
                                                picked = f
                                            }
                                        },
                                        onConfirm = {
                                            if (f.isFile) onPickFile(f)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(Tokens.Space.Lg))

                // 右：预览
                PreviewPane(file = picked, modifier = Modifier.width(200.dp).fillMaxHeight())
            }
        }

        if (error != null) {
            Spacer(Modifier.height(Tokens.Space.Sm))
            MixText(
                text = error,
                style = Tokens.Type.Caption.toTextStyle(),
                color = Tokens.Color.Danger,
            )
        }

        Spacer(Modifier.height(Tokens.Space.Lg))

        // ---- 底部动作 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            MixText(
                text = when {
                    urlMode -> "粘贴图片直链（http/https），支持 jpg / png / webp"
                    picked != null -> picked!!.name
                    else -> "选择一个图片文件，或双击直接使用"
                },
                style = Tokens.Type.Caption.toTextStyle(),
                color = Tokens.Color.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Tokens.Space.Md))
            DialogButton(text = "取消", primary = false, enabled = !busy, onClick = onDismiss)
            Spacer(Modifier.width(Tokens.Space.Sm))
            DialogButton(
                text = if (urlMode) "下载并使用" else "使用这张图",
                primary = true,
                enabled = !busy && if (urlMode) url.isNotBlank() else picked != null,
                onClick = {
                    if (urlMode) onPickUrl(url.trim()) else picked?.let(onPickFile)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 子件

@Composable
private fun SegmentTab(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Tokens.Color.AccentSoft
            hovered -> Tokens.Color.BgSunken
            else -> Color.Transparent
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "segBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Tokens.Color.Accent else Tokens.Color.TextSecondary,
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "segFg",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Lg - Tokens.Space.Xs, vertical = Tokens.Space.Sm),
    ) {
        MixText(
            text = label,
            style = Tokens.Type.Body.toTextStyle()
                .copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = fg,
        )
    }
}

@Composable
private fun LocationRow(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            active -> Tokens.Color.BgElevatedSolid
            hovered -> Tokens.Color.BgElevatedSolid.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "locBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.Sm, vertical = 2.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Tokens.Space.Sm, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderGlyph(active = active)
        Spacer(Modifier.width(Tokens.Space.Sm))
        MixText(
            text = label,
            style = Tokens.Type.Caption.toTextStyle(),
            color = if (active) Tokens.Color.TextPrimary else Tokens.Color.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PathBar(
    dir: File,
    count: Int,
    onUp: () -> Unit,
    onNativePick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextActionSmall(text = "上一级", enabled = dir.parentFile != null, onClick = onUp)
        Spacer(Modifier.width(Tokens.Space.Sm))
        MixText(
            text = dir.absolutePath,
            style = Tokens.Type.Caption.toTextStyle(),
            color = Tokens.Color.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Spacer(Modifier.width(Tokens.Space.Sm))
            MixText(
                text = "$count 张图",
                style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                color = Tokens.Color.TextTertiary,
            )
        }
        Spacer(Modifier.width(Tokens.Space.Sm))
        TextActionSmall(text = "系统文件框…", enabled = true, onClick = onNativePick)
    }
}

@Composable
private fun EntryRow(
    file: File,
    selected: Boolean,
    onOpen: () -> Unit,
    onConfirm: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Tokens.Color.AccentSoft
            hovered -> Tokens.Color.BgElevatedSolid
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "entryBg",
    )
    /**
     * 上一次点击的时间戳（判双击用）。
     *
     * **为什么不用 `detectTapGestures(onDoubleTap)`**（2026-10-28 R-137）：
     * 它内部第一件事就是 `awaitFirstDown()` + `down.consume()`，而这个手势检测器挂在
     * `clickable` 的**内层**（Modifier 链后声明者更靠近内容，Main pass 先收到事件）——
     * 于是 down 被它吃掉，外层 `clickable` 的单击**永远不触发**。
     * 表现就是用户说的「无法浏览图片」：点目录不进去、点文件不选中，
     * 只能盯着起始目录那一屏，什么也翻不了。
     *
     * 现在改成「在 onClick 里比时间戳」：单击照常走 clickable，连点两下且是文件 → 直接用。
     */
    var lastClickAt by remember(file.absolutePath) { mutableStateOf(0L) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    val now = System.currentTimeMillis()
                    val doubleClick = now - lastClickAt < DoubleClickWindowMs
                    lastClickAt = now
                    // 双击文件 = 直接用；双击目录 / 两次点击隔太久 = 常规打开
                    if (doubleClick && file.isFile) onConfirm() else onOpen()
                },
            )
            .padding(horizontal = Tokens.Space.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.isDirectory) {
            FolderGlyph(active = false)
        } else {
            androidx.compose.foundation.Image(
                imageVector = DockIcons.Picture,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Tokens.Color.TextTertiary),
            )
        }
        Spacer(Modifier.width(Tokens.Space.Sm))
        MixText(
            text = file.name,
            style = Tokens.Type.Caption.toTextStyle(),
            color = if (selected) Tokens.Color.Accent else Tokens.Color.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.isFile) {
            MixText(
                text = humanSize(file.length()),
                style = Tokens.Type.Micro.toTextStyle().copy(fontFeatureSettings = "tnum"),
                color = Tokens.Color.TextTertiary,
            )
        }
    }
}

@Composable
private fun PreviewPane(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file?.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val f = file ?: return@withContext null
            // 太大不解码：几百 MB 的图会把 UI 线程卡死
            if (!f.isFile || f.length() > 24L * 1024 * 1024) return@withContext null
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(Tokens.Color.BgSunken),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(Tokens.Space.Sm),
            )
            file != null -> MixText(
                text = "无法预览这张图",
                style = Tokens.Type.Caption.toTextStyle(),
                color = Tokens.Color.TextTertiary,
            )
            else -> MixText(
                text = "选中一张图\n在这里预览",
                style = Tokens.Type.Caption.toTextStyle(),
                color = Tokens.Color.TextTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UrlPane(url: String, onUrlChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        MixText(
            text = "粘贴图片直链。下载后按内容落盘，不会引用原链接 —— 原站删图也不影响你的封面。",
            style = Tokens.Type.Caption.toTextStyle(),
            color = Tokens.Color.TextTertiary,
        )
        Spacer(Modifier.height(Tokens.Space.Md))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            placeholder = {
                Text("https://example.com/cover.jpg", fontSize = 13.sp)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FolderGlyph(active: Boolean) {
    val tint = if (active) Tokens.Color.Accent else Tokens.Color.TextTertiary
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        // 一个「带标签」的文件夹：上面一小条 + 下面主体，纯几何、不引图标库
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, h * 0.22f),
            size = Size(w * 0.52f, h * 0.2f),
            cornerRadius = CornerRadius(w * 0.06f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, h * 0.34f),
            size = Size(w, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.12f),
        )
    }
}

@Composable
private fun IconCircleButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(32.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (hovered) Tokens.Color.AccentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered && enabled) 1.02f else 1f,
        animationSpec = tween(120),
        label = "dlgScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Tokens.Color.BgSunken
            primary -> Tokens.Color.Accent
            hovered -> Tokens.Color.AccentSoft
            else -> Tokens.Color.BgElevatedSolid
        },
        animationSpec = tween(Tokens.Motion.HoverMs),
        label = "dlgBg",
    )
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(36.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(bg)
            .border(
                1.dp,
                if (primary && enabled) Color.Transparent else Tokens.Color.BorderHairline,
                RoundedCornerShape(Tokens.Radius.Control),
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Lg),
        contentAlignment = Alignment.Center,
    ) {
        MixText(
            text = text,
            style = Tokens.Type.Body.toTextStyle().copy(fontWeight = FontWeight.Medium),
            color = when {
                !enabled -> Tokens.Color.TextTertiary
                primary -> Tokens.Color.OnAccent
                else -> Tokens.Color.TextSecondary
            },
        )
    }
}

@Composable
private fun TextActionSmall(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(if (hovered && enabled) Tokens.Color.AccentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Tokens.Space.Sm, vertical = 4.dp),
    ) {
        MixText(
            text = text,
            style = Tokens.Type.Caption.toTextStyle(),
            color = when {
                !enabled -> Tokens.Color.TextTertiary
                hovered -> Tokens.Color.Accent
                else -> Tokens.Color.TextSecondary
            },
        )
    }
}

// ---------------------------------------------------------------- 文件系统

/** 只列这几种：封面最终要被 Skia 解码，列一堆解不出来的格式只是制造失败。 */
private val CoverExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")

/** 记住上次浏览的目录（pref 键）。 */
private const val CoverLastDirKey = "cover.lastDir"

/**
 * 打开弹窗时的起始目录，优先级：
 * **上次用过的目录 → 「图片」→ 「下载」→ 「桌面」→ 主目录**。
 *
 * 每个候选都要求「真的是个能列出来的目录」，否则往下掉一格——
 * 用户机器上 `Desktop` 可能被 OneDrive 改名/搬走（本项目开发机就没有 `~/Desktop`）。
 */
private fun initialCoverDir(): File {
    val home = File(System.getProperty("user.home") ?: ".")
    val remembered = runCatching {
        AppGraph.libraryRepository.getPref(CoverLastDirKey)?.let(::File)
    }.getOrNull()
    val candidates = listOfNotNull(
        remembered,
        File(home, "Pictures"),
        File(home, "Downloads"),
        File(home, "Desktop"),
        home,
    )
    return candidates.firstOrNull { f ->
        runCatching { f.isDirectory && !f.listFiles().isNullOrEmpty() }.getOrDefault(false)
    } ?: candidates.firstOrNull { runCatching { it.isDirectory }.getOrDefault(false) } ?: home
}

/**
 * 系统文件框兜底（2026-10-27 第六轮）：自绘列表列不出来的位置（特殊盘符、网络位置、
 * 权限受限目录），用户至少还能用熟悉的系统框选到图。
 *
 * 只放图片扩展名过滤器，并且**不阻塞**主线程太久——`JFileChooser` 是模态的，
 * 在 Compose 的 AWT 线程上弹是安全的（和旧的 F-150 存图同一个做法）。
 */
private fun nativePickImage(start: File): File? {
    val chooser = javax.swing.JFileChooser(start).apply {
        dialogTitle = "选择封面图片"
        fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = false
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: File): Boolean =
                f.isDirectory || f.extension.lowercase(Locale.ROOT) in CoverExtensions

            override fun getDescription(): String = "图片（jpg / jpeg / png / webp / bmp）"
        }
    }
    val result = chooser.showOpenDialog(null)
    return if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** 常用位置：桌面 / 下载 / 图片 / 文档 / 主目录 + 各盘符。目录不存在就不显示。 */
private fun quickLocations(): List<Pair<String, File>> {
    val home = File(System.getProperty("user.home") ?: ".")
    val named = listOf(
        "桌面" to File(home, "Desktop"),
        "下载" to File(home, "Downloads"),
        "图片" to File(home, "Pictures"),
        "文档" to File(home, "Documents"),
        "主目录" to home,
    ).filter { it.second.isDirectory }
    val roots = runCatching { File.listRoots()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .map { it.path to it }
    return named + roots
}

/**
 * 列目录：**子目录在前、图片在后**，各自按名字排序。
 *
 * 为什么要先目录：用户从「图片」进到某个相册时，同层还可能有几十个子相册；
 * 图片排在前面会让「继续往里走」这个最常见的动作要滚半天。
 */
private fun listCoverEntries(dir: File): List<File> {
    val all = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    val (dirs, files) = all.partition { runCatching { it.isDirectory }.getOrDefault(false) }
    val images = files.filter { f ->
        runCatching {
            f.isFile && f.extension.lowercase(Locale.ROOT) in CoverExtensions
        }.getOrDefault(false)
    }
    val byName = compareBy<File> { it.name.lowercase(Locale.ROOT) }
    return dirs.filter { !it.name.startsWith(".") }.sortedWith(byName) +
        images.filter { !it.name.startsWith(".") }.sortedWith(byName)
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ---------------------------------------------------------------- 双击

/** 双击判定窗口（毫秒）：与系统默认一致，太短用户点不中、太长会误判。 */
private const val DoubleClickWindowMs = 350L
